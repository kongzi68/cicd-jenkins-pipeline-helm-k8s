#!/bin/bash

message() {
    curl -X POST -H "Content-Type: application/json" -d '{
        "msg_type": "post",
        "content": {
            "post": {
                "zh_cn": {
                    "title": "后端程序部署通知",
                    "content": [
                        [
                            {
                                "tag": "text",
                                "text": "发布人：'$BUILD_USER'\n"
                            },
                            {
                                "tag": "text",
                                "text": "打包分支：'${branch_name}'\n"
                            },
                            {
                                "tag": "text",
                                "text": "部署环境：'$updateenv'\n"
                            },
                            {
                                "tag": "text",
                                "text": "部署应用：'$updateapp'\n"
                            },
                            {
                                "tag": "a",
                                "text": "详情查看",
                                "href": "http://IAmIPaddress:8081/job/'${JOB_BASE_NAME}'/'${BUILD_NUMBER}'/console"
                            }
                        ]
                    ]
                }
            }
        }
    }' https://open.feishu.cn/open-apis/bot/v2/hook/372f0db0-2a92-4342-b27a-2c3174a13781
    echo -e "\n"
}

source /etc/profile
export CI=false
echo "env="$env
echo "branch="$branch
#npm install --registry=http://IAmIPaddress:8086/repository/CSINPM
if [ ! -d $WORKSPACE/node_modules ];then
  npm install yarn 
fi
#if [];then
 npm install
#fi
if [ ! `echo $?` -eq 0 ];then
    exit 1
fi
#if [];then
 npm run build:dll:web
#fi
npm run i18n
npm run build
if [[ $? -ne 0 ]];then
   exit 1
fi

scp -r $WORKSPACE/build iamusername@IAmIPaddress:/mnt/data-1/nfs/new-rab/frontend-volume/web-$BUILD_NUMBER && \

if [[ $env =~ (.|[[:space:]]*)dev1$ ]];then
    echo "部署开发dev1 k8s"
    ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress "
        /bin/bash; \
        source /etc/profile; \
        cd /home/betack/yaml/counsel/frontend; \
        sed -i 's!\/app.*!\/app\/web-$BUILD_NUMBER\;!g' nginx-configmap.yaml; \
        kubectl apply -f nginx-configmap.yaml; \
        kubectl delete -f nginx-deploy.yaml; \
        kubectl apply -f nginx-deploy.yaml"
elif [[ $env =~ (.|[[:space:]]*)dev2$ ]];then
echo "部署开发dev2 k8s"
ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress "/bin/bash;source /etc/profile;cd /home/betack/yaml/counsel/frontend;sed -i 's!\/app.*!\/app\/web-$BUILD_NUMBER\;!g' nginx2-configmap.yaml;kubectl apply -f nginx2-configmap.yaml;kubectl delete -f nginx2-deploy.yaml;kubectl apply -f nginx2-deploy.yaml"
elif [[ $env =~ (.|[[:space:]]*)dev3$ ]];then
echo "部署开发dev3 k8s"
ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress "/bin/bash;source /etc/profile;cd /home/betack/yaml/counsel/frontend;sed -i 's!\/app.*!\/app\/web-$BUILD_NUMBER\;!g' nginx3-configmap.yaml;kubectl apply -f nginx3-configmap.yaml;kubectl delete -f nginx3-deploy.yaml;kubectl apply -f nginx3-deploy.yaml"
elif [[ $env =~ (.|[[:space:]]*)dev4$ ]];then
echo "部署开发dev4 k8s"
ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress "/bin/bash;source /etc/profile;cd /home/betack/yaml/counsel/frontend;sed -i 's!\/app.*!\/app\/web-$BUILD_NUMBER\;!g' nginx4-configmap.yaml;kubectl apply -f nginx4-configmap.yaml;kubectl delete -f nginx4-deploy.yaml;kubectl apply -f nginx4-deploy.yaml"
elif [[ $env =~ (.|[[:space:]]*)staging1$ ]];then
echo "部署开发staging1 k8s"
ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress "/bin/bash;source /etc/profile;cd /home/betack/yaml/counsel/frontend;sed -i 's!\/app.*!\/app\/web-$BUILD_NUMBER\;!g' nginx5-configmap.yaml;kubectl apply -f nginx5-configmap.yaml;kubectl delete -f nginx5-deploy.yaml;kubectl apply -f nginx5-deploy.yaml"
elif [[ $env =~ (.|[[:space:]]*)staging2$ ]];then
echo "部署开发staging2 k8s"
ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress "/bin/bash;source /etc/profile;cd /home/betack/yaml/counsel/frontend;sed -i 's!\/app.*!\/app\/web-$BUILD_NUMBER\;!g' nginx6-configmap.yaml;kubectl apply -f nginx6-configmap.yaml;kubectl delete -f nginx6-deploy.yaml;kubectl apply -f nginx6-deploy.yaml"

fi
#/home/betack/sbin/deploy.sh $branch $env frontend $JOB_BASE_NAME $BUILD_NUMBER

message