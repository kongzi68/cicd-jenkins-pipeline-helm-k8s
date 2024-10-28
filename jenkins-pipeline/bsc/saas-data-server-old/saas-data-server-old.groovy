#!groovy
/* 导入Jenkins共享库，默认导入main分支 */
@Library('bf-shared-library') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress"
def hwcloud_registry = "harbor.betack.com"

// 数据通用API **数据管理-更新-发布 项目数据对接
// 项目
def project = "bf-bsc"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/betack/saas-data.git"

// 认证
def harbor_auth = "443eb7ee-c21b-4e32-b449-e01d83171672"
def git_auth = "41dfa7f4-d5f9-4892-a8ba-6b0d31a7cd84"

// 自定义全局变量
def namespaces_list = []
def ip_list_rancher = []
def ip_list_docker = []

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
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将会把镜像同步推送一份到HARBOR镜像仓库: harbor.betack.com',
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
                   value: 'saasdata-dev-1,saasdata-dev-2,saasdata-staging-1,saasdata-staging-2,prod-IAmIPaddress',
                   visibleItemCount: 7
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将把 Arthas Java 诊断工具打包到镜像中',
                 name: 'ARTHAS_TOOLS'
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
          env.GIT_COMMIT_MSG = gitCheckout(git_address, params.BRANCH_TAG, true)
          println(env.GIT_COMMIT_MSG)
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

            try {
              // sh "gradle rab-svc-migrate-to-beyond:bootJar -x test"
              sh """
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:/usr/local/games:/snap/bin:/opt/gradle/${params.GRADLE_VERSION}/bin
                export LANG=zh_CN.UTF-8
                export LC_ALL=zh_CN.UTF-8
                export GRADLE_USER_HOME="${WORKSPACE}/.gradle"
                # export -p
                gradle --no-daemon -g "${WORKSPACE}/.gradle" ${deploy_svc_name}:clean bootJar -x test
              """
              echo "构建的jar包 ${deploy_svc_name} 信息如下："
              sh "ls -lh ${JAR_PKG_NAME}; pwd"
            } catch(Exception err) {
              echo err.getMessage()
              echo err.toString()
              // unstable '构建失败'
              error '构建失败'
            }
          }
        }
      }
    }

    stage('构建镜像上传HARBOR仓库') {
      steps {
        script{
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            switch(deploy_svc_name) {
              case 'saas-data-server':
                deploy_svc_name = 'data-etl-server'
              break
            }

            TEMP_JAR_NAME = "${deploy_svc_name}-1.0-SNAPSHOT.jar"
            JAR_PKG_NAME = "${deploy_svc_name}/build/libs/${TEMP_JAR_NAME}"

            DEPLOY_JAR_PKG_NAME = "${deploy_svc_name}.jar"
            // 创建构建docker镜像用的临时目录
            sh """
              [ -d temp_docker_build_dir ] || mkdir temp_docker_build_dir
              cp ${JAR_PKG_NAME} temp_docker_build_dir/${TEMP_JAR_NAME}
            """
            echo "构建服务：${deploy_svc_name} 的docker镜像"
            dir("${env.WORKSPACE}/temp_docker_build_dir") {
              docker_file = """
                FROM ${JDKVERSION}
                LABEL maintainer="colin" version="1.0" datetime="2021-01-11"
                RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
                    echo "Asia/Shanghai" > /etc/timezone
                COPY ${TEMP_JAR_NAME} /opt/betack/${DEPLOY_JAR_PKG_NAME}
                WORKDIR /opt/betack              
              """.stripIndent()

              writeFile file: 'Dockerfile', text: "${docker_file}", encoding: 'UTF-8'
              // 追加，把 Arthas Java 诊断工具打包到镜像中
              def _ARTHAS_TOOLS = Boolean.valueOf("${params.ARTHAS_TOOLS}")
              if (_ARTHAS_TOOLS) {
                sh """
                  echo 'COPY --from=IAmIPaddress/libs/arthas:3.6.7-no-jdk /opt/arthas /opt/arthas' >> Dockerfile
                """
              }

              sh '''
                pwd; ls -lh
                cat Dockerfile
              '''

              withCredentials([usernamePassword(credentialsId: "${harbor_auth}", passwordVariable: 'password', usernameVariable: 'username')]) {
                image_name = "${office_registry}/${project}/${deploy_svc_name}:${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                sh """
                  docker login -u ${username} -p '${password}' ${office_registry}
                  docker image build -t ${image_name} -f Dockerfile .
                  docker image push ${image_name}
                  docker image tag ${image_name} "${office_registry}/${project}/${deploy_svc_name}:latest"
                  docker image push "${office_registry}/${project}/${deploy_svc_name}:latest"
                """

                // 推送镜像到hwcould仓库：harbor.betack.com
                def _EXTRANET_HARBOR = Boolean.valueOf("${params.EXTRANET_HARBOR}")
                if (_EXTRANET_HARBOR) {
                  extranet_image_name = "${hwcloud_registry}/${project}/${deploy_svc_name}:${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                  sh """
                    docker login -u ${username} -p '${password}' ${hwcloud_registry}
                    docker image tag ${image_name} ${extranet_image_name}
                    docker image push ${extranet_image_name}
                    docker image rm ${extranet_image_name}
                  """
                }
              }

              // 镜像打包后，清理jar包，减少docker build上下文，清理构建环境的镜像节约磁盘空间
              sh """
                rm -f ${TEMP_JAR_NAME}
                docker image rm ${image_name}
                docker image rm ${office_registry}/${project}/${deploy_svc_name}:latest
              """
            }
          }
        }
      }
    }

    // harbor.betack.com/rabbeyond/sdk-grpc-server:36-1.6.11-SNAPSHOT_2.12
    stage('部署服务') {
      stages {
        stage('部署环境分类处理') {
          steps {
            script {
              // 拉取helm部署需要的values.yaml模板文件
              echo '正在从gitlab拉取helm更新部署用的values.yaml文件...'
              // 创建拉取jenkins devops代码用的临时目录
              sh '[ -d temp_jenkins_workspace ] || mkdir temp_jenkins_workspace'
              dir("${env.WORKSPACE}/temp_jenkins_workspace") {
                try {
                  checkout([$class: 'GitSCM',
                    branches: [[name: "main"]],
                    browser: [$class: 'GitLab', repoUrl: ''],
                    userRemoteConfigs: [[credentialsId: "${git_auth}", url: "ssh://git@code.betack.com:4022/devops/jenkins.git"]]])
                } catch(Exception err) {
                  echo err.getMessage()
                  echo err.toString()
                  // unstable '拉取jenkins代码失败'
                  error '拉取jenkins代码失败'
                }
                sh 'pwd; ls -lh'
              }

              // 部署环境分类处理
              for (deploy_to_env in params.DEPLOY_TO_ENV.tokenize(',')) {
                switch(deploy_to_env) {
                  case ['prod-IAmIPaddress']:
                    env_string = deploy_to_env.tokenize('-')[-1]
                    // 处理IP地址
                    if (env_string.matches("([0-9]{1,3}.){3}[0-9]{1,3}")) {
                      // 把所有IP按照服务器启动类型，分类成docker与rancher
                      def lst_docker = ['']
                      def lst_rancher = ['IAmIPaddress']
                      if (lst_docker.contains(env_string)) {
                        ip_list_docker.add(env_string)
                      } else if (lst_rancher.contains(env_string)) {
                        ip_list_rancher.add(env_string)
                      }
                    }
                  break
                  case ['saasdata-dev-1','saasdata-dev-2','saasdata-staging-1','saasdata-staging-2']:
                    namespaces_list.add(deploy_to_env)
                  break
                }
              }
              println("需要用docker部署的：" + ip_list_docker)
              println("需要用rancher部署的："+ ip_list_rancher)
              println("需要用k8s部署的：" + namespaces_list)
            }
          }
        }

        stage('用k8s启动服务') {
          when {
            expression {
              return  ( namespaces_list != [] )
            }
          }
          steps {
            script {
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
                for (namespaces in namespaces_list) {
                  echo "用HELM部署服务: ${deploy_svc_name}到命名空间：${namespaces}"
                  println(namespaces)
                  helm_values_file="temp_jenkins_workspace/bsc/saas-data-server/deployToK8s/${deploy_svc_name}-values.yaml"
                  sh """
                    pwd
                    ls -lh ${helm_values_file}
                    cp ${helm_values_file} .
                    ls -lh
                  """
                  // 设置二进制数据存储卷大小
                  switch(namespaces) {
                    case 'saasdata-staging-2':
                      DATA_PVC_SIZE='800Gi'
                    break
                    case ['saasdata-dev-1', 'saasdata-dev-2', 'saasdata-staging-1']:
                      DATA_PVC_SIZE='500Gi'
                    break
                    default:
                      DATA_PVC_SIZE='300Gi'
                    break
                  }

                  configFileProvider([configFile(fileId: "1a4581df-f0e0-4b5f-9009-ec36d749aedd", targetLocation: "kube-config.yaml")]){
                    switch(deploy_svc_name) {
                      case 'saas-data-server':
                        chart_version = '0.1.1'

                        sh """
                          sed -i 's#DATA_PVC_SIZE#${DATA_PVC_SIZE}#' saas-data-server-values.yaml
                        """

                        // 生成jmx远程调试端口
                        def _JMXREMOTE = Boolean.valueOf("${params.JMXREMOTE}")
                        if (_JMXREMOTE) {
                          // 获取之前已有的jmx远程调试端口
                          tempEvn=namespaces.replaceAll("saasdata-","")
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
                                  nodePort = Math.abs(new Random().nextInt() % 30000) + 30000
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
                        chart_version = '0.1.1'
                      break
                    }

                    image_tag = "${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                    switch(deploy_svc_name) {
                      case ['saas-data-server', 'data-subscriber-client', 'etl-listener']:
                        doDeployToK8s(namespaces, deploy_svc_name, image_tag, chart_version)
                      break
                      case ['data-migration-job']:
                        doDeployToK8sJob(namespaces, deploy_svc_name, image_tag, chart_version, '')
                      break
                      case ['data-meta-import-job']:
                        doDeployToK8sJob(namespaces, deploy_svc_name, image_tag, chart_version, "${params.DATA_META_IMPORT_JOB_PARAMETER}")
                      break
                    }
                  }
                }
              }
            }
          }
        }

        stage('用rancher启动服务') {
          when {
            expression {
              return  (ip_list_rancher != [])
            }
          }
          steps {
            script {
              // 循环处理需要部署的服务
              for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
                switch(deploy_svc_name) {
                  case 'saas-data-server':
                    extranet_image_name = "${hwcloud_registry}/${project}/data-etl-server:${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                  break
                  default:
                    extranet_image_name = "${hwcloud_registry}/${project}/${deploy_svc_name}:${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                  break
                }
                // 循环处理需要部署的环境
                for (deploy_to_env in ip_list_rancher) {
                  // 根据需要部署的环境，切换变量
                  switch("${deploy_to_env}") {
                  case 'IAmIPaddress':
                    env.RANCHER_URL = "http://IAmIPaddress:8080/"
                    env.RANCHER_ACCESS_KEY = "24E21AAC4787811A1D26"
                    env.RANCHER_SECRET_KEY = "MBh2oU9AsQKdaoSfMJ7Zh6rCrorFWmjJsrzpJBZn"
                    CONFIG_ENV='prod'
                    break;
                  default:
                    // 默认发到运维测试环境？ test_ops
                    env.RANCHER_URL = ""
                    env.RANCHER_ACCESS_KEY = ""
                    env.RANCHER_SECRET_KEY = ""
                    break;
                  }

                  // rancher-compose部署
                  switch(deploy_svc_name) {
                    case ['data-meta-import-job']:
                      doDeployToRancher(CONFIG_ENV, deploy_svc_name, extranet_image_name, 'saas-data', "${params.DATA_META_IMPORT_JOB_PARAMETER}")
                    break
                    default:
                      doDeployToRancher(CONFIG_ENV, deploy_svc_name, extranet_image_name, 'saas-data')
                    break;
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}


def doDeployToK8s(deployToEnv, deploySVCName, imageTag, chartVersion) {
  echo "正在部署到 " + deployToEnv + " 环境."
  tempEvn=deployToEnv.replaceAll("saasdata-","")
  CONFIG_ENV=tempEvn.replaceAll("-","")
  println("CONFIG_ENV：" + CONFIG_ENV)
  sh """
    sed -i 's#CONFIG_ENV#${CONFIG_ENV}#' ${deploySVCName}-values.yaml
    sed -i 's#IMAGE_TAG#${imageTag}#' ${deploySVCName}-values.yaml
    sed -i 's#NAMESPACEPREFIX#saasdata#' ${deploySVCName}-values.yaml
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
  sh """
    helm pull oci://harbor.betack.com/libs-charts/bf-java-project-deploy-common --version ${chartVersion}
    helm --kubeconfig kube-config.yaml ${deployType} ${deploySVCName} -n ${deployToEnv} -f ${deploySVCName}-values.yaml bf-java-project-deploy-common-${chartVersion}.tgz
  """
}


def doDeployToK8sJob(deployToEnv, deploySVCName, imageTag, chartVersion, deploySVCOptions) {
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
    sed -i 's#NAMESPACEPREFIX#saasdata#' ${deploySVCName}-values.yaml
    sed -i 's#IMAGE_TAG#${imageTag}#g' ${deploySVCName}-values.yaml
    #+ 若deploySVCOptions传进来的值为空，下面也会被替换为空
    sed -i 's#${deploySVCName}-OPTIONS#${deploySVCOptions}#g' ${deploySVCName}-values.yaml
    #+ 需要在替换了JAVA_COMMAND之后执行这个
    sed -ri 's#-Dspring.profiles.active=(\\S+)#-Dspring.profiles.active=${CONFIG_ENV}#g' ${deploySVCName}-values.yaml
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
  sh """
    helm pull oci://harbor.betack.com/libs-charts/bf-java-project-job-common --version ${chartVersion}
    helm --kubeconfig kube-config.yaml install ${deploySVCName} -n ${deployToEnv} -f ${deploySVCName}-values.yaml bf-java-project-job-common-${chartVersion}.tgz
  """
}


def doDeployToDocker(deployToEnv, deploySVCName, imageTag, project, chartVersion) {
  echo "正在部署到 " + deployToEnv + " 环境."
}


def doDeployToRancher(deployToEnv, deploySVCName, imageName, rancherSpacesDIR, deploySVCOptions='') {
  echo "正在部署到 " + deployToEnv + " 环境."
  if ( deploySVCOptions != '' ) {
    sh """
      sed -i 's#${deploySVCName}-OPTIONS#${deploySVCOptions}#g' docker-compose.yml
    """
  }

  sh """
    pwd
    echo "修改 compose 文件"
    cp temp_jenkins_workspace/bsc/saas-data-server/deployToRancher/* .
    sed -i 's#${deploySVCName}-IMAGE_NAME#${imageName}#' docker-compose.yml
    sed -i 's#${deploySVCName}-ENV#${deployToEnv}#' docker-compose.yml
    cat docker-compose.yml
    echo "正在执行 rancher-compose 命令，更新 ${deploySVCName} 到 ${deployToEnv} 环境."
    rancher-compose -f ./docker-compose.yml -r ./rancher-compose.yml -p ${rancherSpacesDIR} up --upgrade --pull --confirm-upgrade -d ${deploySVCName}
  """
}

