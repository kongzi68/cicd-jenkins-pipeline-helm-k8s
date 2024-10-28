#!groovy
/* 导入Jenkins共享库，默认导入main分支 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
def shanghai_registry = "IAmIPaddress"
// def shanghai_registry = "harbor.betack.com"
// def hwcloud_cce_registry = "swr.cn-east-2.myhuaweicloud.com/betack"
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
    //retry(2)                        // 重试次数
    disableConcurrentBuilds()         // 禁止并发构建
    timestamps()                      // 添加时间戳
    timeout(time: 60, unit:'MINUTES') // 设置此次发版运行60分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', numToKeepStr: '30')  // 设置保留30次构建记录
  }

  parameters {
    string description: '服务需要部署到生产环境shanghai k8s集群，请输入docker镜像TAG。\n比如：docker镜像 IAmIPaddress:8765/rab/rab-svc-api-app:6b2cf841e1-5 中冒号后面的 6b2cf841e1-5 是镜像TAG。',
           name: 'SVC_IMAGE_TAG'
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'fund-report,fund-evaluation-mobile,counsel-nginx,rab-webapp,biu-fronted,portfolio-risk-research-management',
                   visibleItemCount: 6
    extendedChoice description: '发版到选中的运行环境, 其中：\nofficial-website-prod-1 是官网的API; \nproduct-rack-prod-1 是策略产品货架API',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'rab-api4ai-prod-1',
                   visibleItemCount: 8
  }

  stages {
    stage('上传镜像到hwcloudCCE') {
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
            helm_values_file="temp_jenkins_workspace/new-rab/new-rab-frontend-prod/deployToK8s/${deploy_svc_name}-values.yaml"
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
              svcYaml['imagePullSecrets'] = [[name:'harbor-inner'], [name:'harbor-outer']]
              if (isFirst) {
                // 只有当minio中无该helm value yaml文件时，才会去设置这些值
                // 所以，后续可以直接修改minio中的yaml文件，其中不是每次都定义的部分
                svcYaml['image']['harborProject'] = project
              }
              writeYaml file: "${deploy_svc_name}-values.yaml", data: svcYaml, overwrite: true
              if (isFirst) {
                // 经确认，生产环境api-app接口端口为80，开发测试为8080
                // API_ADDRS = "http://rab-svc-api-app-out-${configEnvSuffix}:80"
                // SOCKET_ADDRS = "http://rab-svc-api-app-out-${configEnvSuffix}:9999/socket.io"
                API_ADDRS = "http://IAmIPaddress:80"
                SOCKET_ADDRS = "http://IAmIPaddress:9999/socket.io"
                sh """
                  sed -i 's#API_ADDRS#${API_ADDRS}#' ${deploy_svc_name}-values.yaml
                  sed -i 's#SOCKET_ADDRS#${SOCKET_ADDRS}#' ${deploy_svc_name}-values.yaml
                """
              }
              sh "cat ${deploy_svc_name}-values.yaml"
              // 备份 helm value yaml 文件
              minioFile(doType: 'upload', fileNamePath: "${deploy_svc_name}-values.yaml", namespace: "${namespaces}")
              configFileProvider([configFile(fileId: K8S_AUTH, targetLocation: "kube-config.yaml")]){
                doDeployToK8s(namespaces, deploy_svc_name, params.SVC_IMAGE_TAG, '0.1.2')
              }
            }
          }
        }
      }
    }
  }
}


def doDeployToK8s(deployToEnv, deploySVCName, imageTag, chartVersion) {
  switch(deploySVCName) {
    case ['fund-report']:
      chartName = 'bf-frontend-deploy-common'
    break
    default:
      chartName = 'bf-frontend-nginx-deploy-common'
    break
  }
  // helm 部署服务
  deployWithHelmToK8sByDeploy(deploySVCName:deploySVCName,
                              imageTag:imageTag,
                              deployToEnv:deployToEnv,
                              chartName:chartName,
                              chartVersion:chartVersion)
  echo "部署完成..."
  // 输出访问链接地址
  // svcNodePort = libTools.getSVCNodePort(deployToEnv, deploySVCName, 3005, CONFIG_ENV_SUFFIX)
  // echo "${deploySVCName}，访问地址：http://${NODE_IPADDRS}:${svcNodePort}"
}

