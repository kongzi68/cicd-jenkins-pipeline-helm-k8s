#!groovy
// 公共
// office harbor
def registry = "harbor.od.com"
def git_address = "ssh://git@code.betack.com:4022/betack/rab-backend.git"
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
            image: "${registry}/libs/ubuntu-jenkins-agent:jdk17-nogradle"
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
            command: ["/bin/sh", "-c", 'for i in `seq 1 100`; do sleep 60; done;']
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

  parameters {
    gitParameter branch: '',
                 branchFilter: '.*',
                 defaultValue: 'master',
                 description: '选择需要发布的代码分支，默认为：master 分支',
                 name: 'BRANCH_TAG',
                 quickFilterEnabled: true,
                 selectedValue: 'NONE',
                 sortMode: 'NONE',
                 tagFilter: '*',
                 type: 'PT_BRANCH'
    extendedChoice description: '发版到选中的运行环境',
                   multiSelectDelimiter: ',',
                   name: 'DEPLOY_TO_ENV',
                   quoteValue: false,
                   saveJSONParameterToFile: false,
                   type: 'PT_CHECKBOX',
                   value: 'hwcloud-api3-or-mirror-IAmIPaddress,hwcloud-api3-or-mirror-IAmIPaddress,office-k8s-master-IAmIPaddress',
                   visibleItemCount: 7
  }

  stages {
    stage('拉取代码') {
      steps {
        container('jnlp') {
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
          }
        }
      }
    }

    stage('选择需要同步的backend_data_reloader名称') {
      steps {
        container('jnlp') {
          script {
            // 查找当前WORKERSPACE下的py文件
            // 处理成选择框使用的 value
            def file_list = []
            def files = findFiles(glob: 'scripts/*.py')
            for(t_file in files) {
              file_list.add(t_file.name)
            }
            // Load the list into a variable
            env.LIST = file_list.join(',')
            echo "${env.LIST}"
            env.RELEASE_SCOPE = input message: '请选择需要同步的 backend_data_reloader 脚本具体名称', ok: '确定',
                    parameters: [extendedChoice(
                    description: '请选择需要同步的 backend_data_reloader 脚本具体名称，只能单选',
                    name: 'BACKEND-DATA-RELOADER-LISTS',
                    defaultValue: "${env.BUILD_ARCHS}",
                    multiSelectDelimiter: ',',
                    type: 'PT_SINGLE_SELECT',
                    value: env.LIST,
                    visibleItemCount: 50
              )]
            echo "被选择同步的脚本为: ${env.RELEASE_SCOPE}"
          }
        }
      }
    }

    stage('部署python脚本backend_data_reloader') {
      steps {
        container('ansible') {
          echo '正在同步脚本...'
          script {
            // 循环处理需要部署的环境
            // 注意，需要提前用命令：ssh-copy-id -i /iamusername/.ssh/id_rsa_jenkins.pub betack@IAmIPaddress 进行免密认证
            sshagent (credentials: ['830e90a8-1fec-4a45-9317-415e7acaff10']) {
              def env_list = []
              for (deploy_to_env in params.DEPLOY_TO_ENV.tokenize(',')) {
                ip_address = deploy_to_env.tokenize('-')[-1]
                env_list.add(ip_address)
              }
              dest_hosts = env_list.join(',')
              println(dest_hosts)
              sh """
                ansible all -i "${dest_hosts}," -u betack -m copy -a \
                  "mode=u+x backup=yes src=scripts/${env.RELEASE_SCOPE} dest=/home/betack/script/crontab"
              """
            }
          }
        }
      }
    }
  }
}