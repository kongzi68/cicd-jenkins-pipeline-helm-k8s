#!groovy
/* 导入Jenkins共享库 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
def hwcloud_registry = "harbor.betack.com"

// 项目
def project = "rabbeyond"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/betack/ai_backend.git"

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
    extendedChoice defaultValue: '17',
                   description: '请选择 JDK 版本',
                   multiSelectDelimiter: ',',
                   name: 'JDK_VERSION',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_SINGLE_SELECT',
                   value: '8,11,17',
                   visibleItemCount: 4
    extendedChoice defaultValue: 'gradle-8.2.1',
                   description: '请选择 gradle 版本',
                   multiSelectDelimiter: ',',
                   name: 'GRADLE_VERSION',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_SINGLE_SELECT',
                   value: 'gradle-6.8.3,gradle-7.4.2,gradle-8.2.1',
                   visibleItemCount: 3
    booleanParam defaultValue: false,
                 description: '勾选此选项后，执行命令：gradle clean，清理构建环境。',
                 name: 'CLEAN_BUILD'
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'betack-ai-backend',
                   visibleItemCount: 6
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'betanlp-demo-1',
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
          for (deploySVCName in params.DEPLOY_SVC_NAME.tokenize(',')) {
            echo "当前正在构建服务：${deploySVCName}"
            JAR_PKG_NAME = "build/libs/ai_backend-1.0-SNAPSHOT.jar"
            javaCodeCompile()
            echo "构建的jar包 ${deploySVCName} 信息如下："
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
              TEMP_JAR_NAME = "ai_backend-1.0-SNAPSHOT.jar"
              JAR_PKG_NAME = "build/libs/${TEMP_JAR_NAME}"
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
            gitCheckout('ssh://git@code.betack.com:4022/devops/jenkins.git', 'chengdu-main')
            sh 'pwd; ls -lh'
          }
          // helm登录harbor仓库
          loginHelmChartRegistry()
          // 循环处理需要部署的服务
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            // 循环处理需要部署的命名空间
            for (namespaces in params.DEPLOY_TO_ENV.tokenize(',')) {
              helm_values_file="temp_jenkins_workspace/${project}/betack-ai-backend/deployToK8s/${deploy_svc_name}-values.yaml"
              copyHelmValuesFile(namespaces:namespaces,
                                 helmValuesFile:helm_values_file,
                                 deploySVCName:deploy_svc_name)
              // chengdu office k8s
              K8S_AUTH = 'fd4efaf3-23f9-4f31-a085-3e3baa9618d4'
              sh """
                sed -i 's#DATASTORAGECLASSNAME#nfs-client-retain#' ${deploy_svc_name}-values.yaml
              """
              configFileProvider([configFile(fileId: "${K8S_AUTH}", targetLocation: "kube-config.yaml")]){
                env.JMX_NODEPORT = ''
                switch(deploy_svc_name) {
                  case ['betack-ai-backend']:
                    chart_version = '0.1.3'
                    k8s_rs_type = 'deploy'
                    env.JMX_NODEPORT = javaJmxRemote(k8s: 'cdk8s',
                                                     namespaces: namespaces,
                                                     deploySVCName: deploy_svc_name)
                    // 无论是否勾选 JMXREMOTE，都需要修改 values.yaml 文件中 JMX_REMOTE 的值为布尔值。
                    def _JMXREMOTE = Boolean.valueOf(params.JMXREMOTE)
                    sh "sed -i 's#JMX_REMOTE#${_JMXREMOTE}#' ${deploy_svc_name}-values.yaml"
                  break
                  default:
                    chart_version = '0.1.3'
                    k8s_rs_type = 'deploy'
                    sh "sed -i 's#JMX_REMOTE#false#' ${deploy_svc_name}-values.yaml"
                  break
                }
                reserveJavaCommand(k8sRSType:k8s_rs_type,
                                   namespaces:namespaces,
                                   deploySVCName:deploy_svc_name)
                image_tag = "${COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                doDeployToK8s(namespaces, deploy_svc_name, image_tag, chart_version)
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