#!/usr/bin/env bash
# Acceptance on EKS: concurrent writers (CommitStress) and maintenance (MaintenanceLoop)
# commit to one table through colvenna-catalog while this script injects faults:
#   1. one catalog replica killed, then the other (instance switch mid-commit);
#   2. every catalog connection to PostgreSQL terminated, repeatedly, for 30 seconds;
#   3. the metadata database refusing every connection for 40 seconds (ALLOW_CONNECTIONS false, open
#      connections terminated), and the time the catalog takes to serve again once it reopens;
#   4. both replicas killed at once.
# Every fault must find the writers still committing, and must be seen to take effect. Afterwards:
#   - the writers' own checks (every acknowledged commit once, every outcome known, whole
#     snapshots only, contiguous snapshot ids) must pass, with some commits retried;
#   - the catalog must have refused stale-base commits that the writers then retried;
#   - the published commit intents must number the snapshots 1..N without a gap, N the pointer;
#   - every maintenance round after the faults must succeed;
#   - the table's consistency report must be clean, as it was before the faults.
# Every fault is timestamped in <evidence>/faults.txt.
# Usage: run-commit-faults.sh <run-id> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run_id="${1:?usage: run-commit-faults.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-commit-faults.sh <run-id> <evidence-dir>}"
mkdir -p "$evidence"
committed_sha >"$evidence/source-sha.txt"
flows="$REPO_DIR/tests/acceptance/catalog"
db="flow_${run_id//-/_}_commits"
table=events
work="$(mktemp -d)"
chmod 700 "$work"
stress=""
maintenance=""
# shellcheck disable=SC2317  # invoked through the EXIT trap
cleanup() {
  # Never leave the metadata database closed.
  psql_pod colvenna-catalog-fault-reopen \
    "psql -d postgres -At -c 'ALTER DATABASE colvenna_catalog WITH ALLOW_CONNECTIONS true'" \
    >/dev/null 2>&1 || true
  # Writers left behind by an early exit would keep committing.
  for pid in $stress $maintenance; do
    kill "$pid" >/dev/null 2>&1 || true
  done
  for label in stress maint; do
    kubectl -n "$ENGINE_NAMESPACE" delete job "flink-flow-$run_id-$label" --ignore-not-found \
      --wait=false >/dev/null 2>&1 || true
  done
  admin_close
  rm -rf "$work"
}
trap cleanup EXIT
log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" | tee -a "$evidence/faults.txt"; }
fail() { log "FAIL: $*"; exit 1; }
replicas() {
  kubectl -n "$CATALOG_NAMESPACE" get pods -l app.kubernetes.io/name=colvenna-catalog \
    -o jsonpath='{.items[*].metadata.name}'
}
ready_replicas() {
  kubectl -n "$CATALOG_NAMESPACE" get deploy colvenna-catalog -o jsonpath='{.status.readyReplicas}'
}
await_ready() {
  kubectl -n "$CATALOG_NAMESPACE" rollout status deploy/colvenna-catalog --timeout=5m >/dev/null
  log "catalog ready: $(replicas)"
}
writers_running() { # writers_running <fault>: the fault must find the writers committing
  kill -0 "$stress" 2>/dev/null || fail "the writers finished before fault $1"
}
consistency() { # consistency <out>: the table's report, saved; prints consistent=true|false
  admin_call GET "/colvenna/admin/v1/consistency/$db/$table" | python3 -m json.tool >"$1"
  python3 -c 'import json,sys; print(str(json.load(open(sys.argv[1]))["consistent"]).lower())' "$1"
}

# psql_pod <name> <script>: runs a shell script with psql as the catalog's own database role.
psql_pod() {
  cat >"$work/$1.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata:
  name: $1
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext:
    runAsNonRoot: true
    runAsUser: 70
    seccompProfile: {type: RuntimeDefault}
  containers:
    - name: psql
      image: $POSTGRES_IMAGE
      securityContext:
        allowPrivilegeEscalation: false
        capabilities: {drop: ["ALL"]}
      env:
        - {name: PGHOST, value: "$(stack_output "$FOUNDATION_STACK" CatalogEndpoint)"}
        - {name: PGDATABASE, value: colvenna_catalog}
        - {name: PGSSLMODE, value: verify-full}
        - {name: PGSSLROOTCERT, value: /ca/ca.pem}
        - name: PGUSER
          valueFrom: {secretKeyRef: {name: colvenna-catalog-db, key: username}}
        - name: PGPASSWORD
          valueFrom: {secretKeyRef: {name: colvenna-catalog-db, key: password}}
      command: ["sh", "-c"]
      args:
        - |
$(printf '%s\n' "$2" | sed 's/^/          /')
      volumeMounts:
        - {name: ca, mountPath: /ca}
  volumes:
    - name: ca
      configMap: {name: colvenna-catalog-db-ca}
YAML
  run_pod "$CATALOG_NAMESPACE" "$1" "$work/$1.yaml"
}
# The catalog's other sessions; a role may end its own sessions, so no master credential is needed.
terminate_sql="SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE usename = current_user AND pid <> pg_backend_pid()"

start="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "start database=$db table=$table"
FLOW_CLASS=com.colvenna.lake.acceptance.flow.CommitStress FLOW_ARGS="$db $table 6 120 500 3000" FLOW_DEADLINE=3600 \
  bash "$flows/run-flink-flow.sh" "$run_id-stress" "$evidence/stress" >"$evidence/stress-run.log" 2>&1 &
stress=$!
# The second flow builds the same jar: start it only once the first has handed its jar over.
until kubectl -n "$ENGINE_NAMESPACE" get job "flink-flow-$run_id-stress" >/dev/null 2>&1; do
  kill -0 "$stress" 2>/dev/null || fail "the writers did not start; see stress-run.log"
  sleep 5
done
FLOW_CLASS=com.colvenna.lake.acceptance.flow.MaintenanceLoop FLOW_ARGS="$db $table 25 20" FLOW_DEADLINE=3600 \
  bash "$flows/run-flink-flow.sh" "$run_id-maint" "$evidence/maintenance" \
  >"$evidence/maintenance-run.log" 2>&1 &
maintenance=$!
# Faults wait for the maintenance loop's Job too: its launcher's own catalog calls must not meet them.
until kubectl -n "$ENGINE_NAMESPACE" get job "flink-flow-$run_id-maint" >/dev/null 2>&1; do
  kill -0 "$maintenance" 2>/dev/null || fail "the maintenance loop did not start; see maintenance-run.log"
  sleep 5
done

# Faults begin once the writers are committing: at least five published snapshots.
admin_open
for _ in $(seq 1 60); do
  sleep 10
  (($(published_snapshot "$db" "$table") >= 5)) && break
done
# The report is for a quiet table; between two commits of the running writers is quiet enough.
baseline=false
for _ in $(seq 1 15); do
  [[ "$(consistency "$evidence/consistency-before.json")" == true ]] && { baseline=true; break; }
  sleep 2
done
[[ "$baseline" == true ]] || fail "the table was not consistent before the faults"
log "baseline: table consistent with $(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["branches"][0]["pointerSnapshot"])' "$evidence/consistency-before.json") snapshots published"
admin_close

read -r -a pods <<<"$(replicas)"
writers_running 1
log "fault 1: kill replica ${pods[0]}"
kubectl -n "$CATALOG_NAMESPACE" delete pod "${pods[0]}" --grace-period=0 --force >/dev/null 2>&1
sleep 30
log "fault 1: kill replica ${pods[1]}"
kubectl -n "$CATALOG_NAMESPACE" delete pod "${pods[1]}" --grace-period=0 --force >/dev/null 2>&1
await_ready

sleep 15
writers_running 2
log "fault 2: terminate the catalog's PostgreSQL sessions every 3s for 30s"
psql_pod colvenna-catalog-fault-terminate \
  "for i in \$(seq 1 10); do psql -At -c \"$terminate_sql\"; sleep 3; done" \
  >"$evidence/fault2-sessions-terminated.txt"
terminated="$(awk '{s += $1} END {print s + 0}' "$evidence/fault2-sessions-terminated.txt")"
log "fault 2: done, $terminated sessions terminated"
((terminated > 0)) || fail "fault 2 terminated no session"

sleep 15
writers_running 3
log "fault 3: the metadata database refuses every connection for 40s"
# The database owner closes it to connections from the postgres database, then ends the open ones.
# (A NetworkPolicy would do the cutting too, but on this cluster the CNI broke the pods' name
# resolution for many minutes after such a policy was removed, which measures the CNI.)
psql_pod colvenna-catalog-fault-close \
  "psql -d postgres -At -c 'ALTER DATABASE colvenna_catalog WITH ALLOW_CONNECTIONS false' && psql -d postgres -At -c \"SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE datname = 'colvenna_catalog' AND usename = current_user\"" \
  >"$evidence/fault3-sessions-terminated.txt"
lowest=2
for _ in $(seq 1 8); do
  sleep 5
  now="$(ready_replicas)"
  ((${now:-0} < lowest)) && lowest="${now:-0}"
done
psql_pod colvenna-catalog-fault-open "psql -d postgres -At -c 'ALTER DATABASE colvenna_catalog WITH ALLOW_CONNECTIONS true'" \
  >/dev/null
reopened=$SECONDS
log "fault 3: the database accepts connections again; fewest ready replicas while closed: $lowest"
((lowest == 0)) || fail "fault 3: the catalog never lost its database"
await_ready
log "fault 3: serving again $((SECONDS - reopened))s after the database reopened"

sleep 15
writers_running 4
log "fault 4: kill both replicas"
kubectl -n "$CATALOG_NAMESPACE" delete pod -l app.kubernetes.io/name=colvenna-catalog \
  --grace-period=0 --force >/dev/null 2>&1
await_ready
faults_over="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

status=0
wait "$stress" || status=1
stress=""
log "writers finished (exit $status)"
wait "$maintenance" || { log "maintenance failed"; status=1; }
maintenance=""
cp "$evidence/stress/steps.txt" "$evidence/stress-steps.txt" 2>/dev/null || true
grep -E '^(RESULT|RETRIED)' "$evidence/stress/flow.log" >"$evidence/stress-result.txt" 2>/dev/null || true
retried="$(grep -c '^RETRIED' "$evidence/stress-result.txt" || true)"
log "commits that failed at first and were then resolved: $retried"
((retried > 0)) || { log "FAIL: no fault hit a commit in flight"; status=1; }

# Maintenance rounds once the faults were over must all succeed.
python3 - "$evidence/maintenance/flow.log" "$faults_over" <<'PY' | tee -a "$evidence/faults.txt" || status=1
import datetime, sys
def instant(text):  # second precision; Java prints up to nanoseconds
    return datetime.datetime.fromisoformat(text[:19] + "+00:00")
over = instant(sys.argv[2])
late = []
for line in open(sys.argv[1]):
    parts = line.split()
    if len(parts) >= 3 and parts[0] == "ROUND":
        t = instant(parts[2])
        if t > over + datetime.timedelta(seconds=60):
            late.append(parts[1])
ok = bool(late) and all(r == "OK" for r in late)
print(f"maintenance rounds after the faults: {len(late)}, all OK: {ok}")
sys.exit(0 if ok else 1)
PY

# The catalog's own records: its report for the table, what it did, and the order it published in.
admin_open
consistent="$(consistency "$evidence/consistency.json")"
log "consistency report: consistent=$consistent"
[[ "$consistent" == true ]] || status=1
pointer="$(python3 -c 'import json,sys; print([b for b in json.load(open(sys.argv[1]))["branches"] if b["branch"] == "main"][0]["pointerSnapshot"])' "$evidence/consistency.json")"
admin_close
psql_pod colvenna-catalog-fault-audit \
  "psql -At -F ' ' -c \"SELECT action, outcome, http_status, detail->>'committed', count(*) FROM audit_events WHERE event_time >= '$start' AND resource LIKE '$db.%' GROUP BY 1, 2, 3, 4 ORDER BY 1, 2, 3, 4\"" \
  >"$evidence/audit-summary.txt"
stale="$(awk '$1 == "COMMIT" && $2 == "FAILED" && $4 == "false" {s += $5} END {print s + 0}' "$evidence/audit-summary.txt")"
log "commits the catalog refused on a stale base, then retried by their writers: $stale"
((stale > 0)) || { log "FAIL: no conflicting commit was refused"; status=1; }
psql_pod colvenna-catalog-fault-intents \
  "psql -At -F ' ' -c \"SELECT i.state, coalesce(i.resolution, '-'), count(*) FROM commit_intents i JOIN catalog_tables t ON t.id = i.table_id WHERE t.database_name = '$db' GROUP BY 1, 2 ORDER BY 1, 2\"" \
  >"$evidence/intents-summary.txt"
psql_pod colvenna-catalog-fault-order \
  "psql -At -c \"SELECT i.snapshot_id FROM commit_intents i JOIN catalog_tables t ON t.id = i.table_id WHERE t.database_name = '$db' AND t.name = '$table' AND i.branch = 'main' AND i.state = 'PUBLISHED' ORDER BY 1\"" \
  >"$evidence/published-snapshot-ids.txt"
python3 - "$evidence/published-snapshot-ids.txt" "$pointer" <<'PY' | tee -a "$evidence/faults.txt" || status=1
import sys
ids = [int(l) for l in open(sys.argv[1]) if l.strip()]
pointer = int(sys.argv[2])
ok = ids == list(range(1, pointer + 1))
print(f"published snapshot ids 1..{pointer} each exactly once: {ok} ({len(ids)} published)")
sys.exit(0 if ok else 1)
PY
[[ "$status" == 0 ]] && log "PASS"
exit "$status"
