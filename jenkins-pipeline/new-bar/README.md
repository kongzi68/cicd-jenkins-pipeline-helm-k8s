

## 持久卷分配模板

```groovy
svcYaml['storage']['storageEnable'] = true
// 标准卷
svcYaml['storage']['isCreateDataPVC'] = false
svcYaml['storage']['isMountDataPVType'] = 'empty'
svcYaml['storage']['capacity'] = '50Gi'
svcYaml['storage']['dataPVCNameInfix'] = ''
svcYaml['storage']['dataPVCMountPath'] = '/opt/rab_backend/cache'
svcYaml['storage']['dataStorageClassName'] = 'nfs-client-retain'
// 日志持久卷
svcYaml['storage']['isCreateLogPVC'] = true
svcYaml['storage']['isMountLogPV'] = true
svcYaml['storage']['logStorageClassName'] = 'nfs-client-retain'
// mosek卷
svcYaml['storage']['isCreateMosekPVC'] = true
svcYaml['storage']['isMountMosekPV'] = true
svcYaml['storage']['mosekStorageClassName'] = 'nfs-client-retain'
// 独立卷
svcYaml['storage']['isCreateAlonePVC'] = true
svcYaml['storage']['isMountAlonePV'] = true
svcYaml['storage']['aloneCapacity'] = '200Gi'
svcYaml['storage']['aloneDataPVCNameInfix'] = ''
svcYaml['storage']['aloneDataPVCMountPath'] = '/opt/rab_backend/data'
svcYaml['storage']['aloneDataStorageClassName'] = 'nfs-client-retain'
```

