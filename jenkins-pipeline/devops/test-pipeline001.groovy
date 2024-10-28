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
          // jenkins/inbound-agent:3248.v65ecb_254c298-2-jdk17
          // ubuntu-jenkins-agent:latest
          // IAmIPaddress:8765/libs/ubuntu-jenkins-agent:latest-nofrontend
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
                image: "IAmIPaddress:8765/libs/ubuntu-jenkins-agent:latest-nofrontend"
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

    stages {
        stage('测试数据') {
            steps {
                script {
                  sh 'cat /etc/os-release'
                  sleep 120
                }
            }
        }
    }
}


