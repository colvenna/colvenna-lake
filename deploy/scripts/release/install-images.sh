#!/usr/bin/env bash
# What deploy/install.sh --release <candidate> does in place of building a component's images:
# copies them by digest from the release registry COLVENNA_RELEASE_REGISTRY (any account and region
# holding the candidate, wherever the environment is; the manifest names repositories, tags and
# digests, no registry) into the environment's own repositories, and records them as the build
# scripts do, so the steps after it and the acceptance tests read them unchanged. Nothing is built.
# check-release.sh has checked the manifest, the registry, the Doris digests and the CLI's binaries
# before the install began.
#   third-party  the platform's third-party        tagged <name>-<digest prefix> in
#             images (--fixtures: the fixtures'    colvenna-lake-<env>/third-party, as
#             too)                                 foundation/copy-third-party.sh does; nothing to record
#   flink     the jobs' foundation                 flink-image-ref.txt
#   catalog   Colvenna Catalog's release, its      catalog-release.json, by catalog/fetch-catalog.sh
#             image and chart (the copies the      --release <candidate>, which checks both against
#             candidate keeps, colvenna-lake-      the release's built_from; the image tagged
#             release/..., the pin's digests)      <catalog candidate>, as a source install's
#   operator  colvenna-operator and the five jobs  operator-images-<sha>.json
#   doris     the query engine's FE and BE         tagged <DORIS_TAG>-fe-<digest prefix> and -be-<digest
#             (based on Apache Doris)              prefix> in colvenna-lake-<env>/query-engine (lib/common.sh's
#                                                  doris_tag), as doris/copy-images.sh does; nothing to record
#   console   colvenna-console                     entry-images-<sha>.json
#   bi        the BI interface                     entry-images-<sha>.json
#   readers   the fixtures' Spark reader           readers-<sha>.json
#   cli       the colvenna CLI's binaries          colvenna-cli/dist, each checked again against its SHA-256
# The others are tagged in the environment's repository as their builds would tag them, by this
# commit (<sha>, <sha>-<variant>), beside the tags they have already.
# Usage: COLVENNA_ENV=<env> COLVENNA_RELEASE_REGISTRY=<account>.dkr.ecr.<region>.amazonaws.com \
#          deploy/scripts/release/install-images.sh <candidate> <component> [--fixtures]
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
assert_account
usage="usage: install-images.sh <candidate> third-party [--fixtures]|flink|catalog|operator|doris|console|bi|readers|cli"
candidate="${1:?$usage}"
component="${2:?$usage}"
tool="$DEPLOY_DIR/scripts/release/manifest.py"
manifest="$REPO_DIR/release/$candidate/manifest.json"
python3 "$tool" validate "$manifest" --candidate "$candidate"
sha="$(committed_sha)"
registry="${COLVENNA_RELEASE_REGISTRY:?COLVENNA_RELEASE_REGISTRY=<account>.dkr.ecr.<region>.amazonaws.com: the release registry}"
parts="$(python3 "$tool" registry "$registry")"
region="${parts#*$'\t'}"
declare -A repository digest reference
while IFS=$'\t' read -r key repo _ value; do
  repository[$key]="$repo" digest[$key]="$value" reference[$key]="$registry/colvenna-lake-release/$repo@$value"
done < <(python3 "$tool" images "$manifest" --fixtures --cli)

DOCKER_CONFIG="$(mktemp -d)"
export DOCKER_CONFIG
work="$(mktemp -d)"
trap 'rm -rf "$DOCKER_CONFIG" "$work"' EXIT
aws ecr get-login-password | crane auth login "$ECR_REGISTRY" -u AWS --password-stdin >/dev/null
if [[ "$registry" != "$ECR_REGISTRY" ]]; then
  aws ecr get-login-password --region "$region" | crane auth login "$registry" -u AWS --password-stdin >/dev/null
fi

# own <key>: the key's image in the environment's own repository, by digest.
own() {
  printf '%s/%s@%s\n' "$ECR_REGISTRY" "$(env_name "colvenna-lake/${repository[$1]}")" "${digest[$1]}"
}
# place <key> <tag>: copies the key's image into the environment's repository under that tag;
# prints its pinned reference there. A tag the repository already holds must hold the same digest.
# Assigned before it is used: a failure inside "$(...)" would not stop the script.
place() {
  local key="$1" tag="$2" name held
  name="$(env_name "colvenna-lake/${repository[$1]}")"
  held="$(aws ecr describe-images --repository-name "$name" --image-ids "imageTag=$tag" \
    --query 'imageDetails[0].imageDigest' --output text 2>/dev/null || true)"
  if [[ "$held" == sha256:* && "$held" != "${digest[$key]}" ]]; then
    echo "$key: $name:$tag is already $held, not the release's ${digest[$key]}" >&2
    return 1
  fi
  if [[ "$held" != "${digest[$key]}" ]]; then
    crane copy "${reference[$key]}" "$ECR_REGISTRY/$name:$tag" >&2
  fi
  if [[ "$(crane digest "$ECR_REGISTRY/$name:$tag")" != "${digest[$key]}" ]]; then
    echo "$key: $name:$tag is not ${digest[$key]} after the copy" >&2
    return 1
  fi
  own "$key"
}

case "$component" in
  third-party)
    # The platform's, and with --fixtures the fixtures': the tags lib/common.sh's third_party_image
    # names, the same a source install's copy-third-party.sh gives.
    scope=()
    case "${3:-}" in
      "") ;;
      --fixtures) scope=(--fixtures) ;;
      *) echo "$usage" >&2; exit 1 ;;
    esac
    while IFS=$'\t' read -r name _ _ tag; do
      image="$(place "$name" "$tag")"
      echo "$name $THIRD_PARTY_REPOSITORY:$tag@${image##*@}"
    done < <(python3 "$tool" third-party "${scope[@]}")
    ;;
  flink)
    image="$(place flink "$sha")"
    echo "$image" | tee "$STATE_DIR/flink-image-ref.txt"
    ;;
  catalog)
    bash "$DEPLOY_DIR/scripts/catalog/fetch-catalog.sh" --release "$candidate"
    ;;
  operator)
    declare -A placed
    placed[operator]="$(place operator "$sha")"
    for variant in mysql postgres mongodb sql kafka; do
      placed[$variant]="$(place "jobs-$variant" "$sha-$variant")"
    done
    refs="$STATE_DIR/operator-images-$sha.json"
    {
      printf '{\n  "sha": "%s",\n  "foundation": "%s",\n  "operator": "%s"' "$sha" "$(own flink)" "${placed[operator]}"
      for variant in mysql postgres mongodb sql kafka; do printf ',\n  "%s": "%s"' "$variant" "${placed[$variant]}"; done
      printf '\n}\n'
    } >"$refs.part"
    mv "$refs.part" "$refs"
    cat "$refs"
    ;;
  doris)
    # Each under the tag of its digest (lib/common.sh's doris_tag), the one a source install gives it:
    # a candidate pinning another digest puts it beside the one an earlier install placed (#278).
    for engine in fe be; do
      tag="$(doris_tag "$engine" "${digest[query-engine-$engine]}")"
      image="$(place "query-engine-$engine" "$tag")"
      echo "$engine: $image"
    done
    ;;
  console | bi)
    image="$(place "$component" "$sha")"
    record_image "$component" "$image"
    echo "$component $image"
    ;;
  readers)
    image="$(place readers-spark "$sha-spark")"
    printf '{\n  "sha": "%s",\n  "spark": "%s"\n}\n' "$sha" "$image" | tee "$STATE_DIR/readers-$sha.json"
    ;;
  cli)
    # The binaries make-manifest.sh built, as the one layer of the release's colvenna-cli image.
    crane export "${reference[cli]}" - | tar -x -C "$work"
    mkdir -p "$REPO_DIR/colvenna-cli/dist"
    while read -r binary; do
      want="$(python3 "$tool" get "$manifest" "cli.binaries.$binary")"
      if [[ "$(sha256sum "$work/$binary" | cut -d' ' -f1)" != "$want" ]]; then
        echo "cli: $binary in ${digest[cli]} is not the manifest's $want" >&2
        exit 1
      fi
      install -m 0755 "$work/$binary" "$REPO_DIR/colvenna-cli/dist/$binary"
      echo "cli: colvenna-cli/dist/$binary $want"
    done < <(python3 "$tool" binaries)
    ;;
  *)
    echo "$usage" >&2
    exit 1
    ;;
esac
