#!/usr/bin/env bash
# Acceptance: judges an uninstall that already ran (UAT-DEP-07), as one run of the UAT in
# <evidence-root> (label uninstall, or --label's): verdicts in <evidence-root>/<label>/verdicts.tsv,
# a row in runs.tsv. Fails if any check fails.
#   uninstall.sh ended saying nothing of the environment is left, its leftovers.txt empty and its
#   settings record (deploy/env.sh: region, account, hosted zone, entry domain) gone; then, against an account inventory taken before the uninstall
#   (inventory.sh <dir> --account): nothing of the environment in the account, and every other
#   environment's stacks, roles, repositories (the release registry's included), DNS names,
#   buckets, databases and snapshots, user pools, secrets, log groups, OIDC providers and volumes as
#   they were.
#
# usage: COLVENNA_ENV=<env> tests/acceptance/deploy/check-uninstall.sh <evidence-root> <account-inventory-before> <uninstall-evidence> [--label <label>]
# shellcheck source-path=SCRIPTDIR
usage="usage: check-uninstall.sh <evidence-root> <account-inventory-before> <uninstall-evidence> [--label <label>]"
root="${1:?$usage}" before="${2:?$usage}" uninstalled="${3:?$usage}"
label=uninstall
case "$#" in
  3) ;;
  5) [[ "$4" == --label ]] || { echo "$usage" >&2; exit 1; }; label="$5" ;;
  *) echo "$usage" >&2; exit 1 ;;
esac
[[ -s "$before/meta.json" ]] || { echo "No $before/meta.json: not an inventory (inventory.sh <dir> --account)" >&2; exit 1; }
# The environment's settings went with it: those the inventory before the uninstall recorded
# (meta.json's settings, by variable; its region alone, from an inventory made before), unless given.
while IFS='=' read -r name value; do
  [[ -n "${!name:-}" || -z "$value" ]] || export "$name=$value"
done < <(python3 -c 'import json, sys
meta = json.load(open(sys.argv[1]))
for name, value in (meta.get("settings") or {"COLVENNA_REGION": meta.get("region", "")}).items():
    print(f"{name}={value}")' "$before/meta.json")
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=judge.sh
source "$(dirname "$0")/judge.sh"
assert_account
[[ -s "$before/account-regions.txt" ]] \
  || { echo "$before is an inventory of the environment only, not of the account (inventory.sh <dir> --account)" >&2; exit 1; }
[[ -d "$uninstalled" ]] || { echo "No uninstall evidence $uninstalled" >&2; exit 1; }
as_run "$root" "$label"
out="$root/$label"
mkdir -p "$out"
DEPLOY_VERDICTS="$out/verdicts.tsv"
last="$(tail -1 "$uninstalled/uninstall.log" 2>/dev/null || true)"
if [[ "$last" == *"nothing of $COLVENNA_ENV is left in the account"* && -e "$uninstalled/leftovers.txt" \
  && ! -s "$uninstalled/leftovers.txt" ]]; then
  verdict uninstall-finished PASS "$last"
else
  verdict uninstall-finished FAIL "last line: ${last:-none}; leftovers: $(head -5 "$uninstalled/leftovers.txt" 2>/dev/null | tr '\n' ' ')"
fi
recorded=()
for setting in "${COLVENNA_SETTINGS[@]}"; do
  [[ ! -e "$COLVENNA_STATE_DIR/${setting#*:}" ]] || recorded+=("${setting#*:}")
done
if ((${#recorded[@]} == 0)); then
  verdict settings-record-gone PASS "no region, account, hosted zone or entry domain recorded in $COLVENNA_STATE_DIR: the name may be used again"
else
  verdict settings-record-gone FAIL "$COLVENNA_STATE_DIR still records ${recorded[*]}"
fi
mapfile -t regions <"$before/account-regions.txt"
# The cluster and issuer the environment had, so that leftovers of them are still found.
INVENTORY_CLUSTER="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("cluster") or "")' "$before/meta.json")"
INVENTORY_ISSUER="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("issuer") or "")' "$before/meta.json")"
export INVENTORY_CLUSTER INVENTORY_ISSUER
if bash "$(dirname "$0")/inventory.sh" "$out/after" --account "${regions[@]}" >"$out/inventory.log" 2>&1; then
  judge others "$before" "$out/after" "$COLVENNA_ENV"
else
  verdict inventory-after FAIL "$(tail -3 "$out/inventory.log" | tr '\n' ' ')"
fi
exit "$DEPLOY_FAILED"
