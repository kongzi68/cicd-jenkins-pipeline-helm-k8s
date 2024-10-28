def call(Map config = [:]) {
    /*
        config.containsKey
        config.buildCommand
        config.brinSVCName
    */
    if (config.containsKey("buildCommand") == false) {
        buildCommand = "CARGO_HOME=${WORKSPACE}/.cargo cargo build --release"
        sh "cp -a /iamusername/.cargo/config ${WORKSPACE}/.cargo/config"
    } else {
        buildCommand = config.buildCommand
    }
    try {
        withCredentials([usernameColonPassword(credentialsId: '3d040389-9dfe-4c0d-9dab-9f6487f10409', variable: 'USERPASS')]) {
            sh """
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:/usr/local/games:/snap/bin
                . /etc/profile
                # 这个项目依赖需要从gitlab拉代码
                # sleep 3600
                echo 'https://${USERPASS}@code.betack.com' > ${WORKSPACE}/git-credentials
                git config --global credential.helper 'store --file ${WORKSPACE}/git-credentials'
                eval ${buildCommand}
            """
        }
        echo "构建的二进制命令包 ${config.brinSVCName} 信息如下："
        sh "ls -lh target/release/${config.brinSVCName}; pwd"
    } catch (Exception err) {
        echo err.getMessage()
        echo err.toString()
        unstable '构建失败'
    }
}
