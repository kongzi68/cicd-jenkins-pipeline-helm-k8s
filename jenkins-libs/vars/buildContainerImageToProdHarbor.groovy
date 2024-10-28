def call(Map config = [:]) {
    /*
        config.deploySVCName
        config.deployJarPKGName
        config.project
    */
    def String harborAuth = '443eb7ee-c21b-4e32-b449-e01d83171672'
    def String hwcloudRegistry = 'IAmIPaddress'
    def Map imageDict = [:]
    // 创建构建docker镜像用的临时目录
    sh "[ -d temp_docker_build_dir ] || mkdir temp_docker_build_dir"
    dir("${env.WORKSPACE}/temp_docker_build_dir") {
        sh "rm -f Dockerfile ${config.deployJarPKGName}"
        minioFileToShanghai(doType: 'download',
                            dirPath: "${JOB_BASE_NAME}/${config.deploySVCName}",
                            fileNamePath: "${config.deployJarPKGName}",
                            bucket: 'jenkins')
        minioFileToShanghai(doType: 'download',
                            dirPath: "${JOB_BASE_NAME}/${config.deploySVCName}",
                            fileNamePath: 'Dockerfile',
                            bucket: 'jenkins')
        sh 'pwd; ls -lh Dockerfile; cat Dockerfile'
        withCredentials([usernamePassword(credentialsId: "${harborAuth}", passwordVariable: 'password', usernameVariable: 'username')]) {
            imageName = "${hwcloudRegistry}/${config.project}/${config.deploySVCName}:${env.COMMIT_SHORT_ID}-${BUILD_NUMBER}"
            sh """
                pwd; ls -lh
                podman login -u ${username} -p '${password}' ${hwcloudRegistry}
                podman image build -t ${imageName} -f Dockerfile .
                podman image push ${imageName}
            """
            imageDict.put('imageName', imageName)
        }
    }
    return imageDict
}
