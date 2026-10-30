#!/usr/bin/env bash
set -euo pipefail
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"

assert_account
cd "$REPO_DIR"
if [[ -n "$(git status --porcelain=v1)" ]]; then
  echo "Commit or discard source changes before building the Flink image" >&2
  exit 1
fi
source_sha="$(git rev-parse HEAD)"
if [[ "$(stack_output "$FOUNDATION_STACK" SourceSha)" != "$source_sha" ]]; then
  echo "The deployed CDK stack does not match the Flink image source SHA" >&2
  exit 1
fi

repository_uri="$(stack_output "$FOUNDATION_STACK" FlinkImageRepositoryUri)"
repository="$(env_name colvenna-lake/flink-base)"
if [[ "$repository_uri" != "$ECR_REGISTRY/$repository" ]]; then
  echo "Unexpected ECR repository: $repository_uri" >&2
  exit 1
fi
image_tag="$source_sha"
existing_digest="$(aws ecr list-images --repository-name "$repository" \
  --query "imageIds[?imageTag=='$image_tag'].imageDigest | [0]" --output text)"
if [[ "$existing_digest" == sha256:* ]]; then
  echo "$repository_uri@$existing_digest" > "$STATE_DIR/flink-image-ref.txt"
  echo "Existing Flink image: $(cat "$STATE_DIR/flink-image-ref.txt")"
  exit 0
fi

context_dir="$TOOLS_DIR/flink-image"
mkdir -p "$context_dir/jars"
download() {
  local path="$1" url="$2" sha256="$3"
  if [[ ! -f "$context_dir/jars/$path" ]]; then
    curl -fsSL "$url" -o "$context_dir/jars/$path"
  fi
  printf '%s  %s\n' "$sha256" "$context_dir/jars/$path" | sha256sum --check
}
download paimon-flink-1.20-2.0.0.jar \
  https://repo1.maven.org/maven2/org/apache/paimon/paimon-flink-1.20/2.0.0/paimon-flink-1.20-2.0.0.jar \
  e5279526e6373a5fdeca36a8fc17b5951ea3f0238f7a30bb6d95ec4dd5af6a80
download paimon-s3-2.0.0.jar \
  https://repo1.maven.org/maven2/org/apache/paimon/paimon-s3/2.0.0/paimon-s3-2.0.0.jar \
  a5f79d5c5d60e14e6da7366f3f34f65a489495644e44a205291611af56ce52f0
download paimon-hadoop-uber-2.0.0.jar \
  https://repo1.maven.org/maven2/org/apache/paimon/paimon-hadoop-uber/2.0.0/paimon-hadoop-uber-2.0.0.jar \
  fd88b93d8f8834535bde7d5ff7acc33a7765aed9e65d99f0830eff833ff3a918
download postgresql-42.7.8.jar \
  https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.8/postgresql-42.7.8.jar \
  2a32a9dcbc42d67a50ad3a0de5efd102c8d2be46720045f2cbd6689f160ab7c7
download paimon-flink-action-2.0.0.jar \
  https://repo1.maven.org/maven2/org/apache/paimon/paimon-flink-action/2.0.0/paimon-flink-action-2.0.0.jar \
  cd435d1136fbf18b627f895d2f5ac04de14a2a2fa1c7ff3a7bcd662933f7381f

docker_config="$(mktemp -d "$TOOLS_DIR/docker-config.XXXXXX")"
trap 'rm -rf "$docker_config"' EXIT
export DOCKER_CONFIG="$docker_config"
aws ecr get-login-password | docker login \
  --username AWS --password-stdin "$ECR_REGISTRY"

docker buildx build --platform linux/amd64 \
  --file "$REPO_DIR/deploy/images/flink-base/Dockerfile" \
  --label "org.opencontainers.image.revision=$source_sha" \
  --tag "$repository_uri:$image_tag" \
  --metadata-file "$STATE_DIR/flink-image-metadata.json" \
  --push "$context_dir"
digest="$(jq -r '."containerimage.digest"' "$STATE_DIR/flink-image-metadata.json")"
if [[ "$digest" != sha256:* ]]; then
  echo "Docker build did not return an image digest" >&2
  exit 1
fi
echo "$repository_uri@$digest" > "$STATE_DIR/flink-image-ref.txt"
echo "Flink image: $(cat "$STATE_DIR/flink-image-ref.txt")"
