## **java项目，分布式 Distributed DaemonSet 通用 helm chart 模板

1. 日志存储路径：`/var/log`
2. 数据存储在S3对象存储minIO中
3. 每个pod在 k8s node 节点上，有本地缓存数据，路径：`/opt/rab_backend/data`
4. 拉取镜像用的imagePullSecrets，是用于自建的harbor仓库，约定使用名为`harbor-inner、harbor-outer`

```bash
## 调试模板
helm install --generate-name -n new-rab-dev-1 --dry-run bf-java-project-distributed-statefulset

## 打包chart
helm package bf-java-project-distributed-statefulset
helm push bf-java-project-distributed-statefulset-0.1.1.tgz oci://harbor.betack.com/libs-charts

## 拉取包
helm pull oci://harbor.betack.com/libs-charts/bf-java-project-distributed-statefulset --version 0.1.1
#+ 调试模板
helm install --generate-name -n saasdata-dev-1 --dry-run oci://harbor.betack.com/libs-charts/bf-java-project-distributed-statefulset --version 0.1.1

helm install -n saasdata-dev-3 saas-data-server-test --dry-run --set storage.storageEnable=true,storage.isCreateCachePVC=true,storage.isMountCachePV=true,namespacePrefix=saasdata,nameOverride=saas-data-server-test bf-java-project-distributed-statefulset

helm install -n saasdata-dev-3 saas-data-server-test --dry-run -f values.yaml oci://harbor.betack.com/libs-charts/bf-java-project-distributed-statefulset --version 0.1.0




```
