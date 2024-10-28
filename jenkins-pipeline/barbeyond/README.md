
## helm 手动部署

部署完成之后，需要修改各自的配置文件或者command启动命令

```bash
betack@k8s-master:~$ helm install inner-grpc-proxy-web -n new-rab-dev-1 --set namespacePrefix=new-rab,image.tag=e0f0427-34 oci://harbor.betack.com/rabbeyond-charts/inner-grpc-proxy-web --version 0.1.2
NAME: inner-grpc-proxy-web
LAST DEPLOYED: Mon Jun 12 17:30:47 2023
NAMESPACE: new-rab-dev-1
STATUS: deployed
REVISION: 1
TEST SUITE: None
betack@k8s-master:~$ helm install data-insight-server -n new-rab-dev-1 --set namespacePrefix=new-rab,image.tag=0b6409382-63 oci://harbor.betack.com/rabbeyond-charts/data-insight-server --version 0.1.2
NAME: data-insight-server
LAST DEPLOYED: Mon Jun 12 17:39:49 2023
NAMESPACE: new-rab-dev-1
STATUS: deployed
REVISION: 1
TEST SUITE: None
betack@k8s-master:~$ helm install rabbeyond-fe -n new-rab-dev-1 --set namespacePrefix=new-rab,image.tag=e65a2d9-234 oci://harbor.betack.com/rabbeyond-charts/rabbeyond-fe --version 0.1.3
NAME: rabbeyond-fe
LAST DEPLOYED: Mon Jun 12 17:42:53 2023
NAMESPACE: new-rab-dev-1
STATUS: deployed
REVISION: 1
TEST SUITE: None
```








































