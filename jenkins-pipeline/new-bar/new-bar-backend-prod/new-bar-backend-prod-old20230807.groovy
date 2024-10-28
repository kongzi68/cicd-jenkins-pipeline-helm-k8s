#!groovy
/* 导入Jenkins共享库 */
@Library('bf-shared-library') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress"
def hwcloud_registry = "harbor.betack.com"

// 项目
def project = "rab"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/betack/rab-backend.git"

// 认证
def harbor_auth = "443eb7ee-c21b-4e32-b449-e01d83171672"
def git_auth = "41dfa7f4-d5f9-4892-a8ba-6b0d31a7cd84"
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
              - name: docker-cmd
                mountPath: /usr/bin/docker
              - name: docker-sock
                mountPath: /var/run/docker.sock
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
          dnsConfig:
            nameservers:
            - IAmIPaddress
            - IAmIPaddress
          nodeSelector:
            is-install-docker: true
          imagePullSecrets:
          - name: harbor-outer
          - name: harbor-inner
          volumes:
            - name: docker-cmd
              hostPath:
                path: /usr/bin/docker
            - name: docker-sock
              hostPath:
                path: /var/run/docker.sock
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
                 defaultValue: 'master',
                 listSize: '10',
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
                   visibleItemCount: 4
    extendedChoice defaultValue: 'gradle-7.4.2',
                   description: '请选择 gradle 版本',
                   multiSelectDelimiter: ',',
                   name: 'GRADLE_VERSION',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_SINGLE_SELECT',
                   value: 'gradle-6.8.3,gradle-7.4.2',
                   visibleItemCount: 3
    booleanParam defaultValue: false,
                 description: '勾选此选项后，执行命令：gradle clean，清理构建环境。',
                 name: 'CLEAN_BUILD'
    booleanParam defaultValue: true,
                 description: '若需要部署到公网或外传镜像; 勾选此选项后，将会把镜像同步推送一份到华为云上自建的HARBOR镜像仓库: harbor.betack.com',
                 name: 'EXTRANET_HARBOR'
    extendedChoice defaultValue: '1',
                   description: '当分布式部署 API 服务时，需要选择启动的 API node 数量; 默认值: 1',
                   multiSelectDelimiter: ',',
                   name: 'REPLICA_COUNT',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_SINGLE_SELECT',
                   value: '1,3,5,7,11',
                   visibleItemCount: 5
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'rab-svc-api-app,rab-svc-api-app-master,rab-svc-offline-app,rab-svc-data-sync,rab-task-data-migration',
                   visibleItemCount: 6
    string description: 'rab-task-data-migration的参数，多个参数用空格分隔，【注意】每次都会默认清空上一次变量参数，如果要保持上一次变量参数，请重新填写',
           name: 'rab_TASK_DATA_MIGRATION_PARAMETER',
           trim: true
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'rab-prod-1',
                   visibleItemCount: 10
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将把 Arthas Java 诊断工具打包到镜像中',
                 name: 'ARTHAS_TOOLS'
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将开启 jmxremote 远程调试功能',
                 name: 'JMXREMOTE'
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
          // 按服务打包时去重
          def _DEPLOY_SVC_NAME = libTools.delDeploySVCName(['rab-svc-api-app', 'rab-svc-api-app-master', 'rab-svc-offline-app'], 'rab-svc-api-app')
          println(_DEPLOY_SVC_NAME)
          for (deploySVCName in _DEPLOY_SVC_NAME) {
            echo "当前正在构建服务：${deploySVCName}"
            JAR_PKG_NAME = "${deploySVCName}/build/libs/${deploySVCName}-1.1.0-SNAPSHOT.jar"
            javaCodeCompile(deploySVCName)
            echo "构建的jar包 ${deploySVCName} 信息如下："
            sh "ls -lh ${JAR_PKG_NAME}; pwd"
          }
        }
      }
    }

    stage('构建镜像上传HARBOR仓库') {
      steps {
        script{
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            switch(deploy_svc_name) {
              case ['rab-svc-offline-app','rab-svc-api-app-master']:
                TEMP_JAR_NAME = "rab-svc-api-app-1.1.0-SNAPSHOT.jar"
                JAR_PKG_NAME = "rab-svc-api-app/build/libs/${TEMP_JAR_NAME}"
              break
              default:
                TEMP_JAR_NAME = "${deploy_svc_name}-1.1.0-SNAPSHOT.jar"
                JAR_PKG_NAME = "${deploy_svc_name}/build/libs/${TEMP_JAR_NAME}"
              break
            }
            // DEPLOY_JAR_PKG_NAME = "${deploy_svc_name}-${COMMIT_SHORT_ID}-${BUILD_NUMBER}.jar"
            // DEPLOY_JAR_PKG_NAME = "${deploy_svc_name}.jar"
            images = javaCodeBuildContainerImage(jarPKGName:JAR_PKG_NAME,
                                                 tempJarName:TEMP_JAR_NAME,
                                                 deploySVCName:deploy_svc_name,
                                                 jdkVersion:JDKVERSION,
                                                 project:project,
                                                 deployJarPKGName:"${deploy_svc_name}.jar")
            imageDict.put(deploy_svc_name, images)
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
          // 拉取helm部署需要的values.yaml模板文件
          echo '正在从gitlab拉取helm更新部署用的values.yaml文件...'
          // 创建拉取jenkins devops代码用的临时目录
          sh '[ -d temp_jenkins_workspace ] || mkdir temp_jenkins_workspace'
          dir("${env.WORKSPACE}/temp_jenkins_workspace") {
            gitCheckout('ssh://git@code.betack.com:4022/devops/jenkins.git')
            sh 'pwd; ls -lh'
          }
          // helm登录harbor仓库
          loginHelmChartRegistry()
          // 循环处理需要部署的服务
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            // 循环处理需要部署的命名空间
            for (namespaces in params.DEPLOY_TO_ENV.tokenize(',')) {
              helm_values_file="temp_jenkins_workspace/new-${project}/new-${project}-backend-prod/deployToK8s/${deploy_svc_name}-values.yaml"
              copyHelmValuesFile(namespaces:namespaces,
                                 helmValuesFile:helm_values_file,
                                 deploySVCName:deploy_svc_name)
              K8S = 'hwcloud-k8s'
              K8S_AUTH = '11383954-4f2d-4d7f-b8c9-1ff73fcc9478'
              switch(deploy_svc_name) {
                case ['rab-svc-api-app']:
                  sh """
                    sed -i 's#REPLICA_COUNT#${params.REPLICA_COUNT}#' ${deploy_svc_name}-values.yaml
                  """
                break
              }

              configFileProvider([configFile(fileId: "${K8S_AUTH}", targetLocation: "kube-config.yaml")]){
                env.JMX_NODEPORT = ''
                switch(deploy_svc_name) {
                  case ['rab-svc-api-app', 'rab-svc-api-app-master']:
                    chart_version = '0.1.6'
                    k8s_rs_type = 'deploy'
                    env.JMX_NODEPORT = javaJmxRemote(k8s:K8S,
                                                     namespaces:namespaces,
                                                     deploySVCName:deploy_svc_name)
                    // 无论是否勾选 JMXREMOTE，都需要修改 values.yaml 文件中 JMX_REMOTE 的值为布尔值。
                    def _JMXREMOTE = Boolean.valueOf(params.JMXREMOTE)
                    sh "sed -i 's#JMX_REMOTE#${_JMXREMOTE}#' ${deploy_svc_name}-values.yaml"
                  break
                  case 'rab-task-data-migration':
                    chart_version = '0.1.3'
                    k8s_rs_type = 'job'
                  break
                  default:
                    chart_version = '0.1.6'
                    k8s_rs_type = 'deploy'
                    sh "sed -i 's#JMX_REMOTE#false#' ${deploy_svc_name}-values.yaml"
                  break
                }
                reserveJavaCommand(k8sRSType:k8s_rs_type,
                                   namespaces:namespaces,
                                   deploySVCName:deploy_svc_name)
                image_tag = "${COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                switch(deploy_svc_name) {
                  case 'rab-task-data-migration':
                    doDeployToK8sJob(namespaces, deploy_svc_name, image_tag, chart_version, "${params.rab_TASK_DATA_MIGRATION_PARAMETER}")
                  break
                  default:
                    doDeployToK8s(namespaces, deploy_svc_name, image_tag, chart_version)
                  break
                }
              }
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


def doDeployToK8s(deployToEnv, deploySVCName, imageTag, chartVersion) {
  // helm 部署服务
  deployWithHelmToK8sByDeploy(deploySVCName:deploySVCName,
                              imageTag:imageTag,
                              deployToEnv:deployToEnv,
                              chartName:'bf-java-project-deploy-common',
                              chartVersion:chartVersion)
}


def doDeployToK8sJob(deployToEnv, deploySVCName, imageTag, chartVersion, deploySVCOptions) {
  // helm 部署服务
  deployWithHelmToK8sByJob(deploySVCName:deploySVCName,
                           imageTag:imageTag,
                           deployToEnv:deployToEnv,
                           chartName:'bf-java-project-job-common',
                           chartVersion:chartVersion,
                           deploySVCOptions:deploySVCOptions)
}
