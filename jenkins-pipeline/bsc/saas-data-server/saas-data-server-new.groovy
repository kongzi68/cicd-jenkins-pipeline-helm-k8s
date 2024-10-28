#!groovy
/* 导入Jenkins共享库，默认导入main分支 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
def hwcloud_registry = "harbor.betack.com"

// 数据通用API **数据管理-更新-发布 项目数据对接
// 项目
def project = "bf-bsc"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/betack/saas-data.git"

// 认证
def harbor_auth = "443eb7ee-c21b-4e32-b449-e01d83171672"
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
    timeout(time: 60, unit:'MINUTES') // 设置此次发版运行20分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', numToKeepStr: '30')  // 设置保留30次构建记录
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
    extendedChoice defaultValue: '17',
                   description: '请选择 JDK 版本',
                   multiSelectDelimiter: ',',
                   name: 'JDK_VERSION',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_SINGLE_SELECT',
                   value: '8,11,17',
                   visibleItemCount: 5
    extendedChoice defaultValue: 'gradle-8.2.1',
                   description: '请选择 gradle 版本',
                   multiSelectDelimiter: ',',
                   name: 'GRADLE_VERSION',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_SINGLE_SELECT',
                   value: 'gradle-6.8.3,gradle-7.4.2,gradle-8.2.1',
                   visibleItemCount: 5
    booleanParam defaultValue: false,
                 description: '勾选此选项后，执行命令：gradle clean，清理构建环境。',
                 name: 'CLEAN_BUILD'
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将会把镜像同步推送一份到华为云上自建的HARBOR镜像仓库: harbor.betack.com',
                 name: 'EXTRANET_HARBOR'
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'saas-data-server,data-subscriber-client,etl-listener,data-migration-job,data-meta-import-job',
                   visibleItemCount: 8
    string description: 'data-meta-import-job 参数。多个参数用空格分隔，【注意】每次都会默认清空上一次变量参数，如果要保持上一次变量参数，请重新填写',
           name: 'DATA_META_IMPORT_JOB_PARAMETER',
           trim: true
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'saasdata-dev-1,saasdata-dev-2,saasdata-staging-1',
                   visibleItemCount: 7
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将把 Arthas Java 诊断工具打包到镜像中',
                 name: 'ARTHAS_TOOLS'
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将开启jmxremote远程调试功能',
                 name: 'JMXREMOTE'
    booleanParam defaultValue: true,
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
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            echo "当前正在构建服务：${deploy_svc_name}"
            switch(deploy_svc_name) {
              case 'saas-data-server':
                deploy_svc_name = 'data-etl-server'
              break
            }
            JAR_PKG_NAME = "${deploy_svc_name}/build/libs/${deploy_svc_name}-1.0-SNAPSHOT.jar"
            javaCodeCompile(deploy_svc_name)
            echo "构建的jar包 ${deploy_svc_name} 信息如下："
            sh "ls -lh ${JAR_PKG_NAME}; pwd"
          }
        }
      }
    }

    stage('构建镜像上传HARBOR仓库') {
      steps {
        container('podman') {
          script{
            for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
              switch(deploy_svc_name) {
                case 'saas-data-server':
                  deploy_svc_name = 'data-etl-server'
                break
                /*
                case 'etl-listener':
                  JDKVERSION = "${office_registry}/libs/dragonwell:11-sshpass"
                break
                */
              }
              TEMP_JAR_NAME = "${deploy_svc_name}-1.0-SNAPSHOT.jar"
              JAR_PKG_NAME = "${deploy_svc_name}/build/libs/${TEMP_JAR_NAME}"
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
    }

    // harbor.betack.com/rabbeyond/sdk-grpc-server:36-1.6.11-SNAPSHOT_2.12
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
            helm_values_file="temp_jenkins_workspace/bsc/saas-data-server/deployToK8s/${deploy_svc_name}-values.yaml"
            // 循环处理需要部署的命名空间
            for (namespaces in params.DEPLOY_TO_ENV.tokenize(',')) {
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
              svcYaml['nameOverride'] = deploy_svc_name
              switch(deploy_svc_name) {
                case ['saas-data-server']:
                  svcYaml['image']['imgNameOrSvcName'] = 'data-etl-server'
                break
                default:
                  svcYaml['image']['imgNameOrSvcName'] = deploy_svc_name
                break
              }
              configEnv = libTools.splitNamespaces(namespaces)
              configEnvPrefix = configEnv[0]
              configEnvSuffix = configEnv[1]
              configENV = configEnv[2]
              println("CONFIG_ENV：" + configEnvSuffix)
              println("项目简称，用于命名空间的前缀：" + configEnvPrefix)
              svcYaml['namespacePrefix'] = configEnvPrefix
              // 处理jmxremote调试端口问题
              switch(deploy_svc_name) {
                case ['saas-data-server']:
                  chart_version = '0.1.3'
                  chart_name = 'bf-java-project-distributed-statefulset'
                  k8s_rs_type = 'sts'
                  // 无论是否勾选 JMXREMOTE，都需要修改 values.yaml 文件中 JMX_REMOTE 的值为布尔值。
                  svcYaml['jmxremote']['isopen'] = Boolean.valueOf(params.JMXREMOTE)
                  svcYaml['jmxremote']['ports'] = javaJmxRemote(k8s: 'cdk8s', namespaces: namespaces, deploySVCName: deploy_svc_name)
                  svcYaml['jmxremote']['hostnameIp'] = 'IAmIPaddress'
                break
                case ['data-migration-job', 'data-meta-import-job']:
                  chart_version = '0.1.4'
                  chart_name = 'bf-java-project-job-common'
                  k8s_rs_type = 'job'
                break
                default:
                  chart_version = '0.1.10'
                  chart_name = 'bf-java-project-deploy-common'
                  k8s_rs_type = 'deploy'
                  svcYaml['jmxremote']['isopen'] = false
                  svcYaml['jmxremote']['ports'] = '30001'
                  svcYaml['jmxremote']['hostnameIp'] = 'IAmIPaddress'
                break
              }
              // 处理启动命令和参数
              switch(deploy_svc_name) {
                case ['data-migration-job', 'data-meta-import-job']:
                  svcYaml['svcJARPKGOptions'] = params.DATA_META_IMPORT_JOB_PARAMETER
                break
                default:
                  svcYaml['replicaCount'] = 1
                  svcYaml['commandOps'] = [reserveJavaCommand(k8sRSType: k8s_rs_type, namespaces: namespaces, deploySVCName: deploy_svc_name)]
                break
              }
              println("isFirst": isFirst)
              if (isFirst) {
                svcYaml['image']['harborProject'] = project
                svcYaml['imagePullSecrets'] = [[name:'harbor-inner'], [name:'harbor-outer']]
              }
              writeYaml file: "${deploy_svc_name}-values.yaml", data: svcYaml, overwrite: true
              sh "sed -i 's#CONFIG_ENV#${configENV}#' ${deploy_svc_name}-values.yaml"
              sh "cat ${deploy_svc_name}-values.yaml"
              // 备份 helm value yaml 文件
              minioFile(doType: 'upload', fileNamePath: "${deploy_svc_name}-values.yaml", namespace: namespaces)
              configFileProvider([configFile(fileId: "${K8S_AUTH}", targetLocation: "kube-config.yaml")]){
                switch(deploy_svc_name) {
                  case ['saas-data-server', 'data-subscriber-client', 'etl-listener']:
                    deployWithHelmToK8sByDeploy(deploySVCName: deploy_svc_name,
                                                imageTag: image_tag,
                                                deployToEnv: namespaces,
                                                chartName: chart_name,
                                                chartVersion: chart_version)
                  break
                  case ['data-migration-job', 'data-meta-import-job']:
                    deployWithHelmToK8sByJob(deploySVCName: deploy_svc_name,
                                            imageTag: image_tag,
                                            deployToEnv: namespaces,
                                            chartName: chart_name,
                                            chartVersion: chart_version)
                  break
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
        // 在构建结尾处，输出华为云harbor仓库镜像地址
        libTools.printImageList(imageDict)
      }
    }
  }
}