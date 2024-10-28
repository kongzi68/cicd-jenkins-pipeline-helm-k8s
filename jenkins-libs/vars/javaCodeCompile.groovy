def call(String deploySVCName = '') {
    /* 按照服务名称进行构建 */
    try {
        if (deploySVCName == '') {
            echo '单服务，直接构建'
            svcGradleBootJarString = 'bootJar'
        } else {
            echo '按照服务名称进行构建'
            svcGradleBootJarString = "${deploySVCName}:bootJar"
        }
        // sh "gradle rab-svc-migrate-to-beyond:bootJar -x test"
        sh """
            PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:/usr/local/games:/snap/bin:/opt/gradle/${params.GRADLE_VERSION}/bin
            export LANG=zh_CN.UTF-8
            export LC_ALL=zh_CN.UTF-8
            # export GRADLE_USER_HOME="${WORKSPACE}/.gradle"
            # export -p
            gradle --stacktrace --no-daemon --debug -g "${WORKSPACE}/.gradle" ${svcGradleBootJarString} -x test
        """
    } catch (Exception err) {
        echo err.getMessage()
        echo err.toString()
        unstable '构建失败'
    }
}
