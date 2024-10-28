def call(Map config = [:]) {
    /*
        config.envType    // 可选值：npm、pnpm、yarn
        config.nodeVersion
        config.pnpmVersion
    */
    switch(config.envType) {
        case 'npm':
            echo 'npm'
            sh """
                npm config set registry http://registry.npmmirror.com
                # Electron 自定义镜像和缓存
                export ELECTRON_MIRROR="https://npmmirror.com/mirrors/electron/"
                export electron_config_cache="${WORKSPACE}/.cache/electron/"
                npm install -g n
                # 安装指定版本的node.js
                export N_NODE_MIRROR="https://mirrors.aliyun.com/nodejs-release/"
                export N_CACHE_PREFIX="${WORKSPACE}/.n/cache"
                n ${config.nodeVersion}
                hash -r
                npm config set cache ${WORKSPACE}/.npm
                npm config list
            """
        break
        case 'pnpm':
            echo 'pnpm'
            sh """
                npm config set registry http://registry.npmmirror.com
                # Electron 自定义镜像和缓存
                export ELECTRON_MIRROR="https://npmmirror.com/mirrors/electron/"
                export electron_config_cache="${WORKSPACE}/.cache/electron/"
                npm install -g n
                # 安装指定版本的node.js
                export N_NODE_MIRROR=https://mirrors.aliyun.com/nodejs-release/
                export N_CACHE_PREFIX=${WORKSPACE}/.n/cache
                n ${config.nodeVersion}
                hash -r
                npm config set cache ${WORKSPACE}/.npm
                npm install -g pnpm@${config.pnpmVersion}
                npm config list
            """
        break
        case 'pnpm':
            echo 'pnpm'
            sh """
                npm config set registry http://registry.npmmirror.com
                npm config set electron_mirror https://npmmirror.com/mirrors/electron/
                npm install -g n
                # 安装指定版本的node.js
                export N_NODE_MIRROR=https://mirrors.aliyun.com/nodejs-release/
                n ${config.nodeVersion}
                hash -r
                #npm install -g pnpm@7.8.0
                npm install -g pnpm@${config.pnpmVersion}
                npm config list
            """
        break
        case 'yarn':
            echo 'yarn'
            sh """
                npm config set registry https://registry.npmmirror.com
                yarn config set registry https://registry.npmmirror.com
                yarn config set sass-binary-site https://npmmirror.com/mirrors/node-sass/
                # Electron 自定义镜像和缓存
                export ELECTRON_MIRROR="https://npmmirror.com/mirrors/electron/"
                export electron_config_cache="${WORKSPACE}/.cache/electron/"
                npm config rm proxy
                npm config rm https-proxy
                npm install -g n
                # 安装指定版本的node.js 
                export N_NODE_MIRROR=https://mirrors.aliyun.com/nodejs-release/
                # n 模块安装包下载缓存
                export N_CACHE_PREFIX=${WORKSPACE}/.n/cache
                n ${config.nodeVersion}
                hash -r
                yarn config set ignore-engines true
                # 查询npm缓存目录
                npm config get cache
                # 设置npm缓存目录
                npm config set cache ${WORKSPACE}/.npm
                # 查询yarn缓存目录
                yarn cache dir
                # 改变yarn缓存位置
                yarn config set cache-folder ${WORKSPACE}/.yarn/cache
                npm config list -l
                yarn config list
            """
        break
    }
}