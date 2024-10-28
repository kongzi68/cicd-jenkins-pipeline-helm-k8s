def call(Map config = [:]) {
    /*
        config.namespaces
        config.k8sRSType
        config.deploySVCName
    */
    def configEnv = libTools.splitNamespaces(config.namespaces)
    def configEnvSuffix = configEnv[1]
    def configENV = configEnv[2]
    // println(config.namespaces)
    // println(configEnvSuffix)
    // println(configENV)
    def javaCommand = 'null'
    try {
        // 特例处理
        switch(config.deploySVCName) {
            case ['saas-data-server']:
                javaPKGPath = "/opt/betack/data-etl-server.jar"
            break
            default:
                javaPKGPath = "/opt/betack/${config.deploySVCName}.jar"
            break
        }
        javaCommand = sh (script: """
            kubectl --kubeconfig=kube-config.yaml -n ${config.namespaces} get ${config.k8sRSType} ${config.deploySVCName}-${configEnvSuffix} -o yaml
            """, returnStdout: true).trim()
        if(javaCommand != 'null') {
            commandYaml = readYaml text: javaCommand
            // 获取当前服务的 command 启动参数
            javaCommand = commandYaml['spec']['template']['spec']['containers'][0]['command'][-1]
            println("服务原始command：" + javaCommand)
            // 先去重与去头去尾，会去掉Dall.dag.trigger.time参数
            javaCommand = libTools.strRemoveDuplicateA(javaCommand, javaPKGPath)
            // println('javaCommand 的值1：' + javaCommand )
            // 因helm chart模板包含jmx参数，因此去重 jmx 参数
            javaCommand = libTools.jmxRemoveDuplicateA(javaCommand)
            // 命令字符串再次去重
            javaCommand = libTools.strRemoveDuplicateA(javaCommand, javaPKGPath)
            println("服务 ${config.deploySVCName} 的 JAVA_COMMAND：" + javaCommand)
        }
    } catch(Exception err) {
        println(err.getMessage())
        println(err.toString())
        // 获取失败时，设置初始 java 启动 jar 包的参数选项
        javaCommand = "-Dspring.profiles.active=${configENV} -DLOG_BASE_PATH=/var/log/${config.deploySVCName}"
        println('javaCommand的值：' + javaCommand )
    }
    return javaCommand
}