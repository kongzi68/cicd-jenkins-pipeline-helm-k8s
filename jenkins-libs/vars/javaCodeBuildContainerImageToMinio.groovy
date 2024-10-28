def call(Map config = [:]) {
    /*
        config.jarPKGName
        config.tempJarName
        config.deploySVCName
        config.jdkVersion
        config.project
        config.deployJarPKGName
    */
    def String harborAuth = '443eb7ee-c21b-4e32-b449-e01d83171672'
    def String officeRegistry = 'IAmIPaddress:8765'
    def String hwcloudRegistry = 'harbor.betack.com'
    def Map imageDict = [:]
    // 创建构建docker镜像用的临时目录
    sh """
        [ -d temp_docker_build_dir ] || mkdir temp_docker_build_dir
        cp ${config.jarPKGName} temp_docker_build_dir/${config.tempJarName}
    """
    println("是否从Jenkins传入dockerfile： " + config.containsKey("dockerFile"))
    if (config.containsKey("dockerFile") == false) {
        echo "构建服务：${config.deploySVCName} 的docker镜像"
        dir("${env.WORKSPACE}/temp_docker_build_dir") {
            dockerFile = """
                FROM ${config.jdkVersion}
                LABEL maintainer="colin" version="1.0" datetime="2023-05-17"
                RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
                    echo "Asia/Shanghai" > /etc/timezone
                COPY ${config.tempJarName} /opt/betack/${config.deployJarPKGName}
                WORKDIR /opt/betack
            """.stripIndent()

            writeFile file: 'Dockerfile', text: "${dockerFile}", encoding: 'UTF-8'
            // 追加，把 Arthas Java 诊断工具打包到镜像中
            def _ARTHAS_TOOLS = Boolean.valueOf("${params.ARTHAS_TOOLS}")
            if (_ARTHAS_TOOLS) {
                sh """
                    echo 'COPY --from=hengyunabc/arthas:latest /opt/arthas /opt/arthas' >> Dockerfile
                """
            }

            sh '''
                pwd; ls -lh Dockerfile
                cat Dockerfile
            '''
            println("使用Jenkins共享库中的dockerfile")
            withCredentials([usernamePassword(credentialsId: "${harborAuth}", passwordVariable: 'password', usernameVariable: 'username')]) {
                imageName = "${officeRegistry}/${config.project}/${config.deploySVCName}:${env.COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                sh """
                    pwd; ls -lh
                    podman login -u ${username} -p '${password}' ${officeRegistry}
                    podman image build -t ${imageName} -f Dockerfile .
                    podman image push ${imageName}
                    #podman image tag ${imageName} "${officeRegistry}/${config.project}/${config.deploySVCName}:latest"
                    #podman image push "${officeRegistry}/${config.project}/${config.deploySVCName}:latest"
                """
                imageDict.put('imageName', imageName)

                // 推送镜像到hwcould仓库：harbor.betack.com
                def _EXTRANET_HARBOR = Boolean.valueOf("${params.EXTRANET_HARBOR}")
                if (_EXTRANET_HARBOR) {
                    extranetImageName = "${hwcloudRegistry}/${config.project}/${config.deploySVCName}:${env.COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                    sh "cp -a ${config.tempJarName} ${config.deployJarPKGName}"
                    minioFileToShanghai(doType: 'upload',
                                        dirPath: "${JOB_BASE_NAME}/${config.deploySVCName}",
                                        fileNamePath: "${config.deployJarPKGName}",
                                        bucket: 'jenkins')
                    sh "rm -f ${config.deployJarPKGName}"
                    minioFileToShanghai(doType: 'upload',
                                        dirPath: "${JOB_BASE_NAME}/${config.deploySVCName}",
                                        fileNamePath: 'Dockerfile',
                                        bucket: 'jenkins')
                    imageDict.put('extranetImageName', extranetImageName)
                }
            }

            // 镜像打包后，清理jar包，减少docker build上下文，清理构建环境的镜像节约磁盘空间
            sh """
                rm -f ${config.tempJarName}
                #podman image rm ${imageName}
                #podman image rm ${officeRegistry}/${config.project}/${config.deploySVCName}:latest
            """
        }
    } else {
        dockerFile = config.get('dockerFile')
        dir("${env.WORKSPACE}/temp_docker_build_dir") {
            writeFile file: 'Dockerfile', text: "${dockerFile}", encoding: 'UTF-8'
            sh '''
                pwd; ls -lh Dockerfile
                cat Dockerfile
            '''
            println("使用Jenkins中传入的dockerfile")
            withCredentials([usernamePassword(credentialsId: "${harborAuth}", passwordVariable: 'password', usernameVariable: 'username')]) {
                imageName = "${officeRegistry}/${config.project}/${config.deploySVCName}:${env.COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                sh """
                    pwd; ls -lh
                    podman login -u ${username} -p '${password}' ${officeRegistry}
                    podman image build -t ${imageName} -f Dockerfile .
                    podman image push ${imageName}
                    #podman image tag ${imageName} "${officeRegistry}/${config.project}/${config.deploySVCName}:latest"
                    #podman image push "${officeRegistry}/${config.project}/${config.deploySVCName}:latest"
                """
                imageDict.put('imageName', imageName)

                // 推送镜像到hwcould仓库：harbor.betack.com
                def _EXTRANET_HARBOR = Boolean.valueOf("${params.EXTRANET_HARBOR}")
                if (_EXTRANET_HARBOR) {
                    extranetImageName = "${hwcloudRegistry}/${config.project}/${config.deploySVCName}:${env.COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                    sh "cp -a ${config.tempJarName} ${config.deployJarPKGName}"
                    minioFileToShanghai(doType: 'upload',
                                        dirPath: "${JOB_BASE_NAME}/${config.deploySVCName}",
                                        fileNamePath: "${config.deployJarPKGName}",
                                        bucket: 'jenkins')
                    sh "rm -f ${config.deployJarPKGName}"
                    minioFileToShanghai(doType: 'upload',
                                        dirPath: "${JOB_BASE_NAME}/${config.deploySVCName}",
                                        fileNamePath: 'Dockerfile',
                                        bucket: 'jenkins')
                    imageDict.put('extranetImageName', extranetImageName)
                }
            }

            // 镜像打包后，清理jar包，减少docker build上下文，清理构建环境的镜像节约磁盘空间
            sh """
                rm -f ${config.tempJarName}
                #podman image rm -f ${imageName}
                #podman image rm -f ${officeRegistry}/${config.project}/${config.deploySVCName}:latest
            """
        }
    }
    return imageDict
}
