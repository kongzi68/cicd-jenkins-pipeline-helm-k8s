#!/bin/bash

messagezz()
{
curl -X POST -H "Content-Type: application/json" \
-d '{
        "msg_type": "post",
        "content": {
                "post": {
                        "zh_cn": {
                                "title": "生产mirror环境release通知",
                                "content": [
                                        [
                                                {
                                                        "tag": "text",
                                                        "text": "发布人：'$BUILD_USER'\n"
                                                },
                                                {
                                                        "tag": "text",
                                                        "text": "升级版本：'$updatever'\n"
                                                },
                                                {
                                                        "tag": "text",
                                                        "text": "访问地址：http://mirror.betack.com:81\n"
                                                },
                                                {
                                                        "tag": "text",
                                                        "text": "部署应用：'$updateapp'\n"
                                                }
                                        ]
                                ]
                        }
                }
        }
}' \
https://open.feishu.cn/open-apis/bot/v2/hook/f441fd61-8f44-4a5d-975c-5625212b53e0
}

message1()
{
curl -X POST -H "Content-Type: application/json" \
-d '{
        "msg_type": "post",
        "content": {
                "post": {
                        "zh_cn": {
                                "title": "生产mirror环境release通知",
                                "content": [
                                        [
                                                {
                                                        "tag": "text",
                                                        "text": "生产api4环境release，可以进行测试，测试地址详见下图：\n"
                                                }
                                        ]
                                ]
                        }
                }
        }
}' \
https://open.feishu.cn/open-apis/bot/v2/hook/49cc9811-bd95-4c26-8b69-8888888888888
}

message2()
{
curl -X POST -H "Content-Type: application/json" \
-d '{
        "msg_type": "image",
        "content":{
               "image_key": "img_v2_f87dbf6e-f416-4d70-8b48-2222222222"
        }
    }' \ 
https://open.feishu.cn/open-apis/bot/v2/hook/49cc9811-bd95-4c26-8b69-8888888888888
}


backpath=/mnt/data-1/nfs/new-rab/backend-volume/app
frontpath=/mnt/data-1/nfs/new-rab/frontend-volume

OLD_IFS="$IFS"
IFS=","
arr=($updateapp)
arr2=($migration_parameter)
IFS="$OLD_IFS"

if [[ $env == "api4" ]];then  
ipzz=`ping api4.betack.com -c 1|awk 'NR==1 {print $3}'|awk -F "(" '{print $2}'|awk -F ")" '{print $1}'`
fi
echo "升级ip="$ipzz
#if [[ $ipzz == "IAmIPaddress" ]];then
#  rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P stop nginx
#  rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P stop nginx2
#fi
 
    for svc_name in ${arr[@]}
    do
       if [[ $svc_name == "rab-task-data-migration" ]];then
         echo "升级${svc_name}到$ipzz"   
         ssh -o "StrictHostKeyChecking no" iamusername@IAmIPaddress "scp ${backpath}/$svc_name-${updatever}.jar betack@$ipzz:/data2t/jars/rab-backend/$svc_name/" && \
         if [[ $ipzz == "IAmIPaddress" ]];then       
           if [[ ! ${migration_parameter} == "" ]];then
             echo "migration_parameter_not null"
             ######清空mig参数##########################
             numz=`cat /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml|grep rab-task-data-migration-.*.jar -A10|grep -n '^    -'|wc -l`
             rm -rf /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml.bak
             cp /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml.bak
             echo "numz="$numz
             let numzz=$[$numz-1]
             echo "numzz="$numzz
             if [[ ${numzz} -ne 0 ]];then
               i=1
               while [ $i -le $numzz ]
               do
                 sed  -nie 'p;/rab-task-data-migration-.*.jar/n' /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml
                 let i++
               done
             fi
             ##############新增参数#################
             for k in ${arr2[@]}
             do
               echo $k
               sed -i "/rab-task-data-migration-.*.jar/a\    - $k" /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml 
             done
           fi
           sed -i "s/${svc_name}-.*.jar/${svc_name}-${updatever}.jar/g" /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml
           rancher-compose --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P -f /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml -p rab up --upgrade --pull --confirm-upgrade -d $svc_name                
         fi      
       elif [[ $svc_name == "rab-svc-api-app" ]];then
         echo "升级${svc_name}到$ipzz"   
         ssh -o "StrictHostKeyChecking no" iamusername@IAmIPaddress "scp ${backpath}/$svc_name-${updatever}.jar betack@$ipzz:/data2t/jars/rab-backend/$svc_name/" && \    
         if [[ $ipzz == "IAmIPaddress" ]];then
          sed -i "s/${svc_name}-.*.jar/${svc_name}-${updatever}.jar/g" /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml
          rancher-compose --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P -f /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml -p rab up --upgrade --pull --confirm-upgrade -d $svc_name
         fi
       elif [[ $svc_name == "rab-svc-offline-app" ]];then 
         echo "升级${svc_name}到$ipzz"   
         #ssh -o "StrictHostKeyChecking no" iamusername@IAmIPaddress "scp ${backpath}/rab-svc-api-app-${updatever}.jar betack@$ipzz:/data2t/jars/rab-backend/rab-svc-api-app/" && \    
         if [[ $ipzz == "IAmIPaddress" ]];then
          sed -i "s/rab-svc-api-app-.*.jar/rab-svc-api-app-${updatever}.jar/g" /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml
          rancher-compose --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P -f /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml -p rab up --upgrade --pull --confirm-upgrade -d rab-svc-offline
         fi
       elif [[ $svc_name == "rab-svc-timeseries-data-generator" ]];then
         echo "升级${svc_name}到$ipzz"   
         ssh -o "StrictHostKeyChecking no" iamusername@IAmIPaddress "scp ${backpath}/$svc_name-${updatever}.jar betack@$ipzz:/data2t/jars/rab-backend/$svc_name/" && \    
         if [[ $ipzz == "IAmIPaddress" ]];then
          sed -i "s/${svc_name}-.*.jar/${svc_name}-${updatever}.jar/g" /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml
          rancher-compose --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P -f /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml -p rab up --upgrade --pull --confirm-upgrade -d $svc_name
         fi  
       elif [[ $svc_name == "rab-svc-migrate-to-beyond" ]];then
         echo "升级${svc_name}到$ipzz"
         ssh -o "StrictHostKeyChecking no" iamusername@IAmIPaddress "scp ${backpath}/$svc_name-${updatever}.jar betack@$ipzz:/data2t/jars/rab-backend/$svc_name/" && \ 
         if [[ $ipzz == "IAmIPaddress" ]];then
           sed -i "s/${svc_name}-.*.jar/${svc_name}-${updatever}.jar/g" /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml
           rancher-compose --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P -f /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml -p rab up --upgrade --pull --confirm-upgrade -d $svc_name
         fi  
       elif [[ $svc_name == "embed-dao-exposure" ]];then
         echo "升级${svc_name}到$ipzz"
         ssh -o "StrictHostKeyChecking no" iamusername@IAmIPaddress "scp ${backpath}/$svc_name-${updatever}.jar betack@$ipzz:/data2t/jars/rab-backend/$svc_name/" && \ 
         if [[ $ipzz == "IAmIPaddress" ]];then
           sed -i "s/${svc_name}-.*.jar/${svc_name}-${updatever}.jar/g" /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml
           rancher-compose --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P -f /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml -p rab up --upgrade --pull --confirm-upgrade -d $svc_name
         fi 
       elif [[ $svc_name == "nginx2" ]];then
         # 更新改了nginx配置文件，需要重启nginx服务
         #+ 更新了api之后，需要重启 nginx1 否则会报DNS解析错误
         echo "升级${svc_name}到${env_name}"   
         ssh -o "StrictHostKeyChecking no" iamusername@IAmIPaddress "scp -r ${frontpath}/web-${updatever} betack@$ipzz:/home/betack/" && \    
         if [[ $ipzz == "IAmIPaddress" ]];then
           ssh -o "StrictHostKeyChecking no" betack@$ipzz "/bin/bash;sed -i 's/web-.*/web-${updatever};/g' /data2t/tougu/nginx/nginx.conf"
           rancher-compose --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P -f /home/betack/jenkins/workspace/rab_backend/docker-compose-api4.yml -p rab up --upgrade --pull --confirm-upgrade -d $svc_name
         fi 
         if [[ $feishu == true ]];then
           sleep 5
           messagezz
         fi
       fi
    done   
        
if [[ $restartsvc == true ]];then
   if [[ $ipzz == "IAmIPaddress" ]];then
     rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P stop rab-svc-api-app
     rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P stop rab-svc-offline
     rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P stop rab-svc-timeseries-data-generator  
     rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P start rab-svc-api-app
     rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P start rab-svc-offline
     rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P start rab-svc-timeseries-data-generator
   fi
fi

if [[ $switchdns == true ]];then
   ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress "/home/betack/sbin/switchdns.sh"
fi

if [[ $ipzz == "IAmIPaddress" ]];then
  rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P start nginx
  if [[ $svc_name != "nginx2" ]];then
    rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P start nginx2
  fi
fi


if [[ $feishu == true ]];then
 sleep 5
 message1
 #message2
fi