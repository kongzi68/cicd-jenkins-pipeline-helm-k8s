#!groovy
// 公共
def office_registry = "IAmIPaddress:8765"
def hwcloud_registry = "harbor.betack.com"

// 文档框架官网，https://docsify.js.org/
// 项目，以后所有文档都归于bf-docs下
def project = "bf-docs"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/betack/rabBeyond-Document.git"
// 认证
// def secret_name = "bf-harbor"
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
                memory: "4000Mi"
                cpu: "1000m"
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
          - name: harbor-outer
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
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将会把镜像同步推送一份到HARBOR镜像仓库: harbor.betack.com',
                 name: 'EXTRANET_HARBOR'
    choice (choices: ['docsify-rabbeyond-docs-site'],
            description: '请选择本次发版需要部署的服务',
            name: 'DEPLOY_SVC_NAME')
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'hwcloud-IAmIPaddress',
                   visibleItemCount: 4
  }

  stages {
    stage('拉取代码') {
      steps {
        container('jnlp') {
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

            // 拉取部署需要的配置文件
            echo '正在从gitlab拉取更新部署用的nginx配置文件...'
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
          }
        }
      }
    }

    stage('把文档打包到镜像') {
      stages {
        stage('创建Dockerfile') {
          steps {
            script {
              docker_file = """
                FROM ${office_registry}/libs/nginx:1.24.0-auth
                COPY docs /var/www/html/
                COPY temp_jenkins_workspace/bf-docs/rabbeyond-docs-site/deployToDocker/nginx.conf /etc/nginx/nginx.conf
                COPY temp_jenkins_workspace/bf-docs/rabbeyond-docs-site/deployToDocker/web.conf /etc/nginx/conf.d/default.conf
                RUN mkdir /var/www/chkstatus && echo "this is test, one!" > /var/www/chkstatus/tt.txt
                EXPOSE 80
                WORKDIR /var/www
              """.stripIndent()

              writeFile file: 'Dockerfile', text: "${docker_file}", encoding: 'UTF-8'
              sh '''
                pwd; ls -lh Dockerfile
                cat Dockerfile
              '''
            }
          }
        }

        stage('构建镜像，并上传到harbor仓库') {
          steps {
            container('podman') {
              script {
                echo "构建镜像，并上传到harbor仓库"
                withCredentials([usernamePassword(credentialsId: "${harbor_auth}", passwordVariable: 'password', usernameVariable: 'username')]) {
                  image_name = "${office_registry}/${project}/${params.DEPLOY_SVC_NAME}:${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                  sh """
                    pwd; ls -lh
                    podman login -u ${username} -p '${password}' ${office_registry}
                    podman image build -t ${image_name} .
                    podman image push ${image_name}
                    podman image tag ${image_name} "${office_registry}/${project}/${params.DEPLOY_SVC_NAME}:latest"
                    podman image push "${office_registry}/${project}/${params.DEPLOY_SVC_NAME}:latest"
                  """

                  // 推送镜像到hwcould仓库：harbor.betack.com
                  def _EXTRANET_HARBOR = Boolean.valueOf("${params.EXTRANET_HARBOR}")
                  if (_EXTRANET_HARBOR) {
                    extranet_image_name = "${hwcloud_registry}/${project}/${params.DEPLOY_SVC_NAME}:${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                    env.EXTRANET_IMAGE_NAME = extranet_image_name
                    sh """
                      podman login -u ${username} -p '${password}' ${hwcloud_registry}
                      podman image tag ${image_name} ${extranet_image_name}
                      podman image push ${extranet_image_name}
                      podman image rm ${extranet_image_name}
                    """
                  }
                }

                // 镜像打包后，清理垃圾文件与目录，清理构建环境的镜像节约磁盘空间
                sh """
                  podman image rm ${image_name}
                  podman image rm ${office_registry}/${project}/${params.DEPLOY_SVC_NAME}:latest
                """
              }
            }
          }
        }

        stage('用docker方式启动服务') {
          steps {
            container('ansible') {
              echo '正在用ansible远程部署服务...'
              script {
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

                  // 匹配部署的端口，新增的话，需要添加指定的端口
                  switch(params.DEPLOY_SVC_NAME) {
                    // 因暂时保留旧文档，这里使用36903
                    case 'docsify-rabbeyond-docs-site':
                      deploy_port = 39603
                    break
                  }

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
                    - name: deploy ${params.DEPLOY_SVC_NAME} docs
                      hosts: "{{ target }}"
                      gather_facts: no
                      tasks:
                        - name: Restart a container
                          docker_container:
                            name: ${params.DEPLOY_SVC_NAME}
                            image: harbor.betack.com/${project}/${params.DEPLOY_SVC_NAME}:${env.GIT_COMMIT_MSG}-${BUILD_NUMBER}
                            state: started
                            restart: yes
                            ports:
                              # Publish container port 80 as host port 34567
                              # host-port:container-port
                              - "${deploy_port}:80"
                            volumes:
                              - /opt/important_data/rabbeyond-docs-site/:/var/www/auth/
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
                echo "文档：${params.DEPLOY_SVC_NAME}，在浏览器里面输入访问地址：http://${dest_hosts}:${deploy_port}"
              }
            }
          }
        }
      }
    }
  }
}
