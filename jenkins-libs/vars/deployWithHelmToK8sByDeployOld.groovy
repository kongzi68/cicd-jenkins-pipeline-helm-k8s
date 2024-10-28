def call(Map config = [:]) {
    /* helm 部署 deploy 到 k8s 集群
        注意：
            这里只修改 helm values.yaml 中的 IMAGE_TAG 与 NAMESPACEPREFIX
            其它的请提前修改。
    */
    echo "正在部署到 " + config.deployToEnv + " 环境."
    configEnv = libTools.splitNamespaces(config.deployToEnv)
    configEnvPrefix = configEnv[0]
    configEnvSuffix = configEnv[1]
    println("CONFIG_ENV：" + configEnvSuffix)
    println("项目简称，用于命名空间的前缀：" + configEnvPrefix)
    sh """
        sed -i 's#IMAGE_TAG#${config.imageTag}#' ${config.deploySVCName}-values.yaml
        sed -i 's#NAMESPACEPREFIX#${configEnvPrefix}#' ${config.deploySVCName}-values.yaml
        cat ${config.deploySVCName}-values.yaml
    """
    echo "正在执行helm命令，更新${config.deploySVCName}服务版本${config.imageTag}到${config.deployToEnv}环境."
    // 判断是否已经部署过
    def getDeployName = sh (script: "helm --kubeconfig kube-config.yaml -n ${config.deployToEnv} list -l name==${config.deploySVCName} -q", returnStdout: true).trim()
    println("getDeployName：" + getDeployName)
    println("deploySVCName：" + config.deploySVCName)
    if (getDeployName == config.deploySVCName) {
        echo "正在对服务${config.deploySVCName}进行升级"
        deployType = 'upgrade'
    } else {
        echo "正在对服务${config.deploySVCName}进行部署"
        deployType = 'install'
    }
    sh """
        export HELM_EXPERIMENTAL_OCI=1
        helm pull oci://harbor.betack.com/libs-charts/${config.chartName} --version ${config.chartVersion}
        helm --kubeconfig kube-config.yaml ${deployType} ${config.deploySVCName} -n ${config.deployToEnv} -f ${config.deploySVCName}-values.yaml ${config.chartName}-${config.chartVersion}.tgz
    """
}