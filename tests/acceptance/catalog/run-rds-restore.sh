#!/usr/bin/env bash
# Acceptance on EKS: colvenna-catalog's metadata restored from an RDS snapshot of the project database,
# or to a point in time from its automated backups, with deploy/scripts/catalog/restore-metadata.sh,
# then brought in line with the table files, and a table moved to another path, as an environment
# rebuilt from kept data needs.
#   A. Before the restore point, by CommitStress: t1 (10 commits), t2 (5) and gone (3). The state
#      (CatalogStateDump), snapshot history and listing of the database there are recorded.
#   B. The restore point: a manual snapshot of the project database (mode snapshot), or the time
#      there (mode time: RDS must be able to restore to it before D).
#   C. After it: t1 takes one commit more (WriteProbe), t3 is made (5 commits), gone is dropped.
#   D. restore from the snapshot, or to the time: the catalog lists what it did at the point (gone listed, t3 not), t1
#      and t2 read exactly as there; check finds t1 behind its files, t2 in line, gone's directory
#      missing, and t3's directory new.
#   E. reconcile --unregister-missing: the catalog lists what it did before the restore, every table
#      reads as it did then and is in line with its files, and t1 and t3 take writes.
#   F. As a rebuilt environment takes kept tables back: t2's directory copied under another
#      database's prefix, then reconcile with the copy's start as the restore point creates that
#      database and registers the copy; it reads as t2 did, also once t2 is dropped with its files.
# It restores the whole colvenna_catalog database: like UAT-CAT-11, it needs the environment to itself.
# Writes <evidence>/verdicts.tsv and fails if any check fails. It deletes only the RDS snapshots it
# made, of this environment's project database: the restore point's at once, the restore's
# before-restore snapshot (the way back) only when every check passed.
# KEEP_INSTANCE=1 keeps the restore's temporary instance (restore --keep-instance), for uninstall.sh
# to delete.
# Usage: run-rds-restore.sh <run-id> <evidence-dir> [snapshot|time]
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
# A command that fails inside $(...) fails the assignment that runs it.
shopt -s inherit_errexit
assert_account
ensure_kubeconfig
usage="usage: run-rds-restore.sh <run-id> <evidence-dir> [snapshot|time]"
run_id="${1:?$usage}"
evidence="${2:?$usage}"
mode="${3:-snapshot}"
[[ "$mode" == snapshot || "$mode" == time ]] || { echo "$usage" >&2; exit 1; }
mkdir -p "$evidence"
evidence="$(cd "$evidence" && pwd)"
committed_sha >"$evidence/source-sha.txt"
flows="$REPO_DIR/tests/acceptance/catalog"
tool="$flows/run-catalog-tool.sh"
restore="$REPO_DIR/deploy/scripts/catalog/restore-metadata.sh"
db="flow_${run_id//-/_}_rds" moved="flow_${run_id//-/_}_moved"
point_snapshot="$(tr '_' '-' <<<"$db")-point"
bucket="$(stack_output "$FOUNDATION_STACK" WarehouseBucket)"
endpoint="$(stack_output "$FOUNDATION_STACK" CatalogEndpoint)"
instance="$(aws rds describe-db-instances --output text \
  --query "DBInstances[?Endpoint.Address=='$endpoint'].DBInstanceIdentifier | [0]")"
[[ -n "$instance" && "$instance" != None ]] || { echo "No project database at $endpoint" >&2; exit 1; }
restore_args=(--yes "$COLVENNA_ENV")
[[ "${KEEP_INSTANCE:-}" == 1 ]] && restore_args+=(--keep-instance)
failed=0 finished=false made=""
# shellcheck disable=SC2317  # invoked through the EXIT trap
cleanup() {
  local s of
  admin_close
  # drop <snapshot>: deleted if it is of this environment's project database.
  drop() {
    of="$(aws rds describe-db-snapshots --db-snapshot-identifier "$1" \
      --query 'DBSnapshots[0].DBInstanceIdentifier' --output text 2>/dev/null || true)"
    if [[ "$of" == "$instance" ]] && aws rds delete-db-snapshot --db-snapshot-identifier "$1" >/dev/null 2>&1; then
      echo "deleted RDS snapshot $1" >>"$evidence/timeline.txt"
    else
      echo "RDS snapshot $1 not deleted (of ${of:-nothing})" >>"$evidence/timeline.txt"
    fi
  }
  [[ -z "$made" ]] || drop "$made"
  s="$(cat "$evidence/restore/before-snapshot.txt" 2>/dev/null || true)"
  if [[ -n "$s" ]]; then
    if $finished && ((failed == 0)); then
      drop "$s"
    else
      echo "kept RDS snapshot $s, the way back to before the restore: delete it once done" \
        | tee -a "$evidence/timeline.txt"
    fi
  fi
}
trap cleanup EXIT
log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" | tee -a "$evidence/timeline.txt"; }
verdict() {
  local why="${3:-}"
  why="${why//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${why//$'\t'/ }" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS ]] || failed=1
}
# check <name> <detail> <command...>: a verdict from whether the command succeeds.
check() {
  local name="$1" detail="$2"
  shift 2
  if "$@"; then verdict "$name" PASS "$detail"; else verdict "$name" FAIL "$detail"; fi
}
stress() { # stress <table> <commits>: a new table, one writer committing 100 rows at a time
  FLOW_CLASS=com.colvenna.lake.acceptance.flow.CommitStress FLOW_ARGS="$db $1 1 $2 100 200" \
    bash "$flows/run-flink-flow.sh" "$run_id-$1" "$evidence/stress-$1" >"$evidence/stress-$1.log" 2>&1 \
    || { log "FAIL: the writer of $1 failed; see stress-$1/flow.log"; exit 1; }
}
# record <dir> <database> <table>...: the tables' state (rest.state) and history (rest.history).
record() {
  local dir="$1"
  shift
  if ! bash "$tool" rest "$evidence/$dir" dump "$@" >"$evidence/$dir-dump.log" 2>&1 \
    || ! bash "$tool" rest "$evidence/$dir" history "$@" >"$evidence/$dir-history.log" 2>&1; then
    log "FAIL: reading $* into $dir failed; see $dir-*.log"
    exit 1
  fi
}
tables_of() { # tables_of <database>: the tables the catalog lists in it, sorted
  admin_call GET "/v1/colvenna/databases/$1/tables?maxResults=1000" 2>/dev/null \
    | python3 -c 'import json,sys; [print(t) for t in sorted(json.load(sys.stdin)["tables"] or [])]'
}
# shellcheck disable=SC2317  # invoked through check
same() { diff "$1" "$2" >"$3" 2>&1; }
kind_of() { # kind_of <check.tsv> <database.table>: its kind there, or none
  awk -F'\t' -v n="$2" '$2 == n {print $1; f=1} END {if (!f) print "none"}' "$1"
}
# KeyCount is no use: the CLI pages the listing, and the merged result has none.
# shellcheck disable=SC2317,SC2016  # invoked through check; JMESPath's backticks
directory_gone() { # directory_gone <database> <table>: no schema file left under its conventional path
  [[ "$(aws s3api list-objects-v2 --bucket "$bucket" --prefix "warehouse/$1.db/$2/schema/" \
    --query 'length(Contents || `[]`)' --output text)" == 0 ]]
}

# --- A. before the restore point -------------------------------------------------------------------
log "A: t1, t2 and gone in $db"
stress t1 10
stress t2 5
stress gone 3
record at-point "$db" t1 t2 gone
admin_open
tables_of "$db" >"$evidence/tables-at-point.txt"
admin_close
log "A: at the point: $(tr '\n' ' ' <"$evidence/tables-at-point.txt")"

# --- B. the restore point --------------------------------------------------------------------------
if [[ "$mode" == snapshot ]]; then
  log "B: snapshot $point_snapshot of the project database"
  # Another run's of the same id is not this run's to make, nor to delete.
  if aws rds describe-db-snapshots --db-snapshot-identifier "$point_snapshot" >/dev/null 2>&1; then
    log "FAIL: snapshot $point_snapshot exists already: another run's; use another run id"
    exit 1
  fi
  bash "$restore" snapshot "$point_snapshot" >"$evidence/point-snapshot.txt" 2>&1 \
    || { log "FAIL: no snapshot; see point-snapshot.txt"; exit 1; }
  made="$point_snapshot"
  from=(--snapshot "$point_snapshot")
else
  point="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  sleep 1
  log "B: the restore point is $point"
  from=(--time "$point")
fi

# --- C. after it -----------------------------------------------------------------------------------
log "C: t1 one commit more, t3 made, gone dropped"
bash "$tool" rest "$evidence/write-t1" write "$db" t1 >"$evidence/write-t1.log" 2>&1 \
  || { log "FAIL: the commit to t1 failed"; exit 1; }
stress t3 5
admin_open
admin_call DELETE "/v1/colvenna/databases/$db/tables/gone" >/dev/null
tables_of "$db" >"$evidence/tables-before-restore.txt"
admin_close
check gone-dropped-with-files "gone's directory warehouse/$db.db/gone" directory_gone "$db" gone
record before-restore "$db" t1 t2 t3
log "C: before the restore: $(tr '\n' ' ' <"$evidence/tables-before-restore.txt")"

# --- D. restore ------------------------------------------------------------------------------------
if [[ "$mode" == time ]]; then
  # RDS restores up to its latest restorable time, a few minutes behind: wait until it passes the point.
  for _ in $(seq 1 90); do
    latest="$(aws rds describe-db-instances --db-instance-identifier "$instance" \
      --query 'DBInstances[0].LatestRestorableTime' --output text)"
    (($(date -u -d "$latest" +%s) >= $(date -u -d "$point" +%s))) && break
    sleep 10
  done
  log "D: latest restorable time $latest"
  (($(date -u -d "$latest" +%s) >= $(date -u -d "$point" +%s))) \
    || { log "FAIL: RDS cannot restore to $point yet (latest $latest)"; exit 1; }
fi
log "D: restore ${from[*]}"
begin=$SECONDS
bash "$restore" restore "${from[@]}" "${restore_args[@]}" "$evidence/restore" >"$evidence/restore.log" 2>&1 \
  || { log "FAIL: the restore failed; see restore.log"; exit 1; }
log "D: restored in $((SECONDS - begin)) s, the catalog stopped for $(cat "$evidence/restore/catalog-stopped-seconds.txt") s"
echo "$((SECONDS - begin))" >"$evidence/restore-seconds.txt"
admin_open
tables_of "$db" >"$evidence/tables-after-restore.txt"
admin_close
check lists-as-at-point "$(tr '\n' ' ' <"$evidence/tables-after-restore.txt")" \
  same "$evidence/tables-at-point.txt" "$evidence/tables-after-restore.txt" "$evidence/tables-diff-restore.txt"
record after-restore "$db" t1 t2
grep -vF "\"table\":\"$db.gone\"" "$evidence/at-point/rest.state" >"$evidence/at-point-t1-t2.state"
grep -v "^$db.gone " "$evidence/at-point/rest.history" >"$evidence/at-point-t1-t2.history"
check reads-as-at-point "t1 and t2: state and every snapshot as at the point" \
  same "$evidence/at-point-t1-t2.state" "$evidence/after-restore/rest.state" "$evidence/state-diff-restore.txt"
check history-as-at-point "t1 and t2: $(wc -l <"$evidence/after-restore/rest.history") snapshots" \
  same "$evidence/at-point-t1-t2.history" "$evidence/after-restore/rest.history" "$evidence/history-diff-restore.txt"
bash "$restore" check "$evidence/restore" >"$evidence/check.log" 2>&1 \
  || { log "FAIL: check failed; see check.log"; exit 1; }
cp "$evidence/restore/check.tsv" "$evidence/check-after-restore.tsv"
for expected in "t1 behind" "t2 consistent" "gone missing" "t3 new"; do
  read -r t kind <<<"$expected"
  found="$(kind_of "$evidence/check-after-restore.tsv" "$db.$t")"
  check "check-$t-$kind" "$db.$t: $found" test "$found" == "$kind"
done

# --- E. reconcile ----------------------------------------------------------------------------------
log "E: reconcile"
bash "$restore" reconcile --unregister-missing "$evidence/restore" >"$evidence/reconcile.log" 2>&1 \
  || { log "FAIL: reconcile failed; see reconcile.log"; exit 1; }
cp "$evidence/restore/check.tsv" "$evidence/check-after-reconcile.tsv"
not_in_line="$(awk -F'\t' -v p="$db." 'index($2, p) == 1 && $1 != "consistent"' "$evidence/check-after-reconcile.tsv")"
check all-in-line "${not_in_line:-every table of $db consistent}" test -z "$not_in_line"
admin_open
tables_of "$db" >"$evidence/tables-after-reconcile.txt"
admin_close
check lists-as-before-restore "$(tr '\n' ' ' <"$evidence/tables-after-reconcile.txt")" \
  same "$evidence/tables-before-restore.txt" "$evidence/tables-after-reconcile.txt" "$evidence/tables-diff-reconcile.txt"
record after-reconcile "$db" t1 t2 t3
check reads-as-before-restore "t1, t2 and t3: state as before the restore" \
  same "$evidence/before-restore/rest.state" "$evidence/after-reconcile/rest.state" "$evidence/state-diff-reconcile.txt"
check history-as-before-restore "t1, t2 and t3: $(wc -l <"$evidence/after-reconcile/rest.history") snapshots" \
  same "$evidence/before-restore/rest.history" "$evidence/after-reconcile/rest.history" "$evidence/history-diff-reconcile.txt"
writes="$(bash "$tool" rest "$evidence/write-after" write "$db" t1 t3 >"$evidence/write-after.log" 2>&1 \
  && grep -c COMMITTED "$evidence/write-after/probes.txt" || echo 0)"
check takes-writes "$writes of 2 tables committed" test "$writes" == 2

# --- F. a table moved, as a rebuild moves the warehouse --------------------------------------------
log "F: t2's directory copied to warehouse/$moved.db/t2, then reconcile registers it"
copied="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
sleep 1
aws s3 sync --quiet "s3://$bucket/warehouse/$db.db/t2/" "s3://$bucket/warehouse/$moved.db/t2/"
RESTORE_POINT="$copied" bash "$restore" reconcile "$evidence/rebuild" >"$evidence/rebuild.log" 2>&1 \
  || { log "FAIL: reconcile of the copy failed; see rebuild.log"; exit 1; }
found="$(kind_of "$evidence/rebuild/check.tsv" "$moved.t2")"
check copy-registered "$moved.t2: $found" test "$found" == consistent
admin_open
admin_call DELETE "/v1/colvenna/databases/$db/tables/t2" >/dev/null
admin_close
check original-dropped-with-files "t2's directory warehouse/$db.db/t2" directory_gone "$db" t2
record moved "$moved" t2
# The same table under its new name: its rows as t2's were, its snapshots t2's.
sed "s/\"table\":\"$db.t2\"/\"table\":\"$moved.t2\"/" "$evidence/after-reconcile/rest.state" \
  | grep "\"$moved.t2\"" >"$evidence/t2-as-moved.state"
grep "^$db.t2 " "$evidence/after-reconcile/rest.history" | sed "s/^$db.t2 /$moved.t2 /" \
  >"$evidence/t2-as-moved.history"
check moved-reads-as-t2 "state of $moved.t2" \
  same "$evidence/t2-as-moved.state" "$evidence/moved/rest.state" "$evidence/state-diff-moved.txt"
check moved-history-as-t2 "$(wc -l <"$evidence/moved/rest.history") snapshots" \
  same "$evidence/t2-as-moved.history" "$evidence/moved/rest.history" "$evidence/history-diff-moved.txt"

finished=true
echo "verdicts: $(grep -c PASS "$evidence/verdicts.tsv") PASS, $(grep -c FAIL "$evidence/verdicts.tsv" || true) FAIL"
exit "$failed"
