
> 使用 jenkins pipeline 改写下面的部署方式
> 并且原有的部署方式有问题，不能部署成功
> 现改写为用docker的方式部署

IAmIPaddress 孟亚朋，更新服务，sdk-grpc-server
jenkins：http://IAmIPaddress:8081/view/rabbeyond/job/rabbeyond-deploy/

- 文档： http://ci.betack.com:61032/1.6.X/rab-engine/maintain/deploy-with-single/#_1
- release 版本：http://jfrog.betack.com:8081/artifactory/webapp/#/artifacts/browse/tree/General/libs-release-local/com/betack/sdk-grpc-server
- snapshot版本：http://jfrog.betack.com:8081/artifactory/webapp/#/artifacts/browse/tree/General/libs-snapshot-local/com/betack/sdk-grpc-server

```bash
betack@ecs-fa17-0002-192-168-1-152:~$ cat /home/betack/script/operation_scripts/start.sh
#！/bin/bash

dt=`date +%Y%m%d%H%M%S`
echo $1
wget --http-user=betack  --http-passwd=iampassword -P /home/betack/backend/app $1
ps -ef|grep sdk-grpc-server|grep -v grep|awk '{print $2}'|xargs kill -9
#mv /home/betack/backend/app/rabbeyond /home/betack/backend/app/rabbeyond_$dt
sleep 3
jar_name=$(echo $1 | awk -F '/' '{print $NF}')

nohup java -jar /home/betack/backend/app/$jar_name > /home/betack/backend/app/$jar_name.log 2>&1  &
```

