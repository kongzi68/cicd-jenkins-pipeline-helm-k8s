def call(Map config = [:]) {
    /* helm 部署 job 到 k8s 集群
        注意：
            这里只修改 helm values.yaml 中的 IMAGE_TAG 与 NAMESPACEPREFIX
            其它的请提前修改。
        helm 更新 job 问题，因为一些不可变的资源导致不能使用 helm upgrade
        因此：job的更新，每次helm delete后新建
        1. Cannot upgrade a release with Job #7725 https://github.com/helm/helm/issues/7725
        2. Can't update Jobs, field is immutable #89657 https://github.com/kubernetes/kubernetes/issues/89657
        3. Helm 3 upgrade failed - Immutable field. #7173 https://github.com/helm/helm/issues/7173
    */
    /*  常用参数
        config.deploySVCName
        config.imageTag
        config.deployToEnv
        config.chartName
        config.chartVersion
    */
    echo "正在部署到 " + config.deployToEnv + " 环境."
    echo "正在执行helm命令，更新${config.deploySVCName}服务版本${config.imageTag}到${config.deployToEnv}环境."
    // 判断是否已经部署过
    def getDeployName = sh (script: "helm --kubeconfig kube-config.yaml -n ${config.deployToEnv} list -l name==${config.deploySVCName} -q", returnStdout: true).trim()
    println("getDeployName：" + getDeployName)
    println("deploySVCName：" + config.deploySVCName)
    if (getDeployName == config.deploySVCName) {
        echo "正在删除JOB ${config.deploySVCName}"
        sh "helm --kubeconfig kube-config.yaml -n ${config.deployToEnv} delete ${config.deploySVCName}"
    }
    echo "正在对服务${config.deploySVCName}进行部署"
    sh """
        helm pull oci://harbor.betack.com/libs-charts/${config.chartName} --version ${config.chartVersion}
        helm --kubeconfig kube-config.yaml install ${config.deploySVCName} -n ${config.deployToEnv} -f ${config.deploySVCName}-values.yaml ${config.chartName}-${config.chartVersion}.tgz
    """
}