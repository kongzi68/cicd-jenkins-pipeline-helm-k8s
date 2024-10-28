#!groovy
/* 导入Jenkins共享库 */
@Library('bf-shared-library@chengdu-main') _

def isFirst = true

pipeline {
    agent {
        kubernetes {
          defaultContainer 'jnlp'
          workspaceVolume persistentVolumeClaimWorkspaceVolume(claimName: 'jenkins-agent-workspace', readOnly: false)
          // 注意：ubuntu-jenkins-agent 镜像，需要安装中文语言包 jnlp
          // jenkins/inbound-agent:latest-jdk17
          // ubuntu-jenkins-agent:latest
          yaml """
            apiVersion: v1
            kind: Pod
            metadata:
              name: jenkins-slave
              labels:
                app: jenkins-agent
            spec:
              containers:
              - name: jenkins-agent
                image: "IAmIPaddress:8765/libs/jenkins/inbound-agent:latest-jdk17"
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
                image: "IAmIPaddress:8765/libs/alpine:tools-sshd"
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
                image: "quay.io/podman/stable:v3.4.2"
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


parameters {
  extendedChoice defaultValue: 'rab_server', description: '请选择服务的启动命令', descriptionPropertyValue: '', multiSelectDelimiter: ',', name: 'SVC_START_NAME', quoteValue: false, saveJSONParameterToFile: false, type: 'PT_SINGLE_SELECT', value: 'rab_server,rab_fund_server', visibleItemCount: 5
}


    stages {
        stage('测试数据') {
            steps {
              /*
                container('podman') {
                    script {
                        println('aaaaaaaaaaaa')
                        // sleep 600
                        // sh "podman info"
                        sh "echo iAmPassword | podman login -u bfops --password-stdin IAmIPaddress:8765"
                        sh "podman pull IAmIPaddress:8765/libs/alpine:python3.11.3-tools"
                        sh "podman image list"
                    }
                    echo '1111111111'
                }
              */

                script {
                  /*
                  getDatabaseConnection(type: 'GLOBAL') {
                    def result = sql(sql: "show databases;", parameters: 'List of Object')
                    println(result)
                  }
                  getDatabaseConnection(id: 'bf', type: 'GLOBAL') {
                    def result = sql(sql: "show databases;")
                    println(result)
                    println(result[0])
                    def result1 = sql(sql: "select user, host from mysql.user limit 3;")
                    println(result1)
                  }
                  */

                  /*
                  minio bucket: 'jenkins-devops', 
                        credentialsId: 'ce1a8e22-a4cd-4cbd-9f64-619d45155a86',
                        excludes: '', host: 'http://IAmIPaddress:9000',
                        includes: 'rab-svc-offline-app-values.yaml',
                        targetFolder: 'new-rab-dev-4/'

                  sh 'rm -f rab-svc-offline-app-values.yaml'

                  minioDownload bucket: 'jenkins-devops',
                                credentialsId: 'ce1a8e22-a4cd-4cbd-9f64-619d45155a86',
                                failOnNonExisting: false, 
                                file: 'new-rab-dev-4/rab-svc-offline-app-values.yaml', 
                                host: 'http://IAmIPaddress:9000', 
                                targetFolder: './'
                  sh 'cat rab-svc-offline-app-values.yaml'

                  minioFile(
                    doType: 'upload',
                    fileNamePath: 'rab-svc-offline-app-values.yaml',
                    namespace: 'new-rab-dev-4' 
                  )

                  // svcYaml = readYaml file: "rab-svc-offline-app-values.yaml"

                  minioFile(
                    doType: 'download',
                    fileNamePath: 'rab-svc-offline-app-values.yaml',
                    namespace: 'new-rab-dev-4' 
                  )
                  */
                  svcYaml = readYaml file: "rab-svc-offline-app-values.yaml"
                  println(svcYaml)
                  svcYaml['image']['tag'] = "1576873756-842"
                  svcYaml['imagePullSecrets'] = [[name:'harbor-inner'], [name:'harbor-outer']]
                  writeYaml file: "rab-svc-offline-app-values.yaml", data: svcYaml, overwrite: true

                  /*
                  minioFile(
                    doType: 'upload',
                    fileNamePath: 'rab-svc-offline-app-values.yaml',
                    namespace: 'new-rab-dev-4' 
                  )
                  */
                  sh "cat rab-svc-offline-app-values.yaml"
                  

                  
                  // files = findFiles(glob: '**/TEST-*.xml')
                  /*
                  println(files)

                  try {
                    minioFile(doType: 'download', fileNamePath: 'rab-svc-offline-app-values-11111.yaml', namespace: 'new-rab-dev-4')
                    isFirst = false
                  } catch(Exception err) {
                    echo err.getMessage()
                    echo err.toString()
                    isFirst = true
                  }
                  println(isFirst)
                  println('1111 end')
                  */
                }
            }
        }
    }
}


