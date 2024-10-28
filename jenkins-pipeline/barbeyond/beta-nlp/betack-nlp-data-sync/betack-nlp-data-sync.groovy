#!groovy
/* 导入Jenkins共享库，默认导入main分支 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@chengdu-main-dev') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
def hwcloud_registry = "harbor.betack.com"

// 项目
def project = "rabbeyond"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/beyond/betack_nlp_data_sync.git"

// 认证
def harbor_auth = "443eb7ee-c21b-4e32-b449-e01d83171672"
// def imageDict = [:]
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
            volumeMounts:
              - name: gradles
                mountPath: /opt/gradle
          - name: rust-cargo-tools
            image: rust:1.74.1-slim-bookworm
            env:
              - name: "file.encoding"
                value: "UTF-8"
              - name: "LANG"
                value: "zh_CN.UTF-8"
              - name: "sun.jnu.encoding"
                value: "UTF-8"
            tty: true
            volumeMounts:
              - name: debian12-sources
                mountPath: /etc/apt/sources.list
                subPath: sources.list
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
            - name: debian12-sources
              configMap:
                name: debian12-sources-list
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
    timeout(time: 60, unit:'MINUTES') // 设置此次发版运行60分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', numToKeepStr: '30')  // 设置保留30次构建记录
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
                 description: '若需要部署到公网或外传镜像; 勾选此选项后，将会把镜像同步推送一份到华为云上自建的HARBOR镜像仓库: harbor.betack.com',
                 name: 'EXTRANET_HARBOR'
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'betack-nlp-data-sync',
                   visibleItemCount: 8
    extendedChoice defaultValue: 'rab_server',
                   description: '请选择服务的启动命令',
                   descriptionPropertyValue: '',
                   multiSelectDelimiter: ',',
                   name: 'SVC_START_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_SINGLE_SELECT',
                   value: 'betack_nlp_data_sync',
                   visibleItemCount: 5
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'betanlp-demo-1',
                   visibleItemCount: 8
    booleanParam defaultValue: false,
                 description: '默认启用，将备份values.yaml存储到minio，且部署时优先使用该备份; 若需要用模板重新生成时，请取消勾选。',
                 name: 'IS_ENABLED_BAK_VALUES_YAML'
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
      steps {
        container('rust-cargo-tools') {
          // 环境准备
          selectCargoBuildEnvA()
          // 编译构建
          echo '正在构建...'
          script {
            for (deploySVCName in params.DEPLOY_SVC_NAME.tokenize(',')) {
              echo "当前正在构建服务：${deploySVCName}"
              rustCargoCodeCompileA(brinSVCName:params.SVC_START_NAME)
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
              dockerFile = """
                FROM ${office_registry}/rabbeyond/betack-nlp-data-sync-baseimage:ubuntu-22.04
                LABEL maintainer="colin" version="1.0" datetime="2024-04-23"
                RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
                    echo "Asia/Shanghai" > /etc/timezone
                WORKDIR /opt/betack
                COPY ${params.SVC_START_NAME} /opt/betack/${params.SVC_START_NAME}
              """.stripIndent()
              rustCargoCodeBuildContainerImage(dockerFile: dockerFile,
                                               brinSVCName: params.SVC_START_NAME,
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
            helm_values_file="temp_jenkins_workspace/${project}/beta-nlp/betack-nlp-data-sync/deployToK8s/${deploy_svc_name}-values.yaml"
            // 循环处理需要部署的命名空间
            for (namespaces in params.DEPLOY_TO_ENV.tokenize(',')) {
              // chengdu office k8s
              K8S_AUTH = 'fd4efaf3-23f9-4f31-a085-3e3baa9618d4'
              isFirst = checkHelmValuesFilesOnMinio(isEnabled: params.IS_ENABLED_BAK_VALUES_YAML,
                                                    k8sKey: K8S_AUTH,
                                                    namespaces: namespaces,
                                                    deploySVCName: deploy_svc_name,
                                                    helmValuesFilePath: helm_values_file)
              svcYaml = readYaml file: "${deploy_svc_name}-values.yaml"
              image_tag = "${COMMIT_SHORT_ID}-${BUILD_NUMBER}"
              svcYaml['image']['imgHarbor'] = office_registry
              svcYaml['image']['tag'] = image_tag
              svcYaml['image']['imgNameOrSvcName'] = deploy_svc_name
              // [new-rab, staging-1, staging1]
              // [rab, staging-1, staging1]
              config_env = libTools.splitNamespaces(namespaces)
              configEnvPrefix = config_env[0]
              configEnvSuffix = config_env[1]
              configENV = config_env[2]
              println("CONFIG_ENV：" + configEnvSuffix)
              println("项目简称，用于命名空间的前缀：" + configEnvPrefix)
              svcYaml['namespacePrefix'] = configEnvPrefix
              svcYaml['imagePullSecrets'] = [[name:'harbor-inner'], [name:'harbor-outer']]
              if (isFirst) {
                // 只有当minio中无该helm value yaml文件时，才会去设置这些值
                // 所以，后续可以直接修改minio中的yaml文件，其中不是每次都定义的部分
                svcYaml['nameOverride'] = deploy_svc_name
                svcYaml['image']['harborProject'] = project
              }
              svcYaml['service']['isOut'] = true
              svcYaml['service']['ports'] = [9961]
              writeYaml file: "${deploy_svc_name}-values.yaml", data: svcYaml, overwrite: true              
              // 设置 helm values.yaml
              sh """
                sed -i 's#SVCSTARTNAME#${params.SVC_START_NAME}#' ${deploy_svc_name}-values.yaml
              """
              // 备份 helm value yaml 文件
              sh "cat ${deploy_svc_name}-values.yaml"
              minioFile(doType: 'upload', fileNamePath: "${deploy_svc_name}-values.yaml", namespace: "${namespaces}")
              configFileProvider([configFile(fileId: "${K8S_AUTH}", targetLocation: "kube-config.yaml")]){
                doDeployToK8s(namespaces, deploy_svc_name, image_tag, '0.1.1')
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
                              chartName:'bf-rust-cargo-project-deploy-common',
                              chartVersion:chartVersion)
}