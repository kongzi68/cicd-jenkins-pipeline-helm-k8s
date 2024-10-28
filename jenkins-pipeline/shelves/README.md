指数货架

```bash
## 指数货架生产 mysql8.0
iamusername@mysql-prod-data01:/data/mysql# docker run -d --name mysql80-product-rack-prod2 --ulimit nofile=65536:131072 --restart=always -p IAmIPaddress:3310:3306 -v /data/mysql/product-rack-prod-2/data:/var/lib/mysql -v /data/mysql/product-rack-prod-2/config/mysql.cnf:/etc/mysql/conf.d/mysql.cnf -e MYSQL_iamusername_PASSWORD=iampassword mysql:8.0.35

```














