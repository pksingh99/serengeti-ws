/***************************************************************************
 * Copyright (c) 2015 VMware, Inc. All Rights Reserved.
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
package com.vmware.bdd.service.sp;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import com.vmware.aurora.composition.DiskSchema;
import com.vmware.aurora.composition.DiskSchemaUtil;
import com.vmware.bdd.entity.DiskEntity;
import com.vmware.bdd.exception.ClusterHealServiceException;
import com.vmware.bdd.utils.AuAssert;
import com.vmware.bdd.utils.Constants;
import com.vmware.bdd.utils.VcVmUtil;

import org.apache.log4j.Logger;

import com.vmware.aurora.composition.DiskSchema.Disk;
import com.vmware.aurora.vc.DeviceId;
import com.vmware.aurora.vc.VcCache;
import com.vmware.aurora.vc.VcDatastore;
import com.vmware.aurora.vc.VcHost;
import com.vmware.aurora.vc.VcResourcePool;
import com.vmware.aurora.vc.VcVirtualMachine;
import com.vmware.aurora.vc.VcVirtualMachine.DiskCreateSpec;
import com.vmware.aurora.vc.vcservice.VcContext;
import com.vmware.aurora.vc.vcservice.VcSession;

public class ReplaceVmBadDisksSP implements Callable<Void> {

   private static final Logger logger = Logger.getLogger(ReplaceVmBadDisksSP.class);

   private String vmId;
   private DiskSchema diskSchema;
   private VcResourcePool targetRp;
   private VcDatastore targetDs;
   private List<DiskEntity> badDataDiskEntities;
   private VcVirtualMachine vm;

   public ReplaceVmBadDisksSP(String vmId, DiskSchema diskSchema, VcResourcePool targetRp, VcDatastore targetDs, List<DiskEntity> badDataDiskEntities) {
      this.vmId = vmId;
      this.diskSchema = diskSchema;
      this.targetRp = targetRp;
      this.targetDs = targetDs;
      this.badDataDiskEntities = badDataDiskEntities;
   }

   @Override
   public Void call() throws Exception {

      if (!isVmExisted()) {
         return null;
      }

      shutdownVm();

      detachBadDataDisks();

      replaceVmDataDisks();

      return null;
   }

   private boolean isVmExisted() {
      vm = VcCache.getIgnoreMissing(vmId);
      if (vm == null) {
         logger.info("vm " + vmId + " is not found in VC, ignore this disk fix.");
         return false;
      } else {
         return true;
      }
   }

   private void shutdownVm() {
      VcContext.inVcSessionDo(new VcSession<Void>() {

         @Override
         protected Void body() throws Exception {

            if (vm.isPoweredOn() && !vm.shutdownGuest(Constants.VM_FAST_SHUTDOWN_WAITING_SEC * 1000)) {
               logger.info("shutdown " + vm.getName() + " guest OS failed, power off directly");
               vm.powerOff();
            }

            return null;
         }

         @Override
         protected boolean isTaskSession() {
            return true;
         }

      });
   }

   private void detachBadDataDisks() {
      VcContext.inVcSessionDo(new VcSession<Void>() {

         @Override
         protected Void body() throws Exception {

            String vmName = vm.getName();

            for (DiskEntity badDataDiskEntity : badDataDiskEntities) {
               try {
                  DeviceId deviceId = badDataDiskEntity.getDiskDeviceId();
                  if (vm.isDiskAttached(deviceId)) {
                     vm.detachVirtualDisk(deviceId, true);
                  }
               } catch (Exception e) {
                  throw ClusterHealServiceException.FAILED_TO_DETACH_VIRTUALDISK(badDataDiskEntity.getVmdkPath(), vmName);
               }
            }   return null;
         }

         @Override
         protected boolean isTaskSession() {
            return true;
         }

      });
   }

   private void replaceVmDataDisks() {

      VcContext.inVcSessionDo(new VcSession<Void>() {

         @Override
         protected Void body() throws Exception {

            // Get list of disks to add
            List<VcHost> hostList = new ArrayList<VcHost>();
            HashMap<String, Disk.Operation> diskMap = new HashMap<String, Disk.Operation>();
            List<DiskCreateSpec> addDisks = DiskSchemaUtil.getDisksToAdd(hostList, targetRp, targetDs, diskSchema, diskMap);

            DiskCreateSpec[] tmpAddDisks = addDisks.toArray(new DiskCreateSpec[addDisks.size()]);

            // If current host of VM is not in the list of hosts with access to the
            // datastore(s) for the new disk(s), then migrate the VM first
            if (hostList.size() > 0 && !hostList.contains(vm.getHost())) {
               vm.migrate(hostList.get(0));
            }

            // add the new disks
            vm.changeDisks(null, tmpAddDisks);

            // enalbe disk UUID
            VcVmUtil.enableDiskUUID(vm);

            // update disks to machine id
            Map<String, String> bootupConfigs = vm.getGuestConfigs();
            AuAssert.check(bootupConfigs != null);

            VcVmUtil.addBootupUUID(bootupConfigs);

            // disk fix does support MapR distro, just set this flag to "false"
            bootupConfigs.put(Constants.GUEST_VARIABLE_RESERVE_RAW_DISKS, String.valueOf(false));
            bootupConfigs.put(Constants.GUEST_VARIABLE_VOLUMES, VcVmUtil.getVolumes(vm.getId(), diskSchema.getDisks()));

            vm.setGuestConfigs(bootupConfigs);

            logger.info("Update bootupConfigs to '" + bootupConfigs + "' for node " + vm.getName());

            return null;
         }

         @Override
         protected boolean isTaskSession() {
            return true;
         }

      });
   }

   public VcVirtualMachine getVm() {
      return this.vm;
   }
}