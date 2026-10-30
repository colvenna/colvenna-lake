#!/usr/bin/env bash
# What the deployment cases' scripts share (sourced after tests/acceptance/common.sh): their
# verdicts, deploy_checks.py's checks as verdicts, and recording an operator-run check as one run of
# a UAT. A script sets DEPLOY_VERDICTS, the verdicts.tsv it appends to; DEPLOY_FAILED becomes 1 at
# the first FAIL.
# shellcheck disable=SC2034  # DEPLOY_FAILED is read by the scripts that source this file
DEPLOY_FAILED=0
DEPLOY_CHECKS="$(dirname "${BASH_SOURCE[0]}")/deploy_checks.py"

# verdict <check> PASS|FAIL <detail>: one line of DEPLOY_VERDICTS.
verdict() {
  local detail="${3:-}"
  detail="${detail//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${detail//$'\t'/ }" | tee -a "$DEPLOY_VERDICTS"
  [[ "$2" == PASS ]] || DEPLOY_FAILED=1
}

# judge <deploy_checks.py command> <arguments>...: its checks as verdicts. It must print at least one
# check and exit 0 with none failed, or 1 with one failed at least; anything else (an exception, a
# usage error, no check at all) is a FAIL of its own, judge-<command>, never a silent pass.
judge() {
  local out status=0 name outcome detail checks=0 failures=0 tab=$'\t'
  out="$(python3 "$DEPLOY_CHECKS" "$@" 2>&1)" || status=$?
  while IFS="$tab" read -r name outcome detail; do
    [[ "$name" =~ ^[a-z0-9-]+$ && ( "$outcome" == PASS || "$outcome" == FAIL ) ]] || continue
    checks=$((checks + 1))
    [[ "$outcome" == PASS ]] || failures=$((failures + 1))
    verdict "$name" "$outcome" "$detail"
  done <<<"$out"
  if ((checks == 0 || status > 1 || (status == 1) != (failures > 0))); then
    verdict "judge-$1" FAIL "deploy_checks.py $1 exited $status with $checks checks, $failures failed: $(tail -3 <<<"$out" | tr '\n' ' ' | cut -c1-300)"
  fi
}

# as_run <evidence-root> <label>: from here on, this script is one run of the UAT in <evidence-root>,
# recorded as rerun-uat.sh records one: its output also in <label>.log, a start and an end line in
# uat.log, and at exit a row in runs.tsv (label, exit status, start, seconds), so summary.tsv and the
# report see the case. A label used once already is refused: give the run another (install-2, ...).
as_run() {
  AS_RUN_ROOT="$1" AS_RUN_LABEL="$2" AS_RUN_BEGAN=$SECONDS AS_RUN_START="$(date -u +%FT%TZ)"
  if [[ -e "$AS_RUN_ROOT/$AS_RUN_LABEL.log" ]]; then
    echo "$AS_RUN_ROOT/$AS_RUN_LABEL.log exists: give the run a label of its own (--label)" >&2
    exit 1
  fi
  mkdir -p "$AS_RUN_ROOT"
  echo "$AS_RUN_START start $AS_RUN_LABEL: $0 (by the operator, at $(git -C "$REPO_DIR" rev-parse HEAD))" >>"$AS_RUN_ROOT/uat.log"
  exec > >(tee -a "$AS_RUN_ROOT/$AS_RUN_LABEL.log") 2>&1
  trap 'as_run_end $?' EXIT
}
# shellcheck disable=SC2317  # invoked through the EXIT trap
as_run_end() {
  printf '%s\t%s\t%s\t%s\n' "$AS_RUN_LABEL" "$1" "$AS_RUN_START" "$((SECONDS - AS_RUN_BEGAN))" >>"$AS_RUN_ROOT/runs.tsv"
  echo "$(date -u +%FT%TZ) end $AS_RUN_LABEL: exit $1 after $((SECONDS - AS_RUN_BEGAN))s" >>"$AS_RUN_ROOT/uat.log"
}
