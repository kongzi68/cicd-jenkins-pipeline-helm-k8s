def call(Map config = [:]) {
    def String harborAuth = '443eb7ee-c21b-4e32-b449-e01d83171672'
    def String username = 'bfops'
    def String password = 'iAmPassword'
    def String officeRegistry = 'IAmIPaddress:8765'
    def String extUsername = 'cn-east-2@SPDY9MSWX236589OME2JNB'
    def String extPasswrod = '69ac89d97788272ab100000000000000000dc2176882217c1b941576'
    def String hwcloudRegistry = 'swr.cn-east-2.myhuaweicloud.com'
    imageName = "${officeRegistry}/${config.project}/${config.deploySVCName}:${config.imageTag}"
    extranetImageName = "${hwcloudRegistry}/betack/${config.project}/${config.deploySVCName}:${config.imageTag}"
    println("imageName: " + imageName)
    println("extranetImageName: " + extranetImageName)
    sh """
        podman login -u ${username} -p '${password}' ${officeRegistry}
        podman image pull ${imageName}
        podman image tag ${imageName} ${extranetImageName}
        podman login -u ${extUsername} -p '${extPasswrod}' ${hwcloudRegistry}
        podman image push ${extranetImageName}
        podman image rm -f ${imageName} ${extranetImageName}
    """
}
