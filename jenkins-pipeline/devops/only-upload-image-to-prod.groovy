#!groovy
/* 导入Jenkins共享库 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
// def shanghai_registry = "harbor.betack.com"
def shanghai_inner_registry = "IAmIPaddress"
def imageDict = [:]


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
    // retry(2)                          // 重试次数
    disableConcurrentBuilds()         // java项目禁止并发构建：主要是gradle有锁，导致无法并发构建
    timestamps()                      // 添加时间戳
    timeout(time: 120, unit:'MINUTES') // 设置此次发版运行20分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', numToKeepStr: '100')  // 设置保留100次构建记录
  }

  parameters {
    string description: '请输入docker镜像，比如：IAmIPaddress:8765/rab/rab-svc-api-app:6b2cf841e1-5',
           name: 'SVC_IMAGE'
  }

  stages {
    stage('检查镜像') {
      steps {
        container('podman') {
          script{
            println("检查镜像仓库中是否存在该镜像：" + params.SVC_IMAGE)
            // 比如镜像为：IAmIPaddress:8765/bf-bsc/data-etl-server:52a5b996-2182
            // 拆分为：['IAmIPaddress:8765', 'bf-bsc', 'bf-bsc', 'data-etl-server', '52a5b996-2182']
            // IAmIPaddress:8765/libs/elasticsearch/elasticsearch:7.17.3
            // [IAmIPaddress:8765, libs, libs/elasticsearch/elasticsearch, elasticsearch%252Felasticsearch, elasticsearch, 7.17.3]
            imageElementList = libTools.splitImage(params.SVC_IMAGE)
            println(imageElementList)
            // 第一次直接检查成都开发测试环境的镜像仓库
            checkImageTagExites = checkImageTagForAllHarbor(project: imageElementList[1],
                                                            deploySVCName: imageElementList[-3],
                                                            imageTag: imageElementList[-1],
                                                            harborApiAddr: 'https://bf-harbor.betack.com:8765',
                                                            harborApiAuth: "443eb7ee-c21b-4e32-b449-e01d83171672")
            if(!checkImageTagExites) {
              println("本次检查的镜像为：" + params.SVC_IMAGE)
              error "成都开发测试环境的镜像仓库${office_registry}中不存在镜像: ${params.SVC_IMAGE}，请检查！！！"
            }
          }
        }
      }
    }

    stage('传镜像到上海prod harbor') {
      steps {
        container('podman') {
          script{
            imageDict = imagePullToProd(project: imageElementList[1], deploySVCName: imageElementList[-3], imageTag: imageElementList[-1])
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
