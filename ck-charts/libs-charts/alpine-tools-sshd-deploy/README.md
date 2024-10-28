## alpine-tools-sshd 工具

1. 日志存储路径：`/var/log`
2. 数据存储路径：`/opt/rab_backend/data`
3. 拉取镜像用的imagePullSecrets，是用于自建的harbor仓库，约定使用名为`harbor-inner、harbor-outer`

```bash
## 调试模板
helm install --generate-name -n new-rab-dev-1 --dry-run alpine-tools-sshd-deploy

## 打包chart
helm package alpine-tools-sshd-deploy
helm push alpine-tools-sshd-deploy-0.1.2.tgz oci://harbor.betack.com/libs-charts

## 拉取包
helm pull oci://harbor.betack.com/libs-charts/alpine-tools-sshd-deploy --version 0.1.2
#+ 调试模板
helm install --generate-name -n new-rab-dev-1 --dry-run oci://harbor.betack.com/libs-charts/alpine-tools-sshd-deploy --version 0.1.2

helm install -n saasdata-dev-1 --generate-name --dry-run --set nameOverride=alpine-tools-sshd alpine-tools-sshd-deploy
helm install -n saasdata-dev-1 --generate-name --dry-run --set namespacePrefix=saasdata,nameOverride=alpine-tools-sshd,storage.dataPVCMountPath='/opt/saas/data' alpine-tools-sshd-deploy
```

## 版本说明

- chart 0.1.2 版本：支持直接挂载默认的data二进制数据目录
- chart 0.1.3 版本以后：默认只挂载日志目录


## 0.1.2 部署服务

```bash
# 测试
helm install -n saasdata-dev-1 alpine-tools-sshd --dry-run --set namespacePrefix=saasdata,nameOverride=alpine-tools-sshd,storage.dataPVCMountPath='/opt/saas/data',image.imgHarbor=IAmIPaddress:8765,image.tag='python3.11.3-tools' oci://harbor.betack.com/libs-charts/alpine-tools-sshd-deploy --version 0.1.2

# 部署
helm install -n saasdata-dev-2 alpine-tools-sshd --set namespacePrefix=saasdata,nameOverride=alpine-tools-sshd,storage.dataPVCMountPath='/opt/saas/data',image.imgHarbor=IAmIPaddress:8765,image.tag='python3.11.3-tools' oci://harbor.betack.com/libs-charts/alpine-tools-sshd-deploy --version 0.1.2

# 升级
helm upgrade -n saasdata-staging-2 alpine-tools-sshd --set namespacePrefix=saasdata,nameOverride=alpine-tools-sshd,storage.dataPVCMountPath='/opt/saas/data',image.imgNameOrSvcName=alpine,image.tag='python3.11.3-tools' oci://harbor.betack.com/libs-charts/alpine-tools-sshd-deploy --version 0.1.2
```

## 0.1.3 及更高版本部署

```bash
helm install -n czbank-dev-2 alpine-tools-sshd --set namespacePrefix=czbank,nameOverride=alpine-tools-sshd,image.imgHarbor=IAmIPaddress:8765,image.tag='python3.11.3-tools',imagePullSecrets[0].name=harbor-inner oci://harbor.betack.com/libs-charts/alpine-tools-sshd-deploy --version 0.1.5

#+ shanghai
helm install -n verdaccio alpine-tools-sshd --set namespacePrefix="",nameOverride=alpine-tools-sshd,image.imgHarbor=IAmIPaddress,image.tag='python3.11.3-tools',imagePullSecrets[0].name=harbor-inner,imagePullSecrets[1].name=harbor-outer oci://harbor.betack.com/libs-charts/alpine-tools-sshd-deploy --version 0.1.4

helm install -n product-rack-prod-2 alpine-tools-sshd --set namespacePrefix=product-rack,nameOverride=alpine-tools-sshd,image.imgHarbor=IAmIPaddress,image.tag='python3.11.3-tools',imagePullSecrets[0].name=harbor-inner,imagePullSecrets[1].name=harbor-outer oci://harbor.betack.com/libs-charts/alpine-tools-sshd-deploy --version 0.1.5
```


