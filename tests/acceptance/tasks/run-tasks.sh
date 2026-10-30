#!/usr/bin/env bash
# Acceptance on EKS: batch and maintenance tasks (LakeTask) on declared tables under continuous
# source writes, and Airflow running them. Writes PASS/FAIL lines to <evidence>/verdicts.tsv and
# fails if any check fails.
#
# A LakeSource ingests orders and a segments dimension into ODS; a LakeTable keeps DWD (orders
# with their segment, a lookup join) and another DWS (orders and amount per segment, from DWD's
# changelog). On top of them:
# - Airflow 2.10 (its scheduler and the DAG tasks/colvenna_lake_task.py) tags ODS at end of day
#   through the Kubernetes API as its own service account; the same run again reuses the task; a
#   refused task fails the DAG run; an anonymous request is refused;
# - a full compaction, applied again unchanged, a changed spec refused by the API server, and a
#   second task asking for an existing tag;
# - two tasks on one table running one after the other;
# - a run killed with no retries left failing the task;
# - snapshot expiry while DWD is suspended: the snapshots DWD still has to read stay, and DWD
#   resumes from its position;
# - with the writer stopped, a backfill of DWD after a dimension change the stream does not apply
#   to old orders (a relabelled segment, a deleted one): a backfill of DWS (an aggregate) refused;
#   DWD's job held suspended (the fence) while the backfill runs, its first run killed once it
#   committed its upsert and retried; with DWS suspended the backfill waits (Converging), and
#   once DWS resumes it succeeds with DWS already equal to the source aggregate;
# - then DWD rows equal the source join, DWS rows the source aggregate, and both changelogs
#   replay without a stale before image;
# - a task on a table no declaration here owns refused; finished tasks' tokens refused.
#
# usage: OUTSIDE_TABLE=<database.table> run-tasks.sh <run-id> <evidence-dir>; OUTSIDE_TABLE is an
# existing catalog table no LakeSource or LakeTable of the namespace owns, e.g. the flow_<run>.orders
# of tests/acceptance/catalog/run-flink-flow.sh.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-tasks.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-tasks.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
outside="${OUTSIDE_TABLE:?OUTSIDE_TABLE=<database.table>: an existing table outside this namespace, e.g. flow_<run>.orders that tests/acceptance/catalog/run-flink-flow.sh <run> made}"
sha="$(committed_sha)"
flows="$REPO_DIR/tests/acceptance"
cdc="$flows/cdc"
mkdir -p "$evidence"/{status,dumps,sql,tables,tasks,airflow}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
for image in operator mysql sql; do
  echo "$image $(image_ref "$image")" | tee -a "$evidence/images.txt"
done
echo "airflow $AIRFLOW_IMAGE" | tee -a "$evidence/images.txt"
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

# task_json <name>: the LakeTask as JSON, kept in the evidence.
task_json() {
  kubectl -n "$JOB_NAMESPACE" get laketask "$1" -o json | tee "$evidence/tasks/$1.json"
}

# task_field <name> <python expression over t, the LakeTask's status>
task_field() {
  task_json "$1" | python3 -c 'import json,sys; t=json.load(sys.stdin).get("status",{}); print(eval(sys.argv[1]))' "$2"
}

# await_task <name> <label> <timeout-seconds> <phase>: until the task is final; PASS if it ended
# in the phase.
await_task() {
  local name="$1" label="$2" end=$((SECONDS + $3)) started=$SECONDS phase=""
  while ((SECONDS < end)); do
    phase="$(task_field "$name" 't.get("phase")' 2>/dev/null || true)"
    if [[ "$phase" == Succeeded || "$phase" == Failed ]]; then
      break
    fi
    sleep 10
  done
  if [[ "$phase" == "$4" ]]; then
    verdict "$label" PASS "$((SECONDS - started))s: $(task_field "$name" 't.get("message")')"
    return 0
  fi
  verdict "$label" FAIL "phase $phase: $(task_field "$name" 't.get("message")')"
  return 1
}

# laketask <name> <type> <db> <table> [<extra spec yaml>]
laketask() {
  local file="$evidence/tasks/$1.yaml"
  cat >"$file" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: $1, namespace: $JOB_NAMESPACE}
spec:
  type: $2
  table: {database: $3, table: $4}
${5:-}
YAML
  kubectl apply -f "$file"
}

# running_pod <task> <attempt>: the attempt's pod once it runs, or nothing after 15 minutes.
running_pod() {
  local job="" pod="" end=$((SECONDS + 900))
  while ((SECONDS < end)); do
    job="$(task_field "$1" 't.get("run") if t.get("attempts") == '"$2"' else ""' 2>/dev/null || true)"
    if [[ -n "$job" ]]; then
      pod="$(kubectl -n "$JOB_NAMESPACE" get pods -l "job-name=$job" \
        -o jsonpath='{range .items[?(@.status.phase=="Running")]}{.metadata.name}{end}')"
      [[ -n "$pod" ]] && { echo "$pod"; return 0; }
    fi
    sleep 3
  done
  echo "no running pod for attempt $2 of $1" >&2
  return 1
}

# kill_run_after <task> <attempt> <seconds>: deletes the attempt's pod once it has run that long,
# as a lost node would.
kill_run_after() {
  local pod
  pod="$(running_pod "$1" "$2")" || return 1
  sleep "$3"
  kubectl -n "$JOB_NAMESPACE" delete pod "$pod" --wait=false
  step "killed $pod (attempt $2 of $1, after ${3}s)"
}

# kill_run_committed <task> <attempt>: deletes the attempt's pod once its log says the backfill's
# upsert committed, so the next attempt starts from a half-rewritten table.
kill_run_committed() {
  local pod end=$((SECONDS + 900))
  pod="$(running_pod "$1" "$2")" || return 1
  until grep -q "^TASK_PROGRESS upserted" <<<"$(kubectl -n "$JOB_NAMESPACE" logs "$pod" -c task 2>/dev/null)"; do
    ((SECONDS < end)) || { echo "attempt $2 of $1 never committed"; return 1; }
    sleep 1
  done
  kubectl -n "$JOB_NAMESPACE" delete pod "$pod" --wait=false
  step "killed $pod (attempt $2 of $1, its upsert committed)"
}

# warning_event <name> <reason>: a Warning event with this reason was recorded on the object.
warning_event() {
  grep -qx "$2" <<<"$(kubectl -n "$JOB_NAMESPACE" get events --field-selector "involvedObject.name=$1,type=Warning" \
    -o jsonpath='{range .items[*]}{.reason}{"\n"}{end}')"
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

# token_status <token-file>: the HTTP status colvenna-catalog gives this bearer token. Needs admin_open.
token_status() {
  printf 'header = "Authorization: Bearer %s"\n' "$(cat "$1")" >"$ADMIN_DIR/probe.curlrc"
  curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/probe.curlrc" \
    "https://localhost:$ADMIN_PORT/v1/config?warehouse=colvenna"
}

# compare_query <label> <sql-file> <lake-table> <key> <version>: rows and changelog against a
# source query, retried while the jobs catch up.
compare_query() {
  local label="$1" sql="$2" table="$3" key="$4" version="$5" attempt
  local rows="$evidence/dumps/$label.rows.jsonl" source="$evidence/dumps/$label.source.tsv"
  for attempt in $(seq 1 15); do
    bash "$FIXTURES_DIR/sources/sources.sh" mysql-query "$sql" >"$source"
    bash "$flows/lake.sh" rows "$rows" "$table" >/dev/null
    if python3 "$cdc/compare.py" rows --lake "$rows" --source "$source" --table "$table" \
      --key "$key" >"$evidence/dumps/$label.rows-verdict.json"; then
      verdict "rows-$label" PASS "$(cat "$evidence/dumps/$label.rows-verdict.json")"
      bash "$flows/lake.sh" changelog "$evidence/dumps/$label.changelog.jsonl" "$table" >/dev/null
      if python3 "$cdc/compare.py" changelog --lake "$evidence/dumps/$label.changelog.jsonl" \
        --rows "$rows" --table "$table" --key "$key" --version "$version" \
        >"$evidence/dumps/$label.changelog-verdict.json"; then
        verdict "changelog-$label" PASS "$(cat "$evidence/dumps/$label.changelog-verdict.json")"
      else
        verdict "changelog-$label" FAIL "$(cat "$evidence/dumps/$label.changelog-verdict.json")"
      fi
      return 0
    fi
    echo "attempt $attempt: $(cat "$evidence/dumps/$label.rows-verdict.json")"
    sleep 20
  done
  verdict "rows-$label" FAIL "$(cat "$evidence/dumps/$label.rows-verdict.json")"
}

# airflow_runs <name> <conf-json>...: one Airflow pod, as the airflow-<run> service account, with a
# scheduler running one DAG run per conf in order (tasks/airflow-runs.sh); prints the pod's log.
airflow_runs() {
  local pod="$1" i=0 conf
  shift
  {
    cat <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $pod, namespace: $JOB_NAMESPACE, labels: {app: airflow-$run}}
spec:
  restartPolicy: Never
  serviceAccountName: airflow-$run
  securityContext: {runAsNonRoot: true, runAsUser: 50000, runAsGroup: 0, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: airflow
      image: $AIRFLOW_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["bash", "/dags/airflow-runs.sh"]
      env:
        - {name: AIRFLOW__CORE__LOAD_EXAMPLES, value: "false"}
        - {name: AIRFLOW__CORE__DAGS_FOLDER, value: /tmp/dags}
        - {name: AIRFLOW__SCHEDULER__MIN_FILE_PROCESS_INTERVAL, value: "5"}
        - {name: AIRFLOW__SCHEDULER__DAG_DIR_LIST_INTERVAL, value: "10"}
        - {name: AIRFLOW__SCHEDULER__PARSING_PROCESSES, value: "1"}
        - {name: AIRFLOW__CORE__PARALLELISM, value: "2"}
        - {name: RUNS, value: "$#"}
YAML
    for conf in "$@"; do
      printf '        - {name: CONF_%s, value: %s}\n' "$i" "$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$conf")"
      i=$((i + 1))
    done
    cat <<YAML
      resources: {requests: {cpu: 500m, memory: 2Gi}, limits: {memory: 3Gi}}
      volumeMounts:
        - {name: dags, mountPath: /dags, readOnly: true}
  volumes:
    - {name: dags, configMap: {name: airflow-$run-dags}}
YAML
  } >"$evidence/airflow/$pod.yaml"
  kubectl -n "$JOB_NAMESPACE" delete pod "$pod" --ignore-not-found --wait=true >/dev/null
  kubectl apply -f "$evidence/airflow/$pod.yaml" >/dev/null
  local end=$((SECONDS + 3600))
  while ((SECONDS < end)) && [[ ! "$(kubectl -n "$JOB_NAMESPACE" get pod "$pod" -o jsonpath='{.status.phase}')" =~ ^(Succeeded|Failed)$ ]]; do
    sleep 10
  done
  kubectl -n "$JOB_NAMESPACE" logs "$pod" | tee "$evidence/airflow/$pod.log"
}

small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
work="$(mktemp -d)"
chmod 700 "$work"
trap 'admin_close; rm -rf "$work"' EXIT
ensure_rds_ca "$JOB_NAMESPACE"

# --------------------------------------------------------------------------------- the source
db="task_$run" ods="ods_task_$run" dwd="dwd_task_$run" dws="dws_task_$run"
source_name="ltk-$run" dwd_name="ltk-dwd-$run" dws_name="ltk-dws-$run"
step "mysql fixture $db"
{
  python3 "$cdc/mysql_contract.py" reset "$db"
  python3 "$cdc/mysql_contract.py" setup "$db"
  python3 "$cdc/mysql_contract.py" table "$db" segments
  # Never written: its ODS table has no snapshot (the table stays Snapshotting), for the createTag
  # that must end NoSnapshot.
  python3 "$cdc/mysql_contract.py" table "$db" empties
  cat <<SQL
USE $db;
SET SESSION cte_max_recursion_depth = 100000;
INSERT INTO segments (id, label, amount, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 200)
  SELECT i, ELT(1 + i % 3, 'gold', 'silver', 'bronze'), 0, 1 FROM n;
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
  tables: {include: [orders, segments, empties]}
  target: {database: $ods, bucket: 2}
  # Classified, without personal data: its tags may keep their snapshot longer than a day.
  governance: {owner: shop-team, level: L2, retention: 7d}
  job: $small
  deletionPolicy: Delete
YAML
step "writer start"
bash "$cdc/writer.sh" start mysql "$db" "kw-$run"
kubectl apply -f "$evidence/source.yaml"
await lakesource "$source_name" source-ready 900 \
  "state('orders') == 'Ready' and state('segments') == 'Ready' and state('empties') in ('Snapshotting', 'Ready') and live_all()" || true

dwd_query="SELECT o.id AS order_id, o.customer_id, s.label AS segment, o.amount, o.state, o.version_no
FROM (SELECT *, PROCTIME() AS pt FROM $ods.orders) AS o
JOIN $ods.segments FOR SYSTEM_TIME AS OF o.pt AS s ON o.customer_id = s.id"
dws_query="SELECT segment, COUNT(*) AS order_count, SUM(amount) AS amount_total
FROM $dwd.orders GROUP BY segment"
keep="  deletionPolicy: Delete
  tableOptions: {snapshot.time-retained: 1 d}
  job: $small"
step "apply $dwd_name and $dws_name"
laketable "$dwd_name" "$dwd" orders order_id "$ods.orders,$ods.segments" "$dwd_query" "$keep"
laketable "$dws_name" "$dws" segments segment "$dwd.orders" "$dws_query" "$keep"
await laketable "$dwd_name" dwd-running 900 "phase == 'Running' and live_all()" || true
await laketable "$dws_name" dws-running 900 "phase == 'Running' and live_all()" || true

# ------------------------------------------------------------------------------------ Airflow
# The orchestrator's identity: create and read LakeTasks in this namespace, nothing else.
step "Airflow: end-of-day tag of $ods.orders, the same run again, a refused task"
kubectl -n "$JOB_NAMESPACE" create configmap "airflow-$run-dags" \
  --from-file="$flows/tasks/colvenna_lake_task.py" --from-file="$flows/tasks/airflow-runs.sh" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl apply -f - >/dev/null <<YAML
apiVersion: v1
kind: ServiceAccount
metadata: {name: airflow-$run, namespace: $JOB_NAMESPACE}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: {name: airflow-$run, namespace: $JOB_NAMESPACE}
rules:
  - apiGroups: [colvenna-lake.io]
    resources: [laketasks]
    verbs: [create, get]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: {name: airflow-$run, namespace: $JOB_NAMESPACE}
subjects: [{kind: ServiceAccount, name: airflow-$run, namespace: $JOB_NAMESPACE}]
roleRef: {kind: Role, name: airflow-$run, apiGroup: rbac.authorization.k8s.io}
YAML
tag="eod-$(date -u +%Y%m%d)-$run"
eod="{\"name\": \"eod-$run\", \"spec\": {\"type\": \"createTag\", \"table\": {\"database\": \"$ods\", \"table\": \"orders\"}, \"createTag\": {\"name\": \"$tag\", \"timeRetained\": \"2d\"}}}"
refused="{\"name\": \"refused-$run\", \"spec\": {\"type\": \"backfill\", \"table\": {\"database\": \"$ods\", \"table\": \"orders\"}}}"
log="$(airflow_runs "airflow-$run" "$eod" "$eod" "$refused")"
if grep -qx "DAG_RUN 0 success" <<<"$log" && grep -q "COLVENNA_TASK created $JOB_NAMESPACE/eod-$run" <<<"$log" \
  && [[ "$(task_field "eod-$run" 't.get("phase")')" == Succeeded ]]; then
  verdict airflow-task-succeeded PASS "the DAG run created eod-$run and passed once it Succeeded: $(task_field "eod-$run" 't["result"]["detail"]')"
else
  verdict airflow-task-succeeded FAIL "$(grep -E 'DAG_RUN 0|COLVENNA_TASK' <<<"$log" | head -5 | tr '\n' ' ')"
fi
jobs="$(kubectl -n "$JOB_NAMESPACE" get jobs -l "colvenna-lake.io/task=eod-$run" -o name | wc -l)"
if grep -qx "DAG_RUN 1 success" <<<"$log" && grep -q "COLVENNA_TASK exists $JOB_NAMESPACE/eod-$run" <<<"$log" && ((jobs == 1)); then
  verdict airflow-repeat-reused PASS "the same DAG run again found the task, passed, and ran nothing ($jobs Job)"
else
  verdict airflow-repeat-reused FAIL "jobs=$jobs $(grep -E 'DAG_RUN 1' <<<"$log")"
fi
if grep -qx "DAG_RUN 2 failed" <<<"$log" && grep -q "LakeTask refused-$run failed" <<<"$log" \
  && [[ "$(task_field "refused-$run" 't.get("phase")')" == Failed ]]; then
  verdict airflow-refused-failed PASS "the DAG run failed with the operator's reason: $(task_field "refused-$run" 't.get("message")')"
else
  verdict airflow-refused-failed FAIL "$(grep -E 'DAG_RUN 2|refused' <<<"$log" | head -3 | tr '\n' ' ')"
fi
anonymous="$(sed -n 's/^ANONYMOUS //p' <<<"$log")"
if [[ "$anonymous" == 401 || "$anonymous" == 403 ]]; then
  verdict anonymous-refused PASS "an unauthenticated LakeTask create got $anonymous"
else
  verdict anonymous-refused FAIL "unauthenticated create got ${anonymous:-nothing}"
fi
bash "$flows/lake.sh" table "$evidence/dumps/ods-orders-tags.jsonl" "$ods.orders" >/dev/null
if python3 -c 'import json,sys; t=json.loads(open(sys.argv[1]).readline())["tags"]; sys.exit(0 if sys.argv[2] in t else 1)' \
  "$evidence/dumps/ods-orders-tags.jsonl" "$tag"; then
  verdict tag-exists PASS "$ods.orders has tag $tag"
else
  verdict tag-exists FAIL "no tag $tag on $ods.orders"
fi

# --------------------------------------------------------------------------- maintenance
step "full compaction of $ods.orders; applied again; changed; a second tag task"
laketask "compact-$run" compact "$ods" orders "  compact: {full: true}"
if await_task "compact-$run" compact-succeeded 1200 Succeeded; then
  before="$(task_field "compact-$run" 't["result"].get("snapshotBefore")')"
  after="$(task_field "compact-$run" 't["result"].get("snapshotAfter")')"
  if ((after > before)); then
    verdict compact-committed PASS "snapshot $before -> $after"
  else
    verdict compact-committed FAIL "snapshot $before -> $after"
  fi
fi
finished="$(task_field "compact-$run" 't.get("finishedAt")')"
again="$(kubectl apply -f "$evidence/tasks/compact-$run.yaml")"
sleep 20
jobs="$(kubectl -n "$JOB_NAMESPACE" get jobs -l "colvenna-lake.io/task=compact-$run" -o name | wc -l)"
if [[ "$again" == *unchanged* && "$(task_field "compact-$run" 't.get("finishedAt")')" == "$finished" ]] && ((jobs == 1)); then
  verdict reapply-unchanged PASS "$again; still finished at $finished, $jobs Job"
else
  verdict reapply-unchanged FAIL "$again; jobs=$jobs"
fi
if changed="$(kubectl -n "$JOB_NAMESPACE" patch laketask "compact-$run" --type merge -p '{"spec":{"compact":{"full":false}}}' 2>&1)"; then
  verdict changed-spec-refused FAIL "the change was accepted: $changed"
elif [[ "$changed" == *"a task runs once as created"* ]]; then
  verdict changed-spec-refused PASS "the API server refused it: ${changed##*: }"
else
  verdict changed-spec-refused FAIL "$changed"
fi
laketask "eod-again-$run" createTag "$ods" orders "  createTag: {name: $tag}"
if await_task "eod-again-$run" tag-again 900 Succeeded; then
  detail="$(task_field "eod-again-$run" 't["result"]["detail"]')"
  if [[ "$detail" == "tag $tag exists" ]]; then
    verdict tag-again-idempotent PASS "$detail"
  else
    verdict tag-again-idempotent FAIL "$detail"
  fi
fi

step "two tasks on $ods.segments at once"
laketask "orphans-$run" removeOrphanFiles "$ods" segments
laketask "compact-seg-$run" compact "$ods" segments
sleep 15
waiting="$(task_field "compact-seg-$run" 't.get("phase") + ": " + str(t.get("message"))')"
await_task "orphans-$run" orphans-succeeded 1200 Succeeded || true
await_task "compact-seg-$run" second-succeeded 1200 Succeeded || true
first_end="$(task_field "orphans-$run" 't.get("finishedAt")')"
second_start="$(task_field "compact-seg-$run" 't.get("startedAt")')"
if [[ "$waiting" == "Pending: waiting for task orphans-$run"* && "$second_start" > "$first_end" ]]; then
  verdict tasks-serialized PASS "compact-seg-$run was '$waiting', started $second_start after orphans-$run finished $first_end"
else
  verdict tasks-serialized FAIL "'$waiting'; second started $second_start, first finished $first_end"
fi

step "a run killed with no retries left"
laketask "compact-fail-$run" compact "$ods" segments "  retries: 0"
# At once: a compaction with nothing left to do may end within seconds (t3).
kill_run_after "compact-fail-$run" 1 0 || true
if await_task "compact-fail-$run" retries-exhausted 900 Failed \
  && [[ "$(task_field "compact-fail-$run" 't.get("message")')" == *"attempt 1 of 1"* ]] \
  && warning_event "compact-fail-$run" TaskRunFailed; then
  verdict retries-exhausted-reported PASS "Warning TaskRunFailed recorded"
else
  verdict retries-exhausted-reported FAIL "no TaskRunFailed warning or wrong message"
fi

# Paimon keeps the snapshots a consumer has not read; expiry must not take them from a
# suspended reader.
step "suspend $dwd_name, expire $ods.orders down to one snapshot older than a minute"
kubectl -n "$JOB_NAMESPACE" patch laketable "$dwd_name" --type merge -p '{"spec":{"suspend":true}}'
await laketable "$dwd_name" dwd-suspended 600 "phase == 'Suspended'" || true
sleep 150
laketask "expire-$run" expireSnapshots "$ods" orders "  expireSnapshots: {retainMin: 1, olderThan: 1m}"
await_task "expire-$run" expire-succeeded 1200 Succeeded || true
bash "$flows/lake.sh" table "$evidence/dumps/ods-orders-after-expire.jsonl" "$ods.orders" >/dev/null
bash "$flows/lake.sh" consumers "$evidence/dumps/ods-orders-consumers.jsonl" "$ods.orders" >/dev/null
consumer="$(kubectl -n "$JOB_NAMESPACE" get laketable "$dwd_name" -o jsonpath='{.status.upstream[0].consumerId}')"
if python3 - "$evidence/dumps/ods-orders-after-expire.jsonl" "$evidence/dumps/ods-orders-consumers.jsonl" "$consumer" \
  >"$work/expire.txt" <<'PY'
import json, sys
table = json.loads(open(sys.argv[1]).readline())
consumers = [json.loads(line) for line in open(sys.argv[2]) if line.strip()]
mine = [c for c in consumers if c.get("id") == sys.argv[3]]
print(f"earliest {table['earliestSnapshot']}, latest {table['snapshot']}, consumer {mine}")
sys.exit(0 if mine and table["earliestSnapshot"] <= mine[0]["nextSnapshot"] else 1)
PY
then
  verdict expire-kept-reader-position PASS "$(task_field "expire-$run" 't["result"]["detail"]'); $(cat "$work/expire.txt")"
else
  verdict expire-kept-reader-position FAIL "$(cat "$work/expire.txt")"
fi
kubectl -n "$JOB_NAMESPACE" patch laketable "$dwd_name" --type merge -p '{"spec":{"suspend":false}}'
await laketable "$dwd_name" dwd-resumed-after-expire 900 \
  "phase == 'Running' and live_all() and 'savepoint' in str(restored())" || true

# ------------------------------------------------------------------------------- backfill
# With the writer stopped, what DWD and DWS hold once the backfill is Succeeded must already be
# the source's join and aggregate: nothing is left to catch up but the backfill itself.
step "writer stop: $(bash "$cdc/writer.sh" stop "kw-$run")"
await laketable "$dwd_name" dwd-caught-up 600 "lag('$ods.orders') == 0 and live_all()" || true
await laketable "$dws_name" dws-caught-up 600 "lag('$dwd.orders') == 0 and live_all()" || true

# A lookup join reads the dimension when an order changes: a relabelled or deleted segment
# reaches only the orders that change afterwards. The backfill recomputes them all.
step "dimension change: segments 1-3 relabelled, segment 4 deleted"
cat >"$evidence/sql/dimension-change.sql" <<SQL
UPDATE $db.segments SET label = 'platinum', version_no = version_no + 1 WHERE id IN (1, 2, 3);
DELETE FROM $db.segments WHERE id = 4;
SQL
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/dimension-change.sql" >/dev/null
sleep 120
cat >"$evidence/sql/dwd-expected.sql" <<SQL
SELECT o.id AS order_id, o.customer_id, s.label AS segment, o.amount, o.state, o.version_no
FROM $db.orders o JOIN $db.segments s ON o.customer_id = s.id;
SQL
cat >"$evidence/sql/dws-expected.sql" <<SQL
SELECT s.label AS segment, COUNT(*) AS order_count, SUM(o.amount) AS amount_total
FROM $db.orders o JOIN $db.segments s ON o.customer_id = s.id GROUP BY s.label;
SQL
bash "$FIXTURES_DIR/sources/sources.sh" mysql-query "$evidence/sql/dwd-expected.sql" >"$evidence/dumps/before-backfill.source.tsv"
bash "$flows/lake.sh" rows "$evidence/dumps/before-backfill.rows.jsonl" "$dwd.orders" >/dev/null
if python3 "$cdc/compare.py" rows --lake "$evidence/dumps/before-backfill.rows.jsonl" \
  --source "$evidence/dumps/before-backfill.source.tsv" --table "$dwd.orders" --key order_id \
  >"$evidence/dumps/before-backfill.verdict.json"; then
  verdict backfill-has-work FAIL "DWD already matched the source join"
else
  verdict backfill-has-work PASS "$(head -c 400 "$evidence/dumps/before-backfill.verdict.json")"
fi

step "a backfill of DWS, an aggregate, is refused"
laketask "backfill-dws-$run" backfill "$dws" segments
if await_task "backfill-dws-$run" refused-stateful 300 Failed \
  && [[ "$(task_field "backfill-dws-$run" '[c["reason"] for c in t["conditions"] if c["type"] == "Finished"][0]')" == StatefulQuery ]]; then
  verdict refused-stateful-backfill PASS "StatefulQuery: $(task_field "backfill-dws-$run" 't.get("message")')"
else
  verdict refused-stateful-backfill FAIL "$(task_field "backfill-dws-$run" 't.get("message")')"
fi

step "suspend $dws_name; backfill $dwd.orders, its first run killed once its upsert committed"
kubectl -n "$JOB_NAMESPACE" patch laketable "$dws_name" --type merge -p '{"spec":{"suspend":true}}'
await laketable "$dws_name" dws-suspended 600 "phase == 'Suspended'" || true
laketask "backfill-$run" backfill "$dwd" orders "  retries: 2"
fenced=""
end=$((SECONDS + 600))
while ((SECONDS < end)); do
  message="$(kubectl -n "$JOB_NAMESPACE" get laketable "$dwd_name" -o jsonpath='{.status.phase}: {.status.message}')"
  if [[ "$message" == "Suspended: suspended with a savepoint while LakeTask backfill-$run/"* ]]; then
    fenced="$message"
    break
  fi
  sleep 5
done
kill_run_committed "backfill-$run" 1 || true
running=""
while [[ "$(task_field "backfill-$run" 't.get("phase")')" =~ ^(Fencing|Running)$ ]]; do
  running+="$(kubectl -n "$JOB_NAMESPACE" get flinkdeployment "$dwd_name-table" -o jsonpath='{.status.jobStatus.state} ')"
  sleep 10
done
if [[ -n "$fenced" && "$running" != *RUNNING* ]]; then
  verdict backfill-fenced PASS "$fenced; DWD job states during the runs: $(tr ' ' '\n' <<<"$running" | sort | uniq -c | tr '\n' ' ')"
else
  verdict backfill-fenced FAIL "fence '$fenced'; job states $running"
fi
# DWS is suspended: the backfill must wait for it, in plain sight.
sleep 120
phase="$(task_field "backfill-$run" 't.get("phase") + " " + str(t.get("waitingFor"))')"
if [[ "$phase" == Converging* && "$phase" == *"$dws_name"* ]]; then
  verdict backfill-waits-for-dws PASS "$phase"
else
  verdict backfill-waits-for-dws FAIL "$phase"
fi
kubectl -n "$JOB_NAMESPACE" patch laketable "$dws_name" --type merge -p '{"spec":{"suspend":false}}'
if await_task "backfill-$run" backfill-succeeded 3000 Succeeded; then
  # At once, without retries: an early Succeeded would show here.
  bash "$FIXTURES_DIR/sources/sources.sh" mysql-query "$evidence/sql/dws-expected.sql" >"$evidence/dumps/at-success.dws.source.tsv"
  bash "$flows/lake.sh" rows "$evidence/dumps/at-success.dws.rows.jsonl" "$dws.segments" >/dev/null
  if python3 "$cdc/compare.py" rows --lake "$evidence/dumps/at-success.dws.rows.jsonl" \
    --source "$evidence/dumps/at-success.dws.source.tsv" --table "$dws.segments" --key segment \
    >"$evidence/dumps/at-success.dws.verdict.json"; then
    verdict dws-converged-at-success PASS "$(cat "$evidence/dumps/at-success.dws.verdict.json")"
  else
    verdict dws-converged-at-success FAIL "$(head -c 600 "$evidence/dumps/at-success.dws.verdict.json")"
  fi
  task_json "backfill-$run" >"$evidence/tasks/backfill-final.json"
  if python3 - "$evidence/tasks/backfill-final.json" >"$work/backfill.txt" <<'PY'
import json, re, sys
status = json.load(open(sys.argv[1]))["status"]
deleted = int(re.search(r"deleted (\d+)", status["result"]["detail"]).group(1))
print(f"attempts {status['attempts']}, snapshot {status['result'].get('snapshotBefore')} -> "
      f"{status['result']['snapshotAfter']}, {status['result']['detail']}; {status['message']}")
history = [f"{a['attempt']} {a['outcome']}" for a in status.get("history", [])]
print(f"history {history}; frontier {status.get('frontier')}")
sys.exit(0 if status["attempts"] == 2 and history == ["1 Failed", "2 Succeeded"]
         and deleted > 0 and not status.get("waitingFor") else 1)
PY
  then
    verdict backfill-retried PASS "$(cat "$work/backfill.txt")"
  else
    verdict backfill-retried FAIL "$(cat "$work/backfill.txt")"
  fi
fi

# ------------------------------------------------------------------------------ correctness
# The backfill relabels rows without a new source version: before images are matched column by
# column, not by version.
compare_query dwd "$evidence/sql/dwd-expected.sql" "$dwd.orders" order_id ""
compare_query dws "$evidence/sql/dws-expected.sql" "$dws.segments" segment ""

# ------------------------------------------------------------------------------- refusals
step "a task on a table no declaration here owns"
laketask "outside-$run" compact "${outside%%.*}" "${outside#*.}"
# Refused because the table is not this namespace's (NotManaged), not because it is missing.
if await_task "outside-$run" refused-outside 300 Failed \
  && [[ "$(task_field "outside-$run" '[c["reason"] for c in t["conditions"] if c["type"] == "Finished"][0]')" == NotManaged ]] \
  && [[ -z "$(kubectl -n "$JOB_NAMESPACE" get jobs -l "colvenna-lake.io/task=outside-$run" -o name)" ]]; then
  verdict refused-outside-ran-nothing PASS "no Job for outside-$run"
else
  verdict refused-outside-ran-nothing FAIL "outside-$run ran or was not refused"
fi

# #171: a tag of a table that has committed no snapshot ends at once as NoSnapshot, with no
# attempt run or retried; deleting the task takes its Failed phase off the operator's metrics,
# which is what ColvennaTaskFailed alerts on.
# operator_metrics <out-file>: the operator's metrics page, through a port-forward of its own on a
# free local port; fails when the page cannot be read (no forward, or not a page of colvenna metrics).
operator_metrics() {
  local log="$work/operator-metrics.log" port pid status=0
  kubectl -n "$OPERATOR_NAMESPACE" port-forward "svc/$OPERATOR_RELEASE-metrics" :9100 >"$log" 2>&1 &
  pid=$!
  port="$(forwarded_port "$log")" && curl -sf --max-time 20 "http://127.0.0.1:$port/metrics" >"$1" || status=1
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  ((status == 0)) && grep -q '^colvenna_' "$1"
}
# failed_sample <task>: the value of the task's colvenna_task_phase{phase="Failed"} sample, nothing when
# the operator does not export it; fails when the metrics cannot be read.
failed_sample() {
  operator_metrics "$work/operator-metrics.txt" || return 1
  awk -v t="laketask=\"$1\"" 'index($0, "colvenna_task_phase{") == 1 && index($0, t) && index($0, "phase=\"Failed\"") {print $NF}' \
    "$work/operator-metrics.txt"
}
step "a tag of $ods.empties, which has no snapshot"
laketask "notag-$run" createTag "$ods" empties "  createTag: {name: notag-$run}"
if await_task "notag-$run" notag-failed 300 Failed; then
  first="$(task_field "notag-$run" '[c["reason"] for c in t["conditions"] if c["type"] == "Finished"][0] + " " + str(t.get("attempts")) + " " + str(len(t.get("history") or [])) + " " + str(t.get("finishedAt"))')"
  jobs="$(kubectl -n "$JOB_NAMESPACE" get jobs -l "colvenna-lake.io/task=notag-$run" -o name | wc -l)"
  read -r reason attempts history finished <<<"$first"
  if [[ "$reason" == NoSnapshot && "$attempts" == None && "$history" == 0 && "$jobs" == 0 ]]; then
    verdict notag-no-snapshot PASS "Failed, NoSnapshot, no attempt, no Job: $(task_field "notag-$run" 't.get("message")')"
  else
    verdict notag-no-snapshot FAIL "reason $reason, attempts $attempts, history $history, $jobs Jobs"
  fi
  sleep 90
  again="$(task_field "notag-$run" 't.get("phase") + " " + str(t.get("attempts")) + " " + str(t.get("finishedAt"))')"
  jobs="$(kubectl -n "$JOB_NAMESPACE" get jobs -l "colvenna-lake.io/task=notag-$run" -o name | wc -l)"
  if [[ "$again" == "Failed None $finished" && "$jobs" == 0 ]]; then
    verdict notag-not-retried PASS "90 s later still Failed at $finished, no attempt, no Job"
  else
    verdict notag-not-retried FAIL "90 s later: $again ($jobs Jobs; finished at $finished first)"
  fi
  sample="$(failed_sample "notag-$run" || echo unreadable)"
  if [[ "$sample" =~ ^1(\.0+)?$ ]]; then
    verdict notag-alerting-metric PASS "colvenna_task_phase{laketask=\"notag-$run\",phase=\"Failed\"} 1, what ColvennaTaskFailed alerts on"
  else
    verdict notag-alerting-metric FAIL "the task's Failed sample: ${sample:-absent}"
  fi
  kubectl -n "$JOB_NAMESPACE" delete laketask "notag-$run" --timeout=2m >/dev/null
  # Cleared only when the metrics were read and the task's sample is not among them.
  cleared="" end=$((SECONDS + 120))
  while ((SECONDS < end)); do
    if sample="$(failed_sample "notag-$run")" && [[ -z "$sample" ]]; then cleared=yes; break; fi
    sleep 10
  done
  if [[ -n "$cleared" ]]; then
    verdict notag-cleared-on-delete PASS "deleted: the operator no longer exports the task's phases"
  else
    verdict notag-cleared-on-delete FAIL "still exported 120 s after the deletion"
  fi
fi

# ---------------------------------------------------------------------------- tokens, deletion
step "finished tasks' tokens; delete the tasks"
names=()
for name in eod refused compact eod-again orphans compact-seg compact-fail expire backfill-dws backfill outside notag; do
  names+=("$name-$run")
done
for name in "${names[@]}"; do
  kubectl -n "$JOB_NAMESPACE" get secret "$name-catalog-token" -o jsonpath='{.data.token}' 2>/dev/null \
    | base64 -d >"$work/$name.token" || true
done
admin_open
statuses=""
for name in "${names[@]}"; do
  [[ -s "$work/$name.token" ]] && statuses+="$name=$(token_status "$work/$name.token") "
done
admin_close
if [[ -n "$statuses" && "$statuses" != *=2* ]]; then
  verdict finished-tokens-refused PASS "$statuses"
else
  verdict finished-tokens-refused FAIL "${statuses:-no tokens}"
fi
kubectl -n "$JOB_NAMESPACE" delete laketask "${names[@]}" --ignore-not-found --timeout=5m
sleep 20
# This run's tasks only: other LakeTask users may share the namespace.
left="$(for name in "${names[@]}"; do kubectl -n "$JOB_NAMESPACE" get jobs -l "colvenna-lake.io/task=$name" -o name; done
  kubectl -n "$JOB_NAMESPACE" get laketable "$dwd_name" -o jsonpath='{.metadata.annotations.colvenna-lake\.io/fenced-by}')"
if [[ -z "$left" ]]; then
  verdict tasks-deleted PASS "no task Job and no fence left"
else
  verdict tasks-deleted FAIL "left: $left"
fi

step "delete $dws_name, $dwd_name and $source_name (Delete)"
kubectl -n "$JOB_NAMESPACE" delete laketable "$dws_name" --timeout=10m
kubectl -n "$JOB_NAMESPACE" delete laketable "$dwd_name" --timeout=10m
kubectl -n "$JOB_NAMESPACE" delete lakesource "$source_name" --timeout=10m
kubectl -n "$JOB_NAMESPACE" delete pod "airflow-$run" --ignore-not-found >/dev/null
kubectl -n "$JOB_NAMESPACE" delete configmap "airflow-$run-dags" --ignore-not-found >/dev/null
kubectl -n "$JOB_NAMESPACE" delete rolebinding,role,serviceaccount "airflow-$run" --ignore-not-found >/dev/null
python3 "$cdc/mysql_contract.py" reset "$db" >"$evidence/sql/reset.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/reset.sql" >/dev/null

kubectl -n "$JOB_NAMESPACE" get events -o json >"$evidence/events.json"
echo "verdicts:"
cat "$evidence/verdicts.tsv"
exit "$failed"
