#!groovy
/*
更新服务：sdk-grpc-server
文档： http://ci.betack.com:61032/1.6.X/rab-engine/maintain/deploy-with-single/#_1
release 版本：http://jfrog.betack.com:8081/artifactory/webapp/#/artifacts/browse/tree/General/libs-release-local/com/betack/sdk-grpc-server
snapshot 版本：http://jfrog.betack.com:8081/artifactory/webapp/#/artifacts/browse/tree/General/libs-snapshot-local/com/betack/sdk-grpc-server
betack-open 版本：https://jfrog.betack.com/artifactory/webapp/#/artifacts/browse/tree/General/betack-open/com/betack/sdk-grpc-server
    http://jfrog.betack.com:8081/artifactory/betack-open/com/betack/sdk-grpc-server/maven-metadata.xml
    http://jfrog.betack.com:8081/artifactory/betack-open/com/betack/sdk-grpc-server/1.11.11/sdk-grpc-server-1.11.11-all.jar
betack-open scala版本：https://jfrog.betack.com/artifactory/webapp/#/artifacts/browse/tree/General/betack-open/com/betack/sdk-grpc-server_2.13
    http://jfrog.betack.com:8081/artifactory/betack-open/com/betack/sdk-grpc-server_2.13/maven-metadata.xml
    http://jfrog.betack.com:8081/artifactory/betack-open/com/betack/sdk-grpc-server_2.13/1.14.4/sdk-grpc-server_2.13-1.14.4-all.jar
*/

/* 导入Jenkins共享库 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
// office harbor
def office_registry = "IAmIPaddress:8765"
def hwcloud_registry = "harbor.betack.com"

// 项目
def project = "rabbeyond"  // HARrab镜像仓库中的项目名称
def harbor_auth = "443eb7ee-c21b-4e32-b449-e01d83171672"
def jfrog_url = "https://jfrog.betack.com"
def git_auth = "41dfa7f4-d5f9-4892-a8ba-6b0d31a7cd84"

// 自定义全局变量？？？
def ip_list_docker = []
def ip_list_rancher = []
def namespaces_list = []
def ip_list_office = []
def ip_list_extranet = []


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
    // disableConcurrentBuilds()         // java项目禁止并发构建：主要是gradle有锁，导致无法并发构建
    timestamps()                      // 添加时间戳
    timeout(time: 30, unit:'MINUTES') // 设置此次发版运行20分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', 
                              artifactNumToKeepStr: '',
                              numToKeepStr: '30')  // 设置保留30次构建记录
  }

  parameters {
    booleanParam defaultValue: true,
                 description: '默认发布最新版本；若取消勾选，运行到【选择需要部署的版本号】步骤时，需要手动选择指定版本进行发布。',
                 name: 'DEPLOY_LATEST_VERSION'
    choice choices: ['release版本','snapshot版本','betack-open版本','betack-open-scala版本'],
           description: '请选择需要部署的包类型',
           name: 'SELECT_PKG_TYPE'
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'rabbeyond-staging-1',
                   visibleItemCount: 10
  }

  stages {
    stage('选择需要部署的版本号') {
      steps {
        container('jnlp') {
          script {
            switch(params.SELECT_PKG_TYPE) {
              case 'release版本':
                temp_path = 'libs-release-local'
                // svc_name = "sdk-grpc-server"
                svc_name = "sdk-grpc-server_2.13"
              break
              case 'snapshot版本':
                temp_path = 'libs-snapshot-local'
                svc_name = "sdk-grpc-server"
              break
              case 'betack-open版本':
                temp_path = 'betack-open'
                svc_name = "sdk-grpc-server"
              break
              case 'betack-open-scala版本':
                temp_path = 'betack-open'
                svc_name = "sdk-grpc-server_2.13"
              break
            }

            svc_jfrog_path = "com/betack/${svc_name}"

            // 查找当前WORKERSPACE下的py文件
            // 处理成选择框使用的 value
            withCredentials([usernamePassword(credentialsId: "5f65343b-7226-4b5e-840d-23f7687108e1",
                                              passwordVariable: 'password',
                                              usernameVariable: 'username')]) {
              PKG_VERSIONS = sh (script: """
                curl -u${username}:${password} "${jfrog_url}/artifactory/${temp_path}/${svc_jfrog_path}/maven-metadata.xml" | \
                  grep "<version>" | sed 's/.*<version>\\([^<]*\\)<\\/version>.*/\\1/' | tail -100 | xargs
                """, returnStdout: true).trim()
              // println(PKG_VERSIONS)
              PKG_VERSION_LIST = PKG_VERSIONS.tokenize()
              env.BUILD_ARCHS = PKG_VERSION_LIST[-1]
            }

            def _DEPLOY_LATEST_VERSION = Boolean.valueOf("${params.DEPLOY_LATEST_VERSION}")
            if ( ! _DEPLOY_LATEST_VERSION ) {
              // Load the list into a variable
              env.LIST = PKG_VERSION_LIST.join(',')
              echo "${env.LIST}"
              env.RELEASE_SCOPE = input message: "请选择需要部署的 sdk-grpc-server 服务版本号", ok: '确定',
                      parameters: [extendedChoice(
                      description: '请选择需要部署的 sdk-grpc-server 服务版本号，只能单选',
                      name: 'SVC_VERSION',
                      defaultValue: env.BUILD_ARCHS,
                      multiSelectDelimiter: ',',
                      type: 'PT_SINGLE_SELECT',
                      value: env.LIST,
                      visibleItemCount: 50
                )]
            } else {
              // 默认最新版本
              env.RELEASE_SCOPE = env.BUILD_ARCHS
            }

            echo "被选择的部署版本为: ${env.RELEASE_SCOPE}"
          }
        }
      }
    }

    stage('从jfrog下载jar包') {
      steps {
        container('jnlp') {
          script {
            switch(params.SELECT_PKG_TYPE) {
              case 'release版本':
                // jar_pkg_path = "libs-release-local/com/betack/${svc_name}/${env.RELEASE_SCOPE}/${svc_name}-${env.RELEASE_SCOPE}-all.jar"
                t_version = "${env.RELEASE_SCOPE}"
                jar_pkg_path = "libs-release-local/com/betack/${svc_name}/${env.RELEASE_SCOPE}"
              break
              case 'snapshot版本':
                t_version = "${env.RELEASE_SCOPE}".tokenize('-')[0]
                jar_pkg_path = "libs-snapshot-local/com/betack/${svc_name}/${env.RELEASE_SCOPE}"
              break
              case 'betack-open版本':
                t_version = "${env.RELEASE_SCOPE}"
                jar_pkg_path = "betack-open/com/betack/${svc_name}/${env.RELEASE_SCOPE}"
              break
              case 'betack-open-scala版本':
                t_version = "${env.RELEASE_SCOPE}"
                jar_pkg_path = "betack-open/com/betack/${svc_name}/${env.RELEASE_SCOPE}"
              break
            }
            // 查找当前WORKERSPACE下的py文件
            // 处理成选择框使用的 value
            withCredentials([usernamePassword(credentialsId: "5f65343b-7226-4b5e-840d-23f7687108e1",
                                              passwordVariable: 'password',
                                              usernameVariable: 'username')]) {

              PKG_NAME = sh (script: """
                curl -u${username}:${password} "${jfrog_url}/artifactory/${jar_pkg_path}/" | grep href \
                | sed 's/.*>\\(${svc_name}-${t_version}.*-all.jar\\)<.*/\\1/' | grep -v href | tail -1
              """, returnStdout: true).trim()
              println(PKG_NAME)
              sh """
                rm -f *.jar
                curl -u${username}:${password} "${jfrog_url}/artifactory/${jar_pkg_path}/${PKG_NAME}" -o "${PKG_NAME}"
                ls -lh "${PKG_NAME}"
              """
            }
          }
        }
      }
    }

    stage('构建镜像上传HARBOR仓库') {
      steps {
        container('podman') {
          script{
            echo '创建Dockerfile'
            switch(params.SELECT_PKG_TYPE) {
              case 'release版本':
                // JAVA_VERSION = 8
                JAVA_VERSION = 11
              break
              case 'snapshot版本':
                JAVA_VERSION = 8
              break
              case 'betack-open版本':
                JAVA_VERSION = 11
              break
              case 'betack-open-scala版本':
                JAVA_VERSION = 11
              break
            }
            docker_file = """
              FROM ${office_registry}/libs/amazoncorretto:${JAVA_VERSION}
              LABEL maintainer="colin" version="1.0" datetime="2021-01-11"
              RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
                  echo "Asia/Shanghai" > /etc/timezone
              WORKDIR /opt/betack
              # copy arthas
              # COPY --from=hengyunabc/arthas:latest /opt/arthas /opt/arthas
              COPY --from=IAmIPaddress:8765/libs/arthas:3.6.7-no-jdk /opt/arthas /opt/arthas
              COPY ${PKG_NAME} /opt/betack/${PKG_NAME}
            """.stripIndent()

            writeFile file: 'Dockerfile', text: "${docker_file}", encoding: 'UTF-8'
            sh '''
              pwd; ls -lh
              cat Dockerfile
            '''

            echo '构建镜像，并上传到harbor仓库'
            withCredentials([usernamePassword(credentialsId: "${harbor_auth}", passwordVariable: 'password', usernameVariable: 'username')]) {
              image_name_office = "${office_registry}/${project}/${svc_name}:${BUILD_NUMBER}-${env.RELEASE_SCOPE}"
              sh """
                podman login -u ${username} -p '${password}' ${office_registry}
                podman image build -t ${image_name_office} .
                podman image push ${image_name_office}
                podman image tag ${image_name_office} "${office_registry}/${project}/${svc_name}:latest"
                podman image push "${office_registry}/${project}/${svc_name}:latest"
              """

              // 需要部署到外网，非office内部的，镜像需要直接传到harbor.betack.com
              def extranet_lst = ['hwcloud-IAmIPaddress']
              for (deploy_to_env in params.DEPLOY_TO_ENV.tokenize(',')) {
                if (extranet_lst.contains(deploy_to_env)) {
                  image_name_hwcloud = "${hwcloud_registry}/${project}/${svc_name}:${BUILD_NUMBER}-${env.RELEASE_SCOPE}"
                  sh """
                    podman login -u ${username} -p '${password}' ${hwcloud_registry}
                    podman image tag ${image_name_office} ${image_name_hwcloud}
                    podman image push ${image_name_hwcloud}
                    podman image tag ${image_name_office} "${hwcloud_registry}/${project}/${svc_name}:latest"
                    podman image push "${hwcloud_registry}/${project}/${svc_name}:latest"
                    podman image rm ${image_name_hwcloud}
                    podman image rm ${hwcloud_registry}/${project}/${svc_name}:latest
                  """
                  // 同一个服务，镜像只传第一次匹配到的，后续直接跳出整个循环。
                  break
                }
              }

              // 清理本地构建环境的镜像
              sh """
                podman image rm ${image_name_office}
                podman image rm ${office_registry}/${project}/${svc_name}:latest
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
              for (deploy_to_env in params.DEPLOY_TO_ENV.tokenize(',')) {
                env_string = deploy_to_env.tokenize('-')[-1]
                // 处理IP地址
                if (env_string.matches("([0-9]{1,3}.){3}[0-9]{1,3}")) {
                  // 把所有IP按照服务器启动类型，分类成docker与rancher
                  def lst_docker = ['IAmIPaddress']
                  def lst_rancher = ['IAmIPaddress']
                  if (lst_docker.contains(env_string)) {
                    ip_list_docker.add(env_string)
                  } else if (lst_rancher.contains(env_string)) {
                    ip_list_rancher.add(env_string)
                  }

                  // 再次把所有IP按照部署到内网或外网进行分类
                  def lst_office = ['IAmIPaddress']
                  def lst_extranet = ['IAmIPaddress']
                  if (lst_office.contains(env_string)) {
                    ip_list_office.add(env_string)
                  } else if (lst_extranet.contains(env_string)) {
                    ip_list_extranet.add(env_string)
                  }
                } else {
                  namespaces_list.add(deploy_to_env)
                }
              }
              println("需要用docker部署的：" + ip_list_docker)
              println("需要用rancher部署的："+ ip_list_rancher)
              println("需要用k8s部署的：" + namespaces_list)
              println("需要部署到内网的：" + ip_list_office)
              println("需要部署到外网的：" + ip_list_extranet)
            }
          }
        }

        stage('用k8s启动服务'){
          when {
            expression {
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

              helm_values_file="temp_jenkins_workspace/${project}/${svc_name}/${svc_name}-values.yaml"
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
                  helm pull oci://${hwcloud_registry}/${project}-charts/${svc_name} --version 0.1.7
                """
              }
              // 循环处理需要部署的命名空间
              for (namespaces in namespaces_list) {
                echo "正在部署到 " + namespaces + " 环境."
                configEnv = libTools.splitNamespaces(namespaces)
                configEnvPrefix = configEnv[0]
                configEnvSuffix = configEnv[1]
                println("CONFIG_ENV：" + configEnvSuffix)
                println("项目简称，用于命名空间的前缀：" + configEnvPrefix)
                // harbor.betack.com/rabbeyond/sdk-grpc-server:13-1.9.14
                image_tag = "${BUILD_NUMBER}-${env.RELEASE_SCOPE}"
                sh """
                  sed -i 's#JAR_PKG_NAME#${PKG_NAME}#' ${svc_name}-values.yaml
                  sed -i 's#NAMESPACEPREFIX#${configEnvPrefix}#' ${svc_name}-values.yaml
                """
                configFileProvider([configFile(fileId: "fd4efaf3-23f9-4f31-a085-3e3baa9618d4", targetLocation: "kube-config.yaml")]){
                  doDeployToK8s(namespaces, svc_name, image_tag, project, '0.1.7')
                }
              }
            }
          }
        }

        stage('用docker启动服务') {
          when {
            expression {
              return  (ip_list_docker != [])
            }
          }
          steps {
            container('tools') {
              echo '正在用ansible远程部署服务...'
              script {
                // 循环处理需要部署的环境
                // 注意，需要提前用命令：ssh-copy-id -i /iamusername/.ssh/id_rsa_jenkins.pub betack@IAmIPaddress 进行免密认证
                sshagent (credentials: ['830e90a8-1fec-4a45-9317-415e7acaff10']) {
                  for (env_item in ['office', 'extranet']) {
                    def deploy_ip_lst = []
                    // 取不同的镜像仓库
                    switch(env_item) {
                      case 'office':
                        if (ip_list_office) {
                          registry = office_registry
                          for (ip_itme in ip_list_office) {
                            if (ip_list_docker.contains(ip_itme)) {
                              deploy_ip_lst.add(ip_itme)
                            }
                          }
                        } else {
                          continue
                        }
                      break
                      case 'extranet':
                        if (ip_list_extranet) {
                          registry = hwcloud_registry
                          for (ip_itme in ip_list_extranet) {
                            if (ip_list_docker.contains(ip_itme)) {
                              deploy_ip_lst.add(ip_itme)
                            }
                          }
                        } else {
                          continue
                        }
                      break
                    }

                    println(deploy_ip_lst)
                    for (dest_host_ip in deploy_ip_lst) {
                      // 部署的挂载路径、端口等差异化处理
                      switch(dest_host_ip) {
                        case 'IAmIPaddress':
                          svc_host_port = 50053
                          config_host_dir = '/data1t/bsc-data/bsc-sdk-grpc-server'
                          data_host_dir = '/data1t/bsc-data/bsc-rabbeyond'
                        break
                        default:
                          svc_host_port = 50053
                          config_host_dir = '/data1t/sdk-grpc-server/config'
                          data_host_dir = '/data1t/sdk-grpc-server/data'
                        break
                      }

                      // 特别注意：第一次部署后，服务器启动需要的配置文件，需要手搓搞进去
                      echo '创建ansible-playbook用的文件docker-deploy.yml'
                      dockerDeploy = """
                      - name: deploy ${svc_name}
                        hosts: "{{ target }}"
                        gather_facts: no
                        tasks:
                          - name: Restart a container
                            docker_container:
                              name: ${svc_name}
                              image: ${registry}/${project}/${svc_name}:${BUILD_NUMBER}-${env.RELEASE_SCOPE}
                              state: started
                              restart: yes
                              ports:
                              # Publish container port 50053 as host port 50050
                              #- "50050:50053"
                              - "${svc_host_port}:50053"
                              volumes:
                                - ${config_host_dir}/grpc-server.toml:/opt/betack/grpc-server.toml
                                - ${data_host_dir}:/opt/betack/rabbeyond
                              command: ["java", "-jar", "/opt/betack/${PKG_NAME}"]
                      """.stripIndent()
                      writeFile file: 'docker-deploy.yml', text: "${dockerDeploy}", encoding: 'UTF-8'
                      echo "用docker启动：${dest_host_ip} 的 ${svc_name}"
                      sh 'cat docker-deploy.yml'

                      echo '远程启动docker容器.'
                      configFileProvider([configFile(fileId: "bf00105b-50ad-4791-92b2-d5f0431d217a", targetLocation: "ansible-hosts")]){
                        sh """
                          # 延迟60秒，等office harbor镜像同步到huaweicloud harbor仓库
                          sleep 15
                          ansible-playbook --inventory-file ansible-hosts -e "{target: ${dest_host_ip}}" -i "${dest_host_ip}," -u betack docker-deploy.yml
                        """
                      }
                    }
                  }
                }
              }
            }
          }
        }

        stage('用rancher托管服务') {
          when {
            expression {
              return  (ip_list_rancher != [])
            }
          }
          steps {
            echo '正在用rancher启动服务...'
            script {
              // 循环处理需要部署的环境
              for (env_item in ['office', 'extranet']) {
                def deploy_ip_lst = []
                // 取不同的镜像仓库
                switch(env_item) {
                  case 'office':
                    if (ip_list_office) {
                      registry = office_registry
                      for (ip_itme in ip_list_office) {
                        if (ip_list_rancher.contains(ip_itme)) {
                          deploy_ip_lst.add(ip_itme)
                        }
                      }
                    } else {
                      continue
                    }
                  break
                  case 'extranet':
                    if (ip_list_extranet) {
                      registry = hwcloud_registry
                      for (ip_itme in ip_list_extranet) {
                        if (ip_list_rancher.contains(ip_itme)) {
                          deploy_ip_lst.add(ip_itme)
                        }
                      }
                    } else {
                      continue
                    }
                  break
                }

                println(deploy_ip_lst)
                // 遍历处理需要部署的IP清单
                for (dest_host_ip in deploy_ip_lst) {
                  // 部署的挂载路径、端口等差异化处理
                  switch(dest_host_ip) {
                    case 'IAmIPaddress':
                      svc_host_port = 50054
                      config_host_dir = '/data2t/grpc-server'
                      data_host_dir = '/data2t/opt/rab_backend/data/rabbeyond'
                      rancherSpacesDIR = 'rab'
                      env.RANCHER_URL = "http://IAmIPaddress:8080/"
                      env.RANCHER_ACCESS_KEY = "3F7FEDEFEC342325C0B4"
                      env.RANCHER_SECRET_KEY = "dSnSfZxqfQHVkjte5GrZzwSYcq5ZBiEr6k7ELEq8"
                    break
                    default:
                      svc_host_port = 50053
                      config_host_dir = '/data1t/sdk-grpc-server/config'
                      data_host_dir = '/data1t/sdk-grpc-server/data'
                      rancherSpacesDIR = 'rab'
                      env.RANCHER_URL = ""
                      env.RANCHER_ACCESS_KEY = ""
                      env.RANCHER_SECRET_KEY = ""
                    break
                  }

                  // 特别注意：第一次部署后，服务器启动需要的配置文件，需要手搓搞进去
                  echo '创建rancher需要用的文件docker-compose.yml'
                  dockerCompose = """
                    version: '2'
                    services:
                      ${svc_name}:
                        image: ${registry}/${project}/${svc_name}:${BUILD_NUMBER}-${env.RELEASE_SCOPE}
                        stdin_open: true
                        volumes:
                        - ${config_host_dir}/grpc-server.toml:/opt/betack/grpc-server.toml
                        - ${data_host_dir}:/opt/betack/rabbeyond
                        tty: true
                        ports:
                        - ${svc_host_port}:50053/tcp
                        command:
                        - java
                        - -jar
                        - /opt/betack/${PKG_NAME}
                  """.stripIndent()
                  writeFile file: 'docker-compose.yml', text: "${dockerCompose}", encoding: 'UTF-8'
                  sh 'cat docker-compose.yml'

                  echo '创建rancher需要用的文件rancher-compose.yml'
                  rancherCompose = """
                    version: '2'
                    services:
                      ${svc_name}:
                        scale: 1
                        start_on_create: true
                  """.stripIndent()
                  writeFile file: 'rancher-compose.yml', text: "${rancherCompose}", encoding: 'UTF-8'
                  sh 'cat rancher-compose.yml'

                  echo "正在执行 rancher-compose 命令，更新 ${svc_name} 到 ${dest_host_ip} 环境."
                  sh """
                    rancher-compose -f ./docker-compose.yml -r ./rancher-compose.yml -p ${rancherSpacesDIR} up --upgrade --pull --confirm-upgrade -d ${svc_name}
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


def doDeployToK8s(deployToEnv, deploySVCName, imageTag, project, chartVersion) {
    echo "正在部署到 " + deployToEnv + " 环境."
    sh """
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
    // sdk-grpc-server-0.1.3.tgz
    sh """
      helm --kubeconfig kube-config.yaml ${deployType} ${deploySVCName} -n ${deployToEnv} -f ${deploySVCName}-values.yaml ${deploySVCName}-${chartVersion}.tgz
    """
}

