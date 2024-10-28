def call(Map config = [:]) {
    /*需要传递的值
        config.project
        config.deploySVCName
        config.imageTag
        config.harborApiAddr
        config.harborApiAuth
    */
    textmod = "q=tags=~${config.imageTag}"
    // https://harbor.betack.com/api/v2.0/projects/rab/repositories/rab-svc-api-app/artifacts?q=tags=~c23ec27101-921
    url = "${config.harborApiAddr}/api/v2.0/projects/${config.project}/repositories/${config.deploySVCName}/artifacts?${textmod}"
    println(url)
    try {
        props = readJSON text: httpRequestFunc(url, config.harborApiAuth, '', 'GET')
        imgTag = props[0]['tags'][0]['name']
        if (imgTag) {
            println('查询到imageTag:' + imgTag)
            return true
        } else {
            println('未查询到imageTag:' + imgTag)
            return false
        }
    } catch(Exception err) {
        println(err.getMessage())
        println(err.toString())
        return false
    }
}
