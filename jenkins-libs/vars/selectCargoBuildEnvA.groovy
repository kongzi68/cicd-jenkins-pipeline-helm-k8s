def call(Map config = [:]) {
    // 配置cargo代理
    // 适配 rust:1.74.1-slim-bookworm Debian 12镜像用
    config_file = """
        [source.crates-io]
        replace-with = 'rsproxy-sparse'
        [source.rsproxy]
        registry = "https://rsproxy.cn/crates.io-index"
        [source.rsproxy-sparse]
        registry = "sparse+https://rsproxy.cn/index/"
        [registries.rsproxy]
        index = "https://rsproxy.cn/crates.io-index"
        [net]
        git-fetch-with-cli = true
    """.stripIndent()
    writeFile file: 'config_file', text: "${config_file}", encoding: 'UTF-8'
    echo "当前 cargo 版本信息如下："
    sh '''
        pwd; ls -lh config_file
        [ -d $HOME/.cargo ] || mkdir -p $HOME/.cargo 
        mv config_file $HOME/.cargo/config
        echo "配置的代理内容如下："
        cat $HOME/.cargo/config
        #+ 更新 rust 和 cargo 到最新稳定版
        # rustup update stable
        # rustc -v
        cargo --version
    '''
}