# Spark 3.5.8 with the Paimon 2.0.0 connector and S3 FileIO; COPY steps only, so an amd64 image
# builds on any host without emulation. build-spark-image.sh fetches and verifies the jars.
FROM apache/spark:3.5.8-scala2.12-java17-ubuntu@sha256:9577096950d62dd59994f84cc3a0902fdae994a4bf238933f2fdca22053e5adb
COPY jars/paimon-spark-3.5_2.12-2.0.0.jar /opt/spark/jars/
COPY jars/paimon-s3-2.0.0.jar /opt/spark/jars/
# The base image's spark user, written out.
USER 185:185
