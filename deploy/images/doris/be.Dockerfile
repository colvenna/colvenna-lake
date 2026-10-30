FROM docker.io/apache/doris@sha256:3273e3f66538f75cd6fb5716f6d48eef18c9f5e04daafdfea6ecd561b47f3c8d

COPY jars/postgresql-42.7.8.jar /opt/apache-doris/paimon-driver/postgresql-42.7.8.jar
COPY jars/postgresql-42.7.8.jar /opt/apache-doris/be/lib/java_extensions/paimon-connector/postgresql-42.7.8.jar
COPY reader/be/ /opt/apache-doris/be/lib/java_extensions/paimon-connector/
COPY reader/be-udf/ /opt/apache-doris/be/lib/java_extensions/java-udf/
COPY reader/receipt.json /opt/apache-doris/paimon2-reader-receipt.json
