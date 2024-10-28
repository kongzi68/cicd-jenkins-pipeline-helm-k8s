def call() {
    def _CLEAN_BUILD = Boolean.valueOf("${params.CLEAN_BUILD}")
    if (_CLEAN_BUILD) {
        echo "执行命令：gradle clean，清理构建环境"
        sh """
            PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/gradle/${params.GRADLE_VERSION}/bin
            export LANG=zh_CN.UTF-8
            export LC_ALL=zh_CN.UTF-8
            export GRADLE_USER_HOME="${WORKSPACE}/.gradle"
            gradle --no-daemon -g "${WORKSPACE}/.gradle" clean
        """
    }
}