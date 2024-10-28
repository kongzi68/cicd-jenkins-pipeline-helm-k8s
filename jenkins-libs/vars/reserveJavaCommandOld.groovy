def call(Map config = [:]) {
    configEnv = libTools.splitNamespaces(config.namespaces)
    configEnvSuffix = configEnv[1]
    configENV = configEnv[2]
    // println(config.namespaces)
    // println(configEnvSuffix)
    // println(configENV)
    // 获取当前服务的 command 启动参数
    javaCommand = sh (script: """
        kubectl --kubeconfig=kube-config.yaml -n ${config.namespaces} get ${config.k8sRSType} ${config.deploySVCName}-${configEnvSuffix} -o jsonpath={.spec.template.spec.containers[*].command} \
        | awk -F '\"' '{print \$(NF-1)}' | sed -rn 's#java(.*)-jar.*#\\1# p' | awk '\$1=\$1'
        """, returnStdout: true).trim()
    // 为空时，设置初始 java 启动 jar 包的参数选项
    // println('javaCommand 的值1：' + javaCommand )
    if (! javaCommand ) {
        javaCommand = "-Dspring.profiles.active=${configENV} -DLOG_BASE_PATH=/var/log"
        // println('javaCommand 的值2：' + javaCommand )
    }
    // 因helm chart模板包含jmx参数，因此去重 jmx 参数
    javaCommand = libTools.jmxRemoveDuplicate(javaCommand)
    // 命令字符串去重
    javaCommand = libTools.strRemoveDuplicate(javaCommand)
    println("服务 ${config.deploySVCName} 的 JAVA_COMMAND：" + javaCommand)
    sh "sed -i 's#JAVA_COMMAND#${javaCommand}#' ${config.deploySVCName}-values.yaml"
}