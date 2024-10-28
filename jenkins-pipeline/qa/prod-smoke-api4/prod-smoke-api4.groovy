#!groovy
/*
  QA 冒烟测试
*/
// 公共
def office_registry = "IAmIPaddress"
def hwcloud_registry = "harbor.betack.com"

// 项目
def project = "qa-smoke"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/betack-test/backend-ft.git"

// 认证
def harbor_auth = "443eb7ee-c21b-4e32-b449-e01d83171672"
def git_auth = "41dfa7f4-d5f9-4892-a8ba-6b0d31a7cd84"


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
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '30')  // 设置保留30天的构建历史，或者30次的构建记录
  }

  parameters {
    gitParameter branch: '',
                 branchFilter: '.*',
                 defaultValue: 'api4_prod_smoke',
                 listSize: '10',
                 description: '选择需要发布的代码分支',
                 name: 'BRANCH_TAG',
                 quickFilterEnabled: true,
                 selectedValue: 'NONE',
                 sortMode: 'NONE',
                 tagFilter: '*',
                 type: 'PT_BRANCH',
                 useRepository: "${git_address}"
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将会把镜像同步推送一份到HARBOR镜像仓库: harbor.betack.com',
                 name: 'EXTRANET_HARBOR'
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'prod-smoke-api4',
                   visibleItemCount: 6
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'qa-smoke-prod-1',
                   visibleItemCount: 10
  }

  stages {
    stage('拉取代码') {
      steps {
        echo '正在拉取代码...'
        script {
          echo '拉取冒烟测试的代码'
          sh '[ -d smoke_code ] || mkdir smoke_code'
          dir("${env.WORKSPACE}/smoke_code") {
            try {
              checkout([$class: 'GitSCM',
                branches: [[name: "${params.BRANCH_TAG}"]],
                browser: [$class: 'GitLab', repoUrl: ''],
                extensions: [[$class: 'CloneOption', depth: 1, noTags: false, reference: '', shallow: true, timeout: 30]],
                userRemoteConfigs: [[credentialsId: "${git_auth}", url: "${git_address}"]]])
            } catch(Exception err) {
              echo err.getMessage()
              echo err.toString()
              unstable '拉取代码失败'
            }
            // 获取提交代码的ID，用于打包容器镜像
            env.GIT_COMMIT_MSG = sh (script: 'git rev-parse --short HEAD', returnStdout: true).trim()
          }

          // 创建拉取jenkins devops代码用的临时目录
          echo '拉取 jenkins devops 中保存的基础文件'
          sh '[ -d temp_jenkins_workspace ] || mkdir temp_jenkins_workspace'
          dir("${env.WORKSPACE}/temp_jenkins_workspace") {
            try {
              checkout([$class: 'GitSCM',
                branches: [[name: "main"]],
                browser: [$class: 'GitLab', repoUrl: ''],
                extensions: [[$class: 'CloneOption', depth: 1, noTags: false, reference: '', shallow: true, timeout: 30]],
                userRemoteConfigs: [[credentialsId: "${git_auth}", url: "ssh://git@code.betack.com:4022/devops/jenkins.git"]]])
            } catch(Exception err) {
              echo err.getMessage()
              echo err.toString()
              unstable '拉取jenkins代码失败'
            }
            sh 'pwd; ls -lh'
          }
        }
      }
    }

    stage('构建镜像上传HARBOR仓库') {
      steps {
        script{
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            // 创建构建docker镜像用的临时目录
            sh """
              [ -d temp_docker_build_dir ] || mkdir temp_docker_build_dir
              cd smoke_code && git archive -o ${env.WORKSPACE}/temp_docker_build_dir/backend-ft.tar.gz HEAD
              cd ${env.WORKSPACE}
              cp temp_jenkins_workspace/qa/prod-smoke-api4/deployToK8s/runner_api4.sh temp_docker_build_dir/
            """
            echo "构建服务：${deploy_svc_name} 的docker镜像"
            dir("${env.WORKSPACE}/temp_docker_build_dir") {
              docker_file = """
                FROM ${office_registry}/qa-smoke/jdk-smoke-mvn:v1-jdk11-tools
                LABEL maintainer="colin" version="1.0" datetime="2023-04-28"

                ## 冒烟测试
                ADD backend-ft.tar.gz /opt/betack/backend-ft
                COPY runner_api4.sh /opt/betack/
                WORKDIR /opt/betack
                RUN chmod 755 /opt/betack/runner_api4.sh
              """.stripIndent()

              writeFile file: 'Dockerfile', text: "${docker_file}", encoding: 'UTF-8'
              sh '''
                pwd; ls -lh
                cat Dockerfile
              '''

              withCredentials([usernamePassword(credentialsId: "${harbor_auth}", passwordVariable: 'password', usernameVariable: 'username')]) {
                image_name = "${office_registry}/${project}/${deploy_svc_name}:${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                sh """
                  docker login -u ${username} -p '${password}' ${office_registry}
                  docker image build -t ${image_name} -f Dockerfile .
                  docker image push ${image_name}
                """

                // 推送镜像到hwcould仓库：harbor.betack.com
                def _EXTRANET_HARBOR = Boolean.valueOf("${params.EXTRANET_HARBOR}")
                if (_EXTRANET_HARBOR) {
                  extranet_image_name = "${hwcloud_registry}/${project}/${deploy_svc_name}:${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                  sh """
                    docker login -u ${username} -p '${password}' ${hwcloud_registry}
                    docker image tag ${image_name} ${extranet_image_name}
                    docker image push ${extranet_image_name}
                    docker image rm ${extranet_image_name}
                  """
                }
              }

              // 镜像打包后，清理jar包，减少docker build上下文，清理构建环境的镜像节约磁盘空间
              sh """
                docker image rm ${image_name}
              """
            }

            sh "rm -rf temp_docker_build_dir/backend-ft"
          }
        }
      }
    }

    stage('部署服务') {
      steps {
        script {
          // helm登录harbor仓库
          echo "helm登录registry仓库"
          withCredentials([usernamePassword(credentialsId: "${harbor_auth}", passwordVariable: 'password', usernameVariable: 'username')]) {
            sh """
              export HELM_EXPERIMENTAL_OCI=1
              helm registry login ${hwcloud_registry} --username ${username} --password ${password}
            """
          }

          // 循环处理需要部署的服务
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            // 循环处理需要部署的命名空间
            for (namespaces in params.DEPLOY_TO_ENV.tokenize(',')) {
              echo "用HELM部署服务: ${deploy_svc_name}到命名空间：${namespaces}"
              println(namespaces)
              helm_values_file="temp_jenkins_workspace/qa/prod-smoke-api4/deployToK8s/${deploy_svc_name}-values.yaml"
              sh """
                pwd
                ls -lh ${helm_values_file}
                cp ${helm_values_file} .
                ls -lh ${deploy_svc_name}-values.yaml
              """

              // [bf-bsc, staging-1, staging1]
              config_env = splitNamespaces(namespaces)
              CONFIG_ENV_PREFIX = config_env[0]
              CONFIG_ENV_SUFFIX = config_env[1]
              CONFIGENV = config_env[2]
              K8S_AUTH = '1a4581df-f0e0-4b5f-9009-ec36d749aedd'
              K8S_NODEIP = 'IAmIPaddress'
              configFileProvider([configFile(fileId: "${K8S_AUTH}", targetLocation: "kube-config.yaml")]){
                chart_version = '0.1.4'
                k8s_rs_type = 'deploy'
                image_tag = "${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                doDeployToK8s(namespaces, deploy_svc_name, image_tag, project, chart_version)
              }
            }
          }
        }
      }
    }
  }
}


def doDeployToK8s(deployToEnv, deploySVCName, imageTag, project, chartVersion) {
  echo "正在部署到 " + deployToEnv + " 环境."
  println("CONFIG_ENV：" + CONFIG_ENV_SUFFIX)
  println("项目简称，用于命名空间的前缀：" + CONFIG_ENV_PREFIX)

  sh """
    sed -i 's#IMAGE_TAG#${imageTag}#' ${deploySVCName}-values.yaml
    sed -i 's#NAMESPACEPREFIX#${CONFIG_ENV_PREFIX}#' ${deploySVCName}-values.yaml
  """

  sh "cat ${deploySVCName}-values.yaml"
  echo "正在执行helm命令，更新${deploySVCName}服务版本${imageTag}到${deployToEnv}环境."

  // 判断是否已经部署过
  def getDeployName = sh (script: "helm --kubeconfig kube-config.yaml -n ${deployToEnv} list -l name==${deploySVCName} -q", returnStdout: true).trim()
  println("getDeployName：" + getDeployName)
  println("deploySVCName：" + deploySVCName)
  if (getDeployName == deploySVCName) {
    echo "正在对服务${deploySVCName}进行升级"
    deployType = 'upgrade'
  } else {
    echo "正在对服务${deploySVCName}进行部署"
    deployType = 'install'
  }
  sh """
    helm pull oci://harbor.betack.com/libs-charts/bf-java-project-deploy-common --version ${chartVersion}
    helm --kubeconfig kube-config.yaml ${deployType} ${deploySVCName} -n ${deployToEnv} -f ${deploySVCName}-values.yaml bf-java-project-deploy-common-${chartVersion}.tgz
  """
}


def strRemoveDuplicate(str_command) {
  /*
    * 获取到的命令字符串内容去重
  */
  list0 = str_command.split()
  list1 = []
  for ( item in list0 ) {
    // println(item)
    if ( ! list1.contains(item)) {
        list1.add(item)
    }
  }

  str_ret = list1.join(" ")
  // println(str_ret)
  return str_ret
}


def jmxRemoveDuplicate(str_command, nodePort){
  /*
    * 去重jmx远程调试部分参数，因为helm chart包模板里面已经包含了
  */
  jmx_option = """-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=${nodePort} \
    -Dcom.sun.management.jmxremote.rmi.port=${nodePort} -Djava.rmi.server.hostname=IAmIPaddress \
    -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.authenticate=false \
    -Dcom.sun.management.jmxremote.local.only=false
  """
  // 去除多余的空格
  jmx_option = sh (script: "eval echo ${jmx_option}", returnStdout: true).trim()
  // println("jmx_option：" + jmx_option)
  ret_str_command = str_command.replaceAll(jmx_option,"")
  // println("ret_str_command: "+ ret_str_command)
  return ret_str_command
}


def delDeploySVCName(listDuplicateSVC, retainSVC){
  /*
    * 去掉 offline 与 api-master
    * listDuplicateSVC, 都是同一个jar包的服务名称列表，比如 rab-svc-api-app,rab-svc-api-app-master,rab-svc-offline-app 用的同一个jar包
    * retainSVC，需要保留的服务名称
  */
  _svc_list = []
  for (item in params.DEPLOY_SVC_NAME.tokenize(',')) {
    switch(item) {
      case listDuplicateSVC:
        svc_name = retainSVC
      break
      default:
        svc_name = item
      break
    }
    if ( ! _svc_list.contains(svc_name) ) {
      _svc_list.add(svc_name)
    }
  }
  return _svc_list
}


def getSVCNodePort(namespaces, deploySVCName, podPort, deployENVSuffix, svcNameSalt='out') {
  /* 
    获取指定服务的nodePort端口
  */
  try {
    svc_nodeport = sh (script: """
      kubectl --kubeconfig=kube-config.yaml -n ${namespaces} get svc ${deploySVCName}-${svcNameSalt}-${deployENVSuffix} -o jsonpath='{.spec.ports[?(@.port==${podPort})].nodePort}'
      """, returnStdout: true).trim()
    println("${deploySVCName} 的容器内端口 ${podPort} 对应的 NodePort：" + svc_nodeport)
  } catch(Exception err) {
    svc_nodeport = ''
    echo "未获取 ${deploySVCName} service 的 nodePort 端口。"
  }
  return svc_nodeport
}


def splitNamespaces(namespaces) {
  /*
   * jic-staging-1 or bf-bsc-staging-1
   * 返回结果：[bf-bsc, staging-1, staging1]
  */
  t_list = namespaces.tokenize('-')
  config_env = t_list[-2] + '-' + t_list[-1]
  // println config_env
  config_env_prefix = namespaces.replaceAll('-' + config_env, "")
  configenv = config_env.replaceAll('-', "")
  return [config_env_prefix, config_env, configenv]
}


