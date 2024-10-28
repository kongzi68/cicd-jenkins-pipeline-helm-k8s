def call(Map config = [:]) {
    /*
        config.brinSVCName
        config.deploySVCName
        config.project
        config.dockerFile
    */
    def String harborAuth = '443eb7ee-c21b-4e32-b449-e01d83171672'
    def String officeRegistry = 'IAmIPaddress:8765'
    def String prodRegistry = 'harbor.betack.com'
    def Map imageDict = [:]
    // 创建构建docker镜像用的临时目录
    sh """
        [ -d temp_docker_build_dir ] || mkdir temp_docker_build_dir
        cp -a target/release/${config.brinSVCName} temp_docker_build_dir/
    """
    println("是否从Jenkins传入dockerfile： " + config.containsKey("dockerFile"))
    dir("${env.WORKSPACE}/temp_docker_build_dir") {
        if (config.containsKey("dockerFile") == false) {
            echo "创建Dockerfile"
            dockerFile = """
                FROM ${office_registry}/rabbeyond/betack-nlp-data-sync-baseimage:ubuntu-22.04
                LABEL maintainer="colin" version="1.0" datetime="2024-07-18"
                RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
                    echo "Asia/Shanghai" > /etc/timezone
                WORKDIR /opt/betack
                COPY ${config.brinSVCName} /opt/betack/${config.brinSVCName}
            """.stripIndent()
            println("使用Jenkins共享库中的dockerfile")
        } else {
            dockerFile = config.get('dockerFile')
            println("使用Jenkins中传入的dockerfile")
        }

        writeFile file: 'Dockerfile', text: "${dockerFile}", encoding: 'UTF-8'
        sh 'pwd; ls -lh Dockerfile; cat Dockerfile'

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
            imageDict.put('imageName', imageName)

            // 推送镜像到hwcould仓库：harbor.betack.com
            def _EXTRANET_HARBOR = Boolean.valueOf("${params.EXTRANET_HARBOR}")
            if (_EXTRANET_HARBOR) {
                extranetImageName = "${prodRegistry}/${config.project}/${config.deploySVCName}:${env.COMMIT_SHORT_ID}-${BUILD_NUMBER}"
                sh """
                    podman login -u ${username} -p '${password}' ${prodRegistry}
                    podman image tag ${imageName} ${extranetImageName}
                    podman image push ${extranetImageName}
                    podman image rm ${extranetImageName}
                """
                imageDict.put('extranetImageName', extranetImageName)
            }
        }
        // 镜像打包后，清理jar包，减少docker build上下文，清理构建环境的镜像节约磁盘空间
        sh """
            rm -rf Dockerfile
            #podman image rm ${imageName}
            #podman image rm ${officeRegistry}/${config.project}/${config.deploySVCName}:latest
        """
    }
    return imageDict
}
