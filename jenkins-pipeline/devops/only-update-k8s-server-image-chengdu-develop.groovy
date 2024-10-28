#!groovy
/* 导入Jenkins共享库 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
// def shanghai_registry = "harbor.betack.com"
// def shanghai_inner_registry = "IAmIPaddress"
def Map deployInfoMap = [:]


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
    timeout(time: 120, unit:'MINUTES') // 设置此次发版运行20分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', numToKeepStr: '100')  // 设置保留100次构建记录
  }

  parameters {
    string description: '服务需要部署到生产环境shanghai k8s集群，请输入docker镜像TAG。\n比如：docker镜像 IAmIPaddress:8765/rab/rab-svc-api-app:6b2cf841e1-5 中冒号后面的 6b2cf841e1-5 是镜像TAG。',
           name: 'SVC_IMAGE_TAG'
    extendedChoice defaultValue: '请选择',
                    description: '选择K8S所在的区域：成都开发测试环境、上海生产环境',
                    multiSelectDelimiter: ',',
                    name: 'K8S_REGION',
                    quoteValue: false,
                    saveJSONParameterToFile: false,
                    type: 'PT_SINGLE_SELECT',
                    value: '请选择,成都开发-k8s',
                    visibleItemCount: 5
    reactiveChoice choiceType: 'PT_SINGLE_SELECT', 
        description: '选择需要更新的K8S命名空间环境',
        filterLength: 1,
        filterable: true,
        name: 'K8S_NAMESPACES',
        randomName: 'choice-parameter-10543002365013876',
        referencedParameters: 'K8S_REGION',
            script: groovyScript(
                fallbackScript: [classpath: [], oldScript: '', sandbox: false, script: 'return ["请选择"]'],
                script: [classpath: [], oldScript: '', sandbox: false, script: '''
                    import groovy.json.JsonSlurper
                    switch(K8S_REGION) {
                        case "成都开发-k8s":
                            k8sRegion = "cd-develop"
                        break
                        default:
                            return []
                        break
                    }
                    def apiURL = "http://k8s-api-devops-in-svc:8080/api/k8s/namespaces/only-name?k8s_region=" + k8sRegion
                    def pkgObject = ["curl", apiURL].execute().text
                    def jsonSlurper = new JsonSlurper()
                    def artifactsJsonObject = jsonSlurper.parseText(pkgObject)
                    retList = artifactsJsonObject.data
                    return retList.sort()
                '''])
    reactiveChoice choiceType: 'PT_CHECKBOX',
        description: '选择需要更新的服务',
        filterLength: 1,
        filterable: true,
        name: 'K8S_SERVICES',
        randomName: 'choice-parameter-10546033758567791',
        referencedParameters: 'K8S_REGION,K8S_NAMESPACES',
            script: groovyScript(
                fallbackScript: [classpath: [], oldScript: '', sandbox: false, script: 'return ["请选择"]'],
                script: [classpath: [], oldScript: '', sandbox: false, script: '''
                    import groovy.json.JsonSlurper
                    switch(K8S_REGION) {
                        case "成都开发-k8s":
                            k8sRegion = "cd-develop"
                        break
                        default:
                            return []
                        break
                    }
                    def apiK8S = "http://k8s-api-devops-in-svc:8080/api/k8s/"
                    def workloadTypeList = ["deployments", "statefulsets"]
                    def retList = []
                    for (workloadType in workloadTypeList) {
                        // http://k8s-api-devops-in-svc:8080/api/k8s/deployments/only-name?k8s_region=cd&namespaces=saasdata-dev-1
                        def apiURL =  apiK8S + workloadType  + "/only-name?k8s_region=" + k8sRegion  + "&namespaces=" +  K8S_NAMESPACES
                        // println(apiURL)
                        def pkgObject = ["curl", apiURL].execute().text
                        def jsonSlurper = new JsonSlurper()
                        def artifactsJsonObject = jsonSlurper.parseText(pkgObject)
                        retList.add(artifactsJsonObject.data)
                    }
                    ret = retList[0] + retList[1]
                    return ret.sort()
                '''])
  }

  stages {
    stage('环境准备') {
      when {
        expression {
          return (params.K8S_SERVICES != '')
        }
      }
      steps {
        script{
          configEnv = libTools.splitNamespaces(params.K8S_NAMESPACES)
          // configEnvPrefix = configEnv[0]
          configEnvSuffix = configEnv[1]
          println("CONFIG_ENV：" + configEnvSuffix)
          // println("项目简称，用于命名空间的前缀：" + configEnvPrefix)
          for (k8sServices in params.K8S_SERVICES.tokenize(',')) {
            serverName = k8sServices.replaceAll("-${configEnvSuffix}","")
            println('正在部署服务：' + serverName)
            K8S_AUTH = 'cb046c9d-4a04-4f8c-b46c-fb58731990de'
            harborApiAddr = 'https://bf-harbor.betack.com:8765'
            configFileProvider([configFile(fileId: K8S_AUTH, targetLocation: "kube-config.yaml")]) {
              workloadType = libTools.getServerWorkloadTypeByHelm(params.K8S_NAMESPACES, serverName)
              // 这里只处理一个pod为单个容器的情况
              imageNameLists = libTools.getSVCImagesList(params.K8S_NAMESPACES, workloadType, serverName)
              containerName = imageNameLists[0]
              imageName = imageNameLists[1]
              imageNameList = libTools.splitImage(imageName)
              // println(imageNameList)
              if (imageNameList == []) {
                error "部署失败，请查看Jenkins部署日志，确定报错原因……"
              }
            }
            deployInfoMap.put(serverName, [workloadType, containerName, imageName, imageNameList])
          }
        }
      }
    }

    stage('检查镜像') {
      steps {
        container('podman') {
          script{
            println("检查镜像仓库中是否存在该镜像tag：" + params.SVC_IMAGE_TAG)
            // 处理服务名称与容器镜像关键词名称不一致的情况
            for (serverName in deployInfoMap.keySet()) {
              switch(serverName) {
                  case 'alpine-tools-sshd':
                      tempServerName = 'alpine'
                  break
                  // case 'betack-index-product':  // 指数货架的镜像用的是betack-official-website
                  //     tempServerName = 'betack-official-website'
                  // break
                  default:
                      tempServerName = serverName
                  break
              }
              // 第一次直接检查成都开发测试环境的镜像仓库
              checkImageTagExites = checkImageTagForAllHarbor(project: deployInfoMap.get(serverName)[-1][1],
                                                              deploySVCName: tempServerName,
                                                              imageTag: params.SVC_IMAGE_TAG,
                                                              harborApiAddr: 'https://bf-harbor.betack.com:8765',
                                                              harborApiAuth: "443eb7ee-c21b-4e32-b449-e01d83171672")
              println(deployInfoMap.get(serverName)[-2])
              if(!checkImageTagExites) {
                checkImageName = deployInfoMap.get(serverName)[-2].replaceAll(deployInfoMap.get(serverName)[-1][-1], params.SVC_IMAGE_TAG).replaceAll(deployInfoMap.get(serverName)[-1][0], office_registry)
                println("本次检查的镜像为：" + checkImageName)
                error "成都开发测试环境的镜像仓库${office_registry}中不存在镜像tag: ${params.SVC_IMAGE_TAG}，请检查！！！"
              }
            }
          }
        }
      }
    }

    stage('更新服务镜像') {
      steps {
        script {
          for (serverName in deployInfoMap.keySet()) {
            deployImageName = deployInfoMap.get(serverName)[-2].replaceAll(deployInfoMap.get(serverName)[-1][-1], params.SVC_IMAGE_TAG).replaceAll(deployInfoMap.get(serverName)[-1][0], office_registry)
            println("服务${serverName}本次更新的镜像为：" + deployImageName)
            configFileProvider([configFile(fileId: K8S_AUTH, targetLocation: "kube-config.yaml")]) {
              switch(deployInfoMap.get(serverName)[0]) {
                case 'deploy':
                  tempWorkloadType = 'deployments'
                break
                case 'sts':
                  tempWorkloadType = 'statefulsets'
                break
              }
              containerName = deployInfoMap.get(serverName)[1]
              sh """
                kubectl --kubeconfig=kube-config.yaml set image ${tempWorkloadType}/${serverName}-${configEnvSuffix} \
                  ${containerName}=${deployImageName} -n ${params.K8S_NAMESPACES}
              """
            }
          }
        }
      }
    }
  }
}

