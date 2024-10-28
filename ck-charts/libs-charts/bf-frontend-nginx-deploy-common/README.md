# 通用 nginx 前端项目 helm chart

是以 rab-webapp 为蓝本，创建的 nginx helm chart 通用前端

## 版本说明

1. 版本0.1.2之前版本，不支持nginx auth passwd
2. 版本0.1.3版本，支持nginx auth passwd，挂载到/var/www/auth/passwd 


```bash
## 调试模板
helm install --generate-name -n rab-dev-1 --dry-run bf-frontend-nginx-deploy-common
helm install -n rab-dev-1 --generate-name --dry-run --set nameOverride=rab-webapp bf-frontend-nginx-deploy-common
helm install -n new-rab-dev-1 --generate-name --dry-run --set namespacePrefix=new-rab,nameOverride=rab-webapp bf-frontend-nginx-deploy-common

## 打包chart
helm package bf-frontend-nginx-deploy-common
helm push bf-frontend-nginx-deploy-common-0.1.1.tgz oci://harbor.betack.com/libs-charts

## 拉取包
helm pull oci://harbor.betack.com/libs-charts/bf-frontend-nginx-deploy-common --version 0.1.1
#+ 调试模板
helm install --generate-name -n rab-dev-1 --dry-run oci://harbor.betack.com/libs-charts/bf-frontend-nginx-deploy-common --version 0.1.1
```

