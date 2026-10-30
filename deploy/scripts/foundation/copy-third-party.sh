#!/usr/bin/env bash
# Puts the third-party images (deploy/images/third-party.tsv) into the environment's own repository
# colvenna-lake-<env>/third-party, by digest, each tagged <name>-<the digest's first 12 hex digits>: the
# platform's, and with --fixtures the fixtures' too. The charts, scripts and tests run these copies
# (lib/common.sh's third_party_image), never upstream, which may drop a digest (quay.io rebuilds a
# release's tag on a new base and deletes the old index): an environment keeps what it was
# installed with, and a node it gets later pulls the same.
# Each comes from the release registry when COLVENNA_RELEASE_REGISTRY is given and its
# colvenna-lake-release/third-party holds the digest (a candidate keeps every image it pinned there),
# else from upstream by the pinned digest. crane copies the manifest (an index, with all its
# platforms) as it is, so the digest does not change, and it is checked after the copy. What the
# repository holds already is left: a run that stopped can run again. install.sh's third-party step
# runs it for a source install; a release install copies from its candidate instead
# (release/install-images.sh <candidate> third-party).
# Usage: COLVENNA_ENV=<env> [COLVENNA_RELEASE_REGISTRY=<account>.dkr.ecr.<region>.amazonaws.com] \
#          copy-third-party.sh [--fixtures]
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
assert_account
usage="usage: copy-third-party.sh [--fixtures]"
case "${1:-}" in
  "" | --fixtures) ;;
  *) echo "$usage" >&2; exit 1 ;;
esac
tool="$DEPLOY_DIR/scripts/release/manifest.py"
# Validated: name, upstream reference, digest, tag, a line each.
images="$(python3 "$tool" third-party ${1:+"$1"})"
repository="$(env_name colvenna-lake/third-party)"
if [[ "$(stack_output "$FOUNDATION_STACK" ThirdPartyImageRepositoryUri)" != "$THIRD_PARTY_REPOSITORY" ]]; then
  echo "The foundation stack has no repository $THIRD_PARTY_REPOSITORY; deploy it first" >&2
  exit 1
fi
# crane reads the registries' credentials from a private Docker config of this run only.
DOCKER_CONFIG="$(mktemp -d)"
export DOCKER_CONFIG
trap 'rm -rf "$DOCKER_CONFIG"' EXIT
aws ecr get-login-password | crane auth login "$ECR_REGISTRY" -u AWS --password-stdin >/dev/null
release=""
if [[ -n "${COLVENNA_RELEASE_REGISTRY:-}" ]]; then
  parts="$(python3 "$tool" registry "$COLVENNA_RELEASE_REGISTRY")"
  release="$COLVENNA_RELEASE_REGISTRY/colvenna-lake-release/third-party"
  # One token of a region serves every registry of it the caller may read.
  if [[ "$COLVENNA_RELEASE_REGISTRY" != "$ECR_REGISTRY" ]]; then
    aws ecr get-login-password --region "${parts#*$'\t'}" \
      | crane auth login "$COLVENNA_RELEASE_REGISTRY" -u AWS --password-stdin >/dev/null
  fi
fi
while IFS=$'\t' read -r name upstream digest tag; do
  held="$(aws ecr describe-images --repository-name "$repository" --image-ids "imageTag=$tag" \
    --query 'imageDetails[0].imageDigest' --output text 2>/dev/null || true)"
  if [[ "$held" == "$digest" ]]; then
    echo "$name: $THIRD_PARTY_REPOSITORY:$tag (already there)"
    continue
  fi
  if [[ "$held" == sha256:* ]]; then
    echo "$name: $repository:$tag is $held, not $digest" >&2
    exit 1
  fi
  from="$upstream"
  if [[ -n "$release" ]] && crane digest "$release@$digest" >/dev/null 2>&1; then
    from="$release@$digest"
  fi
  crane copy "$from" "$THIRD_PARTY_REPOSITORY:$tag"
  copied="$(crane digest "$THIRD_PARTY_REPOSITORY:$tag")"
  if [[ "$copied" != "$digest" ]]; then
    echo "$name: the copy from $from has digest $copied, not $digest" >&2
    exit 1
  fi
  echo "$name: $THIRD_PARTY_REPOSITORY:$tag@$digest from $from"
done <<<"$images"
