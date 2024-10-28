def call(Map config = [:]) {
    /*
        config.doType    # 可用值：download、upload
        config.dirPath   # 文件存储文件夹，末尾不带/
        config.fileNamePath   # 文件名
        config.bucket   # 存储桶
    */
    switch(config.get('doType')) {
        case 'download':
            minioDownload bucket: "${config.bucket}",
                credentialsId: '30c7e4c5-05d3-4951-880e-bdf235df8020',
                failOnNonExisting: true,
                file: "${config.dirPath}/${config.fileNamePath}", 
                host: 'http://IAmIPaddress:65003', 
                targetFolder: './'
        break
        case 'upload':
            minio bucket: "${config.bucket}",
                credentialsId: '30c7e4c5-05d3-4951-880e-bdf235df8020',
                excludes: '',
                host: 'http://IAmIPaddress:65003',
                includes: "${config.fileNamePath}",
                targetFolder: "${config.dirPath}/"
        break
    }
}
