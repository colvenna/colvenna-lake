#!/usr/bin/env bash
# Checks that this checkout can install release candidate <candidate>, or the release <release> promoted
# from one (deploy/install.sh --release runs it before it touches anything, and stops if it fails):
#   - its manifest, release/<name>/manifest.json, is complete and valid (manifest.py; a release's,
#     promotion.py: its candidate's under the release's names, the same digests), its third-party
#     images the ones deploy/images/third-party.tsv pins;
#   - the tree is clean and differs from the commit the images were built from (source.built_from)
#     under the directories its tag adds alone, release/<candidate>/ (and release/<release>/ for a
#     release), and holds the git objects the manifest pins; a release's checkout is also its
#     candidate's tag commit with release/<release>/ added, and the candidate's release notes at the
#     recorded commit judge it GO (lib/release.sh's release_checkout);
#   - the Doris images it pins are the ones lib/common.sh pins, which install-doris.sh runs;
#   - every image it pins, the third-party ones, the colvenna CLI's and the copies of the Colvenna
#     Catalog release's image and chart too, is in the release registry COLVENNA_RELEASE_REGISTRY (the
#     manifest names no registry; any account and region holding the candidate, wherever the install
#     goes) under its tag with its digest: a release's tags (<release>[-<variant>], <release>-<name>)
#     on its candidate's digests;
#   - the colvenna CLI's image holds the very binaries the manifest pins, by SHA-256;
#   - the catalog's copies are the release the tree pins (deploy/images/colvenna-catalog.json, which
#     manifest.py's validation compares the manifest's catalog with): each one's config, read by
#     digest, says it was built from the release's built_from (the image's label
#     org.opencontainers.image.revision, the chart's annotation colvenna-catalog/source-commit), the
#     image for linux/amd64, the chart of the pin's name and version.
# Reads only, with the pull rights another account gets (ecr:BatchGetImage,
# ecr:GetDownloadUrlForLayer); every problem is listed before it fails.
# Usage: COLVENNA_RELEASE_REGISTRY=<account>.dkr.ecr.<region>.amazonaws.com \
#          deploy/scripts/release/check-release.sh <candidate or release> [--fixtures]
#   --fixtures  the fixtures' images too (install.sh --release <name> --fixtures)
set -euo pipefail
repo="$(cd "$(dirname "$0")/../../.." && pwd)"
REPO_DIR="$repo"
# shellcheck source-path=SCRIPTDIR source=../lib/git.sh
source "$repo/deploy/scripts/lib/git.sh"
# shellcheck source-path=SCRIPTDIR source=../lib/release.sh
source "$repo/deploy/scripts/lib/release.sh"
tool="$repo/deploy/scripts/release/manifest.py"
name="${1:?usage: check-release.sh <candidate or release> [--fixtures]}"
images=(--cli)
case "${2:-}" in
  "") ;;
  --fixtures) images+=(--fixtures) ;;
  *) echo "usage: check-release.sh <candidate or release> [--fixtures]" >&2; exit 1 ;;
esac
python3 "$tool" kind "$name" >/dev/null
if [[ -z "${COLVENNA_RELEASE_REGISTRY:-}" ]]; then
  echo "COLVENNA_RELEASE_REGISTRY=<account>.dkr.ecr.<region>.amazonaws.com: the release registry to install" \
    "$name from (release/README.md)" >&2
  exit 1
fi
registry="$COLVENNA_RELEASE_REGISTRY"
parts="$(python3 "$tool" registry "$registry")"
account="${parts%%$'\t'*}" region="${parts#*$'\t'}"
release_checkout "$name" || exit 1
manifest="$RELEASE_MANIFEST" built_from="$RELEASE_BUILT_FROM"
missing=0
for component in fe be; do
  pinned="$(sed -n "s/^DORIS_${component^^}_DIGEST=//p" "$repo/deploy/scripts/lib/common.sh")"
  released="$(python3 "$tool" get "$manifest" "images.query-engine-$component.digest")"
  if [[ "$released" != "$pinned" ]]; then
    echo "query-engine-$component: the manifest pins $released, lib/common.sh (what install-doris.sh runs) $pinned" >&2
    missing=$((missing + 1))
  fi
done
ecr() { aws ecr "$@" --region "$region" --registry-id "$account"; }
# By tag, with ecr:BatchGetImage: what an install from another account may do (release-stack.ts's
# pull accounts), and what crane's copy reads. Images, indexes and Docker's equivalents alike.
types=(application/vnd.oci.image.index.v1+json application/vnd.oci.image.manifest.v1+json
  application/vnd.docker.distribution.manifest.list.v2+json application/vnd.docker.distribution.manifest.v2+json)
count=0
while IFS=$'\t' read -r key repository tag digest; do
  count=$((count + 1))
  if ! held="$(ecr batch-get-image --repository-name "colvenna-lake-release/$repository" --image-ids "imageTag=$tag" \
    --accepted-media-types "${types[@]}" --query '[images[0].imageId.imageDigest, failures[0].failureCode]' \
    --output text 2>&1)"; then
    echo "$key: cannot read colvenna-lake-release/$repository in $registry: ${held#*: }" >&2
    missing=$((missing + 1))
    continue
  fi
  read -r found failure <<<"$held"
  if [[ "$found" != "$digest" ]]; then
    echo "$key: colvenna-lake-release/$repository:$tag is $found (failure: $failure), not $digest" >&2
    missing=$((missing + 1))
  fi
done < <(python3 "$tool" images "$manifest" "${images[@]}")
# The CLI's binaries, from its image's one layer. cli_layer: extracts them into $work/cli; a
# problem on stderr. In an if, so each step stops it by its own return.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cli_digest="$(python3 "$tool" get "$manifest" cli.digest)"
cli_layer() {
  local image layer url
  image="$(ecr batch-get-image --repository-name colvenna-lake-release/colvenna-cli --image-ids "imageDigest=$cli_digest" \
    --accepted-media-types "${types[@]}" --query 'images[0].imageManifest' --output text)" || return 1
  layer="$(python3 -c 'import json, sys; [layer] = json.load(sys.stdin)["layers"]; print(layer["digest"])' \
    <<<"$image")" || { echo "not an image of one layer" >&2; return 1; }
  url="$(ecr get-download-url-for-layer --repository-name colvenna-lake-release/colvenna-cli --layer-digest "$layer" \
    --query downloadUrl --output text)" || return 1
  curl -fsS "$url" -o "$work/layer" || return 1
  if [[ "sha256:$(sha256sum "$work/layer" | cut -d' ' -f1)" != "$layer" ]]; then
    echo "the downloaded layer is not $layer" >&2
    return 1
  fi
  mkdir -p "$work/cli" && tar -xf "$work/layer" -C "$work/cli"
}
# config_blob <repository> <digest> <file>: an image's or a chart's config (the manifest's config
# blob), read by digest into the file and checked against its own digest; a problem on stderr.
config_blob() {
  local manifest_json config url
  manifest_json="$(ecr batch-get-image --repository-name "$1" --image-ids "imageDigest=$2" \
    --accepted-media-types "${types[@]}" --query 'images[0].imageManifest' --output text)" || return 1
  config="$(python3 -c 'import json, sys; print(json.load(sys.stdin)["config"]["digest"])' <<<"$manifest_json")" \
    || { echo "not an image or chart manifest with a config" >&2; return 1; }
  url="$(ecr get-download-url-for-layer --repository-name "$1" --layer-digest "$config" \
    --query downloadUrl --output text)" || return 1
  curl -fsS "$url" -o "$3" || return 1
  if [[ "sha256:$(sha256sum "$3" | cut -d' ' -f1)" != "$config" ]]; then
    echo "the downloaded config is not $config" >&2
    return 1
  fi
}
for kind in image chart; do
  repository="$(python3 "$tool" get "$manifest" "catalog.$kind.repository")"
  digest="$(python3 "$tool" get "$manifest" "catalog.$kind.digest")"
  if ! { config_blob "$repository" "$digest" "$work/catalog-$kind.json" \
    && python3 "$tool" catalog-check "$kind" "$work/catalog-$kind.json"; } 2>"$work/error"; then
    error="$(tail -1 "$work/error")"
    echo "catalog: $repository@$digest in $registry: ${error#*: }" >&2
    missing=$((missing + 1))
  fi
done
if cli_layer 2>"$work/error"; then
  while read -r binary; do
    want="$(python3 "$tool" get "$manifest" "cli.binaries.$binary")"
    if [[ ! -f "$work/cli/$binary" || "$(sha256sum "$work/cli/$binary" | cut -d' ' -f1)" != "$want" ]]; then
      echo "cli: $binary in colvenna-lake-release/colvenna-cli@$cli_digest is not the manifest's $want" >&2
      missing=$((missing + 1))
    fi
  done < <(python3 "$tool" binaries)
else
  error="$(tail -1 "$work/error")"
  echo "cli: cannot read the binaries of colvenna-lake-release/colvenna-cli@$cli_digest in $registry: ${error#*: }" >&2
  missing=$((missing + 1))
fi
if ((count == 0 || missing > 0)); then
  echo "$missing problems with the $count images $name pins in the release registry $registry" >&2
  exit 1
fi
echo "release $name: $count images in $registry, the CLI's binaries and Colvenna Catalog" \
  "$(python3 "$tool" get "$manifest" catalog.candidate)'s image and chart checked, built from $built_from"
