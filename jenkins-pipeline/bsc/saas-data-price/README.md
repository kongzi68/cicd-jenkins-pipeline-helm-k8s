

# saas-data-price docker镜像

```bash
iamusername@devops-tools:~/temp# cat dockerfile
FROM IAmIPaddress/libs/zulu-openjdk:17-ubuntu-tools
LABEL maintainer="colin" version="1.0" datetime="2023-05-17"
RUN apt-get update && apt-get -y install tzdata
RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo "Asia/Shanghai" > /etc/timezone
COPY saas-data-price-0.0.1-SNAPSHOT.jar /opt/betack/saas-data-price.jar
WORKDIR /opt/betack


docker image build -t IAmIPaddress/bf-bsc/saas-data-price:20240228-v1 -f dockerfile .
docker image push IAmIPaddress/bf-bsc/saas-data-price:20240228-v1
```

# 启动服务

## 20240228 bak

```bash
java -Dspring.profiles.active=prod -Dlog.path=/var/log -Ddata2.mysql.host=IAmIPaddress -Ddata2.mysql.port=3306 -Ddata2.mysql.data=saas_data -Ddata2.mysql.username=iamusername -Ddata2.mysql.password=iampassword -Ddata1.mysql.host=mysql80-svc -Ddata1.mysql.port=3306 -Ddata1.mysql.data=saas_data_price -Ddata1.mysql.username=iamusername -Ddata1.mysql.password=iampassword -jar /opt/betack/saas-data-price.jar
```




