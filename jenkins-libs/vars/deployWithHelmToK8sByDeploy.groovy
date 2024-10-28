def call(Map config = [:]) {
    /* helm 部署 deploy 到 k8s 集群
        config.deploySVCName
        config.imageTag
        config.deployToEnv
        config.chartName
        config.chartVersion
        config.chartsProject
    */
    echo "正在部署到 " + config.deployToEnv + " 环境."
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
    if (config.containsKey("chartsProject")) {
        chartsProject = config.chartsProject
    } else {
        chartsProject = 'libs-charts'
    }
    sh """
        export HELM_EXPERIMENTAL_OCI=1
        helm pull oci://harbor.betack.com/${chartsProject}/${config.chartName} --version ${config.chartVersion}
        helm --kubeconfig kube-config.yaml ${deployType} ${config.deploySVCName} -n ${config.deployToEnv} -f ${config.deploySVCName}-values.yaml ${config.chartName}-${config.chartVersion}.tgz
    """
}