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

export CI=false
source /etc/profile
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

ENV1_STRING_VALUE=$(echo $env | grep -oE '[A-Za-z]{1,}')
ENV1_NUM_VALUE=$(echo $env | grep -oE '[0-9]{1,}')
NAMESPACE="new-rab-${ENV1_STRING_VALUE}-${ENV1_NUM_VALUE}"
## 传web包兼容问题
#+ 老命名空间，所有pv卷是手动创建，并通过标签选择进行绑定的，所以所有命名空间共用了一个pv，存储所有jar包与web代码
#+ 新命名空间，用的是存储类与pvc，自动申领的pv卷，是单个命名空间独享的
#+ 以上所有都是过度，后续用helm部署的方式，会直接把jar包或web代码打入镜像
NS_ARRAY=(
    new-rab-staging-3
    new-rab-staging-4
)
#+ 模仿python中 a in b
echo "${NS_ARRAY[*]}" | grep -wqF $NAMESPACE && {
    K8S_PV_NAME=$(ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress "
        source /etc/profile; \
        kubectl -n ${NAMESPACE} get pvc pvc-counsel-web-${ENV1_STRING_VALUE}-${ENV1_NUM_VALUE} -o jsonpath={.spec.volumeName};")
    K8S_JARPKG_DIR=$(ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress "
        source /etc/profile; \
        kubectl -n ${NAMESPACE} get pv ${K8S_PV_NAME} -o jsonpath={.spec.nfs.path};")
    echo "${K8S_JARPKG_DIR}"
    ssh -o "StrictHostKeyChecking no" iamusername@IAmIPaddress """
        cp -a /mnt/data-1/nfs/new-rab/frontend-volume/web-$BUILD_NUMBER "${K8S_JARPKG_DIR}/"; \
        ls -lh "${K8S_JARPKG_DIR}/";
        """
}

if [[ $env =~ (.|[[:space:]]*)dev1$ ]];then
    echo "部署开发dev1 k8s"
    ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress "/bin/bash;source /etc/profile;cd /home/betack/yaml/counsel/frontend;sed -i 's!\/app.*!\/app\/web-$BUILD_NUMBER\;!g' nginx-configmap.yaml;kubectl apply -f nginx-configmap.yaml;kubectl delete -f nginx-deploy.yaml;kubectl apply -f nginx-deploy.yaml"
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
elif [[ $env =~ (.|[[:space:]]*)staging3$ ]];then
    echo "部署开发staging3 k8s"
    ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress "/bin/bash;source /etc/profile;cd /home/betack/yaml/counsel/frontend;sed -i 's!\/app.*!\/app\/web-$BUILD_NUMBER\;!g' nginx7-configmap.yaml;kubectl apply -f nginx7-configmap.yaml;kubectl delete -f nginx7-deploy.yaml;kubectl apply -f nginx7-deploy.yaml"
elif [[ $env =~ (.|[[:space:]]*)staging4$ ]];then
    echo "部署开发staging4 k8s"
    ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress "/bin/bash;source /etc/profile;cd /home/betack/yaml/counsel/frontend;sed -i 's!\/app.*!\/app\/web-$BUILD_NUMBER\;!g' nginx8-configmap.yaml;kubectl apply -f nginx8-configmap.yaml;kubectl delete -f nginx8-deploy.yaml;kubectl apply -f nginx8-deploy.yaml"
fi
#/home/betack/sbin/deploy.sh $branch $env frontend $JOB_BASE_NAME $BUILD_NUMBER

message
