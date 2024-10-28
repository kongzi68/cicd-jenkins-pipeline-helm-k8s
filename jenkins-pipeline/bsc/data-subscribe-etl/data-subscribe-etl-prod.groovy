#!groovy
/* 导入Jenkins共享库 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
def shanghai_registry = "IAmIPaddress"
def project = "bf-etl"  // HARrab镜像仓库中的项目名称
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
                memory: "40Gi"
                cpu: "10"
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
    // retry(2)                          // 重试次数
    disableConcurrentBuilds()         // java项目禁止并发构建：主要是gradle有锁，导致无法并发构建
    timestamps()                      // 添加时间戳
    timeout(time: 60, unit:'MINUTES') // 设置此次发版运行20分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', numToKeepStr: '50')  // 设置保留50次的构建记录
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
                   value: 'data-subscribe-etl',
                   visibleItemCount: 6
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'saasdata-prod-1',
                   visibleItemCount: 10
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将开启 jmxremote 远程调试功能',
                 name: 'JMXREMOTE'
    booleanParam defaultValue: true,
                 description: '默认启用，将备份values.yaml存储到minio，且部署时优先使用该备份; 若需要用模板重新生成时，请取消勾选。',
                 name: 'IS_ENABLED_BAK_VALUES_YAML'
  }

  environment {
    image_tag = "${params.SVC_IMAGE_TAG}".trim()
  }

  stages {
    stage('上传镜像到生产PROD环境') {
      steps {
        container('podman') {
          script{
            for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
              imagePullToProd(project: project,
                              deploySVCName: deploy_svc_name,
                              imageTag: image_tag)
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
            helm_values_file="temp_jenkins_workspace/bsc/data-subscribe-etl/deployToK8s/${deploy_svc_name}-values.yaml"
            // 循环处理需要部署的命名空间
            for (namespaces in params.DEPLOY_TO_ENV.tokenize(',')) {
              K8S_AUTH = 'fccafb7c-8128-4a91-87b2-3b7cb6940343'
              isFirst = checkHelmValuesFilesOnMinio(isEnabled: params.IS_ENABLED_BAK_VALUES_YAML,
                                                    k8sKey: K8S_AUTH,
                                                    namespaces: namespaces,
                                                    deploySVCName: deploy_svc_name,
                                                    helmValuesFilePath: helm_values_file)
              svcYaml = readYaml file: "${deploy_svc_name}-values.yaml"
              svcYaml['image']['imgHarbor'] = shanghai_registry
              svcYaml['image']['tag'] = image_tag
              svcYaml['image']['imgNameOrSvcName'] = deploy_svc_name
              configEnv = libTools.splitNamespaces(namespaces)
              configEnvPrefix = configEnv[0]
              configEnvSuffix = configEnv[1]
              configENV = configEnv[2]
              println("CONFIG_ENV：" + configEnvSuffix)
              println("项目简称，用于命名空间的前缀：" + configEnvPrefix)
              svcYaml['namespacePrefix'] = configEnvPrefix
              chart_version = '0.1.0'
              k8s_rs_type = 'deploy'
              svcYaml['replicaCount'] = params.REPLICA_COUNT
              svcYaml['commandOps'] = [reserveJavaCommand(k8sRSType: k8s_rs_type, namespaces: namespaces, deploySVCName: deploy_svc_name)]
              switch(deploy_svc_name) {
                case ['data-subscribe-etl']:
                  // 无论是否勾选 JMXREMOTE，都需要修改 values.yaml 文件中 JMX_REMOTE 的值为布尔值。
                  svcYaml['jmxremote']['isopen'] = Boolean.valueOf(params.JMXREMOTE)
                  svcYaml['jmxremote']['ports'] = javaJmxRemote(k8s: 'shanghai', namespaces: namespaces, deploySVCName: deploy_svc_name)
                  svcYaml['jmxremote']['hostnameIp'] = 'IAmIPaddress'
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
                svcYaml['nameOverride'] = deploy_svc_name
                svcYaml['image']['harborProject'] = project
                /* 处理卷创建与挂载问题 */
                switch(deploy_svc_name) {
                  case ['data-subscribe-etl']:
                    // 只有api的时候才能创建卷
                    // 标准卷
                    svcYaml['storage']['isCreateDataPVC'] = false
                    // 独立卷
                    svcYaml['storage']['isCreateAlonePVC'] = false
                  break
                }
                // 标准卷
                svcYaml['storage']['isMountDataPVType'] = 'pvc'
                svcYaml['storage']['capacity'] = '1536Gi'
                svcYaml['storage']['dataPVCNameInfix'] = 'data'
                svcYaml['storage']['dataPVCMountPath'] = '/opt/saas-data/data'
                svcYaml['storage']['dataStorageClassName'] = 'longhorn-fast'
                // 日志持久卷
                svcYaml['storage']['isMountLogPV'] = true
                svcYaml['storage']['logStorageClassName'] = 'longhorn-fast'
                // mosek卷
                svcYaml['storage']['isMountMosekPV'] = true
                svcYaml['storage']['mosekStorageClassName'] = 'longhorn-fast'
                // 独立卷
                svcYaml['storage']['isMountAlonePV'] = false
                svcYaml['storage']['aloneCapacity'] = ''
                svcYaml['storage']['aloneDataPVCMountPath'] = ''
                svcYaml['storage']['aloneDataStorageClassName'] = ''
              }
              writeYaml file: "${deploy_svc_name}-values.yaml", data: svcYaml, overwrite: true
              sh "sed -i 's#CONFIG_ENV#${configENV}#' ${deploy_svc_name}-values.yaml"
              sh "cat ${deploy_svc_name}-values.yaml"
              // 备份 helm value yaml 文件
              minioFile(doType: 'upload', fileNamePath: "${deploy_svc_name}-values.yaml", namespace: "${namespaces}")
              configFileProvider([configFile(fileId: "${K8S_AUTH}", targetLocation: "kube-config.yaml")]){
                deployWithHelmToK8sByDeploy(deploySVCName: deploy_svc_name,
                                            deployToEnv: namespaces,
                                            imageTag: image_tag,
                                            chartName: 'bf-java-project-deploy-common-v3',
                                            chartVersion: chart_version)
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
