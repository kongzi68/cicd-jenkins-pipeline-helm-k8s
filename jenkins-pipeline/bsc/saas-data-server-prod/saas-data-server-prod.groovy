#!groovy
/* 导入Jenkins共享库，默认导入main分支 */
@Library('bf-shared-library') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress"
// def hwcloud_registry = "harbor.betack.com"
def hwcloud_cce_registry = "swr.cn-east-2.myhuaweicloud.com/betack"

// 数据通用API **数据管理-更新-发布 项目数据对接
// 项目
def project = "bf-bsc"  // HARrab镜像仓库中的项目名称

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
              - name: docker-cmd
                mountPath: /usr/bin/docker
              - name: docker-sock
                mountPath: /var/run/docker.sock
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
          dnsConfig:
            nameservers:
            - IAmIPaddress
            - IAmIPaddress
          nodeSelector:
            is-install-docker: true
          imagePullSecrets:
          - name: harbor-outer
          - name: harbor-inner
          volumes:
            - name: docker-cmd
              hostPath:
                path: /usr/bin/docker
            - name: docker-sock
              hostPath:
                path: /var/run/docker.sock
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
    string description: '服务需要部署到华为云CCE集群，请输入docker镜像TAG。\n比如：docker镜像 IAmIPaddress/rab/rab-svc-api-app:6b2cf841e1-5 中冒号后面的 6b2cf841e1-5 是镜像TAG。',
           name: 'SVC_IMAGE_TAG'
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
                   value: 'saasdata-prod-1',
                   visibleItemCount: 10
  }

  stages {
    stage('上传镜像到hwcloudCCE') {
      steps {
        script{
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            switch(deploy_svc_name) {
              case 'saas-data-server':
                deploy_svc_name = 'data-etl-server'
              break
            }
            imagePullToHwcloudCCE(project: project,
                                  deploySVCName: deploy_svc_name,
                                  imageTag: params.SVC_IMAGE_TAG)
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
            gitCheckout('ssh://git@code.betack.com:4022/devops/jenkins.git')
            sh 'pwd; ls -lh'
          }
          // helm登录harbor仓库
          loginHelmChartRegistry()
          // 循环处理需要部署的服务
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            // 循环处理需要部署的命名空间
            for (namespaces in params.DEPLOY_TO_ENV.tokenize(',')) {
              helm_values_file="temp_jenkins_workspace/bsc/saas-data-server-prod/deployToK8s/${deploy_svc_name}-values.yaml"
              copyHelmValuesFile(namespaces:namespaces,
                                helmValuesFile:helm_values_file,
                                deploySVCName:deploy_svc_name)
              // K8S = 'hwcloud-k8s'
              K8S_AUTH = '11383954-4f2d-4d7f-b8c9-1ff73fcc9478'
              configFileProvider([configFile(fileId: "${K8S_AUTH}", targetLocation: "kube-config.yaml")]){
                switch(deploy_svc_name) {
                  // case ['saas-data-server']:
                  //   chart_version = '0.1.0'
                  //   chart_name = 'bf-java-project-distributed-statefulset'
                  //   k8s_rs_type = 'sts'
                  // break
                  case ['data-migration-job', 'data-meta-import-job']:
                    chart_version = '0.1.3'
                    chart_name = 'bf-java-project-job-common'
                    k8s_rs_type = 'job'
                  break
                  default:
                    chart_version = '0.1.6'
                    chart_name = 'bf-java-project-deploy-common'
                    k8s_rs_type = 'deploy'
                    // sh "sed -i 's#JMX_REMOTE#false#' ${deploy_svc_name}-values.yaml"
                  break
                }
                reserveJavaCommand(k8sRSType:k8s_rs_type,
                                  namespaces:namespaces,
                                  deploySVCName:deploy_svc_name)
                switch(deploy_svc_name) {
                  case ['saas-data-server', 'data-subscriber-client', 'etl-listener']:
                    doDeployToK8s(namespaces, deploy_svc_name, params.SVC_IMAGE_TAG, chart_version, chart_name)
                  break
                  case ['data-migration-job']:
                    doDeployToK8sJob(namespaces, deploy_svc_name, params.SVC_IMAGE_TAG, chart_version, chart_name, '')
                  break
                  case ['data-meta-import-job']:
                    doDeployToK8sJob(namespaces, deploy_svc_name, params.SVC_IMAGE_TAG, chart_version, chart_name, params.DATA_META_IMPORT_JOB_PARAMETER)
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


def doDeployToK8s(deployToEnv, deploySVCName, imageTag, chartVersion, chartName) {
  // helm 部署服务
  deployWithHelmToK8sByDeploy(deploySVCName:deploySVCName,
                              imageTag:imageTag,
                              deployToEnv:deployToEnv,
                              chartName:chartName,
                              chartVersion:chartVersion)
}


def doDeployToK8sJob(deployToEnv, deploySVCName, imageTag, chartVersion, chartName, deploySVCOptions) {
  // helm 部署服务
  deployWithHelmToK8sByJob(deploySVCName:deploySVCName,
                           imageTag:imageTag,
                           deployToEnv:deployToEnv,
                           chartName:chartName,
                           chartVersion:chartVersion,
                           deploySVCOptions:deploySVCOptions)
}

