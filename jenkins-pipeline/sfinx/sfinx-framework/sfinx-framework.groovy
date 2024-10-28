#!groovy
/* 导入Jenkins共享库 */
@Library('bf-shared-library@chengdu-main') _
// @Library('bf-shared-library@dev') _

// 公共
def office_registry = "IAmIPaddress:8765"
def hwcloud_registry = "harbor.betack.com"

// 项目
def project = "sfinx"  // HARrab镜像仓库中的项目名称
def git_address = "ssh://git@code.betack.com:4022/sfinx/sfinx_framework.git"

// 认证
def harbor_auth = "443eb7ee-c21b-4e32-b449-e01d83171672"
def imageDict = [:]
// 默认定义为第一次运行服务
def isFirst = true


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
                memory: "40Gi"
                cpu: "10"
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
          - name: tools-build
            image: "${office_registry}/sfinx/base:v1.6"
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
    timeout(time: 60, unit:'MINUTES') // 设置此次发版运行20分钟后超时
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', numToKeepStr: '100')  // 设置保留30次的构建记录
  }

  parameters {
    gitParameter branch: '',
                 branchFilter: '.*',
                 defaultValue: 'master',
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
                 description: '勾选此选项后，将清理上次构建产生的build目录; 原则上不建议清理（不清理有没有问题待定），否则构建耗时起码30分钟以上。',
                 name: 'CLEAN_BUILD'
    extendedChoice defaultValue: '1',
                   description: '当分布式部署 API 服务时，需要选择启动的 API node 数量; 默认值: 1',
                   multiSelectDelimiter: ',',
                   name: 'REPLICA_COUNT',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_SINGLE_SELECT',
                   value: '1',
                   visibleItemCount: 5
    extendedChoice description: '请选择本次发版需要部署的服务',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_SVC_NAME',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'sfinx-framework-taskrunner,sfinx-framework-rpcservice,sfinx-framework-datapump',      // SFinX_Framework
                   visibleItemCount: 6
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'sfinx-dev-1',
                   visibleItemCount: 10
    booleanParam defaultValue: true,
                 description: '默认启用，将备份values.yaml存储到minio，且部署时优先使用该备份; 若需要用模板重新生成时，请取消勾选。',
                 name: 'IS_ENABLED_BAK_VALUES_YAML'
  }

  stages {
    stage('拉取代码') {
      steps {
        script {
          checkWhetherToContinue()
          echo '正在拉取代码...'
          env.COMMIT_SHORT_ID = gitCheckout(git_address, params.BRANCH_TAG, true)
          println(env.COMMIT_SHORT_ID)
        }
      }
    }

    stage('环境准备') {
      steps {
        script {
          def _CLEAN_BUILD = Boolean.valueOf(params.CLEAN_BUILD)
          if (_CLEAN_BUILD) {
            echo "清理上次构建产生的build目录"
            sh '[ -d build ] && rm -rf build || mkdir -p build'
          }
        }
      }
    }

    stage('代码编译打包') {
      steps {
        container('tools-build') {
          echo '正在构建，编译二进制执行文件...'
          // error("test!!!")
          script {
            sh """
              pwd; ls -lh
              export build_type=Debug
              export LANG=C.UTF-8
              export LC_ALL=C.UTF-8
              # 编译
              cp -a CMakeLists_for_build.txt CMakeLists.txt
              mkdir -p build && cd build && cmake -DCMAKE_BUILD_TYPE=\$build_type .. && make -j20
            """
          }
        }
      }
    }

    stage('构建镜像上传HARBOR仓库') {
      steps {
        container('podman') {
          script{
            for (deploy_svc_name in params.DEPLOY_SVC_NAME.tokenize(',')) {
              // 创建构建docker镜像用的临时目录
              sh """
                [ -d temp_docker_build_dir/build ] || mkdir -p temp_docker_build_dir/build
                cp -a build/SFinX_Framework temp_docker_build_dir/build/
                cp -a build/*.so temp_docker_build_dir/build/
                #cp -a config temp_docker_build_dir/
                cp -a run.sh temp_docker_build_dir/
              """
              switch(deploy_svc_name) {
                case 'sfinx-framework-taskrunner':
                  sfinxConfigPath = './config/runner.txt'
                break
                case 'sfinx-framework-rpcservice':
                  sfinxConfigPath = './config/task.txt'
                break
                case 'sfinx-framework-datapump':
                  sfinxConfigPath = './config/data.txt'
                break
              }
              startScript = """
                #/bin/bash
                echo "current config path:${sfinxConfigPath}"
                /bin/sh -c 'echo "/iamusername/logs/core.%e.%p" > /proc/sys/kernel/core_pattern'
                ldconfig
                export LIBRARY_PATH=\$LIBRARY_PATH:/opt/betack/
                export LD_LIBRARY_PATH=\$LD_LIBRARY_PATH:/opt/betack/
                ./SFinX_Framework -start ${sfinxConfigPath}
              """.stripIndent()
              writeFile file: 'temp_docker_build_dir/run.sh', text: startScript, encoding: 'UTF-8'
              sh 'ls -lh temp_docker_build_dir/run.sh; cat temp_docker_build_dir/run.sh'
              dockerFile = """
                FROM ${office_registry}/sfinx/base:v1.6
                LABEL maintainer="colin" version="1.0" datetime="2024-09-03"
                RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
                    echo "Asia/Shanghai" > /etc/timezone && \
                    cat /dev/null > /etc/resolv.conf
                WORKDIR /opt/betack
                COPY build/SFinX_Framework /opt/betack/
                COPY build/*.so /opt/betack/
                #COPY config /opt/betack/
                COPY run.sh /opt/betack/
                CMD ["/bin/bash","run.sh"]
              """.stripIndent()
              images = sfinxCodeBuildContainerImage(dockerFile: dockerFile,
                                                    deploySVCName: deploy_svc_name,
                                                    project: project)
              imageDict.put(deploy_svc_name, images)
            }
          }
        }
      }
    }

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
            gitCheckout('ssh://git@code.betack.com:4022/devops/jenkins.git', 'chengdu-main')
            sh 'pwd; ls -lh'
          }
          // helm登录harbor仓库
          loginHelmChartRegistry()
          // 根据张玉仙他们的要求，服务有启动顺序
          /* ## sfinx_framework
            ./SFinX_Framework -start /IdeaProjects/sfinx_framework/sfinx_framework/config/task.txt    // rpcservice    2
            ./SFinX_Framework -start /IdeaProjects/sfinx_framework/sfinx_framework/config/data.txt      // datapump    1
            ./SFinX_Framework -start /IdeaProjects/sfinx_framework/sfinx_framework/config/runner.txt     // taskrunner   3
            ./SFinX_Framework -start /IdeaProjects/sfinx_framework/sfinx_framework/config/factor_manager.txt    // 离线服务
          */
          serviceStartupSequence = ['sfinx-framework-datapump':'1', 'sfinx-framework-rpcservice':'2', 'sfinx-framework-taskrunner':'3']
          tempList = [:]
          for (item in params.DEPLOY_SVC_NAME.tokenize(',')) {
            tempList.put(serviceStartupSequence.get(item), item)
          }
          // 循环处理需要部署的服务
          deploySequence = tempList.keySet().sort()
          for (item in deploySequence) {
            deploy_svc_name = tempList.get(item)
            helm_values_file="temp_jenkins_workspace/${project}/sfinx-framework/deployToK8s/sfinx-framework-values.yaml"
            // 循环处理需要部署的命名空间
            for (namespaces in params.DEPLOY_TO_ENV.tokenize(',')) {
              K8S_AUTH = 'fd4efaf3-23f9-4f31-a085-3e3baa9618d4'
              isFirst = checkHelmValuesFilesOnMinio(isEnabled: params.IS_ENABLED_BAK_VALUES_YAML,
                                                    k8sKey: K8S_AUTH,
                                                    namespaces: namespaces,
                                                    deploySVCName: deploy_svc_name,
                                                    helmValuesFilePath: helm_values_file)
              svcYaml = readYaml file: "${deploy_svc_name}-values.yaml"
              image_tag = "${COMMIT_SHORT_ID}-${BUILD_NUMBER}"
              svcYaml['image']['imgHarbor'] = office_registry
              svcYaml['image']['tag'] = image_tag
              configEnv = libTools.splitNamespaces(namespaces)
              configEnvPrefix = configEnv[0]
              configEnvSuffix = configEnv[1]
              configENV = configEnv[2]
              println("CONFIG_ENV：" + configEnvSuffix)
              println("项目简称，用于命名空间的前缀：" + configEnvPrefix)
              svcYaml['namespacePrefix'] = configEnvPrefix
              // 未从minio下载到helm value yaml文件时，需要处理卷挂载问题
              // 后续能够从minio下载文件之后，可以直接修改minio中的yaml文件的磁盘挂载部分内容
              println("isFirst": isFirst)
              if (isFirst) {
                // 只有当minio中无该helm value yaml文件时，才会去设置这些值
                // 所以，后续可以直接修改minio中的yaml文件，其中不是每次都定义的部分
                svcYaml['nameOverride'] = deploy_svc_name
                svcYaml['image']['imgNameOrSvcName'] = deploy_svc_name
                svcYaml['image']['harborProject'] = project
                /* 处理卷创建与挂载问题 */
                svcYaml['storage']['storageEnable'] = true
                // 配置文件目录
                svcYaml['storage']['isCreateConfigPVC'] = true
                svcYaml['storage']['isMountConfigPV'] = true
                svcYaml['storage']['configCapacity'] = '100Mi'
                svcYaml['storage']['configPVCNameInfix'] = deploy_svc_name
                svcYaml['storage']['configPVCMountPath'] = '/opt/betack/config'
                svcYaml['storage']['configStorageClassName'] = 'nfs-client-retain'
                // 标准卷
                switch(deploy_svc_name) {
                  case 'sfinx-framework-taskrunner':
                    svcYaml['storage']['isCreateDataPVC'] = true
                    svcYaml['storage']['isMountDataPVType'] = 'pvc'
                    svcYaml['storage']['capacity'] = '20Gi'
                    svcYaml['storage']['dataPVCNameInfix'] = "${deploy_svc_name}-data"
                    svcYaml['storage']['dataPVCMountPath'] = '/opt/betack/task-data'
                    svcYaml['storage']['dataStorageClassName'] = 'nfs-client-retain'
                  break
                  default:
                    svcYaml['storage']['isCreateDataPVC'] = false
                    svcYaml['storage']['isMountDataPVType'] = 'nil'
                    /*
                    svcYaml['storage']['capacity'] = '500Gi'
                    svcYaml['storage']['dataPVCNameInfix'] = 'data'
                    svcYaml['storage']['dataPVCMountPath'] = '/opt/rab_backend/data'
                    svcYaml['storage']['dataStorageClassName'] = 'nfs-client-retain'
                    */  
                  break
                }
                // 日志持久卷，所有服务共用一个80GB的日志pv卷
                switch(deploy_svc_name) {
                  case 'sfinx-framework-rpcservice':
                    svcYaml['storage']['isCreateLogPVC'] = true
                  break
                  default:
                    svcYaml['storage']['isCreateLogPVC'] = false
                  break
                }
                svcYaml['storage']['isMountLogPV'] = true
                svcYaml['storage']['logPVCMountPath'] = '/opt/betack/sfinx-logs'
                svcYaml['storage']['logStorageClassName'] = 'nfs-client-retain'
                // 独立卷
                svcYaml['storage']['isCreateAlonePVC'] = false
                svcYaml['storage']['isMountAlonePV'] = false
                /*
                svcYaml['storage']['aloneCapacity'] = ''
                svcYaml['storage']['aloneDataPVCNameInfix'] = 'data-alone'
                svcYaml['storage']['aloneDataPVCMountPath'] = '/opt/sfinx/alone-data'
                svcYaml['storage']['aloneDataStorageClassName'] = 'nfs-client-retain'
                */
              }
              // service是否启用
              svcYaml['service']['isIn'] = false
              svcYaml['service']['isOut'] = true
              switch(deploy_svc_name) {
                case 'sfinx-framework-taskrunner':
                  svcYaml['service']['ports'] = [16011, 16018]
                break
                case 'sfinx-framework-rpcservice':
                  svcYaml['service']['ports'] = [16019]
                break
                case 'sfinx-framework-datapump':
                  svcYaml['service']['ports'] = [16020]
                break
              }
              // 环境变量注入
              svcYaml['envFrom']['enabled'] = false
              writeYaml file: "${deploy_svc_name}-values.yaml", data: svcYaml, overwrite: true
              // sh "sed -i 's#CONFIG_ENV#${configENV}#' ${deploy_svc_name}-values.yaml"
              sh "cat ${deploy_svc_name}-values.yaml"
              // 备份 helm value yaml 文件
              minioFile(doType: 'upload', fileNamePath: "${deploy_svc_name}-values.yaml", namespace: "${namespaces}")
              configFileProvider([configFile(fileId: "${K8S_AUTH}", targetLocation: "kube-config.yaml")]){
                deployWithHelmToK8sByDeploy(deploySVCName: deploy_svc_name,
                                            deployToEnv: namespaces,
                                            imageTag: image_tag,
                                            chartName: 'sfinx-framework-deploy-common',
                                            chartsProject: 'sfinx-charts',
                                            chartVersion: '0.1.0')
              }
            }
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
