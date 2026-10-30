#!/usr/bin/env bash
# Builds the BI image (Superset 6.1.0 with colvenna_bi, colvenna-bi/) for linux/amd64 from a committed tree,
# after colvenna-bi/check.sh, pushes it tagged with the source SHA, and records the pinned reference
# (record_image).
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
assert_account
sha="$(committed_sha)"
repo="$(entry_output BiRepositoryUri)"
bash "$REPO_DIR/colvenna-bi/check.sh"
aws ecr get-login-password | docker login --username AWS --password-stdin "${repo%%/*}" >/dev/null
docker buildx build --platform linux/amd64 --provenance=false -t "$repo:$sha" --push "$REPO_DIR/colvenna-bi"
digest="$(aws ecr describe-images --repository-name "${repo#*/}" --image-ids imageTag="$sha" \
  --query 'imageDetails[0].imageDigest' --output text)"
record_image bi "$repo@$digest"
echo "bi $repo@$digest"
