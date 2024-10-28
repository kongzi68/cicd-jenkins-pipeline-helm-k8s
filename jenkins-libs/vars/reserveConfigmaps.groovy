def call(Map config = [:]) {
    /*
        config.namespaces
        config.configmapsName
        config.configMountName
    */
    try {
        // kubectl -n new-rab-dev-4 get configmaps rab-svc-api-app-akka-cluster-conf -o yaml
        configmapsData = sh (script: """
            kubectl --kubeconfig=kube-config.yaml -n ${config.namespaces} get configmaps ${config.configmapsName} -o yaml
            """, returnStdout: true).trim()
        if(configmapsData != 'null') {
            configmapsYaml = readYaml text: configmapsData
            configData = configmapsYaml['data'][config.configMountName]
            println("服务原始配置文件：" + configData)
        }
    } catch(Exception err) {
        println(err.getMessage())
        println(err.toString())
        // 获取失败时，设置初始 java 启动 jar 包的参数选项
        configData = "请自行去k8s配置"
        println('服务配置文件待配置！！！' + configData )
    }
    return configData
}