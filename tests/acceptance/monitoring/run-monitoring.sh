#!/usr/bin/env bash
# Acceptance on EKS: latency, backlog, commits, maintenance and freshness tracked by source, table
# and job; bad rows sent to a traceable place; alerts on correctness and latency breaches; metrics
# that measure what the acceptance contract measures. Writes PASS/FAIL lines to <evidence>/verdicts.tsv and fails if any
# check fails.
#
# The monitoring stack (install-monitoring.sh) scrapes the operator and colvenna-catalog. A MySQL
# LakeSource with a heartbeat table feeds ODS; DWD (orders with their customer's region) declares
# checks, DWS (orders by region) aggregates it. Then:
# - only authenticated access: Grafana refuses an unauthenticated request; from a namespace not
#   labelled for monitoring, Prometheus and the metrics ports are unreachable;
# - each path's measure is in Prometheus: source to ODS (heartbeat), each layer's lag, the
#   freshness of each table, commits by outcome, checkpoints, maintenance backlog, tasks;
# - the contract's measure: marker orders carry their source commit time; each one's first ODS snapshot,
#   read independently, gives its latency, which must agree with the heartbeat's; its first DWD
#   snapshot gives the layer's, which must agree with the operator's layer latency;
# - bad rows: orders in an unknown state go to DWD's quarantine, with the check, and alert;
# - latency breaches: DWD suspended past the p99 target (5 min) alerts, what queries see downstream
#   grows, and it resolves once DWD resumes; the source suspended past p99 alerts on source to ODS.
#
# usage: run-monitoring.sh <run-id> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-monitoring.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-monitoring.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
flows="$REPO_DIR/tests/acceptance"
cdc="$flows/cdc"
mkdir -p "$evidence"/{status,dumps,sql,tables,prometheus}
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

# capture <kind> <name> <file>: the declaration and its deployments, for status.py.
capture() {
  local key=source label=colvenna-lake.io/source
  if [[ "$1" == laketable ]]; then
    key=table label=colvenna-lake.io/table
  fi
  {
    printf '{"%s":' "$key"
    kubectl -n "$JOB_NAMESPACE" get "$1" "$2" -o json
    printf ',"deployments":'
    kubectl -n "$JOB_NAMESPACE" get flinkdeployment -l "$label=$2" -o json \
      | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["items"]))'
    printf '}'
  } >"$3"
}

# await <kind> <name> <label> <timeout-seconds> <expression>: polls until status.py says it holds.
await() {
  local kind="$1" name="$2" label="$3" end=$((SECONDS + $4)) started=$SECONDS
  local file="$evidence/status/$3.json"
  while ((SECONDS < end)); do
    if capture "$kind" "$name" "$file" 2>/dev/null && python3 "$flows/status.py" check "$file" "$5"; then
      verdict "$label" PASS "$((SECONDS - started))s"
      return 0
    fi
    sleep 10
  done
  # Before the run cleans up: what the readiness depended on (#245).
  [[ "$kind" != lakesource ]] || source_diagnostics "$name" "$evidence/diagnostics/$label"
  verdict "$label" FAIL "not within $4s: $5"
  return 1
}

# laketable <name> <db> <table> <primary-key> <upstream,...> <query> [<extra spec yaml>]
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
${7:-}
YAML
  kubectl apply -f "$file"
}

work="$(mktemp -d)"
chmod 700 "$work"
forwards=()
# shellcheck disable=SC2317  # invoked through the EXIT trap
cleanup() {
  local pid
  for pid in "${forwards[@]}"; do kill "$pid" 2>/dev/null || true; done
  rm -rf "$work"
}
trap cleanup EXIT

# forward <service> <service-port> <variable>: an authenticated port-forward to a monitoring Service,
# until the run ends, on a free local port, which goes into <variable>.
prometheus_port="" grafana_port=""
forward() {
  local port end=$((SECONDS + 60))
  kubectl -n "$MONITORING_NAMESPACE" port-forward "svc/$1" ":$2" >"$work/forward-$1.log" 2>&1 &
  forwards+=("$!")
  port="$(forwarded_port "$work/forward-$1.log")" || { echo "no port-forward to $1"; return 1; }
  until curl -s -o /dev/null "http://127.0.0.1:$port/"; do
    ((SECONDS < end)) || { echo "no port-forward to $1"; return 1; }
    sleep 1
  done
  printf -v "$3" '%s' "$port"
}

# prom <promql>: the query's result vector as JSON.
prom() {
  curl -s --get --data-urlencode "query=$1" "http://127.0.0.1:$prometheus_port/api/v1/query" \
    | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["data"]["result"]))'
}

# alert_state <alertname> <label>=<value>: the alert's state for that label (firing, pending) or
# inactive.
alert_state() {
  curl -s "http://127.0.0.1:$prometheus_port/api/v1/alerts" | python3 -c '
import json, sys
name, sel = sys.argv[1], sys.argv[2].split("=", 1)
states = [a["state"] for a in json.load(sys.stdin)["data"]["alerts"]
          if a["labels"].get("alertname") == name and a["labels"].get(sel[0]) == sel[1]]
print("firing" if "firing" in states else states[0] if states else "inactive")' "$1" "$2"
}

# await_alert <label> <timeout-seconds> <alertname> <label>=<value> <state>
await_alert() {
  local end=$((SECONDS + $2)) started=$SECONDS got=""
  while ((SECONDS < end)); do
    got="$(alert_state "$3" "$4" 2>/dev/null || true)"
    if [[ "$got" == "$5" ]]; then
      curl -s "http://127.0.0.1:$prometheus_port/api/v1/alerts" >"$evidence/prometheus/alerts-$1.json"
      verdict "$1" PASS "$((SECONDS - started))s: $3{$4} $got"
      return 0
    fi
    sleep 15
  done
  curl -s "http://127.0.0.1:$prometheus_port/api/v1/alerts" >"$evidence/prometheus/alerts-$1.json"
  verdict "$1" FAIL "$3{$4} is $got, not $5 within $2s"
  return 1
}

small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
ensure_rds_ca "$JOB_NAMESPACE"
bash "$FIXTURES_DIR/sources/sources.sh" users >/dev/null

# ------------------------------------------------------------------------- access
step "the monitoring stack, and who may reach it"
kubectl -n "$MONITORING_NAMESPACE" get deploy,svc,networkpolicy -o wide >"$evidence/monitoring.txt"
forward "$MONITORING_RELEASE-prometheus" 9090 prometheus_port
forward "$MONITORING_RELEASE-grafana" 3000 grafana_port
anonymous="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$grafana_port/api/dashboards/uid/colvenna-lake")"
kubectl -n "$MONITORING_NAMESPACE" get secret "$GRAFANA_ADMIN_SECRET" -o jsonpath='{.data.admin-password}' \
  | base64 -d >"$work/grafana"
printf 'user = "admin:%s"\n' "$(cat "$work/grafana")" >"$work/grafana.curlrc"
rm -f "$work/grafana"
panels="$(curl -s -K "$work/grafana.curlrc" "http://127.0.0.1:$grafana_port/api/dashboards/uid/colvenna-lake" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin)["dashboard"]; print(d["title"], len(d["panels"]))')"
if [[ "$anonymous" == 401 && "$panels" == "Colvenna Lake 运行 "* ]]; then
  verdict grafana-signin-required PASS "anonymous $anonymous; signed in: $panels"
else
  verdict grafana-signin-required FAIL "anonymous $anonymous; signed in: $panels"
fi
# From a namespace not labelled for monitoring, nothing answers: Prometheus, the operator's and
# colvenna-catalog's metrics ports time out (curl exit 28: the name resolves, the connection is
# dropped). From the monitoring namespace the metrics ports answer, Prometheus (Grafana's alone)
# does not.
# probe_pod <namespace> <file>: each URL's HTTP code and curl's exit code, one line each.
probe_pod() {
  cat >"$work/probe.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: mon-probe-$run, namespace: $1}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 100, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: curl
      image: $CURL_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: [ALL]}}
      command: ["sh", "-c"]
      args:
        - |
          for url in http://$MONITORING_RELEASE-prometheus.$MONITORING_NAMESPACE.svc:9090/api/v1/status/buildinfo \\
                     http://$OPERATOR_RELEASE-metrics.$OPERATOR_NAMESPACE.svc:9100/metrics \\
                     http://colvenna-catalog.$CATALOG_NAMESPACE.svc:9100/metrics; do
            code="\$(curl -s -o /dev/null -m 10 -w '%{http_code}' "\$url")"
            echo "\$url \$code \$?"
          done
      resources: {requests: {cpu: 10m, memory: 32Mi}, limits: {memory: 64Mi}}
YAML
  kubectl apply -f "$work/probe.yaml" >/dev/null
  kubectl -n "$1" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/mon-probe-$run" --timeout=3m >/dev/null || true
  kubectl -n "$1" logs "mon-probe-$run" >"$2" 2>&1 || true
  kubectl -n "$1" delete pod "mon-probe-$run" --ignore-not-found >/dev/null
}
probe_pod "$JOB_NAMESPACE" "$evidence/isolation.txt"
probe_pod "$MONITORING_NAMESPACE" "$evidence/isolation-control.txt"
if python3 - "$evidence/isolation.txt" "$evidence/isolation-control.txt" <<'PY'
import sys
outside = [l.split() for l in open(sys.argv[1]) if l.strip()]
inside = [l.split() for l in open(sys.argv[2]) if l.strip()]
# Outside: all three dropped. Inside: Prometheus dropped, the two metrics ports answer.
ok = (len(outside) == 3 and all(code == "000" and rc == "28" for _, code, rc in outside)
      and len(inside) == 3 and inside[0][1:] == ["000", "28"]
      and all(code == "200" for _, code, _ in inside[1:]))
sys.exit(0 if ok else 1)
PY
then
  verdict metrics-isolated PASS "outside: $(tr '\n' ';' <"$evidence/isolation.txt") inside: $(tr '\n' ';' <"$evidence/isolation-control.txt")"
else
  verdict metrics-isolated FAIL "outside: $(tr '\n' ';' <"$evidence/isolation.txt") inside: $(tr '\n' ';' <"$evidence/isolation-control.txt")"
fi

# ------------------------------------------------------------------------- the flow
# m3o: run-mongo.sh already names its ODS ods_mongo_<run> and its writer mw-<run>.
db="mon_$run" ods="ods_mon_$run" dwd="dwd_mon_$run" dws="dws_mon_$run"
source_name="mon-$run" dwd_name="mon-dwd-$run" dws_name="mon-dws-$run"
step "mysql fixture $db, with its heartbeat table"
{
  python3 "$cdc/mysql_contract.py" reset "$db"
  python3 "$cdc/mysql_contract.py" setup "$db"
  python3 "$cdc/mysql_contract.py" heartbeat "$db"
  cat <<SQL
USE $db;
SET SESSION cte_max_recursion_depth = 100000;
INSERT INTO customers (id, name, region, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 200)
  SELECT i, CONCAT('customer-', i), ELT(1 + i % 4, 'north', 'south', 'east', 'west'), 1 FROM n;
INSERT INTO orders (id, customer_id, amount, state, note, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 5000)
  SELECT i, 1 + i % 200, ROUND(i * 1.25, 2), 'created', NULL, 1 FROM n;
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
    host: $(stack_output "$TEST_MYSQL_STACK" MySqlEndpoint)
    database: $db
    credentialsSecret: {name: mysql-cdc}
    inspectorSecret: {name: mysql-inspect}
    caConfigMap: rds-ca
    serverTimeZone: UTC
  tables: {include: [orders, customers, colvenna_heartbeat]}
  target: {database: $ods, bucket: 2}
  governance: {owner: shop-team, level: L2, retention: 7d}
  monitoring:
    heartbeat: {table: colvenna_heartbeat, credentialsSecret: {name: mysql-beat}}
  job: $small
  deletionPolicy: Delete
YAML
step "writer start"
bash "$cdc/writer.sh" start mysql "$db" "ow-$run"
kubectl apply -f "$evidence/source.yaml"
await lakesource "$source_name" source-ready 900 \
  "state('orders') == 'Ready' and state('customers') == 'Ready' and live_all()" || true

dwd_query="SELECT o.id AS order_id, o.customer_id, c.region, o.amount, o.state, o.version_no
FROM (SELECT *, PROCTIME() AS pt FROM $ods.orders) AS o
JOIN $ods.customers FOR SYSTEM_TIME AS OF o.pt AS c ON o.customer_id = c.id"
laketable "$dwd_name" "$dwd" orders order_id "$ods.orders,$ods.customers" "$dwd_query" "  governance: {owner: analytics, level: L2, retention: 7d}
  quality:
    notNull: [customer_id]
    allowed: {state: [created, paid, shipped]}
    ranges: {amount: {min: '0'}}
  deletionPolicy: Delete
  job: $small"
dws_query="SELECT region, COUNT(*) AS orders, SUM(amount) AS amount FROM $dwd.orders GROUP BY region"
laketable "$dws_name" "$dws" segments region "$dwd.orders" "$dws_query" "  governance: {owner: analytics, level: L2, retention: 7d}
  deletionPolicy: Delete
  job: $small"
await laketable "$dwd_name" dwd-running 900 "phase == 'Running' and live_all()" || true
await laketable "$dws_name" dws-running 900 "phase == 'Running' and live_all()" || true

# ------------------------------------------------------------------------- what is measured
step "markers: orders that carry their source commit time"
# A change waits for the next checkpoint (every 30 s, the source's default), so its latency depends on
# where in the interval it lands. The ten markers go in one session 3 s apart, 0, 3, ..., 27 s into
# the interval wherever it begins, so their median sits in its middle; six written by separate pods,
# which take 20-30 s to start, landed wherever chance put them, and their median wandered by 20 s
# (#287). The hop from ODS to DWD is about the same for every marker in a run, so their landing
# together does not change what layer-latency-agrees sees.
marker_base=9000000 marker_count=10
for ((i = 1; i <= marker_count; i++)); do
  ((i == 1)) || echo "DO SLEEP(3);"
  printf "INSERT INTO %s.orders (id, customer_id, amount, state, note, version_no) VALUES (%d, 7, 1.00, 'created', DATE_FORMAT(UTC_TIMESTAMP(6), '%%Y-%%m-%%dT%%H:%%i:%%s.%%fZ'), 1);\n" \
    "$db" "$((marker_base + i))"
done >"$work/markers.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$work/markers.sql" >/dev/null
end=$((SECONDS + 600))
scraped=""
while ((SECONDS < end)); do
  prom '{__name__=~"colvenna_source_latency_seconds|colvenna_source_freshness_seconds|colvenna_table_upstream_lag_seconds|colvenna_table_layer_latency_seconds|colvenna_table_freshness_seconds|colvenna_catalog_commits_total|colvenna_job_last_checkpoint_timestamp_seconds|colvenna_job_taskmanager_heap_used_bytes|colvenna_table_level0_files|colvenna_table_snapshots|colvenna_table_manifest_bytes|colvenna_table_commit_files|colvenna_table_quarantined_rows"}' \
    >"$evidence/prometheus/series.json"
  scraped="$(python3 - "$evidence/prometheus/series.json" "$ods" "$dwd" "$dws" "$source_name" "$dwd_name" <<'PY'
import json, sys
series = json.load(open(sys.argv[1]))
ods, dwd, dws, source, dwd_name = sys.argv[2:]
have = lambda name, **labels: any(
    s["metric"]["__name__"] == name and all(s["metric"].get(k) == v for k, v in labels.items())
    for s in series)
want = {
    "source latency": have("colvenna_source_latency_seconds", source=source),
    "source freshness": have("colvenna_source_freshness_seconds", source=source),
    "dwd lag": have("colvenna_table_upstream_lag_seconds", table=f"{dwd}.orders"),
    "dws lag": have("colvenna_table_upstream_lag_seconds", table=f"{dws}.segments"),
    "dwd freshness": have("colvenna_table_freshness_seconds", table=f"{dwd}.orders"),
    "dws freshness": have("colvenna_table_freshness_seconds", table=f"{dws}.segments"),
    "commits": have("colvenna_catalog_commits_total", table=f"{ods}.orders", outcome="committed"),
    "source checkpoints": have("colvenna_job_last_checkpoint_timestamp_seconds", source=source),
    "dwd checkpoints": have("colvenna_job_last_checkpoint_timestamp_seconds", laketable=dwd_name),
    "dwd layer latency": have("colvenna_table_layer_latency_seconds", table=f"{dwd}.orders"),
    "dwd heap": have("colvenna_job_taskmanager_heap_used_bytes", laketable=dwd_name),
    "ods manifests": have("colvenna_table_manifest_bytes", table=f"{ods}.orders"),
    "ods commit files": have("colvenna_table_commit_files", table=f"{ods}.orders"),
    "ods backlog": have("colvenna_table_level0_files", table=f"{ods}.orders"),
    "dwd snapshots": have("colvenna_table_snapshots", table=f"{dwd}.orders"),
    "dwd quarantine": have("colvenna_table_quarantined_rows", table=f"{dwd}.orders", rule="allowed(state)"),
}
print(("OK " if all(want.values()) else "MISSING ") + ", ".join(k for k, v in want.items() if not v))
PY
)"
  [[ "$scraped" == OK* ]] && break
  sleep 30
done
if [[ "$scraped" == OK* ]]; then
  verdict paths-measured PASS "every path's measure scraped"
else
  verdict paths-measured FAIL "$scraped"
fi
# The acceptance contract's 2.1: each marker's latency, read independently from ODS's changelog (the first snapshot
# holding it), against the heartbeat's over the same minutes.
bash "$flows/lake.sh" changelog "$evidence/dumps/ods-orders.changelog.jsonl" "$ods.orders" >/dev/null
prom "quantile_over_time(0.5, colvenna_source_latency_seconds{source=\"$source_name\"}[15m])" \
  >"$evidence/prometheus/heartbeat-median.json"
if python3 - "$evidence/dumps/ods-orders.changelog.jsonl" "$evidence/prometheus/heartbeat-median.json" "$marker_base" \
  "$marker_count" >"$work/targets.txt" <<'PY'
import datetime, json, statistics, sys
base, count = int(sys.argv[3]), int(sys.argv[4])
is_marker = lambda k: k.isdigit() and base < int(k) <= base + count
first = {}
for line in open(sys.argv[1]):
    c = json.loads(line)
    row = c.get("row", {})
    if c.get("committedAt") and is_marker(str(row.get("id", ""))) and row.get("note"):
        key = row["id"]
        source = datetime.datetime.fromisoformat(row["note"].replace("Z", "+00:00")).timestamp()
        latency = c["committedAt"] / 1000 - source
        first[key] = min(first.get(key, latency), latency)
heartbeat = [float(s["value"][1]) for s in json.load(open(sys.argv[2]))]
markers = sorted(first.values())
# Within a checkpoint's share of either median: an operator that reported 0 would not pass.
ok = (len(markers) >= count - 2 and len(heartbeat) == 1
      and abs(statistics.median(markers) - heartbeat[0]) <= max(15, 0.25 * statistics.median(markers)))
print(f"markers {[round(m) for m in markers]} s, median {round(statistics.median(markers)) if markers else None} s;"
      f" heartbeat median {round(heartbeat[0]) if heartbeat else None} s")
sys.exit(0 if ok else 1)
PY
then
  verdict source-latency-agrees PASS "$(cat "$work/targets.txt")"
else
  verdict source-latency-agrees FAIL "$(cat "$work/targets.txt")"
fi

# The acceptance contract's 2.1, one layer to the next: each marker's first DWD snapshot against its first ODS one, read
# independently from both changelogs, against the layer latency the operator measures.
bash "$flows/lake.sh" changelog "$evidence/dumps/dwd-orders.changelog.jsonl" "$dwd.orders" >/dev/null
prom "quantile_over_time(0.5, colvenna_table_layer_latency_seconds{table=\"$dwd.orders\"}[15m])" \
  >"$evidence/prometheus/layer-median.json"
if python3 - "$evidence/dumps/ods-orders.changelog.jsonl" "$evidence/dumps/dwd-orders.changelog.jsonl" \
  "$evidence/prometheus/layer-median.json" "$marker_base" "$marker_count" >"$work/layer.txt" <<'PY'
import json, statistics, sys
base, count = int(sys.argv[4]), int(sys.argv[5])
is_marker = lambda k: k.isdigit() and base < int(k) <= base + count

def first(path, key):
    out = {}
    for line in open(path):
        c = json.loads(line)
        k = str(c.get("row", {}).get(key, ""))
        if c.get("committedAt") and is_marker(k):
            out[k] = min(out.get(k, c["committedAt"]), c["committedAt"])
    return out

ods, dwd = first(sys.argv[1], "id"), first(sys.argv[2], "order_id")
hops = sorted((dwd[k] - ods[k]) / 1000 for k in ods if k in dwd)
measured = [float(s["value"][1]) for s in json.load(open(sys.argv[3]))]
ok = (len(hops) >= count - 2 and len(measured) == 1
      and abs(statistics.median(hops) - measured[0]) <= max(15, 0.25 * statistics.median(hops)))
print(f"markers ODS -> DWD {[round(h) for h in hops]} s, median {round(statistics.median(hops)) if hops else None} s;"
      f" measured median {round(measured[0]) if measured else None} s")
sys.exit(0 if ok else 1)
PY
then
  verdict layer-latency-agrees PASS "$(cat "$work/layer.txt")"
else
  verdict layer-latency-agrees FAIL "$(cat "$work/layer.txt")"
fi

# ------------------------------------------------------------------------- bad rows
step "orders in an unknown state: DWD's quarantine takes them"
cat >"$work/bad.sql" <<SQL
INSERT INTO $db.orders (id, customer_id, amount, state, note, version_no) VALUES
  (9100001, 3, 5.00, 'lost', NULL, 1), (9100002, 4, 6.00, 'lost', NULL, 1), (9100003, 5, 7.00, 'lost', NULL, 1);
UPDATE $db.orders SET state = 'lost', version_no = version_no + 1 WHERE id = 10;
SQL
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$work/bad.sql" >/dev/null
await laketable "$dwd_name" quarantined 600 "quarantined('allowed(state)') >= 4" || true
await_alert rows-quarantined-alerted 300 ColvennaRowsQuarantined "table=$dwd.orders" firing || true
bash "$flows/lake.sh" rows "$evidence/dumps/quarantine.rows.jsonl" "$dwd.orders_quarantine" "$dwd.orders" >/dev/null
if python3 - "$evidence/dumps/quarantine.rows.jsonl" "$dwd" >"$work/bad.txt" <<'PY'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
q = {r["row"]["order_id"]: r["row"]["colvenna_rule"] for r in rows if r.get("type") == "ROW" and r["table"].endswith("_quarantine")}
t = {r["row"]["order_id"] for r in rows if r.get("type") == "ROW" and r["table"] == f"{sys.argv[2]}.orders"}
bad = {"9100001", "9100002", "9100003", "10"}
ok = bad <= set(q) and not (bad & t) and all(q[k] == "allowed(state)" for k in bad)
print(f"quarantined {sorted(q)} ({sorted(set(q.values()))}); in the table: {sorted(bad & t)}")
sys.exit(0 if ok else 1)
PY
then
  verdict bad-rows-traceable PASS "$(cat "$work/bad.txt")"
else
  verdict bad-rows-traceable FAIL "$(cat "$work/bad.txt")"
fi

# ------------------------------------------------------------------------- a latency breach
step "DWD suspended past the p99 target: the layer's alert fires, and resolves once it resumes"
kubectl -n "$JOB_NAMESPACE" patch laketable "$dwd_name" --type merge -p '{"spec":{"suspend":true}}'
await_alert layer-lag-alerted 900 ColvennaLayerLagP99 "table=$dwd.orders" firing || true
# What a query sees grows with the suspension, down the path (DWS reads DWD's freshness as of its
# own round, so it may follow a round later).
grown=false
for _ in $(seq 1 20); do
  prom "colvenna_table_freshness_seconds{table=~\"$dwd.orders|$dws.segments\"}" >"$evidence/prometheus/freshness-suspended.json"
  if python3 - "$evidence/prometheus/freshness-suspended.json" <<'PY'
import json, sys
got = {s["metric"]["table"]: float(s["value"][1]) for s in json.load(open(sys.argv[1]))}
sys.exit(0 if len(got) == 2 and all(v > 300 for v in got.values()) else 1)
PY
  then
    grown=true
    break
  fi
  sleep 15
done
if [[ "$grown" == true ]]; then
  verdict freshness-grows-down-the-path PASS "$(cat "$evidence/prometheus/freshness-suspended.json")"
else
  verdict freshness-grows-down-the-path FAIL "$(cat "$evidence/prometheus/freshness-suspended.json")"
fi
kubectl -n "$JOB_NAMESPACE" patch laketable "$dwd_name" --type merge -p '{"spec":{"suspend":false}}'
await_alert layer-lag-resolved 900 ColvennaLayerLagP99 "table=$dwd.orders" inactive || true

step "the source suspended past the p99 target: source to ODS alerts, and resolves once it resumes"
kubectl -n "$JOB_NAMESPACE" patch lakesource "$source_name" --type merge -p '{"spec":{"suspend":true}}'
await_alert source-stale-alerted 900 ColvennaSourceStale "source=$source_name" firing || true
kubectl -n "$JOB_NAMESPACE" patch lakesource "$source_name" --type merge -p '{"spec":{"suspend":false}}'
await_alert source-stale-resolved 900 ColvennaSourceStale "source=$source_name" inactive || true

# ------------------------------------------------------------------------- the rest
step "writer stop: $(bash "$cdc/writer.sh" stop "ow-$run")"
prom 'sum by (table, outcome) (colvenna_catalog_commits_total)' >"$evidence/prometheus/commits.json"
# Each by its name: "or" matches on labels alone and would keep one of the three per table.
prom '{__name__=~"colvenna_table_level0_files|colvenna_table_small_files|colvenna_table_snapshots"}' >"$evidence/prometheus/backlog.json"
prom 'time() - colvenna_job_last_checkpoint_timestamp_seconds' >"$evidence/prometheus/checkpoint-age.json"
kubectl -n "$JOB_NAMESPACE" get events -o json >"$evidence/events.json"
step "delete the tables and the source"
kubectl -n "$JOB_NAMESPACE" delete laketable "$dws_name" "$dwd_name" --timeout=10m
kubectl -n "$JOB_NAMESPACE" delete lakesource "$source_name" --timeout=10m
python3 "$cdc/mysql_contract.py" reset "$db" >"$evidence/sql/reset.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/reset.sql" >/dev/null
echo "verdicts:"
cat "$evidence/verdicts.tsv"
exit "$failed"
