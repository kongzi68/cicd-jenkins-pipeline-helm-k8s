## **java项目，通用 helm chart 模板

1. 日志存储路径：`/var/log`
2. 数据存储路径：`/opt/rab_backend/data`
3. 拉取镜像用的imagePullSecrets，是用于自建的harbor仓库，约定使用名为`harbor-inner、harbor-outer`

```bash
## 调试模板
helm install --generate-name -n new-rab-dev-1 --dry-run bf-java-project-deploy-common-v3

## 打包chart
helm package bf-java-project-deploy-common-v3
helm push bf-java-project-deploy-common-v3-0.1.0.tgz oci://harbor.betack.com/libs-charts

## 拉取包
helm pull oci://harbor.betack.com/libs-charts/bf-java-project-deploy-common-v3 --version 0.1.0

## 调试模板
helm install --generate-name -n new-rab-dev-1 --dry-run oci://harbor.betack.com/libs-charts/bf-java-project-deploy-common-v3 --version 0.1.0
helm install --generate-name -n new-rab-dev-1 --dry-run -f values.yaml oci://harbor.betack.com/libs-charts/bf-java-project-deploy-common-v3 --version 0.1.0


helm install -n rab-dev-1 --generate-name --dry-run --set storage.storageEnable=true,storage.isCreateDataPVC=false,storage.isMountDataPVType=empty,storage.dataPVCNameInfix=infix,nameOverride=rab-svc-api-app-master,storage.isCreateAlonePVC=true bf-java-project-deploy-common-v3

helm install -n rab-dev-1 --generate-name --dry-run --set storage.storageEnable=true,storage.isCreateDataPVC=true,storage.isMountDataPVType=pvc,storage.dataPVCNameInfix=infix,nameOverride=rab-svc-api-app-master bf-java-project-deploy-common-v3


```

## 版本说明

### 0.1.0

1. pvc卷必须指定中缀



