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

## /var/opt/mssql目录权限问题

```bash
mssql@mssql-server-2019-statefuleset-0:/var/opt/mssql$ ls -lha
total 28K
drwxrwx--- 1 iamusername  iamusername 4.0K Sep 21 08:29 .
drwxr-xr-x 1 iamusername  iamusername 4.0K Jul 25 08:45 ..
drwxr-xr-x 5 mssql iamusername 4.0K Sep 21 08:29 .system
drwxr-xr-x 2 mssql iamusername 4.0K Sep 21 08:29 data
drwxr-xr-x 2 mssql iamusername 4.0K Sep 21 08:29 log
drwxr-xr-x 2 mssql iamusername 4.0K Sep 21 08:29 secrets
mssql@mssql-server-2019-statefuleset-0:/var/opt/mssql$ cat /etc/passwd
iamusername:x:0:0:iamusername:/iamusername:/bin/bash
daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin
bin:x:2:2:bin:/bin:/usr/sbin/nologin
sys:x:3:3:sys:/dev:/usr/sbin/nologin
sync:x:4:65534:sync:/bin:/bin/sync
games:x:5:60:games:/usr/games:/usr/sbin/nologin
man:x:6:12:man:/var/cache/man:/usr/sbin/nologin
lp:x:7:7:lp:/var/spool/lpd:/usr/sbin/nologin
mail:x:8:8:mail:/var/mail:/usr/sbin/nologin
news:x:9:9:news:/var/spool/news:/usr/sbin/nologin
uucp:x:10:10:uucp:/var/spool/uucp:/usr/sbin/nologin
proxy:x:13:13:proxy:/bin:/usr/sbin/nologin
www-data:x:33:33:www-data:/var/www:/usr/sbin/nologin
backup:x:34:34:backup:/var/backups:/usr/sbin/nologin
list:x:38:38:Mailing List Manager:/var/list:/usr/sbin/nologin
irc:x:39:39:ircd:/var/run/ircd:/usr/sbin/nologin
gnats:x:41:41:Gnats Bug-Reporting System (IamUserName):/var/lib/gnats:/usr/sbin/nologin
nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin
_apt:x:100:65534::/nonexistent:/usr/sbin/nologin
mssql:x:10001:0::/home/mssql:/bin/bash

mssql@mssql-server-2019-statefuleset-0:/var/opt$ ls -lh mssql
total 12K
drwxr-xr-x 2 mssql iamusername 4.0K Sep 21 08:29 data
drwxr-xr-x 2 mssql iamusername 4.0K Sep 21 08:29 log
drwxr-xr-x 2 mssql iamusername 4.0K Sep 21 08:29 secrets
mssql@mssql-server-2019-statefuleset-0:/var/opt$ ls -lh
total 4.0K
drwxrwx--- 1 iamusername iamusername 4.0K Sep 21 08:29 mssql

## pod启动的时候，报权限错误，其实只需要设定持久化的mssql目录权限为 770
```











