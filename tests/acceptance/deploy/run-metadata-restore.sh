#!/usr/bin/env bash
# Acceptance: the metadata restore (UAT-DEP-09) as one run of the UAT in <evidence-root> (label
# metadata-restore, or --label's): the drill tests/acceptance/catalog/run-rds-restore.sh twice, from
# an RDS snapshot (run id mr<RUN_TAG>, evidence <label>/snapshot/) and then to a point in time from
# the automated backups (mt<RUN_TAG>, <label>/time/), the second also when the first failed. Both
# drills' verdicts go into <evidence-root>/<label>/verdicts.tsv, named snapshot-<check> and
# time-<check>, with snapshot-exit and time-exit for each drill's exit status (a drill that stopped
# early wrote fewer verdicts); a row in runs.tsv. Fails if either drill does.
# It replaces the whole colvenna_catalog database: the operator runs it alone, outside run-uat.sh, with
# nothing else running on the environment. RUN_TAG defaults to the UTC month, day, hour and minute;
# KEEP_INSTANCE=1 goes through to the drills.
#
# usage: COLVENNA_ENV=<env> tests/acceptance/deploy/run-metadata-restore.sh <evidence-root> [--label <label>]
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=judge.sh
source "$(dirname "$0")/judge.sh"
assert_account
usage="usage: run-metadata-restore.sh <evidence-root> [--label <label>]"
root="${1:?$usage}"
shift
label=metadata-restore
while (($# > 0)); do
  case "$1" in
    --label) label="${2:?$usage}"; shift ;;
    *) echo "$usage" >&2; exit 1 ;;
  esac
  shift
done
tag="${RUN_TAG:-$(date -u +%m%d%H%M)}"
[[ "$tag" =~ ^[0-9a-z]{1,8}$ ]] || { echo "RUN_TAG: at most 8 lowercase letters and digits" >&2; exit 1; }
as_run "$root" "$label"
out="$root/$label"
mkdir -p "$out"
DEPLOY_VERDICTS="$out/verdicts.tsv"
for drill in snapshot:mr time:mt; do
  mode="${drill%%:*}" code="${drill#*:}" status=0
  echo "$(date -u +%FT%TZ) $mode drill, run id $code$tag"
  bash "$ACCEPTANCE_DIR/catalog/run-rds-restore.sh" "$code$tag" "$out/$mode" "$mode" || status=$?
  # The drill's verdicts under its mode's name: time, check, outcome, detail.
  if [[ -s "$out/$mode/verdicts.tsv" ]]; then
    awk -F'\t' -v OFS='\t' -v m="$mode" '{$2 = m "-" $2; print}' "$out/$mode/verdicts.tsv" >>"$DEPLOY_VERDICTS"
    if grep -q $'\tFAIL\t' "$out/$mode/verdicts.tsv"; then DEPLOY_FAILED=1; fi
  fi
  if ((status == 0)); then
    verdict "$mode-exit" PASS "the $mode drill exited 0"
  else
    verdict "$mode-exit" FAIL "the $mode drill exited $status; see $label.log and $label/$mode/timeline.txt"
  fi
done
exit "$DEPLOY_FAILED"
