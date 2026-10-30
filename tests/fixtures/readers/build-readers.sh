#!/usr/bin/env bash
# Builds the external reader images from pinned jars and pushes them, tagged with the source SHA:
# spark (Spark 3.5.8 with Iceberg 1.10.2 and Paimon 2.0.0). Records the pinned references in
# deploy/.state/<env>/readers-<sha>.json.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../../acceptance/common.sh
source "$(dirname "$0")/../../acceptance/common.sh"
assert_account
sha="$(committed_sha)"
context="$REPO_DIR/tests/fixtures/readers/spark"
mkdir -p "$context/jars" "$STATE_DIR"
# fetch <maven-path> <sha256>
fetch() {
  local file
  file="$context/jars/$(basename "$1")"
  if [[ ! -f "$file" ]] || ! echo "$2  $file" | sha256sum --check --quiet; then
    curl -fsSL "https://repo1.maven.org/maven2/$1" -o "$file"
    echo "$2  $file" | sha256sum --check --quiet
  fi
}
fetch org/apache/iceberg/iceberg-spark-runtime-3.5_2.12/1.10.2/iceberg-spark-runtime-3.5_2.12-1.10.2.jar \
  c562d482dcd16b666e808b755469fbc4b2604284b41622c9d87e2c02b7cc6c5f
fetch org/apache/iceberg/iceberg-aws-bundle/1.10.2/iceberg-aws-bundle-1.10.2.jar \
  a2395ab49bf87e971e5588db644194ac18e9c0e0b44a7ad5a422a97648a38415
fetch org/apache/paimon/paimon-spark-3.5_2.12/2.0.0/paimon-spark-3.5_2.12-2.0.0.jar \
  473ef102a43726dcac1d72a311896b7b4f021cdce25f3b54cf077e0260e49f3f
fetch org/apache/paimon/paimon-s3/2.0.0/paimon-s3-2.0.0.jar \
  a5f79d5c5d60e14e6da7366f3f34f65a489495644e44a205291611af56ce52f0
repository="$(stack_output "$OPEN_LAYER_STACK" ReaderRepositoryUri)"
docker_config="$(mktemp -d)"
trap 'rm -rf "$docker_config"' EXIT
export DOCKER_CONFIG="$docker_config"
aws ecr get-login-password | docker login --username AWS --password-stdin "${repository%%/*}" >/dev/null
tag="$sha-spark"
if ! aws ecr describe-images --repository-name "${repository#*/}" --image-ids imageTag="$tag" \
  >/dev/null 2>&1; then
  docker buildx build --platform linux/amd64 --provenance=false \
    --label "org.opencontainers.image.revision=$sha" --tag "$repository:$tag" --push "$context" >&2
fi
digest="$(aws ecr describe-images --repository-name "${repository#*/}" --image-ids imageTag="$tag" \
  --query 'imageDetails[0].imageDigest' --output text)"
printf '{\n  "sha": "%s",\n  "spark": "%s@%s"\n}\n' "$sha" "$repository" "$digest" \
  | tee "$STATE_DIR/readers-$sha.json"
