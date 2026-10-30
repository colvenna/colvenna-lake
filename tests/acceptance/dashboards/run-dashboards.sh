#!/usr/bin/env bash
# Acceptance on EKS: LakeDashboards served by the platform's Doris (install-doris.sh), under
# continuous source writes. Writes PASS/FAIL lines to <evidence>/verdicts.tsv and fails if any check
# fails.
#
# A MySQL LakeSource feeds ODS; DWD (orders with their customer's region) and DWS (orders by region,
# with the markers among them) are LakeTables. The dashboard serves, from them:
#   region_totals  materialized  DWS's rows                        (the fixed dashboard query)
#   marker_mv      materialized  the markers in ODS                (Paimon to the copy)
#   marker_live    external      the same, read at query time      (source and Paimon to Doris)
#   marker_dws     materialized  the markers DWS counted           (source to the DWS dashboard)
#   order_lookup   external      DWD's orders, by key              (the interactive query)
#   customers_hot  internal      ODS customers, Unique Key by id   (a hot dimension)
#   marker_hot     internal      the markers in ODS                (Paimon to the internal table)
# to analyst (the external and materialized ones), viewer (region_totals) and ops (the internal
# ones). A second, ad-hoc dashboard serves one heavy query to four users, at most 2 at once. Then:
# - access: each user reads what it is given and is refused the rest: the catalog, the query log,
#   Doris's information_schema and mysql, the other dashboard; root and admin without a password,
#   and a wrong password, are refused; only namespaces declared Doris clients reach Doris; what
#   Doris still shows every user (tasks(), SHOW BACKENDS, a view's definition) is recorded;
# - maintenance: ODS's writer does not compact, so every 10 minutes a full compaction of each ODS
#   table is requested (LakeTask, as an orchestrator would): a merge read opens every
#   level-0 file of a bucket at once, and without compactions the reads of ODS stall within the
#   hour (run d2: past about 300 files the BE's S3 connections ran out);
# - markers: a pod commits one order every tenth of a second (ids from 10,000,000) and polls each
#   path once a second; each marker's first sight on each path, from its source commit and from
#   Paimon's commit of it (LakeDump changelog), is a freshness sample (the acceptance contract's
#   2.1), with the clocks involved measured (MySQL's, the marker pod's, the Flink pods'); the
#   backlog must not grow;
# - load (PERF=1 only; without it, a short burst alone): LOAD_MINUTES (40, at least the
#   acceptance contract's 30 of steady state) of 10 fixed
#   dashboard queries a second, 5 internal-table lookups and 1 interactive lookup, open-loop; for
#   BURST_MINUTES (20) in the middle the ad-hoc users run their heavy query on three connections
#   each, two of them from Doris's normal group: the dashboard keeps its targets, each group and
#   user its limit;
# - recovery: the BE, then the FE, deleted: from the injection, until every path answers again with
#   data written after it, lagging within its steady-state p95, and the internal table is
#   rewritten (the acceptance contract's RTO, 30 minutes);
# - with the writers stopped: region_totals equals DWS, customers_hot ODS customers, order_lookup
#   DWD, row by row;
# - the query log holds each user's queries of each path and its refusals; it keeps a query 7 days,
#   and what refreshes replace stays in Doris's storage a day;
# - deletion: the users, the databases and the catalogs gone, the dashboards' tokens refused.
#
# usage: run-dashboards.sh <run-id> <evidence-dir>
# With PERF=1, PERF_EXTRA=<script> runs beside the markers and the load: "<script> start <run>
# <evidence> <db> <ods.orders> <dwd.orders> <dws.segments> <marker-base>" as the markers start,
# "<script> stop <run> <evidence>" once the load is done and drained, in place of the LakeDump
# changelogs of ODS (the script writes <evidence>/release/ods-orders.jsonl, LakeDump's form), and
# "<script> cleanup <run>" on the way out (the release performance run's tests/performance/release.sh).
# A start that fails stops the run there, with its exit status.
# DASHBOARDS_JOB replaces the source's and the tables' job resources (a YAML flow mapping);
# DASHBOARDS_DWS_EXTRA adds columns to DWS's query (", <expression> AS <name>...", over DWD's orders).
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-dashboards.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-dashboards.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
# PERF=1 adds the performance parts: LOAD_MINUTES of load with a BURST_MINUTES burst in its middle,
# the dashboard's targets under them, and the paths' freshness and steady state. Without it (the
# default; the UAT runs so) a short burst alone checks the groups' and users' limits.
perf="${PERF:-0}"
if [[ "$perf" == 1 ]]; then
  load_minutes="${LOAD_MINUTES:-40}" burst_minutes="${BURST_MINUTES:-20}" drain_minutes="${DRAIN_MINUTES:-10}"
  ((load_minutes >= 30)) || { echo "LOAD_MINUTES: the acceptance contract's steady state is 30 minutes at least" >&2; exit 1; }
else
  load_minutes=0 burst_minutes="${BURST_MINUTES:-3}" drain_minutes="${DRAIN_MINUTES:-2}"
fi
perf_extra="${PERF_EXTRA:-}"
if [[ -n "$perf_extra" && "$perf" != 1 ]]; then
  echo "PERF_EXTRA runs beside the load: PERF=1 only" >&2
  exit 1
fi
# Markers from the start of the load on are samples; the first minutes of markers settle the paths.
warmup_minutes=3 base=10000000
sha="$(committed_sha)"
run_from="$(date +%s%3N)"
flows="$REPO_DIR/tests/acceptance"
cdc="$flows/cdc"
here="$flows/dashboards"
mkdir -p "$evidence"/{status,dumps,sql,doris}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
for image in operator mysql sql; do
  echo "$image $(image_ref "$image")" | tee -a "$evidence/images.txt"
done
echo "query-engine-fe $DORIS_FE_IMAGE" | tee -a "$evidence/images.txt"
echo "query-engine-be $DORIS_BE_IMAGE" | tee -a "$evidence/images.txt"
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

# await <kind> <name> <label> <timeout-seconds> <expression>
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
  local file="$evidence/$1.yaml" upstream="" u
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
maintainer=""
trap 'if [[ -n "$maintainer" ]]; then kill "$maintainer" 2>/dev/null || true; fi; if [[ -n "$perf_extra" ]]; then bash "$perf_extra" cleanup "$run" >/dev/null 2>&1 || true; fi; rm -rf "$work"' EXIT
bi="colvenna-bi-client"
fe="$DORIS_CLUSTER-fe-service.$DORIS_NAMESPACE.svc"

# doris_as <namespace> <user> <password-file> <sql-file> <out>: each line of the file as one
# statement of that user, from a pod in that namespace: "-- <statement>" then its rows, or
# "ERROR <message>".
doris_as() {
  local ns="$1" user="$2" pod="doris-as-$RANDOM"
  kubectl -n "$ns" create secret generic "$pod" --from-file=password="$3" --from-file=run.sql="$4" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  cat >"$work/$pod.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $pod, namespace: $ns}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 999, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: mysql
      image: $MYSQL_CLIENT_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["bash", "-c"]
      args:
        - |
          export MYSQL_PWD="\$(cat /in/password)"
          while IFS= read -r q; do
            [[ -z "\$q" ]] && continue
            echo "-- \$q"
            mysql -h $fe -P 9030 -u '$user' --connect-timeout=10 --batch -e "\$q" 2>&1 \
              | sed 's/^ERROR [0-9]* ([A-Z0-9]*)[^:]*: /ERROR /'
          done < /in/run.sql
      resources: {requests: {cpu: 10m, memory: 64Mi}, limits: {memory: 128Mi}}
      volumeMounts: [{name: in, mountPath: /in, readOnly: true}]
  volumes: [{name: in, secret: {secretName: $pod}}]
YAML
  run_pod "$ns" "$pod" "$work/$pod.yaml" >"$5" || true
  kubectl -n "$ns" delete secret "$pod" --ignore-not-found >/dev/null
}

# outcome <file> <statement>: OK or the ERROR line of that statement in a doris_as output.
outcome() {
  python3 - "$1" "$2" <<'PY'
import sys
lines = open(sys.argv[1]).read().splitlines()
want = "-- " + sys.argv[2]
for i, line in enumerate(lines):
    if line == want:
        following = lines[i + 1] if i + 1 < len(lines) else ""
        print(following if following.startswith("ERROR") else "OK")
        break
else:
    print("MISSING")
PY
}

small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
small="${DASHBOARDS_JOB:-$small}"
ensure_rds_ca "$JOB_NAMESPACE"
bash "$FIXTURES_DIR/sources/sources.sh" users >/dev/null
kubectl -n "$DORIS_NAMESPACE" get doriscluster "$DORIS_CLUSTER" >/dev/null \
  || { echo "no Doris: run deploy/scripts/doris/install-doris.sh first" >&2; exit 1; }
kubectl create namespace "$bi" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label namespace "$bi" --overwrite pod-security.kubernetes.io/enforce=restricted \
  colvenna-lake.io/doris-client=true >/dev/null

ensure_rds_ca "$bi"

# bi_pod <name> <script> <cpu> <NAME=value>...: a pod in the client namespace running
# dashboards/<script>, the Secret <name> (made by the caller) at /in, the RDS CA at /ca.
bi_pod() {
  local name="$1" script="$2" cpu="$3" env="" kv
  shift 3
  for kv in "$@"; do
    env+="        - {name: ${kv%%=*}, value: \"${kv#*=}\"}"$'\n'
  done
  kubectl -n "$bi" create configmap "$name" --from-file=run.sh="$here/$script" \
    --from-file=lib.sh="$here/pod-lib.sh" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl -n "$bi" delete pod "$name" --ignore-not-found --wait=true >/dev/null
  kubectl -n "$bi" apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $name, namespace: $bi}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  terminationGracePeriodSeconds: 5
  securityContext: {runAsNonRoot: true, runAsUser: 999, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: run
      image: $MYSQL_CLIENT_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["bash", "/script/run.sh"]
      env:
${env%$'\n'}
      resources: {requests: {cpu: $cpu, memory: 256Mi}, limits: {memory: 512Mi}}
      volumeMounts:
        - {name: in, mountPath: /in, readOnly: true}
        - {name: script, mountPath: /script, readOnly: true}
        - {name: ca, mountPath: /ca, readOnly: true}
  volumes:
    - {name: in, secret: {secretName: $name}}
    - {name: script, configMap: {name: $name}}
    - {name: ca, configMap: {name: rds-ca}}
YAML
  kubectl -n "$bi" wait --for=condition=Ready "pod/$name" --timeout=180s >/dev/null
}

# bi_secret <name> <user>...: the users' passwords, one key each, for a bi_pod.
bi_secret() {
  local name="$1" args=() user
  shift
  for user in "$@"; do args+=(--from-file="$user=$work/$user"); done
  kubectl -n "$bi" create secret generic "$name" "${args[@]}" --dry-run=client -o yaml \
    | kubectl apply -f - >/dev/null
}

# json_field <file> <python expression over s, the status>
json_field() {
  python3 -c 'import json,sys; s=json.load(open(sys.argv[1])).get("status") or {}; print(eval(sys.argv[2]))' "$1" "$2"
}

# ------------------------------------------------------------------------- the lake
db="dash_$run" ods="ods_dash_$run" dwd="dwd_dash_$run" dws="dws_dash_$run"
source_name="dash-$run" dwd_name="dash-dwd-$run" dws_name="dash-dws-$run"
board="board-$run" adhoc="adhoc-$run" marker_user="mark_$run"
# teardown: the tables, the source and the run's pods, users and fixture.
# teardown-status holds 1 until the teardown has run through (any step failing stops the run):
# the release performance run reads it.
teardown() {
  echo 1 >"$evidence/teardown-status"
  if [[ -n "$maintainer" ]]; then kill "$maintainer" 2>/dev/null || true; maintainer=""; fi
  kubectl -n "$JOB_NAMESPACE" get laketask -l "colvenna-lake.io/run=dash-$run" -o json \
    >"$evidence/status/compactions.json" 2>/dev/null || true
  kubectl -n "$JOB_NAMESPACE" delete laketable "$dws_name" "$dwd_name" --timeout=10m
  kubectl -n "$JOB_NAMESPACE" delete lakesource "$source_name" --timeout=10m
  for user in analyst viewer ops; do kubectl -n "$JOB_NAMESPACE" delete secret "$board-$user" --ignore-not-found >/dev/null; done
  for user in explorer scout escapee runaway; do kubectl -n "$JOB_NAMESPACE" delete secret "$adhoc-$user" --ignore-not-found >/dev/null; done
  kubectl -n "$JOB_NAMESPACE" delete laketask -l "colvenna-lake.io/run=dash-$run" --timeout=10m >/dev/null || true
  kubectl -n "$DORIS_NAMESPACE" delete pod "groups-$run" --ignore-not-found >/dev/null
  for pod in marks load burst; do
    kubectl -n "$bi" delete pod "$pod-$run" --ignore-not-found >/dev/null
    kubectl -n "$bi" delete secret "$pod-$run" --ignore-not-found >/dev/null
    kubectl -n "$bi" delete configmap "$pod-$run" --ignore-not-found >/dev/null
  done
  {
    echo "DROP USER IF EXISTS '$marker_user'@'%';"
    python3 "$cdc/mysql_contract.py" reset "$db"
  } >"$evidence/sql/reset.sql"
  bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/reset.sql" >/dev/null
  echo 0 >"$evidence/teardown-status"
}

# groups_sampler <pod> <seconds>: from the platform's admin client, the FE's own count of each
# workload group's running and waiting queries (SHOW WORKLOAD GROUPS), twice a second:
# "<ms> <group> <running> <waiting>". The audit log cannot tell: a query's logged time includes
# the FE's planning, which for an external table reads the catalog before the queue.
groups_sampler() {
  cat >"$work/$1.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $1, namespace: $DORIS_NAMESPACE, labels: {colvenna-lake.io/doris-admin: "true"}}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 999, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: mysql
      image: $MYSQL_CLIENT_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["bash", "-c"]
      args:
        - |
          export MYSQL_PWD="\$(cat /admin/password)"
          end=\$((\$(date +%s) + $2))
          while [ "\$(date +%s)" -lt "\$end" ]; do
            now=\$(date +%s%3N)
            mysql -h $DORIS_CLUSTER-fe-service -P 9030 -uroot --batch -e "SHOW WORKLOAD GROUPS" 2>&1 \\
              | awk -F'\t' -v t="\$now" 'NR == 1 { for (i = 1; i <= NF; i++) c[\$i] = i; next }
                  { print t, \$c["Name"], \$c["running_query_num"], \$c["waiting_query_num"] }'
            sleep 0.5
          done
      volumeMounts: [{name: admin, mountPath: /admin, readOnly: true}]
      resources: {requests: {cpu: 10m, memory: 32Mi}, limits: {memory: 128Mi}}
  volumes: [{name: admin, secret: {secretName: $DORIS_ADMIN_SECRET}}]
YAML
  kubectl apply -f "$work/$1.yaml" >/dev/null
}

# sampling <pod>: waits, three minutes at most, for the sampler's first count. Its pod can take tens
# of seconds to start, and the burst's window must open only once the FE's counts are being taken,
# so that they cover it from its start (#293).
sampling() {
  local _
  for _ in $(seq 90); do
    grep -qE '^[0-9]+ [^ ]+ [0-9]+ [0-9]+$' <<<"$(kubectl -n "$DORIS_NAMESPACE" logs "$1" 2>/dev/null)" && return 0
    sleep 2
  done
  step "the sampler $1 counted nothing in 3 minutes: the burst goes on, and adhoc-bounded says so"
}

# maintain: every 10 minutes, a full compaction of each ODS table, until killed or the harness
# is gone.
maintain() {
  local n=0 table harness=$$
  while kill -0 "$harness" 2>/dev/null; do
    n=$((n + 1))
    for table in orders customers; do
      kubectl apply -f - >/dev/null 2>&1 <<YAML || true
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: dash-compact-$table-$run-$n, namespace: $JOB_NAMESPACE, labels: {colvenna-lake.io/run: dash-$run}}
spec:
  type: compact
  table: {database: $ods, table: $table}
  compact: {full: true}
YAML
    done
    sleep 600
  done
}

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
# The marker writer may insert and read orders, nothing else; its password stays out of the
# evidence.
openssl rand -hex 16 | tr -d '\n' >"$work/marker"
cat >"$work/marker-user.sql" <<SQL
CREATE USER IF NOT EXISTS '$marker_user'@'%' IDENTIFIED WITH caching_sha2_password BY '$(cat "$work/marker")' REQUIRE SSL;
ALTER USER '$marker_user'@'%' IDENTIFIED WITH caching_sha2_password BY '$(cat "$work/marker")' REQUIRE SSL;
GRANT SELECT, INSERT ON \`$db\`.\`orders\` TO '$marker_user'@'%';
SQL
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$work/marker-user.sql" >/dev/null
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
bash "$cdc/writer.sh" start mysql "$db" "dw-$run"
kubectl apply -f "$evidence/source.yaml"
await lakesource "$source_name" source-ready 900 \
  "state('orders') == 'Ready' and state('customers') == 'Ready' and state('colvenna_heartbeat') == 'Ready' and live_all()" || true
maintain &
maintainer=$!
dwd_query="SELECT o.id AS order_id, o.customer_id, c.region, o.amount, o.state, o.version_no
FROM (SELECT *, PROCTIME() AS pt FROM $ods.orders) AS o
JOIN $ods.customers FOR SYSTEM_TIME AS OF o.pt AS c ON o.customer_id = c.id"
laketable "$dwd_name" "$dwd" orders order_id "$ods.orders,$ods.customers" "$dwd_query" "  governance: {owner: analytics, level: L2, retention: 7d}
  deletionPolicy: Delete
  job: $small"
# The markers DWS counted, and the latest of them: what the DWS dashboard path reads.
dws_query="SELECT region, COUNT(*) AS orders, SUM(amount) AS amount,
  SUM(CASE WHEN order_id >= $base THEN 1 ELSE 0 END) AS markers,
  MAX(CASE WHEN order_id >= $base THEN order_id END) AS last_marker${DASHBOARDS_DWS_EXTRA:-}
FROM $dwd.orders GROUP BY region"
laketable "$dws_name" "$dws" segments region "$dwd.orders" "$dws_query" "  governance: {owner: analytics, level: L2, retention: 7d}
  deletionPolicy: Delete
  job: $small"
await laketable "$dwd_name" dwd-running 900 "phase == 'Running' and live_all()" || true
await laketable "$dws_name" dws-running 900 "phase == 'Running' and live_all()" || true

# ------------------------------------------------------------------------- the dashboards
step "the dashboards $board and $adhoc"
for user in analyst viewer ops explorer scout escapee runaway; do
  openssl rand -hex 16 | tr -d '\n' >"$work/$user"
done
for user in analyst viewer ops; do
  kubectl -n "$JOB_NAMESPACE" create secret generic "$board-$user" --from-file=password="$work/$user" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
done
for user in explorer scout escapee runaway; do
  kubectl -n "$JOB_NAMESPACE" create secret generic "$adhoc-$user" --from-file=password="$work/$user" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
done
markers="SELECT COUNT(*) AS n, MAX(id) AS id FROM $ods.orders WHERE id >= $base"
cat >"$evidence/dashboard.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeDashboard
metadata: {name: $board, namespace: $JOB_NAMESPACE}
spec:
  tables: [$ods.orders, $ods.customers, $dwd.orders, $dws.segments]
  governance: {owner: bi-team, level: L2, retention: 7d}
  workload: {maxConcurrency: 32}
  queries:
    - {name: region_totals, serve: materialized, refresh: 1m,
       sql: "SELECT region, orders, amount FROM $dws.segments"}
    - {name: marker_mv, serve: materialized, refresh: 1m, sql: "$markers"}
    - {name: marker_live, serve: external, sql: "$markers"}
    - {name: marker_dws, serve: materialized, refresh: 1m,
       sql: "SELECT SUM(markers) AS n, MAX(last_marker) AS id FROM $dws.segments"}
    - {name: order_lookup, serve: external,
       sql: "SELECT order_id, customer_id, region, amount, state FROM $dwd.orders"}
    - {name: customers_hot, serve: internal, refresh: 1m, key: [id],
       sql: "SELECT id, name, region FROM $ods.customers"}
    - {name: marker_hot, serve: internal, refresh: 1m, key: [k],
       sql: "SELECT 1 AS k, COUNT(*) AS n, MAX(id) AS id FROM $ods.orders WHERE id >= $base"}
  users:
    - {name: analyst, passwordSecret: {name: $board-analyst},
       queries: [region_totals, marker_mv, marker_live, marker_dws, order_lookup]}
    - {name: viewer, passwordSecret: {name: $board-viewer}, queries: [region_totals]}
    - {name: ops, passwordSecret: {name: $board-ops}, queries: [customers_hot, marker_hot]}
---
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeDashboard
metadata: {name: $adhoc, namespace: $JOB_NAMESPACE}
spec:
  tables: [$dwd.orders]
  workload: {maxConcurrency: 2}
  queries:
    - {name: explore, serve: external,
       sql: "SELECT o.region, COUNT(*) AS pairs, SUM(o.amount) AS amount FROM $dwd.orders o JOIN $dwd.orders p ON o.customer_id = p.customer_id GROUP BY o.region"}
  users:
    - {name: explorer, passwordSecret: {name: $adhoc-explorer}, queries: [explore]}
    - {name: scout, passwordSecret: {name: $adhoc-scout}, queries: [explore]}
    - {name: escapee, passwordSecret: {name: $adhoc-escapee}, queries: [explore]}
    - {name: runaway, passwordSecret: {name: $adhoc-runaway}, queries: [explore]}
YAML
kubectl apply -f "$evidence/dashboard.yaml"
ready=false
for _ in $(seq 1 90); do
  kubectl -n "$JOB_NAMESPACE" get lakedashboard "$board" -o json >"$evidence/status/dashboard.json" 2>/dev/null || true
  kubectl -n "$JOB_NAMESPACE" get lakedashboard "$adhoc" -o json >"$evidence/status/adhoc.json" 2>/dev/null || true
  if python3 - "$evidence/status/dashboard.json" "$evidence/status/adhoc.json" <<'PY'
import json, sys
s = json.load(open(sys.argv[1])).get("status") or {}
a = json.load(open(sys.argv[2])).get("status") or {}
q = {x["name"]: x for x in s.get("queries", [])}
copies = ("region_totals", "marker_mv", "marker_dws", "customers_hot", "marker_hot")
ok = s.get("phase") == "Ready" and a.get("phase") == "Ready" and all(q.get(n, {}).get("refreshedAt") for n in copies)
sys.exit(0 if ok else 1)
PY
  then
    ready=true
    break
  fi
  sleep 10
done
summary='s.get("phase"), s.get("message"), s.get("database"), [(q["name"], q.get("serve"), q.get("refreshedAt")) for q in s.get("queries", [])]'
if [[ "$ready" == true ]]; then
  verdict dashboard-ready PASS "$(json_field "$evidence/status/dashboard.json" "$summary"); $(json_field "$evidence/status/adhoc.json" "$summary")"
else
  verdict dashboard-ready FAIL "$(json_field "$evidence/status/dashboard.json" "$summary"); $(json_field "$evidence/status/adhoc.json" "$summary")"
  # Nothing else can be measured: the run is taken down.
  step "the dashboards are not ready: delete them, then the tables, the source and the run's pods"
  kubectl -n "$JOB_NAMESPACE" delete lakedashboard "$board" "$adhoc" --timeout=5m || true
  bash "$cdc/writer.sh" stop "dw-$run" >/dev/null || true
  teardown
  exit 1
fi
database="$(json_field "$evidence/status/dashboard.json" 's["database"]')"
catalog="$(json_field "$evidence/status/dashboard.json" 's["catalog"]')"
adhoc_db="$(json_field "$evidence/status/adhoc.json" 's["database"]')"
adhoc_catalog="$(json_field "$evidence/status/adhoc.json" 's["catalog"]')"
adhoc_group="$(json_field "$evidence/status/adhoc.json" 's["workloadGroup"]')"
# Doris's names of the users: <namespace>.<dashboard>.<name>.
prefix="$JOB_NAMESPACE.$board" aprefix="$JOB_NAMESPACE.$adhoc"
analyst="$prefix.analyst" viewer="$prefix.viewer" ops="$prefix.ops"
explorer="$aprefix.explorer" scout="$aprefix.scout" escapee="$aprefix.escapee" runaway="$aprefix.runaway"

# ------------------------------------------------------------------------- access
step "each user reads what it is given, and nothing else"
granted=("SELECT COUNT(*) FROM $database.region_totals" "SELECT n, id FROM $database.marker_mv"
  "SELECT n, id FROM $database.marker_live" "SELECT n, id FROM $database.marker_dws"
  "SELECT COUNT(*) FROM $database.order_lookup")
refused=("SELECT COUNT(*) FROM $database.customers_hot" "SELECT COUNT(*) FROM $catalog.$dwd.orders"
  "SHOW CREATE CATALOG $catalog" "SELECT COUNT(*) FROM __internal_schema.audit_log"
  "SELECT COUNT(*) FROM $adhoc_db.explore")
# Doris gives every user these; the operator withdraws them (every user's running SQL, every name).
system=("SELECT COUNT(*) FROM information_schema.active_queries" "SELECT COUNT(*) FROM mysql.user")
printf '%s\n' "${granted[@]}" "${refused[@]}" "${system[@]}" "SHOW CATALOGS" \
  "SHOW CREATE VIEW $database.marker_live" >"$work/analyst.sql"
doris_as "$bi" "$analyst" "$work/analyst" "$work/analyst.sql" "$evidence/doris/analyst.txt"
printf '%s\n' "SELECT COUNT(*) FROM $database.region_totals" "SELECT COUNT(*) FROM $database.order_lookup" >"$work/viewer.sql"
doris_as "$bi" "$viewer" "$work/viewer" "$work/viewer.sql" "$evidence/doris/viewer.txt"
printf '%s\n' "SELECT COUNT(*) FROM $database.customers_hot" "SELECT COUNT(*) FROM $database.region_totals" >"$work/ops.sql"
doris_as "$bi" "$ops" "$work/ops" "$work/ops.sql" "$evidence/doris/ops.txt"
# What Doris still shows every user (recorded, LLD "看板服务"): materialized views' tasks, the BEs.
printf '%s\n' "SELECT COUNT(*) FROM $adhoc_db.explore" "SELECT COUNT(*) FROM $database.region_totals" \
  "SELECT COUNT(*) FROM tasks('type' = 'mv') WHERE MvDatabaseName = '$database'" "SHOW BACKENDS" >"$work/explorer.sql"
doris_as "$bi" "$explorer" "$work/explorer" "$work/explorer.sql" "$evidence/doris/explorer.txt"
results="" refusals="" withdrawn=""
for q in "${granted[@]}"; do results+="$(outcome "$evidence/doris/analyst.txt" "$q");"; done
for q in "${refused[@]}"; do refusals+="$(outcome "$evidence/doris/analyst.txt" "$q" | cut -c1-80);"; done
for q in "${system[@]}"; do withdrawn+="$(outcome "$evidence/doris/analyst.txt" "$q" | cut -c1-80);"; done
others="$(outcome "$evidence/doris/viewer.txt" "SELECT COUNT(*) FROM $database.region_totals") $(outcome "$evidence/doris/viewer.txt" "SELECT COUNT(*) FROM $database.order_lookup" | cut -c1-40);"
others+="$(outcome "$evidence/doris/ops.txt" "SELECT COUNT(*) FROM $database.customers_hot") $(outcome "$evidence/doris/ops.txt" "SELECT COUNT(*) FROM $database.region_totals" | cut -c1-40);"
others+="$(outcome "$evidence/doris/explorer.txt" "SELECT COUNT(*) FROM $adhoc_db.explore") $(outcome "$evidence/doris/explorer.txt" "SELECT COUNT(*) FROM $database.region_totals" | cut -c1-40)"
catalogs="$(sed -n '/^-- SHOW CATALOGS$/,$p' "$evidence/doris/analyst.txt" | tail -n +2)"
if [[ "$results" == "OK;OK;OK;OK;OK;" && "$refusals" != *OK* && "$refusals" != *MISSING* \
  && "$catalogs" != *"$catalog"* && "$catalogs" != *"$adhoc_catalog"* \
  && "$others" =~ ^OK\ ERROR.*\;OK\ ERROR.*\;OK\ ERROR ]]; then
  verdict users-read-what-they-are-given PASS "analyst: 5 OK; refused: $refusals no catalog listed; viewer, ops, explorer: $others"
else
  verdict users-read-what-they-are-given FAIL "analyst: $results refused: $refusals catalogs: $(tr '\n' ' ' <<<"$catalogs"); others: $others"
fi
if [[ "$withdrawn" == ERROR*\;ERROR*\; ]]; then
  verdict system-schemas-withdrawn PASS "$withdrawn"
else
  verdict system-schemas-withdrawn FAIL "$withdrawn"
fi
step "still shown to every user: $(sed -n "/tasks('type'/,/^-- SHOW/p" "$evidence/doris/explorer.txt" | tr '\n' ' ' | cut -c1-200); SHOW BACKENDS: $(outcome "$evidence/doris/explorer.txt" "SHOW BACKENDS" | cut -c1-60); its own view's definition: $(outcome "$evidence/doris/analyst.txt" "SHOW CREATE VIEW $database.marker_live" | cut -c1-60)"
step "no way in without the password"
printf '%s' "" >"$work/empty"
printf '%s' "not-the-password" >"$work/wrong"
echo "SELECT 1" >"$work/one.sql"
doris_as "$bi" root "$work/empty" "$work/one.sql" "$evidence/doris/root-empty.txt"
doris_as "$bi" admin "$work/empty" "$work/one.sql" "$evidence/doris/admin-empty.txt"
doris_as "$bi" "$analyst" "$work/wrong" "$work/one.sql" "$evidence/doris/analyst-wrong.txt"
denied="$(for f in root-empty admin-empty analyst-wrong; do outcome "$evidence/doris/$f.txt" "SELECT 1" | cut -c1-60; done | tr '\n' ';')"
if [[ "$denied" != *OK* && "$denied" != *MISSING* && "$(grep -c '^ERROR' <<<"${denied//;/$'\n'}")" -eq 3 ]]; then
  verdict passwords-required PASS "$denied"
else
  verdict passwords-required FAIL "$denied"
fi
step "only declared client namespaces reach Doris"
cat >"$work/reach.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: doris-reach-$run, namespace: NAMESPACE}
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
          for url in http://$fe:8030/api/health telnet://$fe:9030; do
            out="\$(curl -s -o /dev/null --connect-timeout 5 -m 8 -w '%{http_code} %{time_connect}' "\$url" </dev/null)"
            echo "\$url \$out \$?"
          done
      resources: {requests: {cpu: 10m, memory: 32Mi}, limits: {memory: 64Mi}}
YAML
for ns in "$JOB_NAMESPACE" "$bi"; do
  sed "s/NAMESPACE/$ns/" "$work/reach.yaml" >"$work/reach-$ns.yaml"
  run_pod "$ns" "doris-reach-$run" "$work/reach-$ns.yaml" >"$evidence/doris/reach-$ns.txt" 2>&1 || true
done
if python3 - "$evidence/doris/reach-$JOB_NAMESPACE.txt" "$evidence/doris/reach-$bi.txt" <<'PY'
import sys
outside = [l.split() for l in open(sys.argv[1]) if l.strip()]
inside = [l.split() for l in open(sys.argv[2]) if l.strip()]
# "<url> <http code> <seconds to connect> <curl exit>". A telnet session to the MySQL port waits
# for the client and ends in a timeout (exit 28) even when connected: the connect time tells.
# Outside: neither port connects. Inside: the MySQL port does; the HTTP port is the operator's
# alone (it does not connect here either).
def connected(line):
    return float(line[2]) > 0
ok = (len(outside) == 2 and not any(connected(l) for l in outside)
      and len(inside) == 2 and connected(inside[1]) and not connected(inside[0]))
sys.exit(0 if ok else 1)
PY
then
  verdict doris-isolated PASS "outside: $(tr '\n' ';' <"$evidence/doris/reach-$JOB_NAMESPACE.txt") inside: $(tr '\n' ';' <"$evidence/doris/reach-$bi.txt")"
else
  verdict doris-isolated FAIL "outside: $(tr '\n' ';' <"$evidence/doris/reach-$JOB_NAMESPACE.txt") inside: $(tr '\n' ';' <"$evidence/doris/reach-$bi.txt")"
fi

# ------------------------------------------------------------------------- markers and load
source_host="$(stack_output "$TEST_MYSQL_STACK" MySqlEndpoint)"
step "markers: one order a tenth of a second from $base, each path polled once a second"
if [[ -n "$perf_extra" ]]; then
  extra_status=0
  extra_out="$(bash "$perf_extra" start "$run" "$evidence" "$db" "$ods.orders" "$dwd.orders" \
    "$dws.segments" "$base")" || extra_status=$?
  step "$(basename "$perf_extra") start, exit $extra_status: ${extra_out//$'\n'/;}"
  if ((extra_status != 0)); then
    verdict perf-extra-started FAIL "$(basename "$perf_extra") start exited $extra_status"
    # Nothing it adds can be measured: the run is taken down.
    step "delete the dashboards, then the tables, the source and the run's pods"
    kubectl -n "$JOB_NAMESPACE" delete lakedashboard "$board" "$adhoc" --timeout=5m || true
    bash "$cdc/writer.sh" stop "dw-$run" >/dev/null || true
    teardown
    exit "$extra_status"
  fi
fi
bi_secret "marks-$run" marker analyst ops
bi_pod "marks-$run" marks.sh 300m "SOURCE_HOST=$source_host" "SOURCE_DB=$db" "MARK_USER=$marker_user" \
  "DORIS_HOST=$fe" "DATABASE=$database" "ANALYST=$analyst" "OPS=$ops" "BASE=$base"
sleep $((warmup_minutes * 60))
load_from="$(date +%s%3N)"
if [[ "$perf" == 1 ]]; then
  step "load: 10 dashboard queries a second and 1 lookup, open-loop, $load_minutes minutes"
  bi_secret "load-$run" analyst ops
  bi_pod "load-$run" load.sh 500m "DORIS_HOST=$fe" "DATABASE=$database" "ANALYST=$analyst" "OPS=$ops" \
    "MINUTES=$load_minutes"
  sleep $(((load_minutes - burst_minutes) * 30))
fi
step "burst: the ad-hoc users' heavy query, three connections each, $burst_minutes minutes"
bi_secret "burst-$run" explorer scout escapee runaway
groups_sampler "groups-$run" "$((burst_minutes * 60 + 150))"
sampling "groups-$run"
burst_from="$(date +%s%3N)"
bi_pod "burst-$run" burst.sh 100m "DORIS_HOST=$fe" "DATABASE=$adhoc_db" "MINUTES=$burst_minutes" \
  "USERS=$explorer:/in/explorer:own $scout:/in/scout:own $escapee:/in/escapee:normal $runaway:/in/runaway:normal"
kubectl -n "$bi" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/burst-$run" \
  --timeout="$((burst_minutes * 60 + 600))s" >/dev/null || true
burst_to="$(date +%s%3N)"
kubectl -n "$bi" logs "burst-$run" >"$evidence/doris/burst.log" 2>&1 || true
kubectl -n "$DORIS_NAMESPACE" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/groups-$run" \
  --timeout=300s >/dev/null || true
kubectl -n "$DORIS_NAMESPACE" logs "groups-$run" >"$evidence/doris/groups.log" 2>&1 || true
kubectl -n "$DORIS_NAMESPACE" delete pod "groups-$run" --ignore-not-found >/dev/null
if [[ "$perf" == 1 ]]; then
  kubectl -n "$bi" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/load-$run" \
    --timeout="$((load_minutes * 60 + 600))s" >/dev/null || true
  kubectl -n "$bi" logs "load-$run" >"$evidence/doris/load.log" 2>&1 || true
fi
load_to="$(date +%s%3N)"
echo "$load_from $load_to" >"$evidence/doris/window.txt"
if [[ -n "$perf_extra" ]]; then
  step "load done; $drain_minutes minutes for the markers to reach every path, then $(basename "$perf_extra") stop"
  sleep $((drain_minutes * 60))
  bash "$perf_extra" stop "$run" "$evidence" || true
  changelogs="$evidence/release/ods-orders.jsonl"
else
  step "load done; ODS's changelog, then $drain_minutes minutes for the markers to reach every path"
  bash "$flows/lake.sh" changelog "$evidence/dumps/ods-orders-1.jsonl" "$ods.orders" >/dev/null || true
  sleep $((drain_minutes * 60))
  bash "$flows/lake.sh" changelog "$evidence/dumps/ods-orders-2.jsonl" "$ods.orders" >/dev/null || true
  changelogs="$evidence/dumps/ods-orders-1.jsonl,$evidence/dumps/ods-orders-2.jsonl"
fi
kubectl -n "$bi" logs "marks-$run" >"$evidence/doris/marks.log" 2>&1 || true
# clock <namespace> <pod>: its clock minus this machine's, in ms, over the shortest of three round
# trips (printed with it).
clock() {
  local best="" t0 t1 r n
  for n in 1 2 3; do
    t0="$(date +%s%3N)"
    r="$(kubectl -n "$1" exec "$2" -- date +%s%3N 2>/dev/null)" || continue
    t1="$(date +%s%3N)"
    if [[ -z "$best" || $((t1 - t0)) -lt "${best#* }" ]]; then best="$((r - (t0 + t1) / 2)) $((t1 - t0))"; fi
    : "$n"
  done
  echo "${best:-? ?}"
}
marks_clock="$(clock "$bi" "marks-$run")"
echo "marks-$run ${marks_clock}" >"$evidence/doris/clocks.txt"
paimon_skew=0
for pod in $(kubectl -n "$JOB_NAMESPACE" get pods -o name | grep -E "($source_name|$dwd_name|$dws_name).*taskmanager" | cut -d/ -f2); do
  c="$(clock "$JOB_NAMESPACE" "$pod")"
  echo "$pod $c" >>"$evidence/doris/clocks.txt"
  if [[ "${c%% *}" =~ ^-?[0-9]+$ && "${marks_clock%% *}" =~ ^-?[0-9]+$ ]]; then
    d=$((${c%% *} - ${marks_clock%% *}))
    ((${d#-} > paimon_skew)) && paimon_skew=${d#-}
  fi
done
step "clocks against this machine (offset ms, round trip ms): $(tr '\n' ';' <"$evidence/doris/clocks.txt")"
# Performance: the dashboard's targets under load and burst, and the paths' freshness.
if [[ "$perf" == 1 ]]; then
  if python3 "$here/dashboards.py" load "$evidence/doris/load.log" "$evidence/doris/load.json" \
    --burst "$burst_from" "$burst_to" >"$work/load.txt"; then
    verdict load-within-targets PASS "$(cat "$work/load.txt")"
  else
    verdict load-within-targets FAIL "$(cat "$work/load.txt")"
  fi
  # Whether the load pod sent its queries when they were due: if not, the times above carry its lag.
  if python3 -c 'import json,sys; sys.exit(0 if json.load(open(sys.argv[1]))["schedule"]["kept"] else 1)' \
    "$evidence/doris/load.json"; then
    verdict load-on-schedule PASS "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["schedule"])' "$evidence/doris/load.json")"
  else
    verdict load-on-schedule FAIL "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["schedule"])' "$evidence/doris/load.json")"
  fi
  if python3 -c 'import json,sys; sys.exit(0 if json.load(open(sys.argv[1]))["dashboard_during_burst"]["pass"] else 1)' \
    "$evidence/doris/load.json"; then
    verdict dashboard-under-burst PASS "$(python3 -c 'import json,sys; r=json.load(open(sys.argv[1]))["dashboard_during_burst"]; print({k: r[k] for k in ("samples", "p95", "p99", "max")})' "$evidence/doris/load.json")"
  else
    verdict dashboard-under-burst FAIL "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["dashboard_during_burst"])' "$evidence/doris/load.json")"
  fi
  python3 "$here/dashboards.py" fresh "$evidence/doris/marks.log" "$changelogs" "$evidence/doris/fresh.json" \
    --window "$load_from" "$load_to" --paimon-skew-ms "$paimon_skew" >"$work/fresh.txt" || true
  if python3 -c 'import json,sys; sys.exit(0 if json.load(open(sys.argv[1]))["steady"]["steady"] else 1)' "$evidence/doris/fresh.json"; then
    verdict steady-state PASS "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["steady"])' "$evidence/doris/fresh.json")"
  else
    verdict steady-state FAIL "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["steady"])' "$evidence/doris/fresh.json")"
  fi
  for path in source_to_external source_to_dws_dashboard paimon_to_external paimon_to_materialized \
    paimon_to_internal; do
    line="$(python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); p=r[sys.argv[2]]; print("PASS" if p["pass"] else "FAIL", {k: p[k] for k in ("samples", "p50", "p95", "p99", "max", "targets", "judged")}, "clock", r["clock"], "gaps", r["gaps"])' "$evidence/doris/fresh.json" "$path")"
    verdict "fresh-${path//_/-}" "${line%% *}" "${line#* }"
  done
fi
# The ad-hoc users' queries as Doris logged them, once its audit loader has flushed.
sleep 90
cat >"$work/burst-audit.sql" <<SQL
SELECT CAST(UNIX_TIMESTAMP(\`time\`) * 1000 AS BIGINT), \`user\`, workload_group, \`state\`, query_time, queue_time_ms, REPLACE(REPLACE(COALESCE(error_message, ''), '\t', ' '), '\n', ' ') FROM __internal_schema.audit_log WHERE \`user\` IN ('$explorer', '$scout', '$escapee', '$runaway') AND \`time\` >= FROM_UNIXTIME($((burst_from / 1000))) AND stmt LIKE 'SELECT%explore%';
SQL
doris_sql "$work/burst-audit.sql" >"$evidence/doris/burst-audit.tsv" 2>&1 || true
if python3 "$here/dashboards.py" burst "$evidence/doris/burst-audit.tsv" "$evidence/doris/burst.json" \
  "$adhoc_group=2" "normal=2" --sampled "$evidence/doris/groups.log" --window "$burst_from" "$burst_to" \
  >"$work/burst.txt"; then
  verdict adhoc-bounded PASS "$(cat "$work/burst.txt")"
else
  verdict adhoc-bounded FAIL "$(cat "$work/burst.txt")"
fi
limited="$(for user in "$explorer" "$scout" "$escapee" "$runaway"; do printf '%s ' "$(grep "^$user " "$evidence/doris/burst.log" | grep -c 'Reach_limit_of_connections')"; done)"
if [[ "$limited" =~ ^([1-9][0-9]*\ ){4}$ ]]; then
  verdict connections-bounded PASS "third connections refused (explorer scout escapee runaway): $limited"
else
  verdict connections-bounded FAIL "third connections refused (explorer scout escapee runaway): $limited"
fi
# Server-side, the fixed query's times as Doris logged them.
cat >"$work/audit-times.sql" <<SQL
SELECT COUNT(*) AS n, PERCENTILE(query_time, 0.95) AS p95_ms, PERCENTILE(query_time, 0.99) AS p99_ms FROM __internal_schema.audit_log WHERE \`user\` = '$analyst' AND \`state\` = 'EOF' AND \`stmt\` LIKE 'SELECT region, orders, amount%' AND \`time\` >= FROM_UNIXTIME($((load_from / 1000)));
SQL
doris_sql "$work/audit-times.sql" --column-names >"$evidence/doris/audit-times.tsv" 2>&1 || true
step "server-side: $(tr '\n' ' ' <"$evidence/doris/audit-times.tsv")"

# ------------------------------------------------------------------------- recovery
# recover <component>: deletes its pod; from then, until every path answers again for good, the
# lake paths with the marker committed at the injection or a later one, and the internal table is
# rewritten.
recover() {
  local component="$1" injected before hot result="" ok=false end started
  kubectl -n "$bi" logs "marks-$run" >"$work/marks.log"
  before="$(awk '$1 == "mark" {id = $2} END {print id}' "$work/marks.log")"
  injected="$(date +%s%3N)"
  started=$SECONDS
  kubectl -n "$DORIS_NAMESPACE" delete pod "$DORIS_CLUSTER-$component-0" --wait=true >/dev/null
  end=$((SECONDS + 1800))
  while ((SECONDS < end)); do
    sleep 15
    kubectl -n "$bi" logs "marks-$run" >"$work/marks.log" 2>/dev/null || continue
    kubectl -n "$JOB_NAMESPACE" get lakedashboard "$board" -o json >"$work/board.json" 2>/dev/null || continue
    hot="$(json_field "$work/board.json" '[q.get("refreshedAt") for q in s["queries"] if q["name"] == "customers_hot"][0]')"
    if result="$(python3 "$here/dashboards.py" recovered "$work/marks.log" "$injected" "$before")" \
      && [[ "$(date -d "$hot" +%s%3N 2>/dev/null || echo 0)" -ge "$injected" ]]; then
      ok=true
      break
    fi
  done
  cp "$work/marks.log" "$evidence/doris/marks-$component.log"
  if [[ "$ok" == true ]]; then
    verdict "recovered-$component" PASS "within $((SECONDS - started))s of the injection (the acceptance contract's RTO, 1800s): seconds per path $result; customers_hot rewritten at $hot"
  else
    verdict "recovered-$component" FAIL "not within 1800s: $result; customers_hot rewritten at $hot"
  fi
}
step "recovery: the BE, then the FE"
recover be
recover fe

# ------------------------------------------------------------------------- correctness
kubectl -n "$bi" delete pod "marks-$run" --ignore-not-found >/dev/null
step "writer stop: $(bash "$cdc/writer.sh" stop "dw-$run")"
# DWS catches up, then two refreshes of the copies.
sleep 240
bash "$flows/lake.sh" rows "$evidence/dumps/dws.rows.jsonl" "$dws.segments" >/dev/null
bash "$flows/lake.sh" rows "$evidence/dumps/customers.rows.jsonl" "$ods.customers" >/dev/null
bash "$flows/lake.sh" rows "$evidence/dumps/dwd.rows.jsonl" "$dwd.orders" >/dev/null
echo "SELECT region, orders, amount FROM $database.region_totals;" >"$work/region.sql"
echo "SELECT id, name, region FROM $database.customers_hot;" >"$work/customers.sql"
echo "SELECT order_id, customer_id, region, amount, state FROM $database.order_lookup;" >"$work/orders.sql"
doris_sql "$work/region.sql" --column-names >"$evidence/doris/region_totals.tsv"
doris_sql "$work/customers.sql" --column-names >"$evidence/doris/customers_hot.tsv"
doris_sql "$work/orders.sql" --column-names >"$evidence/doris/order_lookup.tsv"
for check in "region_totals dws region orders,amount" "customers_hot customers id name,region" \
  "order_lookup dwd order_id customer_id,region,amount,state"; do
  read -r name dump key columns <<<"$check"
  if python3 "$here/dashboards.py" rows "$evidence/doris/$name.tsv" "$evidence/dumps/$dump.rows.jsonl" \
    "$key" "$columns" >"$work/$name.txt"; then
    verdict "consistent-$name" PASS "$(cat "$work/$name.txt")"
  else
    verdict "consistent-$name" FAIL "$(cat "$work/$name.txt")"
  fi
done

# ------------------------------------------------------------------------- audit
step "the query log"
cat >"$work/audit.sql" <<SQL
SELECT \`user\`, \`state\`, CASE WHEN stmt LIKE '%region_totals%' THEN 'region_totals' WHEN stmt LIKE '%marker_live%' THEN 'marker_live' WHEN stmt LIKE '%marker_mv%' THEN 'marker_mv' WHEN stmt LIKE '%marker_dws%' THEN 'marker_dws' WHEN stmt LIKE '%marker_hot%' THEN 'marker_hot' WHEN stmt LIKE '%customers_hot%' THEN 'customers_hot' WHEN stmt LIKE '%order_lookup%' THEN 'order_lookup' WHEN stmt LIKE '%explore%' THEN 'explore' ELSE 'other' END AS object, COUNT(*) FROM __internal_schema.audit_log WHERE \`user\` IN ('$analyst', '$viewer', '$ops', '$explorer') AND \`time\` >= FROM_UNIXTIME($((run_from / 1000))) GROUP BY 1, 2, 3;
SQL
doris_sql "$work/audit.sql" >"$evidence/doris/audit.tsv" 2>&1 || true
# Each user's reads of each path it was given, and its refusals.
expect=()
for o in region_totals marker_live marker_mv marker_dws order_lookup; do expect+=("$analyst:$o:EOF"); done
for o in customers_hot marker_hot; do expect+=("$ops:$o:EOF"); done
expect+=("$analyst:customers_hot:ERR" "$viewer:region_totals:EOF" "$viewer:order_lookup:ERR"
  "$ops:region_totals:ERR" "$explorer:explore:EOF" "$explorer:region_totals:ERR")
if python3 "$here/dashboards.py" audit "$evidence/doris/audit.tsv" "${expect[@]}" >"$work/audit.txt"; then
  verdict queries-audited PASS "$(cut -c1-600 "$work/audit.txt")"
else
  verdict queries-audited FAIL "$(cut -c1-600 "$work/audit.txt")"
fi
cat >"$work/retention.sql" <<'SQL'
SHOW CREATE TABLE __internal_schema.audit_log;
SHOW FRONTEND CONFIG LIKE 'audit_log_delete_age';
SHOW FRONTEND CONFIG LIKE 'catalog_trash_expire_second';
SHOW BACKEND CONFIG LIKE 'trash_file_expire_time_sec';
SQL
doris_sql "$work/retention.sql" >"$evidence/doris/retention.txt" 2>&1 || true
retention="$(python3 - "$evidence/doris/retention.txt" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
def config(key):
    m = re.search(r"(?m)^(?:\S+\t\S+\t)?" + key + r"\t(\S+)", text)
    return m.group(1) if m else None
found = {"dynamic_partition.start": (re.search(r'"dynamic_partition.start" = "([^"]*)"', text) or [None, None])[1],
         "audit_log_delete_age": config("audit_log_delete_age"),
         "catalog_trash_expire_second": config("catalog_trash_expire_second"),
         "trash_file_expire_time_sec": config("trash_file_expire_time_sec")}
want = {"dynamic_partition.start": "-6", "audit_log_delete_age": "5d",
        "catalog_trash_expire_second": "86400", "trash_file_expire_time_sec": "0"}
print(("PASS " if found == want else "FAIL ") + str(found))
PY
)"
verdict doris-retention "${retention%% *}" "${retention#* } (a query stays 7 days in the log, what a refresh replaced a day in storage)"

# Every compaction the run requested of ODS succeeded (they kept the reads of ODS fast).
kubectl -n "$JOB_NAMESPACE" get laketask -l "colvenna-lake.io/run=dash-$run" -o json >"$work/compactions.json"
if compacted="$(python3 - "$work/compactions.json" <<'PY'
import collections, json, sys
tasks = json.load(open(sys.argv[1]))["items"]
phases = collections.Counter((t.get("status") or {}).get("phase") or "Pending" for t in tasks)
print(dict(phases))
# The last round may still run; none failed.
sys.exit(0 if phases["Succeeded"] > 0 and phases["Failed"] == 0 else 1)
PY
)"; then
  verdict ods-compacted PASS "$compacted"
else
  verdict ods-compacted FAIL "$compacted"
fi

# ------------------------------------------------------------------------- deletion
step "delete the dashboards"
for name in "$board" "$adhoc"; do
  kubectl -n "$JOB_NAMESPACE" get secret "$name-catalog-token" -o jsonpath='{.data.token}' | base64 -d >"$work/token-$name"
done
kubectl -n "$JOB_NAMESPACE" delete lakedashboard "$board" "$adhoc" --timeout=5m
doris_as "$bi" "$analyst" "$work/analyst" "$work/one.sql" "$evidence/doris/analyst-after.txt"
doris_as "$bi" "$explorer" "$work/explorer" "$work/one.sql" "$evidence/doris/explorer-after.txt"
cat >"$work/gone.sql" <<SQL
SELECT 'databases', COUNT(*) FROM information_schema.schemata WHERE SCHEMA_NAME IN ('$database', '$adhoc_db');
SELECT 'groups', COUNT(*) FROM information_schema.workload_groups WHERE NAME = '$adhoc_group';
SHOW CATALOGS;
SQL
doris_sql "$work/gone.sql" >"$evidence/doris/gone.txt" 2>&1 || true
admin_open
tokens=""
for name in "$board" "$adhoc"; do
  printf 'header = "Authorization: Bearer %s"\n' "$(cat "$work/token-$name")" >"$ADMIN_DIR/probe.curlrc"
  tokens+="$(curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/probe.curlrc" \
    "https://localhost:$ADMIN_PORT/v1/config?warehouse=colvenna") "
done
rm -f "$ADMIN_DIR/probe.curlrc"
admin_close
logins="$(outcome "$evidence/doris/analyst-after.txt" "SELECT 1" | cut -c1-50) / $(outcome "$evidence/doris/explorer-after.txt" "SELECT 1" | cut -c1-50)"
if [[ "$logins" == ERROR*" / ERROR"* && "$tokens" == "401 401 " ]] \
  && grep -q $'^databases\t0$' "$evidence/doris/gone.txt" && grep -q $'^groups\t0$' "$evidence/doris/gone.txt" \
  && ! grep -q -e "$catalog" -e "$adhoc_catalog" "$evidence/doris/gone.txt"; then
  verdict dashboard-deleted PASS "users refused ($logins), databases, catalogs and the workload group gone, tokens $tokens"
else
  verdict dashboard-deleted FAIL "users: $logins tokens: $tokens $(tr '\t\n' ' ;' <"$evidence/doris/gone.txt")"
fi
step "delete the tables, the source and the run's pods, users and fixture"
teardown
echo "verdicts:"
cat "$evidence/verdicts.tsv"
exit "$failed"
