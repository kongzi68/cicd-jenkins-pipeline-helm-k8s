def call(Map config = [:]) {
    /*
        config.distDir
        config.project
        config.deploySVCName
        config.dockerFile
    */
    def String harborAuth = '443eb7ee-c21b-4e32-b449-e01d83171672'
    def String officeRegistry = 'IAmIPaddress:8765'
    def String hwcloudRegistry = 'harbor.betack.com'
    def Map imageDict = [:]
    // 创建构建docker镜像用的临时目录
    echo "创建Dockerfile"
    sh 'pwd; ls -lh'
    if (config.containsKey("dockerFile") == false) {
        println("使用Jenkins共享库中的dockerfile")
        dockerFile = """
            FROM ${officeRegistry}/libs/nginx:1.27.0
            LABEL maintainer="colin" version="1.0" datetime="2023-05-17"
            RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
                echo "Asia/Shanghai" > /etc/timezone
            COPY ${env.WORKSPACE}/${config.distDir} /var/www/html/
            RUN mkdir /var/www/chkstatus && echo "this is test, one!" > /var/www/chkstatus/tt.txt
            EXPOSE 80
            WORKDIR /var/www
        """.stripIndent()
    } else {
        println("使用Jenkins中传入的dockerfile")
        dockerFile = config.get('dockerFile')
    }
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
            # podman image tag ${imageName} "${officeRegistry}/${config.project}/${config.deploySVCName}:latest"
            # podman image push "${officeRegistry}/${config.project}/${config.deploySVCName}:latest"
        """
        imageDict.put('imageName', imageName)

        // 推送镜像到hwcould仓库：harbor.betack.com
        def _EXTRANET_HARBOR = Boolean.valueOf("${params.EXTRANET_HARBOR}")
        if (_EXTRANET_HARBOR) {
            extranetImageName = "${hwcloudRegistry}/${config.project}/${config.deploySVCName}:${env.COMMIT_SHORT_ID}-${BUILD_NUMBER}"
            sh """
                podman login -u ${username} -p '${password}' ${hwcloudRegistry}
                podman image tag ${imageName} ${extranetImageName}
                podman image push ${extranetImageName}
                podman image rm -f ${extranetImageName}
            """
            imageDict.put('extranetImageName', extranetImageName)
        }
    }
    // 镜像打包后，清理jar包，减少docker build上下文，清理构建环境的镜像节约磁盘空间
    sh """
        rm -rf Dockerfile ${config.distDir}
        podman image rm -f ${imageName}
        # podman image rm -f ${officeRegistry}/${config.project}/${config.deploySVCName}:latest
    """
    return imageDict
}
