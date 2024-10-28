## **java项目，通用 helm chart 模板

1. 日志存储路径：`/var/log`
2. 数据存储路径：`/opt/rab_backend/data`
3. 拉取镜像用的imagePullSecrets，是用于自建的harbor仓库，约定使用名为`harbor-inner、harbor-outer`

```bash
## 调试模板
helm install --generate-name -n new-rab-dev-1 --dry-run bf-java-project-deploy-common

## 打包chart
helm package bf-java-project-deploy-common
helm push bf-java-project-deploy-common-0.1.1.tgz oci://harbor.betack.com/libs-charts

## 拉取包
helm pull oci://harbor.betack.com/libs-charts/bf-java-project-deploy-common --version 0.1.1

## 调试模板
helm install --generate-name -n new-rab-dev-1 --dry-run oci://harbor.betack.com/libs-charts/bf-java-project-deploy-common --version 0.1.1
helm install --generate-name -n new-rab-dev-1 --dry-run -f values.yaml oci://harbor.betack.com/libs-charts/bf-java-project-deploy-common --version 0.1.10


helm install -n rab-dev-1 --generate-name --dry-run --set storage.storageEnable=true,storage.isCreateDataPVC=false,storage.isMountDataPVType=empty,storage.dataPVCNameInfix=infix,nameOverride=rab-svc-api-app-master,storage.isCreateAlonePVC=true bf-java-project-deploy-common

helm install -n rab-dev-1 --generate-name --dry-run --set storage.storageEnable=true,storage.isCreateDataPVC=true,storage.isMountDataPVType=pvc,storage.dataPVCNameInfix=infix,nameOverride=rab-svc-api-app-master bf-java-project-deploy-common


```

## 版本说明

### 0.1.5

1. 支持通过secret注入pod容器的环境变量

### 0.1.6

1. 这个版本只适配华为云CCE，和之前的版本区别：
    1. 创建mosek的pv卷方式不一样。
    2. data卷只为本地临时卷，用作data二进制本地缓存，随pod生命周期
2. 这个版本只能用于部署到华为云CCE。

### 0.1.7

1. 只适配成都office k8s

### 0.1.10

1. 支持akka集群