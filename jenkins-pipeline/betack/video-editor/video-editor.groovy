#!groovy
/* 导入Jenkins共享库 */
@Library('bf-shared-library') _

// 公共
def office_registry = "IAmIPaddress"
def hwcloud_registry = "harbor.betack.com"

// 项目
def project = "betack"  // HARrab镜像仓库中的项目名称
def git_address = 'ssh://git@code.betack.com:4022/betack/video-editor.git'
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
            image: "${office_registry}/libs/ubuntu-jenkins-agent:jdk17-nogradle"
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
            volumeMounts:
              - name: docker-cmd
                mountPath: /usr/bin/docker
              - name: docker-sock
                mountPath: /var/run/docker.sock
          dnsConfig:
            nameservers:
            - IAmIPaddress
            - IAmIPaddress
          nodeSelector:
            is-install-docker: true
          imagePullSecrets:
          - name: ${secret_name}
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
    timeout(time: 60, unit:'MINUTES') // 设置此次发版运行60分钟后超时
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
    choice (choices: ['video-editor'],
            description: '请选择本次发版需要部署的服务',
            name: 'DEPLOY_SVC_NAME')
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'bf-dev-1',
                   visibleItemCount: 12
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
        echo '当前 npm、yarn 版本与配置信息如下：'
        script {
          ENV_TYPE = 'npm'  // 可选值 npm、yarn
          selectFrontendBuildEnv(envType:ENV_TYPE, nodeVersion:'12.22.1')
        }
      }
    }

    stage('代码编译打包') {
      steps {
        echo '正在构建...'
        script {
          frontendCodeCompile(envType:ENV_TYPE)
        }
      }
    }

    stage('构建镜像并上传到harbor仓库') {
      steps {
        script {
          echo "创建Dockerfile"
          DIST_DIR = 'dist'
          env.EXTRANET_IMAGE_NAME = frontendCodeNginxBuildContainerImage(distDir:DIST_DIR,
                                                                         project:project,
                                                                         deploySVCName:params.DEPLOY_SVC_NAME)
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
            // 默认拉取jenkins的main分支
            gitCheckout('ssh://git@code.betack.com:4022/devops/jenkins.git')
            sh 'pwd; ls -lh'
          }

          // helm登录harbor仓库
          loginHelmChartRegistry()

          def _EXTRANET_HARBOR = Boolean.valueOf("${params.EXTRANET_HARBOR}")
          // 在构建结尾出，输出华为云harbor仓库镜像地址
          if (_EXTRANET_HARBOR && params.DEPLOY_TO_ENV == '') {
            println("华为云或外部网络可用的镜像为：" + env.EXTRANET_IMAGE_NAME)
          }
          
          // 循环处理需要部署的服务
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            // 循环处理需要部署的命名空间
            for (namespaces in params.DEPLOY_TO_ENV.tokenize(',')) {
              helm_values_file="temp_jenkins_workspace/betack/${deploy_svc_name}/deployToK8s/${deploy_svc_name}-values.yaml"
              copyHelmValuesFile(namespaces:namespaces,
                                 helmValuesFile:helm_values_file,
                                 deploySVCName:deploy_svc_name)

              // [new-rab, staging-1, staging1]
              // [rab, staging-1, staging1]
              config_env = libTools.splitNamespaces(namespaces)
              CONFIG_ENV_PREFIX = config_env[0]
              CONFIG_ENV_SUFFIX = config_env[1]
              CONFIGENV = config_env[2]

              switch (namespaces) {
                case ['bf-dev-1']:
                  // rke2 k8s
                  K8S_AUTH = '1a4581df-f0e0-4b5f-9009-ec36d749aedd'
                  NODE_IPADDRS = 'IAmIPaddress'
                  break
                default:
                  // 默认 153 k8s
                  K8S_AUTH = '235f0282-bcf4-49ee-97ad-7caeb98e5266'
                  NODE_IPADDRS = 'IAmIPaddress'
                break
              }

              configFileProvider([configFile(fileId: K8S_AUTH, targetLocation: "kube-config.yaml")]){
                chart_version = '0.1.2'
                image_tag = "${COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                doDeployToK8s(namespaces, deploy_svc_name, image_tag, chart_version)

                // 在构建结尾出，输出华为云harbor仓库镜像地址
                if (_EXTRANET_HARBOR) {
                  extranet_image_name = "${hwcloud_registry}/${project}/${deploy_svc_name}:${COMMIT_SHORT_ID}-${BUILD_NUMBER}"
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


def doDeployToK8s(deployToEnv, deploySVCName, imageTag, chartVersion) {
  // helm 部署服务
  deployWithHelmToK8sByDeploy(deploySVCName:deploySVCName,
                              imageTag:imageTag,
                              deployToEnv:deployToEnv,
                              chartName:'bf-frontend-nginx-deploy-common',
                              chartVersion:chartVersion)
  echo "部署完成..."
  // 输出访问链接地址
  svcNodePort = libTools.getSVCNodePort(deployToEnv, deploySVCName, 80, CONFIG_ENV_SUFFIX)
  echo "${deploySVCName}，访问地址：http://${NODE_IPADDRS}:${svcNodePort}"
}