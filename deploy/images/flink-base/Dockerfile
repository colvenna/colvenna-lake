FROM flink:1.20.4-scala_2.12-java11@sha256:3688d2f31fbff3024226d2c132f3a33be5c43686b1304763ad9e8f57e78dd967

COPY jars/paimon-flink-1.20-2.0.0.jar /opt/flink/lib/
COPY jars/paimon-s3-2.0.0.jar /opt/flink/lib/
COPY jars/paimon-hadoop-uber-2.0.0.jar /opt/flink/lib/
COPY jars/postgresql-42.7.8.jar /opt/flink/lib/
COPY jars/paimon-flink-action-2.0.0.jar /opt/flink/usrlib/

ENV ENABLE_BUILT_IN_PLUGINS=flink-s3-fs-hadoop-1.20.4.jar
# The base image's flink user, the one colvenna-operator runs the job pods as (FlinkJobs).
USER 9999:9999
