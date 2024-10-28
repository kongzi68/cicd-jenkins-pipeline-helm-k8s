#!groovy
/* 导入Jenkins共享库 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
def shanghai_registry = "harbor.betack.com"
// def shanghai_registry = "harbor.betack.com"
// def hwcloud_cce_registry = "swr.cn-east-2.myhuaweicloud.com/betack"

// 项目
def project = "rab"  // HARrab镜像仓库中的项目名称
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
    timeout(time: 30, unit:'MINUTES') // 设置此次发版运行20分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', numToKeepStr: '30')  // 设置保留30次构建记录
  }

  parameters {
    string description: '服务需要部署到生产环境shanghai k8s集群，请输入docker镜像TAG。\n比如：docker镜像 IAmIPaddress:8765/rab/rab-svc-api-app:6b2cf841e1-5 中冒号后面的 6b2cf841e1-5 是镜像TAG。',
           name: 'SVC_IMAGE_TAG'
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
                   value: 'rab-api3-prod-1',
                   visibleItemCount: 10
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将开启 jmxremote 远程调试功能',
                 name: 'JMXREMOTE'
  }

  stages {
    stage('上传镜像到生产PROD环境') {
      steps {
        container('podman') {
          script{
            for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
              imagePullToProd(project: project,
                              deploySVCName: deploy_svc_name,
                              imageTag: "${params.SVC_IMAGE_TAG}")
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
            helm_values_file="temp_jenkins_workspace/new-rab/new-rab-backend-prod/deployToK8s/${deploy_svc_name}-values.yaml"
            // 循环处理需要部署的命名空间
            for (namespaces in params.DEPLOY_TO_ENV.tokenize(',')) {
              // 上海idc prod k8s集群
              K8S_AUTH = 'fccafb7c-8128-4a91-87b2-3b7cb6940343'
              isFirst = checkHelmValuesFilesOnMinio(k8sKey: K8S_AUTH,
                                                    namespaces: namespaces,
                                                    deploySVCName: deploy_svc_name,
                                                    helmValuesFilePath: helm_values_file)
              svcYaml = readYaml file: "${deploy_svc_name}-values.yaml"
              svcYaml['image']['imgHarbor'] = shanghai_registry
              svcYaml['image']['tag'] = params.SVC_IMAGE_TAG
              configEnv = libTools.splitNamespaces(namespaces)
              configEnvPrefix = configEnv[0]
              configEnvSuffix = configEnv[1]
              configENV = configEnv[2]
              println("CONFIG_ENV：" + configEnvSuffix)
              println("项目简称，用于命名空间的前缀：" + configEnvPrefix)
              svcYaml['namespacePrefix'] = configEnvPrefix
              configFileProvider([configFile(fileId: K8S_AUTH, targetLocation: "kube-config.yaml")]){
                switch(deploy_svc_name) {
                  case 'rab-task-data-migration':
                    chart_version = '0.1.3'
                    k8s_rs_type = 'job'
                    // job任务特殊处理
                    svcYaml['svcJARPKGOptions'] = params.rab_TASK_DATA_MIGRATION_PARAMETER
                    sh "sed -i 's#CONFIG_ENV#${configENV}#' ${deploy_svc_name}-values.yaml"
                  break
                  default:
                    chart_version = '0.1.7'
                    k8s_rs_type = 'deploy'
                    svcYaml['replicaCount'] = params.REPLICA_COUNT
                    svcYaml['commandOps'] = [reserveJavaCommand(k8sRSType: k8s_rs_type, namespaces: namespaces, deploySVCName: deploy_svc_name)]
                  break
                }
                switch(deploy_svc_name) {
                  case ['rab-svc-api-app', 'rab-svc-api-app-master']:
                    // 无论是否勾选 JMXREMOTE，都需要修改 values.yaml 文件中 JMX_REMOTE 的值为布尔值。
                    svcYaml['jmxremote']['isopen'] = Boolean.valueOf(params.JMXREMOTE)
                    svcYaml['jmxremote']['ports'] = javaJmxRemote(k8s: 'shanghai', namespaces: namespaces, deploySVCName: deploy_svc_name)
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
                svcYaml['imagePullSecrets'] = [[name:'harbor-inner'], [name:'harbor-outer']]
                if (isFirst) {
                  // 只有当minio中无该helm value yaml文件时，才会去设置这些值
                  // 所以，后续可以直接修改minio中的yaml文件，其中不是每次都定义的部分
                  svcYaml['image']['harborProject'] = project
                  /* 处理卷创建与挂载问题 */
                  switch(deploy_svc_name) {
                    case ['rab-svc-api-app']:
                      // api创建缓存卷
                      // 标准卷
                      svcYaml['storage']['isCreateDataPVC'] = true
                      svcYaml['storage']['isMountDataPVType'] = 'pvc'
                      svcYaml['storage']['capacity'] = '300Gi'
                      svcYaml['storage']['dataPVCMountPath'] = '/opt/rab_backend/data'
                      svcYaml['storage']['dataStorageClassName'] = 'longhorn-fast'
                      // 日志持久卷
                      svcYaml['storage']['logStorageClassName'] = 'longhorn-fast'
                      // mosek卷
                      svcYaml['storage']['mosekStorageClassName'] = 'longhorn-fast'
                      // 独立卷
                      svcYaml['storage']['isCreateAlonePVC'] = false
                      svcYaml['storage']['isMountAlonePV'] = false
                      svcYaml['storage']['aloneCapacity'] = ''
                      svcYaml['storage']['aloneDataPVCMountPath'] = ''
                      svcYaml['storage']['aloneDataStorageClassName'] = ''
                    break
                    default:
                      // 其它未单独列出的，挂载api创建的卷
                      // 标准卷
                      svcYaml['storage']['isCreateDataPVC'] = false
                      svcYaml['storage']['isMountDataPVType'] = 'pvc'
                      svcYaml['storage']['capacity'] = '300Gi'
                      svcYaml['storage']['dataPVCMountPath'] = '/opt/rab_backend/data'
                      svcYaml['storage']['dataStorageClassName'] = 'longhorn-fast'
                      // 日志持久卷
                      svcYaml['storage']['logStorageClassName'] = 'longhorn-fast'
                      // mosek卷
                      svcYaml['storage']['mosekStorageClassName'] = 'longhorn-fast'
                      // 独立卷
                      svcYaml['storage']['isCreateAlonePVC'] = false
                      svcYaml['storage']['isMountAlonePV'] = false
                      svcYaml['storage']['aloneCapacity'] = ''
                      svcYaml['storage']['aloneDataPVCMountPath'] = ''
                      svcYaml['storage']['aloneDataStorageClassName'] = ''
                    break
                  }
                }
                writeYaml file: "${deploy_svc_name}-values.yaml", data: svcYaml, overwrite: true
                sh "cat ${deploy_svc_name}-values.yaml"
                // 备份 helm value yaml 文件
                minioFile(doType: 'upload', fileNamePath: "${deploy_svc_name}-values.yaml", namespace: "${namespaces}")
                switch(deploy_svc_name) {
                  case 'rab-task-data-migration':
                    deployWithHelmToK8sByJob(deploySVCName: deploy_svc_name,
                                            deployToEnv: namespaces,
                                            imageTag: params.SVC_IMAGE_TAG,
                                            chartName: 'bf-java-project-job-common',
                                            chartVersion: chart_version)
                  break
                  default:
                    deployWithHelmToK8sByDeploy(deploySVCName: deploy_svc_name,
                                                deployToEnv: namespaces,
                                                imageTag: params.SVC_IMAGE_TAG,
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

