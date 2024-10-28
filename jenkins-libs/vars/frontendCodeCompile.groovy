def call(Map config = [:]) {
    try {
        switch(config.get('envType')) {
            case 'npm':
                println( 'envType:' + config.get('envType'))
                sh """
                    export LANG=zh_CN.UTF-8
                    export LC_ALL=zh_CN.UTF-8
                    npm install
                    npm run build
                """
            break
            case 'npm1':
                println( 'envType:' + config.get('envType'))
                sh """
                    export LANG=zh_CN.UTF-8
                    export LC_ALL=zh_CN.UTF-8
                    npm install
                    npm run i18n
                    npm run build
                """
            break
            case 'yarn':
                println( 'envType:' + config.get('envType'))
                sh """
                    export LANG=zh_CN.UTF-8
                    export LC_ALL=zh_CN.UTF-8
                    yarn install
                    yarn run i18n
                    yarn run build:dll:web
                    yarn run build
                """
            break
            case 'yarn1':
                println( 'envType:' + config.get('envType'))
                sh """
                    export LANG=zh_CN.UTF-8
                    export LC_ALL=zh_CN.UTF-8
                    yarn install
                    yarn run i18n
                    yarn run build
                """
            break
            default:
                println( 'envType:' + config.get('envType') + '默认情况，执行传入的脚本')
                sh """
                    export LANG=zh_CN.UTF-8
                    export LC_ALL=zh_CN.UTF-8
                    ${config.buildScripts}
                """
            break
        }
    } catch (Exception err) {
        echo err.getMessage()
        echo err.toString()
        error '构建失败'
    }
}