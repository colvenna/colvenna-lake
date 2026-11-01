#!/usr/bin/env bash
# Acceptance on EKS: the operator's lineage, as Marquez shows it back. Writes PASS/FAIL lines to
# <evidence>/verdicts.tsv and fails if any check fails.
#
# - A MySQL source writes orders and segments into ODS; a DWD table joins them, a DWS table
#   aggregates DWD, and a dashboard serves DWS as a Doris view. Marquez shows the path from each
#   MySQL table to the Doris view, table by table, each hop the job that runs it.
# - Each Flink job's current version in Marquez is its FlinkDeployment's spec hash, and its
#   running run is the deployment's Flink job. Each lake table's fields in Marquez are
#   colvenna-catalog's columns, names and types in order, also after a column is added at the source.
# - A declaration change (DWD's checkpoint interval) is a new version: the old run completes, the
#   new one runs. A backfill of DWD is a batch run of DWD's query that completes.
# - What is not lineage is not reported: a compaction, an ad-hoc query. A restarted operator
#   starts no run twice. Deleting the declarations ends their runs.
# - Marquez has no authentication: only ClusterIP services, an unlabelled namespace cannot
#   connect, and the API server refuses an unauthenticated proxy request.
#
# usage: run-lineage.sh <run-id> <evidence-dir>; needs deploy/scripts/open-layer/install-marquez.sh, then
# deploy/scripts/operator/install-operator.sh (which points the operator at Marquez).
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/marquez.sh
source "$REPO_DIR/deploy/scripts/lib/marquez.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-lineage.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-lineage.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
flows="$REPO_DIR/tests/acceptance"
here="$REPO_DIR/tests/acceptance/lineage"
mkdir -p "$evidence"/{status,sql,tables,marquez,catalog}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
for image in operator mysql sql; do
  echo "$image $(image_ref "$image")" | tee -a "$evidence/images.txt"
done
failed=0
step() { echo "$(date -u +%FT%TZ)	$*" | tee -a "$evidence/timeline.tsv"; }
verdict() {
  # One line per verdict: a detail's newlines and tabs become spaces.
  local detail="${3:-}"
  detail="${detail//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${detail//$'\t'/ }" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS ]] || failed=1
}
work="$(mktemp -d)"
chmod 700 "$work"
trap 'marquez_close; admin_close; rm -rf "$work"' EXIT

db="lin_$run" ods="ods_lin_$run" dwd="dwd_lin_$run" dws="dws_lin_$run"
source_name="ll-$run" dwd_name="lwd-$run" dws_name="lws-$run" dashboard="ld-$run"
jobs="colvenna-lake.$JOB_NAMESPACE"
paimon="paimon://colvenna-catalog.colvenna-catalog.svc:8181"
mysql_host="$(stack_output "$TEST_MYSQL_STACK" MySqlEndpoint)"
mysql="mysql://$mysql_host:3306"
doris="doris://$DORIS_CLUSTER-fe-service.$DORIS_NAMESPACE.svc:9030"
small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"

# await_object <label> <timeout> <kind> <name> <python expression over st, the status>
await_object() {
  local label="$1" end=$((SECONDS + $2)) started=$SECONDS file="$evidence/status/$1.json"
  while ((SECONDS < end)); do
    if kubectl -n "$JOB_NAMESPACE" get "$3" "$4" -o json >"$file" 2>/dev/null \
      && python3 -c 'import json,sys; st=json.load(open(sys.argv[1])).get("status") or {}; sys.exit(0 if eval(sys.argv[2]) else 1)' "$file" "$5"; then
      verdict "$label" PASS "$((SECONDS - started))s"
      return 0
    fi
    sleep 10
  done
  verdict "$label" FAIL "not within $2s: $5"
  python3 -c 'import json,sys; s=json.load(open(sys.argv[1])).get("status",{}); print(s.get("phase"), s.get("message"))' "$file" || true
  return 1
}

# await_marquez <label> <timeout> <lineage.py args...>: the check holds within the timeout (the
# operator reports once a minute).
await_marquez() {
  local label="$1" end=$((SECONDS + $2)) started=$SECONDS out="$evidence/marquez/$1.txt"
  shift 2
  while ((SECONDS < end)); do
    if python3 "$here/lineage.py" "$1" "$MARQUEZ_URL" "${@:2}" >"$out" 2>&1; then
      verdict "$label" PASS "$((SECONDS - started))s: $(head -c 900 "$out")"
      return 0
    fi
    sleep 15
  done
  verdict "$label" FAIL "$(head -c 900 "$out")"
  return 1
}

# deployments <file> <name>...: the FlinkDeployments, as Kubernetes has them now.
deployments() {
  local file="$1"
  shift
  kubectl -n "$JOB_NAMESPACE" get flinkdeployment "$@" -o json \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps(d.get("items", [d])))' >"$file"
}

# catalog_table <db> <table>: colvenna-catalog's table, kept as evidence; prints the file.
catalog_table() {
  local file="$evidence/catalog/$1.$2.json"
  admin_call GET "/v1/colvenna/databases/$1/tables/$2" >"$file"
  echo "$file"
}

# laketable <name> <db> <table> <primary-key> <upstream,...> <query>
laketable() {
  local file="$evidence/tables/$1.yaml" upstream="" u
  for u in ${5//,/ }; do upstream+="\"$u\", "; done
  cat >"$file" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTable
metadata: {name: $1, namespace: $JOB_NAMESPACE}
spec:
  target: {database: $2, table: $3}
  primaryKey: [$4]
  upstream: [${upstream%, }]
  query: |
    ${6//$'\n'/$'\n'    }
  governance: {owner: analytics, level: L2, retention: 7d}
  deletionPolicy: Delete
  job: $small
YAML
  kubectl apply -f "$file"
}

# laketask <name> <type> <db> <table>
laketask() {
  cat >"$evidence/tables/$1.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: $1, namespace: $JOB_NAMESPACE}
spec:
  type: $2
  table: {database: $3, table: $4}
YAML
  kubectl apply -f "$evidence/tables/$1.yaml"
}

# ----------------------------------------------------------------------------- preconditions
step "the operator reports to Marquez"
reported="$(kubectl -n "$OPERATOR_NAMESPACE" get deploy "$OPERATOR_RELEASE" \
  -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="COLVENNA_LINEAGE_URL")].value}')"
if [[ "$reported" == "http://marquez.$LINEAGE_NAMESPACE.svc:5000/api/v1/lineage" ]]; then
  verdict operator-reports-to-marquez PASS "$reported"
else
  verdict operator-reports-to-marquez FAIL "COLVENNA_LINEAGE_URL=$reported"
fi
admin_open
marquez_open
ensure_rds_ca "$JOB_NAMESPACE"

# ------------------------------------------------------------------------------- the pipeline
step "mysql fixture $db, source $source_name, $dwd_name, $dws_name and $dashboard"
{
  python3 "$flows/cdc/mysql_contract.py" reset "$db"
  python3 "$flows/cdc/mysql_contract.py" setup "$db"
  python3 "$flows/cdc/mysql_contract.py" table "$db" segments
  cat <<SQL
USE $db;
SET SESSION cte_max_recursion_depth = 100000;
INSERT INTO segments (id, label, amount, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 50)
  SELECT i, ELT(1 + i % 3, 'gold', 'silver', 'bronze'), 0, 1 FROM n;
INSERT INTO orders (id, customer_id, amount, state, note, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 500)
  SELECT i, 1 + i % 50, ROUND(i * 1.25, 2), 'created', NULL, 1 FROM n;
SQL
} >"$evidence/sql/setup.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/setup.sql" >/dev/null
cat >"$evidence/source.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: $source_name, namespace: $JOB_NAMESPACE}
spec:
  type: mysql
  connection:
    host: $mysql_host
    database: $db
    credentialsSecret: {name: mysql-cdc}
    inspectorSecret: {name: mysql-inspect}
    caConfigMap: rds-ca
    serverTimeZone: UTC
  tables: {include: [orders, segments]}
  target: {database: $ods}
  governance: {owner: shop-team, level: L2, retention: 7d}
  job: $small
  deletionPolicy: Delete
YAML
kubectl apply -f "$evidence/source.yaml"
await_object source-ready 900 lakesource "$source_name" \
  "{t['name']: t.get('state') for t in st.get('tables', [])} == {'orders': 'Ready', 'segments': 'Ready'}" || true
laketable "$dwd_name" "$dwd" orders order_id "$ods.orders,$ods.segments" \
  "SELECT o.id AS order_id, o.customer_id, s.label AS segment, o.amount, o.version_no
FROM (SELECT *, PROCTIME() AS pt FROM $ods.orders) AS o
JOIN $ods.segments FOR SYSTEM_TIME AS OF o.pt AS s ON o.customer_id = s.id"
laketable "$dws_name" "$dws" segments segment "$dwd.orders" \
  "SELECT segment, COUNT(*) AS order_count, SUM(amount) AS amount_total FROM $dwd.orders GROUP BY segment"
await_object dwd-running 900 laketable "$dwd_name" "st.get('phase') == 'Running'" || true
await_object dws-running 900 laketable "$dws_name" "st.get('phase') == 'Running'" || true
cat >"$evidence/dashboard.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeDashboard
metadata: {name: $dashboard, namespace: $JOB_NAMESPACE}
spec:
  tables: [$dws.segments]
  governance: {owner: bi-team, level: L2, retention: 7d}
  queries:
    - {name: by_segment, serve: external, sql: "SELECT segment, order_count FROM $dws.segments"}
YAML
kubectl apply -f "$evidence/dashboard.yaml"
await_object dashboard-ready 600 lakedashboard "$dashboard" "st.get('phase') == 'Ready'" || true
object="$(kubectl -n "$JOB_NAMESPACE" get lakedashboard "$dashboard" -o jsonpath='{.status.queries[0].object}')"

# ------------------------------------------------------------------- the path, versions, schema
src_job="$source_name-cdc" dwd_job="$dwd_name-table" dws_job="$dws_name-table"
view_job="LakeDashboard.$dashboard.by_segment"
cat >"$work/orders-path.json" <<JSON
[["$mysql $db.orders", "$src_job", "$paimon colvenna.$ods.orders"],
 ["$paimon colvenna.$ods.orders", "$dwd_job", "$paimon colvenna.$dwd.orders"],
 ["$paimon colvenna.$dwd.orders", "$dws_job", "$paimon colvenna.$dws.segments"],
 ["$paimon colvenna.$dws.segments", "$view_job", "$doris internal.$object"]]
JSON
cat >"$work/segments-path.json" <<JSON
[["$mysql $db.segments", "$src_job", "$paimon colvenna.$ods.segments"],
 ["$paimon colvenna.$ods.segments", "$dwd_job", "$paimon colvenna.$dwd.orders"]]
JSON
cp "$work"/*-path.json "$evidence/marquez/"
await_marquez path-orders-to-doris-view 300 hops "$jobs" "$work/orders-path.json" || true
await_marquez path-segments-to-dwd 300 hops "$jobs" "$work/segments-path.json" || true
deployments "$evidence/status/deployments.json" "$src_job" "$dwd_job" "$dws_job"
await_marquez versions-are-the-deployments 300 versions "$jobs" "$evidence/status/deployments.json" || true
for t in "$ods orders" "$ods segments" "$dwd orders" "$dws segments"; do
  read -r tdb tname <<<"$t"
  await_marquez "schema-$tdb.$tname" 300 schema "$paimon" "colvenna.$tdb.$tname" \
    "$(catalog_table "$tdb" "$tname")" || true
done

step "a column added at the source"
cat >"$evidence/sql/add-column.sql" <<SQL
USE $db;
ALTER TABLE orders ADD COLUMN channel VARCHAR(16) NULL;
UPDATE orders SET channel = 'web', version_no = version_no + 1 WHERE id <= 20;
SQL
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/add-column.sql" >/dev/null
end=$((SECONDS + 600))
while ((SECONDS < end)) && ! grep -q '"channel"' "$(catalog_table "$ods" orders)"; do sleep 15; done
if grep -q '"channel"' "$evidence/catalog/$ods.orders.json"; then
  await_marquez schema-follows-the-catalog-after-a-new-column 300 schema "$paimon" \
    "colvenna.$ods.orders" "$(catalog_table "$ods" orders)" || true
else
  verdict schema-follows-the-catalog-after-a-new-column FAIL "colvenna-catalog has no channel column"
fi

# ---------------------------------------------------------------------------- a new version
step "a new version of $dwd_name: its checkpoint interval"
before="$(kubectl -n "$JOB_NAMESPACE" get flinkdeployment "$dwd_job" -o jsonpath='{.metadata.annotations.colvenna-lake\.io/spec-hash}')"
kubectl -n "$JOB_NAMESPACE" patch laketable "$dwd_name" --type merge -p '{"spec":{"job":{"checkpointInterval":"20s"}}}'
end=$((SECONDS + 600))
while ((SECONDS < end)); do
  after="$(kubectl -n "$JOB_NAMESPACE" get flinkdeployment "$dwd_job" -o jsonpath='{.metadata.annotations.colvenna-lake\.io/spec-hash}')"
  [[ "$after" != "$before" ]] && break
  sleep 10
done
await_object dwd-upgraded 900 laketable "$dwd_name" \
  "st.get('phase') == 'Running' and (st.get('applied') or {}).get('hash') == '$after' and not st.get('upgrade')" || true
deployments "$evidence/status/deployments-upgraded.json" "$dwd_job"
await_marquez new-version-runs 300 versions "$jobs" "$evidence/status/deployments-upgraded.json" || true
python3 "$here/lineage.py" runs "$MARQUEZ_URL" "$jobs" "$dwd_job" >"$evidence/marquez/dwd-runs.txt" || true
if grep -q "COMPLETED $before" "$evidence/marquez/dwd-runs.txt" && grep -q "^RUNNING $after" "$evidence/marquez/dwd-runs.txt"; then
  verdict old-version-run-completed PASS "$(cat "$evidence/marquez/dwd-runs.txt")"
else
  verdict old-version-run-completed FAIL "$(cat "$evidence/marquez/dwd-runs.txt")"
fi

# ----------------------------------------------------------------------- a backfill, a batch run
step "backfill $dwd.orders"
laketask "bf-$run" backfill "$dwd" orders
await_object backfill-succeeded 1800 laketask "bf-$run" "st.get('phase') == 'Succeeded'" || true
cat >"$work/backfill-path.json" <<JSON
[["$paimon colvenna.$ods.orders", "LakeTask.bf-$run", "$paimon colvenna.$dwd.orders"],
 ["$paimon colvenna.$ods.segments", "LakeTask.bf-$run", "$paimon colvenna.$dwd.orders"]]
JSON
await_marquez backfill-is-a-batch-run 300 hops "$jobs" "$work/backfill-path.json" || true
python3 "$here/lineage.py" runs "$MARQUEZ_URL" "$jobs" "LakeTask.bf-$run" >"$evidence/marquez/backfill-runs.txt" || true
if grep -q "^COMPLETED" "$evidence/marquez/backfill-runs.txt"; then
  verdict backfill-run-completed PASS "$(cat "$evidence/marquez/backfill-runs.txt")"
else
  verdict backfill-run-completed FAIL "$(cat "$evidence/marquez/backfill-runs.txt")"
fi
await_object dwd-resumed 900 laketable "$dwd_name" "st.get('phase') == 'Running'" || true
deployments "$evidence/status/deployments-resumed.json" "$dwd_job"
await_marquez resumed-job-is-its-running-run 300 versions "$jobs" "$evidence/status/deployments-resumed.json" || true

# ----------------------------------------------------------------- what is not lineage, restart
step "a compaction and an ad-hoc query: not lineage"
python3 "$here/lineage.py" nodes "$MARQUEZ_URL" "$mysql" "$db.orders" >"$evidence/marquez/nodes-before.txt"
python3 "$here/lineage.py" jobs "$MARQUEZ_URL" "$jobs" >"$evidence/marquez/jobs-before.txt"
laketask "compact-$run" compact "$dws" segments
await_object compaction-succeeded 900 laketask "compact-$run" "st.get('phase') == 'Succeeded'" || true
echo "SELECT segment, order_count FROM $dws.segments" >"$work/adhoc.sql"
bash "$flows/lake.sh" query "$evidence/status/adhoc.rows" "$work/adhoc.sql" "$dws.segments" >/dev/null || true
sleep 150
python3 "$here/lineage.py" jobs "$MARQUEZ_URL" "$jobs" >"$evidence/marquez/jobs.txt"
python3 "$here/lineage.py" nodes "$MARQUEZ_URL" "$mysql" "$db.orders" >"$evidence/marquez/nodes-after.txt"
if cmp -s "$evidence/marquez/jobs-before.txt" "$evidence/marquez/jobs.txt" \
  && cmp -s "$evidence/marquez/nodes-before.txt" "$evidence/marquez/nodes-after.txt"; then
  verdict maintenance-and-queries-not-reported PASS "the same $(grep -c . "$evidence/marquez/jobs.txt") jobs and $(grep -c . "$evidence/marquez/nodes-after.txt") nodes from $db.orders before and after"
else
  verdict maintenance-and-queries-not-reported FAIL "jobs: $(diff "$evidence/marquez/jobs-before.txt" "$evidence/marquez/jobs.txt" | tr '\n' ' '); nodes: $(diff "$evidence/marquez/nodes-before.txt" "$evidence/marquez/nodes-after.txt" | tr '\n' ' ')"
fi

step "the operator restarts"
python3 "$here/lineage.py" runs "$MARQUEZ_URL" "$jobs" "$dws_job" >"$evidence/marquez/dws-runs-before.txt" || true
kubectl -n "$OPERATOR_NAMESPACE" delete pod -l app.kubernetes.io/name=colvenna-operator --wait=true >/dev/null
kubectl -n "$OPERATOR_NAMESPACE" rollout status "deploy/$OPERATOR_RELEASE" --timeout=5m >/dev/null
sleep 150
python3 "$here/lineage.py" runs "$MARQUEZ_URL" "$jobs" "$dws_job" >"$evidence/marquez/dws-runs-after.txt" || true
if cmp -s "$evidence/marquez/dws-runs-before.txt" "$evidence/marquez/dws-runs-after.txt" \
  && [[ "$(grep -o RUNNING "$evidence/marquez/dws-runs-after.txt" | wc -l)" == 1 ]]; then
  verdict restart-starts-no-run-twice PASS "$(cat "$evidence/marquez/dws-runs-after.txt")"
else
  verdict restart-starts-no-run-twice FAIL "before: $(cat "$evidence/marquez/dws-runs-before.txt"); after: $(cat "$evidence/marquez/dws-runs-after.txt")"
fi
metrics_log="$(mktemp)"
kubectl -n "$OPERATOR_NAMESPACE" port-forward "deploy/$OPERATOR_RELEASE" :9100 >"$metrics_log" 2>&1 &
metrics_pid=$!
metrics_port="$(forwarded_port "$metrics_log" || true)"
curl -s "http://localhost:$metrics_port/metrics" | grep '^colvenna_lineage' >"$evidence/status/lineage-metrics.txt" || true
kill "$metrics_pid" 2>/dev/null || true
if grep -q "colvenna_lineage_failures{namespace=\"$JOB_NAMESPACE\"} 0.0" "$evidence/status/lineage-metrics.txt" \
  && grep -q "colvenna_lineage_last_success_timestamp_seconds{namespace=\"$JOB_NAMESPACE\"}" "$evidence/status/lineage-metrics.txt"; then
  verdict lineage-reported-without-failures PASS "$(tr '\n' ' ' <"$evidence/status/lineage-metrics.txt")"
else
  verdict lineage-reported-without-failures FAIL "$(tr '\n' ' ' <"$evidence/status/lineage-metrics.txt")"
fi

# ------------------------------------------------------------------------------- no anonymous
step "Marquez is reachable only inside the cluster, from labelled namespaces"
kubectl -n "$LINEAGE_NAMESPACE" get services,ingresses -o json >"$evidence/status/lineage-exposure.json"
if python3 -c '
import json, sys
items = json.load(open(sys.argv[1]))["items"]
bad = [i["metadata"]["name"] for i in items if i["kind"] == "Ingress" or i["spec"].get("type") != "ClusterIP"]
print(bad); sys.exit(1 if bad else 0)' "$evidence/status/lineage-exposure.json"; then
  verdict marquez-cluster-ip-only PASS "services ClusterIP, no ingress"
else
  verdict marquez-cluster-ip-only FAIL "exposed: see lineage-exposure.json"
fi
# probe <namespace> [<url>]: whether a pod there reaches the URL (Marquez's API by default).
probe() {
  local url="${2:-http://marquez.$LINEAGE_NAMESPACE.svc:5000/api/v1/namespaces}"
  cat >"$work/probe-$1.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: lineage-probe-$run}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 70, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: probe
      image: $POSTGRES_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["sh", "-c"]
      args: ["if wget -q -T 10 -O /dev/null $url; then echo reached; else echo refused; fi"]
YAML
  run_pod "$1" "lineage-probe-$run" "$work/probe-$1.yaml" | tail -1
}
got="$(probe "$JOB_NAMESPACE")"
if [[ "$got" == refused ]]; then
  verdict unlabelled-namespace-cannot-connect PASS "from $JOB_NAMESPACE: $got"
else
  verdict unlabelled-namespace-cannot-connect FAIL "from $JOB_NAMESPACE: $got"
fi
api_ip="$(kubectl -n "$LINEAGE_NAMESPACE" get pod -l app=marquez "$LIVE_PODS" -o jsonpath='{.items[0].status.podIP}')"
web="$(probe "$JOB_NAMESPACE" "http://marquez-web.$LINEAGE_NAMESPACE.svc:3000/")"
admin="$(probe "$JOB_NAMESPACE" "http://$api_ip:5001/healthcheck")"
if [[ "$web" == refused && "$admin" == refused ]]; then
  verdict web-and-admin-port-refused PASS "from $JOB_NAMESPACE: the web UI $web, the admin port $admin"
else
  verdict web-and-admin-port-refused FAIL "from $JOB_NAMESPACE: the web UI $web, the admin port $admin"
fi
got="$(probe "$OPERATOR_NAMESPACE")"
if [[ "$got" == reached ]]; then
  verdict operator-namespace-connects PASS "from $OPERATOR_NAMESPACE: $got"
else
  verdict operator-namespace-connects FAIL "from $OPERATOR_NAMESPACE: $got"
fi
endpoint="$(aws eks describe-cluster --name "$(stack_output "$FOUNDATION_STACK" ClusterName)" --query cluster.endpoint --output text)"
code="$(curl -sk -o /dev/null -w '%{http_code}' "$endpoint/api/v1/namespaces/$LINEAGE_NAMESPACE/services/marquez:5000/proxy/api/v1/namespaces")"
if [[ "$code" == 401 || "$code" == 403 ]]; then
  verdict unauthenticated-proxy-refused PASS "the API server answers $code"
else
  verdict unauthenticated-proxy-refused FAIL "the API server answers $code"
fi

# ---------------------------------------------------------------------------------- deletion
step "delete the declarations"
kubectl -n "$JOB_NAMESPACE" delete laketask "bf-$run" "compact-$run" --wait=false >/dev/null || true
kubectl -n "$JOB_NAMESPACE" delete lakedashboard "$dashboard" --wait=true --timeout=10m >/dev/null || true
kubectl -n "$JOB_NAMESPACE" delete laketable "$dws_name" "$dwd_name" --wait=true --timeout=15m >/dev/null || true
kubectl -n "$JOB_NAMESPACE" delete lakesource "$source_name" --wait=true --timeout=15m >/dev/null || true
sleep 150
open_runs=""
for j in "$src_job" "$dwd_job" "$dws_job" "$view_job"; do
  python3 "$here/lineage.py" runs "$MARQUEZ_URL" "$jobs" "$j" >"$evidence/marquez/ended-$j.txt" || true
  grep -q RUNNING "$evidence/marquez/ended-$j.txt" && open_runs+="$j "
done
if [[ -z "$open_runs" ]]; then
  verdict runs-ended-with-their-declarations PASS "no run of the four jobs is RUNNING"
else
  verdict runs-ended-with-their-declarations FAIL "still RUNNING: $open_runs"
fi
python3 "$flows/cdc/mysql_contract.py" reset "$db" >"$evidence/sql/reset.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/reset.sql" >/dev/null || true

echo
awk -F'\t' '{print $3}' "$evidence/verdicts.tsv" | sort | uniq -c
exit "$failed"
