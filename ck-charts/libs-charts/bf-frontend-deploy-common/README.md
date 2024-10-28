## 非 nginx 前端项目 helm chart

```bash
## 打包chart
[iamusername@localhost rab-charts]# helm package bf-frontend-deploy-common
Successfully packaged chart and saved it to: /data/bf-k8s/rab-charts/bf-frontend-deploy-common-0.1.1.tgz
[iamusername@localhost rab-charts]# helm push bf-frontend-deploy-common-0.1.1.tgz oci://harbor.betack.com/libs-charts
Pushed: harbor.betack.com/libs-charts/bf-frontend-deploy-common:0.1.1
Digest: sha256:f82ac07a9467413cbab5d696115d49127382bc055a022675d9f30ad23a3cbc17


```

### 部署验证

```bash
helm install rabbeyond-docs-site-mkdoc -n bf-docs-prod-1  --set namespacePrefix=bf-docs,nameOverride=rabbeyond-docs-site-mkdoc oci://harbor.betack.com/libs-charts/bf-frontend-deploy-common --version 0.1.2

```

### 升级服务

```bash

```


