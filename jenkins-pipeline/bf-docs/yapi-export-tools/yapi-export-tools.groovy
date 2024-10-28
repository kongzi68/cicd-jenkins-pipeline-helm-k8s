#!groovy
// 公共
def registry = "harbor.od.com"
// 认证
// def secret_name = "bf-harbor"
// def harbor_auth = "443eb7ee-c21b-4e32-b449-e01d83171672"
// def git_auth = "41dfa7f4-d5f9-4892-a8ba-6b0d31a7cd84"
// 定义yapi管理控制台查看到的项目枚举
/*
def betackYapiDocs = [12: 'rab-api',
                        45: 'wms-财富管理系统',
                        113: '**tougu',
                        81: 'CMBC-招商财富']

def project_list = doGetString(betackYapiDocs)
*/

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
            image: "${registry}/libs/yapi-export-tools:v2-zh"
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
    // skipDefaultCheckout true          // 忽略默认的checkout
    skipStagesAfterUnstable()         // 忽略报错后面的步骤
    //retry(2)                        // 重试次数
    timestamps()                      // 添加时间戳
    timeout(time: 20, unit:'MINUTES') // 设置此次发版运行20分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '',
                              artifactNumToKeepStr: '',
                              daysToKeepStr: '30',
                              numToKeepStr: '30')  // 设置保留30天的构建历史，或者30次的构建记录
  }

  environment {
    // _version = createVersion()
    _version = "接口文档"
  }


  parameters {
    booleanParam defaultValue: false,
                 description: "1.勾选此选项后，将通过token获取最新的项目ID和名称信息; 2.在你没有通知让运维同学修改yapi-project-token-list配置文件的情况下，不用勾选此选项; 3.如果发现没有你需要导出的项目，请通知运维同学添加。",
                 name: 'isGetInitEnv'
    /*
    extendedChoice description: '请选择需要导出的项目文档',
                  multiSelectDelimiter: ',',
                  name: 'PROJECT_LIST',
                  quoteValue: false,
                  saveJSONParameterToFile: false,
                  type: 'PT_CHECKBOX',
                  value: env.LIST,
                  visibleItemCount: 100
    */
  }


  stages {
    stage('初始化准备') {
      steps {
        container('jnlp') {
          script {
            // 当Finished: ABORTED时，即终止后
            // 当中止后，把文件 temp_yapi_project_list.txt 删了
            // 此时：files的值还是中止那次运行的结果，会导致报错。
            def files = findFiles(glob: "temp_yapi_project_list.txt")
            boolean exists = files.length > 0
            if(exists) {
              println("当前时间戳：" + currentBuild.startTimeInMillis) 
              println("文件temp_yapi_project_list.txt的最后修改时间戳：" + files[0].lastModified)
              def isLongTimeNoUpdate = files && currentBuild.startTimeInMillis - files[0].lastModified > 604800000
              def _isGetInitEnv = Boolean.valueOf("${params.isGetInitEnv}")
              println("isGetInitEnv：" + _isGetInitEnv)
              // 当勾选了isGetInitEnv时需要更新，或 isLongTimeNoUpdate 超过7天也需要更新
              if(_isGetInitEnv == true || isLongTimeNoUpdate == true) {
                // 获取到需要导出的项目清单
                tempProjectList = doGetString(doGetIDByToken())
                // echo "${tempProjectList}"
                // 把清单写入到临时文件，作为下次创建多选框用
                writeFile file: 'temp_yapi_project_list.txt', text: "${tempProjectList}", encoding: 'UTF-8'
              }
            } else {
              // 文件不存在时更新
              // 获取到需要导出的项目清单
              tempProjectList = doGetString(doGetIDByToken())
              // echo "${tempProjectList}"
              // 把清单写入到临时文件，作为下次创建多选框用
              writeFile file: 'temp_yapi_project_list.txt', text: "${tempProjectList}", encoding: 'UTF-8'
            }
          }
        }
      }
    }

    stage('选择导出项目') {
      options {
          timeout(time: 5, unit: 'MINUTES') 
      }
      steps {
        container('jnlp') {
          script {
            // Load the list into a variable
            env.LIST = readFile("${WORKSPACE}/temp_yapi_project_list.txt")
            echo "${env.LIST}"
            env.RELEASE_SCOPE = input message: '请选择需要导出的项目文档', ok: '确定',
                    parameters: [extendedChoice(
                    description: '请勾选需要导出的YAPI项目文档，可多选',
                    name: 'PROJECT_LIST',
                    defaultValue: "${env.BUILD_ARCHS}",
                    multiSelectDelimiter: ',',
                    type: 'PT_CHECKBOX',
                    value: env.LIST,
                    visibleItemCount: 50
              )]
            echo "被选择导出的项目为: ${env.RELEASE_SCOPE}"
          }
        }
      }
    }

    stage('导出项目文档') {
      steps {
        // 在 yapi 容器中执行导出项目文档
        container('yapi') {
          script {
            // sh 'pwd; ls -lh'
            sh """
              java -version
              cd /opt/betack/yapi-tools
              ls -lh
            """
            def exportProjectList = "${env.RELEASE_SCOPE}".split(',')
            for(String project : exportProjectList) {
              // echo "${project}"
              oneProjectInfo = project.tokenize('|')
              // echo "${oneProjectInfo}"
              projectID = oneProjectInfo[1].replaceAll("\\s","")
              projectName = oneProjectInfo[2].replaceAll("\\s","")
              echo "开始导出，项目ID：${projectID}, 项目名称：${projectName} 的文档"
              // 调用 yapi 容器中的 java 命令，启动导出工具
              // 在容器里面运行，一定要指定jar包启动编码为UTF-8，否则导出内容中文变乱码
              sh """
                java -version
                cd /opt/betack/yapi-tools/dist && {
                  java -Dfile.encoding=utf-8 -jar yapi-1.0-SNAPSHOT.jar -h http://ci.betack.com:61368 \
                  -e IamUserName@IamUserName.com -p iampassword -pid ${projectID} -o temp-yapi-export-file -t html -s script.groovy
                  mv temp-yapi-export-file.html ${WORKSPACE}/${projectName}-${_version}.html
                  ls -lh ${WORKSPACE}/${projectName}-${_version}.html
                }
              """
            }
          }
        }
      }
    }

    stage('复制文档到 IAmIPaddress') {
      steps {
        container('ansible') {
          script {
            sh 'pwd; ls -lh'
            def exportProjectList = "${env.RELEASE_SCOPE}".split(',')
            sshagent (credentials: ['830e90a8-1fec-4a45-9317-415e7acaff10']) {
              for(String project : exportProjectList) {
                // echo "${project}"
                oneProjectInfo = project.tokenize('|')
                // echo "${oneProjectInfo}"
                projectName = oneProjectInfo[2].replaceAll("\\s","")
                echo "复制 ${projectName}-${_version}.html 到服务器 IAmIPaddress"
                sh """
                  ansible all -i "IAmIPaddress," -u betack -m copy -a \
                  "src=${projectName}-${_version}.html dest=/data1t/betack-update-server/docs/"
                  [ \$? -eq 0 ] && rm ${projectName}-${_version}.html -f
                """
              }
            }
            echo "导出完成，请到服务器 IAmIPaddress 下载文件。"
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
      t_str = "$i | $key | $value"
    } else {
      t_str = "$i | $key | $value,"
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


def createVersion() {
    // 定义一个版本号作为当次构建的版本，输出结果 20191210175842_69
    // return new Date().format('yyyyMMdd') + "-${env.BUILD_ID}"
    return new Date().format('yyyyMMdd')
}
