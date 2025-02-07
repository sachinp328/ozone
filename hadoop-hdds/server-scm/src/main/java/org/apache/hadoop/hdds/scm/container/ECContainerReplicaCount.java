/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdds.scm.container;

import org.apache.hadoop.hdds.client.ECReplicationConfig;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeOperationalState.DECOMMISSIONED;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeOperationalState.DECOMMISSIONING;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeOperationalState.ENTERING_MAINTENANCE;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeOperationalState.IN_MAINTENANCE;

/**
 * This class provides a set of methods to test for over / under replication of
 * EC containers, taking into account decommission / maintenance nodes,
 * pending replications, pending deletes and the existing replicas.
 *
 * The intention for this class, is to wrap the logic used to detect over and
 * under replication to allow other areas to easily check the status of a
 * container.
 *
 * For calculating under replication:
 *
 *   * Assume that decommission replicas are already lost, as they
 *     will eventually go away.
 *   * Any pending deletes are treated as if they have deleted
 *   * Pending adds are ignored as they may fail to create.
 *
 * Similar for over replication:
 *
 *   * Assume decommissioned replicas are already lost.
 *   * Pending delete replicas will complete
 *   * Pending adds are ignored as they may not complete.
 *   * Maintenance copies are not considered until they are back to IN_SERVICE
 */

public class ECContainerReplicaCount {

  private final ContainerInfo containerInfo;
  private final ECReplicationConfig repConfig;
  private final List<Integer> pendingAdd;
  private final int remainingMaintenanceRedundancy;
  private final Map<Integer, Integer> healthyIndexes = new HashMap<>();
  private final Map<Integer, Integer> decommissionIndexes = new HashMap<>();
  private final Map<Integer, Integer> maintenanceIndexes = new HashMap<>();

  public ECContainerReplicaCount(ContainerInfo containerInfo,
      Set<ContainerReplica> replicas, List<Integer> indexesPendingAdd,
      List<Integer> indexesPendingDelete, int remainingMaintenanceRedundancy) {
    this.containerInfo = containerInfo;
    this.repConfig = (ECReplicationConfig)containerInfo.getReplicationConfig();
    this.pendingAdd = indexesPendingAdd;
    this.remainingMaintenanceRedundancy
        = Math.min(repConfig.getParity(), remainingMaintenanceRedundancy);

    for (ContainerReplica replica : replicas) {
      HddsProtos.NodeOperationalState state =
          replica.getDatanodeDetails().getPersistedOpState();
      int index = replica.getReplicaIndex();
      ensureIndexWithinBounds(index, "replicaSet");
      if (state == DECOMMISSIONED || state == DECOMMISSIONING) {
        int val = decommissionIndexes.getOrDefault(index, 0);
        decommissionIndexes.put(index, val + 1);
      } else if (state == IN_MAINTENANCE || state == ENTERING_MAINTENANCE) {
        int val = maintenanceIndexes.getOrDefault(index, 0);
        maintenanceIndexes.put(index, val + 1);
      } else {
        int val = healthyIndexes.getOrDefault(index, 0);
        healthyIndexes.put(index, val + 1);
      }
    }
    // Remove the pending delete replicas from the healthy set as we assume they
    // will eventually be removed and reduce the count for this replica. If the
    // count goes to zero, remove it from the map.
    for (Integer i : indexesPendingDelete) {
      ensureIndexWithinBounds(i, "pendingDelete");
      Integer count = healthyIndexes.get(i);
      if (count != null) {
        count = count - 1;
        if (count < 1) {
          healthyIndexes.remove(i);
        } else {
          healthyIndexes.put(i, count);
        }
      }
    }
    // Ensure any pending adds are within bounds
    for (Integer i : pendingAdd) {
      ensureIndexWithinBounds(i, "pendingAdd");
    }
  }

  /**
   * Get a set containing all decommissioning indexes, or an empty set if none
   * are decommissioning. Note it is possible for an index to be
   * decommissioning, healthy and in maintenance, if there are multiple copies
   * of it.
   * @return Set of indexes in decommission
   */
  public Set<Integer> decommissioningIndexes() {
    return decommissionIndexes.keySet();
  }

  /**
   * Get a set containing all maintenance indexes, or an empty set if none are
   * in maintenance. Note it is possible for an index to be
   * decommissioning, healthy and in maintenance, if there are multiple copies
   * of it.
   * @return Set of indexes in maintenance
   */
  public Set<Integer> maintenanceIndexes() {
    return maintenanceIndexes.keySet();
  }

  /**
   * Return true if there are insufficient replicas to recover this container.
   * Ie, less than EC Datanum containers are present.
   * @return True if the container cannot be recovered, false otherwise.
   */
  public boolean unRecoverable() {
    Set<Integer> distinct = new HashSet<>();
    distinct.addAll(healthyIndexes.keySet());
    distinct.addAll(decommissionIndexes.keySet());
    distinct.addAll(maintenanceIndexes.keySet());
    return distinct.size() < repConfig.getData();
  }

  /**
   * Returns an unsorted list of indexes which need additional copies to
   * ensure the container is sufficiently replicated. These missing indexes will
   * not be on maintenance nodes, although they may be on decommissioning nodes.
   * Replicas pending delete are assumed to be removed and any pending add
   * are assume to be created and omitted them from the returned list. This list
   * can be used to determine which replicas must be recovered in a group,
   * assuming the inflight replicas pending add complete successfully.
   * @return List of missing indexes
   */
  public List<Integer> missingNonMaintenanceIndexes() {
    if (isSufficientlyReplicated()) {
      return Collections.emptyList();
    }
    Set<Integer> missing = new HashSet<>();
    for (int i = 1; i <= repConfig.getRequiredNodes(); i++) {
      if (!healthyIndexes.containsKey(i)) {
        missing.add(i);
      }
    }
    // Now we have a list of missing. Remove any pending add as they should
    // eventually recover.
    for (Integer i : pendingAdd) {
      missing.remove(i);
    }
    // Remove any maintenance copies, as they are still available. What remains
    // is the set of indexes we have no copy of, and hence must get re-created
    for (Integer i : maintenanceIndexes.keySet()) {
      missing.remove(i);
    }
    return missing.stream().collect(Collectors.toList());
  }

  /**
   * Returns an unsorted list of replicas that are on a maintenance node, but
   * have no other copies on in_service nodes. This list can be used in
   * conjunction with additionalMaintenanceCopiesNeeded, to select replicas to
   * copy to ensure the maintenance redundancy goal is met.
   * @return
   */
  public List<Integer> maintenanceOnlyIndexes() {
    List<Integer> maintenanceOnly = new ArrayList<>();
    for (Integer i : maintenanceIndexes.keySet()) {
      if (!healthyIndexes.containsKey(i)) {
        maintenanceOnly.add(i);
      }
    }
    return maintenanceOnly;
  }

  /**
   * Get the number of additional replicas needed to make the container
   * sufficiently replicated for maintenance. For EC-3-2, if there is a
   * remainingMaintenanceRedundancy of 1, and two replicas in maintenance,
   * this will return 1, indicating one of the maintenance replicas must be
   * copied to an in-service node to meet the redundancy guarantee.
   * @return
   */
  public int additionalMaintenanceCopiesNeeded() {
    List<Integer> maintenanceOnly = maintenanceOnlyIndexes();
    return Math.max(0, maintenanceOnly.size() - getMaxMaintenance());
  }

  /**
   * If any index has more than one copy that is not in maintenance or
   * decommission, then the container is over replicated. We consider inflight
   * deletes, assuming they will be removed. Inflight adds are ignored until
   * they are actually created.
   * Note it is possible for a container to be both over and under replicated
   * as it could have multiple copies of 1 index, but zero copies of another
   * index.
   * @return True if overReplicated, false otherwise.
   */
  public boolean isOverReplicated() {
    for (Integer count : healthyIndexes.values()) {
      if (count > 1) {
        return true;
      }
    }
    return false;
  }

  /**
   * Return an unsorted list of any replica indexes which have more than one
   * replica and are therefore over-replicated. Maintenance replicas are ignored
   * as if we have excess including maintenance, it may be due to replication
   * which was needed to ensure sufficient redundancy for maintenance.
   * Pending adds are ignored as they may fail to complete.
   * Pending deletes are assumed to complete and any indexes returned from here
   * will have the pending deletes already removed.
   * @return List of indexes which are over-replicated.
   */
  public List<Integer> overReplicatedIndexes() {
    List<Integer> indexes = new ArrayList<>();
    for (Map.Entry<Integer, Integer> entry : healthyIndexes.entrySet()) {
      if (entry.getValue() > 1) {
        indexes.add(entry.getKey());
      }
    }
    return indexes;
  }

  /**
   * The container is sufficiently replicated if the healthy indexes minus any
   * pending deletes give a complete set of container indexes. If not, we must
   * also check the maintenance indexes - the container is still sufficiently
   * replicated if the complete set is made up of healthy + maintenance and
   * there is still sufficient maintenance redundancy.
   * @return True if sufficiently replicated, false otherwise.
   */
  public boolean isSufficientlyReplicated() {
    if (hasFullSetOfIndexes(healthyIndexes)) {
      return true;
    }
    // If we don't have a full healthy set, we could have some maintenance
    // replicas that make up the full set.
    // For maintenance, we must have at least dataNum + maintenance redundancy
    // available.
    if (healthyIndexes.size() <
        repConfig.getData() + remainingMaintenanceRedundancy) {
      return false;
    }
    // Finally, check if the maintenance copies give a full set
    Map<Integer, Integer> healthy = new HashMap<>(healthyIndexes);
    maintenanceIndexes.forEach((k, v) -> healthy.merge(k, v, Integer::sum));
    return hasFullSetOfIndexes(healthy);
  }

  /**
   * Check if there is an entry in the map for all expected replica indexes,
   * and also that the count against each index is greater than zero.
   * @param indexSet A map representing the replica index and count of the
   *                 replicas for that index.
   * @return True if there is a full set of indexes, false otherwise.
   */
  private boolean hasFullSetOfIndexes(Map<Integer, Integer> indexSet) {
    return indexSet.size() == repConfig.getRequiredNodes();
  }

  /**
   * Returns the maximum number of replicas that are allowed to be only on a
   * maintenance node, with no other copies on in-service nodes.
   * @return
   */
  private int getMaxMaintenance() {
    return Math.max(0, repConfig.getParity() - remainingMaintenanceRedundancy);
  }

  /**
   * Validate to ensure that the replia index is between 1 and the max expected
   * replica index for the replication config, eg 5 for 3-2, 9 for 6-3 etc.
   * @param index The replica index to check.
   * @Throws IllegalArgumentException if the index is out of bounds.
   */
  private void ensureIndexWithinBounds(Integer index, String setName) {
    if (index < 1 || index > repConfig.getRequiredNodes()) {
      throw new IllegalArgumentException("Replica Index in " + setName
          + " for containerID " + containerInfo.getContainerID()
          + "must be between 1 and " + repConfig.getRequiredNodes());
    }
  }
}
