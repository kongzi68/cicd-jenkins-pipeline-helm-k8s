#!groovy
// 公共
def office_registry = "IAmIPaddress"
def hwcloud_registry = "harbor.betack.com"

// 项目
def project = "rab"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/betack/rab-backend.git"

// 认证
def harbor_auth = "443eb7ee-c21b-4e32-b449-e01d83171672"
def git_auth = "41dfa7f4-d5f9-4892-a8ba-6b0d31a7cd84"


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
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将会把镜像同步推送一份到HARBOR镜像仓库: harbor.betack.com',
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
                   value: 'new-rab-dev-1,new-rab-dev-2,new-rab-dev-3,new-rab-dev-4,new-rab-staging-1,new-rab-staging-2,new-rab-staging-3,new-rab-staging-4,api4-demo-dev-1',
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
        echo '正在拉取代码...'
        script {
          try {
            checkout([$class: 'GitSCM',
              branches: [[name: "${params.BRANCH_TAG}"]],
              browser: [$class: 'GitLab', repoUrl: ''],
              extensions: [[$class: 'CloneOption', depth: 1, noTags: false, reference: '', shallow: true, timeout: 30]],
              userRemoteConfigs: [[credentialsId: "${git_auth}", url: "${git_address}"]]])
          } catch(Exception err) {
            echo err.getMessage()
            echo err.toString()
            unstable '拉取代码失败'
          }
          // 获取提交代码的ID，用于打包容器镜像
          env.GIT_COMMIT_MSG = sh (script: 'git rev-parse --short HEAD', returnStdout: true).trim()
        }
      }
    }

    stage('环境准备') {
      steps {
        echo "当前 java 版本信息如下："
        sh 'java -version'
        script {
          def javaVersion = sh "java --version | head -1 | awk -F '[ .]+' '{print \$2}'"
          if ("${params.JDK_VERSION}" != "${javaVersion}") {
            echo "开始切换 java 版本."
            try {
              sh """
                update-alternatives --install /usr/bin/java java /usr/lib/jvm/java-"${params.JDK_VERSION}"-openjdk-amd64/bin/java 1081
                update-alternatives --config java
                update-alternatives --set java /usr/lib/jvm/java-"${params.JDK_VERSION}"-openjdk-amd64/bin/java
                java -version
              """
              JDKVERSION = "${office_registry}/libs/amazoncorretto:${params.JDK_VERSION}"
            } catch(Exception err) {
              echo err.getMessage()
              echo err.toString()
              unstable '构建失败'
            }
          } else {
            echo "默认 JDK 版本为 ${javaVersion}"
            JDKVERSION = "${office_registry}/libs/amazoncorretto:${javaVersion}"
          }
        }

        // 执行命令 gradle clean
        script {
          def _CLEAN_BUILD = Boolean.valueOf("${params.CLEAN_BUILD}")
          if (_CLEAN_BUILD) {
            echo "执行命令：gradle clean，清理构建环境"
            sh """
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/gradle/${params.GRADLE_VERSION}/bin
              export LANG=zh_CN.UTF-8
              export LC_ALL=zh_CN.UTF-8
              export GRADLE_USER_HOME="${WORKSPACE}/.gradle"
              gradle --no-daemon -g "${WORKSPACE}/.gradle" clean
            """
          }
        }
      }
    }

    stage('代码编译打包') {
      steps {
        echo '正在构建...'
        script {
          // 按服务打包时去重
          def _DEPLOY_SVC_NAME = delDeploySVCName(['rab-svc-api-app','rab-svc-api-app-master','rab-svc-offline-app'], 'rab-svc-api-app')
          println(_DEPLOY_SVC_NAME)
          for (deploy_svc_name in _DEPLOY_SVC_NAME) {
            echo "当前正在构建服务：${deploy_svc_name}"
            JAR_PKG_NAME = "${deploy_svc_name}/build/libs/${deploy_svc_name}-1.1.0-SNAPSHOT.jar"

            try {
              // sh "gradle rab-svc-migrate-to-beyond:bootJar -x test"
              sh """
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:/usr/local/games:/snap/bin:/opt/gradle/${params.GRADLE_VERSION}/bin
                export LANG=zh_CN.UTF-8
                export LC_ALL=zh_CN.UTF-8
                export GRADLE_USER_HOME="${WORKSPACE}/.gradle"
                # export -p
                gradle -q wrapper --no-daemon -g "${WORKSPACE}/.gradle" ${deploy_svc_name}:bootJar -x test
              """
              echo "构建的jar包 ${deploy_svc_name} 信息如下："
              sh "ls -lh ${JAR_PKG_NAME}; pwd"
            } catch(Exception err) {
              echo err.getMessage()
              echo err.toString()
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
              case ['rab-svc-offline-app','rab-svc-api-app-master']:
                TEMP_JAR_NAME = "rab-svc-api-app-1.1.0-SNAPSHOT.jar"
                JAR_PKG_NAME = "rab-svc-api-app/build/libs/${TEMP_JAR_NAME}"
              break
              default:
                TEMP_JAR_NAME = "${deploy_svc_name}-1.1.0-SNAPSHOT.jar"
                JAR_PKG_NAME = "${deploy_svc_name}/build/libs/${TEMP_JAR_NAME}"
              break
            }

            // DEPLOY_JAR_PKG_NAME = "${deploy_svc_name}-${GIT_COMMIT_MSG}-${BUILD_NUMBER}.jar"
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
                branches: [[name: "main"]],
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
              helm_values_file="temp_jenkins_workspace/new-${project}/new-${project}-backend/deployToK8s/${deploy_svc_name}-values.yaml"
              sh """
                pwd
                ls -lh ${helm_values_file}
                cp ${helm_values_file} .
                ls -lh ${deploy_svc_name}-values.yaml
              """

              // [bf-bsc, staging-1, staging1]
              config_env = splitNamespaces(namespaces)
              CONFIG_ENV_PREFIX = config_env[0]
              CONFIG_ENV_SUFFIX = config_env[1]
              CONFIGENV = config_env[2]

              // 设置 helm values.yaml 中 data pv 卷大小
              switch(namespaces) {
                case 'new-rab-staging-1':
                  // 153 kubeadm k8s
                  K8S_AUTH = '235f0282-bcf4-49ee-97ad-7caeb98e5266'
                  PV_SIZE = '500Gi'
                  DATA_STORAGE_CLASSNAME = 'nfs-client-retain-2'
                  K8S_NODEIP = 'IAmIPaddress'
                  RANDOM_DIVISOR = 20000
                  RANDOM_MIN_NUM = 10000
                break
                case ['api4-demo-dev-1']:
                  // rke2 k8s
                  K8S_AUTH = '1a4581df-f0e0-4b5f-9009-ec36d749aedd'
                  PV_SIZE = '300Gi'
                  DATA_STORAGE_CLASSNAME = 'longhorn-fast'
                  K8S_NODEIP = 'IAmIPaddress'
                  RANDOM_DIVISOR = 30000   // 取值范围 30000~59999
                  RANDOM_MIN_NUM = 30000
                break
                default:
                  K8S_AUTH = '235f0282-bcf4-49ee-97ad-7caeb98e5266'
                  PV_SIZE = '500Gi'
                  DATA_STORAGE_CLASSNAME = 'nfs-client-retain-2'
                  K8S_NODEIP = 'IAmIPaddress'
                  RANDOM_DIVISOR = 20000   // 取值范围 10000~29999
                  RANDOM_MIN_NUM = 10000
                break
              }

              switch(deploy_svc_name) {
                case ['rab-svc-api-app']:
                  sh """
                    sed -i 's#REPLICA_COUNT#${params.REPLICA_COUNT}#' ${deploy_svc_name}-values.yaml
                    sed -i 's#PV_SIZE#${PV_SIZE}#' ${deploy_svc_name}-values.yaml
                    # 只有api-app的时候创建pvc
                    sed -i 's#DATASTORAGECLASSNAME#${DATA_STORAGE_CLASSNAME}#' ${deploy_svc_name}-values.yaml
                  """
                break
              }

              configFileProvider([configFile(fileId: "${K8S_AUTH}", targetLocation: "kube-config.yaml")]){
                switch(deploy_svc_name) {
                  case ['rab-svc-api-app', 'rab-svc-api-app-master']:
                    chart_version = '0.1.3'
                    k8s_rs_type = 'deploy'
                    jsonpath_string = 'jsonpath={.spec.template.spec.containers[*].command}'

                    // 生成jmx远程调试端口
                    def _JMXREMOTE = Boolean.valueOf("${params.JMXREMOTE}")
                    if (_JMXREMOTE) {
                      // 获取之前已有的jmx远程调试端口
                      env.JMX_NODEPORT = getSVCNodePort(namespaces, deploy_svc_name, 5005, CONFIG_ENV_SUFFIX, 'jmxremote')

                      container('tools') {
                        script{
                          if(! env.JMX_NODEPORT) {
                            is_exits = true
                            while(is_exits) {
                              nodePort = Math.abs(new Random().nextInt() % RANDOM_DIVISOR) + RANDOM_MIN_NUM
                              // 若通的，返回true，即继续寻找可用的端口
                              script_ret = sh (script: "nc -vz ${K8S_NODEIP} ${nodePort} && echo 'true' || echo 'false'", returnStdout: true).trim()
                              // println(script_ret)
                              is_exits = Boolean.valueOf(script_ret)
                            }
                            println("设置JMX_NODEPORT为：" + nodePort)
                            env.JMX_NODEPORT = nodePort
                          }
                        }
                      }

                      // 判断是否开启jmxremote远程调试功能
                      println("JMXREMOTE：" + _JMXREMOTE)
                      echo "开启jmxremote远程调试功能"
                      sh """
                        sed -i 's#JMX_NODEPORT#${env.JMX_NODEPORT}#' ${deploy_svc_name}-values.yaml
                        sed -i 's#JMX_NODEIP#${_JMX_NODEIP}#' ${deploy_svc_name}-values.yaml
                      """
                    }
                  break
                  case 'jaa-svc-data-migration':
                    chart_version = '0.1.3'
                    k8s_rs_type = 'job'
                    jsonpath_string = 'jsonpath={.spec.template.spec.containers[*].command}'
                  break
                  default:
                    chart_version = '0.1.3'
                    k8s_rs_type = 'deploy'
                    jsonpath_string = 'jsonpath={.spec.template.spec.containers[*].command}'
                  break
                }

                // 获取当前服务的 command 启动参数
                env.JAVA_COMMAND = sh (script: """
                  kubectl --kubeconfig=kube-config.yaml -n ${namespaces} get ${k8s_rs_type} ${deploy_svc_name}-${CONFIG_ENV_SUFFIX} -o ${jsonpath_string} \
                  | awk -F '\"' '{print \$(NF-1)}' | sed -rn 's#java(.*)-jar.*#\\1# p' | awk '\$1=\$1'
                  """, returnStdout: true).trim()
                // 为空时，设置初始 java 启动 jar 包的参数选项
                if (! env.JAVA_COMMAND ) {
                  env.JAVA_COMMAND = "-Dspring.profiles.active=${CONFIGENV} -DLOG_BASE_PATH=/var/log"
                }

                // 因helm chart模板包含jmx参数，因此去重 jmx 参数
                env.JAVA_COMMAND = jmxRemoveDuplicate(env.JAVA_COMMAND, env.JMX_NODEPORT)
                // 命令字符串去重
                env.JAVA_COMMAND = strRemoveDuplicate(env.JAVA_COMMAND)
                println("服务 ${deploy_svc_name} 的 JAVA_COMMAND：" + env.JAVA_COMMAND)
                sh "sed -i 's#JAVA_COMMAND#${env.JAVA_COMMAND}#' ${deploy_svc_name}-values.yaml"

                image_tag = "${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                switch(deploy_svc_name) {
                  case 'rab-task-data-migration':
                    doDeployToK8sJob(namespaces, deploy_svc_name, image_tag, project, chart_version, "${params.rab_TASK_DATA_MIGRATION_PARAMETER}")
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
  println("CONFIG_ENV：" + CONFIG_ENV_SUFFIX)
  println("项目简称，用于命名空间的前缀：" + CONFIG_ENV_PREFIX)

  sh """
    sed -i 's#CONFIG_ENV#${CONFIG_ENV_SUFFIX}#' ${deploySVCName}-values.yaml
    sed -i 's#IMAGE_TAG#${imageTag}#' ${deploySVCName}-values.yaml
    sed -i 's#NAMESPACEPREFIX#${CONFIG_ENV_PREFIX}#' ${deploySVCName}-values.yaml
  """

  // 无论是否勾选 JMXREMOTE，都需要修改 values.yaml 文件中 JMX_REMOTE 的值为布尔值。
  def _JMXREMOTE = Boolean.valueOf("${params.JMXREMOTE}")
  sh "sed -i 's#JMX_REMOTE#${_JMXREMOTE}#' ${deploySVCName}-values.yaml"

  sh "cat ${deploySVCName}-values.yaml"
  echo "正在执行helm命令，更新${deploySVCName}服务版本${imageTag}到${deployToEnv}环境."

  // 判断是否已经部署过
  def getDeployName = sh (script: "helm --kubeconfig kube-config.yaml -n ${deployToEnv} list -l name==${deploySVCName} -q", returnStdout: true).trim()
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


def doDeployToK8sJob(deployToEnv, deploySVCName, imageTag, project, chartVersion, deploySVCOptions) {
  /* 
    helm 更新 job 问题，因为一些不可变的资源导致不能使用 helm upgrade
    因此：job的更新，每次helm delete后新建
    1. Cannot upgrade a release with Job #7725 https://github.com/helm/helm/issues/7725
    2. Can't update Jobs, field is immutable #89657 https://github.com/kubernetes/kubernetes/issues/89657
    3. Helm 3 upgrade failed - Immutable field. #7173 https://github.com/helm/helm/issues/7173
  */
  echo "正在部署到 " + deployToEnv + " 环境."
  println("CONFIG_ENV：" + CONFIG_ENV_SUFFIX)
  println("项目简称，用于命名空间的前缀：" + CONFIG_ENV_PREFIX)

  sh """
    sed -i 's#CONFIG_ENV#${CONFIG_ENV_SUFFIX}#' ${deploySVCName}-values.yaml
    sed -i 's#IMAGE_TAG#${imageTag}#' ${deploySVCName}-values.yaml
    sed -i 's#NAMESPACEPREFIX#${CONFIG_ENV_PREFIX}#' ${deploySVCName}-values.yaml
    #+ 若deploySVCOptions传进来的值为空，下面也会被替换为空
    sed -i 's#${deploySVCName}-OPTIONS#${deploySVCOptions}#g' ${deploySVCName}-values.yaml
    cat ${deploySVCName}-values.yaml
    echo "正在执行helm命令，更新${deploySVCName}服务版本${imageTag}到${deployToEnv}环境."
  """
  
  // 判断是否已经部署过
  def getDeployName = sh (script: "helm --kubeconfig kube-config.yaml -n ${deployToEnv} list -l name==${deploySVCName} -q", returnStdout: true).trim()
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


def strRemoveDuplicate(str_command) {
  /*
    * 获取到的命令字符串内容去重
  */
  list0 = str_command.split()
  list1 = []
  for ( item in list0 ) {
    // println(item)
    if ( ! list1.contains(item)) {
        list1.add(item)
    }
  }

  str_ret = list1.join(" ")
  // println(str_ret)
  return str_ret
}


def jmxRemoveDuplicate(str_command, nodePort){
  /*
    * 去重jmx远程调试部分参数，因为helm chart包模板里面已经包含了
  */
  jmx_option = """-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=${nodePort} \
    -Dcom.sun.management.jmxremote.rmi.port=${nodePort} -Djava.rmi.server.hostname=IAmIPaddress \
    -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.authenticate=false \
    -Dcom.sun.management.jmxremote.local.only=false
  """
  // 去除多余的空格
  jmx_option = sh (script: "eval echo ${jmx_option}", returnStdout: true).trim()
  // println("jmx_option：" + jmx_option)
  ret_str_command = str_command.replaceAll(jmx_option,"")
  // println("ret_str_command: "+ ret_str_command)
  return ret_str_command
}


def delDeploySVCName(listDuplicateSVC, retainSVC){
  /*
    * 去掉 offline 与 api-master
    * listDuplicateSVC, 都是同一个jar包的服务名称列表，比如 rab-svc-api-app,rab-svc-api-app-master,rab-svc-offline-app 用的同一个jar包
    * retainSVC，需要保留的服务名称
  */
  _svc_list = []
  for (item in params.DEPLOY_SVC_NAME.tokenize(',')) {
    switch(item) {
      case listDuplicateSVC:
        svc_name = retainSVC
      break
      default:
        svc_name = item
      break
    }
    if ( ! _svc_list.contains(svc_name) ) {
      _svc_list.add(svc_name)
    }
  }
  return _svc_list
}


def getSVCNodePort(namespaces, deploySVCName, podPort, deployENVSuffix, svcNameSalt='out') {
  /* 
    获取指定服务的nodePort端口
  */
  try {
    svc_nodeport = sh (script: """
      kubectl --kubeconfig=kube-config.yaml -n ${namespaces} get svc ${deploySVCName}-${svcNameSalt}-${deployENVSuffix} -o jsonpath='{.spec.ports[?(@.port==${podPort})].nodePort}'
      """, returnStdout: true).trim()
    println("${deploySVCName} 的容器内端口 ${podPort} 对应的 NodePort：" + svc_nodeport)
  } catch(Exception err) {
    svc_nodeport = ''
    echo "未获取 ${deploySVCName} service 的 nodePort 端口。"
  }
  return svc_nodeport
}


def splitNamespaces(namespaces) {
  /*
   * jic-staging-1 or bf-bsc-staging-1
   * 返回结果：[bf-bsc, staging-1, staging1]
  */
  t_list = namespaces.tokenize('-')
  config_env = t_list[-2] + '-' + t_list[-1]
  // println config_env
  config_env_prefix = namespaces.replaceAll('-' + config_env, "")
  configenv = config_env.replaceAll('-', "")
  return [config_env_prefix, config_env, configenv]
}


