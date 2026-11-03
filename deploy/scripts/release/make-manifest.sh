#!/usr/bin/env bash
# Makes release candidate <candidate> of this commit, from the environment COLVENNA_ENV installed from
# source at it (deploy/install.sh --fixtures):
#   1. checks that the environment is this commit's: its stacks' SourceSha, the images it recorded
#      (deploy/.state/<env>/) and tagged with this commit in its own repositories, its copies of the
#      third-party images this commit pins (deploy/images/third-party.tsv), the Colvenna Catalog
#      release this commit pins (deploy/images/colvenna-catalog.json: its image tagged with the
#      catalog's candidate, its chart the catalog's release runs from), and the images its
#      workloads run, every pod of them; and that no container runs an image from upstream (only the
#      environment's own, and AWS's in the namespaces of its add-ons and agents, which the evidence
#      lists);
#   2. copies each image into the release registry (deploy-registry.sh: colvenna-lake-release/... in the
#      caller's account and COLVENNA_RELEASE_REGION, default us-west-2) by digest, tagged <candidate> or
#      <candidate>-<variant> (manifest.py names), the third-party ones from the environment's copies
#      into colvenna-lake-release/third-party as <candidate>-<name>, the catalog release's image (from
#      the environment's copy) and chart (an OCI artifact, from where a source install fetches it:
#      COLVENNA_CATALOG_REGISTRY when given, else GitHub Container Registry, anonymously) into
#      colvenna-lake-release/colvenna-catalog and colvenna-lake-release/charts/colvenna-catalog,
#      so the candidate needs no other registry, and builds the colvenna CLI's binaries
#      (colvenna-cli/check.sh build) into one more image there;
#   3. writes release/<candidate>/manifest.json (manifest.py assemble) and notes.md, the release
#      notes' draft from release/notes-template.md.
# Before the first, the release registry must have every repository the candidate is copied into: one
# release-stack.ts added after the registry was deployed (charts/colvenna-catalog, #295) is there only
# once deploy-registry.sh has deployed it again.
# What the manifest says of the tree (charts, scripts, cases, the notes' template) is read from this
# commit, which must still be HEAD, clean, before the CLI is built and before anything is written.
# Commit those two files alone on top of this commit and tag that commit <candidate> (release/README.md):
# deploy/install.sh --release <candidate> installs only from a tree that differs from this commit
# under release/. The manifest names repositories, tags and digests, no registry, account or
# identity (#248): an install copies from the registry its COLVENNA_RELEASE_REGISTRY names, this one or
# a copy of it. A run that stopped can run again: a tag the release registry already holds must hold
# the same digest (the CLI's: the same binaries as this build), and is kept. Evidence (what was
# checked and copied, and by whom) goes to RELEASE_EVIDENCE, by default
# deploy/.state/<env>/release-<candidate>/.
# Usage: COLVENNA_ENV=<env> [COLVENNA_CATALOG_REGISTRY=<registry>[/<prefix>]] [COLVENNA_RELEASE_REGION=<region>] \
#          [RELEASE_REGIONS=<region>,<region>...] deploy/scripts/release/make-manifest.sh <candidate>
#   COLVENNA_CATALOG_REGISTRY  a registry holding the Colvenna Catalog release this commit pins in
#                            place of the pin's, GitHub Container Registry (fetch-catalog.sh's; the
#                            one the environment's source install fetched it from): an ECR one is
#                            read with the AWS credentials, any other anonymously
#   COLVENNA_RELEASE_REGION  the release registry's region (default us-west-2)
#   RELEASE_REGIONS          the standard regions the candidate is accepted in, two or more
#                            (configurations.regions; default us-west-2,us-east-2)
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
assert_account
candidate="${1:?usage: COLVENNA_ENV=<env> make-manifest.sh <candidate>, e.g. v1.0.0-rc.1}"
tool="$DEPLOY_DIR/scripts/release/manifest.py"
# A release (v<major>.<minor>.<patch>) is never made: one of its candidates judged GO is promoted to it
# (promote.sh), its images the candidate's.
if [[ "$(python3 "$tool" kind "$candidate")" != candidate ]]; then
  echo "$candidate is a release's name: a release is promoted from a candidate judged GO" \
    "(deploy/scripts/release/promote.sh <candidate> $candidate), never made" >&2
  exit 1
fi
names="$(python3 "$tool" names "$candidate")"
sha="$(committed_sha)"
out="$REPO_DIR/release/$candidate"
if [[ -e "$out/manifest.json" ]]; then
  echo "$out/manifest.json exists: a candidate is made once; name another" >&2
  exit 1
fi
# still_this_commit: the tree is still this commit's, clean.
still_this_commit() {
  if [[ "$(committed_sha)" != "$sha" ]]; then
    echo "HEAD moved from $sha during the run: make the candidate again" >&2
    exit 1
  fi
}
work="$(mktemp -d)"
DOCKER_CONFIG="$(mktemp -d)"
export DOCKER_CONFIG
trap 'rm -rf "$DOCKER_CONFIG" "$work"' EXIT
# The release's required cases (docs/uat/cases.md, between the required-cases markers), from this
# commit: checked before anything is copied.
git -C "$REPO_DIR" show "$sha:docs/uat/cases.md" >"$work/cases.md"
python3 "$tool" required-cases "$work/cases.md" >/dev/null
# The regions the candidate is accepted in, checked as the manifest will be (two or more distinct
# standard ones) before anything is copied.
regions="${RELEASE_REGIONS:-us-west-2,us-east-2}"
python3 "$tool" regions "$regions" >/dev/null
release_region="${COLVENNA_RELEASE_REGION:-us-west-2}"
if ! standard_region "$release_region"; then
  echo "COLVENNA_RELEASE_REGION: $release_region is not a standard AWS region" >&2
  exit 1
fi
release_registry="$(aws sts get-caller-identity --query Account --output text).dkr.ecr.$release_region.amazonaws.com"
# The Colvenna Catalog release this commit pins, and where its chart is copied from: as a source
# install fetches it (fetch-catalog.sh), with the region of an ECR registry, which is read with the
# AWS credentials (none: anonymously).
catalog_parts="$(python3 "$tool" catalog-source "${COLVENNA_CATALOG_REGISTRY:-}")"
IFS=$'\t' read -r catalog_source _ catalog_region <<<"$catalog_parts"
catalog() { python3 "$tool" catalog "$1"; }
catalog_candidate="$(catalog candidate)"
release_stack="$(python3 "$tool" stack)"
ev="${RELEASE_EVIDENCE:-$STATE_DIR/release-$candidate}"
mkdir -p "$ev"
log() { echo "$*" | tee -a "$ev/make-manifest.log"; }
log "$(date -u +%FT%TZ) candidate $candidate of $sha from $COLVENNA_ENV in $COLVENNA_REGION into $release_registry"
log "by $(aws sts get-caller-identity --query Arn --output text)"
if ! aws cloudformation describe-stacks --region "$release_region" --stack-name "$release_stack" \
  >/dev/null 2>&1; then
  echo "No release registry ($release_stack in $release_region): deploy/scripts/release/deploy-registry.sh" >&2
  exit 1
fi
# Every repository the candidate is copied into (names gives them, the CLI's too) is in the release
# registry, before anything is checked or copied: one release-stack.ts has added since the registry was
# deployed is not, until it is deployed again. A registry deployed before #295 has no
# charts/colvenna-catalog, for the catalog release's chart.
registry_repositories="$(aws ecr describe-repositories --region "$release_region" \
  --query "repositories[?starts_with(repositoryName, 'colvenna-lake-release/')].repositoryName" --output text)" \
  || { echo "Cannot list the release registry's repositories in $release_region" >&2; exit 1; }
missing="$(comm -23 <(cut -f2 <<<"$names" | sed 's|^|colvenna-lake-release/|' | sort -u) \
  <(tr -s '[:blank:]' '\n' <<<"$registry_repositories" | sort -u))"
if [[ -n "$missing" ]]; then
  echo "The release registry ($release_stack in $release_region) has no $(paste -sd, <<<"$missing" | sed 's/,/, /g'):" \
    "deploy it again at this commit (COLVENNA_RELEASE_REGION=$release_region" \
    "deploy/scripts/release/deploy-registry.sh, with the COLVENNA_RELEASE_PULL_ACCOUNTS of its last" \
    "deploy, or the other accounts lose their pull rights), then make the candidate" >&2
  exit 1
fi
log "release registry: every repository the candidate is copied into, in $release_stack"

# 1. The environment is this commit's.
for stack in "$FOUNDATION_STACK" "$CATALOG_STACK" "$OPERATOR_STACK" "$OPEN_LAYER_STACK" "$ENTRY_STACK" \
  "$TEST_MYSQL_STACK" "$TEST_POSTGRES_STACK"; do
  deployed="$(aws cloudformation describe-stacks --stack-name "$stack" \
    --query "Stacks[0].Tags[?Key=='SourceSha'].Value | [0]" --output text 2>/dev/null || true)"
  if [[ "$deployed" != "$sha" ]]; then
    echo "$stack is of ${deployed:-nothing}, not $sha: install $COLVENNA_ENV at this commit with --fixtures first" >&2
    exit 1
  fi
done
log "stacks: all seven of $sha"
# recorded <file> <field>: a reference the build scripts (or fetch-catalog.sh) recorded for this commit.
recorded() {
  python3 "$tool" get "$STATE_DIR/$1" "$2" 2>/dev/null \
    || { echo "No $2 in $STATE_DIR/$1: install $COLVENNA_ENV at this commit with --fixtures" >&2; exit 1; }
}
declare -A ref
ref[flink]="$(cat "$STATE_DIR/flink-image-ref.txt")"
# The catalog release, as fetch-catalog.sh fetched it for the environment: the image its copy in the
# environment's repository, the chart its release's (the environment holds only the package).
fetched() { recorded catalog-release.json "$1"; }
if [[ "$(fetched candidate)" != "$catalog_candidate" || "$(fetched chart_digest)" != "$(catalog chart.digest)" ]]; then
  echo "$COLVENNA_ENV fetched Colvenna Catalog $(fetched candidate), not $catalog_candidate as this commit pins:" \
    "install it at this commit first" >&2
  exit 1
fi
ref[catalog]="$(fetched image)"
ref[catalog-chart]="$catalog_source/$(catalog chart.repository)@$(catalog chart.digest)"
for name in operator mysql postgres mongodb sql kafka; do
  key="jobs-$name"
  [[ "$name" == operator ]] && key=operator
  ref[$key]="$(recorded "operator-images-$sha.json" "$name")"
done
ref[console]="$(recorded "entry-images-$sha.json" console)"
ref[bi]="$(recorded "entry-images-$sha.json" bi)"
ref[readers-spark]="$(recorded "readers-$sha.json" spark)"
ref[query-engine-fe]="$DORIS_FE_IMAGE"
ref[query-engine-be]="$DORIS_BE_IMAGE"
# The third-party images, the platform's and the fixtures': the environment's copies
# (copy-third-party.sh), which outlive upstream's.
declare -A built_tag
while IFS=$'\t' read -r name _ digest tag; do
  ref[$name]="$THIRD_PARTY_REPOSITORY@$digest" built_tag[$name]="$tag"
done < <(python3 "$tool" third-party --fixtures)
if [[ "$(recorded "operator-images-$sha.json" foundation)" != "${ref[flink]}" ]]; then
  echo "The jobs were built on $(recorded "operator-images-$sha.json" foundation), not ${ref[flink]}" >&2
  exit 1
fi
# Each image (but Doris's, pinned by digest in lib/common.sh, and the catalog release's) carries this
# commit's tag in the environment's own repository: build-flink-image.sh, build-images.sh,
# build-console.sh, build-bi.sh and build-readers.sh tag them so; a third-party one, its
# <name>-<digest prefix>.
built_tag+=([flink]="$sha" [operator]="$sha" [console]="$sha" [bi]="$sha"
  [readers-spark]="$sha-spark")
for name in mysql postgres mongodb sql kafka; do built_tag[jobs-$name]="$sha-$name"; done
while IFS=$'\t' read -r key repository _; do
  [[ "$key" == cli || "$key" == catalog-chart ]] && continue
  image="${ref[$key]}"
  if [[ "$key" == catalog ]]; then
    # The pinned release's, under the catalog's candidate in the environment's repository.
    tagged="$(tag_digest "$(env_name colvenna-lake/colvenna-catalog)" "$catalog_candidate")"
    if [[ "$image" != *"@$(catalog image.digest)" || "$tagged" != "$(catalog image.digest)" ]]; then
      echo "catalog: $image, tagged $catalog_candidate as $tagged, is not Colvenna Catalog $catalog_candidate's" \
        "$(catalog image.digest)" >&2
      exit 1
    fi
    log "recorded catalog $image (Colvenna Catalog $catalog_candidate)"
    continue
  fi
  if [[ "$image" != "$ECR_REGISTRY/$(env_name "colvenna-lake/$repository")@sha256:"* ]]; then
    echo "$key: $image is not in $COLVENNA_ENV's own repository $(env_name "colvenna-lake/$repository")" >&2
    exit 1
  fi
  if ! tags="$(aws ecr describe-images --repository-name "$(env_name "colvenna-lake/$repository")" \
    --image-ids "imageDigest=${image#*@}" --query 'imageDetails[0].imageTags' --output text 2>&1)"; then
    echo "$key: no image ${image#*@} in $(env_name "colvenna-lake/$repository"): $tags" >&2
    exit 1
  fi
  if [[ -n "${built_tag[$key]:-}" && " $(tr '\t' ' ' <<<"$tags") " != *" ${built_tag[$key]} "* ]]; then
    echo "$key: $image is not tagged ${built_tag[$key]} (it is: $tags): not built from $sha" >&2
    exit 1
  fi
  log "recorded $key $image"
done <<<"$names"

# running <namespace> <kind>/<name>: the digests the containers of the workload's live pods run, once
# each; "not-ready" for a container that is not, so that a rollout in progress is not taken. A finished
# pod (LIVE_PODS) runs nothing.
running() {
  local selector
  selector="$(kubectl -n "$1" get "$2" -o json | python3 -c '
import json, sys
labels = json.load(sys.stdin)["spec"]["selector"]["matchLabels"]
print(",".join(f"{k}={v}" for k, v in sorted(labels.items())))')"
  kubectl -n "$1" get pods -l "$selector" "$LIVE_PODS" -o json | python3 -c '
import json, sys
pods = json.load(sys.stdin)["items"]
if not pods:
    print("no-pod")
for pod in pods:
    for c in pod["status"].get("containerStatuses", []):
        print(c["imageID"].rsplit("@", 1)[-1] if c.get("ready") else "not-ready")' | sort -u
}
ensure_kubeconfig
assert_cluster
for workload in "catalog $CATALOG_NAMESPACE deployment/colvenna-catalog" \
  "operator $OPERATOR_NAMESPACE deployment/$OPERATOR_RELEASE" \
  "console $CONSOLE_NAMESPACE deployment/$CONSOLE_RELEASE" "bi $BI_NAMESPACE deployment/$BI_RELEASE" \
  "query-engine-fe $DORIS_NAMESPACE statefulset/$DORIS_CLUSTER-fe" \
  "query-engine-be $DORIS_NAMESPACE statefulset/$DORIS_CLUSTER-be"; do
  read -r key namespace name <<<"$workload"
  digests="$(running "$namespace" "$name")"
  if [[ "$digests" != "${ref[$key]#*@}" ]]; then
    echo "$key: $namespace/$name runs $(tr '\n' ' ' <<<"$digests"), not ${ref[$key]#*@}" >&2
    exit 1
  fi
  log "running $key $namespace/$name ${ref[$key]#*@}"
done
# The jobs' images are the operator's, which it gives the Flink deployments it makes.
jobs="$(kubectl -n "$OPERATOR_NAMESPACE" get "deployment/$OPERATOR_RELEASE" -o json | python3 -c '
import json, sys
spec = json.load(sys.stdin)["spec"]["template"]["spec"]
env = {e["name"]: e.get("value") for c in spec["containers"] for e in c.get("env", [])}
for name, image in sorted(json.loads(env["COLVENNA_JOB_IMAGES"]).items()):
    print(name, image)')"
for name in mysql postgres mongodb sql kafka; do
  if [[ "$(awk -v n="$name" '$1 == n {print $2}' <<<"$jobs")" != "${ref[jobs-$name]}" ]]; then
    echo "jobs-$name: the operator gives its jobs $(awk -v n="$name" '$1 == n {print $2}' <<<"$jobs"), not ${ref[jobs-$name]}" >&2
    exit 1
  fi
  log "running jobs-$name: the operator's COLVENNA_JOB_IMAGES ${ref[jobs-$name]}"
done
# Nothing runs from upstream: every container, of every workload and of every pod not finished,
# runs the environment's own image, or AWS's own in its add-ons' and agents' namespaces (the EKS
# add-ons, GuardDuty's agent: AWS installs and updates them, out of the release), which the
# evidence records. A finished pod (Succeeded or Failed) runs nothing and never starts again, so a
# Completed one an earlier commit left does not count (manifest.py running).
kubectl get deployments,statefulsets,daemonsets,pods --all-namespaces -o json >"$work/workloads.json"
foreign="$(python3 "$tool" running "$work/workloads.json" "$ECR_REGISTRY/$(env_name colvenna-lake)")"
upstream="$(awk -F'\t' '$4 == "upstream"' <<<"$foreign")"
if [[ -n "$upstream" ]]; then
  echo "Containers of $COLVENNA_ENV that run an image from upstream, not its own copy (deploy/images/third-party.tsv):" >&2
  awk -F'\t' '{printf "  %s, container %s: %s\n", $1, $2, $3}' <<<"$upstream" >&2
  echo "Each is <namespace>/<kind>/<name>. Install what runs it again at this commit (install.sh --fixtures;" \
    "its strimzi step moves a development Kafka of an earlier commit to the copy), delete a pod or workload" \
    "a test left behind (kubectl -n <namespace> delete pod <name>), or pin its image in the list" >&2
  exit 1
fi
managed="$(awk -F'\t' '$4 == "aws" {print "aws-managed " $1 " " $2 " " $3}' <<<"$foreign")"
[[ -z "$managed" ]] || printf '%s\n' "$managed" >>"$ev/make-manifest.log"
log "running: every container from $COLVENNA_ENV's own repositories, but $(grep -c . <<<"$managed" || true) of AWS's add-ons and agents"
# The catalog's Helm release runs the pinned chart.
chart_running="$(helm -n "$CATALOG_NAMESPACE" get metadata colvenna-catalog -o json | python3 -c '
import json, sys
m = json.load(sys.stdin)
print(m["chart"], m["version"])')"
if [[ "$chart_running" != "$(catalog chart.name) $(catalog chart.version)" ]]; then
  echo "catalog: the release colvenna-catalog runs the chart $chart_running, not $(catalog chart.name)" \
    "$(catalog chart.version)" >&2
  exit 1
fi
log "running catalog-chart $chart_running (Colvenna Catalog $catalog_candidate)"

# 2. Into the release registry. crane reads the registries' credentials from the Docker config of
# this run only.
aws ecr get-login-password | crane auth login "$ECR_REGISTRY" -u AWS --password-stdin >/dev/null
if [[ "$release_registry" != "$ECR_REGISTRY" ]]; then
  aws ecr get-login-password --region "$release_region" \
    | crane auth login "$release_registry" -u AWS --password-stdin >/dev/null
fi
if [[ -n "$catalog_region" && "${catalog_source%%/*}" != "$ECR_REGISTRY" && "${catalog_source%%/*}" != "$release_registry" ]]; then
  aws ecr get-login-password --region "$catalog_region" \
    | crane auth login "${catalog_source%%/*}" -u AWS --password-stdin >/dev/null
fi
log "catalog-chart from $catalog_source$([[ -z "$catalog_region" ]] && echo ', anonymously')"
# release_digest <repository> <tag>: the digest the release registry holds under that tag, or nothing.
release_digest() {
  tag_digest "colvenna-lake-release/$1" "$2" --region "$release_region"
}
while IFS=$'\t' read -r key repository tag; do
  [[ "$key" == cli ]] && continue
  want="${ref[$key]#*@}" held="$(release_digest "$repository" "$tag")"
  if [[ "$held" == sha256:* && "$held" != "$want" ]]; then
    echo "$key: the release registry holds $repository:$tag as $held, not $want" >&2
    exit 1
  fi
  if [[ "$held" != "$want" ]]; then
    crane copy "${ref[$key]}" "$release_registry/colvenna-lake-release/$repository:$tag"
  fi
  if [[ "$(crane digest "$release_registry/colvenna-lake-release/$repository:$tag")" != "$want" ]]; then
    echo "$key: the copy has another digest than $want" >&2
    exit 1
  fi
  printf '%s=%s\n' "$key" "$want" >>"$work/digests"
  log "release $key $release_registry/colvenna-lake-release/$repository:$tag $want"
done <<<"$names"
# The CLI: built here from this commit, in the pinned Go image; its binaries the one layer of an
# image (no base), so an install extracts the very files. An image the registry already holds
# under the tag must hold these very binaries.
still_this_commit
bash "$REPO_DIR/colvenna-cli/check.sh" build >"$ev/cli-build.log" 2>&1
mapfile -t binaries < <(python3 "$tool" binaries)
mkdir "$work/cli" "$work/held"
for binary in "${binaries[@]}"; do cp "$REPO_DIR/colvenna-cli/dist/$binary" "$work/cli/"; done
# Built from this commit still: binaries of another, under a tag that never moves, would spend the
# candidate's name.
still_this_commit
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -cf "$work/cli.tar" -C "$work/cli" "${binaries[@]}"
cli_tag="$(awk -F'\t' '$1 == "cli" {print $3}' <<<"$names")"
cli_image="$release_registry/colvenna-lake-release/colvenna-cli:$cli_tag"
cli_digest="$(release_digest colvenna-cli "$cli_tag")"
if [[ "$cli_digest" == sha256:* ]]; then
  crane export "$release_registry/colvenna-lake-release/colvenna-cli@$cli_digest" - | tar -x -C "$work/held"
  for binary in "${binaries[@]}"; do
    if ! cmp -s "$work/cli/$binary" "$work/held/$binary"; then
      echo "cli: $cli_image ($cli_digest) holds another $binary than this build: make another candidate" >&2
      exit 1
    fi
  done
  log "release cli $cli_image $cli_digest (already there, the same binaries)"
else
  crane append --oci-empty-base -f "$work/cli.tar" -t "$cli_image" >/dev/null
  cli_digest="$(crane digest "$cli_image")"
  log "release cli $cli_image $cli_digest"
fi

# 3. The manifest and the notes' draft, from this commit.
still_this_commit
git_rev() { git -C "$REPO_DIR" rev-parse "$sha:$1"; }
git -C "$REPO_DIR" show "$sha:deploy/images/colvenna-catalog.json" >"$work/catalog.json"
mkdir "$work/charts"
while read -r chart; do
  git -C "$REPO_DIR" show "$sha:deploy/helm/$chart/Chart.yaml" >"$work/charts/$chart"
  printf '%s=%s\n' "$chart" "$(git_rev "deploy/helm/$chart")" >>"$work/trees"
done < <(git -C "$REPO_DIR" ls-tree --name-only "$sha" deploy/helm/ | sed 's|^deploy/helm/||')
python3 - "$work/params.json" "$candidate" "$sha" "$(date -u +%FT%TZ)" "$COLVENNA_ENV" "$COLVENNA_REGION" \
  "$(git_rev deploy/install.sh)" "$(git_rev deploy/uninstall.sh)" \
  "$(git -C "$REPO_DIR" log -1 --format=%H "$sha" -- docs/uat/cases.md)" "$(git_rev docs/uat/cases.md)" \
  "$regions" "$cli_digest" <<'PY'
import json, sys
(path, candidate, sha, at, environment, region, install, uninstall, cases_commit, cases_blob, regions,
 cli_digest) = sys.argv[1:]
with open(path, "w") as f:
    json.dump({
        "candidate": candidate, "built_from": sha,
        "generated": {"at": at, "environment": environment, "region": region},
        "installer": {"install": {"path": "deploy/install.sh", "blob": install},
                      "uninstall": {"path": "deploy/uninstall.sh", "blob": uninstall}},
        "uat_cases": {"path": "docs/uat/cases.md", "commit": cases_commit, "blob": cases_blob},
        "regions": regions.split(","), "cli_digest": cli_digest,
    }, f)
PY
python3 "$tool" assemble "$work" >"$work/manifest.json"
python3 "$tool" validate "$work/manifest.json" --name "$candidate"
git -C "$REPO_DIR" show "$sha:release/notes-template.md" | python3 "$tool" notes "$work/manifest.json" >"$work/notes.md"
cp "$work/manifest.json" "$ev/manifest.json"
# Into the tree only once both are whole: a run that stops before leaves it clean, to run again.
still_this_commit
mkdir -p "$out"
cp "$work/manifest.json" "$work/notes.md" "$out/"
log "wrote $out/manifest.json and $out/notes.md; commit them alone, then tag that commit $candidate"
