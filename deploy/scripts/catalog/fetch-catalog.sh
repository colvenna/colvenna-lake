#!/usr/bin/env bash
# Puts the Colvenna Catalog release this tree pins (deploy/images/colvenna-catalog.json) where
# install-catalog.sh installs it from, after checking that what it fetched is that release's: nothing
# of the catalog is built here (#295).
#   1. checks both, each read by its digest, before anything is copied: the server image's label
#      org.opencontainers.image.revision is the release's built_from, and it is for linux/amd64; the
#      Helm chart (helm pull oci://...@sha256:...) has the pin's name and version, and its annotation
#      colvenna-catalog/source-commit is built_from, in its OCI config and in the package's
#      Chart.yaml alike;
#   2. copies the image by digest (crane) into the environment's own repository
#      colvenna-lake-<env>/colvenna-catalog, tagged with the release's name (the pin's candidate: one
#      of the catalog's candidates, or a release promoted from one, such as v1.0.0; a tag there that
#      holds another digest is never moved), and keeps the chart's package in deploy/.state/<env>/catalog/;
#   3. records both in deploy/.state/<env>/catalog-release.json, which install-catalog.sh reads.
# The release comes from the registry the catalog publishes it in, GitHub Container Registry
# (ghcr.io/colvenna/colvenna-catalog and ghcr.io/colvenna/charts/colvenna-catalog, as the pin names
# them), read anonymously, as GitHub gives a public package to anyone (until the catalog's are public
# there, an anonymous read is refused). COLVENNA_CATALOG_REGISTRY=<registry>[/<prefix>]
# names another that holds the same digests under the same repository names below its prefix (the
# maintainers' private release registry, for a candidate's acceptance before the catalog is public):
# an ECR registry is read with the AWS credentials, with pull rights only; any other, anonymously too.
# With --release <candidate>, from the copies Colvenna Lake's candidate keeps in its own release
# registry COLVENNA_RELEASE_REGISTRY (colvenna-lake-release/..., as its manifest names them;
# check-release.sh has checked them), the same digests; with --release <release>, a release promoted
# from one, the same copies by the same digests. A run that stopped can run again.
# Usage: COLVENNA_ENV=<env> [COLVENNA_CATALOG_REGISTRY=<registry>[/<prefix>]] fetch-catalog.sh
#        COLVENNA_ENV=<env> COLVENNA_RELEASE_REGISTRY=<account>.dkr.ecr.<region>.amazonaws.com \
#          fetch-catalog.sh --release <candidate or release>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
assert_account
usage="usage: fetch-catalog.sh [--release <candidate>]"
tool="$DEPLOY_DIR/scripts/release/manifest.py"
pin() { python3 "$tool" catalog "$1"; }
candidate="$(pin candidate)" built_from="$(pin built_from)"
image_digest="$(pin image.digest)" chart_digest="$(pin chart.digest)" chart_version="$(pin chart.version)"
# The registry's region when it is an ECR registry, read with the AWS credentials; none when it is read
# anonymously.
region=""
case "${1:-}" in
  "")
    parts="$(python3 "$tool" catalog-source "${COLVENNA_CATALOG_REGISTRY:-}")"
    IFS=$'\t' read -r registry _ region <<<"$parts"
    from="Colvenna Catalog's release $candidate in $registry"
    image_source="$registry/$(pin image.repository)"
    chart_source="$registry/$(pin chart.repository)"
    ;;
  --release)
    release="${2:?$usage}"
    registry="${COLVENNA_RELEASE_REGISTRY:-}"
    if [[ -z "$registry" ]]; then
      echo "COLVENNA_RELEASE_REGISTRY=<account>.dkr.ecr.<region>.amazonaws.com: the release registry that holds" \
        "$release's copies of Colvenna Catalog $candidate" >&2
      exit 1
    fi
    parts="$(python3 "$tool" registry "$registry")"
    region="${parts#*$'\t'}"
    manifest="$REPO_DIR/release/$release/manifest.json"
    python3 "$tool" validate "$manifest" --name "$release"
    from="Colvenna Lake's release $release"
    image_source="$registry/$(python3 "$tool" get "$manifest" catalog.image.repository)"
    chart_source="$registry/$(python3 "$tool" get "$manifest" catalog.chart.repository)"
    ;;
  *) echo "$usage" >&2; exit 1 ;;
esac

work="$(mktemp -d)"
chmod 700 "$work"
# crane and Helm read the registries' credentials from files of this run only.
DOCKER_CONFIG="$work/docker"
HELM_REGISTRY_CONFIG="$work/helm-registry.json"
export DOCKER_CONFIG HELM_REGISTRY_CONFIG
mkdir "$DOCKER_CONFIG"
trap 'rm -rf "$work"' EXIT
aws ecr get-login-password | crane auth login "$ECR_REGISTRY" -u AWS --password-stdin >/dev/null
if [[ -n "$region" ]]; then
  aws ecr get-login-password --region "$region" >"$work/token"
  crane auth login "${registry%%/*}" -u AWS --password-stdin <"$work/token" >/dev/null
  helm registry login "${registry%%/*}" --username AWS --password-stdin <"$work/token" >/dev/null 2>&1
  rm -f "$work/token"
fi
# cannot_read <what>: the registry did not give it; an anonymous one may not have it public yet.
cannot_read() {
  echo "Cannot read $1" >&2
  if [[ -z "$region" && -z "${COLVENNA_CATALOG_REGISTRY:-}" ]]; then
    echo "$registry gives Colvenna Catalog's packages to anyone once they are public. Until $candidate is" \
      "there, COLVENNA_CATALOG_REGISTRY=<registry>[/<prefix>] names a registry holding the same digests," \
      "e.g. the maintainers' release registry <account>.dkr.ecr.<region>.amazonaws.com/colvenna-catalog-release" >&2
  fi
  exit 1
}

# 1. The image and the chart are the release's, each read by its digest (crane and Helm check what the
# registry gives against it), before anything is copied or kept.
crane config "$image_source@$image_digest" >"$work/image-config.json" || cannot_read "$image_source@$image_digest"
python3 "$tool" catalog-check image "$work/image-config.json"
crane config "$chart_source@$chart_digest" >"$work/chart-config.json" || cannot_read "$chart_source@$chart_digest"
python3 "$tool" catalog-check chart "$work/chart-config.json"
mkdir "$work/chart"
helm pull "oci://$chart_source@$chart_digest" --destination "$work/chart" >"$work/pull.txt" 2>&1 \
  || { cat "$work/pull.txt" >&2; cannot_read "oci://$chart_source@$chart_digest"; }
# Helm names a package pulled by digest <name>@sha256-<hex>.tgz: the one file it left.
packages=("$work"/chart/*.tgz)
[[ ${#packages[@]} == 1 && -f "${packages[0]}" ]] \
  || { echo "helm pull left no one chart package: $(cat "$work/pull.txt")" >&2; exit 1; }
package="${packages[0]}"
# The package's own Chart.yaml says the same (helm pull checked the layer against the digest).
helm show chart "$package" >"$work/Chart.yaml"
grep -qx "version: $chart_version" "$work/Chart.yaml" \
  || { echo "The chart package is not version $chart_version: $(cat "$work/Chart.yaml")" >&2; exit 1; }
grep -qx "  colvenna-catalog/source-commit: $built_from" "$work/Chart.yaml" \
  || { echo "The chart package is not annotated colvenna-catalog/source-commit: $built_from" >&2; exit 1; }

# 2. The image into the environment's own repository, by its digest, and the chart's package kept.
repository="$(env_name colvenna-lake/colvenna-catalog)"
if [[ "$(stack_output "$CATALOG_STACK" CatalogRepositoryUri)" != "$ECR_REGISTRY/$repository" ]]; then
  echo "The catalog stack has no repository $ECR_REGISTRY/$repository; deploy it first (deploy-stack.sh)" >&2
  exit 1
fi
held="$(tag_digest "$repository" "$candidate")"
if [[ "$held" == sha256:* && "$held" != "$image_digest" ]]; then
  echo "$repository:$candidate is already $held, not Colvenna Catalog $candidate's $image_digest" >&2
  exit 1
fi
if [[ "$held" != "$image_digest" ]]; then
  crane copy "$image_source@$image_digest" "$ECR_REGISTRY/$repository:$candidate"
fi
if [[ "$(crane digest "$ECR_REGISTRY/$repository:$candidate")" != "$image_digest" ]]; then
  echo "$repository:$candidate is not $image_digest after the copy" >&2
  exit 1
fi
echo "image: $repository:$candidate@$image_digest, built from $built_from, from $from"
mkdir -p "$STATE_DIR/catalog"
kept="$STATE_DIR/catalog/$(pin chart.name)-$chart_version.tgz"
cp "$package" "$kept.part"
mv "$kept.part" "$kept"
echo "chart: $(pin chart.name) $chart_version@$chart_digest, built from $built_from, from $from"

# 3. The record.
python3 - "$STATE_DIR/catalog-release.json" "$candidate" "$built_from" "$ECR_REGISTRY/$repository@$image_digest" \
  "$chart_digest" "${kept#"$STATE_DIR/"}" "$(sha256sum "$kept" | cut -d' ' -f1)" "$from" <<'PY'
import json, os, sys
path, candidate, built_from, image, chart_digest, chart, chart_sha256, source = sys.argv[1:]
with open(path + ".part", "w") as f:
    json.dump({"candidate": candidate, "built_from": built_from, "image": image, "chart_digest": chart_digest,
               "chart": chart, "chart_sha256": chart_sha256, "from": source}, f, indent=2)
    f.write("\n")
os.replace(path + ".part", path)
PY
echo "Colvenna Catalog $candidate (built from $built_from) ready to install: $STATE_DIR/catalog-release.json"
