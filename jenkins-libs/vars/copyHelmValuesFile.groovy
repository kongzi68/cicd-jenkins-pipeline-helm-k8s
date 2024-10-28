def call(Map config = [:]) {
    echo "用HELM部署服务: ${config.deploySVCName}到命名空间：${config.namespaces}"
    println(config.namespaces)
    sh """
        pwd
        ls -lh ${config.helmValuesFile}
        cp -a ${config.helmValuesFile} ${config.deploySVCName}-values.yaml
        ls -lh ${config.deploySVCName}-values.yaml
        ## 如果删了，对于一次性发版到多个环境，就不能正确拷贝模板
        # [ -d temp_jenkins_workspace ] && rm -rf temp_jenkins_workspace*
    """
}