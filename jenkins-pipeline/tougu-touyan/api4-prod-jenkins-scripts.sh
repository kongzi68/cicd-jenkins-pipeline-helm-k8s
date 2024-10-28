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
# arr2=($migration_parameter)
IFS="$OLD_IFS"

if [[ $env == "api4" ]];then
    ipzz=`ping api4.betack.com -c 1|awk 'NR==1 {print $3}'|awk -F "(" '{print $2}'|awk -F ")" '{print $1}'`
elif [[ $env == "mirror" ]];then  
    ipzz=`ping mirror.betack.com -c 1|awk 'NR==1 {print $3}'|awk -F "(" '{print $2}'|awk -F ")" '{print $1}'`
fi


## 定义各服务器的变量
API4_IFACE="""$(sh -xc "ssh -o 'StrictHostKeyChecking no' betack@${ipzz} ip route | head -1 | awk '{print \$5}'")"""
API4_IPADDRESS="$(ssh -o "StrictHostKeyChecking no" betack@${ipzz} ip addr list ${API4_IFACE} | grep -E "${API4_IFACE}$" | awk -F'[ /]+' '{print $3}')"
if [[ "${API4_IPADDRESS}" == "" ]];then
	echo "获取服务器内网IP地址失败，退出更新"
	exit 1
fi
if [[ "${API4_IPADDRESS}" == "IAmIPaddress" ]];then
    DIR_DATA2T='/data2t'
    MYSQL_PORT=3306
    RANCHER_ACCESS_KEY='CDD6D43C10C0CCE99408'
    RANCHER_SECRET_KEY='VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P'
    RANCHER_SPACES_DIR='rab'
elif [[ "${API4_IPADDRESS}" == "IAmIPaddress" ]];then
    DIR_DATA2T='/data3t/api4_temp'
    MYSQL_PORT=3310
    RANCHER_ACCESS_KEY='76C39262AC3853DD7EF0'
    RANCHER_SECRET_KEY='AnzZG69joNb1uLBzyRToc4wR6Gb32EWGjMceFQa2'
    RANCHER_SPACES_DIR='rab-api4'
fi

echo "当前正在更新的服务器环境为：${env}，公网IP为：${ipzz}，内网IP为：${API4_IPADDRESS}"

## 更新前停止前端服务
# rancher --url http://$ipzz:8080/v1 --access-key "${RANCHER_ACCESS_KEY}" --secret-key "${RANCHER_SECRET_KEY}" stop nginx
# rancher --url http://$ipzz:8080/v1 --access-key "${RANCHER_ACCESS_KEY}" --secret-key "${RANCHER_SECRET_KEY}" stop nginx2


## 更新的服务
for svc_name in ${arr[@]};do
    ## 传jar包 或 nginx 前端代码
    if [ "$svc_name" = 'rab-svc-offline-app' -o "$svc_name" = 'rab-svc-offline-app-new' ];then
        SCP_OPTION=''
        T_OPTION='-f'
        if [ "$svc_name" = 'rab-svc-offline-app-new' ];then
            JARPKG_NAME="rab-svc-offline-app-${updatever}.jar"
        else
            JARPKG_NAME="${svc_name}-${updatever}.jar"
        fi
        SRC_FILE="${backpath}/${JARPKG_NAME}"
        DST_JAR_PATH="${DIR_DATA2T}/jars/rab-backend/rab-svc-api-app/"
    elif [[ $svc_name == 'nginx2' ]];then
        SCP_OPTION='-r'
        T_OPTION='-d'
        JARPKG_NAME="web-${updatever}"
        SRC_FILE="${frontpath}/${JARPKG_NAME}"
        DST_JAR_PATH="/home/betack/"
    else
        SCP_OPTION=''
        T_OPTION='-f'
        JARPKG_NAME="${svc_name}-${updatever}.jar"
        SRC_FILE="${backpath}/${JARPKG_NAME}"
        DST_JAR_PATH="${DIR_DATA2T}/jars/rab-backend/${svc_name}/"
    fi
    echo "当前正在复制 ${svc_name} 的更新文件：${JARPKG_NAME}"
    JARPKG_IS_EXIST=$(ssh -o "StrictHostKeyChecking no" betack@${ipzz} "[ ${T_OPTION} ${DST_JAR_PATH}${JARPKG_NAME} ] && echo 0 || echo 1 ")
    if [[ ${JARPKG_IS_EXIST} -eq 1 ]];then
        ssh -o "StrictHostKeyChecking no" iamusername@IAmIPaddress "scp ${SCP_OPTION} ${SRC_FILE} betack@${ipzz}:${DST_JAR_PATH}"
        if [ $? -eq 0 ];then
            echo "复制JAR包或前端代码到 ${ipzz} 的 ${DST_JAR_PATH} 成功。" 
        else
            echo "复制JAR包或前端代码到 ${ipzz} 的 ${DST_JAR_PATH} 失败..." 
            exit 1
        fi
    else
        echo "文件已存在，不需要传输。"
    fi

    ## 处理服务的 docker-compose.yml
    echo "当前正在修改 ${svc_name} docker-compose.yml 模板文件"
    DOCKER_COMPOSE_FILE="docker-compose-api4-${API4_IPADDRESS}.yml"
    echo "docker-compose文件名称：${DOCKER_COMPOSE_FILE}"
    cd /home/betack/jenkins/workspace/rab_backend && {
        cp -a docker-compose-api4-Template.yml ${DOCKER_COMPOSE_FILE}
        sed -i "s#API4_IPADDRESS#${API4_IPADDRESS}#g" ${DOCKER_COMPOSE_FILE}
        sed -i "s#MYSQL_PORT#${MYSQL_PORT}#g" ${DOCKER_COMPOSE_FILE}
        sed -i "s#API4_DATA2T#${DIR_DATA2T}#g" ${DOCKER_COMPOSE_FILE}
        sed -i "s#${svc_name}-JARPKG_NAME#${JARPKG_NAME}#g" ${DOCKER_COMPOSE_FILE}
        #+ 处理 rab-task-data-migration 服务的参数
        if [[ $svc_name == 'rab-task-data-migration' ]];then
            sed -i "s#${svc_name}-PARAMETER#${migration_parameter}#g" ${DOCKER_COMPOSE_FILE}
        fi
        echo "服务 $svc_name 的主要 docker compose 内容如下："
        cat ${DOCKER_COMPOSE_FILE} | grep -C 10 "${svc_name}"
    }
    #+ 处理nginx2前端特例
    if [[ $svc_name == 'nginx2' ]];then
        ssh -o "StrictHostKeyChecking no" betack@$ipzz "/bin/bash;sed -i 's/web-.*/web-${updatever};/g' ${DIR_DATA2T}/tougu/nginx/nginx.conf; cat ${DIR_DATA2T}/tougu/nginx/nginx.conf"
    fi

    ## 更新服务
    echo "当前正在执行 rancher-compose 命令，更新服务 $svc_name"
    sh -xc "rancher-compose --url http://$ipzz:8080/v1 --access-key ${RANCHER_ACCESS_KEY} --secret-key ${RANCHER_SECRET_KEY} \
        -f /home/betack/jenkins/workspace/rab_backend/${DOCKER_COMPOSE_FILE} -p ${RANCHER_SPACES_DIR} up --upgrade --pull --confirm-upgrade -d ${svc_name}"
done   


#if [[ $restartsvc == true ]];then
#   if [[ $ipzz == "IAmIPaddress" ]];then
#     rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P stop rab-svc-api-app
#     rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P stop rab-svc-offline
#     rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P stop rab-svc-timeseries-data-generator  
#     rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P start rab-svc-api-app
#     rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P start rab-svc-offline
#     rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P start rab-svc-timeseries-data-generator
#   fi
#fi

if [[ $switchdns == true ]];then
   ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress "/home/betack/sbin/switchdns.sh"
fi

# if [[ $ipzz == "IAmIPaddress" ]];then
#   rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P start nginx
#   if [[ $svc_name != "nginx2" ]];then
#     rancher --url http://$ipzz:8080/v1 --access-key CDD6D43C10C0CCE99408 --secret-key  VsSXYVYbtkYU24NU2GnrV4dFAueqr31MeaQTkL3P start nginx2
#   fi
# fi


if [[ $feishu == true ]];then
 sleep 5
 #message1
 #message2
fi