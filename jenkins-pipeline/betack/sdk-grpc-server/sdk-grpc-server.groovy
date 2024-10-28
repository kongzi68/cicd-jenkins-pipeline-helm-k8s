#!groovy
/*
华为云服务器：IAmIPaddress，孟亚朋提的需求
更新服务：sdk-grpc-server
文档： http://ci.betack.com:61032/1.6.X/rab-engine/maintain/deploy-with-single/#_1
release 版本：http://jfrog.betack.com:8081/artifactory/webapp/#/artifacts/browse/tree/General/libs-release-local/com/betack/sdk-grpc-server
snapshot 版本：http://jfrog.betack.com:8081/artifactory/webapp/#/artifacts/browse/tree/General/libs-snapshot-local/com/betack/sdk-grpc-server
*/

// 公共
// office harbor
def registry = "harbor.od.com"
def harbor_auth = "443eb7ee-c21b-4e32-b449-e01d83171672"
def jfrog_url = "https://jfrog.betack.com"
def svc_name = "sdk-grpc-server"
def svc_jfrog_path = "com/betack/${svc_name}"
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
            image: "${registry}/libs/ubuntu-jenkins-agent:jdk17-nogradle"
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
          - name: ansible
            image: "${registry}/libs/alpine-ansible:3.15.4"
            imagePullPolicy: Always
            tty: true
            env:
              - name: "file.encoding"
                value: "UTF-8"
              - name: "LANG"
                value: "zh_CN.UTF-8"
              - name: "sun.jnu.encoding"
                value: "UTF-8"
            command: ["/bin/sh", "-c", 'for i in `seq 1 100`; do sleep 60; done;']
          dnsConfig:
            nameservers:
            - IAmIPaddress
            - IAmIPaddress
          imagePullSecrets:
          - name: bf-harbor
          volumes:
            - name: docker-cmd
              hostPath:
                path: /usr/bin/docker
            - name: docker-sock
              hostPath:
                path: /var/run/docker.sock
      """.stripIndent()
    }
  }

  // 设置pipeline Jenkins选项参数
  options {
    skipDefaultCheckout true          // 忽略默认的checkout
    skipStagesAfterUnstable()         // 忽略报错后面的步骤
    //retry(2)                        // 重试次数
    timestamps()                      // 添加时间戳
    timeout(time: 20, unit:'MINUTES') // 设置此次发版运行20分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', 
                              artifactNumToKeepStr: '',
                              daysToKeepStr: '30',
                              numToKeepStr: '30')  // 设置保留30天的构建历史，或者30次的构建记录
  }

  parameters {
    choice choices: ['release版本','snapshot版本'],
           description: '请选择需要部署的包类型',
           name: 'SELECT_PKG_TYPE'
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'hwcloud-IAmIPaddress',
                   visibleItemCount: 7
  }

  stages {
    stage('选择需要部署的版本号') {
      options {
          timeout(time: 5, unit: 'MINUTES') 
      }
      steps {
        container('jnlp') {
          script {
            switch(params.SELECT_PKG_TYPE) {
              case 'release版本':
                temp_path = 'libs-release-local'
              break
              case 'snapshot版本':
                temp_path = 'libs-snapshot-local'
              break
            }
            // 查找当前WORKERSPACE下的py文件
            // 处理成选择框使用的 value
            withCredentials([usernamePassword(credentialsId: "5f65343b-7226-4b5e-840d-23f7687108e1",
                                              passwordVariable: 'password',
                                              usernameVariable: 'username')]) {
              PKG_VERSIONS = sh (script: """
                curl -u${username}:${password} "${jfrog_url}/artifactory/${temp_path}/${svc_jfrog_path}/maven-metadata.xml" | \
                  grep "<version>" | sed 's/.*<version>\\([^<]*\\)<\\/version>.*/\\1/' | tail -10 | xargs
                """, returnStdout: true).trim()
              // println(PKG_VERSIONS)
              PKG_VERSION_LIST = PKG_VERSIONS.tokenize()
              env.BUILD_ARCHS = PKG_VERSION_LIST[-1]
            }
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

    stage('获取grpc-server.properties文件') {
      steps {
        echo '正在从gitlab拉取grpc-server.properties文件...'
        script {
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
              unstable '拉取jenkins.git代码失败'
            }
            sh "pwd; ls -lh"
          }
        }
      }
    }

    stage('打包镜像并上传到HARBOR仓库') {
      steps {
        container('jnlp') {
          script{
            stage('创建Dockerfile') {
              // 复制 grpc-server.properties
              sh """
                cp temp_jenkins_workspace/betack/sdk-grpc-server/grpc-server.properties .
                pwd; ls -lh
              """
              // 创建 创建Dockerfile
              sh """
                echo '''
                  FROM ${registry}/libs/amazoncorretto:8
                  LABEL maintainer="colin" version="1.0" datetime="2021-01-11"
                  RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime
                  RUN echo "Asia/Shanghai" > /etc/timezone
                  # COPY grpc-server.properties /opt/betack/grpc-server.properties
                  COPY ${PKG_NAME} /opt/betack/${PKG_NAME}
                  WORKDIR /opt/betack
                ''' > Dockerfile
              """
            }

            stage('构建镜像，并上传到harbor仓库') {
              withCredentials([usernamePassword(credentialsId: "${harbor_auth}", passwordVariable: 'password', usernameVariable: 'username')]) {
                image_name = "${registry}/bf-project/${svc_name}-119-3-137-87:${BUILD_NUMBER}-${env.RELEASE_SCOPE}"
                sh """
                  docker login -u ${username} -p '${password}' ${registry}
                  docker image build -t ${image_name} .
                  docker image push ${image_name}
                """
              }
            }
          }
        }
      }
    }

    // harbor.betack.com:8443/bf-project/sdk-grpc-server-119-3-137-87:36-1.6.11-SNAPSHOT_2.12

    stage('用docker方式启动服务') {
      steps {
        container('ansible') {
          echo '正在用ansible远程部署服务...'
          script {
            // 循环处理需要部署的环境
            // 注意，需要提前用命令：ssh-copy-id -i /iamusername/.ssh/id_rsa_jenkins.pub betack@IAmIPaddress 进行免密认证
            sshagent (credentials: ['830e90a8-1fec-4a45-9317-415e7acaff10']) {
              def env_list = []
              for (deploy_to_env in params.DEPLOY_TO_ENV.tokenize(',')) {
                ip_address = deploy_to_env.tokenize('-')[-1]
                env_list.add(ip_address)
              }
              dest_hosts = env_list.join(',')
              println(dest_hosts)

              sh """
                echo '创建ansible-playbook用的文件docker-deploy.yml'
                echo '''
- name: deploy ${svc_name}
  hosts: "{{ target }}"
  gather_facts: no
  tasks:
    - name: Restart a container
      docker_container:
        name: ${svc_name}
        image: harbor.betack.com/bf-project/${svc_name}-119-3-137-87:${BUILD_NUMBER}-${env.RELEASE_SCOPE}
        state: started
        restart: yes
        ports:
        # Publish container port 50053 as host port 50050
        #- "50050:50053"
        - "50053:50053"
        volumes:
          - /home/betack/rabbeyond/sdk-grpc-server/config/grpc-server.properties:/opt/betack/grpc-server.properties
          - /home/betack/rabbeyond/sdk-grpc-server/data:/opt/betack/rabbeyond
        command: ["java", "-jar", "/opt/betack/${PKG_NAME}"]
                ''' > docker-deploy.yml
              """
              configFileProvider([configFile(fileId: "bf00105b-50ad-4791-92b2-d5f0431d217a", targetLocation: "ansible-hosts")]){
                sh """
                  echo '远程启动docker容器.'
                  # 延迟60秒，等office harbor镜像同步到huaweicloud harbor仓库
                  sleep 60
                  ansible-playbook --inventory-file ansible-hosts -e "{target: ${dest_hosts}}" -i "${dest_hosts}," -u betack docker-deploy.yml
                """
              }
            }
          }
        }
      }
    }
  }
}