#!groovy
/* 导入Jenkins共享库 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
def shanghai_inner_registry = "IAmIPaddress"
def shanghai_outer_registry = "harbor.betack.com"

// 项目
def project = "rab"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/betack/rab-backend.git"
def imageDict = [:]

pipeline {
  agent {
    kubernetes {
      cloud "kubernetes"
      // cloud "shanghai-idc-k8s"  // 选择名字是 shanghai-idc-k8s 的cloud
      defaultContainer 'jnlp'
      workspaceVolume persistentVolumeClaimWorkspaceVolume(claimName: 'jenkins-agent-workspace', readOnly: false)
      // 注意：ubuntu-jenkins-agent 镜像，需要安装中文语言包
      yaml """
        apiVersion: v1
        kind: Pod
        metadata:
          name: jenkins-slave
          labels:
            app: jenkins-agent
        spec:
          containers:
          - name: jnlp
            image: "${office_registry}/libs/ubuntu-jenkins-agent:latest-nofrontend"
            imagePullPolicy: Always
            resources:
              limits: {}
              requests:
                memory: "1500Mi"
                cpu: "500m"
            env:
              - name: "file.encoding"
                value: "UTF-8"
              - name: "LANG"
                value: "zh_CN.UTF-8"
              - name: "sun.jnu.encoding"
                value: "UTF-8"
            tty: true
            volumeMounts:
              - name: gradles
                mountPath: /opt/gradle
          - name: tools
            image: "${office_registry}/libs/alpine:tools-sshd"
            imagePullPolicy: Always
            env:
              - name: "file.encoding"
                value: "UTF-8"
              - name: "LANG"
                value: "zh_CN.UTF-8"
              - name: "sun.jnu.encoding"
                value: "UTF-8"
            tty: true
          - name: podman
            image: "${office_registry}/libs/podman/stable:v3.4.2"
            env:
              - name: "file.encoding"
                value: "UTF-8"
              - name: "LANG"
                value: "zh_CN.UTF-8"
              - name: "sun.jnu.encoding"
                value: "UTF-8"
            securityContext:
              privileged: true
            tty: true
            volumeMounts:
              - name: podman-sock
                mountPath: /var/run/podman
              - name: podman-registries-conf
                mountPath: /etc/containers/registries.conf
              - name: podman-storage
                mountPath: /var/lib/containers/storage
          dnsConfig:
            nameservers:
            - IAmIPaddress
            - IAmIPaddress
          nodeSelector:
            is-install-docker: true
          imagePullSecrets:
          - name: harbor-inner
          volumes:
            - name: podman-sock
              hostPath:
                path: /var/run/podman
            - name: podman-registries-conf
              hostPath:
                path: /etc/containers/registries.conf
            - name: podman-storage
              hostPath:
                path: /var/lib/containers/storage
            - name: gradles
              persistentVolumeClaim:
                claimName: jenkins-gradle
      """.stripIndent()
    }
  }

  // 设置pipeline Jenkins选项参数
  options {
    skipDefaultCheckout true          // 忽略默认的checkout
    skipStagesAfterUnstable()         // 忽略报错后面的步骤
    // retry(2)                          // 重试次数
    disableConcurrentBuilds()         // java项目禁止并发构建：主要是gradle有锁，导致无法并发构建
    timestamps()                      // 添加时间戳
    timeout(time: 60, unit:'MINUTES') // 设置此次发版运行20分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', numToKeepStr: '30')  // 设置保留30次的构建记录
  }

  parameters {
    string description: '服务需要部署到生产环境shanghai，请输入docker镜像TAG。\n比如：docker镜像 IAmIPaddress:8765/rab/rab-svc-api-app:6b2cf841e1-5 中冒号后面的 6b2cf841e1-5 是镜像TAG。',
           name: 'SVC_IMAGE_TAG'
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'rab-svc-api-app,rab-svc-offline-app,rab-svc-timeseries-data-generator,rab-task-data-migration',
                   visibleItemCount: 9
    string description: 'rab-task-data-migration的参数，多个参数用空格分隔，【注意】每次都会默认清空上一次变量参数，如果要保持上一次变量参数，请重新填写',
           name: 'rab_TASK_DATA_MIGRATION_PARAMETER',
           trim: true
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'shanghai-IDC-rab-api3-docker-rancher',
                   visibleItemCount: 10
    booleanParam defaultValue: false,
                 description: '有以下几种情况需要勾选此选项：1.新增了服务，2.更改了服务名称。',
                 name: 'IS_UPDATE_SERVICE_LIST'
  }

  stages {
    stage('上传镜像到生产PROD环境') {
      when {
        expression {
          return (params.DEPLOY_TO_ENV != '')
        }
      }

      steps {
        container('podman') {
          script{
            for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
              images = imagePullToProd(project: project,
                                       deploySVCName: deploy_svc_name,
                                       imageTag: "${params.SVC_IMAGE_TAG}")
              imageDict.put(deploy_svc_name, images)
            }
          }
        }
      }
    }

    stage('部署服务') {
      agent {
        kubernetes {
          // cloud "kubernetes"
          cloud "shanghai-idc-k8s"  // 选择名字是 shanghai-idc-k8s 的cloud
          defaultContainer 'jnlp'
          showRawYaml "false"
          workspaceVolume persistentVolumeClaimWorkspaceVolume(claimName: 'jenkins-agent-workspace', readOnly: false)
          // 注意：ubuntu-jenkins-agent 镜像，需要安装中文语言包
          yaml """
            apiVersion: v1
            kind: Pod
            metadata:
              name: jenkins-slave
              labels:
                app: jenkins-agent
            spec:
              containers:
              - name: jnlp
                image: "${shanghai_inner_registry}/libs/ubuntu-jenkins-agent:latest-nofrontend"
                imagePullPolicy: Always
                resources:
                  limits: {}
                  requests:
                    memory: "1500Mi"
                    cpu: "500m"
                env:
                  - name: "file.encoding"
                    value: "UTF-8"
                  - name: "LANG"
                    value: "zh_CN.UTF-8"
                  - name: "sun.jnu.encoding"
                    value: "UTF-8"
                tty: true
              dnsConfig:
                nameservers:
                - IAmIPaddress
                - IAmIPaddress
              nodeSelector:
                is-install-docker: true
              imagePullSecrets:
              - name: harbor-inner
              - name: harbor-outer
          """.stripIndent()
        }
      }

      when {
        expression {
          return (params.DEPLOY_TO_ENV != '')
        }
      }

      steps {
        script {
          sh "pwd; echo \$(date) >> tmp.txt"  // 执行这条命令，Jenkins才会自动创建工作目录
          // 循环处理需要部署的命名空间
          for (deploy_env in params.DEPLOY_TO_ENV.tokenize(',')) {
            switch(deploy_env) {
              case 'shanghai-IDC-rab-api3-docker-rancher':
                RANCHER_IP = 'IAmIPaddress'
                // 账号API Keys
                // AccessKey: 12D4F412C37E942B52EF   SecretKey: qZatbbFsguWZExzREcYPydQkY4ZsGdSp3mjvZzmf
                AUTHENTICATION = 'a48ba552-69e6-4192-8777-dcb59a5136b7'
                RANCHER_URL = "http://${RANCHER_IP}:8080/v2-beta/projects"
                PROJECT_ID = '1a5'
                STACKS_ID = '1st5'
                RANCHER_SPACES_DIR = 'rab'
                // 环境API Keys
                RANCHER_ACCESS_KEY = 'F6CF2AA12A2A14397C22'
                RANCHER_SECRET_KEY = 'yRmXcpaA2gTrw8k6zQeJPfv8Uu3jNjYRxFwuqN9Z'
              break
              default:
                // error "退出！"
              break
            }
            def url = "${RANCHER_URL}/${PROJECT_ID}/stacks/${STACKS_ID}"
            def service_id_map = rancherGetStacksServiceMap(RANCHER_IP, STACKS_ID, url, AUTHENTICATION)
            println(service_id_map)
            // 循环处理需要部署的服务
            for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
              SERVICE_ID = service_id_map.get(deploy_svc_name)
              if (SERVICE_ID == null) {
                echo "Jenkins中配置的服务名称：${deploy_svc_name}与Rancher中的服务名称不匹配，需检查确认！"
                echo "当前跳过服务：${deploy_svc_name}的部署"
                continue
              }
              textmod = "action=exportconfig&serviceIds=${SERVICE_ID}"
              props = readJSON text: httpRequestFunc(url, AUTHENTICATION, textmod)
              svcYaml = readYaml text: props['dockerComposeConfig']
              images = imageDict[deploy_svc_name]['imageName'].replaceAll(office_registry, shanghai_inner_registry)
              svcYaml['services'][deploy_svc_name]['image'] = images
              println(images)
              // rab-task-data-migration 处理
              if (deploy_svc_name == 'rab-task-data-migration') {
                command = svcYaml['services'][deploy_svc_name]['command']
                elementToFind = "-jar"
                index = command.indexOf(elementToFind)
                if (index >= 0) {
                  index += 1
                  newCommand = command[0..index]
                  parameterList = params.rab_TASK_DATA_MIGRATION_PARAMETER.tokenize(' ')
                  newCommand = newCommand.plus(parameterList)
                  println("服务${deploy_svc_name}新的command：" + newCommand)
                  svcYaml['services'][deploy_svc_name]['command'] = newCommand
                }
              }
              writeYaml file: 'docker-compose.yaml', data: svcYaml, overwrite: true
              sh "cat docker-compose.yaml"
              sh "rancher-compose --url http://${RANCHER_IP}:8080/v1 --access-key ${RANCHER_ACCESS_KEY} --secret-key ${RANCHER_SECRET_KEY} \
                -f docker-compose.yaml -p ${RANCHER_SPACES_DIR} up --upgrade --pull --confirm-upgrade -d ${deploy_svc_name}"
            }
          }
        }
      }
    }
  }
}

