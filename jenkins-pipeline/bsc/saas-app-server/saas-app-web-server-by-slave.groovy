#!groovy
// 公共
def registry = "harbor.betack.com"
// 项目
def project = "bf-bsc"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/betack/saas-commons.git"
// 认证
// def secret_name = "bf-harbor"
def harbor_auth = "443eb7ee-c21b-4e32-b449-e01d83171672"
def git_auth = "41dfa7f4-d5f9-4892-a8ba-6b0d31a7cd84"

pipeline {
  agent {
    // 标签不能用这种方式？ label 'my-node1' || 'my-node2'
    // 试过不行，只能把所有slave节点都设置标签slave
    // 这样发版的时候，会分配到几台slave中的一台上
    label 'slave'
  }

  // 设置pipeline Jenkins选项参数
  options {
    skipDefaultCheckout true          // 忽略默认的checkout
    skipStagesAfterUnstable()         // 忽略报错后面的步骤
    //retry(2)                        // 重试次数
    timestamps()                      // 添加时间戳
    timeout(time: 30, unit:'MINUTES') // 设置此次发版运行30分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '30')  // 设置保留30天的构建历史，或者30次的构建记录
  }

  parameters {
    gitParameter branch: '',
                 branchFilter: '.*',
                 defaultValue: 'main',
                 description: '选择需要发布的代码分支',
                 name: 'BRANCH_TAG',
                 quickFilterEnabled: true,
                 selectedValue: 'NONE',
                 sortMode: 'NONE',
                 tagFilter: '*',
                 type: 'PT_BRANCH'
    extendedChoice defaultValue: '17',
                   description: '请选择 JDK 版本',
                   multiSelectDelimiter: ',',
                   name: 'JDK_VERSION',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_SINGLE_SELECT',
                   value: '8,11,17',
                   visibleItemCount: 5
    extendedChoice defaultValue: 'gradle-7.4.2',
                   description: '请选择 gradle 版本',
                   multiSelectDelimiter: ',',
                   name: 'GRADLE_VERSION',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_SINGLE_SELECT',
                   value: 'gradle-6.8.3,gradle-7.4.2',
                   visibleItemCount: 5
    booleanParam defaultValue: false,
                 description: '勾选此选项后，执行命令：gradle clean，清理构建环境。',
                 name: 'CLEAN_BUILD'
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'saas-app-server',
                   visibleItemCount: 8
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'bf-bsc-staging-1',
                   visibleItemCount: 7
  }

  stages {
    stage('拉取代码') {
      steps {
        echo '正在拉取代码...'
        script {
          try {
            checkout([$class: 'GitSCM',
              branches: [[name: "${params.BRANCH_TAG}"]],
              browser: [$class: 'GitLab', repoUrl: ''],
              userRemoteConfigs: [[credentialsId: "${git_auth}", url: "${git_address}"]]])
          } catch(Exception err) {
            echo err.getMessage()
            echo err.toString()
            unstable '拉取代码失败'
          }
          // 获取提交代码的ID，用于打包容器镜像
          env.GIT_COMMIT_MSG = sh (script: 'git rev-parse --short HEAD', returnStdout: true).trim()
        }
      }
    }

    stage('环境准备') {
      steps {
        echo "当前 java 版本信息如下："
        sh 'java -version'
        script {
          def javaVersion = sh "java --version | head -1 | awk -F '[ .]+' '{print \$2}'"
          if ("${params.JDK_VERSION}" != "${javaVersion}") {
            echo "开始切换 java 版本."
            try {
              sh """
                update-alternatives --install /usr/bin/java java /usr/lib/jvm/java-"${params.JDK_VERSION}"-openjdk-amd64/bin/java 1081
                update-alternatives --config java
                update-alternatives --set java /usr/lib/jvm/java-"${params.JDK_VERSION}"-openjdk-amd64/bin/java
                java -version
              """
              JDKVERSION = "${registry}/libs/amazoncorretto:${params.JDK_VERSION}"
            } catch(Exception err) {
              echo err.getMessage()
              echo err.toString()
              unstable '构建失败'
            }
          } else {
            echo "默认 JDK 版本为 ${javaVersion}"
            JDKVERSION = "${registry}/libs/amazoncorretto:${javaVersion}"
          }
        }
        // 执行命令 gradle clean
        script {
          def _CLEAN_BUILD = Boolean.valueOf("${params.CLEAN_BUILD}")
          if (_CLEAN_BUILD) {
            echo "执行命令：gradle clean，清理构建环境"
            sh """
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:/usr/local/games:/snap/bin:/opt/gradle/${params.GRADLE_VERSION}/bin
              export LANG=zh_CN.UTF-8
              export LC_ALL=zh_CN.UTF-8
              gradle clean
            """
          }
        }
      }
    }

    stage('代码编译打包') {
      steps {
        echo '正在构建...'
        script {
          // 循环构建？待验证测试速度问题
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            echo "当前正在构建服务：${deploy_svc_name}"
            JAR_PKG_NAME = "${deploy_svc_name}/build/libs/${deploy_svc_name}-0.2.1-SNAPSHOT.jar"
            gradle_options = "${deploy_svc_name}"
            try {
              sh """
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:/usr/local/games:/snap/bin:/opt/gradle/${params.GRADLE_VERSION}/bin
                export LANG=zh_CN.UTF-8
                export LC_ALL=zh_CN.UTF-8
                export -p
                gradle :${gradle_options}:bootJar
              """
              //
              echo "构建的jar包 ${deploy_svc_name} 信息如下："
              sh "ls -lh ${deploy_svc_name}/build/libs/"
              sh "ls -lh ${JAR_PKG_NAME}"
            } catch(Exception err) {
              echo err.getMessage()
              echo err.toString()
              unstable '构建失败'
            }
          }
        }
      }
    }

    stage('构建镜像') {
      steps {
        script {
          // 循环处理需要部署的服务
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            JAR_PKG_NAME = "${deploy_svc_name}/build/libs/${deploy_svc_name}-0.2.1-SNAPSHOT.jar"
            echo "当前需要使用包及路径：${JAR_PKG_NAME}"
            sh "ls -lh ${JAR_PKG_NAME}; pwd && ls -lh"
            def DEPLOY_JAR_PKG_NAME = "${deploy_svc_name}-${GIT_COMMIT_MSG}-${BUILD_NUMBER}.jar"
            stage('创建Dockerfile') {
              docker_file = """
                FROM ${JDKVERSION}
                LABEL maintainer="colin" version="1.0" datetime="2021-01-11"
                RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime
                RUN echo "Asia/Shanghai" > /etc/timezone
                COPY ${JAR_PKG_NAME} /opt/betack/${DEPLOY_JAR_PKG_NAME}
                WORKDIR /opt/betack            
              """.stripIndent()

              writeFile file: 'Dockerfile', text: "${docker_file}", encoding: 'UTF-8'
              sh '''
                pwd; ls -lh
                cat Dockerfile
              '''
            }

            stage('构建镜像，并上传到harbor仓库') {
              withCredentials([usernamePassword(credentialsId: "${harbor_auth}", passwordVariable: 'password', usernameVariable: 'username')]) {
                image_name = "${registry}/${project}/${deploy_svc_name}:${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                sh """
                  docker login -u ${username} -p '${password}' ${registry}
                  docker image build -t ${image_name} .
                  docker image push ${image_name}
                  docker image tag ${image_name} ${registry}/${project}/${deploy_svc_name}:latest
                  docker image push ${registry}/${project}/${deploy_svc_name}:latest
                  docker image rm ${image_name}
                  docker image rm ${registry}/${project}/${deploy_svc_name}:latest
                """
              }
            }
          }
        }
      }
    }

    stage('部署服务') {
      steps {
        script {
          // 循环处理需要部署的服务
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            stage('拉取values.yaml') {
              echo '正在从gitlab拉取helm更新部署用的values.yaml文件...'
              // 创建拉取jenkins devops代码用的临时目录
              sh '[ -d temp_jenkins_workspace ] || mkdir temp_jenkins_workspace'
              dir("${env.WORKSPACE}/temp_jenkins_workspace") {
                try {
                  checkout([$class: 'GitSCM',
                    branches: [[name: "main"]],
                    browser: [$class: 'GitLab', repoUrl: ''],
                    userRemoteConfigs: [[credentialsId: "${git_auth}", url: "ssh://git@code.betack.com:4022/devops/jenkins.git"]]])
                } catch(Exception err) {
                  echo err.getMessage()
                  echo err.toString()
                  unstable '拉取jenkins代码失败'
                }
                sh 'pwd; ls -lh'
              }
            }

            stage('用HELM部署服务') {
              for (deployToEnv in params.DEPLOY_TO_ENV.tokenize(',')) {
                sh """
                  pwd
                  ls -lh temp_jenkins_workspace/bsc/saas-app-server/${deploy_svc_name}-values.yaml
                  cp temp_jenkins_workspace/bsc/saas-app-server/${deploy_svc_name}-values.yaml .
                  ls -lh
                """
                configFileProvider([configFile(fileId: "1a4581df-f0e0-4b5f-9009-ec36d749aedd", targetLocation: "kube-config.yaml")]){
                  image_tag = "${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
                  doDeployToK8s(deployToEnv, deploy_svc_name, image_tag, project, '0.1.2')
                }
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
    sh """
      sed -i 's#IMAGE_TAG#${imageTag}#' ${deploySVCName}-values.yaml
      cat ${deploySVCName}-values.yaml
      echo "正在执行helm命令，更新${deploySVCName}服务版本${imageTag}到${deployToEnv}环境."
      helm version
    """
    // 判断是否已经部署过
    def getDeployName = sh (script: "helm --kubeconfig kube-config.yaml -n ${deployToEnv} list -f ${deploySVCName} -q", returnStdout: true).trim()
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
      helm --kubeconfig kube-config.yaml ${deployType} ${deploySVCName} -n ${deployToEnv} -f ${deploySVCName}-values.yaml \
        oci://harbor.betack.com/${project}-charts/${deploySVCName} --version ${chartVersion}
    """
}
