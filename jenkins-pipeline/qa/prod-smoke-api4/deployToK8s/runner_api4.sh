#! /bin/bash

PROPERTY_BASE_DIR='/opt/betack/backend-ft/src/test/resources'
POM_BASE_DIR='/opt/betack/backend-ft'
#env_ip=$1
#echo $env_ip

# sed 修改ip

#sed -i "/host/c host=${env_ip}"  ${PROPERTY_BASE_DIR}/test.properties

#sed -i "/mysqlHost/c mysqlHost=${env_ip}" ${PROPERTY_BASE_DIR}/test.properties

callNum=$1
echo $callNum
sed -i "/calleeNbr/c calleeNbr\=${callNum}" ${PROPERTY_BASE_DIR}/test.properties
#sed  -i "s/calleeNbr\=+8613320283582 /calleeNbr\=${callNum}/g"  test.properties

mvn test -U --batch-mode -f ${POM_BASE_DIR}/pom.xml -Dtest=ProdSmoke