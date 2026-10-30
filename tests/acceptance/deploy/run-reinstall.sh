#!/usr/bin/env bash
# Acceptance: deploy/install.sh run again on an installed environment, at this commit, judged by
# what it changed: UAT-DEP-03 (a repeat) when the environment was installed at this commit or at
# one whose tree differs from it only under release/ (the same product: a candidate's tag and the
# commit its images were built from), UAT-DEP-06 (an upgrade) when at another, older one. Writes
# PASS/FAIL lines to <evidence>/verdicts.tsv, the kind of run to <evidence>/kind (repeat or upgrade),
# and fails if any check fails.
#   both: install.sh exits 0, every step done and verify passed (check-install.sh's checks), and
#     nothing runs from upstream afterwards (no-upstream-images);
#   repeat: no stack resource made or replaced, the same nodes, the releases converged at the same
#     charts, the same workload images and commits, the same volumes, no image built;
#   upgrade: the same stacks, their data kept (database, buckets, user pool, repositories, secrets,
#     VPC, cluster), every volume kept, the components annotated with this commit and running new
#     images.
# The installed commit is the one colvenna-catalog's pods are annotated with. A commit whose tree differs
# from it only under release/ is the same product (a release tag and the commit its images were
# built from, release/README.md): a repeat, the components' commit annotations moving from the one
# to the other. The install runs with
# --fixtures when the environment has the test sources, and with UAT_INSTALL_ARGS (for example
# "--release <candidate>" for an environment installed from a release manifest).
#
# usage: COLVENNA_ENV=<env> tests/acceptance/deploy/run-reinstall.sh <run-id> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=judge.sh
source "$(dirname "$0")/judge.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-reinstall.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-reinstall.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
mkdir -p "$evidence"
exec > >(tee -a "$evidence/run.log") 2>&1
DEPLOY_VERDICTS="$evidence/verdicts.tsv"
installed="$(catalog_replicas | awk '{print $3}' | sort -u | head -1)"
kind=upgrade same_product=()
if [[ "$installed" == "$sha" ]]; then
  kind=repeat
elif [[ -n "$installed" ]] && ! has_commit "$installed"; then
  echo "the installed commit $installed is not in this clone: judged an upgrade (git fetch to compare)"
elif [[ -n "$installed" ]] && same_product "$installed" "$sha"; then
  kind=repeat same_product=(--same-product "$installed" "$sha")
fi
echo "$kind" >"$evidence/kind"
fixtures=()
if aws cloudformation describe-stacks --stack-name "$TEST_MYSQL_STACK" >/dev/null 2>&1; then fixtures=(--fixtures); fi
read -r -a extra <<<"${UAT_INSTALL_ARGS:-}"
echo "run $run: install.sh ${fixtures[*]} ${extra[*]} at $sha on $COLVENNA_ENV installed at ${installed:-unknown} ($kind${same_product[*]:+, the same product})"
if [[ -z "$installed" ]]; then
  verdict installed-commit FAIL "no colvenna-catalog replica annotated with the commit it was installed at"
fi
inventory() {
  bash "$(dirname "$0")/inventory.sh" "$evidence/$1" >"$evidence/inventory-$1.log" 2>&1 \
    || verdict "inventory-$1" FAIL "$(tail -3 "$evidence/inventory-$1.log" | tr '\n' ' ')"
}
inventory before
status=0
INSTALL_EVIDENCE="$evidence/install" bash "$DEPLOY_DIR/install.sh" "${fixtures[@]}" "${extra[@]}" \
  >"$evidence/install.out" 2>&1 || status=$?
if ((status == 0)); then
  verdict install-exit PASS "install.sh exited 0 after $(awk -F'\t' '{s += $3} END {print s + 0}' "$evidence/install/steps.tsv" 2>/dev/null) s"
else
  verdict install-exit FAIL "install.sh exited $status: $(tail -3 "$evidence/install.out" | tr '\n' ' ' | cut -c1-300)"
fi
inventory after
bash "$DEPLOY_DIR/install.sh" --list >"$evidence/steps-list.txt" || verdict steps-listed FAIL "install.sh --list failed"
platform=()
((${#fixtures[@]} > 0)) || platform=(--platform)
judge steps "$evidence/steps-list.txt" "${platform[@]}" "$evidence/install"
judge images "$evidence/after"
if [[ "$kind" == repeat ]]; then
  judge repeat "$evidence/before" "$evidence/after" "${same_product[@]}"
else
  judge upgrade "$evidence/before" "$evidence/after" "$sha"
fi
[[ -s "$DEPLOY_VERDICTS" ]] || { echo "no verdicts" >&2; exit 1; }
exit "$DEPLOY_FAILED"
