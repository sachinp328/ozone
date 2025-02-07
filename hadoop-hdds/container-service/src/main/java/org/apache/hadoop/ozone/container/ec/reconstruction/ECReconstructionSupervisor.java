/*
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
package org.apache.hadoop.ozone.container.ec.reconstruction;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.hadoop.ozone.container.common.impl.ContainerSet;
import org.apache.hadoop.ozone.container.common.statemachine.StateContext;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * This class is to handle all the EC reconstruction tasks to be scheduled as
 * they arrive.
 */
public class ECReconstructionSupervisor {

  private final ContainerSet containerSet;
  private final StateContext context;
  private final ExecutorService executor;

  public ECReconstructionSupervisor(ContainerSet containerSet,
      StateContext context, ExecutorService executor) {
    this.containerSet = containerSet;
    this.context = context;
    this.executor = executor;
  }

  public ECReconstructionSupervisor(ContainerSet containerSet,
      StateContext context, int poolSize) {
    // TODO: ReplicationSupervisor and this class can be refactored to have a
    //  common interface.
    this(containerSet, context,
        new ThreadPoolExecutor(poolSize, poolSize, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadFactoryBuilder().setDaemon(true)
                .setNameFormat("ECContainerReconstructionThread-%d").build()));
  }

  public void stop() {
    try {
      executor.shutdown();
      if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException ie) {
      // Ignore, we don't really care about the failure.
      Thread.currentThread().interrupt();
    }
  }

  public void addTask(ECReconstructionCoordinatorTask task) {
    executor.execute(task);
  }
}
