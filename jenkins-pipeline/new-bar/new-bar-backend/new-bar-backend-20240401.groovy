#!groovy
/* 导入Jenkins共享库 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
def shanghai_inner_registry = "IAmIPaddress"
def shanghai_outer_registry = "harbor.betack.com"

// 项目
def project = "rab"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/betack/rab-backend.git"

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
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', numToKeepStr: '30')  // 设置保留30次的构建记录
  }

  parameters {
    gitParameter branch: '',
                 branchFilter: '.*',
                 defaultValue: 'master',
                 listSize: '10',
                 description: '选择需要发布的代码分支',
                 name: 'BRANCH_TAG',
                 quickFilterEnabled: true,
                 selectedValue: 'NONE',
                 sortMode: 'NONE',
                 tagFilter: '*',
                 type: 'PT_BRANCH',
                 useRepository: "${git_address}"
    extendedChoice defaultValue: '11',
                   description: '请选择 JDK 版本',
                   multiSelectDelimiter: ',',
                   name: 'JDK_VERSION',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_SINGLE_SELECT',
                   value: '8,11,17',
                   visibleItemCount: 4
    extendedChoice defaultValue: 'gradle-8.2.1',
                   description: '请选择 gradle 版本',
                   multiSelectDelimiter: ',',
                   name: 'GRADLE_VERSION',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_SINGLE_SELECT',
                   value: 'gradle-6.8.3,gradle-7.4.2,gradle-8.2.1',
                   visibleItemCount: 3
    booleanParam defaultValue: false,
                 description: '勾选此选项后，执行命令：gradle clean，清理构建环境。',
                 name: 'CLEAN_BUILD'
    booleanParam defaultValue: false,
                 description: '若需要部署到公网或外传镜像; 勾选此选项后，将会把镜像同步推送一份到华为云上自建的HARBOR镜像仓库: harbor.betack.com',
                 name: 'EXTRANET_HARBOR'
    extendedChoice defaultValue: '1',
                   description: '当分布式部署 API 服务时，需要选择启动的 API node 数量; 默认值: 1',
                   multiSelectDelimiter: ',',
                   name: 'REPLICA_COUNT',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_SINGLE_SELECT',
                   value: '1,3,5,7,11',
                   visibleItemCount: 5
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'rab-svc-api-app,rab-svc-api-app-master,rab-svc-offline-app,rab-svc-data-sync,rab-task-data-migration',
                   visibleItemCount: 6
    string description: 'rab-task-data-migration的参数，多个参数用空格分隔，【注意】每次都会默认清空上一次变量参数，如果要保持上一次变量参数，请重新填写',
           name: 'rab_TASK_DATA_MIGRATION_PARAMETER',
           trim: true
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'new-rab-dev-1,new-rab-dev-2,new-rab-staging-1,new-rab-staging-3,new-rab-dev-3,new-rab-dev-4,new-rab-staging-4',
                   visibleItemCount: 10
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将把 Arthas Java 诊断工具打包到镜像中',
                 name: 'ARTHAS_TOOLS'
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将开启 jmxremote 远程调试功能',
                 name: 'JMXREMOTE'
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
          // 按服务打包时去重
          def _DEPLOY_SVC_NAME = libTools.delDeploySVCName(['rab-svc-api-app', 'rab-svc-api-app-master', 'rab-svc-offline-app'], 'rab-svc-api-app')
          println(_DEPLOY_SVC_NAME)
          for (deploySVCName in _DEPLOY_SVC_NAME) {
            echo "当前正在构建服务：${deploySVCName}"
            JAR_PKG_NAME = "${deploySVCName}/build/libs/${deploySVCName}-1.1.0-SNAPSHOT.jar"
            javaCodeCompile(deploySVCName)
            echo "构建的jar包 ${deploySVCName} 信息如下："
            sh "ls -lh ${JAR_PKG_NAME}; pwd"
          }
        }
      }
    }

    stage('构建镜像上传成都HARBOR') {
      steps {
        container('podman') {
          script{
            for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
              switch(deploy_svc_name) {
                case ['rab-svc-offline-app','rab-svc-api-app-master']:
                  TEMP_JAR_NAME = "rab-svc-api-app-1.1.0-SNAPSHOT.jar"
                  JAR_PKG_NAME = "rab-svc-api-app/build/libs/${TEMP_JAR_NAME}"
                break
                default:
                  TEMP_JAR_NAME = "${deploy_svc_name}-1.1.0-SNAPSHOT.jar"
                  JAR_PKG_NAME = "${deploy_svc_name}/build/libs/${TEMP_JAR_NAME}"
                break
              }
              // DEPLOY_JAR_PKG_NAME = "${deploy_svc_name}-${COMMIT_SHORT_ID}-${BUILD_NUMBER}.jar"
              // DEPLOY_JAR_PKG_NAME = "${deploy_svc_name}.jar"
              images = javaCodeBuildContainerImageToMinio(jarPKGName: JAR_PKG_NAME,
                                                          tempJarName: TEMP_JAR_NAME,
                                                          deploySVCName: deploy_svc_name,
                                                          jdkVersion: JDKVERSION,
                                                          project: project,
                                                          deployJarPKGName: "${deploy_svc_name}.jar")
              imageDict.put(deploy_svc_name, images)
            }
          }
        }
      }
    }

    stage('上传镜像到上海HARBOR') {
      agent {
        kubernetes {
          cloud "shanghai-idc-k8s"  // 选择名字是 shanghai-idc-k8s 的cloud
          defaultContainer 'jnlp'
          showRawYaml "false"
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
                image: "${shanghai_inner_registry}/libs/ubuntu-jenkins-agent:latest-nofrontend"
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
              - name: podman
                image: "${shanghai_inner_registry}/libs/podman/stable:v3.4.2"
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

      when {
        expression {
          return (Boolean.valueOf("${params.EXTRANET_HARBOR}"))
        }
      }

      steps {
        container('podman') {
          script{
            for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
              images = buildContainerImageToProdHarbor(deploySVCName: deploy_svc_name,
                                                      project: project,
                                                      deployJarPKGName: "${deploy_svc_name}.jar")
            }
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
            helm_values_file="temp_jenkins_workspace/new-rab/new-rab-backend/deployToK8s/${deploy_svc_name}-values.yaml"
            // 循环处理需要部署的命名空间
            for (namespaces in params.DEPLOY_TO_ENV.tokenize(',')) {
              K8S_AUTH = 'fd4efaf3-23f9-4f31-a085-3e3baa9618d4'
              isFirst = checkHelmValuesFilesOnMinio(k8sKey: K8S_AUTH,
                                                    namespaces: namespaces,
                                                    deploySVCName: deploy_svc_name,
                                                    helmValuesFilePath: helm_values_file)
              svcYaml = readYaml file: "${deploy_svc_name}-values.yaml"
              image_tag = "${COMMIT_SHORT_ID}-${BUILD_NUMBER}"
              svcYaml['image']['imgHarbor'] = office_registry
              svcYaml['image']['tag'] = image_tag
              configEnv = libTools.splitNamespaces(namespaces)
              configEnvPrefix = configEnv[0]
              configEnvSuffix = configEnv[1]
              configENV = configEnv[2]
              println("CONFIG_ENV：" + configEnvSuffix)
              println("项目简称，用于命名空间的前缀：" + configEnvPrefix)
              svcYaml['namespacePrefix'] = configEnvPrefix
              // dev4环境启用akka集群
              if(namespaces == 'new-rab-dev-4') {
                switch(deploy_svc_name) {
                  case ['rab-svc-api-app']:
                    svcYaml['akka']['enabled'] = false
                    // 默认不创建Role，一个命名空间下，保持一个服务可创建，其它都需要设置为false
                    svcYaml['akka']['akkaIsCreateRole'] = false
                  break
                  case ['rab-svc-offline-app']:
                    svcYaml['akka']['enabled'] = false
                    svcYaml['akka']['akkaIsCreateRole'] = false
                  break
                }
                // 非第一次部署的时候，从k8s集群获取akka集群配置文件
                if(!isFirst) {
                  switch(deploy_svc_name) {
                    case ['rab-svc-api-app', 'rab-svc-offline-app']:
                      configData = reserveConfigmaps(namespaces: namespaces,
                                                    configmapsName: "${deploy_svc_name}-akka-cluster-conf",
                                                    configMountName: 'akka-config.conf')
                      svcYaml['akka']['akkaClusterConf'] = configData
                    break
                  }
                }
              }
              switch(deploy_svc_name) {
                case 'rab-task-data-migration':
                  chart_version = '0.1.4'
                  k8s_rs_type = 'job'
                  // job任务特殊处理
                  svcYaml['svcJARPKGOptions'] = params.rab_TASK_DATA_MIGRATION_PARAMETER
                break
                default:
                  chart_version = '0.1.10'
                  k8s_rs_type = 'deploy'
                  svcYaml['replicaCount'] = params.REPLICA_COUNT
                  svcYaml['commandOps'] = [reserveJavaCommand(k8sRSType: k8s_rs_type, namespaces: namespaces, deploySVCName: deploy_svc_name)]
                break
              }
              switch(deploy_svc_name) {
                case ['rab-svc-api-app', 'rab-svc-api-app-master']:
                  // 无论是否勾选 JMXREMOTE，都需要修改 values.yaml 文件中 JMX_REMOTE 的值为布尔值。
                  svcYaml['jmxremote']['isopen'] = Boolean.valueOf(params.JMXREMOTE)
                  svcYaml['jmxremote']['ports'] = javaJmxRemote(k8s: 'cdk8s', namespaces: namespaces, deploySVCName: deploy_svc_name)
                  svcYaml['jmxremote']['hostnameIp'] = 'IAmIPaddress'
                break
                case ['rab-task-data-migration']:
                break
                default:
                  svcYaml['jmxremote']['isopen'] = false
                  svcYaml['jmxremote']['ports'] = '30001'
                  svcYaml['jmxremote']['hostnameIp'] = 'IAmIPaddress'
                break
              }
              // 未从minio下载到helm value yaml文件时，需要处理卷挂载问题
              // 后续能够从minio下载文件之后，可以直接修改minio中的yaml文件的磁盘挂载部分内容
              println("isFirst": isFirst)
              if (isFirst) {
                // 只有当minio中无该helm value yaml文件时，才会去设置这些值
                // 所以，后续可以直接修改minio中的yaml文件，其中不是每次都定义的部分
                svcYaml['image']['harborProject'] = project
                /* 处理卷创建与挂载问题 */
                switch(namespaces) {
                  // api4
                  case ['new-rab-dev-3', 'new-rab-dev-4', 'new-rab-staging-4']:
                    switch(deploy_svc_name) {
                      case ['rab-svc-api-app']:
                        // 只有api的时候才能创建卷
                        // 标准卷
                        svcYaml['storage']['isCreateDataPVC'] = false
                        // alone独立卷
                        svcYaml['storage']['isCreateAlonePVC'] = true
                      break
                    }
                    // 标准卷
                    svcYaml['storage']['isMountDataPVType'] = 'empty'
                    svcYaml['storage']['capacity'] = '50Gi'
                    svcYaml['storage']['dataPVCMountPath'] = '/opt/rab_backend/cache'
                    svcYaml['storage']['dataStorageClassName'] = 'nfs-client-retain'
                    // 日志持久卷
                    svcYaml['storage']['logStorageClassName'] = 'nfs-client-retain'
                    // mosek卷
                    svcYaml['storage']['mosekStorageClassName'] = 'nfs-client-retain'
                    // 独立卷
                    switch(deploy_svc_name) {
                      case ['rab-svc-api-app', 'rab-svc-offline-app']:
                        svcYaml['storage']['isMountAlonePV'] = true
                        svcYaml['storage']['aloneCapacity'] = '200Gi'
                        svcYaml['storage']['aloneDataPVCMountPath'] = '/opt/rab_backend/data'
                      break
                    }
                    svcYaml['storage']['aloneDataStorageClassName'] = 'nfs-client-retain'
                  break
                  default:
                    switch(deploy_svc_name) {
                      case ['rab-svc-api-app']:
                        // 只有api的时候才能创建卷
                        // 标准卷
                        svcYaml['storage']['isCreateDataPVC'] = true
                        // 独立卷
                        svcYaml['storage']['isCreateAlonePVC'] = false
                      break
                    }
                    // 标准卷
                    svcYaml['storage']['isMountDataPVType'] = 'pvc'
                    svcYaml['storage']['capacity'] = '500Gi'
                    svcYaml['storage']['dataPVCMountPath'] = '/opt/rab_backend/data'
                    svcYaml['storage']['dataStorageClassName'] = 'nfs-client-retain'
                    // 日志持久卷
                    svcYaml['storage']['logStorageClassName'] = 'nfs-client-retain'
                    // mosek卷
                    svcYaml['storage']['mosekStorageClassName'] = 'nfs-client-retain'
                    // 独立卷
                    switch(deploy_svc_name) {
                      case ['rab-svc-api-app', 'rab-svc-offline-app']:
                        svcYaml['storage']['isMountAlonePV'] = false
                        svcYaml['storage']['aloneCapacity'] = ''
                        svcYaml['storage']['aloneDataPVCMountPath'] = ''
                      break
                    }
                    svcYaml['storage']['aloneDataStorageClassName'] = ''
                  break
                }
              }
              writeYaml file: "${deploy_svc_name}-values.yaml", data: svcYaml, overwrite: true
              sh "sed -i 's#CONFIG_ENV#${configENV}#' ${deploy_svc_name}-values.yaml"
              sh "cat ${deploy_svc_name}-values.yaml"
              // 备份 helm value yaml 文件
              minioFile(doType: 'upload', fileNamePath: "${deploy_svc_name}-values.yaml", namespace: "${namespaces}")
              configFileProvider([configFile(fileId: "${K8S_AUTH}", targetLocation: "kube-config.yaml")]){
                switch(deploy_svc_name) {
                  case 'rab-task-data-migration':
                    deployWithHelmToK8sByJob(deploySVCName: deploy_svc_name,
                                            deployToEnv: namespaces,
                                            imageTag: image_tag,
                                            chartName: 'bf-java-project-job-common',
                                            chartVersion: chart_version)
                  break
                  case ['rab-svc-api-app']:
                    // neo4j数据库有文件锁
                    switch(namespaces) {
                      case ['new-rab-dev-4']:
                        sh """
                          kubectl --kubeconfig kube-config.yaml -n ${namespaces} scale deployment ${deploy_svc_name}-${configEnvSuffix} --replicas 0
                        """
                      break
                    }
                    deployWithHelmToK8sByDeploy(deploySVCName: deploy_svc_name,
                                                deployToEnv: namespaces,
                                                imageTag: image_tag,
                                                chartName: 'bf-java-project-deploy-common',
                                                chartVersion: chart_version)
                  break
                  default:
                    deployWithHelmToK8sByDeploy(deploySVCName: deploy_svc_name,
                                                deployToEnv: namespaces,
                                                imageTag: image_tag,
                                                chartName: 'bf-java-project-deploy-common',
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

