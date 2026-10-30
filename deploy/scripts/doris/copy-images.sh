#!/usr/bin/env bash
# Puts the platform's Doris FE and BE images (lib/common.sh's DORIS_FE_DIGEST and DORIS_BE_DIGEST:
# the query engine, a modified build based on Apache Doris 4.1.4 with the Paimon 2.0.0 reader,
# deploy/images/doris) into the environment's own repository, colvenna-lake-<env>/query-engine, by
# digest, each under the tag lib/common.sh's doris_tag gives it, <DORIS_TAG>-fe-<digest prefix> and
# -be-<digest prefix>, as release/install-images.sh does. A digest the repository holds under other
# tags only (build-images.sh's build-<sha>-fe, say) is given its tag, by digest: nothing is copied or
# built, the digest stays. Those to copy come from DORIS_IMAGE_SOURCE, an ECR repository holding them,
# <account>.dkr.ecr.<region>.amazonaws.com/<repository>, of any account the caller may pull from and
# in any standard region. By default it is, in the release registry COLVENNA_RELEASE_REGISTRY, the query
# engine's repository of the newest release manifest of the current schema and names in the
# checkout (release/<candidate>/manifest.json) whose FE and BE are these digests; without either,
# DORIS_IMAGE_SOURCE must be given. crane copies
# each manifest as it is, so the digests do not change; build-images.sh builds the same recipe anew
# instead.
# Usage: [DORIS_IMAGE_SOURCE=<repository> | COLVENNA_RELEASE_REGISTRY=<registry>] copy-images.sh
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
# shellcheck source=../lib/preflight.sh
source "$(dirname "$0")/../lib/preflight.sh"
assert_account
repository="$(env_name "$DORIS_REPOSITORY_NAME")"
if [[ "$(stack_output "$FOUNDATION_STACK" QueryEngineImageRepositoryUri)" != "$DORIS_REPOSITORY" ]]; then
  echo "The foundation stack has no query engine repository $DORIS_REPOSITORY; deploy it first" >&2
  exit 1
fi
# The source, as install.sh's preflight checked it (lib/preflight.sh): a DORIS_IMAGE_SOURCE given is
# an ECR repository in a standard region, and the two images are in the environment's repository
# already, or the source holds them.
assert_doris_source || exit 1
# What each needs: nothing (its tag holds its digest), a tag (the repository holds the digest under
# other tags only), or a copy from the source. A tag holding another digest is not rewritten (the
# repository's tags cannot be): it stops the step.
declare -A digest tag need
digest[fe]="$DORIS_FE_DIGEST" digest[be]="$DORIS_BE_DIGEST"
for component in fe be; do
  tag[$component]="$(doris_tag "$component" "${digest[$component]}")"
  held="$(aws ecr describe-images --repository-name "$repository" --image-ids "imageTag=${tag[$component]}" \
    --query 'imageDetails[0].imageDigest' --output text 2>/dev/null || true)"
  if [[ "$held" == "${digest[$component]}" ]]; then
    need[$component]=nothing
  elif [[ "$held" == sha256:* ]]; then
    echo "$component: $repository:${tag[$component]} is already $held, not ${digest[$component]}" >&2
    exit 1
  elif aws ecr describe-images --repository-name "$repository" --image-ids "imageDigest=${digest[$component]}" \
    >/dev/null 2>&1; then
    need[$component]=tag
  else
    need[$component]=copy
  fi
done
# Both under their tags already: nothing to copy or tag, and no registry to log in to.
if [[ "${need[fe]} ${need[be]}" == "nothing nothing" ]]; then
  for component in fe be; do
    echo "$component: $DORIS_REPOSITORY:${tag[$component]}@${digest[$component]} (already there)"
  done
  exit 0
fi
# crane reads the registry credential from a private Docker config of this run only.
DOCKER_CONFIG="$(mktemp -d)"
export DOCKER_CONFIG
trap 'rm -rf "$DOCKER_CONFIG"' EXIT
aws ecr get-login-password | crane auth login "$ECR_REGISTRY" -u AWS --password-stdin >/dev/null
# The source to copy from, and its region, as the check read them; only when there is a copy.
if [[ "${need[fe]} ${need[be]}" == *copy* ]]; then
  source_repository="$(doris_source)"
  doris_source_parts "$source_repository" || exit 1
  # Another registry (another region's, or another account's: one token of a region serves every
  # registry of it the caller may read).
  if [[ "${source_repository%%/*}" != "$ECR_REGISTRY" ]]; then
    aws ecr get-login-password --region "$DORIS_SOURCE_REGION" \
      | crane auth login "${source_repository%%/*}" -u AWS --password-stdin >/dev/null
  fi
fi
for component in fe be; do
  target="$DORIS_REPOSITORY:${tag[$component]}"
  case "${need[$component]}" in
    nothing)
      echo "$component: $target@${digest[$component]} (already there)"
      continue
      ;;
    tag)
      crane tag "$DORIS_REPOSITORY@${digest[$component]}" "${tag[$component]}"
      how="there already, tagged now"
      ;;
    copy)
      crane copy "$source_repository@${digest[$component]}" "$target"
      how="from $source_repository"
      ;;
  esac
  placed="$(crane digest "$target")"
  if [[ "$placed" != "${digest[$component]}" ]]; then
    echo "$component: $target has digest $placed, not ${digest[$component]}" >&2
    exit 1
  fi
  echo "$component: $target@${digest[$component]} ($how)"
done
