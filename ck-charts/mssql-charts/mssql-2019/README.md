helm-chart仓库，使用华为云上自建的harbor，https://harbor.betack.com

## Microsoft SQL Server

1. 中文官网, https://www.microsoft.com/zh-cn/sql-server/sql-server-downloads
2. 官方文档资料, https://learn.microsoft.com/zh-cn/sql/linux/quickstart-install-connect-docker?view=sql-server-ver16&pivots=cs1-bash
3. docker镜像tag标签, https://hub.docker.com/_/microsoft-mssql-server?tab=description
4. https://hub.docker.com/publishers/microsoftowner

> 需要切换的英文的网站：https://www.microsoft.com/en-us/sql-server/sql-server-downloads, 才能打开文档链接

```bash
betack@rke-k8s-rancher-tools:~$ docker pull mcr.microsoft.com/mssql/server:2019-latest
2019-latest: Pulling from mssql/server
87fe25d61c01: Pull complete
209c3118dbee: Pull complete
9d2f7158599c: Pull complete
Digest: sha256:f54a84b8a802afdfa91a954e8ddfcec9973447ce8efec519adf593b54d49bedf
Status: Downloaded newer image for mcr.microsoft.com/mssql/server:2019-latest
mcr.microsoft.com/mssql/server:2019-latest
betack@rke-k8s-rancher-tools:~$ docker tag mcr.microsoft.com/mssql/server:2019-latest IAmIPaddress/libs/mssql-server:2019-latest
betack@rke-k8s-rancher-tools:~$ docker image push IAmIPaddress/libs/mssql-server:2019-latest
The push refers to repository [IAmIPaddress/libs/mssql-server]
1b32402c889e: Pushed
c63e7317ee73: Pushed
af7ed92504ae: Pushed
2019-latest: digest: sha256:ef28f523e2eba1e2d2d3fed9942eac25812b5fcdc187ee4c249b8bf464c4ba84 size: 954
```

## helm 包管理

```bash
[iamusername@localhost bf-k8s]# cd mssql-charts/
[iamusername@localhost mssql-charts]# ls
mssql-2019  README.md
[iamusername@localhost mssql-charts]# helm package mssql-2019
Successfully packaged chart and saved it to: /data/bf-k8s/mssql-charts/mssql-server-2019-0.1.4.tgz
[iamusername@localhost mssql-charts]# helm push mssql-server-2019-0.1.4.tgz oci://harbor.betack.com/libs-charts
Pushed: harbor.betack.com/libs-charts/mssql-server-2019:0.1.4
Digest: sha256:896f9afc326d0ef018dfe20a10e07e66eb4846ce33cf33adace2d45a7cab59b4
```

## 启动mssql-2019

helm pull oci://harbor.betack.com/libs-charts/mssql-server-2019 --version 0.1.4


```bash
# 调试
helm -n jaa-dev-1 install mssql-2019 --dry-run oci://harbor.betack.com/libs-charts/mssql-server-2019 --version 0.1.4

betack@rke-k8s-rancher-tools:~$ helm -n jaa-dev-1 install mssql-server-2019 oci://harbor.betack.com/libs-charts/mssql-server-2019 --version 0.1.4
NAME: mssql-server-2019
LAST DEPLOYED: Wed Sep 21 15:53:01 2022
NAMESPACE: jaa-dev-1
STATUS: deployed
REVISION: 1
TEST SUITE: None

betack@rke-k8s-rancher-tools:~$ helm -n jaa-dev-1 list
NAME             	NAMESPACE	REVISION	UPDATED                                	STATUS  	CHART                  	APP VERSION
mssql-server-2019	jaa-dev-1	1       	2022-09-21 15:53:01.211315757 +0800 CST	deployed	mssql-server-2019-0.1.3	1.0.2019
mysql            	jaa-dev-1	1       	2022-09-20 16:33:03.027353407 +0800 CST	deployed	mysql-80-0.1.4         	8.0.29
```