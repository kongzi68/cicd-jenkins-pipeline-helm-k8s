#!groovy
// 公共
def registry = "harbor.betack.com"
def office_registry = "IAmIPaddress:8765"
// 认证
// def secret_name = "bf-harbor"
def harbor_auth = "443eb7ee-c21b-4e32-b449-e01d83171672"
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
            image: "${office_registry}/libs/ubuntu-jenkins-agent:latest-nofrontend"
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
          - name: yapi
            image: "${office_registry}/libs/yapi-export-tools:v2-zh"
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
            image: "${office_registry}/libs/alpine:tools-sshd"
            imagePullPolicy: Always
            tty: true
            env:
              - name: "file.encoding"
                value: "UTF-8"
              - name: "LANG"
                value: "zh_CN.UTF-8"
              - name: "sun.jnu.encoding"
                value: "UTF-8"
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
    _imagetag = createVersion()
    _version = "接口文档"
  }


  parameters {
    booleanParam defaultValue: false,
                 description: "1.勾选此选项后，将通过token获取最新的项目ID和名称信息; 2.在你没有通知让运维同学修改yapi-project-token-list配置文件的情况下，不用勾选此选项; 3.如果发现没有你需要导出的项目，请通知运维同学添加。",
                 name: 'isGetInitEnv'
    string description: '请输入项目的字母简称，该简称用于打包docker镜像，例如：华泰二期，即输入：htsc-2',
           name: 'PROJECT_IMAGE_NAME'
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
    extendedChoice description: '部署到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'hwcloud-IAmIPaddress',
                   visibleItemCount: 7
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
      steps {
        container('jnlp') {
          script {
            // Load the list into a variable
            env.LIST = readFile("${WORKSPACE}/temp_yapi_project_list.txt")
            echo "${env.LIST}"
            env.RELEASE_SCOPE = input message: '请选择需要导出的项目文档', ok: '确定',
                    parameters: [extendedChoice(
                        description: '请勾选需要导出的YAPI项目文档',
                        name: 'PROJECT_LIST',
                        defaultValue: "${env.BUILD_ARCHS}",
                        multiSelectDelimiter: ',',
                        type: 'PT_SINGLE_SELECT',
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
                  java -Dfile.encoding=utf-8 -jar yapi-1.0-SNAPSHOT.jar -h https://yapi.betack.com \
                  -e IamUserName@IamUserName.com -p 4M5Pt8eaSy -pid ${projectID} -o temp-yapi-export-file -t html -s script.groovy
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
              configFileProvider([configFile(fileId: "bf00105b-50ad-4791-92b2-d5f0431d217a", targetLocation: "ansible-hosts")]){
                for(String project : exportProjectList) {
                  // echo "${project}"
                  oneProjectInfo = project.tokenize('|')
                  // echo "${oneProjectInfo}"
                  projectName = oneProjectInfo[2].replaceAll("\\s","")
                  echo "复制 ${projectName}-${_version}.html 到服务器 IAmIPaddress"
                  sh """
                    ansible --inventory-file ansible-hosts yapi_file_servers -u betack -m copy -a \
                    "src=${projectName}-${_version}.html dest=/data1t/betack-update-server/docs/"
                  """
                }
              }
            }
            echo "导出完成，请到 http://ci.betack.com:61888/docs/ 下载文件。"
          }
        }
      }
    }

    stage('把文档打包到nginx镜像') {
      stages {
        stage('创建Dockerfile') {
          steps {
            // 这里后续，可能会因为打包命令不同，复制打包后的代码文件路径也不同
            sh """
              pwd; ls -lh
              echo '''
                FROM IAmIPaddress:8765/libs/nginx:1.21.1
                LABEL maintainer="colin" version="1.0" datetime="2022-04-26"
                RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
                    echo "Asia/Shanghai" > /etc/timezone
                COPY bf-docs/yapi-export-tools/nginx/nginx.conf /etc/nginx/nginx.conf
                COPY bf-docs/yapi-export-tools/nginx/web.conf /etc/nginx/conf.d/web.conf
                COPY ${projectName}-${_version}.html /var/www/html/index.html
                RUN mkdir /var/www/chkstatus && echo "this is test, one!" > /var/www/chkstatus/tt.txt
                EXPOSE 80
                WORKDIR /var/www
              ''' > Dockerfile
            """
          }
        }
        stage('构建镜像，并上传到harbor仓库') {
          steps {
            container('podman') {
              script {
                withCredentials([usernamePassword(credentialsId: "${harbor_auth}", passwordVariable: 'password', usernameVariable: 'username')]) {
                  image_name = "${registry}/yapi-docs/${params.PROJECT_IMAGE_NAME}:v${_imagetag}"
                  sh """
                    pwd; ls -lh
                    podman login -u ${username} -p '${password}' ${registry}
                    podman image build -t ${image_name} .
                    podman image push ${image_name}
                  """
                  echo "需要部署到docker的镜像：${image_name}"
                }
              }
            }
          }
        }

        // harbor.od.com/yapi-docs/htsc-2:v20220518-11
        // harbor.betack.com:8443/yapi-docs/htsc-2:v20220518-11

        stage('用docker方式启动服务') {
          steps {
            container('ansible') {
              echo '正在用ansible远程部署服务...'
              script {
                // 循环处理需要部署的环境
                // 注意，需要提前用命令：ssh-copy-id -i /iamusername/.ssh/id_rsa_jenkins.pub betack@ipaddress 进行免密认证
                sshagent (credentials: ['830e90a8-1fec-4a45-9317-415e7acaff10']) {
                  def env_list = []
                  for (deploy_to_env in params.DEPLOY_TO_ENV.tokenize(',')) {
                    ip_address = deploy_to_env.tokenize('-')[-1]
                    env_list.add(ip_address)
                  }
                  dest_hosts = env_list.join(',')
                  println(dest_hosts)

                  // 匹配部署的端口，新增的话，需要添加指定的端口
                  switch("${projectID}") {
                    case '153':
                      // 华泰二期 yapi ID 153
                      deploy_port = 34567
                    break
                  }

                  sh """
                    echo '创建ansible-playbook用的文件docker-deploy.yml'
                    echo '''
    - name: deploy ${params.PROJECT_IMAGE_NAME} yapi docs
      hosts: "{{ target }}"
      gather_facts: no
      tasks:
        - name: Restart a container
          docker_container:
            name: ${params.PROJECT_IMAGE_NAME}-yapi-docs
            image: harbor.betack.com/yapi-docs/${params.PROJECT_IMAGE_NAME}:v${_imagetag}
            state: started
            restart: yes
            ports:
            # Publish container port 80 as host port 34567
            # host-port:container-port
            - "${deploy_port}:80"
                    ''' > docker-deploy.yml
                  """
                  configFileProvider([configFile(fileId: "bf00105b-50ad-4791-92b2-d5f0431d217a", targetLocation: "ansible-hosts")]){
                    sh """
                      cat docker-deploy.yml
                      echo '远程启动docker容器.'
                      echo '等待30~90秒，让镜像从office同步到华为云cloud'
                      # 根据网络情况，同步大约要60秒
                    """
                    try {
                      // 重试3次，每次间隔30秒
                      retry(3) {
                        sleep 30
                        sh """
                          ansible-playbook --inventory-file ansible-hosts -e "{target: ${dest_hosts}}" -i "${dest_hosts}," -u betack docker-deploy.yml
                        """
                      }
                    }catch (Exception err) {
                      println err
                    }
                  }
                }
                echo "文档：${params.PROJECT_IMAGE_NAME}，在浏览器里面输入访问地址：http://${dest_hosts}:${deploy_port}"
              }
            }
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
      response = httpRequest "https://yapi.betack.com/api/project/get?token=${token}"
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
    // return new Date().format('yyyyMMdd')
    return new Date().format('yyyyMMdd') + "-${env.BUILD_ID}"
}
