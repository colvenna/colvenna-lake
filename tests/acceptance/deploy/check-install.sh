#!/usr/bin/env bash
# Acceptance: judges an install that already ran (UAT-DEP-01 from nothing, UAT-DEP-02 the platform
# only) from its evidence and from what the environment is made of now, as one run of the UAT in
# <evidence-root> (label install, install-platform with --platform, or --label's): verdicts in
# <evidence-root>/<label>/verdicts.tsv, a row in runs.tsv. Fails if any check fails.
#   every step of this commit's `install.sh --list` done (the fixtures' skipped with --platform),
#   verify passed (health, the 14 access checks, both entry hosts sending the unsigned to sign-in),
#   the install finished; nothing runs from upstream (every container's image the environment's
#   own, its copies of the third-party ones too, but AWS's own add-ons and agents); with --fresh
#   every stack created after the install began; with --platform nothing of the fixtures in the
#   environment.
# Several evidence directories are one install interrupted and resumed with --from.
#
# usage: COLVENNA_ENV=<env> tests/acceptance/deploy/check-install.sh <evidence-root> [--label <label>] [--fresh] [--platform] <install-evidence>...
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=judge.sh
source "$(dirname "$0")/judge.sh"
assert_account
usage="usage: check-install.sh <evidence-root> [--label <label>] [--fresh] [--platform] <install-evidence>..."
root="${1:?$usage}"
shift
fresh=false platform=() label=""
while [[ "${1:-}" == --* ]]; do
  case "$1" in
    --fresh) fresh=true ;;
    --platform) platform=(--platform) ;;
    --label) label="${2:?$usage}"; shift ;;
    *) echo "$usage" >&2; exit 1 ;;
  esac
  shift
done
(($# > 0)) || { echo "$usage" >&2; exit 1; }
if [[ -z "$label" ]]; then
  label=install
  ((${#platform[@]} == 0)) || label=install-platform
fi
for evidence in "$@"; do
  [[ -d "$evidence" ]] || { echo "No install evidence $evidence" >&2; exit 1; }
done
as_run "$root" "$label"
out="$root/$label"
mkdir -p "$out"
DEPLOY_VERDICTS="$out/verdicts.tsv"
echo "install of $COLVENNA_ENV in $COLVENNA_REGION judged at $(git -C "$REPO_DIR" rev-parse HEAD): $*"
if ! bash "$DEPLOY_DIR/install.sh" --list >"$out/steps-list.txt"; then
  verdict steps-listed FAIL "install.sh --list failed"
fi
if ! bash "$(dirname "$0")/inventory.sh" "$out/inventory" >"$out/inventory.log" 2>&1; then
  verdict inventory FAIL "$(tail -3 "$out/inventory.log" | tr '\n' ' ')"
fi
judge steps "$out/steps-list.txt" "${platform[@]}" "$@"
judge images "$out/inventory"
if $fresh; then judge fresh "$out/inventory" "$@"; fi
if ((${#platform[@]} > 0)); then judge platform "$out/inventory"; fi
exit "$DEPLOY_FAILED"
