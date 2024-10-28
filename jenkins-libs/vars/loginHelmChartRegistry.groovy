def call() {
    echo "helm登录registry仓库"
    withCredentials([usernamePassword(credentialsId: '443eb7ee-c21b-4e32-b449-e01d83171672', passwordVariable: 'password', usernameVariable: 'username')]) {
        sh """
            export HELM_EXPERIMENTAL_OCI=1
            helm registry login harbor.betack.com --username ${username} --password ${password}
        """
    }
}