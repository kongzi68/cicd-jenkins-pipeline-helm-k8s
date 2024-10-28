#!groovy
// 公共
def office_registry = "IAmIPaddress:8765"
def hwcloud_registry = "harbor.betack.com"

// 项目
def project = "rabbeyond"  // HARrab镜像仓库中的项目名称
def git_address = 'ssh://git@code.betack.com:4022/beyond/data-insight-server.git'
// 认证
// def secret_name = 'harbor-inner'
def harbor_auth = '443eb7ee-c21b-4e32-b449-e01d83171672'
def git_auth = '41dfa7f4-d5f9-4892-a8ba-6b0d31a7cd84'

// 参照文档：https://code.betack.com/chenyouguo/rab-engine-x/-/blob/feature/2022-08-30-dag-storage/rust/document/rabbeyond-visual.md


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
    timeout(time: 30, unit:'MINUTES') // 设置此次发版运行30分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '30')  // 设置保留30天的构建历史，或者30次的构建记录
  }

  parameters {
    gitParameter branch: '',
                 branchFilter: '.*',
                 defaultValue: 'master',
                 listSize: '5',
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
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'data-insight-server',
                   visibleItemCount: 8
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'saasdata-dev-1,saasdata-staging-1',
                   visibleItemCount: 8
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
        echo "当前 cargo 版本信息如下："
        sh """
          PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:/usr/local/games:/snap/bin
          . /etc/profile
          export -p
          cargo --version
          #apt-get update
          #apt-get -y install cmake protobuf-compiler
        """
      
        script {
          // 配置cargo代理
          config_file = """
            # 放到 \$HOME/.cargo/config 文件中
            [source.crates-io]
            #registry = "https://github.com/rust-lang/crates.io-index"
            # 替换成你偏好的镜像源
            #replace-with = 'sjtu'
            replace-with = 'ustc'

            # 清华大学
            [source.tuna]
            registry = "https://mirrors.tuna.tsinghua.edu.cn/git/crates.io-index.git"

            # 中国科学技术大学
            [source.ustc]
            registry = "https://mirrors.ustc.edu.cn/crates.io-index"

            # 上海交通大学
            [source.sjtu]
            registry = "https://mirrors.sjtug.sjtu.edu.cn/git/crates.io-index"

            # rustcc社区
            [source.rustcc]
            registry = "git://crates.rustcc.cn/crates.io-index"

            # 字节
            [source.rsproxy]
            registry = "https://rsproxy.cn/crates.io-index"

            [registries.rsproxy]
            index = "https://rsproxy.cn/crates.io-index"

            [net]
            git-fetch-with-cli = true 
          """.stripIndent()

          writeFile file: 'config_file', text: "${config_file}", encoding: 'UTF-8'
          sh '''
            pwd; ls -lh config_file
            mv config_file $HOME/.cargo/config
            echo "配置的代理内容如下："
            cat $HOME/.cargo/config
          '''
        }
      }
    }

    stage('代码编译打包') {
      steps {
        echo '正在构建...'
        script {
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            echo "当前正在构建服务：${deploy_svc_name}"
            try {
              withCredentials([usernameColonPassword(credentialsId: '3d040389-9dfe-4c0d-9dab-9f6487f10409', variable: 'USERPASS')]) {
                sh """
                  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:/usr/local/games:/snap/bin
                  . /etc/profile
                  # 这个项目依赖需要从gitlab拉代码
                  # sleep 3600
                  echo 'https://${USERPASS}@code.betack.com' > ${WORKSPACE}/git-credentials
                  git config --global credential.helper 'store --file ${WORKSPACE}/git-credentials'
                  [ -d ${WORKSPACE}/.cargo ] || mkdir ${WORKSPACE}/.cargo
                  cp -a $HOME/.cargo/config ${WORKSPACE}/.cargo/
                  CARGO_HOME=${WORKSPACE}/.cargo cargo build --release
                """
              }
              echo "构建的jar包 ${deploy_svc_name} 信息如下："
              sh "ls -lh target/release/${deploy_svc_name}; pwd"
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
        container('podman') {
          script{
            for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
              // 创建构建docker镜像用的临时目录
              sh """
                [ -d temp_docker_build_dir ] || mkdir temp_docker_build_dir
                cp target/release/${deploy_svc_name} temp_docker_build_dir/${deploy_svc_name}
              """
              echo "构建服务：${deploy_svc_name} 的docker镜像"
              dir("${env.WORKSPACE}/temp_docker_build_dir") {
                docker_file = """
                  FROM ${office_registry}/libs/ubuntu:20.04
                  LABEL maintainer="colin" version="1.0" datetime="2021-01-11"
                  RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
                      echo "Asia/Shanghai" > /etc/timezone
                  RUN apt-get update && apt-get -y install netcat-openbsd curl wget
                  WORKDIR /opt/betack
                  COPY ${deploy_svc_name} /opt/betack/${deploy_svc_name}
                """.stripIndent()

                writeFile file: 'Dockerfile', text: "${docker_file}", encoding: 'UTF-8'
                sh '''
                  pwd; ls -lh
                  cat Dockerfile
                '''

                withCredentials([usernamePassword(credentialsId: "${harbor_auth}", passwordVariable: 'password', usernameVariable: 'username')]) {
                  image_name = "${office_registry}/${project}/${deploy_svc_name}:${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                  sh """
                    podman login -u ${username} -p '${password}' ${office_registry}
                    podman image build -t ${image_name} -f Dockerfile .
                    podman image push ${image_name}
                    podman image tag ${image_name} "${office_registry}/${project}/${deploy_svc_name}:latest"
                    podman image push "${office_registry}/${project}/${deploy_svc_name}:latest"
                  """

                  // 推送镜像到hwcould仓库：harbor.betack.com
                  def _EXTRANET_HARBOR = Boolean.valueOf("${params.EXTRANET_HARBOR}")
                  if (_EXTRANET_HARBOR) {
                    extranet_image_name = "${hwcloud_registry}/${project}/${deploy_svc_name}:${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                    sh """
                      podman login -u ${username} -p '${password}' ${hwcloud_registry}
                      podman image tag ${image_name} ${extranet_image_name}
                      podman image push ${extranet_image_name}
                      podman image rm ${extranet_image_name}
                    """
                  }
                }

                // 镜像打包后，清理jar包，减少docker build上下文，清理构建环境的镜像节约磁盘空间
                sh """
                  rm -rf "${env.WORKSPACE}/temp_docker_build_dir/*"
                  # podman image rm ${image_name}
                  podman image rm ${office_registry}/${project}/${deploy_svc_name}:latest
                """
              }
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
              helm_values_file="temp_jenkins_workspace/${project}/${deploy_svc_name}/deployToK8s/${deploy_svc_name}-values.yaml"
              sh """
                pwd
                ls -lh ${helm_values_file}
                cp ${helm_values_file} .
                ls -lh
              """

              configFileProvider([configFile(fileId: "fd4efaf3-23f9-4f31-a085-3e3baa9618d4", targetLocation: "kube-config.yaml")]){
                // 针对项目集成了 sdk-grpc 特例处理
                switch(namespaces) {
                  case ['saasdata-dev-1','saasdata-dev-2','saasdata-staging-1','saasdata-staging-2']:
                    NAMESPACEPREFIX = 'saasdata'
                    // 标件的服务saas-app-server，集成了sdk-grpc 服务 50053 端口
                    SDK_GRPC_SVC_NAME = 'saas-data-server'
                  break
                  default:
                  break
                }

                tempEvn=namespaces.replaceAll("${NAMESPACEPREFIX}-","")
                // 获取sdk-grpc-server svc 的 NodePort 暴露出来的端口
                try {
                  env.GRPC_NODEPORT = sh (script: """
                    kubectl --kubeconfig=kube-config.yaml -n ${namespaces} get svc ${SDK_GRPC_SVC_NAME}-out-${tempEvn} -o jsonpath='{.spec.ports[?(@.port==50053)].nodePort}'
                    """, returnStdout: true).trim()
                  println("${SDK_GRPC_SVC_NAME} NodePort：" + env.GRPC_NODEPORT)
                } catch(Exception err) {
                  env.GRPC_NODEPORT = ''
                  error "未获取 ${SDK_GRPC_SVC_NAME} service 的 nodePort 端口，终止本次部署。"
                }

                image_tag = "${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                doDeployToK8s(namespaces, deploy_svc_name, image_tag, project, '0.1.1', NAMESPACEPREFIX)
              }
            }
          }
        }
      }
    }
  }
}


def doDeployToK8s(deployToEnv, deploySVCName, imageTag, project, chartVersion, namespacePrefix) {
  echo "正在部署到 " + deployToEnv + " 环境."
  sh """
    sed -i 's#IMAGE_TAG#${imageTag}#g' ${deploySVCName}-values.yaml
    sed -i 's#SDK_GRPC_NODEPORT#${env.GRPC_NODEPORT}#g' ${deploySVCName}-values.yaml
    sed -i 's#NAMESPACEPREFIX#${namespacePrefix}#' ${deploySVCName}-values.yaml
    cat ${deploySVCName}-values.yaml
  """
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
    helm pull oci://harbor.betack.com/${project}-charts/${deploySVCName} --version ${chartVersion}
    helm --kubeconfig kube-config.yaml ${deployType} ${deploySVCName} -n ${deployToEnv} -f ${deploySVCName}-values.yaml ${deploySVCName}-${chartVersion}.tgz
  """
}
