
# etf prod mysql8.0

```bash
# etf 生产环境mysql8.0
iamusername@mysql-prod-data01:/data/mysql# docker run -d --name mysql80-etf-prod1 --ulimit nofile=65536:131072 --restart=always -p IAmIPaddress:3311:3306 -v /data/mysql/etf-prod-1/data:/var/lib/mysql -v /data/mysql/etf-prod-1/config/mysql.cnf:/etc/mysql/conf.d/mysql.cnf -e MYSQL_iamusername_PASSWORD=iampassword mysql:8.0.35
```

# etf prod neo4j

```bash
betack@bf-dell-host01:~$ cat neo4j/values.yaml
neo4j:
  name: etf-prod1-standalone
  resources:
    cpu: "1"
    memory: "2Gi"
  # Uncomment to set the initial password
  password: "iampassword"
volumes:
  data:
    mode: "dynamic"
    dynamic:
      storageClassName: "longhorn-fast"
```