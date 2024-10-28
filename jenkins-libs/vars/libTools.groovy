def strRemoveDuplicate(str_command) {
    /*
        * 获取到的命令字符串内容去重
    */
    list0 = str_command.tokenize()
    list1 = []
    for ( item in list0 ) {
        // println(item)
        if ( ! list1.contains(item)) {
            list1.add(item)
        }
    }
    str_ret = list1.join(" ")
    // println(str_ret)
    return str_ret
}


def strRemoveDuplicateA(str_command, jarPKGName) {
    /*
        * 获取到的命令字符串内容去重
    */
    str_command = str_command.replaceAll(jarPKGName, "")
    // 去除定时计划任务参数
    str_command = str_command.replaceAll("-Dall.dag.trigger.time='(. ){4,5}.'", "")
    list0 = str_command.tokenize()
    list1 = []
    // println(list0)
    for ( item in list0 ) {
        switch(item) {
        case ['java', '-jar']:
            continue
        break
        }
        if ( ! list1.contains(item)) {
            // println(item)
            list1.add(item)
        }
    }
    str_ret = list1.join(" ")
    // println('函数strRemoveDuplicateA处理过：' + str_ret)
    return str_ret
}


def jmxRemoveDuplicate(str_command){
    /*
        去重jmx远程调试部分参数，因为helm chart包模板里面已经包含了
    */
    jmx_option = """-Dcom.sun.management.jmxremote \
        -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.authenticate=false \
        -Dcom.sun.management.jmxremote.local.only=false
    """
    // 去除多余的空格
    jmx_option = sh (script: "eval echo ${jmx_option}", returnStdout: true).trim()
    // println("jmx_option：" + jmx_option)
    // 预处理
    str_command = str_command.replaceAll("-Dcom.sun.management.jmxremote.port=[0-9]{5}", "")
    str_command = str_command.replaceAll("-Dcom.sun.management.jmxremote.rmi.port=[0-9]{5}", "")
    str_command = str_command.replaceAll("-Djava.rmi.server.hostname=([0-9]{1,3}.){3}[0-9]{1,3}", "")
    str_command = sh (script: "eval echo ${str_command}", returnStdout: true).trim()
    ret_str_command = str_command.replaceAll(jmx_option,"")
    // println("ret_str_command: "+ ret_str_command)
    return ret_str_command
}


def jmxRemoveDuplicateA(str_command){
    /*
        去重jmx远程调试部分参数，因为helm chart包模板里面已经包含了
        去除 akka config 配置文件参数部分
    */
    jmx_option = """-Dcom.sun.management.jmxremote \
        -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.authenticate=false \
        -Dcom.sun.management.jmxremote.local.only=false
    """
    akka_config = "-Dconfig.file=/opt/betack/akka-config.conf"
    // 去除多余的空格
    jmx_option = strRemoveDuplicate(jmx_option)
    // println("jmx_option：" + jmx_option)
    // 预处理
    str_command = str_command.replaceAll("-Dcom.sun.management.jmxremote.port=[0-9]{5}", "")
    str_command = str_command.replaceAll("-Dcom.sun.management.jmxremote.rmi.port=[0-9]{5}", "")
    str_command = str_command.replaceAll("-Djava.rmi.server.hostname=([0-9]{1,3}.){3}[0-9]{1,3}", "")
    str_command = strRemoveDuplicate(str_command)
    str_command = str_command.replaceAll(akka_config,"")
    ret_str_command = str_command.replaceAll(jmx_option,"")
    // println('函数jmxRemoveDuplicateA处理过：' + ret_str_command)
    return ret_str_command
}


def delDeploySVCName(List listDuplicateSVC, String retainSVC){
    /*
        * 去掉 offline 与 api-master
        * listDuplicateSVC, 都是同一个jar包的服务名称列表，比如 rab-svc-api-app,rab-svc-api-app-master,rab-svc-offline-app 用的同一个jar包
        * retainSVC，需要保留的服务名称
    */
    _svc_list = []
    for (item in params.DEPLOY_SVC_NAME.tokenize(',')) {
        switch(item) {
        case listDuplicateSVC:
            svc_name = retainSVC
        break
        default:
            svc_name = item
        break
        }
        if ( ! _svc_list.contains(svc_name) ) {
        _svc_list.add(svc_name)
        }
    }
    return _svc_list
}


def getSVCNodePort(namespaces, deploySVCName, podPort, deployENVSuffix, svcNameSalt='out') {
    /* 
        获取指定服务的nodePort端口
    */
    try {
        svc_nodeport = sh (script: """
            kubectl --kubeconfig=kube-config.yaml -n ${namespaces} get svc ${deploySVCName}-${svcNameSalt}-${deployENVSuffix} \
                -o jsonpath='{.spec.ports[?(@.port==${podPort})].nodePort}'
            """, returnStdout: true).trim()
        println("${deploySVCName} 的容器内端口 ${podPort} 对应的 NodePort：" + svc_nodeport)
    } catch(Exception err) {
        svc_nodeport = ''
        println("${deploySVCName} 的容器内端口 ${podPort} 对应的 NodePort 端口，获取失败！！！")
    }
    return svc_nodeport
}


def getSVCNodePortByName(namespaces, deploySVCName, podPortName, deployENVSuffix, svcNameSalt='out') {
    /* 
        获取指定服务的nodePort端口
    */
    try {
        svc_nodeport = sh (script: """
            kubectl --kubeconfig=kube-config.yaml -n ${namespaces} get svc ${deploySVCName}-${svcNameSalt}-${deployENVSuffix} \
                -o jsonpath='{.spec.ports[?(@.name=="${podPortName}")].nodePort}'
            """, returnStdout: true).trim()
        println("${deploySVCName} 的容器内端口名 ${podPortName} 对应的 NodePort：" + svc_nodeport)
    } catch(Exception err) {
        svc_nodeport = ''
        println("${deploySVCName} 的容器内端口名 ${podPortName} 对应的 NodePort 端口，获取失败！！！")
    }
    return svc_nodeport
}


def getSVCNodePortBySVCName(namespaces, deploySVCName, podPortName, serviceName) {
    /* 
        获取指定服务的nodePort端口
    */
    try {
        svc_nodeport = sh (script: """
            kubectl --kubeconfig=kube-config.yaml -n ${namespaces} get svc ${serviceName} \
                -o jsonpath='{.spec.ports[?(@.name=="${podPortName}")].nodePort}'
            """, returnStdout: true).trim()
        println("${deploySVCName} 的容器内端口名 ${podPortName} 对应的 NodePort：" + svc_nodeport)
    } catch(Exception err) {
        svc_nodeport = ''
        println("${deploySVCName} 的容器内端口名 ${podPortName} 对应的 NodePort 端口，获取失败！！！")
    }
    return svc_nodeport
}


def getSVCImagesMaps(namespaces, workloadType, deploySVCName) {
    /* 
        获取指定服务的imageName，适应pod为多个容器的情况
    */
    def serverYaml = 'null'
    def retImageNames = [:]
    try {
        serverYaml = sh (script: """
            kubectl --kubeconfig=kube-config.yaml -n ${namespaces} get ${workloadType} -l app-name=${deploySVCName} -o yaml
            """, returnStdout: true).trim()
        if(serverYaml != 'null') {
            tempYaml = readYaml text: serverYaml
            // imageName = tempYaml['items'][0]['spec']['template']['spec']['containers'][0]['image']
            imageNames = tempYaml['items'][0]['spec']['template']['spec']['containers']
            for(item in imageNames) {
                // println(item['name'])
                // println(item['image'])
                retImageNames.put(item['name'], item['image'])
            }
            // println(retImageNames)
        }
    } catch(Exception err) {
        println(err.getMessage())
        println(err.toString())
        println("${deploySVCName} 的镜像获取失败！！！")
    }
    return retImageNames
}


def getSVCImagesList(namespaces, workloadType, deploySVCName) {
    /* 
        获取指定服务的imageName，适应pod为单个容器的情况
    */
    def serverYaml = 'null'
    def imageNameList = []
    try {
        serverYaml = sh (script: """
            kubectl --kubeconfig=kube-config.yaml -n ${namespaces} get ${workloadType} -l app-name=${deploySVCName} -o yaml
            """, returnStdout: true).trim()
        if(serverYaml != 'null') {
            tempYaml = readYaml text: serverYaml
            imageNames = tempYaml['items'][0]['spec']['template']['spec']['containers']
            for(item in imageNames) {
                imageNameList.add(item['name'])
                imageNameList.add(item['image'])
            }
        }
    } catch(Exception err) {
        println(err.getMessage())
        println(err.toString())
        println("${deploySVCName} 的镜像获取失败！！！")
    }
    return imageNameList
}


def getServerWorkloadTypeByHelm(namespaces, deploySVCName) {
    /*
        获取服务的工作负载类型，只能获取到通过helm部署的且chart为这种类型的：
        bf-java-project-distributed-statefulset-0.1.3或bf-java-project-deploy-common-0.1.10
        然后其它的默认为deploy类型
    */
    def helmRetYaml = 'null'
    def workloadType = 'deploy'
    try {
        helmRetYaml = sh (script: """
            helm --kubeconfig=kube-config.yaml -n ${namespaces} list -l name==${deploySVCName} -o yaml
            """, returnStdout: true).trim()
        if(helmRetYaml != 'null') {
            tempYaml = readYaml text: helmRetYaml
            // 获取当前服务的 command 启动参数
            chartName = tempYaml[0]['chart']
            println(chartName)
            isMatches = chartName.matches("(.*)statefulset(.*)")
            if (isMatches) {
                workloadType = 'sts'
            }
        }
    } catch(Exception err) {
        println(err.getMessage())
        println(err.toString())
    }
    println('服务工作负载类型：' + workloadType )
    return workloadType
}


def splitImage(imageName) {
    /*
        比如镜像为：IAmIPaddress:8765/libs/elasticsearch/elasticsearch:7.17.3
        拆分为：[IAmIPaddress:8765, libs, libs/elasticsearch/elasticsearch, elasticsearch%252Felasticsearch, elasticsearch, 7.17.3]
    */
    retList = []
    if (imageName != '') {
        imageElement = imageName.tokenize('/')
        // println(imageElement)
        registryAddr = imageElement[0]
        if (imageElement.size == 2 && imageElement[1].indexOf(':') != -1 ) {
            imageNameList = imageElement[-1].tokenize(':')
            project = imageNameList[0]
            serverName = imageNameList[0]
            imageTag = imageNameList[1]
            imageInfix = serverName
            repositoryName = serverName
        } else if (imageElement.size == 3 && imageElement[2].indexOf(':') != -1 ) {
            project = imageElement[1]
            imageNameList = imageElement[-1].tokenize(':')
            serverName = imageNameList[0]
            imageTag = imageNameList[1]
            imageInfix = project + '/' + serverName
            repositoryName = serverName
        } else {
            project = imageElement[1]
            imageNameList = imageElement[-1].tokenize(':')
            serverName = imageNameList[0]
            imageTag = imageNameList[1]
            imageInfix = imageElement[1..-2].join('/') + '/' + serverName
            repositoryName = imageElement[2..-2].join('%252F') + '%252F' + serverName
        }
        retList = [registryAddr, project, imageInfix, repositoryName, serverName, imageTag]
        // println(retList)
    }
    return retList
}


def splitNamespaces(namespaces) {
    /*
    * jic-staging-1 or bf-bsc-staging-1
    * 返回结果：[bf-bsc, staging-1, staging1]
    */
    imageElement = namespaces.tokenize('-')
    config_env = imageElement[-2] + '-' + imageElement[-1]
    // println config_env
    config_env_prefix = namespaces.replaceAll('-' + config_env, "")
    configenv = config_env.replaceAll('-', "")
    return [config_env_prefix, config_env, configenv]
}


def printImageList(imageDict) {
    def _EXTRANET_HARBOR = Boolean.valueOf("${params.EXTRANET_HARBOR}")
    if (_EXTRANET_HARBOR) {
        println("华为云或外部网络可用的镜像为：" + imageDict)
        imageDict.each { k, v ->
            println "服务 ${k} 的镜像如下："
            v.each { key, value ->
                println(key + " => " + value)
            }
        }
    }
}


def getJarPackageName(jarPackageKey) {
    /* 返回最新的一个文件名等信息
        jarPackageKey   需要查找的jar包关键词，一般包含被查找的路径
            传参示例："data-subscribe-svc-api-app/build/libs/data-subscribe-svc-api-app-*.jar"
    */
    def retList = []
    def files = findFiles(glob: jarPackageKey)
    def lastTimestamp = ''
    for(file in files) {
        if (lastTimestamp == '') {
            lastTimestamp = file.lastModified
        } else if (lastTimestamp < file.lastModified) {
            lastTimestamp = file.lastModified
        }
    }
    for(file in files) {
        if (lastTimestamp == file.lastModified) {
            println(file.name + "; " + file.path)
            retList.add(file.name)
            retList.add(file.path)
        }
    }
    return retList
}

