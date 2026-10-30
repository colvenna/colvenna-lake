#!/usr/bin/env bash
# Builds colvenna-console for linux/amd64 from a committed tree, pushes it tagged with the source SHA,
# and records the pinned reference (record_image).
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
assert_account
sha="$(committed_sha)"
repo="$(entry_output ConsoleRepositoryUri)"
mkdir -p "$STATE_DIR"
bash "$REPO_DIR/colvenna-console/check.sh"
aws ecr get-login-password | docker login --username AWS --password-stdin "${repo%%/*}" >/dev/null
docker buildx build --platform linux/amd64 --provenance=false -t "$repo:$sha" --push \
  -f "$REPO_DIR/colvenna-console/Dockerfile" "$REPO_DIR"
digest="$(aws ecr describe-images --repository-name "${repo#*/}" --image-ids imageTag="$sha" \
  --query 'imageDetails[0].imageDigest' --output text)"
record_image console "$repo@$digest"
echo "console $repo@$digest"
