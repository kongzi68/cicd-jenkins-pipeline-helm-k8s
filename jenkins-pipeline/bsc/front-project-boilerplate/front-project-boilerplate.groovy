#!groovy
/* 导入Jenkins共享库 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
def hwcloud_registry = "harbor.betack.com"

// 项目 bsc 标件，标件的文档界面
def project = 'bf-project'     // HARBOR 镜像仓库中的项目名称
def git_address = 'ssh://git@code.betack.com:4022/betack/betack-components-lib.git'

// 认证
def harbor_auth = "443eb7ee-c21b-4e32-b449-e01d83171672"
def git_auth = "41dfa7f4-d5f9-4892-a8ba-6b0d31a7cd84"
def imageDict = [:]
// 默认定义为第一次运行服务
def isFirst = true


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
          - name: frontend
            image: "${office_registry}/libs/node:18.20.4"
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
    timeout(time: 20, unit:'MINUTES') // 设置此次发版运行20分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', numToKeepStr: '30')  // 设置保留30次构建记录
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
    choice (choices: ['front-project-boilerplate','front-project-boilerplate-doc'],
            description: '请选择本次发版需要部署的服务',
            name: 'DEPLOY_SVC_NAME')
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'bsc-staging-1',
                   visibleItemCount: 5
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
        container('frontend') {
          echo '当前 pnpm、npm、yarn 版本与配置信息如下：'
          script {
            // 可选值 pnpm、npm、yarn
            selectFrontendBuildEnv(envType:'pnpm', nodeVersion:'16.14.0', pnpmVersion:'7.8.0')
          }
        }
      }
    }

    stage('代码编译打包') {
      steps {
        container('frontend') {
          echo '正在构建...'
          script {
            // 研发说，需要根据不同的服务，打包的命令不同
            switch ("${params.DEPLOY_SVC_NAME}") {
            case 'front-project-boilerplate':
              YARN_CMD = 'build'
              break
            case 'front-project-boilerplate-doc':
              YARN_CMD = 'doc:prod'
              break
            }
            frontendCodeCompile(buildScripts:"pnpm install; npm run ${YARN_CMD}")
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
            switch ("${params.DEPLOY_SVC_NAME}") {
            case 'front-project-boilerplate':
              DIST_DIR = 'build'
              break
            case 'front-project-boilerplate-doc':
              DIST_DIR = 'docs-dist'
              break
            }
            images = frontendCodeNginxBuildContainerImage(distDir:DIST_DIR,
                                                          project:project,
                                                          deploySVCName:params.DEPLOY_SVC_NAME)
            imageDict.put(params.DEPLOY_SVC_NAME, images)
          }
        }
      }
    }

    stage('部署服务') {
      when {
        expression {
          return (params.DEPLOY_TO_ENV != '')
        }
      }
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
              K8S_AUTH = 'fd4efaf3-23f9-4f31-a085-3e3baa9618d4'
              NODE_IPADDRS = 'IAmIPaddress'
              configFileProvider([configFile(fileId: "${K8S_AUTH}", targetLocation: "kube-config.yaml")]){
                // 判断是否已经部署过
                def getDeployName = sh (script: "helm --kubeconfig kube-config.yaml -n ${namespaces} list -l name==${deploy_svc_name} -q", returnStdout: true).trim()
                println("getDeployName：" + getDeployName)
                println("deploySVCName：" + deploy_svc_name)
                if (getDeployName == deploy_svc_name) {
                  // 下载helm value yaml文件
                  // 注意：这里minio中文件不存在时，会继续构建，并用模板文件更新服务，同时会上传一份到minio
                  try {
                    minioFile(doType: 'download', fileNamePath: "${deploy_svc_name}-values.yaml", namespace: "${namespaces}")
                    isFirst = false
                  } catch(Exception err) {
                    echo err.getMessage()
                    echo err.toString()
                    isFirst = true
                  }
                } else {
                  // 用初始helm value yaml模板进行修改
                  helm_values_file="temp_jenkins_workspace/bsc/front-project-boilerplate/deployToK8s/${deploy_svc_name}-values.yaml"
                  copyHelmValuesFile(namespaces: namespaces, helmValuesFile: helm_values_file, deploySVCName: deploy_svc_name)
                  isFirst = true
                }
                svcYaml = readYaml file: "${deploy_svc_name}-values.yaml"
                image_tag = "${COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                svcYaml['image']['imgHarbor'] = office_registry
                svcYaml['image']['tag'] = image_tag
                // svcYaml['image']['tag'] = "${COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                // [new-rab, staging-1, staging1]
                // [rab, staging-1, staging1]
                config_env = libTools.splitNamespaces(namespaces)
                configEnvPrefix = config_env[0]
                configEnvSuffix = config_env[1]
                configENV = config_env[2]
                println("CONFIG_ENV：" + configEnvSuffix)
                println("项目简称，用于命名空间的前缀：" + configEnvPrefix)
                svcYaml['namespacePrefix'] = configEnvPrefix
                if (isFirst) {
                  // 只有当minio中无该helm value yaml文件时，才会去设置这些值
                  // 所以，后续可以直接修改minio中的yaml文件，其中不是每次都定义的部分
                  svcYaml['image']['harborProject'] = project
                }
                writeYaml file: "${deploy_svc_name}-values.yaml", data: svcYaml, overwrite: true
                switch (namespaces) {
                  case 'rab-dev-1':
                    API_ADDRS = 'http://IAmIPaddress:19776'
                    SOCKET_ADDRS = 'http://IAmIPaddress:19776'
                  break
                  default:
                    API_ADDRS = "http://rab-svc-api-app-in-${configEnvSuffix}:8080"
                    SOCKET_ADDRS = "http://rab-svc-api-app-in-${configEnvSuffix}:9999/socket.io"
                  break
                }
                sh """
                  sed -i 's#API_ADDRS#${API_ADDRS}#' ${deploy_svc_name}-values.yaml
                  sed -i 's#SOCKET_ADDRS#${SOCKET_ADDRS}#' ${deploy_svc_name}-values.yaml
                """
                // 备份 helm value yaml 文件
                sh "cat ${deploy_svc_name}-values.yaml"
                minioFile(doType: 'upload', fileNamePath: "${deploy_svc_name}-values.yaml", namespace: "${namespaces}")
                chart_version = '0.1.2'
                doDeployToK8s(namespaces, deploy_svc_name, image_tag, chart_version)
              }
            }
          }
        }
      }
    }
  }

  post {
    success {
      script {
        // 在构建结尾处，输出华为云harbor仓库镜像地址
        libTools.printImageList(imageDict)
      }
    }
  }
}


def doDeployToK8s(deployToEnv, deploySVCName, imageTag, chartVersion) {
  // helm 部署服务
  deployWithHelmToK8sByDeploy(deploySVCName: deploySVCName,
                              imageTag: imageTag,
                              deployToEnv: deployToEnv,
                              chartName: 'bf-frontend-nginx-deploy-common',
                              chartVersion: chartVersion)
  echo "部署完成..."
  // 输出访问链接地址
  svcNodePort = libTools.getSVCNodePort(deployToEnv, deploySVCName, 80, configEnvSuffix)
  echo "${deploySVCName}，访问地址：http://${NODE_IPADDRS}:${svcNodePort}"
}





