/***************************************************************************

 * Copyright (c) 2012-2013 VMware, Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ***************************************************************************/

package com.vmware.bdd.manager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.log4j.Logger;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vmware.bdd.apitypes.ClusterCreate;
import com.vmware.bdd.apitypes.ClusterRead;
import com.vmware.bdd.apitypes.ClusterRead.ClusterStatus;
import com.vmware.bdd.apitypes.DistroRead;
import com.vmware.bdd.apitypes.NetworkRead;
import com.vmware.bdd.apitypes.NodeGroupCreate;
import com.vmware.bdd.apitypes.NodeGroupRead;
import com.vmware.bdd.apitypes.Priority;
import com.vmware.bdd.entity.ClusterEntity;
import com.vmware.bdd.entity.NodeEntity;
import com.vmware.bdd.entity.NodeGroupEntity;
import com.vmware.bdd.exception.BddException;
import com.vmware.bdd.exception.ClusterConfigException;
import com.vmware.bdd.exception.ClusterManagerException;
import com.vmware.bdd.service.IClusteringService;
import com.vmware.bdd.service.job.JobConstants;
import com.vmware.bdd.service.resmgmt.INetworkService;
import com.vmware.bdd.service.resmgmt.IResourceService;
import com.vmware.bdd.specpolicy.ClusterSpecFactory;
import com.vmware.bdd.spectypes.HadoopRole;
import com.vmware.bdd.spectypes.VcCluster;
import com.vmware.bdd.utils.AuAssert;
import com.vmware.bdd.utils.CommonUtil;
import com.vmware.bdd.utils.ValidationUtils;

public class ClusterManager {
   static final Logger logger = Logger.getLogger(ClusterManager.class);
   private ClusterConfigManager clusterConfigMgr;

   private INetworkService networkManager;

   private JobManager jobManager;

   private DistroManager distroManager;

   private IResourceService resMgr;

   private ClusterEntityManager clusterEntityMgr;

   private RackInfoManager rackInfoMgr;

   private IClusteringService clusteringService;

   public JobManager getJobManager() {
      return jobManager;
   }

   public void setJobManager(JobManager jobManager) {
      this.jobManager = jobManager;
   }

   public ClusterConfigManager getClusterConfigMgr() {
      return clusterConfigMgr;
   }

   public void setClusterConfigMgr(ClusterConfigManager clusterConfigMgr) {
      this.clusterConfigMgr = clusterConfigMgr;
   }

   public INetworkService getNetworkManager() {
      return networkManager;
   }

   public void setNetworkManager(INetworkService networkManager) {
      this.networkManager = networkManager;
   }

   public IResourceService getResMgr() {
      return resMgr;
   }

   @Autowired
   public void setResMgr(IResourceService resMgr) {
      this.resMgr = resMgr;
   }

   public ClusterEntityManager getClusterEntityMgr() {
      return clusterEntityMgr;
   }

   @Autowired
   public void setClusterEntityMgr(ClusterEntityManager clusterEntityMgr) {
      this.clusterEntityMgr = clusterEntityMgr;
   }

   public RackInfoManager getRackInfoMgr() {
      return rackInfoMgr;
   }

   @Autowired
   public void setRackInfoMgr(RackInfoManager rackInfoMgr) {
      this.rackInfoMgr = rackInfoMgr;
   }

   public DistroManager getDistroManager() {
      return distroManager;
   }

   @Autowired
   public void setDistroManager(DistroManager distroManager) {
      this.distroManager = distroManager;
   }

   public IClusteringService getClusteringService() {
      return clusteringService;
   }

   @Autowired
   public void setClusteringService(IClusteringService clusteringService) {
      this.clusteringService = clusteringService;
   }

   public Map<String, Object> getClusterConfigManifest(String clusterName,
         List<String> targets, boolean needAllocIp) {
      ClusterCreate clusterConfig =
            clusterConfigMgr.getClusterConfig(clusterName, needAllocIp);
      Map<String, String> cloudProvider = resMgr.getCloudProviderAttributes();
      ClusterRead read = getClusterByName(clusterName, false);
      Map<String, Object> attrs = new HashMap<String, Object>();
      attrs.put("cloud_provider", cloudProvider);
      attrs.put("cluster_definition", clusterConfig);
      if (read != null) {
         attrs.put("cluster_data", read);
      }
      if (targets != null && !targets.isEmpty()) {
         attrs.put("targets", targets);
      }
      return attrs;
   }

   private void writeJsonFile(Map<String, Object> clusterConfig, File file) {
      Gson gson =
            new GsonBuilder().excludeFieldsWithoutExposeAnnotation()
                  .setPrettyPrinting().create();
      String jsonStr = gson.toJson(clusterConfig);

      AuAssert.check(jsonStr != null);
      logger.info("writing cluster manifest in json " + jsonStr + " to file "
            + file);

      FileWriter fileStream = null;

      try {
         fileStream = new FileWriter(file);
         fileStream.write(jsonStr);
      } catch (IOException ex) {
         logger.error(ex.getMessage()
               + "\n failed to write cluster manifest to file " + file);
         throw BddException.INTERNAL(ex, "failed to write cluster manifest");
      } finally {
         if (fileStream != null) {
            try {
               fileStream.close();
            } catch (IOException e) {
               logger.error("falied to close output stream " + fileStream, e);
            }
         }
      }
   }

   public File writeClusterSpecFile(String targetName, File workDir,
         boolean needAllocIp) {
      String clusterName = targetName.split("-")[0];
      String fileName = clusterName + ".json";
      List<String> targets = new ArrayList<String>(1);
      targets.add(targetName);
      Map<String, Object> clusterConfig =
            getClusterConfigManifest(clusterName, targets, needAllocIp);

      File file = new File(workDir, fileName);
      writeJsonFile(clusterConfig, file);

      return file;
   }

   private boolean checkAndResetNodePowerStatusChanged(String clusterName) {
      boolean statusStale = false;

      for (NodeEntity node : clusterEntityMgr.findAllNodes(clusterName)) {
         if (node.isPowerStatusChanged()) {
            switch (node.getStatus()) {
            case VM_READY:
               statusStale = true;
               break;
            case SERVICE_READY:
            case BOOTSTRAP_FAILED:
               node.setPowerStatusChanged(false);
               break;
            }
         }
      }

      return statusStale;
   }

   private void refreshClusterStatus(String clusterName) {
      List<String> clusterNames = new ArrayList<String>();
      clusterNames.add(clusterName);
      refreshClusterStatus(clusterNames);
   }

   private void refreshClusterStatus(List<String> clusterNames) {
      List<Long> jobExecutionIds = new ArrayList<Long>();

      for (String clusterName : clusterNames) {
         Map<String, JobParameter> param = new TreeMap<String, JobParameter>();
         param.put(JobConstants.TIMESTAMP_JOB_PARAM, new JobParameter(
               new Date()));
         param.put(JobConstants.CLUSTER_NAME_JOB_PARAM, new JobParameter(
               clusterName));
         JobParameters jobParameters = new JobParameters(param);
         try {
            long jobExecutionId =
                  jobManager.runJob(JobConstants.QUERY_CLUSTER_JOB_NAME,
                        jobParameters);
            jobExecutionIds.add(jobExecutionId);
         } catch (Exception ex) {
            logger.error("failed to run query cluster job: " + clusterName, ex);
         }
      }
   }

   public ClusterRead getClusterByName(String clusterName, boolean realTime) {
      ClusterEntity cluster = clusterEntityMgr.findByName(clusterName);
      if (cluster == null) {
         throw BddException.NOT_FOUND("cluster", clusterName);
      }

      // return the latest data from db
      if (realTime && cluster.getStatus() == ClusterStatus.RUNNING
      // for not running cluster, we don't sync up status from chef
            && checkAndResetNodePowerStatusChanged(clusterName)) {
         refreshClusterStatus(clusterName);
      }

      return clusterEntityMgr.toClusterRead(clusterName);
   }

   public ClusterCreate getClusterSpec(String clusterName) {
      ClusterCreate spec = clusterConfigMgr.getClusterConfig(clusterName);
      spec.setVcClusters(null);
      spec.setTemplateId(null);
      spec.setDistroMap(null);
      spec.setSharedPattern(null);
      spec.setLocalPattern(null);
      spec.setNetworking(null);
      spec.setRpNames(null);
      spec.setDsNames(null);
      spec.setNetworkName(null);
      spec.setName(null);
      spec.setDistro(null);
      spec.setValidateConfig(null);
      spec.setTopologyPolicy(null);
      spec.setHostToRackMap(null);
      spec.setHttpProxy(null);
      spec.setNoProxy(null);
      spec.setDistroVendor(null);
      spec.setDistroVersion(null);
      NodeGroupCreate[] groups = spec.getNodeGroups();
      if (groups != null) {
         for (NodeGroupCreate group : groups) {
            group.setVcClusters(null);
            group.setGroupType(null);
            group.setRpNames(null);
            group.getStorage().setDsNames(null);
            group.getStorage().setNamePattern(null);
            group.setVmFolderPath(null);
            group.getStorage().setSplitPolicy(null);
            group.getStorage().setControllerType(null);
            group.getStorage().setAllocType(null);
         }
      }
      return spec;
   }

   public List<ClusterRead> getClusters(Boolean realTime) {
      List<ClusterRead> clusters = new ArrayList<ClusterRead>();
      List<ClusterEntity> clusterEntities = clusterEntityMgr.findAllClusters();
      for (ClusterEntity entity : clusterEntities) {
         clusters.add(getClusterByName(entity.getName(), realTime));
      }
      return clusters;
   }

   public Long createCluster(ClusterCreate createSpec) throws Exception {
      if (CommonUtil.isBlank(createSpec.getDistro())) {
         setDefaultDistro(createSpec);
      }
      DistroRead distroRead =
            getDistroManager().getDistroByName(createSpec.getDistro());
      createSpec.setDistroVendor(distroRead.getVendor());
      createSpec.setDistroVersion(distroRead.getVersion());
      ClusterCreate clusterSpec =
            ClusterSpecFactory.getCustomizedSpec(createSpec);

      String name = clusterSpec.getName();
      logger.info("ClusteringService, creating cluster " + name);

      List<String> dsNames = getUsedDS(clusterSpec.getDsNames());
      if (dsNames.isEmpty()) {
         throw ClusterConfigException.NO_RESOURCE_POOL_ADDED();
      }
      List<VcCluster> vcClusters = getUsedVcClusters(clusterSpec.getRpNames());
      if (vcClusters.isEmpty()) {
         throw ClusterConfigException.NO_DATASTORE_ADDED();
      }
      // validate accessibility
      validateDatastore(dsNames, vcClusters);
      validateNetworkAccessibility(name, createSpec.getNetworkName(),
            vcClusters);
      //save configuration into meta-db, and extend configuration using default spec
      clusterConfigMgr.createClusterConfig(clusterSpec);
      clusterEntityMgr.updateClusterStatus(name, ClusterStatus.PROVISIONING);
      Map<String, JobParameter> param = new TreeMap<String, JobParameter>();
      param.put(JobConstants.TIMESTAMP_JOB_PARAM, new JobParameter(new Date()));
      param.put(JobConstants.CLUSTER_SUCCESS_STATUS_JOB_PARAM,
            new JobParameter(ClusterStatus.RUNNING.name()));
      param.put(JobConstants.CLUSTER_FAILURE_STATUS_JOB_PARAM,
            new JobParameter(ClusterStatus.PROVISION_ERROR.name()));
      param.put(JobConstants.CLUSTER_NAME_JOB_PARAM, new JobParameter(
            createSpec.getName()));
      param.put(JobConstants.VERIFY_NODE_STATUS_SCOPE_PARAM, new JobParameter(
            JobConstants.CLUSTER_NODE_SCOPE_VALUE));
      JobParameters jobParameters = new JobParameters(param);
      return jobManager.runJob(JobConstants.CREATE_CLUSTER_JOB_NAME,
            jobParameters);
   }

   private void validateNetworkAccessibility(String clusterName,
         String networkName, List<VcCluster> clusters) {
      if (networkName == null || networkName.isEmpty()) {
         List<NetworkRead> nets =
               clusterConfigMgr.getNetworkMgr().getAllNetworks(false);
         if (nets.isEmpty() || nets.size() > 1) {
            throw ClusterConfigException.NETWORK_IS_NOT_SPECIFIED(nets.size(),
                  clusterName);
         } else {
            networkName = nets.get(0).getName();
         }
      }

      logger.info("start to validate network accessibility.");
      boolean shared = true;
      VcCluster cluster = null;
      for (VcCluster vcCluster : clusters) {
         if (!resMgr.isNetworkSharedInCluster(networkName, vcCluster.getName())) {
            cluster = vcCluster;
            shared = false;
            break;
         }
      }
      if (!shared) {
         throw ClusterConfigException.NETWORK_UNACCESSIBLE(networkName,
               cluster.getName());
      }
   }

   private void validateDatastore(List<String> dsNames, List<VcCluster> clusters) {
      // validate if there is any datastore is accessible by one cluster
      logger.info("start to validate accessibility for datastores: " + dsNames
            + ", and clusters: " + clusters);
      boolean found = false;
      for (String dsName : dsNames) {
         for (VcCluster vcCluster : clusters) {
            if (resMgr.isDatastoreAccessibleByCluster(dsName,
                  vcCluster.getName())) {
               found = true;
               break;
            }
         }
      }
      if (!found) {
         // no any datastore is accessible by specified cluster
         List<String> vcClusterNames = new ArrayList<String>();
         for (VcCluster vcCluster : clusters) {
            vcClusterNames.add(vcCluster.getName());
         }
         throw ClusterConfigException.DATASTORE_UNACCESSIBLE(dsNames,
               vcClusterNames);
      }
   }

   private List<String> getUsedDS(List<String> specifiedDsNames) {
      if (specifiedDsNames == null || specifiedDsNames.isEmpty()) {
         specifiedDsNames = new ArrayList<String>();
         specifiedDsNames.addAll(clusterConfigMgr.getDatastoreMgr()
               .getAllDataStoreName());
      }
      return specifiedDsNames;
   }

   private List<VcCluster> getUsedVcClusters(List<String> rpNames) {
      List<VcCluster> clusters = null;
      if (rpNames == null || rpNames.isEmpty()) {
         clusters = clusterConfigMgr.getRpMgr().getAllVcResourcePool();
      } else {
         clusters = new ArrayList<VcCluster>();
         for (String rpName : rpNames) {
            clusters.addAll(clusterConfigMgr.getRpMgr()
                  .getVcResourcePoolByName(rpName));
         }
      }
      return clusters;
   }

   private void setDefaultDistro(ClusterCreate createSpec) {
      List<DistroRead> distroList = distroManager.getDistros();
      DistroRead[] distros = new DistroRead[distroList.size()];
      createSpec.setDistro(createSpec.getDefaultDistroName(distroList
            .toArray(distros)));
   }

   public Long configCluster(String clusterName, ClusterCreate createSpec)
         throws Exception {
      logger.info("ClusterManager, config cluster " + clusterName);
      ClusterEntity cluster;

      if ((cluster = clusterEntityMgr.findByName(clusterName)) == null) {
         logger.error("cluster " + clusterName + " does not exist");
         throw BddException.NOT_FOUND("cluster", clusterName);
      }

      if (!ClusterStatus.RUNNING.equals(cluster.getStatus())
            && !ClusterStatus.CONFIGURE_ERROR.equals(cluster.getStatus())) {
         logger.error("can not config cluster: " + clusterName + ", "
               + cluster.getStatus());
         throw ClusterManagerException.UPDATE_NOT_ALLOWED_ERROR(clusterName,
               "it should be in RUNNING status");
      }
      clusterConfigMgr.updateAppConfig(clusterName, createSpec);

      Map<String, JobParameter> param = new TreeMap<String, JobParameter>();
      param.put(JobConstants.CLUSTER_NAME_JOB_PARAM, new JobParameter(
            clusterName));
      param.put(JobConstants.TIMESTAMP_JOB_PARAM, new JobParameter(new Date()));
      param.put(JobConstants.CLUSTER_SUCCESS_STATUS_JOB_PARAM,
            new JobParameter(ClusterStatus.RUNNING.name()));
      param.put(JobConstants.CLUSTER_FAILURE_STATUS_JOB_PARAM,
            new JobParameter(ClusterStatus.CONFIGURE_ERROR.name()));
      JobParameters jobParameters = new JobParameters(param);
      clusterEntityMgr.updateClusterStatus(clusterName,
            ClusterStatus.CONFIGURING);
      try {
         return jobManager.runJob(JobConstants.CONFIG_CLUSTER_JOB_NAME,
               jobParameters);
      } catch (Exception e) {
         logger.error("Failed to configure cluster " + clusterName, e);
         clusterEntityMgr.updateClusterStatus(clusterName,
               ClusterStatus.CONFIGURE_ERROR);
         throw e;
      }
   }

   public Long resumeClusterCreation(String clusterName) throws Exception {
      logger.info("ClusterManager, resume cluster creation " + clusterName);

      ClusterEntity cluster = clusterEntityMgr.findByName(clusterName);

      if (cluster == null) {
         logger.error("cluster " + clusterName + " does not exist");
         throw BddException.NOT_FOUND("cluster", clusterName);
      }

      if (cluster.getStatus() != ClusterStatus.PROVISION_ERROR) {
         logger.error("can not resume creation of cluster: " + clusterName
               + ", " + cluster.getStatus());
         throw ClusterManagerException.UPDATE_NOT_ALLOWED_ERROR(clusterName,
               "it should be in PROVISION_ERROR status");
      }
      List<String> dsNames = getUsedDS(cluster.getVcDatastoreNameList());
      if (dsNames.isEmpty()) {
         throw ClusterConfigException.NO_RESOURCE_POOL_ADDED();
      }
      List<VcCluster> vcClusters = getUsedVcClusters(cluster.getVcRpNameList());
      if (vcClusters.isEmpty()) {
         throw ClusterConfigException.NO_DATASTORE_ADDED();
      }
      // validate accessibility
      validateDatastore(dsNames, vcClusters);
      validateNetworkAccessibility(clusterName, cluster.getNetwork().getName(),
            vcClusters);
      Map<String, JobParameter> param = new TreeMap<String, JobParameter>();
      param.put(JobConstants.CLUSTER_NAME_JOB_PARAM, new JobParameter(
            clusterName));
      param.put(JobConstants.TIMESTAMP_JOB_PARAM, new JobParameter(new Date()));
      param.put(JobConstants.CLUSTER_SUCCESS_STATUS_JOB_PARAM,
            new JobParameter(ClusterStatus.RUNNING.name()));
      param.put(JobConstants.CLUSTER_FAILURE_STATUS_JOB_PARAM,
            new JobParameter(ClusterStatus.PROVISION_ERROR.name()));
      JobParameters jobParameters = new JobParameters(param);
      clusterEntityMgr.updateClusterStatus(clusterName,
            ClusterStatus.PROVISIONING);
      try {
         return jobManager.runJob(JobConstants.RESUME_CLUSTER_JOB_NAME,
               jobParameters);
      } catch (Exception e) {
         logger.error("Failed to resume cluster creation for cluster "
               + clusterName, e);
         clusterEntityMgr.updateClusterStatus(clusterName,
               ClusterStatus.PROVISION_ERROR);
         throw e;
      }
   }

   public Long deleteClusterByName(String clusterName) throws Exception {
      logger.info("ClusterManager, deleting cluster " + clusterName);

      ClusterEntity cluster = clusterEntityMgr.findByName(clusterName);

      if (cluster == null) {
         logger.error("cluster " + clusterName + " does not exist");
         throw BddException.NOT_FOUND("cluster", clusterName);
      }

      if (!ClusterStatus.RUNNING.equals(cluster.getStatus())
            && !ClusterStatus.STOPPED.equals(cluster.getStatus())
            && !ClusterStatus.ERROR.equals(cluster.getStatus())
            && !ClusterStatus.PROVISION_ERROR.equals(cluster.getStatus())
            && !ClusterStatus.CONFIGURE_ERROR.equals(cluster.getStatus())) {
         logger.error("cluster: " + clusterName
               + " cannot be deleted, it is in " + cluster.getStatus()
               + " status");
         throw ClusterManagerException.DELETION_NOT_ALLOWED_ERROR(clusterName,
               "it should be in RUNNING/STOPPED/ERROR/PROVISION_ERROR status");
      }
      Map<String, JobParameter> param = new TreeMap<String, JobParameter>();
      param.put(JobConstants.CLUSTER_NAME_JOB_PARAM, new JobParameter(
            clusterName));
      param.put(JobConstants.TIMESTAMP_JOB_PARAM, new JobParameter(new Date()));
      param.put(JobConstants.CLUSTER_FAILURE_STATUS_JOB_PARAM,
            new JobParameter(ClusterStatus.ERROR.name()));
      JobParameters jobParameters = new JobParameters(param);
      clusterEntityMgr.updateClusterStatus(clusterName, ClusterStatus.DELETING);
      try {
         return jobManager.runJob(JobConstants.DELETE_CLUSTER_JOB_NAME,
               jobParameters);
      } catch (Exception e) {
         logger.error("Failed to delete cluster " + clusterName, e);
         cluster = clusterEntityMgr.findByName(clusterName);
         if (cluster != null) {
            clusterEntityMgr.updateClusterStatus(clusterName,
                  ClusterStatus.ERROR);
         }
         throw e;
      }
   }

   public Long startCluster(String clusterName) throws Exception {
      logger.info("ClusterManager, starting cluster " + clusterName);

      ClusterEntity cluster = clusterEntityMgr.findByName(clusterName);
      if (cluster == null) {
         logger.error("cluster " + clusterName + " does not exist");
         throw BddException.NOT_FOUND("cluster", clusterName);
      }

      if (ClusterStatus.RUNNING.equals(cluster.getStatus())) {
         logger.error("cluster " + clusterName + " is running already");
         throw ClusterManagerException.ALREADY_STARTED_ERROR(clusterName);
      }

      if (!ClusterStatus.STOPPED.equals(cluster.getStatus())
            && !ClusterStatus.ERROR.equals(cluster.getStatus())) {
         logger.error("cluster " + clusterName
               + " cannot be started, it is in " + cluster.getStatus()
               + " status");
         throw ClusterManagerException.START_NOT_ALLOWED_ERROR(clusterName,
               "it should be in STOPPED status");
      }
      Map<String, JobParameter> param = new TreeMap<String, JobParameter>();
      param.put(JobConstants.CLUSTER_NAME_JOB_PARAM, new JobParameter(
            clusterName));
      param.put(JobConstants.TIMESTAMP_JOB_PARAM, new JobParameter(new Date()));
      param.put(JobConstants.CLUSTER_SUCCESS_STATUS_JOB_PARAM,
            new JobParameter(ClusterStatus.RUNNING.name()));
      param.put(JobConstants.CLUSTER_FAILURE_STATUS_JOB_PARAM,
            new JobParameter(ClusterStatus.ERROR.name()));
      JobParameters jobParameters = new JobParameters(param);
      clusterEntityMgr.updateClusterStatus(clusterName, ClusterStatus.STARTING);
      try {
         return jobManager.runJob(JobConstants.START_CLUSTER_JOB_NAME,
               jobParameters);
      } catch (Exception e) {
         logger.error("Failed to start cluster " + clusterName, e);
         clusterEntityMgr.updateClusterStatus(clusterName, ClusterStatus.ERROR);
         throw e;
      }
   }

   public Long stopCluster(String clusterName) throws Exception {
      logger.info("ClusterManager, stopping cluster " + clusterName);

      ClusterEntity cluster = clusterEntityMgr.findByName(clusterName);
      if (cluster == null) {
         logger.error("cluster " + clusterName + " does not exist");
         throw BddException.NOT_FOUND("cluster", clusterName);
      }

      if (ClusterStatus.STOPPED.equals(cluster.getStatus())) {
         logger.error("cluster " + clusterName + " is stopped already");
         throw ClusterManagerException.ALREADY_STOPPED_ERROR(clusterName);
      }

      if (!ClusterStatus.RUNNING.equals(cluster.getStatus())
            && !ClusterStatus.ERROR.equals(cluster.getStatus())) {
         logger.error("cluster " + clusterName
               + " cannot be stopped, it is in " + cluster.getStatus()
               + " status");
         throw ClusterManagerException.STOP_NOT_ALLOWED_ERROR(clusterName,
               "it should be in RUNNING status");
      }
      Map<String, JobParameter> param = new TreeMap<String, JobParameter>();
      param.put(JobConstants.CLUSTER_NAME_JOB_PARAM, new JobParameter(
            clusterName));
      param.put(JobConstants.TIMESTAMP_JOB_PARAM, new JobParameter(new Date()));
      param.put(JobConstants.CLUSTER_SUCCESS_STATUS_JOB_PARAM,
            new JobParameter(ClusterStatus.STOPPED.name()));
      param.put(JobConstants.CLUSTER_FAILURE_STATUS_JOB_PARAM,
            new JobParameter(ClusterStatus.ERROR.name()));
      JobParameters jobParameters = new JobParameters(param);
      clusterEntityMgr.updateClusterStatus(clusterName, ClusterStatus.STOPPING);
      try {
         return jobManager.runJob(JobConstants.STOP_CLUSTER_JOB_NAME,
               jobParameters);
      } catch (Exception e) {
         logger.error("Failed to stop cluster " + clusterName, e);
         clusterEntityMgr.updateClusterStatus(clusterName, ClusterStatus.ERROR);
         throw e;
      }
   }

   public Long resizeCluster(String clusterName, String nodeGroupName,
         int instanceNum) throws Exception {
      logger.info("ClusterManager, updating node group " + nodeGroupName
            + " in cluster " + clusterName + " reset instance number to "
            + instanceNum);

      ClusterEntity cluster = clusterEntityMgr.findByName(clusterName);
      if (cluster == null) {
         logger.error("cluster " + clusterName + " does not exist");
         throw BddException.NOT_FOUND("cluster", clusterName);
      }

      List<String> dsNames = getUsedDS(cluster.getVcDatastoreNameList());
      if (dsNames.isEmpty()) {
         throw ClusterConfigException.NO_RESOURCE_POOL_ADDED();
      }

      List<VcCluster> vcClusters = getUsedVcClusters(cluster.getVcRpNameList());
      if (vcClusters.isEmpty()) {
         throw ClusterConfigException.NO_DATASTORE_ADDED();
      }

      // validate accessibility
      validateDatastore(dsNames, vcClusters);
      validateNetworkAccessibility(clusterName, cluster.getNetwork().getName(),
            vcClusters);

      NodeGroupEntity group =
            clusterEntityMgr.findByName(cluster, nodeGroupName);
      if (group == null) {
         logger.error("nodegroup " + nodeGroupName + " of cluster "
               + clusterName + " does not exist");
         throw ClusterManagerException.NODEGROUP_NOT_FOUND_ERROR(nodeGroupName);
      }

      // resize of job tracker and name node is not supported
      List<String> roles = group.getRoleNameList();
      List<String> unsupportedRoles = new ArrayList<String>();
      AuAssert.check(!roles.isEmpty(), "roles should not be empty");
      if (roles.contains(HadoopRole.HADOOP_NAMENODE_ROLE.toString())) {
         unsupportedRoles.add(HadoopRole.HADOOP_NAMENODE_ROLE.toString());
      }
      if (roles.contains(HadoopRole.HADOOP_JOBTRACKER_ROLE.toString())) {
         unsupportedRoles.add(HadoopRole.HADOOP_JOBTRACKER_ROLE.toString());
      }
      if (roles.contains(HadoopRole.ZOOKEEPER_ROLE.toString())) {
         unsupportedRoles.add(HadoopRole.ZOOKEEPER_ROLE.toString());
      }
      if (!unsupportedRoles.isEmpty()) {
         logger.info("can not resize node group with role: " + unsupportedRoles);
         throw ClusterManagerException.ROLES_NOT_SUPPORTED(unsupportedRoles);
      }

      if (!ClusterStatus.RUNNING.equals(cluster.getStatus())) {
         logger.error("cluster " + clusterName
               + " can be resized only in RUNNING status, it is now in "
               + cluster.getStatus() + " status");
         throw ClusterManagerException.UPDATE_NOT_ALLOWED_ERROR(clusterName,
               "it should be in RUNNING status");
      }

      if (instanceNum <= group.getDefineInstanceNum()) {
         logger.error("node group " + nodeGroupName
               + " cannot be shrinked from " + group.getDefineInstanceNum()
               + " to " + instanceNum + " nodes");
         throw ClusterManagerException.SHRINK_OP_NOT_SUPPORTED(nodeGroupName,
               instanceNum, group.getDefineInstanceNum());
      }

      Integer instancePerHost = group.getInstancePerHost();
      if (instancePerHost != null && instanceNum % instancePerHost != 0) {
         throw BddException.INVALID_PARAMETER(
               "instance number",
               new StringBuilder(100).append(instanceNum)
                     .append(": not divisiable by instancePerHost").toString());
      }

      ValidationUtils.validHostNumber(clusterEntityMgr, group, instanceNum);
      ValidationUtils.hasEnoughHost(rackInfoMgr, clusterEntityMgr, group,
            instanceNum);

      int oldInstanceNum = group.getDefineInstanceNum();
      group.setDefineInstanceNum(instanceNum);

      clusterEntityMgr.update(group);

      // create job
      Map<String, JobParameter> param = new TreeMap<String, JobParameter>();
      param.put(JobConstants.CLUSTER_NAME_JOB_PARAM, new JobParameter(
            clusterName));
      param.put(JobConstants.GROUP_NAME_JOB_PARAM, new JobParameter(
            nodeGroupName));
      param.put(JobConstants.GROUP_INSTANCE_NEW_NUMBER_JOB_PARAM,
            new JobParameter(Long.valueOf(instanceNum)));
      param.put(JobConstants.GROUP_INSTANCE_OLD_NUMBER_JOB_PARAM,
            new JobParameter(Long.valueOf(oldInstanceNum)));
      param.put(JobConstants.TIMESTAMP_JOB_PARAM, new JobParameter(new Date()));
      param.put(JobConstants.CLUSTER_SUCCESS_STATUS_JOB_PARAM,
            new JobParameter(ClusterStatus.RUNNING.name()));
      param.put(JobConstants.CLUSTER_FAILURE_STATUS_JOB_PARAM,
            new JobParameter(ClusterStatus.RUNNING.name()));
      param.put(JobConstants.VERIFY_NODE_STATUS_SCOPE_PARAM, new JobParameter(
            JobConstants.GROUP_NODE_SCOPE_VALUE));
      JobParameters jobParameters = new JobParameters(param);
      clusterEntityMgr.updateClusterStatus(clusterName, ClusterStatus.UPDATING);
      try {
         return jobManager.runJob(JobConstants.RESIZE_CLUSTER_JOB_NAME,
               jobParameters);
      } catch (Exception e) {
         logger.error("Failed to resize cluster " + clusterName, e);
         clusterEntityMgr.updateClusterStatus(clusterName,
               ClusterStatus.RUNNING);
         group.setDefineInstanceNum(oldInstanceNum);
         clusterEntityMgr.update(group);
         throw e;
      }
   }

   /**
    * set cluster parameters synchronously
    * @param clusterName
    * @param nodeGroupName
    * @param activeComputeNodeNum
    * @param minComputeNodeNum
    * @param mode
    * @param ioPriority
    * @throws Exception
    */
   @SuppressWarnings("unchecked")
   public  List<String> syncSetParam(String clusterName, String nodeGroupName,
         Integer activeComputeNodeNum, Integer minComputeNodeNum,
         Boolean enableAuto, Priority ioPriority) throws Exception {
      ClusterEntity cluster = clusterEntityMgr.findByName(clusterName);
      ClusterRead clusterRead = getClusterByName(clusterName, false);
      if (cluster == null) {
         logger.error("cluster " + clusterName + " does not exist");
         throw BddException.NOT_FOUND("cluster", clusterName);
      }

      if (enableAuto != null && enableAuto != cluster.getAutomationEnable()) {
      }
      cluster.setAutomationEnable(enableAuto);

      if (minComputeNodeNum != null && minComputeNodeNum != cluster.getVhmMinNum()) {
         cluster.setVhmMinNum(minComputeNodeNum);
      }

      List<String> nodeGroupNames = new ArrayList<String>();
      if (!clusterRead.validateSetManualElasticity(nodeGroupName, nodeGroupNames)) {
         if (nodeGroupName != null) {
            throw BddException.INVALID_PARAMETER("nodeGroup", nodeGroupName);
         } else {
            throw BddException.INVALID_PARAMETER("cluster", clusterName);
         }
      }

      if (nodeGroupName == null && activeComputeNodeNum != null) {
         if (activeComputeNodeNum != cluster.getVhmTargetNum()) {
            cluster.setVhmTargetNum(activeComputeNodeNum);
         }
      }

      clusterEntityMgr.update(cluster);

      if (nodeGroupName != null) {
         NodeGroupEntity ngEntity =
               clusterEntityMgr.findByName(clusterName, nodeGroupName);
         if (activeComputeNodeNum != ngEntity.getVhmTargetNum()) {
            ngEntity.setVhmTargetNum(activeComputeNodeNum);
            clusterEntityMgr.update(ngEntity);
         }
      }

      //update vhm extra config file
      if (!ClusterStatus.RUNNING.equals(cluster.getStatus())
            && !ClusterStatus.STOPPED.equals(cluster.getStatus())) {
         logger.error("cluster " + clusterName
               + " cannot be reconfigured, it is in " + cluster.getStatus()
               + " status");
         throw ClusterManagerException.SET_AUTO_ELASTICITY_NOT_ALLOWED_ERROR(
               clusterName, "it should be in RUNNING or STOPPED status");
      }

      boolean sucess = clusteringService.setAutoElasticity(clusterName, null);
      if (!sucess) {
         throw ClusterManagerException.SET_AUTO_ELASTICITY_NOT_ALLOWED_ERROR(
               clusterName, "failed");
      }

      //update vm ioshares
      if (ioPriority != null) {
         prioritizeCluster(clusterName, nodeGroupName, ioPriority);
      }

      return nodeGroupNames;
   }

   /**
    * set cluster parameters asynchronously
    * @param clusterName
    * @param enableManualElasticity
    * @param nodeGroupName
    * @param activeComputeNodeNum
    * @return
    * @throws Exception
    */
   @SuppressWarnings("unchecked")
   public Long asyncSetParam(String clusterName, String nodeGroupName,
         Integer activeComputeNodeNum, Integer minComputeNodeNum,
         Boolean enableAuto, Priority ioPriority) throws Exception {
      ClusterRead cluster = getClusterByName(clusterName, false);
      // cluster must be running status
      if (!ClusterStatus.RUNNING.equals(cluster.getStatus())) {
         String msg = "Cluster is not running.";
         logger.error(msg);
         throw ClusterManagerException.SET_MANUAL_ELASTICITY_NOT_ALLOWED_ERROR(
               clusterName, msg);
      }

      List<String> nodeGroupNames = syncSetParam(clusterName, nodeGroupName, activeComputeNodeNum,
            minComputeNodeNum, enableAuto, ioPriority);

      // find hadoop job tracker ip
      List<NodeGroupRead> nodeGroups = cluster.getNodeGroups();
      String hadoopJobTrackerIP = "";
      for (NodeGroupRead nodeGroup : nodeGroups) {
         if (nodeGroup.getRoles() != null
               && nodeGroup.getRoles().contains(
                     HadoopRole.HADOOP_JOBTRACKER_ROLE.toString())) {
            AuAssert.check(nodeGroup.getInstanceNum() == 1,
                  "The Jobtracker only support one instance .");
            hadoopJobTrackerIP = nodeGroup.getInstances().get(0).getIp();
            AuAssert.check(!CommonUtil.isBlank(hadoopJobTrackerIP),
                  "Hadoop jobtracker cannot be null");
            break;
         }
      }

      Map<String, JobParameter> param = new TreeMap<String, JobParameter>();
      param.put(JobConstants.TIMESTAMP_JOB_PARAM, new JobParameter(new Date()));
      param.put(JobConstants.CLUSTER_NAME_JOB_PARAM, new JobParameter(
            clusterName));
      param.put(JobConstants.GROUP_NAME_JOB_PARAM,
            new JobParameter(new Gson().toJson(nodeGroupNames)));
      if (activeComputeNodeNum == null) {
         if (nodeGroupName == null) {
            activeComputeNodeNum = cluster.getVhmTargetNum();
         } else {
            activeComputeNodeNum = cluster.getNodeGroupByName(nodeGroupName).getVhmTargetNum();
         }
      }
      param.put(JobConstants.GROUP_ACTIVE_COMPUTE_NODE_NUMBER_JOB_PARAM,
            new JobParameter(Long.valueOf(activeComputeNodeNum)));
      param.put(JobConstants.HADOOP_JOBTRACKER_IP_JOB_PARAM, new JobParameter(
            hadoopJobTrackerIP));
      param.put(JobConstants.CLUSTER_SUCCESS_STATUS_JOB_PARAM,
            new JobParameter(ClusterStatus.RUNNING.name()));
      param.put(JobConstants.CLUSTER_FAILURE_STATUS_JOB_PARAM,
            new JobParameter(ClusterStatus.RUNNING.name()));
      JobParameters jobParameters = new JobParameters(param);
      try {
         clusterEntityMgr.updateClusterStatus(clusterName,
               ClusterStatus.VHM_RUNNING);
         return jobManager.runJob(JobConstants.SET_MANUAL_ELASTICITY_JOB_NAME,
               jobParameters);
      } catch (Exception e) {
         logger.error("Failed to set manual elasticity for cluster "
               + clusterName, e);
         clusterEntityMgr.updateClusterStatus(clusterName,
               ClusterStatus.RUNNING);
         throw e;
      }
   }

   /*
    * Change the disk I/O priority of the cluster or a node group   
    */
   public void prioritizeCluster(String clusterName, String nodeGroupName,
         Priority ioShares) throws Exception {
      ClusterEntity cluster = clusterEntityMgr.findByName(clusterName);
      if (cluster == null) {
         logger.error("cluster " + clusterName + " does not exist");
         throw BddException.NOT_FOUND("cluster", clusterName);
      }

      // do all node groups have the required io share level already?
      boolean diff = false;
      if (nodeGroupName != null && !nodeGroupName.isEmpty()) {
         NodeGroupEntity nodeGroup =
               clusterEntityMgr.findByName(clusterName, nodeGroupName);
         if (nodeGroup == null) {
            logger.error("node group " + nodeGroupName + " does not exist");
            throw BddException.NOT_FOUND("node group", nodeGroupName);
         }
         if (!ioShares.equals(nodeGroup.getIoShares())) {
            diff = true;
         }
      } else {
         for (NodeGroupEntity nodeGroup : clusterEntityMgr
               .findAllGroups(clusterName)) {
            if (!ioShares.equals(nodeGroup.getIoShares())) {
               diff = true;
               break;
            }
         }
      }

      // simply return since all target node groups have the required io share level
      if (!diff)
         return;

      if (nodeGroupName != null && !nodeGroupName.isEmpty()) {
         logger.info("Change all nodes' disk I/O shares to " + ioShares
               + " in the node group " + nodeGroupName + " cluster "
               + clusterName);
      } else {
         logger.info("Change all nodes' disk I/O shares to " + ioShares
               + " in the cluster " + clusterName);
      }

      // cluster must be in RUNNING or STOPPEED status
      if (!ClusterStatus.RUNNING.equals(cluster.getStatus())
            && !ClusterStatus.STOPPED.equals(cluster.getStatus())) {
         String msg = "Cluster is not in RUNNING or STOPPED status.";
         logger.error(msg);
         throw ClusterManagerException.PRIORITIZE_CLUSTER_NOT_ALLOWED_ERROR(
               clusterName, msg);
      }

      // get target nodes
      List<NodeEntity> targetNodes;
      if (nodeGroupName != null && !nodeGroupName.isEmpty()) {
         targetNodes =
               clusterEntityMgr.findAllNodes(clusterName, nodeGroupName);
      } else {
         targetNodes = clusterEntityMgr.findAllNodes(clusterName);
      }

      if (targetNodes.isEmpty()) {
         throw ClusterManagerException.PRIORITIZE_CLUSTER_NOT_ALLOWED_ERROR(
               clusterName, " target node set is empty");
      }

      // call clustering service to set the io shares
      int count =
            clusteringService
                  .configIOShares(clusterName, targetNodes, ioShares);
      logger.info("configured " + count + " nodes' IO share level to "
            + ioShares.toString());

      if (targetNodes.size() != count) {
         throw ClusterManagerException.PRIORITIZE_CLUSTER_FAILED(clusterName,
               count, targetNodes.size());
      }

      // update io shares in db
      if (nodeGroupName != null && !nodeGroupName.isEmpty()) {
         NodeGroupEntity nodeGroup =
               clusterEntityMgr.findByName(clusterName, nodeGroupName);
         nodeGroup.setIoShares(ioShares);
         clusterEntityMgr.update(nodeGroup);
      } else {
         for (NodeGroupEntity nodeGroup : clusterEntityMgr
               .findAllGroups(clusterName)) {
            nodeGroup.setIoShares(ioShares);
            clusterEntityMgr.update(nodeGroup);
         }
      }
   }
}
