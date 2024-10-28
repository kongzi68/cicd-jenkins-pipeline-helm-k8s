## mysql-57

> mysql-57，指mysql版本为5.7系列

1. 配置文件挂载到：`/etc/mysql/my.cnf`
2. mysql数据存储路径：`/var/lib/mysql`
3. 拉取镜像用的imagePullSecrets，是用于自建的harbor仓库，约定使用名为`bf-harbor`
4. 数据库当前镜像是docker官方仓库的`mysql:5.7.38`

```bash
helm package mysql-57
helm push mysql-57-0.1.10.tgz oci://harbor.betack.com/libs-charts/

helm pull oci://harbor.betack.com/libs-charts/mysql-57 --version 0.1.10
```

## chart 0.1.8 版本不含备份功能

```bash
helm install --namespace new-rab-staging-3 new-rab-mysql-staging-3 --dry-run oci://harbor.betack.com/libs-charts/mysql-57 --version 0.1.8
helm install --namespace new-rab-staging-3 new-rab-mysql-staging-3 oci://harbor.betack.com/libs-charts/mysql-57 --version 0.1.8

helm install --namespace bf-metersphere --dry-run --set storage.capacity=10Gi,storage.storageClassName=nfs-client-retain  bf-ms-mysql57 oci://harbor.betack.com/libs-charts/mysql-57 --version 0.1.6
helm install --namespace bf-metersphere --set storage.capacity=10Gi,storage.storageClassName=nfs-client-retain  bf-ms-mysql57 oci://harbor.betack.com/libs-charts/mysql-57 --version 0.1.6

helm install --namespace test-esm --set storage.capacity=20Gi,storage.storageClassName=nfs-client-retain,nameOverride=mysql57 mysql57 oci://harbor.betack.com/libs-charts/mysql-57 --version 0.1.8

```

## chart 0.1.9 之后版本含备份功能

```bash
#+ 默认启用备份功能
helm install --namespace colin-test-1 --set global.namespacePrefix=colin,storage.capacity=20Gi,storage.storageClassName=nfs-client-retain,nameOverride=mysql57 mysql57 oci://harbor.betack.com/libs-charts/mysql-57 --version 0.1.10

helm install --namespace new-rab-dev-4 --set global.namespacePrefix=new-rab,global.image.tag=5.7.43,storage.capacity=30Gi,storage.storageClassName=nfs-client-retain,nameOverride=mysql57 mysql57 oci://harbor.betack.com/libs-charts/mysql-57 --version 0.1.10

#+ 不启用备份功能，则需要设置：--set backupMysqlDBCronjob.enabled=false
helm install --namespace rab-api5-prod-1 --set global.namespacePrefix=rab-api5,global.image.tag=5.7.43,storage.capacity=60Gi,storage.storageClassName=longhorn-fast,backupMysqlDBCronjob.enabled=false,nameOverride=mysql57 mysql57 oci://harbor.betack.com/libs-charts/mysql-57 --version 0.1.10

```

