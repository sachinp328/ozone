/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.ozone.om.lock;

import com.google.common.base.Preconditions;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.request.file.OMFileRequest;

import java.io.IOException;

import static org.apache.hadoop.ozone.om.lock.OzoneManagerLock.Resource.BUCKET_LOCK;

/**
 * Implementation of OzoneLockStrategy interface. Concrete strategy for OBS
 * KEY_PATH_LOCK.
 */
public class OBSKeyPathLockStrategy implements OzoneLockStrategy {

  @Override
  public boolean acquireWriteLock(OMMetadataManager omMetadataManager,
                                  OzoneManagerLock.Resource resource,
                                  String volumeName, String bucketName,
                                  String keyName) throws IOException {
    boolean acquiredLock;

    acquiredLock = omMetadataManager.getLock().acquireReadLock(BUCKET_LOCK,
        volumeName, bucketName);
    OMFileRequest.validateBucket(omMetadataManager, volumeName, bucketName);

    Preconditions.checkArgument(acquiredLock,
        "BUCKET_LOCK should be acquired!");

    acquiredLock = omMetadataManager.getLock()
        .acquireWriteLock(resource, volumeName, bucketName, keyName);

    return acquiredLock;
  }

  @Override
  public void releaseWriteLock(OMMetadataManager omMetadataManager,
                               OzoneManagerLock.Resource resource,
                               String volumeName, String bucketName,
                               String keyName) throws IOException {
    omMetadataManager.getLock()
        .releaseWriteLock(resource, volumeName, bucketName, keyName);

    omMetadataManager.getLock()
        .releaseReadLock(BUCKET_LOCK, volumeName, bucketName);

    return;
  }

  @Override
  public boolean acquireReadLock(OMMetadataManager omMetadataManager,
                                 OzoneManagerLock.Resource resource,
                                 String volumeName, String bucketName,
                                 String keyName) throws IOException {
    boolean acquiredLock;

    acquiredLock = omMetadataManager.getLock()
        .acquireReadLock(BUCKET_LOCK, volumeName, bucketName);
    OMFileRequest.validateBucket(omMetadataManager, volumeName, bucketName);

    Preconditions.checkArgument(acquiredLock,
        "BUCKET_LOCK should be acquired!");

    acquiredLock = omMetadataManager.getLock()
        .acquireReadLock(resource, volumeName, bucketName, keyName);

    return acquiredLock;
  }

  @Override
  public void releaseReadLock(OMMetadataManager omMetadataManager,
                              OzoneManagerLock.Resource resource,
                              String volumeName, String bucketName,
                              String keyName) throws IOException {
    omMetadataManager.getLock()
        .releaseReadLock(resource, volumeName, bucketName, keyName);

    omMetadataManager.getLock()
        .releaseReadLock(BUCKET_LOCK, volumeName, bucketName);

    return;
  }
}
