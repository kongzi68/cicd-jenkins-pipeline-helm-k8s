def call() {
    // 若未勾选需要部署的服务，则立即退出
    def String _IS_NULL = params.DEPLOY_SVC_NAME
    println(_IS_NULL)
    if ( _IS_NULL == '' ) {
        error '未勾选需要部署的服务'
    }
    // if (_IS_NULL.length() == 0) {
    //     error '未勾选需要部署的服务'
    // }
}