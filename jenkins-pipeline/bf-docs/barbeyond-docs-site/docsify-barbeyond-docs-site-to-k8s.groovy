#!groovy
/* 导入Jenkins共享库 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
def shanghai_registry = "harbor.betack.com"
def shanghai_registry_inner = "IAmIPaddress"

// 文档框架官网，https://docsify.js.org/
// 项目，以后所有文档都归于bf-docs下
def project = "bf-docs"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/betack/rabBeyond-Document.git"
// 认证
// def secret_name = "bf-harbor"
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
    timeout(time: 60, unit:'MINUTES') // 设置此次发版运行60分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', numToKeepStr: '30')  // 设置保留30天的构建历史，或者30次的构建记录
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
    booleanParam defaultValue: true,
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
                   value: 'bf-docs-prod-1',
                   visibleItemCount: 4
  }

  stages {
    stage('拉取代码') {
      when {
        expression {
          return (params.DEPLOY_TO_ENV != '')
        }
      }
      steps {
        container('jnlp') {
          echo '正在拉取代码...'
          script {
            checkWhetherToContinue()
            env.COMMIT_SHORT_ID = gitCheckout(git_address, params.BRANCH_TAG, true)
            println(env.COMMIT_SHORT_ID)
            // 拉取部署需要的配置文件
            echo '正在从gitlab拉取更新部署用的nginx配置文件...'
            // 创建拉取jenkins devops代码用的临时目录
            sh '[ -d temp_jenkins_workspace ] || mkdir temp_jenkins_workspace'
            dir("${env.WORKSPACE}/temp_jenkins_workspace") {
              gitCheckout("ssh://git@code.betack.com:4022/devops/jenkins.git", "chengdu-main")
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
                LABEL maintainer="colin" version="1.0" datetime="2023-05-17"
                RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
                    echo "Asia/Shanghai" > /etc/timezone
                COPY docs /var/www/html/
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
                  image_name = "${office_registry}/${project}/${params.DEPLOY_SVC_NAME}:${COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                  imageDict.put('imageName', image_name)
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
                    extranet_image_name = "${shanghai_registry}/${project}/${params.DEPLOY_SVC_NAME}:${COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                    imagePull(imageSRCHarbor: office_registry,
                              imageDSTHarbor: shanghai_registry,
                              project: project,
                              deploySVCName: params.DEPLOY_SVC_NAME,
                              imageTag:"${COMMIT_SHORT_ID}-${BUILD_NUMBER}")
                    imageDict.put('extranetImageName', extranet_image_name)
                  }
                }

                // 镜像打包后，清理垃圾文件与目录，清理构建环境的镜像节约磁盘空间
                sh """
                  #podman image rm ${image_name}
                  podman image rm ${office_registry}/${project}/${params.DEPLOY_SVC_NAME}:latest
                """
                
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
                helm_values_file="temp_jenkins_workspace/bf-docs/rabbeyond-docs-site/deployToK8s/${deploy_svc_name}-values.yaml"
                // 循环处理需要部署的命名空间
                for (namespaces in params.DEPLOY_TO_ENV.tokenize(',')) {
                  // 上海idc prod k8s集群
                  K8S_AUTH = 'fccafb7c-8128-4a91-87b2-3b7cb6940343'
                  NODE_IPADDRS = 'IAmIPaddress'
                  isFirst = checkHelmValuesFilesOnMinio(k8sKey: K8S_AUTH,
                                                        namespaces: namespaces,
                                                        deploySVCName: deploy_svc_name,
                                                        helmValuesFilePath: helm_values_file)
                  svcYaml = readYaml file: "${deploy_svc_name}-values.yaml"
                  image_tag = "${COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                  svcYaml['image']['imgHarbor'] = shanghai_registry_inner
                  svcYaml['image']['tag'] = image_tag
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
                  // 备份 helm value yaml 文件
                  sh "cat ${deploy_svc_name}-values.yaml"
                  minioFile(doType: 'upload', fileNamePath: "${deploy_svc_name}-values.yaml", namespace: "${namespaces}")
                  configFileProvider([configFile(fileId: "${K8S_AUTH}", targetLocation: "kube-config.yaml")]){
                    chart_version = '0.1.3'
                    doDeployToK8s(namespaces, deploy_svc_name, image_tag, chart_version)
                  }
                }
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
        tempImageMap = [:]
        tempImageMap.put(params.DEPLOY_SVC_NAME, imageDict)
        // 在构建结尾处，输出华为云harbor仓库镜像地址
        libTools.printImageList(tempImageMap)
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
