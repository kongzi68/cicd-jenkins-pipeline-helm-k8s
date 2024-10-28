#!groovy
/* 导入Jenkins共享库 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
def hwcloud_registry = "harbor.betack.com"

// 项目
def project = "rab-pf"  // HARrab镜像仓库中的项目名称
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
    // buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '30')  // 设置保留30天的构建历史，或者30次的构建记录
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', numToKeepStr: '30')  // 设置保留30天的构建历史，或者30次的构建记录
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
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将会把镜像同步推送一份到HARBOR镜像仓库: harbor.betack.com',
                 name: 'EXTRANET_HARBOR'
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'rab-svc-api-app,rab-svc-offline-app,rab-svc-timeseries-data-generator,rab-task-data-migration',
                   visibleItemCount: 8
    string description: 'migration的参数，多个参数用空格分隔，【注意】每次都会默认清空上一次变量参数，如果要保持上一次变量参数，请重新填写',
           name: 'SVC_PARAMETER',
           trim: true
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'rab-pf-dev-1,rab-pf-staging-1',
                   visibleItemCount: 7
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将开启jmxremote远程调试功能',
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
        container('podman') {
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
              DEPLOY_JAR_PKG_NAME = "${deploy_svc_name}-${COMMIT_SHORT_ID}-${BUILD_NUMBER}.jar"
              // DEPLOY_JAR_PKG_NAME = "${deploy_svc_name}.jar"
              images = javaCodeBuildContainerImage(jarPKGName: JAR_PKG_NAME,
                                                   tempJarName: TEMP_JAR_NAME,
                                                   deploySVCName: deploy_svc_name,
                                                   jdkVersion: JDKVERSION,
                                                   project: project,
                                                   deployJarPKGName: DEPLOY_JAR_PKG_NAME)
              imageDict.put(deploy_svc_name, images)
            }
          }
        }
      }
    }

    // harbor.betack.com/rabbeyond/sdk-grpc-server:36-1.6.11-SNAPSHOT_2.12
    stage('部署服务') {
      steps {
        script {
          // 拉取helm部署需要的values.yaml模板文件
          echo '正在从gitlab拉取helm更新部署用的values.yaml文件...'
          // 创建拉取jenkins devops代码用的临时目录
          sh '[ -d temp_jenkins_workspace ] || mkdir temp_jenkins_workspace'
          dir("${env.WORKSPACE}/temp_jenkins_workspace") {
            try {
              checkout([$class: 'GitSCM',
                branches: [[name: "chengdu-main"]],
                browser: [$class: 'GitLab', repoUrl: ''],
                userRemoteConfigs: [[credentialsId: "${git_auth}", url: "ssh://git@code.betack.com:4022/devops/jenkins.git"]]])
            } catch(Exception err) {
              echo err.getMessage()
              echo err.toString()
              unstable '拉取jenkins代码失败'
            }
            sh 'pwd; ls -lh'
          }

          // helm登录harbor仓库
          echo "helm登录registry仓库"
          withCredentials([usernamePassword(credentialsId: "${harbor_auth}", passwordVariable: 'password', usernameVariable: 'username')]) {
            sh """
              export HELM_EXPERIMENTAL_OCI=1
              helm registry login ${hwcloud_registry} --username ${username} --password ${password}
            """
          }

          // 循环处理需要部署的服务
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            // 循环处理需要部署的命名空间
            for (namespaces in params.DEPLOY_TO_ENV.tokenize(',')) {
              echo "用HELM部署服务: ${deploy_svc_name}到命名空间：${namespaces}"
              println(namespaces)
              helm_values_file="temp_jenkins_workspace/${project}/${project}-backend/deployToK8s/${deploy_svc_name}-values.yaml"
              sh """
                pwd
                ls -lh ${helm_values_file}
                cp ${helm_values_file} .
                ls -lh
              """
              
              configFileProvider([configFile(fileId: "fd4efaf3-23f9-4f31-a085-3e3baa9618d4", targetLocation: "kube-config.yaml")]){
                switch(deploy_svc_name) {
                  case 'rab-svc-api-app':
                    chart_version = '0.1.3'

                    // 生成jmx远程调试端口
                    def _JMXREMOTE = Boolean.valueOf("${params.JMXREMOTE}")
                    if (_JMXREMOTE) {
                      // 获取之前已有的jmx远程调试端口
                      tempEvn=namespaces.replaceAll("${project}-","")
                      try {
                        env.JMX_NODEPORT = sh (script: """
                          kubectl --kubeconfig=kube-config.yaml -n ${namespaces} get svc ${deploy_svc_name}-jmxremote-${tempEvn} -o jsonpath={.spec.ports[*].nodePort}
                          """, returnStdout: true).trim()
                        println("JMX_NODEPORT：" + env.JMX_NODEPORT)
                      } catch(Exception err) {
                        env.JMX_NODEPORT = ''
                        echo "之前未开启jmx远程调试功能，所以未获取到相应service的nodePort端口"
                      }

                      container('tools') {
                        script{
                          if(! env.JMX_NODEPORT) {
                            is_exits = true
                            while(is_exits) {
                              nodePort = Math.abs(new Random().nextInt() % 30000 % 2767) + 30000
                              // 若通的，返回true，即继续寻找可用的端口
                              script_ret = sh (script: "nc -vz IAmIPaddress ${nodePort} && echo 'true' || echo 'false'", returnStdout: true).trim()
                              // println(script_ret)
                              is_exits = Boolean.valueOf(script_ret)
                            }
                            println("设置JMX_NODEPORT为：" + nodePort)
                            env.JMX_NODEPORT = nodePort
                          }
                        }
                      }
                    }
                  break
                  default:
                    chart_version = '0.1.2'
                  break
                }

                image_tag = "${COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                switch(deploy_svc_name) {
                  case 'rab-task-data-migration':
                    doDeployToK8sJob(namespaces, deploy_svc_name, image_tag, project, chart_version, "${params.SVC_PARAMETER}")
                  break
                  default:
                    doDeployToK8s(namespaces, deploy_svc_name, image_tag, project, chart_version)
                  break
                }
              }
            }
          }
        }
      }
    }
  }
}


def doDeployToK8s(deployToEnv, deploySVCName, imageTag, project, chartVersion) {
  echo "正在部署到 " + deployToEnv + " 环境."
  CONFIG_ENV = sh (script: "echo ${deployToEnv} | awk -F'-' '{print \$2\$3}'", returnStdout: true).trim()
  println("CONFIG_ENV：" + CONFIG_ENV)
  sh """
    sed -i 's#CONFIG_ENV#${CONFIG_ENV}#' ${deploySVCName}-values.yaml
    sed -i 's#IMAGE_TAG#${imageTag}#' ${deploySVCName}-values.yaml
    sed -i 's#NAMESPACEPREFIX#${project}#' ${deploySVCName}-values.yaml
  """

  // 判断是否开启jmxremote远程调试功能
  def _JMXREMOTE = Boolean.valueOf("${params.JMXREMOTE}")
  println("JMXREMOTE：" + _JMXREMOTE)
  echo "开启jmxremote远程调试功能"
  sh """
    sed -i 's#JMX_REMOTE#${_JMXREMOTE}#' ${deploySVCName}-values.yaml
  """
  if(_JMXREMOTE) {
    sh "sed -i 's#JMX_NODEPORT#${env.JMX_NODEPORT}#' ${deploySVCName}-values.yaml"
  }

  sh "cat ${deploySVCName}-values.yaml"
  echo "正在执行helm命令，更新${deploySVCName}服务版本${imageTag}到${deployToEnv}环境."

  // 判断是否已经部署过
  def getDeployName = sh (script: "helm --kubeconfig kube-config.yaml -n ${deployToEnv} list -f ${deploySVCName} -q", returnStdout: true).trim()
  println("getDeployName：" + getDeployName)
  println("deploySVCName：" + deploySVCName)
  if (getDeployName == deploySVCName) {
    echo "正在对服务${deploySVCName}进行升级"
    deployType = 'upgrade'
  } else {
    echo "正在对服务${deploySVCName}进行部署"
    deployType = 'install'
  }
  // **私募产品线 charts 复用 igw 项目的 charts
  println("**私募产品线 rab-pf 的 charts 复用 igw 项目的 charts")
  sh """
    helm pull oci://harbor.betack.com/igw-charts/${deploySVCName} --version ${chartVersion}
    helm --kubeconfig kube-config.yaml ${deployType} ${deploySVCName} -n ${deployToEnv} -f ${deploySVCName}-values.yaml ${deploySVCName}-${chartVersion}.tgz
  """
}


def doDeployToK8sJob(deployToEnv, deploySVCName, imageTag, project, chartVersion, deploySVCOptions) {
  /* 
    helm 更新 job 问题，因为一些不可变的资源导致不能使用 helm upgrade
    因此：job的更新，每次helm delete后新建
    1. Cannot upgrade a release with Job #7725 https://github.com/helm/helm/issues/7725
    2. Can't update Jobs, field is immutable #89657 https://github.com/kubernetes/kubernetes/issues/89657
    3. Helm 3 upgrade failed - Immutable field. #7173 https://github.com/helm/helm/issues/7173
  */
  echo "正在部署到 " + deployToEnv + " 环境."
  CONFIG_ENV = sh (script: "echo ${deployToEnv} | awk -F'-' '{print \$2\$3}'", returnStdout: true).trim()
  println("CONFIG_ENV：" + CONFIG_ENV)
  sh """
    sed -i 's#CONFIG_ENV#${CONFIG_ENV}#' ${deploySVCName}-values.yaml
    sed -i 's#IMAGE_TAG#${imageTag}#' ${deploySVCName}-values.yaml
    sed -i 's#NAMESPACEPREFIX#${project}#' ${deploySVCName}-values.yaml
    #+ 若deploySVCOptions传进来的值为空，下面也会被替换为空
    sed -i 's#${deploySVCName}-OPTIONS#${deploySVCOptions}#g' ${deploySVCName}-values.yaml
    cat ${deploySVCName}-values.yaml
    echo "正在执行helm命令，更新${deploySVCName}服务版本${imageTag}到${deployToEnv}环境."
  """
  
  // 判断是否已经部署过
  def getDeployName = sh (script: "helm --kubeconfig kube-config.yaml -n ${deployToEnv} list -f ${deploySVCName} -q", returnStdout: true).trim()
  println("getDeployName：" + getDeployName)
  println("deploySVCName：" + deploySVCName)
  if (getDeployName == deploySVCName) {
    echo "正在删除JOB ${deploySVCName}"
    sh "helm --kubeconfig kube-config.yaml -n ${deployToEnv} delete ${deploySVCName}"
  }
  echo "正在对服务${deploySVCName}进行部署"
  // **私募产品线 charts 复用 igw 项目的 charts
  println("**私募产品线 rab-pf 的 charts 复用 igw 项目的 charts")
  sh """
    helm pull oci://harbor.betack.com/igw-charts/${deploySVCName} --version ${chartVersion}
    helm --kubeconfig kube-config.yaml install ${deploySVCName} -n ${deployToEnv} -f ${deploySVCName}-values.yaml ${deploySVCName}-${chartVersion}.tgz
  """
}


