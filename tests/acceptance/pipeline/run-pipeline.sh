#!/usr/bin/env bash
# Acceptance on AWS: the console's picture of the whole pipeline, behind the entry. Writes
# PASS/FAIL lines to <evidence>/verdicts.tsv and fails if any check fails.
#
# - A MySQL source with a heartbeat feeds ODS; DWD (orders with their customer's region, with
#   checks and a quarantine) and DWS (orders by region) are the table jobs; a dashboard serves DWS
#   in Doris, one query a view and one a materialized view; a writer keeps changing the source.
# - Followed in the console from the source to the dashboard, each part's state, latency, backlog
#   and alerts are what kubectl, Prometheus (at the console's moment) and Marquez say.
# - Faults: rows in an unknown state quarantined by DWD, DWD suspended past the p99 target, the
#   source suspended past it. When the alert fires, the console marks that part as the problem,
#   what is downstream as affected by it and what is upstream as neither, and its alerts are
#   Prometheus's; once the alert resolves the console no longer gives it as the reason, and once
#   every alert of the run has resolved nothing is marked.
# - Nothing the console serves holds a Secret's value, a password seal or a row of the tables; it
#   answers nothing but GET. Its Kubernetes identity may only get and list, and writing with it is
#   refused; it holds no colvenna-catalog identity, cannot reach colvenna-catalog, and colvenna-catalog refuses
#   its service-account token; Prometheus answers its namespace's queries, not admin or lifecycle
#   calls, and a namespace without the label not at all.
# - Administrators and operators see the pipeline, BI users and users without a role are refused,
#   without a session nothing is served; the console's audit has each of them.
#
# usage: run-pipeline.sh <run-id> <evidence-dir>; needs the entry stack, install-lbc.sh, and
# build-console.sh then install-console.sh from this commit, with the monitoring stack
# (deploy/scripts/monitoring/install-monitoring.sh, from this commit too) and Marquez.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/marquez.sh
source "$REPO_DIR/deploy/scripts/lib/marquez.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-pipeline.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-pipeline.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
here="$REPO_DIR/tests/acceptance/pipeline"
entry_py="$REPO_DIR/tests/acceptance/entry/entry.py"
flows="$REPO_DIR/tests/acceptance"
cdc="$flows/cdc"
mkdir -p "$evidence"/{status,sql,http,console,prometheus,marquez,audit}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
image="$(entry_image console)"
running="$(kubectl -n "$CONSOLE_NAMESPACE" get deployment "$CONSOLE_RELEASE" -o jsonpath='{.spec.template.spec.containers[0].image}')"
[[ "$running" == "$image" ]] || { echo "The console runs $running, not $image of this commit" >&2; exit 1; }
{
  echo "console $image"
  echo "operator $(kubectl -n "$OPERATOR_NAMESPACE" get deployment "$OPERATOR_RELEASE" -o jsonpath='{.spec.template.spec.containers[0].image}')"
  echo "prometheus $(kubectl -n "$MONITORING_NAMESPACE" get deployment "$MONITORING_RELEASE-prometheus" -o jsonpath='{.spec.template.spec.containers[0].image}')"
} | tee "$evidence/versions.txt"
failed=0
began=$SECONDS
started_ms="$(($(date +%s) * 1000))"
step() { echo "$(date -u +%FT%TZ)	$*" | tee -a "$evidence/timeline.tsv"; }
verdict() {
  local why="${3:-}"
  why="${why//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${why//$'\t'/ }" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS ]] || failed=1
}
# check <label> <detail> <command...>: PASS when the command succeeds.
check() {
  local label="$1" detail="$2"
  shift 2
  if "$@"; then verdict "$label" PASS "$detail"; else verdict "$label" FAIL "$detail"; fi
}
# checked <label> <command...>: PASS when the command succeeds, with what it printed.
checked() {
  local label="$1" out
  shift
  if out="$("$@" 2>&1)"; then verdict "$label" PASS "$out"; else verdict "$label" FAIL "$out"; fi
}

pool="$(entry_output UserPoolId)"
url="$(entry_output consoleUrl)"
signin="$(entry_output SignInDomain)"
audit_group="$(entry_output ConsoleAuditLogGroup)"
ns="$JOB_NAMESPACE"
db="pipe_$run" ods="ods_pipe_$run" dwd="dwd_pipe_$run" dws="dws_pipe_$run"
source_name="ps-$run" dwd_name="pwd-$run" dws_name="pws-$run" dashboard="pd-$run" writer="pw-$run" task="pt-$run"
small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
# The run's nodes, as the console names them.
n_source="source:$ns/$source_name" n_dwd="job:$ns/$dwd_name" n_dws="job:$ns/$dws_name" n_board="dashboard:$ns/$dashboard"
t_orders="table:$ods.orders" t_customers="table:$ods.customers" t_dwd="table:$dwd.orders"
t_quarantine="table:$dwd.orders_quarantine" t_dws="table:$dws.segments"
prefix="pipe-$run"
export ENTRY_AGENT="$prefix-browser"
state="$(mktemp -d)"
chmod 700 "$state"
python3 -c 'import sys,urllib.parse; print(urllib.parse.urlsplit(sys.argv[1]).hostname)' "$signin" >"$state/signin-host"
declare -A groups=([admin]=colvenna-admin [op]=colvenna-operator [bi]=colvenna-bi [none]="")
forwards=()
# shellcheck disable=SC2317  # run by the trap
cleanup() {
  local pid u
  for pid in "${forwards[@]}"; do kill "$pid" 2>/dev/null || true; done
  marquez_close
  bash "$cdc/writer.sh" stop "$writer" >/dev/null 2>&1 || true
  # A run that stopped early leaves no job running (the last steps delete them in order).
  kubectl -n "$ns" delete laketask "$task" "$task-c-orders" "$task-c-customers" "$task-c-colvenna-heartbeat" \
    --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kubectl -n "$ns" delete lakedashboard "$dashboard" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kubectl -n "$ns" delete laketable "$dws_name" "$dwd_name" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kubectl -n "$ns" delete lakesource "$source_name" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kubectl -n "$CONSOLE_NAMESPACE" delete pod "pipeline-probe-$run" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kubectl -n "$ns" delete pod "pipeline-probe-$run" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  for u in "${!groups[@]}"; do
    aws cognito-idp admin-delete-user --user-pool-id "$pool" --username "$prefix-$u" 2>/dev/null || true
  done
  rm -rf "$state"
}
trap cleanup EXIT
entry() { python3 -I "$entry_py" "$@"; }
compare() { python3 -I "$here/compare.py" "$@"; }
field() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))' "$1" "$2"; }
# refused_to_sign_in <entry-get-file>: a 302 to the user pool's sign-in, nothing of the console.
# shellcheck disable=SC2317  # run by check
refused_to_sign_in() {
  [[ "$(field "$1" 'd["status"]')" == 302 && "$(field "$1" 'd["location"]')" == "$signin/oauth2/authorize"* ]]
}
# console <file> [<user>]: GET /api/pipeline as the operator (or that user); its body to the file.
console() {
  local out
  out="$(entry get "$url/api/pipeline" "$state" "$prefix-${2:-op}" --body-file "$1")"
  [[ "$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["status"])' "$out")" == 200 ]]
}
# forward <namespace> <service> <service-port> <variable> [s]: an authenticated port-forward, until
# the run ends, on a free local port, which goes into <variable>; s when the service speaks HTTPS.
forward() {
  local port end=$((SECONDS + 60)) log
  log="$(mktemp)"
  kubectl -n "$1" port-forward "svc/$2" ":$3" >"$log" 2>&1 &
  forwards+=("$!")
  port="$(forwarded_port "$log")" || { echo "no port-forward to $2"; return 1; }
  until curl -sk -o /dev/null "http${5:-}://127.0.0.1:$port/"; do
    ((SECONDS < end)) || { echo "no port-forward to $2"; return 1; }
    sleep 1
  done
  printf -v "$4" '%s' "$port"
}
prometheus_port="" catalog_port="" prom_url=""
# alert_state <alertname> <label>=<value>: firing, pending or inactive.
alert_state() {
  curl -s "$prom_url/api/v1/alerts" | python3 -c '
import json, sys
name, sel = sys.argv[1], sys.argv[2].split("=", 1)
states = [a["state"] for a in json.load(sys.stdin)["data"]["alerts"]
          if a["labels"].get("alertname") == name and a["labels"].get(sel[0]) == sel[1]]
print("firing" if "firing" in states else states[0] if states else "inactive")' "$1" "$2"
}
# await_status <label> <timeout> <kind> <name> <python expression over st, the status>
await_status() {
  local label="$1" end=$((SECONDS + $2)) started=$SECONDS file="$evidence/status/$1.json"
  while ((SECONDS < end)); do
    if kubectl -n "$ns" get "$3" "$4" -o json >"$file" 2>/dev/null && python3 - "$file" "$5" <<'PY'; then
import json, sys
st = json.load(open(sys.argv[1])).get("status") or {}
def table(name):
    return next((t for t in st.get("tables", []) if t.get("name") == name), {})
sys.exit(0 if eval(sys.argv[2]) else 1)
PY
      verdict "$label" PASS "$((SECONDS - started))s"
      return 0
    fi
    sleep 10
  done
  verdict "$label" FAIL "not within $2s: $5"
  return 1
}
# laketable <name> <db> <table> <primary-key> <upstream,...> <query> [<extra spec yaml>]
laketable() {
  local file="$evidence/status/$1.yaml" upstream="" u
  for u in ${5//,/ }; do upstream+="\"$u\", "; done
  cat >"$file" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTable
metadata: {name: $1, namespace: $ns}
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
# kubectl_now <file>: the run's declarations and deployments as kubectl reads them.
kubectl_now() {
  {
    printf '{"sources":'
    kubectl -n "$ns" get lakesource "$source_name" -o json | python3 -c 'import json,sys; print(json.dumps([json.load(sys.stdin)]))'
    printf ',"tables":'
    kubectl -n "$ns" get laketable "$dwd_name" "$dws_name" -o json | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["items"]))'
    printf ',"dashboards":'
    kubectl -n "$ns" get lakedashboard "$dashboard" -o json | python3 -c 'import json,sys; print(json.dumps([json.load(sys.stdin)]))'
    printf ',"tasks":'
    kubectl -n "$ns" get laketask "$task" -o json | python3 -c 'import json,sys; print(json.dumps([json.load(sys.stdin)]))'
    printf ',"deployments":'
    kubectl -n "$ns" get flinkdeployment -o json | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["items"]))'
    printf '}'
  } >"$1"
}
# agree <label> <tries> <compare-command...>: reads the console and the other side back to back
# until they agree (a value may change between two reads), at most that many times.
agree() {
  local label="$1" tries="$2" i out
  shift 2
  for i in $(seq 1 "$tries"); do
    console "$evidence/console/$label.json" || { sleep 10; continue; }
    case "$1" in
      state) kubectl_now "$evidence/status/$label-kubectl.json"
        out="$(compare state "$evidence/console/$label.json" "$evidence/status/$label-kubectl.json" "$ns" 2>&1)" ;;
      alerts) curl -s "$prom_url/api/v1/alerts" >"$evidence/prometheus/$label.json"
        out="$(compare alerts "$evidence/console/$label.json" "$evidence/prometheus/$label.json" "${@:2}" 2>&1)" ;;
      *) out="$(compare "$1" "$evidence/console/$label.json" "${@:2}" 2>&1)" ;;
    esac && { verdict "$label" PASS "$out (read $i)"; return 0; }
    sleep 10
  done
  verdict "$label" FAIL "$out"
  return 1
}
# await_located <label> <timeout> <alertname> <label>=<value> <problem> <downstream,...> <upstream,...>
# When Prometheus has the alert firing, the console (read right after) has the problem there: in
# the same poll or within a minute of it.
await_located() {
  local label="$1" end=$((SECONDS + $2)) started=$SECONDS fired="" out="" i
  while ((SECONDS < end)); do
    if [[ "$(alert_state "$3" "$4" 2>/dev/null)" == firing ]]; then
      fired=$((SECONDS - started))
      curl -s "$prom_url/api/v1/alerts" >"$evidence/prometheus/$label.json"
      for i in 1 2 3 4 5; do
        if console "$evidence/console/$label.json" &&
          out="$(compare located "$evidence/console/$label.json" "$5" "$3" "$6" "$7" 2>&1)"; then
          verdict "$label" PASS "$3 firing ${fired}s after the fault; the console $(((SECONDS - started) - fired))s after: $out"
          return 0
        fi
        sleep 12
      done
      verdict "$label" FAIL "$3 firing ${fired}s after the fault; the console: $out"
      return 1
    fi
    sleep 15
  done
  verdict "$label" FAIL "$3{$4} not firing within $2s"
  return 1
}
# await_cleared <label> <timeout> <alertname> <label>=<value> <node>: once the alert is inactive,
# the console no longer has it as why the node is a problem.
await_cleared() {
  local label="$1" end=$((SECONDS + $2)) started=$SECONDS out="" i
  while ((SECONDS < end)); do
    if [[ "$(alert_state "$3" "$4" 2>/dev/null)" == inactive ]]; then
      for i in 1 2 3 4 5; do
        if console "$evidence/console/$label.json" &&
          out="$(compare cleared "$evidence/console/$label.json" "$5" "$3" 2>&1)"; then
          verdict "$label" PASS "$3 inactive $((SECONDS - started))s after the fix: $out"
          return 0
        fi
        sleep 12
      done
      verdict "$label" FAIL "$3 inactive; the console: $out"
      return 1
    fi
    sleep 15
  done
  verdict "$label" FAIL "$3{$4} still active after $2s"
  return 1
}

forward "$MONITORING_NAMESPACE" "$MONITORING_RELEASE-prometheus" 9090 prometheus_port
prom_url="http://127.0.0.1:$prometheus_port"
marquez_open

# ----------------------------------------------------------------------------------------- users
step "users"
for u in "${!groups[@]}"; do
  name="$prefix-$u"
  python3 -c 'import secrets,string; a=string.ascii_letters+string.digits; print("Aa1!" + "".join(secrets.choice(a) for _ in range(24)), end="")' >"$state/$name.pw"
  aws cognito-idp admin-create-user --user-pool-id "$pool" --username "$name" --message-action SUPPRESS >/dev/null
  # The password goes in a file, not on the command line.
  python3 -c 'import json,sys; print(json.dumps({"UserPoolId": sys.argv[1], "Username": sys.argv[2], "Password": open(sys.argv[3]).read(), "Permanent": True}))' \
    "$pool" "$name" "$state/$name.pw" >"$state/$name.input.json"
  aws cognito-idp admin-set-user-password --cli-input-json "file://$state/$name.input.json"
  rm -f "$state/$name.input.json"
  for g in ${groups[$u]}; do
    aws cognito-idp admin-add-user-to-group --user-pool-id "$pool" --username "$name" --group-name "$g"
  done
done

# ---------------------------------------------------------------------------- who sees the pipeline
step "without a session, and by role"
for page in "pipeline /pipeline" "api /api/pipeline" "node /pipeline/node?id=$n_dwd"; do
  read -r name path <<<"$page"
  file="$evidence/http/no-session-$name.json"
  entry get "$url$path" >"$file"
  check "no-session-$name" "$(field "$file" '(d["status"], d["location"][:70])')" refused_to_sign_in "$file"
done
for u in admin op bi none; do
  entry login "$url/" "$state" "$prefix-$u" >"$evidence/http/login-$u.json" || true
done
# shellcheck disable=SC2317  # run by check
draws_the_pipeline() { [[ "$(field "$1" 'd["status"]')" == 200 ]] && grep -q "<svg" "$2"; }
for u in admin op; do
  entry get "$url/pipeline" "$state" "$prefix-$u" --body-file "$evidence/http/$u-pipeline.html" >"$evidence/http/$u-pipeline.json"
  check "sees-the-pipeline-$u" "$(field "$evidence/http/$u-pipeline.json" 'd["status"]')" \
    draws_the_pipeline "$evidence/http/$u-pipeline.json" "$evidence/http/$u-pipeline.html"
done
for u in bi none; do
  codes=""
  for path in /pipeline /api/pipeline "/pipeline/node?id=$n_dwd"; do
    entry get "$url$path" "$state" "$prefix-$u" >"$state/refused.json"
    codes+="$(field "$state/refused.json" 'd["status"]') "
  done
  check "refused-the-pipeline-$u" "$codes" test "$codes" = "403 403 403 "
done

# ------------------------------------------------------------------------------------ the pipeline
step "mysql fixture $db, with its heartbeat table; a marker row the console must not show"
marker="row-$(openssl rand -hex 12)"
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
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 2000)
  SELECT i, 1 + i % 200, ROUND(i * 1.25, 2), 'created', NULL, 1 FROM n;
INSERT INTO orders (id, customer_id, amount, state, note, version_no) VALUES (900001, 1, 1.00, 'created', '$marker', 1);
SQL
} >"$evidence/sql/setup.sql"
bash "$FIXTURES_DIR/sources/sources.sh" users >/dev/null
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/setup.sql" >/dev/null
ensure_rds_ca "$ns"
cat >"$evidence/status/source.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: $source_name, namespace: $ns}
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
bash "$cdc/writer.sh" start mysql "$db" "$writer"
kubectl apply -f "$evidence/status/source.yaml"
await_status source-ready 900 lakesource "$source_name" \
  "table('orders').get('state') == 'Ready' and table('customers').get('state') == 'Ready'" || true
laketable "$dwd_name" "$dwd" orders order_id "$ods.orders,$ods.customers" \
  "SELECT o.id AS order_id, o.customer_id, c.region, o.amount, o.state, o.version_no
FROM (SELECT *, PROCTIME() AS pt FROM $ods.orders) AS o
JOIN $ods.customers FOR SYSTEM_TIME AS OF o.pt AS c ON o.customer_id = c.id" "  governance: {owner: analytics, level: L2, retention: 7d}
  quality:
    notNull: [customer_id]
    allowed: {state: [created, paid, shipped]}
  deletionPolicy: Delete
  job: $small"
laketable "$dws_name" "$dws" segments region "$dwd.orders" \
  "SELECT region, COUNT(*) AS orders, SUM(amount) AS amount FROM $dwd.orders GROUP BY region" "  governance: {owner: analytics, level: L2, retention: 7d}
  deletionPolicy: Delete
  job: $small"
await_status dwd-running 900 laketable "$dwd_name" "st.get('phase') == 'Running'" || true
await_status dws-running 900 laketable "$dws_name" "st.get('phase') == 'Running'" || true
cat >"$evidence/status/dashboard.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeDashboard
metadata: {name: $dashboard, namespace: $ns}
spec:
  tables: [$dws.segments]
  governance: {owner: bi-team, level: L2, retention: 7d}
  queries:
    - {name: by_region, serve: external, sql: "SELECT region, orders FROM $dws.segments"}
    - {name: totals, serve: materialized, refresh: 1m, sql: "SELECT SUM(orders) AS orders, SUM(amount) AS amount FROM $dws.segments"}
YAML
kubectl apply -f "$evidence/status/dashboard.yaml"
await_status dashboard-ready 600 lakedashboard "$dashboard" "st.get('phase') == 'Ready'" || true
# ------------------------------------------------------------------- what the console shows of it
step "the console's pipeline against kubectl, Prometheus and Marquez"
# The measures arrive with the operator's rounds: the heartbeat's latency, each layer's lag, the
# freshness a query of DWS sees, the materialized view's age.
end=$((SECONDS + 900))
while ((SECONDS < end)); do
  console "$evidence/console/measured.json" && python3 - "$evidence/console/measured.json" "$n_source" "$n_dwd" "$t_dws" "$n_board" <<'PY' && break
import json, sys
nodes = {n["id"]: n.get("measures") or {} for n in json.load(open(sys.argv[1]))["nodes"]}
need = {sys.argv[2]: "sourceLatencySeconds", sys.argv[3]: "upstreamLagSeconds", sys.argv[4]: "freshnessSeconds",
        sys.argv[5]: "copyAgeSeconds"}
sys.exit(0 if all(m in nodes.get(n, {}) for n, m in need.items()) else 1)
PY
  sleep 20
done
# A task on DWS, as `colvenna maintain tag` makes it: the console shows it on the table it works on.
# Only once DWS has a snapshot: a tag names one, and `Running` does not mean committed yet.
end=$((SECONDS + 600))
while ((SECONDS < end)); do
  bash "$ACCEPTANCE_DIR/lake.sh" table "$state/dws-table.jsonl" "$dws.segments" >/dev/null 2>&1 \
    && python3 -c 'import json,sys; sys.exit(0 if json.loads(open(sys.argv[1]).readline()).get("snapshot") else 1)' \
      "$state/dws-table.jsonl" && break
  sleep 20
done
cat >"$evidence/status/task.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: $task, namespace: $ns}
spec:
  type: createTag
  table: {database: $dws, table: segments}
  createTag: {name: t-$run}
YAML
kubectl apply --server-side --field-manager=colvenna-cli -f "$evidence/status/task.yaml"
await_status task-ended 900 laketask "$task" "st.get('phase') in ('Succeeded', 'Failed')" || true
# Each attempt's log while its Job is there: it goes with the task.
for job in $(kubectl -n "$ns" get jobs -l "colvenna-lake.io/task=$task" -o name 2>/dev/null); do
  kubectl -n "$ns" logs "$job" -c task --tail=200 >"$evidence/status/${job#job.batch/}.log" 2>&1 || true
done

cat >"$state/edges.json" <<JSON
[["$n_source", "$t_orders"], ["$n_source", "$t_customers"], ["$t_orders", "$n_dwd"], ["$t_customers", "$n_dwd"],
 ["$n_dwd", "$t_dwd"], ["$n_dwd", "$t_quarantine"], ["$t_dwd", "$n_dws"], ["$n_dws", "$t_dws"], ["$t_dws", "$n_board"]]
JSON
cp "$state/edges.json" "$evidence/console/edges-expected.json"
agree path-source-to-dashboard 3 path "$state/edges.json"
agree state-is-kubectls 3 state
run_nodes=("$n_source" "$t_orders" "$t_customers" "$n_dwd" "$t_dwd" "$t_quarantine" "$n_dws" "$t_dws" "$n_board")
agree measures-are-prometheus 3 measures "$prom_url" "${run_nodes[@]}"
agree alerts-are-prometheus 3 alerts "${run_nodes[@]}"
cat >"$state/lineage.json" <<JSON
{"$n_source": ["$source_name-cdc"], "$n_dwd": ["$dwd_name-table"], "$n_dws": ["$dws_name-table"],
 "$n_board": ["LakeDashboard.$dashboard.by_region", "LakeDashboard.$dashboard.totals"]}
JSON
cp "$state/lineage.json" "$evidence/marquez/lineage-expected.json"
# How the operator names colvenna-catalog's tables in the lineage it reports.
lake="paimon://colvenna-catalog.$CATALOG_NAMESPACE.svc:8181"
# The operator reports lineage on its rounds; Marquez has it within a few minutes.
end=$((SECONDS + 300))
while ((SECONDS < end)); do
  console "$evidence/console/lineage.json" &&
    out="$(compare lineage "$evidence/console/lineage.json" "$MARQUEZ_URL" "colvenna-lake.$ns" "$lake" "$state/lineage.json" 2>&1)" && break
  sleep 20
done
check lineage-is-marquezs "${out:-no answer}" compare lineage "$evidence/console/lineage.json" "$MARQUEZ_URL" "colvenna-lake.$ns" "$lake" "$state/lineage.json"

# ------------------------------------------------------------------------- what it does not show
step "no Secret, password seal or row in what the console serves; only GET"
# Every value of the Secrets of the namespaces the console's picture comes from or sits near, the
# strings inside JSON ones and each line of long ones; the dashboard's password seals; the marker
# row. Each with where it is from: a match names that, never the value.
for space in "$ns" "$OPERATOR_NAMESPACE" "$CATALOG_NAMESPACE" "$BI_NAMESPACE" "$DORIS_NAMESPACE" "$CONSOLE_NAMESPACE"; do
  kubectl -n "$space" get secrets -o json 2>/dev/null
done | python3 -c '
import base64, json, sys
decoder, text, values = json.JSONDecoder(), sys.stdin.read(), {}
def leaves(v):
    if isinstance(v, dict):
        for x in v.values(): yield from leaves(x)
    elif isinstance(v, list):
        for x in v: yield from leaves(x)
    elif isinstance(v, str):
        yield v
i = 0
while i < len(text):
    if text[i].isspace():
        i += 1
        continue
    doc, i = decoder.raw_decode(text, i)
    for s in doc.get("items", []):
        for key, raw in (s.get("data") or {}).items():
            where = "%s/%s/%s" % (s["metadata"]["namespace"], s["metadata"]["name"], key)
            v = base64.b64decode(raw).decode("utf-8", "replace")
            found = {v} | {line.strip() for line in v.splitlines() if len(line.strip()) >= 16}
            try:
                found |= set(leaves(json.loads(v)))
            except ValueError:
                pass
            for x in found:
                values.setdefault(x, where)
for v, where in values.items():
    print(base64.b64encode(v.encode()).decode() + "\t" + where)' >"$state/values"
{
  kubectl -n "$ns" get lakedashboard "$dashboard" -o json | python3 -c '
import base64, json, sys
for user, v in ((json.load(sys.stdin).get("status") or {}).get("users") or {}).items():
    print(base64.b64encode(str(v).encode()).decode() + "\tthe password seal of " + user)'
  printf '%s\ta row of the source\n' "$(printf '%s' "$marker" | base64 -w0)"
  # The users' session cookies (what the load balancer turns into their tokens; HttpOnly ones are
  # kept with a prefix), and the identity the source's status names (the console leaves it out).
  for u in admin op; do
    awk -v u="$u" '{sub(/^#HttpOnly_/, "")} !/^#/ && NF >= 7 {print $7 "\t" u "'"'"'s cookie " $6}' "$state/$prefix-$u.cookies" |
      while IFS=$'\t' read -r value where; do
        printf '%s\t%s\n' "$(printf '%s' "$value" | base64 -w0)" "$where"
      done
  done
  kubectl -n "$ns" get lakesource "$source_name" -o json | python3 -c '
import base64, json, sys
for k, v in ((json.load(sys.stdin).get("status") or {}).get("identity") or {}).items():
    for x in (v if isinstance(v, list) else [v]):
        print(base64.b64encode(str(x).encode()).decode() + "\tthe source status identity " + k)'
} >>"$state/values"
pages=()
console "$state/page-api.json" && pages+=("$state/page-api.json")
entry get "$url/pipeline" "$state" "$prefix-op" --body-file "$state/page-pipeline.html" >/dev/null && pages+=("$state/page-pipeline.html")
i=0
for id in $(python3 -c 'import json,sys; print(" ".join(n["id"] for n in json.load(open(sys.argv[1]))["nodes"]))' "$state/page-api.json"); do
  i=$((i + 1))
  entry get "$url/pipeline/node?id=$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$id")" \
    "$state" "$prefix-op" --body-file "$state/page-node-$i.html" >/dev/null && pages+=("$state/page-node-$i.html")
done
cp "$state/page-api.json" "$evidence/console/served.json"
checked nothing-secret-or-of-the-data-is-served compare secrets "$state/values" "${pages[@]}"
rm -f "$state/values"
codes=""
for request in "POST /api/pipeline" "PUT /api/pipeline" "DELETE /api/pipeline" "PATCH /api/pipeline" \
  "POST /pipeline" "DELETE /pipeline/node?id=$n_dwd"; do
  read -r method path <<<"$request"
  entry get "$url$path" "$state" "$prefix-admin" --method "$method" --header "Origin:$url" >"$state/write.json"
  codes+="$method $(field "$state/write.json" 'd["status"]'); "
done
check only-get "$codes" python3 -c 'import sys; r=[p.split() for p in sys.argv[1].split(";") if p.strip()]; sys.exit(0 if len(r) == 6 and all(p[1] == "405" for p in r) else 1)' "$codes"

step "the console's identities"
sa="$(kubectl -n "$CONSOLE_NAMESPACE" get deployment "$CONSOLE_RELEASE" -o jsonpath='{.spec.template.spec.serviceAccountName}')"
who="system:serviceaccount:$CONSOLE_NAMESPACE:$sa"
kubectl auth can-i --list --as="$who" -n "$ns" >"$evidence/status/can-i.txt"
denied=""
for verb in create update patch delete deletecollection; do
  for resource in lakesources laketables lakedashboards laketasks flinkdeployments secrets pods; do
    [[ "$(kubectl auth can-i "$verb" "$resource" --as="$who" -n "$ns" 2>/dev/null)" == no ]] || denied+="$verb $resource; "
  done
done
for request in "get secrets" "get configmaps" "create pods/exec" "create pods/portforward" "get services/proxy"; do
  read -r verb resource <<<"$request"
  [[ "$(kubectl auth can-i "$verb" "$resource" --as="$who" -n "$ns" 2>/dev/null)" == no ]] || denied+="$request; "
done
reads=""
for resource in lakesources laketables lakedashboards laketasks flinkdeployments; do
  reads+="$(kubectl auth can-i list "$resource" --as="$who" -n "$ns" 2>/dev/null) "
done
# Elsewhere it may do nothing: not in the platform's other namespaces, not cluster-wide.
for space in "$OPERATOR_NAMESPACE" "$CATALOG_NAMESPACE" "$CONSOLE_NAMESPACE" default; do
  for request in "list laketables" "get secrets" "create pods" "patch deployments"; do
    read -r verb resource <<<"$request"
    [[ "$(kubectl auth can-i "$verb" "$resource" --as="$who" -n "$space" 2>/dev/null)" == no ]] || denied+="$request in $space; "
  done
done
for request in "list namespaces" "get nodes" "create clusterrolebindings" "impersonate users" "list secrets"; do
  read -r verb resource <<<"$request"
  [[ "$(kubectl auth can-i "$verb" "$resource" --as="$who" --all-namespaces 2>/dev/null)" == no ]] || denied+="$request cluster-wide; "
done
kubectl auth can-i --list --as="$who" -n "$OPERATOR_NAMESPACE" >"$evidence/status/can-i-elsewhere.txt"
# The rules it has: get and list on the five kinds, and impersonating its two users; the rest is
# what every authenticated identity may (reviewing its own access, discovery).
check kubernetes-identity-only-reads "list: $reads; allowed beyond: ${denied:-none}" python3 - \
  "$evidence/status/can-i.txt" "$evidence/status/can-i-elsewhere.txt" "$reads|${denied}" <<'PY'
import re, sys
def rules(path):
    out = []
    for line in open(path).read().splitlines()[1:]:
        cells = re.findall(r"\[[^\]]*\]|\S+", line)
        if cells and not cells[0].startswith("["):
            verbs = set(cells[-1].strip("[]").split())
            names = cells[-2].strip("[]") if len(cells) >= 4 else ""
            if verbs == {"impersonate"} and (cells[0], names) in acts_as:
                continue
            out.append((cells[0], verbs))
    return out
own = {"selfsubjectreviews.authentication.k8s.io", "selfsubjectaccessreviews.authorization.k8s.io",
       "selfsubjectrulesreviews.authorization.k8s.io"}
# Its operations (#138) impersonate its two users, with the person attached; nothing else.
acts_as = {("users", "colvenna-console:admin"), ("users", "colvenna-console:operator"),
           ("userextras.authentication.k8s.io/colvenna-lake.io/person", ""),
           ("userextras.authentication.k8s.io/colvenna-lake.io/sub", "")}
reads = {"lakesources.colvenna-lake.io", "laketables.colvenna-lake.io", "lakedashboards.colvenna-lake.io",
         "laketasks.colvenna-lake.io", "flinkdeployments.flink.apache.org"}
here, elsewhere = rules(sys.argv[1]), rules(sys.argv[2])
ok = (all((r in reads and v <= {"get", "list"}) or (r in own and v == {"create"}) for r, v in here)
      and reads <= {r for r, _ in here} and all(r in own for r, _ in elsewhere)
      and sys.argv[3] == "yes yes yes yes yes |")
sys.exit(0 if ok else 1)
PY
# Writing with the console's own token: a short-lived one for its service account.
kubectl -n "$CONSOLE_NAMESPACE" create token "$sa" --duration=10m >"$state/sa.token"
server="$(kubectl config view --minify --raw -o jsonpath='{.clusters[0].cluster.server}')"
kubectl config view --minify --raw -o jsonpath='{.clusters[0].cluster.certificate-authority-data}' | base64 -d >"$state/ca.crt"
# The token goes in a file, not on a command line.
cat >"$state/sa.kubeconfig" <<YAML
apiVersion: v1
kind: Config
clusters: [{name: c, cluster: {server: "$server", certificate-authority: "$state/ca.crt"}}]
users: [{name: sa, user: {token: "$(cat "$state/sa.token")"}}]
contexts: [{name: c, context: {cluster: c, user: sa}}]
current-context: c
YAML
as_console() { KUBECONFIG="$state/sa.kubeconfig" kubectl -n "$ns" "$@" 2>&1; }
{
  echo "get laketable: $(as_console get laketable "$dwd_name" -o name)"
  echo "patch laketable: $(as_console patch laketable "$dwd_name" --type merge -p '{"spec":{"suspend":true}}')"
  echo "delete lakedashboard: $(as_console delete lakedashboard "$dashboard" --wait=false)"
  echo "create laketask: $(printf '{"apiVersion":"colvenna-lake.io/v1alpha1","kind":"LakeTask","metadata":{"name":"console-%s"},"spec":{"type":"compact","table":"%s.orders"}}' "$run" "$dwd" | as_console create -f -)"
  echo "get secret: $(as_console get secret mysql-cdc -o name)"
} >"$evidence/status/as-console.txt"
check kubernetes-refuses-the-consoles-writes "$(tr '\n' ';' <"$evidence/status/as-console.txt" | cut -c1-400)" python3 - "$evidence/status/as-console.txt" <<'PY'
import sys
lines = dict(l.rstrip("\n").split(": ", 1) for l in open(sys.argv[1]) if ": " in l)
ok = lines["get laketable"].startswith("laketable") and all(
    "forbidden" in lines[k].lower() for k in ("patch laketable", "delete lakedashboard", "create laketask", "get secret"))
sys.exit(0 if ok else 1)
PY
kubectl -n "$ns" get laketable "$dwd_name" -o jsonpath='{.spec.suspend}' >"$state/suspend"
check the-write-did-not-happen "suspend: $(cat "$state/suspend")" test "$(cat "$state/suspend")" != true
# colvenna-catalog: the console holds no identity of it (no Secret mounted or in its environment but
# its operations' confirmation key, #138), its namespace may not reach it, and its service-account
# token is not a colvenna-catalog token.
kubectl -n "$CONSOLE_NAMESPACE" get deployment "$CONSOLE_RELEASE" -o json >"$evidence/status/console-deployment.json"
check console-holds-no-catalog-identity "$(kubectl get namespace "$CONSOLE_NAMESPACE" -o jsonpath='{.metadata.labels}')" python3 - \
  "$evidence/status/console-deployment.json" "$(kubectl get namespace "$CONSOLE_NAMESPACE" -o jsonpath='{.metadata.labels.colvenna-lake\.io/catalog-client}')" <<'PY'
import json, sys
spec = json.load(open(sys.argv[1]))["spec"]["template"]["spec"]
volumes = [v for v in spec.get("volumes", [])
           if ("secret" in v and v["secret"].get("secretName") != "colvenna-console-confirm")
           or any("secret" in s for s in (v.get("projected") or {}).get("sources", []))]
env = [e for c in spec["containers"] + spec.get("initContainers", []) for e in c.get("env", [])
       if "secretKeyRef" in (e.get("valueFrom") or {})]
env_from = [e for c in spec["containers"] for e in c.get("envFrom", []) if "secretRef" in e]
print(volumes, env, env_from)
sys.exit(0 if not (volumes or env or env_from) and sys.argv[2] != "true" else 1)
PY
# AWS: the pod's role writes its own audit and signs users out, nothing of the platform.
role="$(kubectl -n "$CONSOLE_NAMESPACE" get serviceaccount "$sa" -o jsonpath='{.metadata.annotations.eks\.amazonaws\.com/role-arn}')"
role="${role##*/}"
{
  for policy in $(aws iam list-role-policies --role-name "$role" --query 'PolicyNames[]' --output text); do
    aws iam get-role-policy --role-name "$role" --policy-name "$policy" --query PolicyDocument --output json
  done
  echo "attached $(aws iam list-attached-role-policies --role-name "$role" --query 'length(AttachedPolicies)' --output text)"
} >"$evidence/status/console-role.txt"
check aws-identity-only-its-own-audit-and-sign-out "$role: $(grep -o '"[a-z-]*:[A-Za-z]*"' "$evidence/status/console-role.txt" | sort -u | tr '\n' ' ')" \
  python3 - "$evidence/status/console-role.txt" "$audit_group" "$pool" <<'PY'
import json, sys
text = open(sys.argv[1]).read()
docs, rest = [], text
decoder = json.JSONDecoder()
while rest.lstrip().startswith("{"):
    doc, end = decoder.raw_decode(rest.lstrip())
    docs.append(doc)
    rest = rest.lstrip()[end:]
statements = [s for d in docs for s in d["Statement"]]
def listed(v):
    return v if isinstance(v, list) else [v]
allowed = {"logs:CreateLogStream", "logs:PutLogEvents", "cognito-idp:AdminUserGlobalSignOut"}
ok = statements and rest.strip() == "attached 0"
for s in statements:
    actions, resources = set(listed(s["Action"])), listed(s["Resource"])
    ok = ok and s["Effect"] == "Allow" and actions <= allowed and "*" not in resources
    if any(a.startswith("logs:") for a in actions):
        ok = ok and all(sys.argv[2] in r for r in resources)
    else:
        ok = ok and all(r.endswith("userpool/" + sys.argv[3]) for r in resources)
sys.exit(0 if ok else 1)
PY
forward "$CATALOG_NAMESPACE" colvenna-catalog 8181 catalog_port s
printf 'Authorization: Bearer %s\n' "$(cat "$state/sa.token")" >"$state/sa.header"
catalog="GET /v1/config $(curl -sk -o /dev/null -w '%{http_code}' -H @"$state/sa.header" "https://127.0.0.1:$catalog_port/v1/config"); "
catalog+="POST /v1/colvenna/databases $(curl -sk -o /dev/null -w '%{http_code}' -X POST -H @"$state/sa.header" \
  -H 'Content-Type: application/json' --data "{\"name\":\"console_$run\"}" "https://127.0.0.1:$catalog_port/v1/colvenna/databases"); "
rm -f "$state/sa.header"
check catalog-refuses-the-consoles-token "$catalog" test "$catalog" = "GET /v1/config 401; POST /v1/colvenna/databases 401; "

step "from the console's namespace, and from pods or namespaces without the label"
# probe <namespace> <file> [<pod-labels>]: what a pod there reaches, one request per line.
probe() {
  local space="$1" file="$2"
  cat >"$state/probe.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: pipeline-probe-$run, namespace: $space, labels: {${3:-}}}
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
          p=http://$MONITORING_RELEASE-prometheus.$MONITORING_NAMESPACE.svc:9090
          for request in "GET \$p/api/v1/query?query=up" "POST \$p/api/v1/admin/tsdb/snapshot" "POST \$p/api/v1/admin/tsdb/delete_series?match[]=up" \\
                         "POST \$p/-/reload" "POST \$p/-/quit" "POST \$p/api/v1/write" "GET https://colvenna-catalog.$CATALOG_NAMESPACE.svc:8181/v1/config"; do
            set -- \$request
            rm -f /tmp/body
            out="\$(curl -sk -g -o /tmp/body -m 10 -X "\$1" -w '%{http_code} %{time_connect}' "\$2")"
            echo "\$1 \${2#*svc:*/} \$out \$? \$(head -c 120 /tmp/body 2>/dev/null | tr -s ' \n' '__')"
          done
      resources: {requests: {cpu: 10m, memory: 32Mi}, limits: {memory: 64Mi}}
YAML
  kubectl apply -f "$state/probe.yaml" >/dev/null
  kubectl -n "$space" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/pipeline-probe-$run" --timeout=5m >/dev/null || true
  kubectl -n "$space" logs "pipeline-probe-$run" >"$file" 2>&1 || true
  kubectl -n "$space" delete pod "pipeline-probe-$run" --ignore-not-found >/dev/null
}
reader="$(helm -n "$MONITORING_NAMESPACE" get values "$MONITORING_RELEASE" --all -o json | python3 -c 'import json,sys; print(json.load(sys.stdin)["prometheus"]["readerLabel"])')"
# Labelled like the console's pods.
probe "$CONSOLE_NAMESPACE" "$evidence/http/from-console-namespace.txt" "$reader: \"true\""
check prometheus-reads-only-from-the-consoles-namespace "$(tr '\n' ';' <"$evidence/http/from-console-namespace.txt")" python3 - "$evidence/http/from-console-namespace.txt" <<'PY'
import sys
lines = [l.split() for l in open(sys.argv[1]) if l.strip()]
# Query 200; admin, lifecycle and remote write refused, each as not enabled (Prometheus answers
# 500 "admin APIs disabled", 403 "Lifecycle API is not enabled", 404); the catalog: no connection
# (curl timed out, 28).
said = [" ".join(l[5:]) for l in lines]
ok = (len(lines) == 7 and lines[0][2] == "200"
      and all(l[2] == "500" and "admin_APIs_disabled" in s for l, s in zip(lines[1:3], said[1:3]))
      and all(l[2] == "403" and "Lifecycle_API_is_not_enabled" in s for l, s in zip(lines[3:5], said[3:5]))
      and lines[5][2] == "404" and lines[6][2:5] == ["000", "0.000000", "28"])
sys.exit(0 if ok else 1)
PY
# The same label in a namespace without it; no label in the console's namespace.
probe "$ns" "$evidence/http/from-job-namespace.txt" "$reader: \"true\""
probe "$CONSOLE_NAMESPACE" "$evidence/http/unlabelled-in-console-namespace.txt"
check prometheus-unreachable-without-the-labels "$(head -1 "$evidence/http/from-job-namespace.txt"); $(head -1 "$evidence/http/unlabelled-in-console-namespace.txt")" \
  python3 - "$evidence/http/from-job-namespace.txt" "$evidence/http/unlabelled-in-console-namespace.txt" <<'PY'
import sys
firsts = [[l.split() for l in open(f) if l.strip()][:1] for f in sys.argv[1:]]
sys.exit(0 if all(f and f[0][2:5] == ["000", "0.000000", "28"] for f in firsts) else 1)
PY

# ----------------------------------------------------------------------------------------- faults
step "orders in an unknown state: DWD quarantines them"
cat >"$state/bad.sql" <<SQL
INSERT INTO $db.orders (id, customer_id, amount, state, note, version_no) VALUES
  (910001, 3, 5.00, 'lost', NULL, 1), (910002, 4, 6.00, 'lost', NULL, 1), (910003, 5, 7.00, 'lost', NULL, 1);
SQL
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$state/bad.sql" >/dev/null
await_located quarantine-located 600 ColvennaRowsQuarantined "laketable=$dwd_name" "$n_dwd" \
  "$t_dwd,$t_quarantine,$n_dws,$t_dws,$n_board" "$n_source,$t_orders,$t_customers" || true
agree alerts-are-prometheus-during-a-fault 3 alerts "${run_nodes[@]}"

step "DWD suspended past the p99 target"
kubectl -n "$ns" patch laketable "$dwd_name" --type merge -p '{"spec":{"suspend":true}}'
await_located layer-lag-located 900 ColvennaLayerLagP99 "laketable=$dwd_name" "$n_dwd" \
  "$t_dwd,$t_quarantine,$n_dws,$t_dws,$n_board" "$n_source,$t_orders,$t_customers" || true
agree alerts-are-prometheus-while-dwd-lags 3 alerts "${run_nodes[@]}"
kubectl -n "$ns" patch laketable "$dwd_name" --type merge -p '{"spec":{"suspend":false}}'
await_cleared layer-lag-cleared 900 ColvennaLayerLagP99 "laketable=$dwd_name" "$n_dwd" || true

step "the source suspended past the p99 target"
kubectl -n "$ns" patch lakesource "$source_name" --type merge -p '{"spec":{"suspend":true}}'
await_located source-stale-located 900 ColvennaSourceStale "source=$source_name" "$n_source" \
  "$t_orders,$t_customers,$n_dwd,$t_dwd,$n_dws,$t_dws,$n_board" "" || true
agree alerts-are-prometheus-while-the-source-is-stale 3 alerts "${run_nodes[@]}"
kubectl -n "$ns" patch lakesource "$source_name" --type merge -p '{"spec":{"suspend":false}}'
await_cleared source-stale-cleared 900 ColvennaSourceStale "source=$source_name" "$n_source" || true

# The binlog writer only writes: the ODS tables' level-0 files pile up until their owner compacts
# them, and past 30 minutes ColvennaCompactionBacklog fires on the source. Compacted as an owner would.
step "the ODS tables compacted, as their owner would"
for t in orders customers colvenna_heartbeat; do
  cat <<YAML | kubectl apply --server-side --field-manager=colvenna-cli -f -
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: $task-c-${t//_/-}, namespace: $ns}
spec:
  type: compact
  table: {database: $ods, table: $t}
  compact: {full: true}
YAML
done

step "every alert of the run resolves: nothing stays marked"
# The quarantine's alert holds 15 minutes after the newest bad row, failed checkpoints 15 minutes,
# the source's p99 10 minutes.
end=$((SECONDS + 1800)) started=$SECONDS clear=""
while ((SECONDS < end)); do
  if console "$evidence/console/all-clear.json" && python3 - "$evidence/console/all-clear.json" "${run_nodes[@]}" <<'PY'; then
import json, sys
nodes = {n["id"]: n for n in json.load(open(sys.argv[1]))["nodes"]}
ids = set(sys.argv[2:])
# Nothing fires for the run's nodes any more (the next check holds the console's alerts to
# Prometheus's), and the console marks nothing.
firing = [n for n in ids for a in nodes.get(n, {}).get("alerts") or [] if a["state"] == "firing"]
marked = [i for i in ids if nodes.get(i, {}).get("problem") or nodes.get(i, {}).get("affected")]
sys.exit(0 if not firing and not marked else 1)
PY
    clear=$((SECONDS - started))
    break
  fi
  sleep 30
done
check nothing-marked-once-every-alert-resolves "${clear:-not}${clear:+s after the source alert cleared}" test -n "$clear"
agree alerts-are-prometheus-once-resolved 3 alerts "${run_nodes[@]}"

# ------------------------------------------------------------------------------------------ audit
step "audit"
# audit_complete <file>: the operator's and the administrator's requests, the BI user's refusal and
# the writes refused (405) are there; with --print, what is there.
audit_complete() {
  python3 - "$@" <<'PY'
import collections, json, sys
events = [json.loads(m) for m in json.load(open(sys.argv[1]))]
of = [e for e in events if "/pipeline" in e.get("path", "")]
seen = {(e.get("username", "").rsplit("-", 1)[-1], e.get("kind")) for e in of}
if sys.argv[2:] == ["--print"]:
    print(dict(collections.Counter((e.get("username", "").rsplit("-", 1)[-1], e.get("kind"), e.get("method"), e.get("status")) for e in of)))
    sys.exit(0)
writes = [e for e in of if e.get("method") != "GET"]
sys.exit(0 if {("op", "request"), ("admin", "request"), ("bi", "denied")} <= seen and writes else 1)
PY
}
for _ in $(seq 1 20); do
  aws logs filter-log-events --log-group-name "$audit_group" --start-time "$started_ms" \
    --filter-pattern "\"$prefix\"" --query 'events[].message' --output json >"$evidence/audit/console.json"
  audit_complete "$evidence/audit/console.json" && break
  sleep 30
done
check audit-has-who-looked-and-who-was-refused "$(audit_complete "$evidence/audit/console.json" --print)" \
  audit_complete "$evidence/audit/console.json"

# ----------------------------------------------------------------------------------------- the end
step "writer stop: $(bash "$cdc/writer.sh" stop "$writer")"
step "delete the task, the dashboard, the tables and the source"
kubectl -n "$ns" delete laketask "$task" "$task-c-orders" "$task-c-customers" "$task-c-colvenna-heartbeat" --timeout=10m
kubectl -n "$ns" delete lakedashboard "$dashboard" --timeout=10m
kubectl -n "$ns" delete laketable "$dws_name" "$dwd_name" --timeout=10m
kubectl -n "$ns" delete lakesource "$source_name" --timeout=10m
python3 "$cdc/mysql_contract.py" reset "$db" >"$evidence/sql/reset.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/reset.sql" >/dev/null
console "$evidence/console/after.json" || true
check gone-from-the-console "$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["nodes"]))' "$evidence/console/after.json") nodes" \
  python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); ids={n["id"] for n in d["nodes"]}; sys.exit(0 if not ids & set(sys.argv[2:]) else 1)' \
  "$evidence/console/after.json" "$n_source" "$n_dwd" "$n_dws" "$n_board"
echo "took $((SECONDS - began))s"
echo "verdicts: $(grep -c $'\tPASS\t' "$evidence/verdicts.tsv") PASS, $(grep -c $'\tFAIL\t' "$evidence/verdicts.tsv") FAIL"
exit "$failed"
