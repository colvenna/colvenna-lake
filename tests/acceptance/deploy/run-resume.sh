#!/usr/bin/env bash
# Acceptance: an install interrupted in the middle of a step, then resumed with --from that step
# (UAT-DEP-04). Writes PASS/FAIL lines to <evidence>/verdicts.tsv and fails if any check fails.
#
# On an installed environment, at the commit it was installed at, deploy/install.sh --from <step>
# (default operator-again: colvenna-operator's Helm upgrade, the cli and verify after it) runs in a
# session of its own. Once the step's `helm upgrade` has made the release pending, the whole session
# gets RESUME_SIGNAL (default HUP, as when the operator's SSH connection drops; INT is Ctrl-C, KILL a
# lost host).
# Checks: the run ended without the step done; then install.sh --from <step> again completes
# every step from it, verify included, and the Helm release is deployed at a newer revision.
# The step's state between the two (the release's status and history) is kept as evidence. The
# interrupted install starts with the signals' default dispositions: one started under nohup (or any
# parent that ignores HUP) would otherwise ignore the interruption it is meant to get. Both runs
# take UAT_INSTALL_ARGS too (for example "--release <candidate>").
#
# usage: COLVENNA_ENV=<env> tests/acceptance/deploy/run-resume.sh <run-id> <evidence-dir> [<step>]
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-resume.sh <run-id> <evidence-dir> [<step>]}"
evidence="${2:?usage: run-resume.sh <run-id> <evidence-dir> [<step>]}"
step="${3:-operator-again}"
signal="${RESUME_SIGNAL:-HUP}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
[[ "$signal" =~ ^(HUP|INT|TERM|KILL)$ ]] || { echo "RESUME_SIGNAL: HUP, INT, TERM or KILL" >&2; exit 1; }
sha="$(committed_sha)"
mkdir -p "$evidence"
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text): $step, interrupted by SIG$signal"
failed=0
verdict() {
  local detail="${3:-}"
  detail="${detail//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${detail//$'\t'/ }" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS ]] || failed=1
}

# The release each step upgrades, for the steps this test knows to interrupt.
case "$step" in
  operator | operator-again) release="$OPERATOR_RELEASE" namespace="$OPERATOR_NAMESPACE" ;;
  catalog) release=colvenna-catalog namespace="$CATALOG_NAMESPACE" ;;
  monitoring) release="$MONITORING_RELEASE" namespace="$MONITORING_NAMESPACE" ;;
  console) release="$CONSOLE_RELEASE" namespace="$CONSOLE_NAMESPACE" ;;
  bi) release="$BI_RELEASE" namespace="$BI_NAMESPACE" ;;
  *) echo "No Helm release known for step $step" >&2; exit 1 ;;
esac
fixtures=()
if aws cloudformation describe-stacks --stack-name "$TEST_MYSQL_STACK" >/dev/null 2>&1; then
  fixtures=(--fixtures)
fi
read -r -a extra <<<"${UAT_INSTALL_ARGS:-}"
fixtures+=("${extra[@]}")
# The steps from the interrupted one on, as --list prints them for this install.
mapfile -t expected < <(bash "$DEPLOY_DIR/install.sh" --list | awk '{print $1}' \
  | sed -n "/^$step\$/,\$p")
revision_before="$(helm -n "$namespace" history "$release" -o json | python3 -c 'import json,sys; print(json.load(sys.stdin)[-1]["revision"])')"

# ---------------------------------------------------------------------------- the interruption
echo "$(date -u +%FT%TZ) install.sh ${fixtures[*]} --from $step, in a session of its own"
# The installs run in sessions of their own; stopping this test stops them too, whichever runs.
sessions=()
# shellcheck disable=SC2317  # invoked through the EXIT trap
stop_installs() {
  local s
  for s in "${sessions[@]}"; do
    kill -KILL -- "-$s" 2>/dev/null || true
  done
}
trap stop_installs EXIT
INSTALL_EVIDENCE="$evidence/interrupted" setsid /usr/bin/env --default-signal=HUP,INT,TERM \
  bash "$DEPLOY_DIR/install.sh" "${fixtures[@]}" --from "$step" \
  >"$evidence/interrupted.out" 2>&1 </dev/null &
session=$!
sessions+=("$session")
seen="" end=$((SECONDS + 1800))
while ((SECONDS < end)) && kill -0 "$session" 2>/dev/null; do
  if pgrep -s "$session" -f "helm upgrade --install $release " >/dev/null; then
    seen=$SECONDS
    break
  fi
  sleep 1
done
if [[ -z "$seen" ]]; then
  kill -KILL -- "-$session" 2>/dev/null || true
  verdict interrupted FAIL "the install never reached $release's helm upgrade; see interrupted.out"
  exit 1
fi
# Interrupted once the upgrade has recorded its pending revision, while its helm still runs: what an
# interrupted Helm leaves behind, and what the resume must recover. An upgrade that ends before the
# release is seen pending cannot test that: a FAIL, to be run again, never a pass.
pending="" end=$((SECONDS + 300))
while ((SECONDS < end)) && pgrep -s "$session" -f "helm upgrade --install $release " >/dev/null; do
  pending="$(helm -n "$namespace" status "$release" -o json 2>/dev/null \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["info"]["status"])' 2>/dev/null || true)"
  [[ "$pending" != pending-* ]] || break
  pending=""
  sleep 0.5
done
if [[ -z "$pending" ]]; then
  wait "$session" || true
  verdict interrupted FAIL "$release's upgrade ended before the release was seen pending: nothing was interrupted; run the case again"
  exit 1
fi
sleep "${RESUME_DELAY:-1}"
pgrep -s "$session" -a >"$evidence/session-at-interruption.txt" || true
echo "$(date -u +%FT%TZ) SIG$signal to the session $session"
kill "-$signal" -- "-$session" 2>/dev/null || true
status=0
wait "$session" || status=$?
sleep 2
pkill -KILL -s "$session" 2>/dev/null || true
helm -n "$namespace" status "$release" -o json >"$evidence/release-after-interruption.json" 2>&1 || true
helm -n "$namespace" history "$release" >"$evidence/release-history-after-interruption.txt" 2>&1 || true
between="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["info"]["status"])' \
  "$evidence/release-after-interruption.json" 2>/dev/null || echo unknown)"
done_steps="$(awk -F'\t' '$2 == "ok" {print $1}' "$evidence/interrupted/steps.tsv" 2>/dev/null | tr '\n' ' ' || true)"
if ((status != 0)) && [[ " $done_steps " != *" $step "* ]]; then
  verdict interrupted PASS "SIG$signal $((SECONDS - seen))s into $release's upgrade: install.sh ended with $status, $step not done; the release is $between"
else
  verdict interrupted FAIL "install.sh ended with $status; steps done: ${done_steps:-none}"
fi

# --------------------------------------------------------------------------------- the resume
echo "$(date -u +%FT%TZ) install.sh ${fixtures[*]} --from $step again"
resumed=0
INSTALL_EVIDENCE="$evidence/resumed" setsid bash "$DEPLOY_DIR/install.sh" "${fixtures[@]}" --from "$step" \
  >"$evidence/resumed.out" 2>&1 </dev/null &
sessions+=("$!")
wait "$!" || resumed=$?
missing=""
for s in "${expected[@]}"; do
  grep -Eq "^$s	(ok|skipped \(no --fixtures\))	" "$evidence/resumed/steps.tsv" 2>/dev/null || missing+="$s "
done
if ((resumed == 0)) && [[ -z "$missing" ]]; then
  verdict resumed PASS "install.sh --from $step: ${#expected[@]} steps done, verify included ($(awk -F'\t' '{s += $3} END {print s}' "$evidence/resumed/steps.tsv") s)"
else
  verdict resumed FAIL "exit $resumed; not done: ${missing:-none}; $(tail -5 "$evidence/resumed.out" | tr '\n' ' ' | cut -c1-300)"
fi
helm -n "$namespace" status "$release" -o json >"$evidence/release-after-resume.json" 2>&1 || true
read -r final revision < <(python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); print(r["info"]["status"], r["version"])' \
  "$evidence/release-after-resume.json" 2>/dev/null || echo "unknown 0")
if [[ "$final" == deployed ]] && ((revision > revision_before)); then
  verdict release-deployed PASS "$release deployed at revision $revision (was $revision_before; $between between the runs)"
else
  verdict release-deployed FAIL "$release $final at revision $revision (was $revision_before)"
fi
echo "verdicts:"
cat "$evidence/verdicts.tsv"
exit "$failed"
