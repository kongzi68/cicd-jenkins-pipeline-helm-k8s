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

OLD_IFS="$IFS"
IFS=","
arr=($updateapp)
arr2=($updateenv)
#arr3=($migration_parameter)
IFS="$OLD_IFS"

#OLD_IFS2="$IFS2"
#IFS2=","
#arr2=($updateenv)
#IFS2="$OLD_IFS2"

export SUFFIX=""

if [[ ! $branch_name ]]; then  
  echo "branch_name is null, use master"
  branch_name=master
else  
  echo "checkout to $branch_name"  
  git fetch --all
  git checkout -f $branch_name
  git pull
  export SUFFIX="-feature"
  echo "branch_suffix="$branch_suffix
  if [[ $branch_suffix != "" ]]; then   

  export SUFFIX="-"$branch_suffix  
  echo "SUFFIX "$SUFFIX
  fi
fi    

source /home/betack/.bash_profile 
#export PATH=$PATH:/home/betack/rancher-compose-v0.12.5/

backpath=/data2t/jars/rab-backend
frontpath=/data2t/jars/www

if [[ ${arr} == "" ]];then
  echo "请选择要升级的应用"
  exit 1
fi

echo "jdk11="$jdk11
if [[ $jdk11 == false ]];then
  echo "使用jdk8环境"
  sudo update-alternatives --set java java-1.8.0-openjdk.x86_64
fi

export PATH=/opt/gradle-7.6/bin:$PATH
if $clean_build;then
   echo "使用gradle clean环境"
   gradle -v
   gradle clean
fi

gradle clean build -x test 
#echo "zz="$?
if [[ $? -ne 0 ]];then
   echo "编译报错"
   exit 1
fi

for svc_name in ${arr[@]};do
    ## 传jar包
    if [[ $svc_name == 'rab-svc-offline-app' ]];then
        scp $WORKSPACE/rab-svc-api-app/build/libs/*.jar iamusername@IAmIPaddress:/mnt/data-1/nfs/new-rab/backend-volume/app/${svc_name}-$BUILD_NUMBER.jar
    else
        scp $WORKSPACE/${svc_name}/build/libs/*.jar iamusername@IAmIPaddress:/mnt/data-1/nfs/new-rab/backend-volume/app/${svc_name}-$BUILD_NUMBER.jar 
    fi
    if [ $? -eq 0 ];then
        echo "复制 JAR 包到 IAmIPaddress /mnt/data-1/nfs/new-rab/backend-volume/app/${svc_name}-$BUILD_NUMBER.jar 成功。" 
    else
        echo "复制 JAR 包到 IAmIPaddress /mnt/data-1/nfs/new-rab/backend-volume/app/${svc_name}-$BUILD_NUMBER.jar 失败..." 
    fi

    #+ 遍历处理每个服务需要部署的环境
    for env_name in ${arr2[@]};do
        echo "正在部署deploy ${svc_name} to ${env_name}"
        
        ENV1_STRING_VALUE=$(echo $env_name | grep -oE '[A-Za-z]{1,}')
        ENV1_NUM_VALUE=$(echo $env_name | grep -oE '[0-9]{1,}')
        #+ 针对特殊命名空间进行处理
        if [ ${env_name} = 'testzz' -o  ${env_name} = 'zz' ];then
            ENV_STRING=${ENV1_STRING_VALUE}
        else
            ENV_STRING=${ENV1_STRING_VALUE}-${ENV1_NUM_VALUE}
        fi
        NAMESPACE="new-rab-${ENV_STRING}"

        ## 传jar包兼容问题
        #+ 老命名空间，所有pv卷是手动创建，并通过标签选择进行绑定的，所以所有命名空间共用了一个pv，存储所有jar包
        #+ 新命名空间，用的是存储类与pvc，自动申领的pv卷，是单个命名空间独享的
        #+ 以上所有都是过度，后续用helm部署的方式，会直接把jar包打入镜像
        NS_ARRAY=(
            new-rab-staging-3
            new-rab-staging-4
        )
        #+ 模仿python中 a in b
        echo "${NS_ARRAY[*]}" | grep -wqF $NAMESPACE && {
            K8S_PV_NAME=$(ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress "
                source /etc/profile; \
                kubectl -n ${NAMESPACE} get pvc pvc-java-new-rab-${ENV_STRING} -o jsonpath={.spec.volumeName};")
            K8S_JARPKG_DIR=$(ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress "
                source /etc/profile; \
                kubectl -n ${NAMESPACE} get pv ${K8S_PV_NAME} -o jsonpath={.spec.nfs.path};")
            echo "${K8S_JARPKG_DIR}"
            ssh -o "StrictHostKeyChecking no" iamusername@IAmIPaddress """
                cp /mnt/data-1/nfs/new-rab/backend-volume/app/${svc_name}-$BUILD_NUMBER.jar "${K8S_JARPKG_DIR}/";
                """
        }

        SVC_YAML_NAME="${svc_name}-${ENV_STRING}-deploy.yaml"
        echo "${SVC_YAML_NAME}"
        
        ## 处理所有服务相同部分
        echo "DO：处理所有服务相同部分"
        ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress """
            cd /home/betack/yaml/new-rab/backend; \
            cp ${svc_name}-deploy-Template.yaml ${SVC_YAML_NAME}; \
            sed -i 's#ENV1#${ENV_STRING}#g' ${SVC_YAML_NAME}; \
            sed -i 's#${svc_name}-.*.jar#${svc_name}-$BUILD_NUMBER.jar#g' ${SVC_YAML_NAME};
            """

        ## 处理服务在k8s中的 command 部分，java 启动服务的环境变量部分
        #+ 特别注意：后续若需要修改k8s中服务的java启动环境变量，需要直接在k8s中进行编辑，这样修改的变量会立即生效
        #+ 使用命令: kubectl -n namespace edit deploy svc_name
        #+ 对于新的命名空间，先发版，只是不能获取到 K8S_JAVA_SVC_ENV 的值，会导致服务第一次启动失败，直接用上面的命令去改一次即可
        #+ 下次更新，将从k8s中获取到最新的环境变量部分
        echo "DO：处理服务在k8s中的 command 部分，java 启动服务的环境变量部分"
        TEMP_SVC_NAME=$(echo ${svc_name} | grep -Eo 'api|generator|data-sync|offline|svc-migrate-to-beyond|embed|migration')
        if [[ ${TEMP_SVC_NAME} == 'migration' ]];then
            K8S_RS_TYPE="job"
        else
            K8S_RS_TYPE="deploy"
        fi
        K8S_RS_NAME="new-rab-${TEMP_SVC_NAME}-${ENV_STRING}"
        echo "命名空间为: ${NAMESPACE}, 资源名称: ${K8S_RS_NAME}"
        echo "${K8S_RS_TYPE}, ${K8S_RS_NAME}"
        K8S_JAVA_SVC_ENV=$(ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress "
        	source /etc/profile; \
            kubectl -n ${NAMESPACE} get ${K8S_RS_TYPE} ${K8S_RS_NAME} -o jsonpath={.spec.template.spec.containers[*].command} | jq -r '.[-1]' | sed -rn 's#/usr/local/bin/java(.*)-jar.*#\1# p';")
        #+ 去除两边的空格
        K8S_JAVA_SVC_ENV=$(echo ${K8S_JAVA_SVC_ENV} | awk '$1=$1')
        echo "获取到的服务java启动变量为:: ${K8S_JAVA_SVC_ENV}"
        #+ 替换java启动服务的环境变量
        ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress """
            cd /home/betack/yaml/new-rab/backend; \
            sed -i 's#JAVA_SVC_ENV#${K8S_JAVA_SVC_ENV}#g' ${SVC_YAML_NAME};
            """
        
        ## 处理jar包后接参数的服务
        echo "DO：处理jar包后接参数的服务"
        if [[ $svc_name == "rab-task-data-migration" ]];then    
            ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress """
                cd /home/betack/yaml/new-rab/backend; \
                sed -i 's#JAR_PARAMETER#${migration_parameter}#g' ${SVC_YAML_NAME};
                """
        elif [[ $svc_name == "rab-svc-migrate-to-beyond" ]];then
            ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress """
                cd /home/betack/yaml/new-rab/backend; \
                sed -i 's#JAR_PARAMETER#${migrate_to_beyond_parameter}#g' ${SVC_YAML_NAME};
                """
        fi
        
        ## 部署服务或更新服务
        echo "DO：部署服务或更新服务"
        #+ rab-task-data-migration 是job，删除后新建
        if [ $svc_name = "rab-task-data-migration" ];then    
            ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress """
                source /etc/profile; \
                cd /home/betack/yaml/new-rab/backend; \
                echo "本次部署服务 ${svc_name} 到 ${env_name} 的 YAML 文件内容如下："; \
                cat ${SVC_YAML_NAME}; \
                kubectl delete -f ${SVC_YAML_NAME}; \
                kubectl apply -f ${SVC_YAML_NAME}
                """
        else
            ssh -o "StrictHostKeyChecking no" betack@IAmIPaddress """
                source /etc/profile; \
                cd /home/betack/yaml/new-rab/backend; \
                echo "本次部署服务 ${svc_name} 到 ${env_name} 的 YAML 文件内容如下："; \
                cat ${SVC_YAML_NAME}; \
                kubectl apply -f ${SVC_YAML_NAME}
                """
        fi

        echo "部署deploy ${svc_name} to ${env_name} 完成。"
    done
done

#message
