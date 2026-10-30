FROM docker.io/apache/doris@sha256:71b5422dfcc204f9adf36298a0457846d186aba5d1d3353e54546764f54f2e47

COPY jars/postgresql-42.7.8.jar /opt/apache-doris/paimon-driver/postgresql-42.7.8.jar
COPY jars/postgresql-42.7.8.jar /opt/apache-doris/fe/lib/postgresql-42.7.8.jar
COPY reader/fe/ /opt/apache-doris/fe/lib/
COPY reader/receipt.json /opt/apache-doris/paimon2-reader-receipt.json
