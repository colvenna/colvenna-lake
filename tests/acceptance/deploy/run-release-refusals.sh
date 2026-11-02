#!/usr/bin/env bash
# Checks that deploy/install.sh --release refuses, before it changes anything, a release candidate it
# cannot install exactly as the candidate's manifest pins it:
#   no-registry         no COLVENNA_RELEASE_REGISTRY: the manifest names no registry to copy from
#   no-manifest         a candidate this checkout has no manifest of
#   other-candidate     a manifest that is another candidate's
#   incomplete          a manifest without one of its images (images.bi)
#   unknown-digest      an image whose digest the release registry does not hold under its tag
#   swapped-digests     two images' digests swapped: each one is in the registry, but not where pinned
#   doris-not-pinned    a query engine (Doris) digest other than the one lib/common.sh pins
#                       (install-doris.sh runs it)
#   third-party-not-pinned  a third-party image's digest other than deploy/images/third-party.tsv's
#   cli-binaries        a CLI binary's SHA-256 other than the one in the release's colvenna-cli image
#   tree-differs        a tree that differs from the commit the images were built from outside release/
#   uncommitted         a change not committed
#   other-mode          an environment installed from source at the base commit (its install-mode record)
# and, as the control, that check-release.sh passes the candidate as it is (with --fixtures).
# Each case runs install.sh in a scratch worktree of the base commit (this one, or REFUSAL_BASE),
# changed in that one way and committed there, for an environment that does not exist (named from
# the run id), with --only verify: were a refusal to fail, the install would record the region in
# the scratch worktree and run the read-only verify, which fails. Before and after, the account must
# hold no stack of that environment, and the scratch worktree no region record. Verdicts go to
# <evidence>/verdicts.tsv.
# Run it from a checkout of the candidate's tag, with COLVENNA_RELEASE_REGISTRY naming the release registry
# that holds it; it changes nothing in the account. From a later commit that changed only the tests (a
# test fixed after the tag, #20), REFUSAL_BASE=<the tag> makes the scratch worktree the candidate's tree
# all the same: the cases always run the candidate's install.sh. It uses the
# caller's AWS credentials, as install.sh does (their AWS_PROFILE, or the default chain), and gives the
# scratch environment the account and hosted zone (deploy/env.sh) that COLVENNA_ACCOUNT,
# COLVENNA_HOSTED_ZONE_ID and COLVENNA_HOSTED_ZONE_NAME name, or else those COLVENNA_ENV's local state records
# (the environment the UAT runs on), so its install reaches the release checks after them.
# usage: tests/acceptance/deploy/run-release-refusals.sh <run-id> <evidence> [<candidate>]
#   <candidate>      by default the one UAT_INSTALL_ARGS names after --release, when it names one
#                    (the install arguments of run-uat.sh's phases)
#   REFUSAL_REGION   the region the scratch environment would be in; by default COLVENNA_REGION, or
#                    the AWS CLI's configured region
set -euo pipefail
usage="usage: run-release-refusals.sh <run-id> <evidence> [<candidate>]"
run_id="${1:?$usage}"
evidence="${2:?$usage}"
candidate="${3:-}"
if [[ -z "$candidate" ]]; then
  read -r -a install_args <<<"${UAT_INSTALL_ARGS:-}"
  for ((i = 0; i + 1 < ${#install_args[@]}; i++)); do
    [[ "${install_args[$i]}" == --release ]] && candidate="${install_args[$((i + 1))]}"
  done
fi
if [[ -z "$candidate" ]]; then
  echo "No candidate: give one, or UAT_INSTALL_ARGS=\"--release <candidate>\" ($usage)" >&2
  exit 1
fi
repo="$(cd "$(dirname "$0")/../../.." && pwd)"
# COLVENNA_ENV's settings, as deploy/env.sh reads them, for those not given.
if [[ -n "${COLVENNA_ENV:-}" && ( -z "${COLVENNA_ACCOUNT:-}" || -z "${COLVENNA_HOSTED_ZONE_ID:-}" || -z "${COLVENNA_HOSTED_ZONE_NAME:-}" ) ]]; then
  recorded="$(bash -c 'source "$1/deploy/env.sh" && for s in "${COLVENNA_SETTINGS[@]}"; do
    n="${s%%:*}"; printf "%s=%q\n" "$n" "${!n}"; done' _ "$repo")" || exit 1
  while IFS='=' read -r name value; do
    case "$name" in
      COLVENNA_ACCOUNT | COLVENNA_HOSTED_ZONE_ID | COLVENNA_HOSTED_ZONE_NAME) [[ -n "${!name:-}" ]] || eval "$name=$value" ;;
    esac
  done <<<"$recorded"
fi
for name in COLVENNA_ACCOUNT COLVENNA_HOSTED_ZONE_ID COLVENNA_HOSTED_ZONE_NAME COLVENNA_RELEASE_REGISTRY; do
  if [[ -z "${!name:-}" ]]; then
    echo "No $name for the scratch environment: give it, or COLVENNA_ENV of an environment that recorded it ($usage)" >&2
    exit 1
  fi
done
region="${REFUSAL_REGION:-${COLVENNA_REGION:-$(aws configure get region 2>/dev/null || true)}}"
if [[ ! "$region" =~ ^[a-z]{2}-[a-z]+-[1-9]$ || "$region" == cn-* ]]; then
  echo "No standard AWS region for the scratch environment: set REFUSAL_REGION ($usage)" >&2
  exit 1
fi
mkdir -p "$evidence"
evidence="$(cd "$evidence" && pwd)"
failed=0
verdict() {
  local detail="${3:-}"
  detail="${detail//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${detail//$'\t'/ }" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS ]] || failed=1
}
# An environment name no one uses: r and 7 hex digits of the run id.
env="r$(printf '%s' "$run_id" | sha256sum | cut -c1-7)"
stack="ColvennaLake${env^}Foundation"
# absent: whether the account lacks the stack; any other answer than "does not exist" stops the test.
absent() {
  local out
  out="$(aws cloudformation describe-stacks --region "$region" --stack-name "$stack" 2>&1)" && return 1
  [[ "$out" == *"does not exist"* ]] && return 0
  echo "Cannot read $stack in $region: $out" >&2
  exit 1
}
if ! absent; then
  echo "$stack exists in $region: choose another run id" >&2
  exit 1
fi
manifest="release/$candidate/manifest.json"
if [[ -n "${REFUSAL_BASE:-}" ]]; then
  base="$(git -C "$repo" rev-parse --verify -q "$REFUSAL_BASE^{commit}")" \
    || { echo "REFUSAL_BASE=$REFUSAL_BASE is no commit of this clone ($usage)" >&2; exit 1; }
  # The tests changed since, the product not: this checkout's deploy/ is the candidate's.
  changed="$(git -C "$repo" diff --name-only "$base" HEAD -- deploy)"
  if [[ -n "$changed" ]]; then
    echo "REFUSAL_BASE=$REFUSAL_BASE: this checkout's deploy/ differs from it: ${changed//$'\n'/ }" >&2
    exit 1
  fi
else
  base="$(git -C "$repo" rev-parse HEAD)"
fi
if ! git -C "$repo" cat-file -e "$base:$manifest" 2>/dev/null; then
  echo "No $manifest at ${REFUSAL_BASE:-this checkout}: run this from a checkout of the candidate's tag," \
    "or give REFUSAL_BASE=<the tag>" >&2
  exit 1
fi
scratch="$(mktemp -d)/tree"
git -C "$repo" worktree add --quiet --detach "$scratch" "$base"
trap 'git -C "$repo" worktree remove --force "$scratch"' EXIT
echo "environment $env in $region, scratch worktree $scratch of $base, candidate $candidate, tests at $(git -C "$repo" rev-parse HEAD)" \
  | tee "$evidence/setup.txt"

# edit <python>: changes the scratch manifest m (a dict) with that statement, and commits it.
# pin(key, digest) re-pins an image.
edit() {
  python3 - "$scratch/$manifest" "$1" <<'PY'
import json, sys
path, statement = sys.argv[1:]
m = json.load(open(path))
def pin(key, digest):
    m["images"][key]["digest"] = digest
exec(statement)
with open(path, "w") as f:
    json.dump(m, f, indent=2)
    f.write("\n")
PY
  commit "change the manifest"
}
# commit <message>: commits the scratch worktree as it is. It shares the repository's hooks, and a
# clone with the pre-commit hooks installed (CONTRIBUTING.md) would hold these deliberately broken
# trees to them: the commits skip them (#18), the check being install.sh's refusal, not the hooks'.
# Nor are they signed, whatever the caller's git configuration asks.
commit() {
  git -C "$scratch" -c user.name=release-refusals -c user.email=release-refusals@colvenna-lake.invalid \
    -c commit.gpgsign=false commit --quiet --no-verify -am "$1"
}
# refused <case> <expected text> <install.sh arguments...>: runs the install, which must fail with
# that text before recording anything; then the scratch worktree goes back to the base commit.
# Without, the settings it unsets (-u <name>...).
without=()
refused() {
  local case="$1" expected="$2" status=0 out
  shift 2
  out="$(/usr/bin/env -u COLVENNA_ENTRY_DOMAIN "${without[@]}" COLVENNA_ENV="$env" COLVENNA_REGION="$region" COLVENNA_ACCOUNT="$COLVENNA_ACCOUNT" \
    COLVENNA_HOSTED_ZONE_ID="$COLVENNA_HOSTED_ZONE_ID" COLVENNA_HOSTED_ZONE_NAME="$COLVENNA_HOSTED_ZONE_NAME" \
    bash "$scratch/deploy/install.sh" "$@" --only verify 2>&1)" || status=$?
  printf '%s\n' "$out" >"$evidence/$case.log"
  if [[ "$status" == 0 ]]; then
    verdict "$case" FAIL "install.sh exited 0"
  elif [[ "$out" != *"$expected"* ]]; then
    verdict "$case" FAIL "exit $status without \"$expected\": $(tail -3 <<<"$out")"
  elif [[ -e "$scratch/deploy/.state/$env/region" ]]; then
    verdict "$case" FAIL "refused, but after recording the region"
  else
    verdict "$case" PASS "exit $status: $(grep -F -m1 "$expected" <<<"$out")"
  fi
  git -C "$scratch" reset --quiet --hard "$base"
  git -C "$scratch" clean --quiet -fdx
}
# digest <key>: a product image's digest in the candidate's manifest (schema 3: the catalog's release is
# not one of them, #20).
digest() { git -C "$repo" show "$base:$manifest" | python3 -c 'import json, sys; print(json.load(sys.stdin)["images"][sys.argv[1]]["digest"])' "$1"; }

if out="$(bash "$scratch/deploy/scripts/release/check-release.sh" "$candidate" --fixtures 2>&1)"; then
  verdict control PASS "$out"
else
  verdict control FAIL "$out"
fi
without=(-u COLVENNA_RELEASE_REGISTRY)
refused no-registry "COLVENNA_RELEASE_REGISTRY=<account>.dkr.ecr.<region>.amazonaws.com" --release "$candidate"
without=()
refused no-manifest "No release/v0.0.0-dev.999/manifest.json" --release v0.0.0-dev.999
mkdir -p "$scratch/release/v0.0.0-dev.998"
cp "$scratch/$manifest" "$scratch/release/v0.0.0-dev.998/manifest.json"
git -C "$scratch" add release/v0.0.0-dev.998
commit "another candidate's name"
refused other-candidate "not v0.0.0-dev.998's" --release v0.0.0-dev.998
edit 'del m["images"]["bi"]'
refused incomplete "images.bi: missing" --release "$candidate"
edit 'pin("operator", "sha256:" + "0" * 64)'
refused unknown-digest "colvenna-lake-release/colvenna-operator:$candidate is $(digest operator) (failure: None), not sha256:$(printf '0%.0s' {1..64})" \
  --release "$candidate"
edit 'operator, console = m["images"]["operator"]["digest"], m["images"]["console"]["digest"]; pin("operator", console); pin("console", operator)'
refused swapped-digests "colvenna-lake-release/colvenna-operator:$candidate is $(digest operator) (failure: None), not $(digest console)" \
  --release "$candidate"
edit 'pin("query-engine-fe", m["images"]["query-engine-be"]["digest"])'
refused doris-not-pinned "lib/common.sh (what install-doris.sh runs)" --release "$candidate"
edit 'm["third_party"]["postgres"]["digest"] = "sha256:" + "0" * 64'
refused third-party-not-pinned "third_party.postgres.digest: not" --release "$candidate"
edit 'm["cli"]["binaries"]["colvenna-linux-amd64"] = "0" * 64'
refused cli-binaries "colvenna-linux-amd64 in colvenna-lake-release/colvenna-cli@" --release "$candidate"
echo "# a change outside release/" >>"$scratch/deploy/env.sh"
commit "a change outside release/"
refused tree-differs "outside release/" --release "$candidate"
echo "# not committed" >>"$scratch/deploy/env.sh"
refused uncommitted "Commit or remove local changes first" --release "$candidate"
# Local state only (the scratch worktree's, ignored by git): as if installed from source at the base commit.
mkdir -p "$scratch/deploy/.state/$env"
printf '%s source\n' "$base" >"$scratch/deploy/.state/$env/install-mode"
refused other-mode "was installed at $base as source, not release $candidate" --release "$candidate"

if absent; then
  verdict nothing-made PASS "no $stack in $region"
else
  verdict nothing-made FAIL "$stack exists in $region"
fi
cat "$evidence/verdicts.tsv"
exit "$failed"
