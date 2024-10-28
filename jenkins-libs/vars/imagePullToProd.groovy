def call(Map config = [:]) {
    /*
        config.project
        config.deploySVCName
        config.imageTag
    */
    def Map imageDict = [:]
    // def String harborAuth = '443eb7ee-c21b-4e32-b449-e01d83171672'
    def String username = 'bfops'
    def String password = 'iAmPassword'
    def String officeRegistry = 'IAmIPaddress:8765'
    def String extUsername = 'bfops'
    def String extPasswrod = 'iAmPassword'
    def String prodRegistry = 'harbor.betack.com'
    imageName = "${officeRegistry}/${config.project}/${config.deploySVCName}:${config.imageTag}"
    extranetImageName = "${prodRegistry}/${config.project}/${config.deploySVCName}:${config.imageTag}"
    println("imageName: " + imageName)
    println("extranetImageName: " + extranetImageName)
    imageDict.put('imageName', imageName)
    imageDict.put('extranetImageName', extranetImageName)
    // 第一次检查
    checkImageTagExites = checkImageTag(project: config.project, deploySVCName: config.deploySVCName, imageTag: config.imageTag)
    if (checkImageTagExites) {
        println("镜像仓库中已存在该镜像tag: ${config.imageTag}，无需再传！")
        return imageDict
    } else {
        parallelRsyncDockerImage(project: config.project, deploySVCName: config.deploySVCName, imageTag: config.imageTag, parallelJobs: '20')
    }
    // 第二次检查
    checkImageTagExites = checkImageTag(project: config.project, deploySVCName: config.deploySVCName, imageTag: config.imageTag)
    if (checkImageTagExites) {
        println("镜像仓库中已存在该镜像tag: ${config.imageTag}，无需再传！")
    } else {
        sh """
            podman login -u ${username} -p '${password}' ${officeRegistry}
            podman image pull ${imageName}
            podman image tag ${imageName} ${extranetImageName}
            podman login -u ${extUsername} -p '${extPasswrod}' ${prodRegistry}
            podman image push ${extranetImageName}
            #podman login -u ${extUsername} -p '${extPasswrod}' ${prodRegistry} --tls-verify=false
            #podman image push ${extranetImageName} --tls-verify=false
            podman image rm -f ${imageName} ${extranetImageName}
        """
    }
    return imageDict
}
