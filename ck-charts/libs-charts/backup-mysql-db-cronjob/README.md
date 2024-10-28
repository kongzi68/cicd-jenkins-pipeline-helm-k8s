## 用cronjob备份mysql数据库

1. 镜像：`IAmIPaddress/libs/alpine:3.15.4-ansible` 的制作，参考笔记 [[image-alpine-A，tools.md id=4acce004-f1c3-45cf-ac86-71cf6fafb813]]需要安装openssh客户端等。
2. 用镜像：`mysql:5.7.38` 作为mysql的客户端，里面会包含`mysql`、`mysqldump`等命令

```bash
## 调试模板
helm install --generate-name -n new-rab-dev-1 --dry-run backup-mysql-db-cronjob

## 打包chart
helm package backup-mysql-db-cronjob
helm push backup-mysql-db-cronjob-0.1.3.tgz oci://harbor.betack.com/libs-charts

## 拉取包
helm pull oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob --version 0.1.3
#+ 调试模板
helm install --generate-name -n new-rab-dev-1 --dry-run oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob --version 0.1.3
helm install -n rab-dev-1 --generate-name --dry-run --set nameOverride=rab-task-data-migration backup-mysql-db-cronjob
helm install -n pingan-dev-1 backup-mysql-db-cronjob --dry-run oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob --version 0.1.3

## 部署
helm install -n pingan-dev-1 backup-mysql-db-cronjob --set namespacePrefix='pingan' oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob --version 0.1.3
#+ 升级
helm upgrade -n pingan-dev-1 backup-mysql-db-cronjob --set namespacePrefix='pingan' oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob --version 0.1.3


### 实际应用
## mysql80
#+ 安装
helm install -n pingan-dev-1 backup-mysql-db-cronjob --set namespacePrefix='pingan' oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob --version 0.1.3
helm install -n pingan-dev-2 backup-mysql-db-cronjob --set namespacePrefix='pingan' oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob --version 0.1.3
#+ 升级
helm upgrade -n pingan-dev-1 backup-mysql-db-cronjob --set namespacePrefix='pingan' oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob --version 0.1.3

## mysql57
helm install -n igw-pre-1 backup-mysql-db-cronjob --set namespacePrefix='igw',image.imgMysql=mysql:5.7.38,mysql.svcName='mysql57-svc',cronjob.schedule="10 16 * * *" oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob --version 0.1.3

## 0.1.4 备份到成都 IAmIPaddress TrueNAS
#+ mysql5.7
helm install -n bf-metersphere backup-mysql-db-cronjob --set namespacePrefix='qa',image.imgAlpineTools=IAmIPaddress:8765/libs/alpine:python3.11.3-tools,image.imgMysql=mysql:5.7.38,mysql.password='Password123@mysql',mysql.svcName='metersphere-mysql-out',cronjob.schedule="5 16 * * *" oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob --version 0.1.4
#+ mysql8.0
helm install -n betanlp-demo-1 backup-mysql80-db-cronjob --set namespacePrefix='betanlp',image.imgAlpineTools=IAmIPaddress:8765/libs/alpine:python3.11.3-tools,image.imgMysql=mysql:8.0.29,mysql.password='iampassword',mysql.svcName='mysql80-svc',cronjob.schedule="15 16 * * *" oci://harbor.betack.com/libs-charts/backup-mysql-db-cronjob --version 0.1.4
```
