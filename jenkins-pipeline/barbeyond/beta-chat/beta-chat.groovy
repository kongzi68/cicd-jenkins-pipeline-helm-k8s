#!groovy
/* 导入Jenkins共享库，默认导入main分支 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
def hwcloud_registry = "harbor.betack.com"

// 项目，betaChat
def project = "rabbeyond"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/beyond/betachat.git"

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
    timeout(time: 90, unit:'MINUTES') // 设置此次发版运行20分钟后超时
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
    booleanParam defaultValue: false,
                 description: '勾选，将增加启动运行模型训练命令',
                 name: 'ADD_MODEL_TRAIN_COMMAND'
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'beta-chat',
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

    stage('源代码导出') {
      steps {
        echo '正在导出不含.git目录的源代码...'
        script {
          echo "当前正在构建服务：${params.DEPLOY_SVC_NAME}"
          sh """
            git archive --format=tar HEAD > betachat.tar
            ls -lh betachat.tar
          """
        }
      }
    }

    stage('构建镜像上传HARBOR仓库') {
      steps {
        container('podman') {
          script{
            for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
              echo "创建Dockerfile"
              dockerFile = """
                FROM ${office_registry}/rabbeyond/beta-chat-baseimage:v20231009
                LABEL maintainer="colin" version="1.0" datetime="2023-09-13"
                ENV PYTHONUNBUFFERED=1 \
                    PYTHONDONTWRITEBYTECODE=1 \
                    PIP_NO_CACHE_DIR=off \
                    PIP_DISABLE_PIP_VERSION_CHECK=on \
                    PIP_DEFAULT_TIMEOUT=100 \
                    POETRY_VIRTUALENVS_IN_PROJECT=true \
                    POETRY_NO_INTERACTION=1 \
                    PYSETUP_PATH="/opt/betack/betachat" \
                    VENV_PATH="/opt/betack/betachat/.venv"
                ENV PATH="\$VENV_PATH/bin:\$PATH"
                RUN pip install poetry==1.6.1
                COPY pyproject.toml /opt/betack/betachat/
                COPY poetry.lock /opt/betack/betachat/
                WORKDIR /opt/betack/betachat
                RUN poetry install --no-iamusername --only main
                ADD betachat.tar /opt/betack/betachat/
              """.stripIndent()
              env.EXTRANET_IMAGE_NAME = pythonCodeBuildContainerImage(dockerFile:dockerFile,
                                                                      projectCodePKG:'betachat.tar pyproject.toml poetry.lock',
                                                                      project:project,
                                                                      deploySVCName:params.DEPLOY_SVC_NAME)
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
              helm_values_file="temp_jenkins_workspace/${project}/beta-chat/deployToK8s/${deploy_svc_name}-values.yaml"
              copyHelmValuesFile(namespaces:namespaces,
                                 helmValuesFile:helm_values_file,
                                 deploySVCName:deploy_svc_name)
              // 模型增加启动模型训练命令
              def _ADD_MODEL_TRAIN_COMMAND = Boolean.valueOf("${params.ADD_MODEL_TRAIN_COMMAND}")
              if (_ADD_MODEL_TRAIN_COMMAND) {
                sh """
                  sed -i 's#ADD_MODEL_TRAIN_COMMAND#poetry run rasa train; #' ${deploy_svc_name}-values.yaml
                """
              } else {
                sh """
                  sed -i 's#ADD_MODEL_TRAIN_COMMAND##' ${deploy_svc_name}-values.yaml
                """
              }
              // chengdu office k8s
              K8S_AUTH = 'fd4efaf3-23f9-4f31-a085-3e3baa9618d4'
              configFileProvider([configFile(fileId: "${K8S_AUTH}", targetLocation: "kube-config.yaml")]){
                image_tag = "${COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                doDeployToK8s(namespaces, deploy_svc_name, image_tag, '0.1.2')
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
