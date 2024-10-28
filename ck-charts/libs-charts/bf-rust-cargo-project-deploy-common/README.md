## **java项目，通用 helm chart 模板

1. 日志存储路径：`/var/log`
2. 数据存储路径：`/opt/rab_backend/data`
3. 拉取镜像用的imagePullSecrets，是用于自建的harbor仓库，约定使用名为`harbor-inner、harbor-outer`

```bash
## 调试模板
helm install --generate-name -n betanlp-demo-1 --dry-run bf-rust-cargo-project-deploy-common

## 打包chart
helm package bf-rust-cargo-project-deploy-common
helm push bf-rust-cargo-project-deploy-common-0.1.1.tgz oci://harbor.betack.com/libs-charts

## 拉取包
helm pull oci://harbor.betack.com/libs-charts/bf-rust-cargo-project-deploy-common --version 0.1.1
#+ 调试模板
helm install --generate-name -n betanlp-demo-1 --dry-run oci://harbor.betack.com/libs-charts/bf-rust-cargo-project-deploy-common --version 0.1.1

helm install -n rab-dev-1 --generate-name --dry-run --set storage.storageEnable=true,storage.isCreatePVC=true,storage.isCreateDataPVC=true,storage.isMountDataPV=true,storage.dataPVCNameInfix='llm',nameOverride=beta-llm-embedding bf-rust-cargo-project-deploy-common



```
