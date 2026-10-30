# A colvenna-jobs image: the Flink base (Flink 1.20.4 on Java 11, Paimon 2.0.0 and its S3,
# Hadoop and action jars) plus a CDC source's connector jars (none for sql) and the job entry
# points. The build context holds lib/ (the connector jars of this image) and colvenna-jobs.jar
# (deploy/scripts/operator/build-images.sh).
ARG FOUNDATION_IMAGE
FROM ${FOUNDATION_IMAGE}
COPY lib/ /opt/flink/lib/
COPY colvenna-jobs.jar /opt/flink/usrlib/colvenna-jobs.jar
# The Flink base image's flink user, written out (colvenna-operator runs the pods as it).
USER 9999:9999
