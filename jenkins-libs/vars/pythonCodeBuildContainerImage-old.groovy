def call(Map config = [:]) {
    def String harborAuth = '443eb7ee-c21b-4e32-b449-e01d83171672'
    def String officeRegistry = 'IAmIPaddress:8765'
    def String hwcloudRegistry = 'harbor.betack.com'
    // 创建构建docker镜像用的临时目录
    if (config.get('dockerFile') == '') {
        sh """
            [ -d temp_docker_build_dir ] || mkdir temp_docker_build_dir
            cp -a ${config.distDir} temp_docker_build_dir/
        """
        echo "创建Dockerfile"
        dir("${env.WORKSPACE}/temp_docker_build_dir") {
            dockerFile = """
                FROM ${officeRegistry}/libs/nginx:1.21.1
                LABEL maintainer="colin" version="1.0" datetime="2023-05-17"
                RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
                    echo "Asia/Shanghai" > /etc/timezone
                COPY ${config.distDir} /var/www/html/
                RUN mkdir /var/www/chkstatus && echo "this is test, one!" > /var/www/chkstatus/tt.txt
                EXPOSE 80
                WORKDIR /var/www
            """.stripIndent()
            writeFile file: 'Dockerfile', text: "${dockerFile}", encoding: 'UTF-8'
            sh '''
                pwd; ls -lh Dockerfile
                cat Dockerfile
            '''
            echo "构建镜像，并上传到harbor仓库"
            withCredentials([usernamePassword(credentialsId: "${harborAuth}", passwordVariable: 'password', usernameVariable: 'username')]) {
                imageName = "${officeRegistry}/${config.project}/${config.deploySVCName}:${env.COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                sh """
                    pwd; ls -lh
                    podman login -u ${username} -p '${password}' ${officeRegistry}
                    podman image build -t ${imageName} -f Dockerfile .
                    podman image push ${imageName}
                    podman image tag ${imageName} "${officeRegistry}/${config.project}/${config.deploySVCName}:latest"
                    podman image push "${officeRegistry}/${config.project}/${config.deploySVCName}:latest"
                """

                // 推送镜像到hwcould仓库：harbor.betack.com
                def _EXTRANET_HARBOR = Boolean.valueOf("${params.EXTRANET_HARBOR}")
                if (_EXTRANET_HARBOR) {
                    extranetImageName = "${hwcloudRegistry}/${config.project}/${config.deploySVCName}:${env.COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                    sh """
                        podman login -u ${username} -p '${password}' ${hwcloudRegistry}
                        podman image tag ${imageName} ${extranetImageName}
                        podman image push ${extranetImageName}
                        podman image rm ${extranetImageName}
                    """
                } else {
                    extranetImageName = ''
                }
            }
            // 镜像打包后，清理jar包，减少docker build上下文，清理构建环境的镜像节约磁盘空间
            sh """
                rm -rf Dockerfile ${config.distDir}
                # podman image rm ${imageName}
                podman image rm ${officeRegistry}/${config.project}/${config.deploySVCName}:latest
            """
        }
    } else {
        sh """
            [ -d temp_docker_build_dir ] || mkdir temp_docker_build_dir
            cp -a ${config.projectCodePKG} temp_docker_build_dir/
        """
        dir("${env.WORKSPACE}/temp_docker_build_dir") {
            dockerFile = config.get('dockerFile')
            writeFile file: 'Dockerfile', text: "${dockerFile}", encoding: 'UTF-8'
            sh '''
                pwd; ls -lh Dockerfile
                cat Dockerfile
            '''
            echo "构建镜像，并上传到harbor仓库"
            withCredentials([usernamePassword(credentialsId: "${harborAuth}", passwordVariable: 'password', usernameVariable: 'username')]) {
                imageName = "${officeRegistry}/${config.project}/${config.deploySVCName}:${env.COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                sh """
                    pwd; ls -lh
                    podman login -u ${username} -p '${password}' ${officeRegistry}
                    podman image build -t ${imageName} -f Dockerfile .
                    podman image push ${imageName}
                    podman image tag ${imageName} "${officeRegistry}/${config.project}/${config.deploySVCName}:latest"
                    podman image push "${officeRegistry}/${config.project}/${config.deploySVCName}:latest"
                """

                // 推送镜像到hwcould仓库：harbor.betack.com
                def _EXTRANET_HARBOR = Boolean.valueOf("${params.EXTRANET_HARBOR}")
                if (_EXTRANET_HARBOR) {
                    extranetImageName = "${hwcloudRegistry}/${config.project}/${config.deploySVCName}:${env.COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                    sh """
                        podman login -u ${username} -p '${password}' ${hwcloudRegistry}
                        podman image tag ${imageName} ${extranetImageName}
                        podman image push ${extranetImageName}
                        podman image rm ${extranetImageName}
                    """
                } else {
                    extranetImageName = ''
                }
            }
            // 镜像打包后，清理jar包，减少docker build上下文，清理构建环境的镜像节约磁盘空间
            sh """
                rm -rf Dockerfile
                # podman image rm ${imageName}
                podman image rm ${officeRegistry}/${config.project}/${config.deploySVCName}:latest
            """
        }
    }
    return extranetImageName
}
