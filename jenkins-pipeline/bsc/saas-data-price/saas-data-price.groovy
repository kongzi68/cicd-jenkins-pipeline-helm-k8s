#!groovy
/* 导入Jenkins共享库，默认导入main分支 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
def hwcloud_registry = "harbor.betack.com"

// 项目
def project = "bf-bsc"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/betack/saas-data-price.git"

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
          - name: ansible
            image: "${office_registry}/libs/alpine:3.15.4-ansible"
            imagePullPolicy: Always
            tty: true
            env:
              - name: "file.encoding"
                value: "UTF-8"
              - name: "LANG"
                value: "zh_CN.UTF-8"
              - name: "sun.jnu.encoding"
                value: "UTF-8"
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
                 description: '若需要部署到【公网】，请勾选此选项，将会把镜像同步推送一份到HARBOR镜像仓库: harbor.betack.com',
                 name: 'EXTRANET_HARBOR'
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'saas-data-price',
                   visibleItemCount: 3
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'prod-IAmIPaddress',
                   visibleItemCount: 10
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将把 Arthas Java 诊断工具打包到镜像中',
                 name: 'ARTHAS_TOOLS'
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
          echo "当前正在构建服务：${params.DEPLOY_SVC_NAME}"
          JAR_PKG_NAME = "build/libs/${params.DEPLOY_SVC_NAME}-0.0.1-SNAPSHOT.jar"
          javaCodeCompile() // 不传入服务名称，即表示单服务直接构建
          echo "构建 ${params.DEPLOY_SVC_NAME} 的 jar 包信息如下："
          sh "ls -lh ${JAR_PKG_NAME}; pwd"
        }
      }
    }

    stage('构建镜像上传HARBOR仓库') {
      steps {
        script{
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            TEMP_JAR_NAME = "${deploy_svc_name}-0.0.1-SNAPSHOT.jar"
            JAR_PKG_NAME = "build/libs/${TEMP_JAR_NAME}"
            // DEPLOY_JAR_PKG_NAME = "${deploy_svc_name}-${COMMIT_SHORT_ID}-${BUILD_NUMBER}.jar"
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

    stage('用docker方式启动服务') {
      steps {
        container('ansible') {
          echo '正在用ansible远程部署服务...'
          script {
            def _EXTRANET_HARBOR = Boolean.valueOf("${params.EXTRANET_HARBOR}")
            if (_EXTRANET_HARBOR) {
              extranetImageName = imageDict.get(params.DEPLOY_SVC_NAME).get('extranetImageName')
              println(extranetImageName)
            } else {
              error '未传镜像到公网HARBOR仓库：harbor.betack.com'
            }
            // 循环处理需要部署的环境
            // 注意，需要提前用命令：ssh-copy-id -i /iamusername/.ssh/id_rsa_jenkins.pub betack@ipaddress 进行免密认证
            sshagent (credentials: ['830e90a8-1fec-4a45-9317-415e7acaff10']) {
              def env_list = []
              for (deploy_to_env in params.DEPLOY_TO_ENV.tokenize(',')) {
                ip_address = deploy_to_env.tokenize('-')[-1]
                env_list.add(ip_address)
              }
              dest_hosts = env_list.join(',')
              println(dest_hosts)

              // 创建 ansible.cfg
              ansible_config = """
                [defaults]
                host_key_checking = False
                deprecation_warnings = False
                command_warnings = False
              """.stripIndent()
              writeFile file: 'ansible.cfg', text: "${ansible_config}", encoding: 'UTF-8'

              echo '创建ansible-playbook用的文件docker-deploy.yml'
              dockerDeploy = """
                - name: deploy ${params.DEPLOY_SVC_NAME} service
                  hosts: "{{ target }}"
                  gather_facts: no
                  tasks:
                    - name: Restart a container
                      docker_container:
                        name: ${params.DEPLOY_SVC_NAME}
                        image: ${extranetImageName}
                        state: started
                        restart: yes
                        ports:
                          # host-port:container-port
                          - 81:80
                          - 5005:5005
                          - 9090:9090
                        volumes:
                          - /data1t/saas-data-price/log/:/var/log
                        command: 
                          - java
                          - -Dlog.path=/var/log
                          - -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
                          - -jar /opt/betack/${DEPLOY_JAR_PKG_NAME}
                """.stripIndent()
              writeFile file: 'docker-deploy.yml', text: "${dockerDeploy}", encoding: 'UTF-8'
              configFileProvider([configFile(fileId: "bf00105b-50ad-4791-92b2-d5f0431d217a", targetLocation: "ansible-hosts")]){
                sh """
                  cat docker-deploy.yml
                  echo '远程启动docker容器.'
                  echo '等待30~90秒，让镜像从office同步到华为云cloud'
                  # 根据网络情况，同步大约要60秒
                """
                try {
                  // 重试3次，每次间隔30秒
                  retry(3) {
                    sleep 10
                    // 这里需要特别注意betack用户执行docker命令的权限问题
                    // 如果在betack用户下，执行命令 docker container ls 可以列出容器，那么ansible-playbook 使用 betack 用户才能管理容器
                    sh """
                      # ansible all -i "IAmIPaddress," -u betack -m ping
                      ansible-playbook --inventory-file ansible-hosts -e "{target: ${dest_hosts}}" -i "${dest_hosts}," -u betack docker-deploy.yml
                    """
                  }
                }catch (Exception err) {
                  error "${err}"
                }
              }
            }
          }
        }
      }
    }
  }
}