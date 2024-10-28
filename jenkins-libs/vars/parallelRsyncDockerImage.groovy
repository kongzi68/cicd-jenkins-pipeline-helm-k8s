def call(Map config = [:]) {
    // 通过切割大文件镜像为多个小文件，并行传输节省时间；跳过成都办公室带宽上行限速问题
    // 后续可优化为切割jar包，然后传jar包后，再远程打镜像
    /*需要传递的值
        config.project
        config.deploySVCName
        config.imageTag
        config.parallelJobs
    */
    def String username = 'bfops'
    def String password = 'iAmPassword'
    def String officeRegistry = 'IAmIPaddress:8765'
    def String extUsername = 'bfops'
    def String extPasswrod = 'iAmPassword'
    def String prodRegistry = 'IAmIPaddress'
    imageName = "${officeRegistry}/${config.project}/${config.deploySVCName}:${config.imageTag}"
    extranetImageName = "${prodRegistry}/${config.project}/${config.deploySVCName}:${config.imageTag}"
    tempImagePkgName = "${config.deploySVCName}-${config.imageTag}.tar"
    tempDir = "/iamusername/${config.deploySVCName}-${config.imageTag}"
    try {
        // 先清理这个目录，然后再创建
        sh "[ -d temp_docker_rsync_dir ] && rm -rf temp_docker_rsync_dir"
        sh "[ -d temp_docker_rsync_dir ] || mkdir temp_docker_rsync_dir"
    } catch(Exception err) {
        println(err.getMessage())
        println(err.toString())
    }
    dir("${env.WORKSPACE}/temp_docker_rsync_dir") {
        sh """
            podman login -u ${username} -p '${password}' ${officeRegistry}
            podman image pull ${imageName}
            podman image save --quiet -o ${tempImagePkgName} ${imageName}
            podman image rm -f ${imageName}
            ls -lh ${tempImagePkgName}
        """
    }
    container('tools') {
        dir("${env.WORKSPACE}/temp_docker_rsync_dir") {
            try {
                sh """
                    echo "开始切割镜像文件为多个小文件"
                    split -a 5 -b 1m ${tempImagePkgName} '${tempImagePkgName}.'
                    rm -f ${tempImagePkgName}
                    sshpass -p iampassword ssh -o StrictHostKeyChecking=no -p65000 iamusername@IAmIPaddress '[ -d ${tempDir} ] || rm -rf ${tempDir};[ -d ${tempDir} ] || mkdir ${tempDir}'
                    find . -type f -name "${tempImagePkgName}.*" | SHELL=/bin/sh parallel --linebuffer --jobs=${config.parallelJobs} 'sshpass -p iampassword rsync -e "ssh -p65000 -o StrictHostKeyChecking=no" -azP {} iamusername@IAmIPaddress:${tempDir}/' 
                """
            } catch(Exception err) {
                println(err.getMessage())
                println(err.toString())
            }
            sh """
                echo "执行补充文件传输"
                sshpass -p iampassword rsync -e "ssh -p65000 -o StrictHostKeyChecking=no" -azP "${env.WORKSPACE}/temp_docker_rsync_dir/" iamusername@IAmIPaddress:${tempDir}/
                echo "传输文件完成，开始导入镜像"
                sshpass -p iampassword ssh -o StrictHostKeyChecking=no -p65000 iamusername@IAmIPaddress "cd ${tempDir} && cat ${tempImagePkgName}.* > ${tempImagePkgName};"
                sshpass -p iampassword ssh -o StrictHostKeyChecking=no -p65000 iamusername@IAmIPaddress "docker login -u ${extUsername} -p '${extPasswrod}' ${prodRegistry}; \
                    ls -lh ${tempDir}/${tempImagePkgName}; \
                    docker image load -i ${tempDir}/${tempImagePkgName}; \
                    rm -rf ${tempDir}; \
                    docker image tag ${imageName} ${extranetImageName}; \
                    docker image push ${extranetImageName}; \
                    docker image rm -f ${imageName} ${extranetImageName};"
            """
        }
    }
    sh "[ -d temp_docker_rsync_dir ] && rm -rf temp_docker_rsync_dir"
    println("镜像已导入到habor仓库${prodRegistry}")
}
