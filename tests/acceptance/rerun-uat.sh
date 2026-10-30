#!/usr/bin/env bash
# Runs one UAT test again, after a fix, beside its earlier runs (docs/uat/cases.md 3.2): its output
# in <label>.log and its row in runs.tsv, as run-uat.sh records them, against the images of the
# commit the prep phase ran at. The label takes a suffix (catalog-doris-2, …) and the test a new
# run id, so neither the earlier run's log nor its evidence directory is reused; the summary phase
# then lists both.
#
# usage: COLVENNA_ENV=<env> tests/acceptance/rerun-uat.sh <evidence-root> <label> <command>...
#   e.g. tests/acceptance/rerun-uat.sh <root> cdc-2 bash tests/acceptance/cdc/run-cdc.sh cdcb1020 <root>/cdc-2
set -euo pipefail
root="${1:?usage: rerun-uat.sh <evidence-root> <label> <command>...}"
label="${2:?usage: rerun-uat.sh <evidence-root> <label> <command>...}"
shift 2
(($# > 0)) || { echo "usage: rerun-uat.sh <evidence-root> <label> <command>..." >&2; exit 1; }
[[ -s "$root/installed-sha" ]] || { echo "No $root/installed-sha: run the prep phase first" >&2; exit 1; }
[[ ! -e "$root/$label.log" ]] || { echo "$root/$label.log exists: give the rerun a label of its own" >&2; exit 1; }
IMAGES_SHA="$(cat "$root/installed-sha")"
export IMAGES_SHA
log() { echo "$(date -u +%FT%TZ) $*" >>"$root/uat.log"; }
log "tests now at $(git -C "$(dirname "$0")" rev-parse HEAD), images of $IMAGES_SHA"
begin=$SECONDS status=0
log "start $label: $*"
("$@") >"$root/$label.log" 2>&1 || status=$?
printf '%s\t%s\t%s\t%s\n' "$label" "$status" "$(date -u -d "@$(($(date +%s) - (SECONDS - begin)))" +%FT%TZ)" \
  "$((SECONDS - begin))" >>"$root/runs.tsv"
log "end $label: exit $status after $((SECONDS - begin))s"
exit "$status"
