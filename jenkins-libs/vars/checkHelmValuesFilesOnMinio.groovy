def call(Map config = [:]) {
    /*需要传递的值
        config.isEnabled
        config.k8sKey
        config.namespaces
        config.deploySVCName
        config.helmValuesFilePath
    */
    needToModifyTemplate = true  // 默认需要用模板文件进行创建values
    if (config.containsKey("isEnabled")) {
        // 传入isEnabled的时候，传入true才启用
        if (config.get('isEnabled') == true) {
            isEnabled = true
        } else {
            isEnabled = false
        }
    } else {
        isEnabled = true    // 兼顾旧版，没有传入isEnabled的时候，默认启用
    }
    helmValuesFile = "${config.deploySVCName}-values.yaml"
    // 检查Jenkins agent workspace下面是否存在yaml文件
    files = findFiles(glob: helmValuesFile)
    boolean filesExists = files.length > 0
    println('filesExists: ' + filesExists + ", " + 'isEnabled: ' + isEnabled)
    if (filesExists && isEnabled) {
        println('WORKSPACE下helm values文件存在：' + helmValuesFile)
        configFileProvider([configFile(fileId: "${config.k8sKey}", targetLocation: "kube-config.yaml")]){
            // 判断是否已经部署过
            getDeployName = sh (script: "helm --kubeconfig kube-config.yaml -n ${config.namespaces} list -l name==${config.deploySVCName} -q", returnStdout: true).trim()
        }
        println("getDeployName：" + getDeployName)
        println("deploySVCName：" + config.deploySVCName)
        if (getDeployName == config.deploySVCName) {
            // 下载helm value yaml文件
            // 注意：这里minio中文件不存在时，会继续构建，并用模板文件更新服务，后续需要上传一份到minio
            try {
                minioFile(doType: 'download', fileNamePath: helmValuesFile, namespace: config.namespaces)
                needToModifyTemplate = false  // 从minio存储中获取原有的values文件直接使用
            } catch(Exception err) {
                echo err.getMessage()
                echo err.toString()
                println("下载报错，用模板修改values文件.")
                copyHelmValuesFile(namespaces: config.namespaces, helmValuesFile: config.helmValuesFilePath, deploySVCName: config.deploySVCName)
            }
        } else {
            // 此命名空间下，该服务未部署过，用初始helm value yaml模板进行修改
            println("该服务未部署，用模板修改values文件.")
            copyHelmValuesFile(namespaces: config.namespaces, helmValuesFile: config.helmValuesFilePath, deploySVCName: config.deploySVCName)
        }
    } else {
        println('WORKSPACE下helm values文件不存在~~~，' + helmValuesFile)
        println("用模板修改values文件.")
        copyHelmValuesFile(namespaces: config.namespaces, helmValuesFile: config.helmValuesFilePath, deploySVCName: config.deploySVCName)
    }
    return needToModifyTemplate
}