#!groovy
/* 导入Jenkins共享库 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
// def hwcloud_registry = "harbor.betack.com"
def hwcloud_cce_registry = "swr.cn-east-2.myhuaweicloud.com/betack"

// 项目
def project = "rab"  // HARrab镜像仓库中的项目名称

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
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '30')  // 设置保留30天的构建历史，或者30次的构建记录
  }

  parameters {
    string description: '服务需要部署到华为云CCE集群，请输入docker镜像TAG。\n比如：docker镜像 IAmIPaddress:8765/rab/rab-svc-api-app:6b2cf841e1-5 中冒号后面的 6b2cf841e1-5 是镜像TAG。',
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
                   value: 'rab-prod-1',
                   visibleItemCount: 10
  }

  stages {
    stage('上传镜像到hwcloudCCE') {
      steps {
        container('podman') {
          script{
            for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
              imagePullToHwcloudCCE(project: project,
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
            // 循环处理需要部署的命名空间
            for (namespaces in params.DEPLOY_TO_ENV.tokenize(',')) {
              helm_values_file="temp_jenkins_workspace/new-${project}/new-${project}-backend-prod/deployToK8s/${deploy_svc_name}-values.yaml"
              copyHelmValuesFile(namespaces:namespaces,
                                 helmValuesFile:helm_values_file,
                                 deploySVCName:deploy_svc_name)
              // K8S = 'hwcloud-k8s'
              K8S_AUTH = '11383954-4f2d-4d7f-b8c9-1ff73fcc9478'
              switch(deploy_svc_name) {
                case ['rab-svc-api-app']:
                  sh """
                    sed -i 's#REPLICA_COUNT#${params.REPLICA_COUNT}#' ${deploy_svc_name}-values.yaml
                  """
                break
              }

              configFileProvider([configFile(fileId: "${K8S_AUTH}", targetLocation: "kube-config.yaml")]){
                env.JMX_NODEPORT = ''
                switch(deploy_svc_name) {
                  case ['rab-svc-api-app', 'rab-svc-api-app-master']:
                    chart_version = '0.1.6'
                    k8s_rs_type = 'deploy'
                  break
                  case 'rab-task-data-migration':
                    chart_version = '0.1.3'
                    k8s_rs_type = 'job'
                  break
                  default:
                    chart_version = '0.1.6'
                    k8s_rs_type = 'deploy'
                  break
                }
                reserveJavaCommand(k8sRSType:k8s_rs_type,
                                   namespaces:namespaces,
                                   deploySVCName:deploy_svc_name)
                sh """
                  sed -ri "s#dag.datasource.url=(.+Shanghai)#dag.datasource.url='\\1'#g" ${deploy_svc_name}-values.yaml
                  sed -i "s#u0026#\\&#g" ${deploy_svc_name}-values.yaml
                """
                switch(deploy_svc_name) {
                  case 'rab-task-data-migration':
                    doDeployToK8sJob(namespaces, deploy_svc_name, params.SVC_IMAGE_TAG, chart_version, params.rab_TASK_DATA_MIGRATION_PARAMETER)
                  break
                  default:
                    doDeployToK8s(namespaces, deploy_svc_name, params.SVC_IMAGE_TAG, chart_version)
                  break
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
                              chartName:'bf-java-project-deploy-common',
                              chartVersion:chartVersion)
}


def doDeployToK8sJob(deployToEnv, deploySVCName, imageTag, chartVersion, deploySVCOptions) {
  // helm 部署服务
  deployWithHelmToK8sByJob(deploySVCName:deploySVCName,
                           imageTag:imageTag,
                           deployToEnv:deployToEnv,
                           chartName:'bf-java-project-job-common',
                           chartVersion:chartVersion,
                           deploySVCOptions:deploySVCOptions)
}
