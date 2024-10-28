# mysql8.0
## mysql-80

helm-chart仓库，使用华为云上自建的harbor，https://harbor.betack.com

> mysql-80，指mysql版本为8.0系列

1. 配置文件挂载到：`/etc/mysql/my.cnf`；0.1.7版本配置文件挂载到/etc/mysql/conf.d/mysql.cnf，适用于mysql8.0.34及之后版本
2. mysql数据存储路径：`/var/lib/mysql`
3. 拉取镜像用的imagePullSecrets，是用于自建的harbor仓库，约定使用名为`bf-harbor`
4. 数据库当前镜像是docker官方仓库的`mysql:8.0.29`

```bash
helm package mysql-80
helm push mysql-80-0.1.7.tgz oci://harbor.betack.com/libs-charts/

helm pull oci://harbor.betack.com/libs-charts/mysql-80 --version 0.1.0

[iamusername@localhost bf-k8s]# helm package mysql-80
Successfully packaged chart and saved it to: /data/bf-k8s/mysql-80-0.1.0.tgz
[iamusername@localhost bf-k8s]# HELM_EXPERIMENTAL_OCI=1 helm push mysql-80-0.1.0.tgz oci://harbor.betack.com/libs-charts
Pushed: harbor.betack.com/libs-charts/mysql-80:0.1.0
Digest: sha256:a70455cfd815facf9a8bb961b067c3575a2d7f71d3c3150bce2573035694d092
```

## 启动mysql-80

若不需要备份，chart需要指定为版本 0.1.4

```bash
# 调试
helm -n htsc-dev-1 install mysql --dry-run oci://harbor.betack.com/libs-charts/mysql-80 --version 0.1.4

helm install --namespace saasdata-staging-1 --dry-run --set storage.capacity=40Gi,storage.storageClassName=longhorn-fast,nameOverride=mysql80 mysql80 oci://harbor.betack.com/libs-charts/mysql-80 --version 0.1.5


betack@rke-k8s-rancher-tools:~/yaml/project/htsc$ helm -n htsc-dev-1 install mysql oci://harbor.betack.com/libs-charts/mysql-80 --version 0.1.4
NAME: mysql
LAST DEPLOYED: Mon Jul  4 14:16:08 2022
NAMESPACE: htsc-dev-1
STATUS: deployed
REVISION: 1
TEST SUITE: None
betack@rke-k8s-rancher-tools:~/yaml/project/htsc$ helm -n htsc-dev-1 list -f mysql
NAME 	NAMESPACE 	REVISION	UPDATED                                	STATUS  	CHART         	APP VERSION
mysql	htsc-dev-1	1       	2022-07-04 14:16:08.565455276 +0800 CST	deployed	mysql-80-0.1.4	8.0.29


helm install --namespace saasdata-staging-1 --set storage.capacity=40Gi,storage.storageClassName=longhorn-fast,nameOverride=mysql80 mysql80 oci://harbor.betack.com/libs-charts/mysql-80 --version 0.1.4

```

## 带每日定时备份功能的mysql80

```bash
## 需要备份，chart需要指定为版本 0.1.5 以上
helm install --namespace saasdata-dev-1 --dry-run --set global.namespacePrefix=saasdata,global.image.tag=8.0.35,storage.capacity=40Gi,storage.storageClassName=nfs-client-retain,nameOverride=mysql80 mysql80 oci://harbor.betack.com/libs-charts/mysql-80 --version 0.1.7

## 若不需要备份，则需要设置：--set backupMysqlDBCronjob.enabled=false
helm install --namespace saasdata-dev-1 --dry-run --set global.namespacePrefix=saasdata,global.image.tag=8.0.35,storage.capacity=40Gi,storage.storageClassName=longhorn-fast,nameOverride=mysql80,backupMysqlDBCronjob.enabled=false mysql80 oci://harbor.betack.com/libs-charts/mysql-80 --version 0.1.7
#+
helm install --namespace saasdata-prod-1 --set global.namespacePrefix=saasdata,global.image.tag=8.0.35-debian,storage.capacity=100Gi,storage.storageClassName=longhorn-fast,nameOverride=mysql80,backupMysqlDBCronjob.enabled=false mysql80 oci://harbor.betack.com/libs-charts/mysql-80 --version 0.1.7

```

# mysql chart 版本说明

> https://dev.mysql.com/doc/refman/8.0/en/upgrading-from-previous-series.html#upgrade-caching-sha2-password

1. chart 0.1.6 及之前版本，适用于 mysql 8.0.33 及以前版本，密码插件：mysql_native_password
2. chart 0.1.7 及之后版本，适用于 mysql 8.0.34 及以后版本，密码插件：caching_sha2_password




