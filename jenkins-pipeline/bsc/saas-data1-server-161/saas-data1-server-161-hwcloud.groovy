#!groovy
/* 导入Jenkins共享库，默认导入main分支 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
def hwcloud_registry = "harbor.betack.com"

// 数据通用API **数据管理-更新-发布 项目数据对接
// 项目
def project = "bf-bsc"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/betack/saas-data.git"
def imageDict = [:]

pipeline {
  agent {
    kubernetes {
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
    timeout(time: 30, unit:'MINUTES') // 设置此次发版运行20分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '30')  // 设置保留30天的构建历史，或者30次的构建记录
  }

  parameters {
    gitParameter branch: '',
                 branchFilter: '.*',
                 defaultValue: 'main',
                 listSize: '6',
                 description: '选择需要发布的代码分支',
                 name: 'BRANCH_TAG',
                 quickFilterEnabled: true,
                 selectedValue: 'NONE',
                 sortMode: 'NONE',
                 tagFilter: '*',
                 type: 'PT_BRANCH',
                 useRepository: "${git_address}"
    extendedChoice defaultValue: '11',
                   description: '请选择 JDK 版本',
                   multiSelectDelimiter: ',',
                   name: 'JDK_VERSION',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_SINGLE_SELECT',
                   value: '8,11,17',
                   visibleItemCount: 5
    extendedChoice defaultValue: 'gradle-7.4.2',
                   description: '请选择 gradle 版本',
                   multiSelectDelimiter: ',',
                   name: 'GRADLE_VERSION',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_SINGLE_SELECT',
                   value: 'gradle-6.8.3,gradle-7.4.2',
                   visibleItemCount: 5
    booleanParam defaultValue: false,
                 description: '勾选此选项后，执行命令：gradle clean，清理构建环境。',
                 name: 'CLEAN_BUILD'
    booleanParam defaultValue: true,
                 description: '若需要部署到公网或外传镜像; 勾选此选项后，将会把镜像同步推送一份到华为云上自建的HARBOR镜像仓库: harbor.betack.com',
                 name: 'EXTRANET_HARBOR'
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'saas-data-server,data-subscriber-client,etl-listener,data-migration-job,data-meta-import-job',
                   visibleItemCount: 8
    string description: 'data-meta-import-job 参数。多个参数用空格分隔，【注意】每次都会默认清空上一次变量参数，如果要保持上一次变量参数，请重新填写',
           name: 'DATA_META_IMPORT_JOB_PARAMETER',
           trim: true
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'IAmIPaddress',
                   visibleItemCount: 7
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将把 Arthas Java 诊断工具打包到镜像中',
                 name: 'ARTHAS_TOOLS'
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将开启jmxremote远程调试功能',
                 name: 'JMXREMOTE'
    booleanParam defaultValue: false,
                 description: '有以下几种情况需要勾选此选项：1.新增了服务，2.更改了服务名称。',
                 name: 'IS_UPDATE_SERVICE_LIST'
  }

  stages {
    stage('拉取代码') {
      steps {
        script {
          checkWhetherToContinue()
          echo '正在拉取代码...'
          env.COMMIT_SHORT_ID = gitCheckout(git_address, params.BRANCH_TAG, true)
          println(env.COMMIT_SHORT_ID)
        }
      }
    }

    stage('环境准备') {
      steps {
        script {
          // 切换 java 版本
          JDKVERSION = selectJavaVersion(office_registry)
          println(JDKVERSION)
          // 执行命令 gradle clean
          gradleClean()
        }
      }
    }

    stage('代码编译打包') {
      steps {
        echo '正在构建...'
        script {
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            echo "当前正在构建服务：${deploy_svc_name}"
            switch(deploy_svc_name) {
              case 'saas-data-server':
                deploy_svc_name = 'data-etl-server'
              break
            }
            JAR_PKG_NAME = "${deploy_svc_name}/build/libs/${deploy_svc_name}-1.0-SNAPSHOT.jar"
            javaCodeCompile(deploy_svc_name)
            echo "构建的jar包 ${deploy_svc_name} 信息如下："
            sh "ls -lh ${JAR_PKG_NAME}; pwd"
          }
        }
      }
    }

    stage('构建镜像上传HARBOR仓库') {
      steps {
        container('podman') {
          script{
            for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
              switch(deploy_svc_name) {
                case 'saas-data-server':
                  deploy_svc_name = 'data-etl-server'
                break
                case 'etl-listener':
                  JDKVERSION = "${office_registry}/libs/zulu-openjdk:11-focal-tools"
                break
              }
              TEMP_JAR_NAME = "${deploy_svc_name}-1.0-SNAPSHOT.jar"
              JAR_PKG_NAME = "${deploy_svc_name}/build/libs/${TEMP_JAR_NAME}"
              DEPLOY_JAR_PKG_NAME = "${deploy_svc_name}.jar"
              images = javaCodeBuildContainerImage(jarPKGName:JAR_PKG_NAME,
                                                  tempJarName:TEMP_JAR_NAME,
                                                  deploySVCName:deploy_svc_name,
                                                  jdkVersion:JDKVERSION,
                                                  project:project,
                                                  deployJarPKGName:DEPLOY_JAR_PKG_NAME)
              imageDict.put(deploy_svc_name, images)
            }
          }
        }
      }
    }

    stage('部署服务') {
      when {
        expression {
          return (params.DEPLOY_TO_ENV != '')
        }
      }
      steps {
        script {
          // 循环处理需要部署的命名空间
          for (deploy_env in params.DEPLOY_TO_ENV.tokenize(',')) {
            switch(deploy_env) {
              case 'IAmIPaddress':
                // 账号API Keys
                AUTHENTICATION = 'ef8e2f64-6917-4520-a508-ef2398678662'
                RANCHER_URL = "http://${deploy_env}:8080/v2-beta/projects"
                PROJECT_ID = '1a5'
                STACKS_ID = '1st13'
                RANCHER_SPACES_DIR = 'saas-data1'
                // 环境API Keys
                RANCHER_ACCESS_KEY = '24E21AAC4787811A1D26'
                RANCHER_SECRET_KEY = 'MBh2oU9AsQKdaoSfMJ7Zh6rCrorFWmjJsrzpJBZn'
              break
              default:
                // error "退出！"
              break
            }
            def url = "${RANCHER_URL}/${PROJECT_ID}/stacks/${STACKS_ID}"
            def service_id_map = rancherGetStacksServiceMap(deploy_env, STACKS_ID, url, AUTHENTICATION)
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
              switch(deploy_svc_name) {
                case 'saas-data-server':
                  image_name = 'data-etl-server'
                break
                default:
                  image_name = deploy_svc_name
                break
              }
              svcYaml['services'][deploy_svc_name]['image'] = imageDict[image_name]['extranetImageName']
              // data-meta-import-job 处理
              if (deploy_svc_name == 'data-meta-import-job') {
                command = svcYaml['services'][deploy_svc_name]['command']
                elementToFind = "-jar"
                index = command.indexOf(elementToFind)
                if (index >= 0) {
                  index += 1
                  newCommand = command[0..index]
                  parameterList = params.DATA_META_IMPORT_JOB_PARAMETER.tokenize(' ')
                  newCommand = newCommand.plus(parameterList)
                  println("服务${deploy_svc_name}新的command：" + newCommand)
                  svcYaml['services'][deploy_svc_name]['command'] = newCommand
                } 
              }
              writeYaml file: 'docker-compose.yaml', data: svcYaml, overwrite: true
              sh "cat docker-compose.yaml"
              sh "rancher-compose --url http://${deploy_env}:8080/v1 --access-key ${RANCHER_ACCESS_KEY} --secret-key ${RANCHER_SECRET_KEY} \
                -f docker-compose.yaml -p ${RANCHER_SPACES_DIR} up --upgrade --pull --confirm-upgrade -d ${deploy_svc_name}"
            }
          }
        }
      }
    }
  }

  post {
    success {
      script {
        // 在构建结尾处，输出华为云harbor仓库镜像地址
        libTools.printImageList(imageDict)
      }
    }
  }
}

