/***************************************************************************
 * Copyright (c) 2012-2015 VMware, Inc. All Rights Reserved.
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
package com.vmware.bdd.apitypes;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.gson.annotations.Expose;

/**
 * Storage get output
 */
public class StorageRead {

   public enum DiskScsiControllerType {
      LSI_CONTROLLER("VirtualLsiLogicController"), PARA_VIRTUAL_CONTROLLER("ParaVirtualSCSIController");
      private String displayName;
      private DiskScsiControllerType(String name) {
         this.displayName = name;
      }
      public String getDisplayName() {
         return displayName;
      }
   }

   public enum DiskType {
      SYSTEM_DISK("root.vmdk", "OS"), SWAP_DISK("swap.vmdk", "SWAP"), DATA_DISK(
            "data.vmdk", "DATA");

      public String diskName;
      public String type;

      private DiskType(String diskName, String type) {
         this.diskName = diskName;
         this.type = type;
      }

      public String getDiskName() {
         return diskName;
      }

      public String getType() {
         return type;
      }

      public static DiskType getDiskType(String type) {
         if (SYSTEM_DISK.getType().equals(type)) {
            return SYSTEM_DISK;
         } else if (SWAP_DISK.getType().equals(type)) {
            return SWAP_DISK;
         } else if (DATA_DISK.getType().equals(type)) {
            return DATA_DISK;
         } else
            return null;
      }
   }

   @Expose
   private String type;

   @Expose
   private Priority shares;

   @Expose
   private int sizeGB;

   @Expose
   private List<String> dsNames;

   @Expose
   private List<String> dsNames4System;

   @Expose
   private List<String> dsNames4Data;

   @Expose
   private Integer diskNum;

   @Expose
   private Boolean shareDatastore;

   // internal used, data disk store name patterns
   private List<String> diskstoreNamePattern;

   // internal used, system disk store name patterns
   private List<String> imagestoreNamePattern;

   private DiskSplitPolicy splitPolicy;

   private DiskScsiControllerType controllerType;

   private String allocType;

   public List<String> getDsNames() {
      return dsNames;
   }

   public void setDsNames(List<String> dsNames) {
      this.dsNames = dsNames;
   }

   public List<String> getDsNames4System() {
      return dsNames4System;
   }

   public void setDsNames4System(List<String> dsNames4System) {
      this.dsNames4System = dsNames4System;
   }

   public List<String> getDsNames4Data() {
      return dsNames4Data;
   }

   public void setDsNames4Data(List<String> dsNames4Data) {
      this.dsNames4Data = dsNames4Data;
   }

   @RestIgnore
   public List<String> getDiskstoreNamePattern() {
      return diskstoreNamePattern;
   }

   public void setDiskstoreNamePattern(List<String> diskstoreNamePattern) {
      this.diskstoreNamePattern = diskstoreNamePattern;
   }

   @RestIgnore
   public List<String> getImagestoreNamePattern() {
      return imagestoreNamePattern;
   }

   public void setImagestoreNamePattern(List<String> imagestoreNamePattern) {
      this.imagestoreNamePattern = imagestoreNamePattern;
   }

   public String getType() {
      return type;
   }

   public void setType(String type) {
      this.type = type;
   }

   public int getSizeGB() {
      return sizeGB;
   }

   public void setSizeGB(int sizeGB) {
      this.sizeGB = sizeGB;
   }

   @RestIgnore
   public DiskSplitPolicy getSplitPolicy() {
      return splitPolicy;
   }

   public void setSplitPolicy(DiskSplitPolicy splitPolicy) {
      this.splitPolicy = splitPolicy;
   }

   @JsonIgnore
   @RestIgnore
   public Priority getShares() {
      return shares;
   }

   public void setShares(Priority shares) {
      this.shares = shares;
   }

   @RestIgnore
   public DiskScsiControllerType getControllerType() {
      return controllerType;
   }

   public void setControllerType(DiskScsiControllerType controllerType) {
      this.controllerType = controllerType;
   }

   @RestIgnore
   public String getAllocType() {
      return allocType;
   }

   public void setAllocType(String allocType) {
      this.allocType = allocType;
   }

   public Integer getDiskNum() {
      return diskNum;
   }

   public void setDiskNum(Integer diskNum) {
      this.diskNum = diskNum;
   }

   public Boolean isShareDatastore() {
      return shareDatastore;
   }

   public void setShareDatastore(Boolean shareDatastore) {
      this.shareDatastore = shareDatastore;
   }
}
