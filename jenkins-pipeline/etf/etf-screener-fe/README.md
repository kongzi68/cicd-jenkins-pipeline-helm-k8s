
# 上海生产环境official-website-prod-1新官网betack-official-website

```bash
helm install betack-official-website -n official-website-prod-1 --set namespacePrefix=official-website,nameOverride=betack-official-website,image.imgNameOrSvcName=betack-official-website,image.imgHarbor=IAmIPaddress,image.harborProject=betack,image.pullPolicy=Always,image.tag=fcd5c09-369,imagePullSecrets[0].name=harbor-inner,imagePullSecrets[1].name=harbor-outer,service.ports[0]=3000 oci://harbor.betack.com/libs-charts/bf-frontend-deploy-common --version 0.1.2
```

# 上海生产环境official-website-prod-1旧官网

```bash
helm install betack-official-website-old -n official-website-prod-1 --set namespacePrefix=official-website,nameOverride=betack-official-website-old,image.imgNameOrSvcName=betack-official-website-old,image.imgHarbor=IAmIPaddress,image.harborProject=betack,image.pullPolicy=Always,image.tag=v231,imagePullSecrets[0].name=harbor-inner,imagePullSecrets[1].name=harbor-outer,service.ports[0]=3000 oci://harbor.betack.com/libs-charts/bf-frontend-deploy-common --version 0.1.2
```

# 上海生产环境product-rack-prod-1指数货架

```bash
helm install betack-index-product -n product-rack-prod-1 --set namespacePrefix=product-rack,nameOverride=betack-index-product,image.imgNameOrSvcName=betack-official-website,image.imgHarbor=IAmIPaddress,image.harborProject=betack,image.pullPolicy=Always,image.tag=a2ad596-401,imagePullSecrets[0].name=harbor-inner,imagePullSecrets[1].name=harbor-outer,service.ports[0]=3000 oci://harbor.betack.com/libs-charts/bf-frontend-deploy-common --version 0.1.2
```




