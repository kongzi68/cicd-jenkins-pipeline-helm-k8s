## **java项目，通用 helm chart 模板

1. 使用的是 `hostPath` 卷
2. 日志存储路径：`/var/log`
3. 数据存储路径：`/opt/rab_backend/data`
4. 拉取镜像用的imagePullSecrets，是用于自建的harbor仓库，约定使用名为`harbor-inner、harbor-outer`

```bash
## 调试模板
helm install --generate-name -n new-rab-dev-1 --dry-run bf-java-project-deploy-common-v2

## 打包chart
helm package bf-java-project-deploy-common-v2
helm push bf-java-project-deploy-common-v2-0.1.1.tgz oci://harbor.betack.com/libs-charts

## 拉取包
helm pull oci://harbor.betack.com/libs-charts/bf-java-project-deploy-common-v2 --version 0.1.1

## 调试模板
helm install --generate-name -n new-rab-dev-1 --dry-run oci://harbor.betack.com/libs-charts/bf-java-project-deploy-common-v2 --version 0.1.1
helm install --generate-name -n new-rab-dev-1 --dry-run -f values.yaml oci://harbor.betack.com/libs-charts/bf-java-project-deploy-common-v2 --version 0.1.1


helm install -n rab-dev-1 --generate-name --dry-run --set storage.storageEnable=true,storage.isCreateDataPVC=false,storage.isMountDataPVType=empty,storage.dataPVCNameInfix=infix,nameOverride=rab-svc-api-app-master,storage.isCreateAlonePVC=true bf-java-project-deploy-common-v2

helm install -n rab-dev-1 --generate-name --dry-run --set storage.storageEnable=true,storage.isCreateDataPVC=true,storage.isMountDataPVType=pvc,storage.dataPVCNameInfix=infix,nameOverride=rab-svc-api-app-master bf-java-project-deploy-common-v2


```
## 版本说明

### 0.1.0

所有pv卷，都使用的是 hostPath 卷类型
