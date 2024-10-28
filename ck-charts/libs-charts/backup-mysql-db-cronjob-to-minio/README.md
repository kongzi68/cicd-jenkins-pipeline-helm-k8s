# 用cronjob备份mysql数据库

## 调试模板

```bash
helm install --generate-name -n new-rab-dev-1 --dry-run backup-mysql-db-cronjob-to-minio

## 打包chart
helm package backup-mysql-db-cronjob-to-minio
helm push backup-mysql-db-cronjob-to-minio-0.1.1.tgz oci://harbor.betack.com/libs-charts

## 拉取包
helm pull oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob-to-minio --version 0.1.1
#+ 调试模板
helm install --generate-name -n new-rab-dev-1 --dry-run oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob-to-minio --version 0.1.1
helm install -n rab-dev-1 --generate-name --dry-run --set nameOverride=rab-task-data-migration backup-mysql-db-cronjob-to-minio
helm install -n pingan-dev-1 backup-mysql-db-cronjob-to-minio --dry-run oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob-to-minio --version 0.1.1
```


```bash
## 部署
helm install -n pingan-dev-1 backup-mysql-db-cronjob-to-minio --set namespacePrefix='pingan' oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob-to-minio --version 0.1.1
#+ 升级
helm upgrade -n pingan-dev-1 backup-mysql-db-cronjob-to-minio --set namespacePrefix='pingan' oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob-to-minio --version 0.1.1


### 实际应用
## mysql5.7
helm install -n czbank-prod-1 backup-mysql-db-cronjob-to-minio --set namespacePrefix='czbank',image.imgMysql=mysql:5.7.43,mysql.password='iampassword',mysql.svcName='mysql57-svc',cronjob.schedule="5 0 * * *" oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob-to-minio --version 0.1.1


#+ mysql8.0
helm install -n product-rack-prod-2 backup-mysql-db-cronjob-to-minio --set namespacePrefix='product-rack',image.imgMysql=mysql:8.0.35-debian,mysql.password='iampassword',mysql.svcName='mysql80-svc',cronjob.schedule="5 0 * * *" oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob-to-minio --version 0.1.1

```
