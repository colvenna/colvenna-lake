# The acceptance runs' external reader: Spark 3.5.8 with Iceberg's REST catalog and S3 FileIO (the
# reader under test), and Paimon's connector (to read the same tables natively, for comparison).
# COPY steps only, so an amd64 image builds on any host; build-readers.sh fetches and verifies the
# jars.
FROM apache/spark:3.5.8-scala2.12-java17-ubuntu@sha256:9577096950d62dd59994f84cc3a0902fdae994a4bf238933f2fdca22053e5adb
COPY jars/iceberg-spark-runtime-3.5_2.12-1.10.2.jar /opt/spark/jars/
COPY jars/iceberg-aws-bundle-1.10.2.jar /opt/spark/jars/
COPY jars/paimon-spark-3.5_2.12-2.0.0.jar /opt/spark/jars/
COPY jars/paimon-s3-2.0.0.jar /opt/spark/jars/
# The base image's spark user, written out.
USER 185:185
