#!groovy
// 公共
def office_registry = "IAmIPaddress"
def hwcloud_registry = "harbor.betack.com"

// 项目
// 公式集成测试项目,不知道划分到哪个项目，就直接归类为betack
def project = "betack"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/betack/formula-integration.git"

// 认证
// def secret_name = "bf-harbor"
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
            image: "${office_registry}/libs/ubuntu-jenkins-agent:jdk17-nogradle"
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
    timeout(time: 30, unit:'MINUTES') // 设置此次发版运行30分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '30')  // 设置保留30天的构建历史，或者30次的构建记录
  }

  parameters {
    gitParameter branch: '',
                 branchFilter: '.*',
                 defaultValue: 'main',
                 listSize: '10',
                 description: '选择需要发布的代码分支',
                 name: 'BRANCH_TAG',
                 quickFilterEnabled: true,
                 selectedValue: 'NONE',
                 sortMode: 'NONE',
                 tagFilter: '*',
                 type: 'PT_BRANCH',
                 useRepository: "${git_address}"
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
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将会把镜像同步推送一份到HARBOR镜像仓库: harbor.betack.com',
                 name: 'EXTRANET_HARBOR'
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'svc-api-app,svc-data-etl,svc-data-migration',
                   visibleItemCount: 8
    string description: 'svc-data-migration 参数。多个参数用空格分隔，【注意】每次都会默认清空上一次变量参数，如果要保持上一次变量参数，请重新填写',
           name: 'MIGRATION_PARAMETER',
           trim: true
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'IAmIPaddress',
                   visibleItemCount: 7
    booleanParam defaultValue: false,
                 description: '勾选此选项后，将开启jmxremote远程调试功能',
                 name: 'JMXREMOTE'
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
              JDKVERSION = "${office_registry}/libs/amazoncorretto:${params.JDK_VERSION}"
            } catch(Exception err) {
              echo err.getMessage()
              echo err.toString()
              unstable '构建失败'
            }
          } else {
            echo "默认 JDK 版本为 ${javaVersion}"
            JDKVERSION = "${office_registry}/libs/amazoncorretto:${javaVersion}"
          }
        }

        // 执行命令 gradle clean
        script {
          def _CLEAN_BUILD = Boolean.valueOf("${params.CLEAN_BUILD}")
          if (_CLEAN_BUILD) {
            echo "执行命令：gradle clean，清理构建环境"
            sh """
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/gradle/${params.GRADLE_VERSION}/bin
              export LANG=zh_CN.UTF-8
              export LC_ALL=zh_CN.UTF-8
              export GRADLE_USER_HOME="${WORKSPACE}/.gradle"
              gradle --no-daemon -g "${WORKSPACE}/.gradle" clean
            """
          }
        }
      }
    }

    stage('代码编译打包') {
      steps {
        echo '正在构建...'
        script {
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            echo "当前正在构建服务：${deploy_svc_name}"
            JAR_PKG_NAME = "${deploy_svc_name}/build/libs/${deploy_svc_name}-0.0.1-SNAPSHOT.jar"
            DEPLOY_JAR_PKG_NAME = "${deploy_svc_name}-${GIT_COMMIT_MSG}-${BUILD_NUMBER}.jar"
            try {
              // sh "gradle rab-svc-migrate-to-beyond:bootJar -x test"
              sh """
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:/usr/local/games:/snap/bin:/opt/gradle/${params.GRADLE_VERSION}/bin
                export LANG=zh_CN.UTF-8
                export LC_ALL=zh_CN.UTF-8
                export GRADLE_USER_HOME="${WORKSPACE}/.gradle"
                # export -p
                gradle --no-daemon -g "${WORKSPACE}/.gradle" ${deploy_svc_name}:bootJar -x test
              """
              echo "构建的jar包 ${deploy_svc_name} 信息如下："
              sh "ls -lh ${JAR_PKG_NAME}; pwd"
            } catch(Exception err) {
              echo err.getMessage()
              echo err.toString()
              unstable '构建失败'
            }
          }
        }
      }
    }

    stage('构建镜像上传HARBOR仓库') {
      steps {
        script {
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            TEMP_JAR_NAME = "${deploy_svc_name}-0.0.1-SNAPSHOT.jar"
            JAR_PKG_NAME = "${deploy_svc_name}/build/libs/${TEMP_JAR_NAME}"
            DEPLOY_JAR_PKG_NAME = "${deploy_svc_name}-${GIT_COMMIT_MSG}-${BUILD_NUMBER}.jar"
            // 创建构建docker镜像用的临时目录
            sh """
              [ -d temp_docker_build_dir ] || mkdir temp_docker_build_dir
              cp ${JAR_PKG_NAME} temp_docker_build_dir/${TEMP_JAR_NAME}
            """
            echo "构建服务：${deploy_svc_name} 的docker镜像"
            dir("${env.WORKSPACE}/temp_docker_build_dir") {
              docker_file = """
                FROM ${JDKVERSION}
                LABEL maintainer="colin" version="1.0" datetime="2021-01-11"
                RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime
                RUN echo "Asia/Shanghai" > /etc/timezone
                WORKDIR /opt/betack       
                # copy arthas
                # COPY --from=hengyunabc/arthas:latest /opt/arthas /opt/arthas
                COPY --from=IAmIPaddress/libs/arthas:3.6.7-no-jdk /opt/arthas /opt/arthas
                COPY ${TEMP_JAR_NAME} /opt/betack/${DEPLOY_JAR_PKG_NAME}
              """.stripIndent()

              writeFile file: 'Dockerfile', text: "${docker_file}", encoding: 'UTF-8'
              sh '''
                pwd; ls -lh
                cat Dockerfile
              '''

              image_name = "${office_registry}/${project}/bffi-${deploy_svc_name}:${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
              l_image_name = "${office_registry}/${project}/bffi-${deploy_svc_name}:latest"
              withCredentials([usernamePassword(credentialsId: "${harbor_auth}", passwordVariable: 'password', usernameVariable: 'username')]) {
                sh """
                  docker login -u ${username} -p '${password}' ${office_registry}
                  docker image build -t ${image_name} -f Dockerfile .
                  docker image push ${image_name}
                  docker image tag ${image_name} ${l_image_name}
                  docker image push ${l_image_name}
                """

                // 推送镜像到hwcould仓库：harbor.betack.com
                def _EXTRANET_HARBOR = Boolean.valueOf("${params.EXTRANET_HARBOR}")
                if (_EXTRANET_HARBOR) {
                  extranet_image_name = "${hwcloud_registry}/${project}/bffi-${deploy_svc_name}:${GIT_COMMIT_MSG}-${BUILD_NUMBER}"
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
                rm -f ${TEMP_JAR_NAME}
                docker image rm ${image_name}
                docker image rm ${l_image_name}
              """
            }
          }
        }
      }
    }

    stage('部署服务') {
      steps {
        script {
          // 创建拉取jenkins devops代码用的临时目录
          echo '正在从gitlab拉取rancher-compose更新部署用的YAML文件...'
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
          // Rancher Compose 文件复制
          RANCHER_YAML='temp_jenkins_workspace/betack/formula-integration-project/deployToRancher/*'
          sh """
            cp -a  ${RANCHER_YAML} ./
            pwd; ls -lh
          """

          // 循环处理需要部署的服务
          for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
            // 循环处理需要部署的环境
            for (deploy_to_env in params.DEPLOY_TO_ENV.tokenize(',')) {
              // 根据需要部署的环境，切换变量
              switch("${deploy_to_env}") {
              case 'IAmIPaddress':
                env.RANCHER_URL = "http://IAmIPaddress:8080/"
                env.RANCHER_ACCESS_KEY = "CB44CBA373D21EA03276"
                env.RANCHER_SECRET_KEY = "v9aGKsAu8xR35HWs29g2B6cJoxFaVp5w9kae9dzP"
                // rancher-compose部署
                tmp_deploy_to_env = 'dev1'
                doDeployToRancher(tmp_deploy_to_env,
                                  deploy_svc_name,
                                  image_name,
                                  'rab',
                                  DEPLOY_JAR_PKG_NAME)
                break;
              default:
                // 默认发到运维测试环境？ test_ops
                env.RANCHER_URL = ""
                env.RANCHER_ACCESS_KEY = ""
                env.RANCHER_SECRET_KEY = ""
                break;
              }
            }
          }
        }
      }
    }
  }
}


def doDeployToRancher(deployToEnv, deploySVCName, imageName, rancherSpacesDIR, deployJARPKGName) {
  stage("Rancher Compose部署") {
    echo "正在部署到 " + deployToEnv + " 环境."
    sh """
      sed -i 's#${deploySVCName}-IMAGE_NAME#${imageName}#' docker-compose.yml
      sed -i 's#${deploySVCName}-ENV#${deployToEnv}#' docker-compose.yml
      sed -i 's#${deploySVCName}-JAR_PKG_NAME#${deployJARPKGName}#' docker-compose.yml
    """

    if (deploySVCName == 'svc-data-migration') {
      sh """
        sed -i 's#${deploySVCName}-OPTIONS#${params.MIGRATION_PARAMETER}#' docker-compose.yml
      """
    }

    sh """
      cat docker-compose.yml
      echo "正在执行 rancher-compose 命令，更新 ${deploySVCName} 到 ${deployToEnv} 环境."
      rancher-compose -f ./docker-compose.yml -r ./rancher-compose.yml -p ${rancherSpacesDIR} up --upgrade --pull --confirm-upgrade -d ${deploySVCName}
    """
  }
}

