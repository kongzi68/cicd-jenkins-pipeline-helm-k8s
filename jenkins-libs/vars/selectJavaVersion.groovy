def call(String registry) {
    echo '当前 java 版本信息如下：'
    sh 'java -version'
    def javaVersion = sh (script: "java --version | head -1 | awk -F '[ .]+' '{print \$2}'", returnStdout: true).trim()
    if ("${params.JDK_VERSION}" != "${javaVersion}") {
        echo '开始切换 java 版本.'
        try {
            sh """
                #+ openjdk
                # update-alternatives --install /usr/bin/java java /usr/lib/jvm/java-"${params.JDK_VERSION}"-openjdk-amd64/bin/java 1081
                # update-alternatives --config java
                # update-alternatives --set java /usr/lib/jvm/java-"${params.JDK_VERSION}"-openjdk-amd64/bin/java
                #+ zulu jdk
                update-alternatives --install /usr/bin/java java /usr/lib/jvm/zulu"${params.JDK_VERSION}"/bin/java 1807201
                update-alternatives --config java
                update-alternatives --set java /usr/lib/jvm/zulu"${params.JDK_VERSION}"/bin/java
                java -version
            """
            // jdkImage = "${registry}/libs/amazoncorretto:${params.JDK_VERSION}"
            // jdkImage = "${registry}/libs/zulu-openjdk:${params.JDK_VERSION}-ubuntu-tools"
            // jdkImage = "${registry}/libs/zulu-openjdk:${params.JDK_VERSION}-centos7-tools"
            jdkImage = "${registry}/libs/zulu-openjdk:${params.JDK_VERSION}-focal-tools"
        } catch (Exception err) {
            echo err.getMessage()
            echo err.toString()
            unstable '构建失败'
        }
    } else {
        echo "默认 JDK 版本为 ${javaVersion}"
        // jdkImage = "${registry}/libs/amazoncorretto:${javaVersion}"
        // jdkImage = "${registry}/libs/zulu-openjdk:${javaVersion}-ubuntu-tools"
        // jdkImage = "${registry}/libs/zulu-openjdk:${params.JDK_VERSION}-centos7-tools"
        jdkImage = "${registry}/libs/zulu-openjdk:${params.JDK_VERSION}-focal-tools"
    }
    return jdkImage
}
