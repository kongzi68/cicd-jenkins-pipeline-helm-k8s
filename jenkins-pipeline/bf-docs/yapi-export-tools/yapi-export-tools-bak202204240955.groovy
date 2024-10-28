#!groovy
import hudson.model.*
import hudson.util.*
import hudson.FilePath
import jenkins.model.Jenkins
import hudson.EnvVars
import hudson.security.Permission


// 公共
def registry = "harbor.od.com"
// 认证
def secret_name = "bf-harbor"
def harbor_auth = "443eb7ee-c21b-4e32-b449-e01d83171672"
def git_auth = "41dfa7f4-d5f9-4892-a8ba-6b0d31a7cd84"
// 定义yapi管理控制台查看到的项目枚举
def betackYapiDocs = [12: 'rab-api',
                        45: 'wms-财富管理系统',
                        113: '**tougu',
                        81: 'CMBC-招商财富']


// def project_list = doGetString(betackYapiDocs)
def tempProjectList = ""


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
            image: "${registry}/libs/ubuntu-jenkins-agent:jdk17-nogradle"
            imagePullPolicy: Always
            resources:
              limits: {}
              requests:
                memory: "4000Mi"
                cpu: "1000m"
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
          - name: yapi
            image: "${registry}/libs/yapi-export-tools:v1"
            imagePullPolicy: Always
            tty: true
            env:
              - name: "file.encoding"
                value: "UTF-8"
              - name: "LANG"
                value: "zh_CN.UTF-8"
              - name: "sun.jnu.encoding"
                value: "UTF-8"
          - name: ansible
            image: "${registry}/libs/alpine-ansible:3.15.4"
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
          imagePullSecrets:
          - name: bf-harbor
          volumes:
            - name: docker-cmd
              hostPath:
                path: /usr/bin/docker
            - name: docker-sock
              hostPath:
                path: /var/run/docker.sock
      """.stripIndent()
    }
  }



  // 设置pipeline Jenkins选项参数
  options {
    skipDefaultCheckout true          // 忽略默认的checkout
    skipStagesAfterUnstable()         // 忽略报错后面的步骤
    //retry(2)                        // 重试次数
    timestamps()                      // 添加时间戳
    timeout(time: 20, unit:'MINUTES') // 设置此次发版运行20分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '30')  // 设置保留30天的构建历史，或者30次的构建记录
  }

/*
    parameters {
      extendedChoice description: '请选择需要导出的项目文档',
                    multiSelectDelimiter: ',',
                    name: 'PROJECT_LIST',
                    quoteValue: false,
                    saveJSONParameterToFile: false,
                    type: 'PT_CHECKBOX',
                    value: env.LIST,
                    visibleItemCount: 100
    }
*/

/*
  parameters {
    extendedChoice description: '请选择需要导出的项目文档',
                   multiSelectDelimiter: ',',
                   name: 'PROJECT_LIST',
                   quoteValue: false,
                   type: 'PT_CHECKBOX',
                   visibleItemCount: 100,
                   groovyScript: """
                   import java.io.File
                   filename = "${env.WORKSPACE}/temp_yapi_project_list.txt"
                   File file = new File(filename)
                   println file.text
                   return file.text
                   """
  }
*/


  stages {
    stage('环境准备') {
      steps {

        container('jnlp') {
          script {
            // 获取到需要导出的项目清单
            tempProjectList = doGetString(doGetIDByToken())
            echo "${tempProjectList}"

            // 把清单写入到临时文件，作为下次创建多选框用
            writeFile file: 'temp_yapi_project_list.txt', text: "${tempProjectList}", encoding: 'UTF-8'
            echo "jnlp"
            /*
            configFileProvider([configFile(fileId: 'cf7066c0-141a-48f6-8676-e287db4b0c18',
                                           targetLocation: 'yapi-project-token-list.txt',
                                           variable: 'YAPI项目token清单')]) {
              // sh "cat yapi-project-token-list.txt"
              echo "###########################"
              projectTokenList = readFile('yapi-project-token-list.txt')
              // echo "${projectTokenList}"
              for(token in projectTokenList.split('\n')) {
                def response = httpRequest "http://ci.betack.com:61368/api/project/get?token=${token}"
                // println response.content
                def retJson = readJSON text: response.content, returnPojo: true
                retJson.each { key, value ->
                  echo "Walked through key $key and value $value"
                }
                println retJson['data'].get('_id')
                println retJson['data'].get('name')
                betackYapiDocs2.put(retJson['data'].get('_id'), retJson['data'].get('name'))
              }
            }
            */


            // Load the list into a variable
            env.LIST = readFile("${WORKSPACE}/temp_yapi_project_list.txt")
            echo "${env.LIST}"

            env.RELEASE_SCOPE = input message: 'User input required', ok: 'Release!',
                    parameters: [extendedChoice(
                    name: 'PROJECT_LIST',
                    defaultValue: "${env.BUILD_ARCHS}",
                    multiSelectDelimiter: ',',
                    type: 'PT_CHECKBOX',
                    value: env.LIST
              )]


            echo "Release scope selected: ${env.RELEASE_SCOPE}"



          }
        }


        // 在 gradle 容器中编译
        container('ansible') {
          // 执行命令 gradle clean
          script {
            echo "ansible"
          }
        }
      }
    }

  }
  


}

// 把定义的yapi项目转成多选框可用的value
def doGetString(tMap) {
  temp_string = ""
  int map_size = tMap.size() - 1
  tMap.sort().eachWithIndex{ key, value, i ->
    if(map_size == i) {
      t_str = "$i|$key|$value"
    } else {
      t_str = "$i|$key|$value,"
    }
    temp_string += t_str
  }
  return temp_string
}

// 通过token获取项目的ID和name
def doGetIDByToken() {
  betackYapiDocs2 = [:]
  configFileProvider([configFile(fileId: 'cf7066c0-141a-48f6-8676-e287db4b0c18',
                                 targetLocation: 'yapi-project-token-list.txt',
                                 variable: 'YAPI项目token清单')]) {
    // sh "cat yapi-project-token-list.txt"
    echo "###########################"
    projectTokenList = readFile('yapi-project-token-list.txt')
    // echo "${projectTokenList}"
    for(token in projectTokenList.split('\n')) {
      response = httpRequest "http://ci.betack.com:61368/api/project/get?token=${token}"
      // println response.content
      retJson = readJSON text: response.content, returnPojo: true
      /*
      retJson.each { key, value ->
        echo "Walked through key $key and value $value"
      }
      println retJson['data'].get('_id')
      println retJson['data'].get('name')
      */
      betackYapiDocs2.put(retJson['data'].get('_id'), retJson['data'].get('name'))
    }
  }
  return betackYapiDocs2
}



