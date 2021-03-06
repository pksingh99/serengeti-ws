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

package com.vmware.bdd.manager;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.apache.log4j.Logger;

import com.vmware.bdd.apitypes.ClusterRead;
import com.vmware.bdd.exception.BddException;


/**
 * ElasticityScheduleManager is a thread that monitors the file to set VHM auto
 * elasticity mode. The file is generated by an external scheduling tool (e.g.
 * cron).
 *
 */
public class ElasticityScheduleManager extends Thread {

   private static final Logger logger = Logger
         .getLogger(ElasticityScheduleManager.class);
   private boolean isTerminate = false;
   private ClusterManager clusterMgr;
   private final String filenamePrefix = ".elasticity-";

   public ClusterManager getClusterManager() {
      return clusterMgr;
   }

   public void setClusterManager(ClusterManager clusterMgr) {
      this.clusterMgr = clusterMgr;
   }

   public void run() {
      while (!isTerminate) {
         try {
            File directory = new File("/opt/serengeti/tmp/");
            File[] files = directory.listFiles(new FilenameFilter() {
               public boolean accept(File dir, String name) {
                  if (name.startsWith(filenamePrefix))
                     return true;
                  else
                     return false;
               }
            });
            if (files == null) {
               files = new File[0];
            }
            Arrays.sort(files, new Comparator<File>() {
               public int compare(File f1, File f2) {
                  return Long.valueOf(f1.lastModified()).compareTo(
                        f2.lastModified());
               }
            });
            for (File file : files) {
               String filename = file.getName();
               logger.info("Start to schedule " + filename + ".");

               String[] strs =
                     filename.substring(filenamePrefix.length()).split("\\.");
               if (strs == null || strs.length != 2) {
                  logger.error("Filename " + filename
                        + " is not valid. Should be " + filenamePrefix
                        + "<clusterName>.<nodeCount>");
                  continue;
               }
               String clusterName = strs[0];
               String nodeCountStr = strs[1];

               ClusterRead cluster = null;
               try {
                  cluster = clusterMgr.getClusterByName(clusterName, false);
               } catch (BddException e) {
                  logger.error("Caught BDD Exception during getting cluster.", e);
                  continue;
               }
               if (cluster == null) {
                  logger.error("Cluster " + clusterName + " does not exist.");
                  continue;
               }

               // validate compute only node group
               if (!cluster.validateSetManualElasticity()) {
                  logger.error("Cluster " + clusterName
                        + " does not have compute only node group(s).");
                  continue;
               }

               int nodeCount;
               try {
                  nodeCount = Integer.valueOf(nodeCountStr);
               } catch (NumberFormatException e) {
                  logger.error(nodeCountStr + " is not a valid number.");
                  continue;
               }
               if (nodeCount < -1) {
                  logger.error("Node count " + nodeCountStr + " is not valid.");
                  continue;
               }
               int computeNodeNum = cluster.retrieveComputeNodeNum();
               if (nodeCount > computeNodeNum) {
                  logger.error("minComputeNodeNum and maxComputeNodeNum should be less than or equal to deployed compute only nodes:"
                        + computeNodeNum);
                  continue;
               }


               try {
                  List<String> nodeGroupNames =
                        clusterMgr.syncSetParam(clusterName, null,
                              Integer.valueOf(nodeCount),
                              Integer.valueOf(nodeCount), true, null);
                  logger.info(nodeGroupNames
                        + " has been set to auto elasticity with min and max set to "
                        + nodeCount);
                  file.delete();
               } catch (BddException e) {
                  logger.error(
                        "Caught BDD Exception during scheduling VHM.", e);
               }
            }
         } catch (Exception e) {
            logger.error("Caught an Exception.", e);
         }
         try {
            Thread.sleep(1000);
         } catch (InterruptedException e) {
            logger.warn("Thread interrupt exception received, exit.");
            isTerminate = true;
         }
      }
   }

   public boolean isTerminate() {
      return isTerminate;
   }

   public void setTerminate(boolean isTerminate) {
      this.isTerminate = isTerminate;
   }

   public void shutdown() {
      this.interrupt();
   }

}
