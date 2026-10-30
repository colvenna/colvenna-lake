#!/usr/bin/env bash
# Acceptance on EKS: declared layered tables over a declared MySQL source, under continuous source
# writes. Writes PASS/FAIL lines to <evidence>/verdicts.tsv and fails if any check fails.
#
# A LakeSource ingests orders and a segments dimension into ODS. Two LakeTables keep a DWD table
# (orders with their segment, a lookup join, as the layered-processing experiment had) and a DWS
# table (orders and amount per segment, from the DWD changelog) running. On top of them:
# - a dimension change, then updates of the orders it affects: their old rows must leave their
#   old segment in DWS (the layered-processing experiment stopped at new orders after a dimension
#   change);
# - invalid declarations, each refused for its reason: SQL that does not parse, a table the
#   query reads without declaring it, a join whose updates can arrive out of key order, an
#   upstream outside the namespace, two tables reading each other, a table reading itself, and a
#   target another table owns;
# - a change of the output schema refused while the job keeps running;
# - a compatible upgrade (checkpoint interval) restored from its savepoint, and an incompatible
#   one (the plan changes, so the state no longer maps) rolled back from the savepoint to the
#   spec that ran: once after the old job's first checkpoint, once before it (#279);
# - suspend and resume from a savepoint, DWS frozen meanwhile, and a TaskManager loss;
# - then, with the writer stopped: DWD rows equal the source join, DWS rows the source
#   aggregate, and both full changelogs replay without a duplicate or a stale before image;
# - a job whose upstream position is gone (its consumer dropped while suspended) refusing to
#   start while the source keeps changing, its rows untouched (REBUILD_REQUIRED, as the
#   layered-processing experiment found);
# - deletion: consumers dropped, a target another table reads retained, Delete dropping the
#   other, tokens refused.
#
# usage: OUTSIDE_TABLE=<database.table> run-tables.sh <run-id> <evidence-dir>; OUTSIDE_TABLE is an
# existing catalog table no LakeSource or LakeTable of the namespace owns, e.g. the flow_<run>.orders
# of tests/acceptance/catalog/run-flink-flow.sh.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-tables.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-tables.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
outside="${OUTSIDE_TABLE:?OUTSIDE_TABLE=<database.table>: an existing table outside this namespace, e.g. flow_<run>.orders that tests/acceptance/catalog/run-flink-flow.sh <run> made}"
sha="$(committed_sha)"
flows="$REPO_DIR/tests/acceptance"
cdc="$flows/cdc"
mkdir -p "$evidence"/{status,dumps,sql,tables}
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
  python3 -c 'import json,sys; s=json.load(open(sys.argv[1]))["table" if "table" in open(sys.argv[1]).read(12) else "source"].get("status",{}); print(s.get("phase"), s.get("message")); [print(c["type"], c["status"], c.get("reason"), c.get("message")) for c in s.get("conditions",[])]' \
    "$file" 2>/dev/null || true
  return 1
}

# warnings <name> <reason>: how many Warning events with this reason the object has.
warnings() {
  kubectl -n "$JOB_NAMESPACE" get events --field-selector "involvedObject.name=$1,type=Warning,reason=$2" \
    -o name | wc -l
}

# warning_event <name> <reason>: a Warning event with this reason was recorded on the object.
warning_event() {
  (($(warnings "$1" "$2") > 0))
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

# patch_query <name> <query>: replaces the declared query.
patch_query() {
  kubectl -n "$JOB_NAMESPACE" patch laketable "$1" --type merge \
    -p "$(python3 -c 'import json,sys; print(json.dumps({"spec": {"query": sys.argv[1]}}))' "$2")"
}

# token_status <token-file>: the HTTP status colvenna-catalog gives this bearer token. Needs admin_open.
token_status() {
  printf 'header = "Authorization: Bearer %s"\n' "$(cat "$1")" >"$ADMIN_DIR/probe.curlrc"
  curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/probe.curlrc" \
    "https://localhost:$ADMIN_PORT/v1/config?warehouse=colvenna"
}

# table_http <db> <table>: the HTTP status of the table's metadata, as the admin sees it.
table_http() {
  curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/curlrc" \
    "https://localhost:$ADMIN_PORT/v1/colvenna/databases/$1/tables/$2"
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

# The shared nodes (no capacity node groups): half a CPU per JobManager and
# TaskManager, bursting to one.
small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
work="$(mktemp -d)"
chmod 700 "$work"
trap 'admin_close; rm -rf "$work"' EXIT
ensure_rds_ca "$JOB_NAMESPACE"

# --------------------------------------------------------------------------------- the source
db="tbl_$run" ods="ods_tbl_$run" dwd="dwd_tbl_$run" dws="dws_tbl_$run"
source_name="lts-$run" dwd_name="dwd-$run" dws_name="dws-$run"
step "mysql fixture $db"
{
  python3 "$cdc/mysql_contract.py" reset "$db"
  python3 "$cdc/mysql_contract.py" setup "$db"
  python3 "$cdc/mysql_contract.py" table "$db" segments
  cat <<SQL
USE $db;
SET SESSION cte_max_recursion_depth = 100000;
INSERT INTO customers (id, name, region, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 200)
  SELECT i, CONCAT('customer-', i), ELT(1 + i % 4, 'north', 'south', 'east', 'west'), 1 FROM n;
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
  tables: {include: [orders, segments]}
  target: {database: $ods, bucket: 2}
  job: $small
  deletionPolicy: Delete
YAML
step "writer start"
bash "$cdc/writer.sh" start mysql "$db" "tw-$run"
kubectl apply -f "$evidence/source.yaml"
await lakesource "$source_name" source-ready 900 \
  "state('orders') == 'Ready' and state('segments') == 'Ready' and live_all()" || true

# ------------------------------------------------------------------------- the layered tables
dwd_query="SELECT o.id AS order_id, o.customer_id, s.label AS segment, o.amount, o.state, o.version_no
FROM (SELECT *, PROCTIME() AS pt FROM $ods.orders) AS o
JOIN $ods.segments FOR SYSTEM_TIME AS OF o.pt AS s ON o.customer_id = s.id"
dws_query="SELECT segment, COUNT(*) AS order_count, SUM(amount) AS amount_total
FROM $dwd.orders GROUP BY segment"
# Snapshots are kept for a day so the full changelogs can be replayed at the end.
keep="  deletionPolicy: Delete
  tableOptions: {snapshot.time-retained: 1 d}
  job: $small"
step "apply $dwd_name and $dws_name together; DWS waits for its upstream"
laketable "$dws_name" "$dws" segments segment "$dwd.orders" "$dws_query" "$keep"
laketable "$dwd_name" "$dwd" orders order_id "$ods.orders,$ods.segments" "$dwd_query" "$keep"
await laketable "$dws_name" dws-waits-for-upstream 300 \
  "phase == 'Pending' and why('DependenciesReady') == 'UpstreamMissing'" || true
await laketable "$dwd_name" dwd-running 900 \
  "phase == 'Running' and live_all() and validation.get('streams') == ['$ods.orders'] \
  and validation.get('lookups') == ['$ods.segments']" || true
await laketable "$dws_name" dws-running 900 \
  "phase == 'Running' and live_all() and validation.get('streams') == ['$dwd.orders']" || true

# A lookup join reads the dimension again when an order changes, so the retraction of an order's
# old row would carry its new segment. The target's changelog comes from the rows it held
# (changelog-producer lookup), so DWS moves the order out of the segment it was counted in.
step "dimension change: segments 1-3 become platinum, then their orders change"
echo "UPDATE $db.segments SET label = 'platinum', version_no = version_no + 1 WHERE id IN (1, 2, 3);" \
  >"$evidence/sql/dimension-change.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/dimension-change.sql" >/dev/null
# Through CDC into ODS (a checkpoint) and into the lookup cache (its refresh interval).
sleep 90
echo "UPDATE $db.orders SET version_no = version_no + 1 WHERE customer_id IN (1, 2, 3);" \
  >"$evidence/sql/dimension-orders.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/dimension-orders.sql" >/dev/null

# ------------------------------------------------------------------------ refused declarations
step "invalid declarations"
laketable "bad-sql-$run" "$dwd" bad_sql order_id "$ods.orders" "SELEC id AS order_id FROM $ods.orders"
laketable "undeclared-$run" "$dwd" undeclared id "$ods.orders" "SELECT id, label FROM $ods.segments"
laketable "extra-$run" "$dwd" extra order_id "$ods.orders,$ods.segments" \
  "SELECT id AS order_id, amount FROM $ods.orders"
laketable "unordered-$run" "$dwd" unordered order_id "$ods.orders,$ods.segments" \
  "SELECT o.id AS order_id, s.label AS segment FROM $ods.orders AS o JOIN $ods.segments AS s ON o.customer_id = s.id"
laketable "outside-$run" "$dwd" outside id "$outside" "SELECT * FROM $outside"
laketable "cycle-a-$run" "$dwd" cycle_a k "$dwd.cycle_b" "SELECT k FROM $dwd.cycle_b"
laketable "cycle-b-$run" "$dwd" cycle_b k "$dwd.cycle_a" "SELECT k FROM $dwd.cycle_a"
laketable "self-$run" "$dwd" self k "$dwd.self" "SELECT k FROM $dwd.self"
laketable "conflict-$run" "$dwd" orders order_id "$ods.orders" "SELECT id AS order_id FROM $ods.orders"
await laketable "bad-sql-$run" refused-invalid-sql 600 "phase == 'Failed' and why('QueryValid') == 'InvalidQuery'" || true
await laketable "undeclared-$run" refused-undeclared-upstream 600 \
  "phase == 'Failed' and why('QueryValid') == 'UpstreamNotDeclared'" || true
await laketable "extra-$run" refused-unread-upstream 600 \
  "phase == 'Failed' and why('QueryValid') == 'UpstreamMismatch'" || true
await laketable "unordered-$run" refused-unordered-changelog 600 \
  "phase == 'Failed' and why('QueryValid') == 'UnorderedChangelog'" || true
await laketable "outside-$run" refused-outside-upstream 300 \
  "phase == 'Failed' and why('DependenciesReady') == 'UpstreamNotAllowed'" || true
await laketable "cycle-a-$run" refused-cycle-a 300 "phase == 'Failed' and why('DependenciesReady') == 'DependencyCycle'" || true
await laketable "cycle-b-$run" refused-cycle-b 300 "phase == 'Failed' and why('DependenciesReady') == 'DependencyCycle'" || true
await laketable "self-$run" refused-self-read 300 "phase == 'Failed' and why('SpecValid') == 'InvalidSpec'" || true
await laketable "conflict-$run" refused-target-conflict 300 "phase == 'Failed' and why('SpecValid') == 'TargetConflict'" || true
admin_open
created=""
for t in bad_sql undeclared extra unordered outside cycle_a cycle_b self; do
  [[ "$(table_http "$dwd" "$t")" == 404 ]] || created+=" $dwd.$t"
done
admin_close
created+="$(kubectl -n "$JOB_NAMESPACE" get flinkdeployment -o name \
  | grep -E "(bad-sql|undeclared|extra|unordered|outside|cycle-a|cycle-b|self|conflict)-$run" || true)"
if [[ -z "$created" ]]; then
  verdict refused-created-nothing PASS "no target table and no job for any refused declaration"
else
  verdict refused-created-nothing FAIL "created:$created"
fi
for t in bad-sql undeclared extra unordered outside cycle-a cycle-b self conflict; do
  kubectl -n "$JOB_NAMESPACE" delete laketable "$t-$run" --wait=true >/dev/null
done

# ---------------------------------------------------------------------------- upgrades
step "a change of the output schema is refused; the job keeps running"
patch_query "$dws_name" "SELECT segment, COUNT(*) AS order_count, SUM(amount) AS amount_total, MAX(amount) AS amount_max
FROM $dwd.orders GROUP BY segment"
await laketable "$dws_name" schema-change-refused 600 \
  "phase == 'Degraded' and why('TargetReady') == 'SchemaChanged' and live_all()" || true
patch_query "$dws_name" "$dws_query"
await laketable "$dws_name" schema-change-reverted 600 \
  "phase == 'Running' and condition('SpecApplied') == 'True' and live_all()" || true

step "compatible upgrade: DWD checkpoint interval 30s -> 20s"
kubectl -n "$JOB_NAMESPACE" patch laketable "$dwd_name" --type merge -p '{"spec":{"job":{"checkpointInterval":"20s"}}}'
sleep 20
await laketable "$dwd_name" compatible-upgrade-restored 900 \
  "phase == 'Running' and live_all() and not upgrading and 'savepoint' in str(restored())" || true
if [[ "$(kubectl -n "$JOB_NAMESPACE" get flinkdeployment "$dwd_name-table" \
  -o jsonpath='{.spec.flinkConfiguration.execution\.checkpointing\.interval}')" == 20s ]]; then
  verdict compatible-upgrade-applied PASS "execution.checkpointing.interval=20s"
else
  verdict compatible-upgrade-applied FAIL "interval not applied"
fi

# DWS restarted alone with a savepoint, restored from exactly that savepoint.
step "compatible upgrade: DWS TaskManager memory"
kubectl -n "$JOB_NAMESPACE" patch laketable "$dws_name" --type merge -p '{"spec":{"job":{"taskManager":{"memory":"4096m"}}}}'
sleep 20
if await laketable "$dws_name" dws-upgrade-restored 900 \
  "phase == 'Running' and live_all() and not upgrading and 'savepoint' in str(restored())"; then
  taken="$(kubectl -n "$JOB_NAMESPACE" get flinkdeployment "$dws_name-table" -o jsonpath='{.status.jobStatus.upgradeSavepointPath}')"
  used="$(kubectl -n "$JOB_NAMESPACE" get laketable "$dws_name" -o jsonpath='{.status.job.restoredFrom}')"
  if [[ -n "$taken" && "$taken" == "$used" ]]; then
    verdict dws-restored-from-its-upgrade-savepoint PASS "$used"
  else
    verdict dws-restored-from-its-upgrade-savepoint FAIL "took $taken, restored $used"
  fi
fi

# dws_interval <interval> <label> <expression>: DWS's checkpoint interval changed, a compatible
# upgrade, then awaited: running again, restored from that upgrade's savepoint, and <expression>.
dws_interval() {
  local before
  before="$(kubectl -n "$JOB_NAMESPACE" get laketable "$dws_name" -o jsonpath='{.status.job.restoredFrom}')"
  kubectl -n "$JOB_NAMESPACE" patch laketable "$dws_name" --type merge \
    -p "{\"spec\":{\"job\":{\"checkpointInterval\":\"$1\"}}}"
  sleep 20
  await laketable "$dws_name" "$2" 900 "phase == 'Running' and live_all() and not upgrading \
    and 'savepoint' in str(restored()) and restored() != '$before' and $3" || true
}

# A filter before the aggregate changes the plan, so the savepoint's aggregate state no longer
# maps to an operator: the new job cannot start, and the operator returns to what ran.
filtered="SELECT segment, COUNT(*) AS order_count, SUM(amount) AS amount_total
FROM $dwd.orders WHERE amount >= 0 GROUP BY segment"

# incompatible_upgrade <began> <expression> <rolled-back> <alerted> <reverted>: DWS with the
# filter. While the upgrade is in flight, <expression> on what it recorded (status.upgrade) says
# how it began; then the rollback from the savepoint, another Warning UpgradeRolledBack, and the
# query reverted running again.
incompatible_upgrade() {
  local rollbacks
  rollbacks="$(warnings "$dws_name" UpgradeRolledBack)"
  patch_query "$dws_name" "$filtered"
  await laketable "$dws_name" "$1" 300 "upgrading and $2" || true
  if await laketable "$dws_name" "$3" 1500 \
    "rolled_back and why('SpecApplied') == 'RolledBack' and live_all() and 'savepoint' in str(restored())" \
    && (($(warnings "$dws_name" UpgradeRolledBack) > rollbacks)); then
    verdict "$4" PASS "another Warning UpgradeRolledBack recorded"
  else
    verdict "$4" FAIL "no new rollback warning"
  fi
  patch_query "$dws_name" "$dws_query"
  await laketable "$dws_name" "$5" 600 \
    "phase == 'Running' and condition('SpecApplied') == 'True' and not rolled_back and live_all()" || true
}

# After the old job's first checkpoint. A checkpoint the old job triggered between the operator's
# reading of its checkpoints and its savepoint would make the rollback's proof fail, on the safe
# side (colvenna-operator-lld.md, 失败回退): with five minutes between checkpoints, and the upgrade
# beginning about a minute after the first one, none falls there.
step "incompatible upgrade after the old job's first checkpoint: DWS checkpoints every 5 min"
dws_interval 5m dws-checkpointed "checkpointed"
incompatible_upgrade incompatible-upgrade-upgrading "upgrade.get('fromCheckpointsCompleted', 0) > 0" \
  incompatible-upgrade-rolled-back incompatible-upgrade-alerted rolled-back-change-reverted

# Before the old job's first checkpoint (#279): the upgrade follows the savepoint the old job
# restored from. A day-long interval puts that first checkpoint at a random point of the day.
step "incompatible upgrade before the old job's first checkpoint: DWS checkpoints daily"
dws_interval 1440m dws-no-checkpoint-yet "not checkpointed"
incompatible_upgrade before-checkpoint-upgrading "upgrade.get('fromCheckpointsCompleted') == 0" \
  before-checkpoint-rolled-back before-checkpoint-alerted before-checkpoint-change-reverted
step "DWS checkpoints every 30 s again"
dws_interval 30s dws-checkpoints-again "checkpointed"

step "suspend DWD, let the source run ahead, resume"
before="$(kubectl -n "$JOB_NAMESPACE" get laketable "$dwd_name" -o jsonpath='{.status.job.restoredFrom}')"
kubectl -n "$JOB_NAMESPACE" patch laketable "$dwd_name" --type merge -p '{"spec":{"suspend":true}}'
await laketable "$dwd_name" dwd-suspended 600 "phase == 'Suspended'" || true
# What DWS sees of DWD: no new snapshot while DWD is down, so DWS holds still too.
sleep 30
frozen="$(kubectl -n "$JOB_NAMESPACE" get laketable "$dws_name" -o jsonpath='{.status.upstream[0].latestSnapshot}')"
sleep 60
if [[ -n "$frozen" && "$frozen" == "$(kubectl -n "$JOB_NAMESPACE" get laketable "$dws_name" -o jsonpath='{.status.upstream[0].latestSnapshot}')" ]]; then
  verdict dwd-frozen-while-suspended PASS "DWD stayed at snapshot $frozen while the source kept writing"
else
  verdict dwd-frozen-while-suspended FAIL "DWD moved from snapshot $frozen while suspended"
fi
kubectl -n "$JOB_NAMESPACE" patch laketable "$dwd_name" --type merge -p '{"spec":{"suspend":false}}'
sleep 20
await laketable "$dwd_name" dwd-resumed-from-savepoint 900 \
  "phase == 'Running' and live_all() and restored() != '$before' and 'savepoint' in str(restored())" || true

step "fault: delete the DWS TaskManager"
kubectl -n "$JOB_NAMESPACE" delete pod -l "app=$dws_name-table,component=taskmanager" --wait=false
sleep 30
await laketable "$dws_name" dws-taskmanager-loss 900 "live_all() and 'chk-' in str(restored())" || true

# ------------------------------------------------------------------------------ correctness
step "writer stop: $(bash "$cdc/writer.sh" stop "tw-$run")"
await laketable "$dwd_name" dwd-caught-up 600 "lag('$ods.orders') == 0 and live_all()" || true
await laketable "$dws_name" dws-caught-up 600 "lag('$dwd.orders') == 0 and live_all()" || true
cat >"$evidence/sql/dwd-expected.sql" <<SQL
SELECT o.id AS order_id, o.customer_id, s.label AS segment, o.amount, o.state, o.version_no
FROM $db.orders o JOIN $db.segments s ON o.customer_id = s.id;
SQL
cat >"$evidence/sql/dws-expected.sql" <<SQL
SELECT s.label AS segment, COUNT(*) AS order_count, SUM(o.amount) AS amount_total
FROM $db.orders o JOIN $db.segments s ON o.customer_id = s.id GROUP BY s.label;
SQL
compare_query dwd "$evidence/sql/dwd-expected.sql" "$dwd.orders" order_id version_no
compare_query dws "$evidence/sql/dws-expected.sql" "$dws.segments" segment ""

# A consumer gone while the job was down means changes may have expired unread; the job
# must refuse to start rather than skip them. The source keeps writing, so a job that ran anyway
# would change DWD's rows.
step "drop DWD's consumer on $ods.orders while suspended, then resume under writes"
bash "$cdc/writer.sh" start mysql "$db" "tw2-$run"
consumer="$(kubectl -n "$JOB_NAMESPACE" get laketable "$dwd_name" -o jsonpath='{.status.upstream[0].consumerId}')"
kubectl -n "$JOB_NAMESPACE" patch laketable "$dwd_name" --type merge -p '{"spec":{"suspend":true}}'
await laketable "$dwd_name" dwd-suspended-again 600 "phase == 'Suspended'" || true
bash "$flows/lake.sh" drop-consumer "$ods.orders" "$consumer"
bash "$flows/lake.sh" consumers "$evidence/dumps/ods-consumers-dropped.jsonl" "$ods.orders" >/dev/null
bash "$flows/lake.sh" rows "$evidence/dumps/dwd-before-refusal.rows.jsonl" "$dwd.orders" >/dev/null
kubectl -n "$JOB_NAMESPACE" patch laketable "$dwd_name" --type merge -p '{"spec":{"suspend":false}}'
if await laketable "$dwd_name" dwd-rebuild-required 900 \
  "why('JobRunning') == 'RebuildRequired' and not live_all()" \
  && warning_event "$dwd_name" RebuildRequired; then
  sleep 60
  bash "$flows/lake.sh" rows "$evidence/dumps/dwd-after-refusal.rows.jsonl" "$dwd.orders" >/dev/null
  batches="$(bash "$cdc/writer.sh" stop "tw2-$run")"
  step "writer stop: $batches"
  if [[ "${batches#batches=}" -eq 0 ]]; then
    verdict dwd-rows-untouched FAIL "the source did not change: $batches"
  elif cmp -s <(sort "$evidence/dumps/dwd-before-refusal.rows.jsonl") <(sort "$evidence/dumps/dwd-after-refusal.rows.jsonl"); then
    verdict dwd-rows-untouched PASS "the refused job wrote nothing while the source changed ($batches)"
  else
    verdict dwd-rows-untouched FAIL "DWD rows changed"
  fi
else
  verdict dwd-rows-untouched FAIL "the job did not refuse to start"
  step "writer stop: $(bash "$cdc/writer.sh" stop "tw2-$run")"
fi

# -------------------------------------------------------------------------------- deletion
step "delete $dwd_name while $dws_name reads it, then $dws_name (both Delete)"
for name in "$dws_name" "$dwd_name"; do
  kubectl -n "$JOB_NAMESPACE" get secret "$name-catalog-token" -o jsonpath='{.data.token}' | base64 -d >"$work/$name.token"
done
kubectl -n "$JOB_NAMESPACE" delete laketable "$dwd_name" --timeout=10m
retained="$(warning_event "$dwd_name" TargetRetained && echo yes || echo no)"
kubectl -n "$JOB_NAMESPACE" delete laketable "$dws_name" --timeout=10m
bash "$flows/lake.sh" consumers "$evidence/dumps/dwd-consumers.jsonl" "$dwd.orders" >/dev/null
admin_open
dws_table="$(table_http "$dws" segments)" dwd_table="$(table_http "$dwd" orders)"
dws_token="$(token_status "$work/$dws_name.token")" dwd_token="$(token_status "$work/$dwd_name.token")"
admin_close
if [[ "$retained" == yes && "$dwd_table" == 200 && "$dws_table" == 404 ]] \
  && [[ "$dws_token" == 401 && "$dwd_token" == 401 ]] \
  && [[ ! -s "$evidence/dumps/dwd-consumers.jsonl" ]] \
  && [[ -z "$(kubectl -n "$JOB_NAMESPACE" get flinkdeployment -o name | grep -- "-$run-table" || true)" ]]; then
  verdict tables-deleted PASS "jobs gone, DWS's consumer dropped, $dwd.orders retained (read by DWS), $dws.segments dropped, tokens 401"
else
  verdict tables-deleted FAIL "retained=$retained dwd=$dwd_table dws=$dws_table tokens=$dws_token/$dwd_token"
fi

step "delete $source_name (Delete)"
kubectl -n "$JOB_NAMESPACE" delete lakesource "$source_name" --timeout=10m
python3 "$cdc/mysql_contract.py" reset "$db" >"$evidence/sql/reset.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/reset.sql" >/dev/null

kubectl -n "$JOB_NAMESPACE" get events -o json >"$evidence/events.json"
echo "verdicts:"
cat "$evidence/verdicts.tsv"
exit "$failed"
