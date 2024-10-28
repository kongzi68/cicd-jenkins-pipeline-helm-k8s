
## mysql80

```bash
helm install mysql -n bf-bsc-staging-1 --dry-run -f mysql-80-values.yaml oci://harbor.betack.com/libs-charts/mysql-80 --version 0.1.1

betack@rke-k8s-rancher-tools:~/yaml/project/bsc$ helm install mysql -n bf-bsc-staging-1 -f mysql-80-values.yaml oci://harbor.betack.com/libs-charts/mysql-80 --version 0.1.2
NAME: mysql
LAST DEPLOYED: Tue Jun 28 16:51:24 2022
NAMESPACE: bf-bsc-staging-1
STATUS: deployed
REVISION: 1
TEST SUITE: None
betack@rke-k8s-rancher-tools:~/yaml/project/bsc$ helm -n bf-bsc-staging-1 list
NAME 	NAMESPACE       	REVISION	UPDATED                                	STATUS  	CHART         	APP VERSION
mysql	bf-bsc-staging-1	1       	2022-06-28 16:51:24.975513601 +0800 CST	deployed	mysql-80-0.1.2	8.0.29

betack@rke-k8s-rancher-tools:~/yaml/project/bsc$ kubectl -n bf-bsc-staging-1 get pod,svc
NAME                       READY   STATUS    RESTARTS   AGE
pod/mysql-statefuleset-0   1/1     Running   0          7m2s

NAME                TYPE       CLUSTER-IP      EXTERNAL-IP   PORT(S)          AGE
service/mysql-svc   NodePort   IAmIPaddress   <none>        3306:51737/TCP   7m2s

```

## saas-app-server

```bash
helm install saas-app-server --dry-run -n bf-bsc-staging-1 oci://harbor.betack.com/bf-bsc-charts/saas-app-server --version 0.1.1

betack@rke-k8s-rancher-tools:~/yaml/project/bsc$ helm install saas-app-server -n bf-bsc-staging-1 oci://harbor.betack.com/bf-bsc-charts/saas-app-server --version 0.1.1
NAME: saas-app-server
LAST DEPLOYED: Tue Jun 28 17:29:27 2022
NAMESPACE: bf-bsc-staging-1
STATUS: deployed
REVISION: 1
TEST SUITE: None

betack@rke-k8s-rancher-tools:~$ kubectl -n bf-bsc-staging-1 get pod,svc
NAME                                             READY   STATUS    RESTARTS   AGE
pod/mysql-statefuleset-0                         1/1     Running   0          25m
pod/saas-app-server-staging-1-7dfc759b84-m4cc8   1/1     Running   0          7m55s

NAME                                    TYPE       CLUSTER-IP      EXTERNAL-IP   PORT(S)                                        AGE
service/mysql-svc                       NodePort   IAmIPaddress   <none>        3306:51737/TCP                                 25m
service/saas-app-server-out-staging-1   NodePort   IAmIPaddress      <none>        8080:57141/TCP,9090:52352/TCP,9091:57681/TCP   7m55s
```

## 部署alpine-tools工具pod

```bash
betack@rke-k8s-rancher-tools:~/yaml/project/bsc$ kubectl -n bf-bsc-staging-1 apply -f alpine-tools-pod.yaml
pod/alpine-tools created

```








