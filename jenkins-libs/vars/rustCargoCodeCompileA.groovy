def call(Map config = [:]) {
    /*  适配 rust:1.74.1-slim-bookworm Debian 12镜像用
        可以先配置debian12国内源
        常用参数：
        config.containsKey
        config.buildCommand
        config.brinSVCName
    */
    if (config.containsKey("buildCommand") == false) {
        buildCommand = "CARGO_HOME=${WORKSPACE}/.cargo cargo build --release"
        sh """
            [ -d ${WORKSPACE}/.cargo ] || mkdir -p ${WORKSPACE}/.cargo
            cp -a /iamusername/.cargo/config ${WORKSPACE}/.cargo/config
        """
    } else {
        buildCommand = config.buildCommand
    }
    try {
        sh """
            rm -rf /etc/apt/sources.list.d/*
            apt-get clean all; apt-get update; apt-get install -y git g++ make
        """
        withCredentials([usernameColonPassword(credentialsId: '3d040389-9dfe-4c0d-9dab-9f6487f10409', variable: 'USERPASS')]) {
            sh """
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
