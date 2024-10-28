#!groovy
/* 导入Jenkins共享库 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
// def shanghai_registry = "harbor.betack.com"
def shanghai_inner_registry = "IAmIPaddress"
def holdImagesMap = [:]
def retainTagsNum = 50


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
    timeout(time: 360, unit:'MINUTES') // 设置此次发版运行20分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', numToKeepStr: '100')  // 设置保留100次构建记录
  }

  parameters {
    extendedChoice defaultValue: '请选择',
                    description: '请选择需要清理的镜像仓库：成都开发测试环境、上海生产环境',
                    multiSelectDelimiter: ',',
                    name: 'HARBOR_REGION',
                    quoteValue: false,
                    saveJSONParameterToFile: false,
                    type: 'PT_SINGLE_SELECT',
                    value: '请选择,成都开发测试环境,上海生产环境',
                    visibleItemCount: 5
    reactiveChoice choiceType: 'PT_CHECKBOX', 
        description: '选择需要清理的项目',
        filterLength: 1,
        filterable: false,
        name: 'HARBOR_PROJECTS',
        randomName: 'choice-parameter-5604612449190661',
        referencedParameters: 'HARBOR_REGION',
            script: groovyScript(
                fallbackScript: [classpath: [], oldScript: '', sandbox: false, script: ''],
                script: [classpath: [], oldScript: '', sandbox: false, script: '''
                    import groovy.json.JsonSlurper
                    switch(HARBOR_REGION) {
                      case "成都开发测试环境":
                        harborURL = "https://IAmIPaddress:8765"
                      break
                      case "上海生产环境":
                        harborURL = "https://harbor.betack.com"
                      break
                    }
                    // def apiURL =  "curl -s -k -u bfops:iAmPassword ${harborURL}/api/v2.0/projects?page_size=100"
                    // def pkgObject = apiURL.execute().text
                    def apiURL = harborURL + "/api/v2.0/projects?page_size=100"
                    def pkgObject = ["curl", "-s", "-k", "-u", "bfops:iAmPassword", apiURL].execute().text
                    def jsonSlurper = new JsonSlurper()
                    def artifactsJsonObject = jsonSlurper.parseText(pkgObject)
                    def retList = []
                    for (project in artifactsJsonObject) {
                      projectName = project['name']
                      if (projectName.indexOf('charts') == -1) {
                        retList.add(project['name'])
                      }
                    }
                    return retList.sort()
                '''])
  }

  stages {
    stage('获取保留镜像清单') {
      when {
        expression {
          return (params.HARBOR_PROJECTS != '')
        }
      }
      steps {
        script{
          // 从k8s-api-devops服务api接口获取各项目目前在成都、上海k8s集群中正在使用的镜像
          response = httpRequest(
            url: 'http://k8s-api-devops-in-svc:8080/api/k8s/pods/containers/all-images',
            authentication: '',
            contentType: 'APPLICATION_JSON',
            httpMode: 'GET'
          )
          if (response.status == 200) {
            holdImages = readJSON text: response.content
            for (image in holdImages['data']) {
              imageList = libTools.splitImage(image)
              def holdImagetagsList = []
              if (holdImagesMap.keySet().contains(imageList[2])) {
                holdImagetagsList = holdImagesMap.get(imageList[2])[0]
                holdImagetagsList.add(imageList[-1])
              } else {
                holdImagetagsList.add(imageList[-1])
              }
              holdImagesMap.put(imageList[2], [holdImagetagsList, imageList])
            }
            println(holdImagesMap)
          } else {
            error "获取需要保留的镜像清单失败！！！"
          }
        }
      }
    }

    stage('清理选定项目的镜像') {
      when {
        expression {
          return (params.HARBOR_PROJECTS != '')
        }
      }
      steps {
        script{
          AUTHENTICATION = 'bedb7a7e-7628-47cd-b320-aa20f88588e2'
          switch(params.HARBOR_REGION) {
            case "成都开发测试环境":
              harborURL = "https://bf-harbor.betack.com:8765"
              harborAddr = office_registry
            break
            case "上海生产环境":
              harborURL = "https://harbor.betack.com"
              harborAddr = shanghai_inner_registry
            break
          }

          for (projectItem in params.HARBOR_PROJECTS.tokenize(',')) {
            switch(projectItem) {
              // 排除清理这些项目下的镜像
              case ['libs', 'qa-smoke', 'sfinx', 'library', 'bf-devops', 'bf-docs']:
                continue
              break
            }
            println("清理项目：" + projectItem)
            repositorys = readJSON text: httpRequestFunc("${harborURL}/api/v2.0/projects/${projectItem}/repositories?page_size=0",
                                                          AUTHENTICATION, '', 'GET')
            for (repository in repositorys['name']) {
              println("镜像repository：" + repository)
              tRepository = repository
              repository = repository.replaceAll(projectItem + "/", "").replaceAll("/", "%252F")
              // 查询返回结果为push_time升序，需要保留的结果为列表最后部分
              artifacts = readJSON text: httpRequestFunc("${harborURL}/api/v2.0/projects/${projectItem}/repositories/${repository}/artifacts?page_size=0&sort=push_time",
                                                          AUTHENTICATION, '', 'GET')
              println("镜像repository：" + tRepository + "镜像制品总数量为：" + artifacts.size())
              // 镜像制品数小于等于retainTagsNum不镜像清理
              if (artifacts.size() <= retainTagsNum) {
                println("镜像制品数量小于" + retainTagsNum + "个，不执行清理任务")
                continue
              }
              def holdtags = []
              def k8sUsedArtifacts = []
              // println(holdImagesMap.keySet())
              if (holdImagesMap.keySet().contains(tRepository)) {
                k8sUsedArtifacts = holdImagesMap.get(tRepository)[0]
              }
              println("k8s集群正在使用的镜像tags：" + k8sUsedArtifacts)
              println("k8s正在使用的tags合计为：" + k8sUsedArtifacts.size())
              holdtags = holdtags + k8sUsedArtifacts
              if (k8sUsedArtifacts.size() < retainTagsNum) {
                // 取最新的部分，凑足retainTagsNum个
                def dValue = retainTagsNum - k8sUsedArtifacts.size()
                println("截取保留用的索引值：-" + dValue)
                for (item in artifacts[-dValue..-1]) {
                  println("增加需要保留的镜像：" + item['tags'][0]['name'])
                  holdtags.add(item['tags'][0]['name'])
                }
              }
              println("保留的镜像tags：" + holdtags)
              println("保留的镜像tags总数为：" + holdtags.size())
              for (artifact in artifacts) {
                // 一个镜像有多个tags的，只取1个
                imagetagName = artifact['tags'][0]['name']
                pushTime = artifact['push_time']
                def imagePushTime = sh(returnStdout: true, script: "date -d ${pushTime} +%s" ).trim()
                def currentTime = sh(returnStdout: true, script: "date +%s").trim()
                // 保留镜像
                if (currentTime.toLong() - imagePushTime.toLong() <= 604800) {
                  println("镜像推送时间小于7天，不能删除")
                  continue
                }
                if (holdtags.contains(imagetagName)) {
                  println("镜像tags：" + imagetagName + "需要保留")
                  continue
                }
                // 删除镜像
                response = httpRequestFunc("${harborURL}/api/v2.0/projects/${projectItem}/repositories/${repository}/artifacts/${imagetagName}",
                              AUTHENTICATION, '', 'DELETE')
                /*
                response = httpRequest(
                  url: "${harborURL}/api/v2.0/projects/${projectItem}/repositories/${repository}/artifacts/${imagetagName}",
                  authentication: AUTHENTICATION,
                  contentType: 'APPLICATION_FORM',
                  httpMode: 'DELETE'
                )
                if (response.status == 200) {
                */
                if (response == 200) {
                  println("删除镜像：" + harborAddr + '/' + tRepository + ':' + imagetagName + " 成功。")
                } else {
                  println("删除镜像：" + harborAddr + '/' + tRepository + ':' + imagetagName + " 失败！！！")
                  echo "Request failed with status code: ${response.status}"
                  echo "Response content: ${response.content}"
                }
                // error "调试退出！"
              }
            }
          }
        }
      }
    }
  }
}

