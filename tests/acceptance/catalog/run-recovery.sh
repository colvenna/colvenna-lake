#!/usr/bin/env bash
# Acceptance on EKS: the catalog and its commit history stay consistent through instance failures
# and a metadata restore.
#   A. Instance failures under write load (CommitStress on table t1): the replicas run on different
#      nodes; one replica's node is drained for maintenance (the PodDisruptionBudget keeps one
#      serving, and the evicted replica waits rather than join the other), then the replica that
#      kept serving fails (killed without grace). The writers must be committing at each step.
#   B. Metadata backup and restore: pg_dump of colvenna_catalog is taken while CommitStress writes t2,
#      so the files run ahead of the backup, and t3 is created after it; the database is restored
#      from it with the catalog stopped. The catalog must list what it did before but t3; the
#      consistency report must show t2, and only t2, behind its files; reconcile brings t2 back
#      and register t3; every snapshot of t1, t2 and t3 must then read as before the restore, and
#      t2 must take writes again.
# The drill restores with pg_dump/pg_restore for speed; production restores RDS to a point in time,
# which leaves the same kind of state behind (docs/history/m2/control-plane-operations.md).
# Usage: run-recovery.sh <run-id> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run_id="${1:?usage: run-recovery.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-recovery.sh <run-id> <evidence-dir>}"
mkdir -p "$evidence"
committed_sha >"$evidence/source-sha.txt"
flows="$REPO_DIR/tests/acceptance/catalog"
tool="$flows/run-catalog-tool.sh"
db="flow_${run_id//-/_}_recovery"
tools_pod=colvenna-catalog-pg-tools
replica_count="$(kubectl -n "$CATALOG_NAMESPACE" get deploy colvenna-catalog -o jsonpath='{.spec.replicas}')"
writers=""
# shellcheck disable=SC2317  # invoked through the EXIT trap
cleanup() {
  kubectl uncordon "$(cat "$evidence/drained-node.txt" 2>/dev/null || echo none)" >/dev/null 2>&1 || true
  # Never leave the catalog stopped, nor writers committing behind an early exit.
  kubectl -n "$CATALOG_NAMESPACE" scale deploy/colvenna-catalog --replicas="$replica_count" \
    >/dev/null 2>&1 || true
  [[ -z "$writers" ]] || kill "$writers" >/dev/null 2>&1 || true
  kubectl -n "$ENGINE_NAMESPACE" delete job "flink-flow-$run_id-stress-t1" "flink-flow-$run_id-stress-t2" \
    "flink-flow-$run_id-stress-t3" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kubectl -n "$CATALOG_NAMESPACE" delete pod "$tools_pod" --ignore-not-found --wait=false \
    >/dev/null 2>&1 || true
  admin_close
}
trap cleanup EXIT
log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" | tee -a "$evidence/timeline.txt"; }
fail() { log "FAIL: $*"; exit 1; }
stress() { # stress <table> <label> <writers> <commits>
  FLOW_CLASS=com.colvenna.lake.acceptance.flow.CommitStress FLOW_ARGS="$db $1 $3 $4 200 1000" FLOW_DEADLINE=3600 \
    bash "$flows/run-flink-flow.sh" "$run_id-$2" "$evidence/$2" >"$evidence/$2.log" 2>&1
}
writing() { kill -0 "$writers" 2>/dev/null || fail "the writers finished before $1"; }
replicas_apart() { # replicas_apart: no two running replicas share a node, in one listing
  local nodes
  nodes="$(catalog_replicas | awk '{print $2}')"
  [[ "$(sort -u <<<"$nodes" | wc -l)" == "$(wc -l <<<"$nodes")" ]]
}
unscheduled() { # unscheduled: how many replicas the scheduler finds no node for
  kubectl -n "$CATALOG_NAMESPACE" get pods -l app.kubernetes.io/name=colvenna-catalog "$LIVE_PODS" \
    -o jsonpath='{range .items[*]}{.status.conditions[?(@.type=="PodScheduled")].reason}{"\n"}{end}' \
    | grep -c '^Unschedulable$' || true
}
consistent() { # consistent <table> <out>: prints true or false
  admin_call GET "/colvenna/admin/v1/consistency/$db/$1" | python3 -m json.tool >"$2"
  python3 -c 'import json,sys; print(str(json.load(open(sys.argv[1]))["consistent"]).lower())' "$2"
}
listing() { # listing <out>: every database and table the catalog lists
  local d
  for d in $(admin_call GET '/v1/colvenna/databases?maxResults=1000' \
    | python3 -c 'import json,sys; print(" ".join(json.load(sys.stdin)["databases"] or []))'); do
    admin_call GET "/v1/colvenna/databases/$d/tables?maxResults=1000" \
      | python3 -c 'import json,sys; [print(sys.argv[1] + "." + t) for t in json.load(sys.stdin)["tables"] or []]' "$d"
  done | sort >"$1"
}

# --- A. instance failures -------------------------------------------------------------------------
log "A: replicas $(catalog_replicas | tr '\n' ';')"
{ [[ "$(catalog_replicas | wc -l)" -ge 2 ]] && replicas_apart; } || fail "the catalog replicas share a node"
kubectl -n "$CATALOG_NAMESPACE" get pdb colvenna-catalog -o jsonpath='{.spec.minAvailable}' \
  >"$evidence/pdb-min-available.txt"
stress t1 stress-t1 4 240 &
writers=$!
admin_open
await_published "$db" t1 5 || fail "A: the writers published no five snapshots"
at="$(published_snapshot "$db" t1)"
admin_close
writing "the drain"
# One replica keeps serving through the drain of the other's node, and is the one to fail after.
listing="$(catalog_replicas)"
read -r serving serving_node _ <<<"$(head -1 <<<"$listing")"
node="$(awk -v s="$serving_node" '$2 != s {print $2; exit}' <<<"$listing")"
[[ -n "$node" ]] || fail "A: no replica runs on another node than $serving"
echo "$node" >"$evidence/drained-node.txt"
log "A: drain node $node for maintenance (catalog pods only), at snapshot $at"
kubectl drain "$node" --pod-selector=app.kubernetes.io/name=colvenna-catalog --ignore-daemonsets \
  --delete-emptydir-data --timeout=5m >"$evidence/drain.txt" 2>&1
# The evicted replica never joins the other on its node: it waits for a node of its own, or, with
# a free eligible node, starts there. One replica keeps serving.
settled=false
for _ in $(seq 1 24); do
  sleep 5
  replicas_apart || fail "A: two replicas share a node during the drain: $(catalog_replicas | tr '\n' ';')"
  waiting="$(unscheduled)"
  if ((waiting > 0)) || [[ "$(catalog_replicas | wc -l)" == "$replica_count" ]]; then
    settled=true
    break
  fi
done
[[ "$settled" == true ]] || fail "A: the evicted replica neither waits for a node nor runs"
log "A: during the drain: serving $(catalog_replicas | tr '\n' ';') $waiting replica waiting for a node of its own"
kubectl uncordon "$node" >/dev/null
kubectl -n "$CATALOG_NAMESPACE" rollout status deploy/colvenna-catalog --timeout=5m >/dev/null
log "A: replicas after the drain $(catalog_replicas | tr '\n' ';')"
replicas_apart || fail "A: the replicas share a node after the drain"
sleep 30
writing "the replica failure"
victim="$serving"
grep -q "^$victim " <<<"$(catalog_replicas)" || fail "A: the replica $victim that kept serving is gone"
log "A: replica $victim fails (killed without grace)"
kubectl -n "$CATALOG_NAMESPACE" delete pod "$victim" --grace-period=0 --force >/dev/null 2>&1
kubectl -n "$CATALOG_NAMESPACE" rollout status deploy/colvenna-catalog --timeout=5m >/dev/null
writing "the recovery"
wait "$writers" || fail "A: the writers' checks failed; see stress-t1/flow.log"
writers=""
grep -E '^(STEP|RESULT|RETRIED)' "$evidence/stress-t1/flow.log" >"$evidence/stress-t1-result.txt" || true
admin_open
[[ "$(consistent t1 "$evidence/consistency-t1-after-failures.json")" == true ]] || fail "A: t1 inconsistent"
admin_close
log "A: PASS, writers' checks passed and t1 is consistent"

# --- B. metadata backup and restore ---------------------------------------------------------------
cat >"$evidence/pg-tools.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $tools_pod}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 70, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: psql
      image: $POSTGRES_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["sleep", "7200"]
      env:
        - {name: PGHOST, value: "$(stack_output "$FOUNDATION_STACK" CatalogEndpoint)"}
        - {name: PGDATABASE, value: colvenna_catalog}
        - {name: PGSSLMODE, value: verify-full}
        - {name: PGSSLROOTCERT, value: /ca/ca.pem}
        - name: PGUSER
          valueFrom: {secretKeyRef: {name: colvenna-catalog-db, key: username}}
        - name: PGPASSWORD
          valueFrom: {secretKeyRef: {name: colvenna-catalog-db, key: password}}
      volumeMounts:
        - {name: ca, mountPath: /ca}
        - {name: backup, mountPath: /backup}
  volumes:
    - name: ca
      configMap: {name: colvenna-catalog-db-ca}
    - name: backup
      emptyDir: {}
YAML
kubectl -n "$CATALOG_NAMESPACE" apply -f "$evidence/pg-tools.yaml" >/dev/null
kubectl -n "$CATALOG_NAMESPACE" wait "pod/$tools_pod" --for=condition=Ready --timeout=5m >/dev/null
pg() { kubectl -n "$CATALOG_NAMESPACE" exec "$tools_pod" -- "$@"; }

stress t2 stress-t2 2 60 &
writers=$!
admin_open
await_published "$db" t2 20 || fail "B: the writers published no 20 snapshots"
admin_close
writing "the backup"
log "B: pg_dump of colvenna_catalog while t2 is being written"
pg pg_dump -Fc -f /backup/colvenna_catalog.dump
pg sh -c 'ls -l /backup/colvenna_catalog.dump; pg_restore -l /backup/colvenna_catalog.dump | wc -l' \
  >"$evidence/backup.txt"
wait "$writers" || fail "B: the writers' checks failed; see stress-t2/flow.log"
writers=""
grep -E '^(STEP|RESULT|RETRIED)' "$evidence/stress-t2/flow.log" >"$evidence/stress-t2-result.txt" || true
log "B: t3 created after the backup"
stress t3 stress-t3 1 10 || fail "B: the writer of t3 failed; see stress-t3/flow.log"
bash "$tool" rest "$evidence/before-restore" history "$db" t1 t2 t3 >/dev/null
admin_open
listing "$evidence/listing-before-restore.txt"
t3_path="$(admin_call GET "/v1/colvenna/databases/$db/tables/t3" | python3 -c 'import json,sys; print(json.load(sys.stdin)["path"])')"
admin_close
log "B: before the restore: $(wc -l <"$evidence/before-restore/rest.history") snapshots, $(wc -l <"$evidence/listing-before-restore.txt") tables listed"

log "B: restore colvenna_catalog from the backup, catalog stopped"
kubectl -n "$CATALOG_NAMESPACE" scale deploy/colvenna-catalog --replicas=0 >/dev/null
kubectl -n "$CATALOG_NAMESPACE" wait pod -l app.kubernetes.io/name=colvenna-catalog "$LIVE_PODS" --for=delete \
  --timeout=5m >/dev/null 2>&1 || true
pg pg_restore --clean --if-exists --no-owner --single-transaction -d colvenna_catalog \
  /backup/colvenna_catalog.dump >"$evidence/restore.txt" 2>&1
kubectl -n "$CATALOG_NAMESPACE" scale deploy/colvenna-catalog --replicas="$replica_count" >/dev/null
kubectl -n "$CATALOG_NAMESPACE" rollout status deploy/colvenna-catalog --timeout=5m >/dev/null
log "B: catalog back on the restored database: $(catalog_replicas | tr '\n' ';')"

admin_open
listing "$evidence/listing-after-restore.txt"
diff "$evidence/listing-before-restore.txt" "$evidence/listing-after-restore.txt" \
  >"$evidence/listing-diff.txt" || true
[[ "$(grep '^[<>]' "$evidence/listing-diff.txt")" == "< $db.t3" ]] \
  || fail "B: the catalog should list what it did before but t3; see listing-diff.txt"
t1_state="$(consistent t1 "$evidence/consistency-t1-restored.json")"
t2_state="$(consistent t2 "$evidence/consistency-t2-restored.json")"
log "B: after the restore t3 is not listed, t1 consistent=$t1_state, t2 consistent=$t2_state, t2 served at snapshot $(published_snapshot "$db" t2)"
[[ "$t1_state" == true && "$t2_state" == false ]] \
  || fail "B: expected t2, and only t2, behind its files after the restore"
admin_call POST "/colvenna/admin/v1/tables/$db/t2/reconcile" | python3 -m json.tool \
  >"$evidence/reconcile-t2.json"
[[ "$(consistent t2 "$evidence/consistency-t2-reconciled.json")" == true ]] \
  || fail "B: t2 still inconsistent after reconcile"
log "B: t2 reconciled: $(python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); print(r.get("branches"))' "$evidence/reconcile-t2.json")"
admin_call POST "/v1/colvenna/databases/$db/register" \
  "{\"identifier\":{\"database\":\"$db\",\"object\":\"t3\"},\"path\":\"$t3_path\"}" >/dev/null
[[ "$(consistent t3 "$evidence/consistency-t3-registered.json")" == true ]] \
  || fail "B: t3 inconsistent after it was registered again"
listing "$evidence/listing-after-register.txt"
diff "$evidence/listing-before-restore.txt" "$evidence/listing-after-register.txt" \
  >"$evidence/listing-diff-after-register.txt" || fail "B: the catalog lists other tables than before"
log "B: t3 registered again at $t3_path; the catalog lists what it did before the restore"
admin_close

bash "$tool" rest "$evidence/after-restore" history "$db" t1 t2 t3 >/dev/null
diff "$evidence/before-restore/rest.history" "$evidence/after-restore/rest.history" \
  >"$evidence/history-diff.txt" || fail "B: the history differs after the restore; see history-diff.txt"
log "B: every snapshot of t1, t2 and t3 reads as before the restore"
bash "$tool" rest "$evidence/write-after" write "$db" t2 >/dev/null
log "B: t2 takes writes again: $(grep -c COMMITTED "$evidence/write-after/probes.txt") commit"
log "PASS"
