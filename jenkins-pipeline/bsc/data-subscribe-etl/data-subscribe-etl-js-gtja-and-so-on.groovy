#!groovy
/* 导入Jenkins共享库 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
def shanghai_inner_registry = "IAmIPaddress"
// def shanghai_outer_registry = "harbor.betack.com"

// 项目
def project = "bf-etl"  // HARrab镜像仓库中的项目名称
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
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', numToKeepStr: '30')  // 设置保留30天的构建历史，或者30次的构建记录
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
                   value: 'data-subscribe-etl',
                   visibleItemCount: 9
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'gtja,jsfund',
                   visibleItemCount: 10
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
            PORTAINER_API = 'https://IAmIPaddress:9443'
            // X-API-Key: ptr_ojhcFcTdOO4sNickm3n1eEnP0PnIpAYgWWiJIFB2OGM=
            PORTAINER_TOKEN = 'ptr_ojhcFcTdOO4sNickm3n1eEnP0PnIpAYgWWiJIFB2OGM='
            switch(deploy_env) {
              case 'gtja':
                STACKS_ID = '8'
              break
              case 'jsfund':
                STACKS_ID = '7'
              break
              default:
                // error "退出！"
              break
            }
            def url = "${PORTAINER_API}/api/stacks"
            props = readJSON text: sh (script: "curl -sk --location '${url}/${STACKS_ID}/file?X-API-Key=${PORTAINER_TOKEN}'", returnStdout: true).trim()
            svcYaml = readYaml text: props['StackFileContent']
            // println(svcYaml)
            // 获取endpointId值
            tempProps = readJSON text: sh (script: "curl -sk --location '${url}/${STACKS_ID}?X-API-Key=${PORTAINER_TOKEN}'", returnStdout: true).trim()
            endpointId = tempProps['EndpointId']
            // 循环处理需要部署的服务
            for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
              switch(deploy_svc_name) {
                case 'data-subscribe-etl':
                  COMPOSE_SVC_NAME = 'data-subscribe-svc-api-app'
                break
              }
              images = imageDict[deploy_svc_name]['imageName'].replaceAll(office_registry, shanghai_inner_registry)
              println(images)
              svcYaml['services'][COMPOSE_SVC_NAME]['image'] = images
              writeYaml file: 'docker-compose.yaml', data: svcYaml, overwrite: true
              sh 'cat docker-compose.yaml'
              props['StackFileContent'] = readFile 'docker-compose.yaml'
              // println(props)
              // 使用 writeJSON 将对象写入文件
              writeJSON file: 'temp.json', json: props
              // 读取文件内容
              def jsonString = readFile 'temp.json'
              println(jsonString)
              sh """
                curl -sk --location --request PUT '${url}/${STACKS_ID}?endpointId=${endpointId}' \
                --header 'Content-Type: application/json' \
                --header 'X-API-Key: ${PORTAINER_TOKEN}' \
                --data '${jsonString}'
              """
            }
          }
        }
      }
    }
  }
}

