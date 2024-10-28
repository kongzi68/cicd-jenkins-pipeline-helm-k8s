#!groovy
// 公共
def office_registry = "IAmIPaddress:8765"
def hwcloud_registry = "harbor.betack.com"

// 项目
def project = "rab-pf"  // HARrab镜像仓库中的项目名称
// tougu前端，基金投资顾问UI，常简称为：counsel-frontend
def git_address = 'ssh://git@code.betack.com:4022/betack/fund-investment-advisor-ui.git'
// 认证
def secret_name = 'harbor-inner'
def harbor_auth = '443eb7ee-c21b-4e32-b449-e01d83171672'
def git_auth = '41dfa7f4-d5f9-4892-a8ba-6b0d31a7cd84'


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
                 defaultValue: 'master',
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
    choice (choices: ['fund-investment-advisor-ui'],
            description: '请选择本次发版需要部署的服务',
            name: 'DEPLOY_SVC_NAME')
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'rab-pf-dev-1,rab-pf-staging-1',
                   visibleItemCount: 5
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
          } catch (Exception err) {
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
        echo '当前 npm、yarn 版本与配置信息如下：'
        sh '''
          npm config set registry https://registry.npmmirror.com
          yarn config set registry https://registry.npmmirror.com
          yarn config set sass-binary-site https://npmmirror.com/mirrors/node-sass/
          npm install -g n
          # 安装指定版本的node.js 
          export N_NODE_MIRROR=https://mirrors.aliyun.com/nodejs-release/
          n 12.22.1
          hash -r
          npm config list
          yarn config list
          yarn config set ignore-engines true
        '''
      }
    }

    stage('代码编译打包') {
      /*
      environment {
        def http_proxy="http://IAmIPaddress:31687/"
        def HTTP_PROXY="http://IAmIPaddress:31687/"
        def https_proxy="http://IAmIPaddress:31687/"
        def HTTPS_PROXY="http://IAmIPaddress:31687/"
        // def ftp_proxy="http://xxx.xxx.xxx:xxx/"
        def no_proxy="*.betack.com,*.ustc.edu.cn,localhost,IAmIPaddress"
        def NO_PROXY="*.betack.com,*.ustc.edu.cn,localhost,IAmIPaddress"
      }
      */
      steps {
        echo '正在构建...'
        script {
          try {
            sh """
              export LANG=zh_CN.UTF-8
              export LC_ALL=zh_CN.UTF-8
              yarn install
              yarn run i18n
              #yarn run build
            """
            sh """
              export LANG=zh_CN.UTF-8
              export LC_ALL=zh_CN.UTF-8
              export http_proxy="http://IAmIPaddress:31687/"
              export HTTP_PROXY="http://IAmIPaddress:31687/"
              export https_proxy="http://IAmIPaddress:31687/"
              export HTTPS_PROXY="http://IAmIPaddress:31687/"
              export no_proxy="*.betack.com,*.ustc.edu.cn,localhost,IAmIPaddress"
              export NO_PROXY="*.betack.com,*.ustc.edu.cn,localhost,IAmIPaddress"
              yarn run build
            """
          } catch (Exception err) {
            echo err.getMessage()
            echo err.toString()
            unstable '构建失败'
          }
        }
      }
    }

    stage('构建镜像并上传到harbor仓库') {
      steps {
        container('podman') {
          script {
            echo "创建Dockerfile"
            // 这里后续，可能会因为打包命令不同，复制打包后的代码文件路径也不同
            DIST_DIR = 'build'
            /*
            switch ("${params.DEPLOY_SVC_NAME}") {
            case 'fund-investment-advisor-ui':
              DIST_DIR = 'dist'
              break
            default:
              DIST_DIR = 'build'
              break
            }
            */
            
            sh """
              [ -d temp_docker_build_dir ] || mkdir temp_docker_build_dir
              cp -a ${DIST_DIR} temp_docker_build_dir/
            """
            dir("${env.WORKSPACE}/temp_docker_build_dir") {
              docker_file = """
                FROM ${office_registry}/libs/nginx:1.21.1
                LABEL maintainer="colin" version="1.0" datetime="2022-04-26"
                RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
                    echo "Asia/Shanghai" > /etc/timezone
                COPY ${DIST_DIR} /var/www/html/
                RUN mkdir /var/www/chkstatus && echo "this is test, one!" > /var/www/chkstatus/tt.txt
                EXPOSE 80
                WORKDIR /var/www
              """.stripIndent()

              writeFile file: 'Dockerfile', text: "${docker_file}", encoding: 'UTF-8'
              sh '''
                pwd; ls -lh
                cat Dockerfile
              '''

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
                rm Dockerfile ${DIST_DIR} -rf
                podman image rm ${image_name}
                podman image rm ${office_registry}/${project}/${params.DEPLOY_SVC_NAME}:latest
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

          def _EXTRANET_HARBOR = Boolean.valueOf("${params.EXTRANET_HARBOR}")
          // 在构建结尾出，输出华为云harbor仓库镜像地址
          if (_EXTRANET_HARBOR && params.DEPLOY_TO_ENV == '') {
            println("华为云或外部网络可用的镜像为：" + env.EXTRANET_IMAGE_NAME)
          }

          // 循环处理需要部署的服务
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            // 循环处理需要部署的命名空间
            for (namespaces in params.DEPLOY_TO_ENV.tokenize(',')) {
              echo "用HELM部署服务: ${deploy_svc_name}到命名空间：${namespaces}"
              println(namespaces)
              helm_values_file="temp_jenkins_workspace/rab-pf/rab-pf-frontend/deployToK8s/${deploy_svc_name}-values.yaml"
              sh """
                pwd
                ls -lh ${helm_values_file}
                cp ${helm_values_file} .
                ls -lh
              """
              configFileProvider([configFile(fileId: "fd4efaf3-23f9-4f31-a085-3e3baa9618d4", targetLocation: "kube-config.yaml")]){
                tempEvn=namespaces.replaceAll("${project}-","")
                // 获取 rab-svc-api-app api svc 的 NodePort 暴露出来的端口
                try {
                  env.API_NODEPORT = sh (script: """
                    kubectl --kubeconfig=kube-config.yaml -n ${namespaces} get svc rab-svc-api-app-out-${tempEvn} -o jsonpath='{.spec.ports[?(@.port==8080)].nodePort}'
                    """, returnStdout: true).trim()
                  println("rab-svc-api-app NodePort：" + env.API_NODEPORT)
                } catch(Exception err) {
                  env.API_NODEPORT = ''
                  error "未获取 rab-svc-api-app service 的 nodePort 端口，终止本次部署。"
                }

                chart_version = '0.1.1'
                image_tag = "${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                doDeployToK8s(namespaces, deploy_svc_name, image_tag, project, chart_version)

                // 在构建结尾出，输出华为云harbor仓库镜像地址
                if (_EXTRANET_HARBOR) {
                  extranet_image_name = "${hwcloud_registry}/${project}/${deploy_svc_name}:${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                  println("华为云或外部网络可用的镜像为：" + extranet_image_name)
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
  CONFIG_ENV = sh (script: """echo ${deployToEnv} | awk -F'-' '{print \$(NF-1)"-"\$NF}'""", returnStdout: true).trim()
  println("CONFIG_ENV：" + CONFIG_ENV)
  sh """
    sed -i 's#IMAGE_TAG#${imageTag}#' ${deploySVCName}-values.yaml
    sed -i 's#CONFIG_ENV#${CONFIG_ENV}#' ${deploySVCName}-values.yaml
    sed -i 's#NAMESPACEPREFIX#${project}#' ${deploySVCName}-values.yaml
    sed -i 's#API_NODEPORT#${API_NODEPORT}#' ${deploySVCName}-values.yaml
  """

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
  // 但前端服务，都是复用的 rab-charts 中的 rab-webapp
  println("**私募产品线 rab-pf 的 charts 复用 igw 项目的 charts")
  sh """
    export HELM_EXPERIMENTAL_OCI=1
    helm pull oci://harbor.betack.com/rab-charts/rab-webapp --version ${chartVersion}
    helm --kubeconfig kube-config.yaml ${deployType} ${deploySVCName} -n ${deployToEnv} -f ${deploySVCName}-values.yaml rab-webapp-${chartVersion}.tgz
  """

  echo "部署完成..."
  // 输出访问链接地址
  svcNodePort = sh (script: """kubectl -n ${deployToEnv} --kubeconfig=kube-config.yaml get \
    svc ${deploySVCName}-out-${CONFIG_ENV} -o go-template --template='{{range .spec.ports}}{{printf "%d" .nodePort}}{{end}}'""",
    returnStdout: true).trim()
  echo "${deploySVCName}，访问地址：http://IAmIPaddress:${svcNodePort}"
}
