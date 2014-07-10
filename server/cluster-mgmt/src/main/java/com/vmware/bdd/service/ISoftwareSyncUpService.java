/***************************************************************************
 * Copyright (c) 2012-2014 VMware, Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ***************************************************************************/
package com.vmware.bdd.service;

/**
 * Sync up with software manager for service status and configuration
 * @author line
 *
 */
public interface ISoftwareSyncUpService {
   /**
    * Sync up every 5 minutes 
    * @param clusterName
    */
   void syncUp(String clusterName);
   /**
    * Sync up once only
    * @param clusterName
    */
   void syncUpOnce(String clusterName);
   /** 
    * Detect if this cluster is already in sync up queue
    * @param clusterName
    * @return
    */
   boolean isClusterInQueue(String clusterName);
}
