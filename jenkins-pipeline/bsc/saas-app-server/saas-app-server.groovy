#!groovy
// 公共
def office_registry = "IAmIPaddress"
def hwcloud_registry = "harbor.betack.com"

// 项目
def project = "bf-bsc"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/betack/saas-commons.git"
// 认证
// def secret_name = "bf-harbor"
def harbor_auth = "443eb7ee-c21b-4e32-b449-e01d83171672"
def git_auth = "41dfa7f4-d5f9-4892-a8ba-6b0d31a7cd84"

// 自定义全局变量？？？
def ip_list_docker = []
def ip_list_rancher = []
def namespaces_list = []


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
            image: "${office_registry}/libs/ubuntu-jenkins-agent:jdk17-nogradle"
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
          - name: ansible
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
    //retry(2)                        // 重试次数
    timestamps()                      // 添加时间戳
    timeout(time: 30, unit:'MINUTES') // 设置此次发版运行30分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '30')  // 设置保留30天的构建历史，或者30次的构建记录
  }

  parameters {
    gitParameter branch: '',
                 branchFilter: '.*',
                 defaultValue: 'main',
                 listSize: '10',
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
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'saas-app-server,saas-etl-server',
                   visibleItemCount: 8
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'bf-bsc-staging-1,hwcloud-IAmIPaddress',
                   visibleItemCount: 7
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将开启jmxremote远程调试功能',
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
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:/usr/local/games:/snap/bin:/opt/gradle/${params.GRADLE_VERSION}/bin
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
          // 循环构建？待验证测试速度问题
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            echo "当前正在构建服务：${deploy_svc_name}"
            switch(deploy_svc_name) {
              case 'saas-app-server':
                CODE_iamusername_DIR = "${WORKSPACE}"
                JAR_PKG_NAME = "${deploy_svc_name}/build/libs/${deploy_svc_name}-0.2.1-SNAPSHOT.jar"
                BUILD_COMMAND = "gradle --no-daemon -g ${WORKSPACE}/.gradle :${deploy_svc_name}:bootJar -x test"
              break
              case 'saas-etl-server':
                CODE_iamusername_DIR = "${WORKSPACE}/etl"
                JAR_PKG_NAME = "etl/${deploy_svc_name}/build/libs/${deploy_svc_name}-0.3.0-SNAPSHOT.jar"
                BUILD_COMMAND = "gradle --no-daemon -g ${WORKSPACE}/.gradle clean build -x test"
              break
              default:
                CODE_iamusername_DIR = "${WORKSPACE}"
                JAR_PKG_NAME = "${deploy_svc_name}/build/libs/${deploy_svc_name}-0.2.1-SNAPSHOT.jar"
                BUILD_COMMAND = "gradle --no-daemon -g ${WORKSPACE}/.gradle :${deploy_svc_name}:bootJar -x test"
              break
            }
            
            try {
              sh """
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:/usr/local/games:/snap/bin:/opt/gradle/${params.GRADLE_VERSION}/bin
                export LANG=zh_CN.UTF-8
                export LC_ALL=zh_CN.UTF-8
                export GRADLE_USER_HOME="${WORKSPACE}/.gradle"
                # export -p
                cd ${CODE_iamusername_DIR} && ${BUILD_COMMAND}
              """

              echo "构建的jar包 ${deploy_svc_name} 信息如下："
              sh "ls -lh ${JAR_PKG_NAME}"
            } catch(Exception err) {
              echo err.getMessage()
              echo err.toString()
              unstable '构建失败'
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
              case 'saas-app-server':
                TEMP_JAR_NAME = "${deploy_svc_name}-0.2.1-SNAPSHOT.jar"
                JAR_PKG_NAME = "${deploy_svc_name}/build/libs/${deploy_svc_name}-0.2.1-SNAPSHOT.jar"
                DEPLOY_JAR_PKG_NAME = "${deploy_svc_name}-${GIT_COMMIT_MSG}-${BUILD_NUMBER}.jar"
              break
              case 'saas-etl-server':
                TEMP_JAR_NAME = "${deploy_svc_name}-0.3.0-SNAPSHOT.jar"
                JAR_PKG_NAME = "etl/${deploy_svc_name}/build/libs/${deploy_svc_name}-0.3.0-SNAPSHOT.jar"
                DEPLOY_JAR_PKG_NAME = "${deploy_svc_name}-${GIT_COMMIT_MSG}-${BUILD_NUMBER}.jar"
              break
              default:
                TEMP_JAR_NAME = "${deploy_svc_name}-0.2.1-SNAPSHOT.jar"
                JAR_PKG_NAME = "${deploy_svc_name}/build/libs/${deploy_svc_name}-0.2.1-SNAPSHOT.jar"
                DEPLOY_JAR_PKG_NAME = "${deploy_svc_name}-${GIT_COMMIT_MSG}-${BUILD_NUMBER}.jar"
              break
            }
            
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
                RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime
                RUN echo "Asia/Shanghai" > /etc/timezone
                COPY ${TEMP_JAR_NAME} /opt/betack/${DEPLOY_JAR_PKG_NAME}
                WORKDIR /opt/betack              
              """.stripIndent()

              writeFile file: 'Dockerfile', text: "${docker_file}", encoding: 'UTF-8'
              sh '''
                pwd; ls -lh
                cat Dockerfile
              '''

              withCredentials([usernamePassword(credentialsId: "${harbor_auth}", passwordVariable: 'password', usernameVariable: 'username')]) {
                image_name_office = "${office_registry}/${project}/${deploy_svc_name}:${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                sh """
                  docker login -u ${username} -p '${password}' ${office_registry}
                  docker image build -t ${image_name_office} -f Dockerfile .
                  docker image push ${image_name_office}
                  docker image tag ${image_name_office} "${office_registry}/${project}/${deploy_svc_name}:latest"
                  docker image push "${office_registry}/${project}/${deploy_svc_name}:latest"
                """

                // 需要部署到外网，非office内部的，镜像需要直接传到harbor.betack.com
                def extranet_lst = ['hwcloud-IAmIPaddress']
                for (deploy_to_env in params.DEPLOY_TO_ENV.tokenize(',')) {
                  if (extranet_lst.contains(deploy_to_env)) {
                    image_name_hwcloud = "${hwcloud_registry}/${project}/${deploy_svc_name}:${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                    sh """
                      docker login -u ${username} -p '${password}' ${hwcloud_registry}
                      docker image tag ${image_name_office} ${image_name_hwcloud}
                      docker image push ${image_name_hwcloud}
                      docker image tag ${image_name_office} "${hwcloud_registry}/${project}/${deploy_svc_name}:latest"
                      docker image push "${hwcloud_registry}/${project}/${deploy_svc_name}:latest"
                      docker image rm ${image_name_hwcloud}
                      docker image rm ${hwcloud_registry}/${project}/${deploy_svc_name}:latest
                    """
                    // 同一个服务，镜像只传第一次匹配到的，后续直接跳出整个循环。
                    break
                  }
                }

                // 清理本地构建环境的镜像
                sh """
                  docker image rm ${image_name_office}
                  docker image rm ${office_registry}/${project}/${deploy_svc_name}:latest
                """
              }

              // 镜像打包后，清理jar包，减少docker build上下文
              sh "rm -f ${TEMP_JAR_NAME}"
            }
          }
        }
      }
    }

    stage('部署服务') {
      stages {
        stage('部署环境分类处理') {
          steps {
            script {
              for (deploy_to_env in params.DEPLOY_TO_ENV.tokenize(',')) {
                env_string = deploy_to_env.tokenize('-')[-1]
                // 处理IP地址
                if (env_string.matches("([0-9]{1,3}.){3}[0-9]{1,3}")) {
                  // 先把所有IP分类成docker与rancher
                  def lst_docker = ['IAmIPaddress']
                  def lst_rancher = []
                  if (lst_docker.contains(env_string)) {
                    ip_list_docker.add(env_string)
                  } else if (lst_rancher.contains(env_string)) {
                    ip_list_rancher.add(env_string)
                  }
                } else {
                  namespaces_list.add(deploy_to_env)
                }
              }
              println(ip_list_docker)
              println(ip_list_rancher)
              println(namespaces_list)
              // env.NAMESPACES_LIST = namespaces_list.join(',')
              // println("${env.NAMESPACES_LIST}")
              // 需要设置为环境变量传递？？？设置为全局变量即可？？？
            }
          }
        }

        stage('用k8s启动服务'){
          when {
            expression {
              // return  ("${env.NAMESPACES_LIST}" != "")
              return  ( namespaces_list != [] )
            }
          }
          steps {
            script {
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

              // 循环处理需要部署的服务
              for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
                helm_values_file="temp_jenkins_workspace/bsc/saas-app-server/deployToK8s/${deploy_svc_name}-values.yaml"
                sh """
                  pwd
                  ls -lh ${helm_values_file}
                  cp ${helm_values_file} .
                  ls -lh
                """

                // helm登录harbor仓库
                withCredentials([usernamePassword(credentialsId: "${harbor_auth}", passwordVariable: 'password', usernameVariable: 'username')]) {
                  sh """
                    export HELM_EXPERIMENTAL_OCI=1
                    helm registry login ${hwcloud_registry} --username ${username} --password ${password}
                  """
                }

                for (namespaces in namespaces_list) {
                  switch(deploy_svc_name) {
                    case 'saas-app-server':
                      chart_version = '0.1.4'
                    break
                    case 'saas-etl-server':
                      chart_version = '0.1.1'
                    break
                    default:
                      chart_version = '0.1.0'
                    break
                  }
                  println(namespaces)

                  // 生成jmx远程调试端口
                  def _JMXREMOTE = Boolean.valueOf("${params.JMXREMOTE}")
                  if (_JMXREMOTE) {
                    // 获取之前已有的jmx远程调试端口
                    tempEvn=namespaces.replaceAll("bf-bsc-","")
                    try {
                      env.JMX_NODEPORT = sh (script: """
                        kubectl --kubeconfig=kube-config.yaml -n ${namespaces} get svc ${deploy_svc_name}-jmxremote-${tempEvn} -o jsonpath={.spec.ports[*].nodePort}
                        """, returnStdout: true).trim()
                      println("JMX_NODEPORT：" + env.JMX_NODEPORT)
                    } catch(Exception err) {
                      env.JMX_NODEPORT = ''
                      echo "之前未开启jmx远程调试功能，所以未获取到相应service的nodePort端口"
                    }

                    container('ansible') {
                      script{
                        if(! env.JMX_NODEPORT) {
                          is_exits = true
                          while(is_exits) {
                            // 生成的端口范围为30000~60000
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

                  configFileProvider([configFile(fileId: "1a4581df-f0e0-4b5f-9009-ec36d749aedd", targetLocation: "kube-config.yaml")]){
                    image_tag = "${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                    doDeployToK8s(namespaces, deploy_svc_name, image_tag, project, chart_version)
                  }
                }
              }
            }
          }
        }

        stage('用docker启动服务') {
          when {
            expression {
              dest_hosts_docker = ip_list_docker.join(',')
              println(dest_hosts_docker)
              return  ("${dest_hosts_docker}" != '')
            }
          }
          steps {
            container('ansible') {
              echo '正在用ansible远程部署服务...'
              script {
                // 循环处理需要部署的环境
                for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
                  // 注意，需要提前用命令：ssh-copy-id -i /iamusername/.ssh/id_rsa_jenkins.pub betack@IAmIPaddress 进行免密认证
                  sshagent (credentials: ['830e90a8-1fec-4a45-9317-415e7acaff10']) {
                    echo '创建ansible-playbook用的文件docker-deploy.yml'
                    switch(deploy_svc_name) {
                      case 'saas-app-server':
                        dockerDeploy = """
                        - name: deploy ${deploy_svc_name}
                          hosts: "{{ target }}"
                          gather_facts: no
                          tasks:
                            - name: Restart a container
                              docker_container:
                                name: bsc-${deploy_svc_name}
                                image: ${hwcloud_registry}/${project}/${deploy_svc_name}:${GIT_COMMIT_MSG}-${BUILD_NUMBER}
                                state: started
                                restart: yes
                                ports:
                                # Publish container port 50053 as host port 50050
                                  #- "50050:50053"
                                  - "51001:8080"
                                  - "51002:50053"
                                  - "51003:5005"
                                volumes:
                                  - /data1t/bsc-data/bsc-rabbeyond:/opt/saas_commons/data
                                command: [
                                  "java",
                                  "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005",
                                  "-Ddag.datasource.url=jdbc:mysql://IAmIPaddress:3306/rabbeyond",
                                  "-Dspring.profiles.active=external-test",
                                  "--add-opens=java.base/java.nio=ALL-UNNAMED",
                                  "-jar",
                                  "/opt/betack/${deploy_svc_name}-${GIT_COMMIT_MSG}-${BUILD_NUMBER}.jar"]
                        """.stripIndent()
                      break
                      default:
                        dockerDeploy = ""
                      break
                    }

                    writeFile file: 'docker-deploy.yml', text: "${dockerDeploy}", encoding: 'UTF-8'
                    sh 'cat docker-deploy.yml'

                    echo '远程启动docker容器.'
                    configFileProvider([configFile(fileId: "bf00105b-50ad-4791-92b2-d5f0431d217a", targetLocation: "ansible-hosts")]){
                      sh """
                        # 延迟60秒，等office harbor镜像同步到huaweicloud harbor仓库
                        sleep 5
                        ansible-playbook --inventory-file ansible-hosts -e "{target: ${dest_hosts_docker}}" -i "${dest_hosts_docker}," -u betack docker-deploy.yml
                      """
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
}


def doDeployToK8s(deployToEnv, deploySVCName, imageTag, project, chartVersion) {
    echo "正在部署到 " + deployToEnv + " 环境."
    // 薛驹说：8、11版本不支持这个参数？--add-opens=java.base/java.nio=ALL-UNNAMED
    if ("${params.JDK_VERSION}" == '17') {
      JAVA_JAR_OPTS = '--add-opens=java.base/java.nio=ALL-UNNAMED'
    } else {
      JAVA_JAR_OPTS = ''
    }

    // 判断是否开启jmxremote远程调试功能
    def _JMXREMOTE = Boolean.valueOf("${params.JMXREMOTE}")
    println("JMXREMOTE：" + _JMXREMOTE)
    sh """
      sed -i 's#JMX_REMOTE#${_JMXREMOTE}#' ${deploySVCName}-values.yaml
    """
    if(_JMXREMOTE) {
      println("开启jmxremote远程调试功能")
      sh "sed -i 's#JMX_NODEPORT#${env.JMX_NODEPORT}#' ${deploySVCName}-values.yaml"
    }

    // 修改 helm values.yaml
    CONFIG_ENV = sh (script: "echo ${deployToEnv} | awk -F'-' '{print \$3\$4}'", returnStdout: true).trim()
    println("CONFIG_ENV：" + CONFIG_ENV)
    sh """
      sed -i 's#CONFIG_ENV#${CONFIG_ENV}#' ${deploySVCName}-values.yaml
      sed -i 's#JAVA_JAR_OPTS#${JAVA_JAR_OPTS}#' ${deploySVCName}-values.yaml
      sed -i 's#IMAGE_TAG#${imageTag}#' ${deploySVCName}-values.yaml
      cat ${deploySVCName}-values.yaml
      echo "正在执行helm命令，更新${deploySVCName}服务版本${imageTag}到${deployToEnv}环境."
      helm version
    """

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
      helm --kubeconfig kube-config.yaml ${deployType} ${deploySVCName} -n ${deployToEnv} -f ${deploySVCName}-values.yaml \
        oci://harbor.betack.com/${project}-charts/${deploySVCName} --version ${chartVersion}
    """
}


def doDeployToDocker(deployToEnv, deploySVCName, imageTag, project, chartVersion) {
    echo "正在部署到 " + deployToEnv + " 环境."
}


def doDeployToRancher(deployToEnv, deploySVCName, imageTag, project, chartVersion) {
  echo ""
}