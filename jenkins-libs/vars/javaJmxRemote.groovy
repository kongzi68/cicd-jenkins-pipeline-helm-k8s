def call(Map config = [:]) {
    /*
        config.k8s
        config.namespaces
        config.deploySVCName
    
    */
    switch(config.k8s) {
        case 'shanghai':
            // shanghai prod kubeadm k8s
            k8sNodeIP = 'IAmIPaddress'
            k8sAuth = 'fccafb7c-8128-4a91-87b2-3b7cb6940343'
            randomDivisor = 30000  // 取值范围 30000-32767
            randomMinNUM = 30000
        break
        case 'rke2k8s':
            // rke2 k8s
            k8sNodeIP = 'IAmIPaddress'
            k8sAuth = 'gd4efaf3-23f9-4f31-aaaa-3e3baa9618d4'
            randomDivisor = 30000   // 取值范围 30000~59999
            randomMinNUM = 30000
        break
        case 'cdk8s':
            // 成都office kubeadm k8s
            k8sNodeIP = 'IAmIPaddress'
            k8sAuth = 'fd4efaf3-23f9-4f31-a085-3e3baa9618d4'
            randomDivisor = 30000   // 取值范围 30000-32767
            randomMinNUM = 30000
        break
    }
    // 生成jmx远程调试端口
    def _JMXREMOTE = Boolean.valueOf("${params.JMXREMOTE}")
    // 获取之前已有的jmx远程调试端口
    configEnv = libTools.splitNamespaces(config.namespaces)
    configEnvSuffix = configEnv[1]
    def jmxNodeport = libTools.getSVCNodePortByName(config.namespaces, config.deploySVCName, 'jmxremoteport', configEnvSuffix, 'jmxremote')
    if (_JMXREMOTE) {
        container('tools') {
            script{
                if(! jmxNodeport) {
                    isExits = true
                    while(isExits) {
                        switch(config.k8s) {
                            case ['cdk8s', 'shanghai']:
                                nodePort = Math.abs(new Random().nextInt() % randomDivisor % 2767) + randomMinNUM
                            break
                            default:
                                nodePort = Math.abs(new Random().nextInt() % randomDivisor) + randomMinNUM
                            break
                        }
                        // 若通的，返回true，即继续寻找可用的端口
                        useNcCommandCheck = sh (script: "nc -w 5 -vz ${k8sNodeIP} ${nodePort} && echo 'true' || echo 'false'", returnStdout: true).trim()
                        useNcCommandCheck = Boolean.valueOf(useNcCommandCheck)
                        // 若为false时，进一步用kubectl检查确认
                        if (useNcCommandCheck) {
                            isExits = true
                        } else {
                            configFileProvider([configFile(fileId: k8sAuth, targetLocation: "kube-config.yaml")]) {
                                useKubectlCheck = sh (script: "kubectl --kubeconfig=kube-config.yaml get svc -A | grep ${nodePort} > /dev/null && echo 'true' || echo 'false'", returnStdout: true).trim()
                                useKubectlCheck = Boolean.valueOf(useKubectlCheck)
                            }
                            if (useKubectlCheck) {
                                isExits = true
                            } else {
                                isExits = false
                            }
                        }
                    }
                    println("设置JMX_NODEPORT为：" + nodePort)
                    jmxNodeport = nodePort
                }
            }
        }
        // 判断是否开启jmxremote远程调试功能
        println("JMXREMOTE：" + _JMXREMOTE)
        echo "开启jmxremote远程调试功能"
        sh """
            sed -i 's#JMX_NODEPORT#${jmxNodeport}#' ${config.deploySVCName}-values.yaml
            sed -i 's#JMX_NODEIP#${k8sNodeIP}#' ${config.deploySVCName}-values.yaml
        """
    }
    return jmxNodeport
}