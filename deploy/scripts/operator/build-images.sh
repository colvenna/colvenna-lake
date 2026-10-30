#!/usr/bin/env bash
# Builds colvenna-operator and the colvenna-jobs images (one per source type) for linux/amd64 from a
# committed tree, pushes them tagged with the source SHA, and records the pinned references in
# deploy/.state/<env>/operator-images-<sha>.json. Connector jars are checked against pinned SHA-256 sums.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
assert_account
sha="$(committed_sha)"
operator_repo="$(stack_output "$OPERATOR_STACK" OperatorRepositoryUri)"
jobs_repo="$(stack_output "$OPERATOR_STACK" JobRepositoryUri)"
registry="${operator_repo%%/*}"
if [[ "$FOUNDATION_IMAGE" != *@sha256:* ]]; then
  echo "No Flink image for this environment: run deploy/scripts/foundation/build-flink-image.sh" >&2
  exit 1
fi
tools="$TOOLS_DIR"
mkdir -p "$tools/jars" "$STATE_DIR"

mvn -B -q -f "$REPO_DIR/colvenna-operator/pom.xml" -DskipTests clean package
mvn -B -q -f "$REPO_DIR/colvenna-jobs/pom.xml" -DskipTests clean package

# download <file> <url> <sha256>
download() {
  local path="$tools/jars/$1"
  if [[ ! -f "$path" ]]; then
    curl -fsSL "$2" -o "$path.part"
    mv "$path.part" "$path"
  fi
  printf '%s  %s\n' "$3" "$path" | sha256sum --check --quiet
}
central=https://repo1.maven.org/maven2
# MySQL's connector is 3.7 (FLINK-40741, docs/history/m3/flink-cdc-3.7.md); the others stay on 3.5.0.
download flink-sql-connector-mysql-cdc-3.7.0-1.20.jar \
  "$central/org/apache/flink/flink-sql-connector-mysql-cdc/3.7.0-1.20/flink-sql-connector-mysql-cdc-3.7.0-1.20.jar" \
  d1c91f29027d51b61f14b0932ddabff5e19001dd2131885b872a37bfe0b2d397
download flink-sql-connector-postgres-cdc-3.5.0.jar \
  "$central/org/apache/flink/flink-sql-connector-postgres-cdc/3.5.0/flink-sql-connector-postgres-cdc-3.5.0.jar" \
  8c5b78d279a51676ad27e9730db37477c1cbf6691dd2382c6fa653b3fe293262
download flink-sql-connector-mongodb-cdc-3.5.0.jar \
  "$central/org/apache/flink/flink-sql-connector-mongodb-cdc/3.5.0/flink-sql-connector-mongodb-cdc-3.5.0.jar" \
  6cb6c11b75c63e26397336b262a1c57aa2f860604993fd4d559f0faf83408084
download flink-sql-connector-kafka-3.4.0-1.20.jar \
  "$central/org/apache/flink/flink-sql-connector-kafka/3.4.0-1.20/flink-sql-connector-kafka-3.4.0-1.20.jar" \
  b860724fd97d7da0901bfc3859680442f4c1aa2b35ab944407558b393a1cf93e
download mysql-connector-java-8.0.27.jar \
  "$central/mysql/mysql-connector-java/8.0.27/mysql-connector-java-8.0.27.jar" \
  d670e85fa1075a80d91b8f17dbd620d76717bc20c74ab4aea8356e37a8545ffe
# The Paimon 2.0 CDC runtime needs Avro and Jackson from the classpath (docs/history/m1/mysql-cdc.md).
download avro-1.11.4.jar "$central/org/apache/avro/avro/1.11.4/avro-1.11.4.jar" \
  eeba11b77070b9aa6337d886fdf778f6695f6c4c3dcfd2a02389925c885079fa
download jackson-core-2.14.3.jar \
  "$central/com/fasterxml/jackson/core/jackson-core/2.14.3/jackson-core-2.14.3.jar" \
  7ee2debad3c002e97b28b84d5f1b2044a38e780abb673948238f5bc656e2fe44
download jackson-databind-2.14.3.jar \
  "$central/com/fasterxml/jackson/core/jackson-databind/2.14.3/jackson-databind-2.14.3.jar" \
  ef0694046dc86e8a601e69efef24cda88a85773aaf012f5b9b1167d6f2e7de45
download jackson-annotations-2.14.3.jar \
  "$central/com/fasterxml/jackson/core/jackson-annotations/2.14.3/jackson-annotations-2.14.3.jar" \
  ea1b3a9ec60a353f4d7e78559c7140abb9bcf71b31456e9d57c8289f7cf397f1
common_jars=(avro-1.11.4.jar jackson-core-2.14.3.jar jackson-databind-2.14.3.jar
  jackson-annotations-2.14.3.jar)
# The CDC variants add their connector, kafka the Kafka connector (shaded clients) and, for the
# Debezium records a Kafka-carried CDC job parses with Paimon's CDC runtime, the same Avro and
# Jackson; sql (LakeTable jobs and their validation) runs on the foundation image's Paimon
# connector alone.
declare -A variant_jars=(
  [mysql]="${common_jars[*]} flink-sql-connector-mysql-cdc-3.7.0-1.20.jar mysql-connector-java-8.0.27.jar"
  [postgres]="${common_jars[*]} flink-sql-connector-postgres-cdc-3.5.0.jar"
  [mongodb]="${common_jars[*]} flink-sql-connector-mongodb-cdc-3.5.0.jar"
  [sql]=""
  [kafka]="${common_jars[*]} flink-sql-connector-kafka-3.4.0-1.20.jar"
)

docker_config="$(mktemp -d)"
trap 'rm -rf "$docker_config"' EXIT
export DOCKER_CONFIG="$docker_config"
aws ecr get-login-password | docker login --username AWS --password-stdin "$registry" >/dev/null

# push <repository-name> <tag> <context> [build-arg...]: builds unless the tag exists, prints the
# pinned reference.
push() {
  local name="$1" tag="$2" context="$3" uri="$registry/$1" digest
  shift 3
  if ! aws ecr describe-images --repository-name "$name" --image-ids imageTag="$tag" \
    >/dev/null 2>&1; then
    docker buildx build --platform linux/amd64 --provenance=false "$@" \
      --label "org.opencontainers.image.revision=$sha" --tag "$uri:$tag" --push "$context" >&2
  fi
  digest="$(aws ecr describe-images --repository-name "$name" --image-ids imageTag="$tag" \
    --query 'imageDetails[0].imageDigest' --output text)"
  echo "$uri@$digest"
}

refs="$STATE_DIR/operator-images-$sha.json.part"
printf '{\n  "sha": "%s",\n  "foundation": "%s",\n' "$sha" "$FOUNDATION_IMAGE" >"$refs"
printf '  "operator": "%s"' "$(push "${operator_repo#*/}" "$sha" "$REPO_DIR/colvenna-operator")" >>"$refs"
for variant in mysql postgres mongodb sql kafka; do
  context="$tools/context-$variant"
  rm -rf "$context"
  mkdir -p "$context/lib"
  # shellcheck disable=SC2086  # the variant's jar list
  for jar in ${variant_jars[$variant]}; do
    cp "$tools/jars/$jar" "$context/lib/"
  done
  cp "$REPO_DIR/colvenna-jobs/target/colvenna-jobs-0.1.0.jar" "$context/colvenna-jobs.jar"
  cp "$REPO_DIR/colvenna-jobs/image/Dockerfile" "$context/Dockerfile"
  printf ',\n  "%s": "%s"' "$variant" \
    "$(push "${jobs_repo#*/}" "$sha-$variant" "$context" --build-arg "FOUNDATION_IMAGE=$FOUNDATION_IMAGE")" >>"$refs"
done
printf '\n}\n' >>"$refs"
mv "$refs" "${refs%.part}"
cat "${refs%.part}"
[[ "$jobs_repo" == "$registry/$(env_name colvenna-lake/colvenna-jobs)" ]]
