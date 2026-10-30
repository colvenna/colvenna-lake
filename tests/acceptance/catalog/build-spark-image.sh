#!/usr/bin/env bash
# Builds the Spark flow image from pinned Paimon jars and pushes it, tagged with the source SHA.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
assert_account
sha="$(committed_sha)"
context="$REPO_DIR/tests/acceptance/catalog/spark/image"
mkdir -p "$context/jars"
fetch() {
  local path="$1" sum="$2" file
  file="$context/jars/$(basename "$path")"
  if [[ ! -f "$file" ]] || ! echo "$sum  $file" | sha256sum --check --quiet; then
    curl -fsSL "https://repo1.maven.org/maven2/org/apache/paimon/$path" -o "$file"
    echo "$sum  $file" | sha256sum --check --quiet
  fi
}
fetch paimon-spark-3.5_2.12/2.0.0/paimon-spark-3.5_2.12-2.0.0.jar \
  473ef102a43726dcac1d72a311896b7b4f021cdce25f3b54cf077e0260e49f3f
fetch paimon-s3/2.0.0/paimon-s3-2.0.0.jar \
  a5f79d5c5d60e14e6da7366f3f34f65a489495644e44a205291611af56ce52f0
repository="$(stack_output "$CATALOG_STACK" SparkRepositoryUri)"
aws ecr get-login-password | docker login --username AWS --password-stdin "${repository%%/*}" >/dev/null
if ! aws ecr describe-images --repository-name "${repository#*/}" --image-ids imageTag="$sha" \
  >/dev/null 2>&1; then
  docker buildx build --platform linux/amd64 --provenance=false --tag "$repository:$sha" \
    --push "$context"
fi
digest="$(aws ecr describe-images --repository-name "${repository#*/}" --image-ids imageTag="$sha" \
  --query 'imageDetails[0].imageDigest' --output text)"
mkdir -p "$STATE_DIR"
echo "$repository@$digest" | tee "$STATE_DIR/spark-image-ref.txt"
