## **java项目，通用 helm chart 模板

1. 日志存储路径：`/var/log`
2. 数据存储路径：`/opt/rab_backend/data`
3. 拉取镜像用的imagePullSecrets，是用于自建的harbor仓库，约定使用名为`harbor-inner、harbor-outer`

```bash
## 调试模板
helm install --generate-name -n betanlp-demo-1 --dry-run bf-python-project-deploy-common

## 打包chart
helm package bf-python-project-deploy-common
helm push bf-python-project-deploy-common-0.1.3.tgz oci://harbor.betack.com/libs-charts

## 拉取包
helm pull oci://harbor.betack.com/libs-charts/bf-python-project-deploy-common --version 0.1.3
#+ 调试模板
helm install --generate-name -n betanlp-demo-1 --dry-run oci://harbor.betack.com/libs-charts/bf-python-project-deploy-common --version 0.1.3

helm install -n rab-dev-1 --generate-name --dry-run --set storage.isCreatePVC=true,nameOverride=rab-svc-api-app-master bf-python-project-deploy-common
helm install -n rab-dev-1 --generate-name --dry-run --set storage.isCreatePVC=true,storage.isMountPV=true,nameOverride=rab-svc-api-app-master,storage.isCreateAlonePVC=true bf-python-project-deploy-common

```
