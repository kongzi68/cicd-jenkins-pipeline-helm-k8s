#!groovy
/* 导入Jenkins共享库，默认导入main分支 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
def hwcloud_registry = "harbor.betack.com"

// 项目，betaChat
def project = "rabbeyond"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/beyond/chat_action_server.git"

// 认证
def harbor_auth = "443eb7ee-c21b-4e32-b449-e01d83171672"
def git_auth = "41dfa7f4-d5f9-4892-a8ba-6b0d31a7cd84"
// def imageDict = [:]

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
          - name: package-tools
            image: "${office_registry}/rabbeyond/betachat-action-server-baseimage:rust1.72.1-python3.10.14"
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
    // retry(2)                          // 重试次数
    disableConcurrentBuilds()         // java项目禁止并发构建：主要是gradle有锁，导致无法并发构建
    timestamps()                      // 添加时间戳
    timeout(time: 60, unit:'MINUTES') // 设置此次发版运行20分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', numToKeepStr: '30')  // 设置保留30次构建记录
  }

  parameters {
    gitParameter branch: '',
                 branchFilter: '.*',
                 defaultValue: 'main',
                 listSize: '5',
                 description: '选择需要发布的代码分支',
                 name: 'BRANCH_TAG',
                 quickFilterEnabled: true,
                 selectedValue: 'NONE',
                 sortMode: 'NONE',
                 tagFilter: '*',
                 type: 'PT_BRANCH',
                 useRepository: "${git_address}"
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'beta-chat-etf-screen-action-server1',
                   visibleItemCount: 8
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'betanlp-demo-1',
                   visibleItemCount: 8
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

    stage('代码编译打包') {
      /*
      environment {
        def http_proxy="http://IAmIPaddress:31687/"
        def https_proxy="http://IAmIPaddress:31687/"
        // def ftp_proxy="http://xxx.xxx.xxx:xxx/"
        def no_proxy=".betack.com,.ustc.edu.cn,localhost,IAmIPaddress"
      }
      */
      steps {
        container('package-tools') {
          echo '当前步骤为：代码编译环境准备与打包'
          script {
            echo "rust 国内依赖仓库配置"
            selectCargoBuildEnvNew()
            echo "当前正在构建服务：${params.DEPLOY_SVC_NAME}"
            withCredentials([usernameColonPassword(credentialsId: '3d040389-9dfe-4c0d-9dab-9f6487f10409', variable: 'USERPASS')]) {
              sh """
                pwd; ls -lha
                #echo "IAmIPaddress  code.betack.com" >> /etc/hosts
                [ -d ${WORKSPACE}/.cargo ] || mkdir ${WORKSPACE}/.cargo
                cp -a $HOME/.cargo/config ${WORKSPACE}/.cargo/config
                export CARGO_HOME=${WORKSPACE}/.cargo
                cargo --version
                pyenv virtualenv 3.10.14 betachat-python3.10.14
                poetry env use /iamusername/.pyenv/versions/3.10.14/envs/betachat-python3.10.14/bin/python
                export POETRY_CONFIG_DIR=${WORKSPACE}/.config/pypoetry
                export POETRY_DATA_DIR=${WORKSPACE}/.local/share/pypoetry
                export POETRY_CACHE_DIR=${WORKSPACE}/.config/pypoetry
                poetry lock --no-update
                poetry install --no-iamusername
                echo 'https://${USERPASS}@code.betack.com' > ${WORKSPACE}/git-credentials
                git config --global credential.helper 'store --file ${WORKSPACE}/git-credentials'
                poetry run poe build
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
            for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
              echo "创建Dockerfile"
              // ${office_registry}/rabbeyond/beta-chat-baseimage:v-python3.10.14
              dockerFile = """
                FROM ${office_registry}/rabbeyond/beta-chat-etf-screen-action-server1:latest
                LABEL maintainer="colin" version="1.0" datetime="2024-07-17"
                ADD *.whl /opt/betack/
                WORKDIR /opt/betack/
                RUN pip uninstall -y chat-action-server
                RUN pip install *.whl
              """.stripIndent()
              pythonCodeBuildContainerImage(dockerFile: dockerFile,
                                            projectCodePKG: 'rust/target/wheels/*.whl',
                                            project: project,
                                            deploySVCName: params.DEPLOY_SVC_NAME)
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
            gitCheckout('ssh://git@code.betack.com:4022/devops/jenkins.git', 'chengdu-main')
            sh 'pwd; ls -lh'
          }
          // helm登录harbor仓库
          loginHelmChartRegistry()
          // 循环处理需要部署的服务
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            // 循环处理需要部署的命名空间
            for (namespaces in params.DEPLOY_TO_ENV.tokenize(',')) {
              helm_values_file="temp_jenkins_workspace/${project}/beta-chat-etf-screen-action-server1/deployToK8s/${deploy_svc_name}-values.yaml"
              copyHelmValuesFile(namespaces:namespaces,
                                 helmValuesFile:helm_values_file,
                                 deploySVCName:deploy_svc_name)
              // chengdu office k8s
              K8S_AUTH = 'fd4efaf3-23f9-4f31-a085-3e3baa9618d4'
              configFileProvider([configFile(fileId: "${K8S_AUTH}", targetLocation: "kube-config.yaml")]){
                image_tag = "${COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                doDeployToK8s(namespaces, deploy_svc_name, image_tag, '0.1.3')
              }
            }
          }
        }
      }
    }
  }
}


def doDeployToK8s(deployToEnv, deploySVCName, imageTag, chartVersion) {
  // helm 部署服务
  deployWithHelmToK8sByDeployOld(deploySVCName:deploySVCName,
                              imageTag:imageTag,
                              deployToEnv:deployToEnv,
                              chartName:'bf-python-project-deploy-common',
                              chartVersion:chartVersion)
}
