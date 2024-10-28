def call(env, stackID, url, auth) {
    def serviceMap = [:]
    def tempYamlFile = "${env}-${stackID}.yaml"
    def _IS_UPDATE_SERVICE_LIST = Boolean.valueOf("${params.IS_UPDATE_SERVICE_LIST}")
    if (_IS_UPDATE_SERVICE_LIST) {
        println("手动触发，从rancher API获取最新服务清单")
        serviceMap = getNewServiceList(serviceMap, url, auth, tempYamlFile)
        return serviceMap
    }
    def files = findFiles(glob: tempYamlFile)
    boolean exists = files.length > 0
    if (exists) {
        println("直接从暂存文件读取："+ tempYamlFile)
        serviceMap = readYaml file: tempYamlFile
    } else {
        println("暂存文件不存在，本次从rancher API获取最新服务清单")
        serviceMap = getNewServiceList(serviceMap, url, auth, tempYamlFile)
    }
    return serviceMap
}


def getNewServiceList(serviceMap, url, auth, tempYamlFile) {
    def reqBody = "action=activateservices"
    def props = readJSON text: httpRequestFunc(url, auth, reqBody)
    def serviceIds = props['serviceIds']
    for (item in serviceIds) {
        reqBody = "action=exportconfig&serviceIds=${item}"
        props = readJSON text: httpRequestFunc(url, auth, reqBody)
        svcYaml = readYaml text: props['dockerComposeConfig']
        svcName = svcYaml['services'].keySet()
        if ( svcName != null ) {
            serviceMap.put(svcName[0], item)
        }
        // println(svcName)
    }
    writeYaml file: tempYamlFile, data: serviceMap, overwrite: true
    return serviceMap
}
