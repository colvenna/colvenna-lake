# colvenna-operator runtime image. Only COPY steps, so an amd64 image builds on any host without
# emulation. Build after `mvn -f colvenna-operator/pom.xml package`.
FROM eclipse-temurin:21-jre-noble@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c
# Readable by the runtime user whatever the build host's umask left on the jars.
COPY --chmod=0755 target/lib/ /opt/colvenna-operator/lib/
COPY --chmod=0644 target/colvenna-operator-0.1.0.jar /opt/colvenna-operator/colvenna-operator.jar
# The base image's unprivileged ubuntu user (Hadoop's login, used by Paimon's S3 FileIO, needs a
# passwd entry for the UID).
USER 1000:1000
EXPOSE 9100
# SLF4J 2 first: paimon-hadoop-uber bundles SLF4J 1.7's API, which would silence logback.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.io.tmpdir=/tmp", "-cp", "/opt/colvenna-operator/colvenna-operator.jar:/opt/colvenna-operator/lib/slf4j-api-2.0.17.jar:/opt/colvenna-operator/lib/*", "com.colvenna.lake.operator.Main"]
