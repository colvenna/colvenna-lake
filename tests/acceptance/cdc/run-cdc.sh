#!/usr/bin/env bash
# Acceptance on EKS: declared MySQL and PostgreSQL sources, from admission to deletion, under
# continuous source writes that start before the declaration. Writes PASS/FAIL lines to
# <evidence>/verdicts.tsv and fails if any check fails.
#
# MySQL (database cdc_<run>, LakeSource cdc-<run>): admission of compliant tables (one of every
#   accepted type) and rejection of broken ones, each for its reason, with the server verified
#   against the RDS CA; a new table enrolled while written to; a supported schema change; an
#   unsupported one failing only its table, which stays out when deselected and selected again;
#   a resource change and a declared parallelism the job keeps; TaskManager and JobManager loss
#   with each restart restored from a checkpoint; suspend and resume; rows and full changelog
#   against the source; a job deleted behind the operator's back failing its tables instead of
#   snapshotting again; deletion keeping the tables and revoking the identity.
# PostgreSQL (database pg_<run>, LakeSource pg-<run>): the same admission, a partitioned table read
#   from its leaves, a new table enrolled while written to, an added column, an unsupported change
#   whose table leaves the job and whose slot is dropped, a resource change, JobManager loss,
#   suspend and resume, rows and changelog, then deletion that drops the tables and the slots.
#
# usage: run-cdc.sh <run-id> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-cdc.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-cdc.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
flows="$REPO_DIR/tests/acceptance"
cdc="$flows/cdc"
mkdir -p "$evidence"/{status,dumps,sql}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
for image in operator mysql postgres; do
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

# capture <source> <file>: the LakeSource and its deployments, for status.py.
capture() {
  {
    printf '{"source":'
    kubectl -n "$JOB_NAMESPACE" get lakesource "$1" -o json
    printf ',"deployments":'
    kubectl -n "$JOB_NAMESPACE" get flinkdeployment -l "colvenna-lake.io/source=$1" -o json \
      | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["items"]))'
    printf '}'
  } >"$2"
}

# await <source> <label> <timeout-seconds> <expression>: polls until status.py says it holds.
await() {
  local source="$1" label="$2" end=$((SECONDS + $3)) started=$SECONDS file="$evidence/status/$2.json"
  while ((SECONDS < end)); do
    if capture "$source" "$file" 2>/dev/null && python3 "$flows/status.py" check "$file" "$4"; then
      verdict "$label" PASS "$((SECONDS - started))s"
      return 0
    fi
    sleep 10
  done
  # Before the run cleans up: what the readiness depended on (#245).
  source_diagnostics "$source" "$evidence/diagnostics/$label"
  verdict "$label" FAIL "not within $3s: $4"
  python3 "$flows/status.py" tables "$file" || true
  return 1
}

# converge <kind> <source-db> <target-db> <table> <key>: the target's rows equal the source's
# (retried while the job catches up), then its full changelog replays correctly.
converge() {
  local kind="$1" db="$2" target="$3" table="$4" key="$5" attempt rows source_rows
  rows="$evidence/dumps/$table.rows.jsonl"
  source_rows="$evidence/dumps/$table.source.tsv"
  for attempt in $(seq 1 20); do
    bash "$FIXTURES_DIR/sources/sources.sh" "$kind-table" "$db" "$table" >"$source_rows"
    bash "$flows/lake.sh" rows "$rows" "$target.$table" >/dev/null
    if python3 "$cdc/compare.py" rows --lake "$rows" --source "$source_rows" \
      --table "$target.$table" --key "$key" >"$evidence/dumps/$table.rows-verdict.json"; then
      verdict "rows-$target.$table" PASS "$(cat "$evidence/dumps/$table.rows-verdict.json")"
      bash "$flows/lake.sh" changelog "$evidence/dumps/$table.changelog.jsonl" "$target.$table" >/dev/null
      if python3 "$cdc/compare.py" changelog --lake "$evidence/dumps/$table.changelog.jsonl" \
        --rows "$rows" --table "$target.$table" --key "$key" \
        >"$evidence/dumps/$table.changelog-verdict.json"; then
        verdict "changelog-$target.$table" PASS "$(cat "$evidence/dumps/$table.changelog-verdict.json")"
      else
        verdict "changelog-$target.$table" FAIL "$(cat "$evidence/dumps/$table.changelog-verdict.json")"
      fi
      return 0
    fi
    echo "attempt $attempt: $(cat "$evidence/dumps/$table.rows-verdict.json")"
    sleep 20
  done
  verdict "rows-$target.$table" FAIL "$(cat "$evidence/dumps/$table.rows-verdict.json")"
}

# warning_event <source> <reason>: a Warning event with this reason was recorded on the source.
warning_event() {
  grep -qx "$2" <<<"$(kubectl -n "$JOB_NAMESPACE" get events --field-selector "involvedObject.name=$1,type=Warning" \
    -o jsonpath='{range .items[*]}{.reason}{"\n"}{end}')"
}

# restored <source> <label> <deployment>: the deployment's running job restored from a checkpoint.
restored() {
  if await "$1" "$2" 300 "live('$3') and 'chk-' in str(restored('$3'))"; then
    python3 -c 'import json,sys; s=json.load(open(sys.argv[1]))["source"]["status"]; print(sys.argv[3] + ": " + str([j.get("restoredFrom") for j in s["jobs"] if j["deployment"] == sys.argv[2]][0]))' \
      "$evidence/status/$2.json" "$3" "$2"
  fi
}

# token_status <token-file>: the HTTP status colvenna-catalog gives this bearer token. Needs admin_open.
token_status() {
  printf 'header = "Authorization: Bearer %s"\n' "$(cat "$1")" >"$ADMIN_DIR/probe.curlrc"
  curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/probe.curlrc" \
    "https://localhost:$ADMIN_PORT/v1/config?warehouse=colvenna"
}

# The shared nodes (no capacity node groups): half a CPU per JobManager and
# TaskManager, bursting to one.
small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
work="$(mktemp -d)"
chmod 700 "$work"
trap 'admin_close; rm -rf "$work"' EXIT
ensure_rds_ca "$JOB_NAMESPACE"

# ------------------------------------------------------------------------------------- MySQL
mydb="cdc_$run" mytarget="ods_cdc_$run" myname="cdc-$run"
step "mysql fixture $mydb"
{
  python3 "$cdc/mysql_contract.py" reset "$mydb"
  python3 "$cdc/mysql_contract.py" setup "$mydb"
  cat <<SQL
USE $mydb;
SET SESSION cte_max_recursion_depth = 100000;
INSERT INTO customers (id, name, region, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 200)
  SELECT i, CONCAT('customer-', i), ELT(1 + i % 4, 'north', 'south', 'east', 'west'), 1 FROM n;
INSERT INTO orders (id, customer_id, amount, state, note, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 5000)
  SELECT i, 1 + i % 200, ROUND(i * 1.25, 2), 'created', NULL, 1 FROM n;
INSERT INTO tokens (tenant, id, token, version_no) VALUES (1, 1, UNHEX(MD5('t1')), 1);
INSERT INTO blobs (id, payload, version_no) VALUES (1, UNHEX('FF00FE01'), 1);
SQL
} >"$evidence/sql/mysql-setup.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/mysql-setup.sql" >/dev/null

cat >"$evidence/mysql-source.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: $myname, namespace: $JOB_NAMESPACE}
spec:
  type: mysql
  connection:
    host: $(stack_output "$TEST_MYSQL_STACK" MySqlEndpoint)
    database: $mydb
    credentialsSecret: {name: mysql-cdc}
    inspectorSecret: {name: mysql-inspect}
    caConfigMap: rds-ca
    serverTimeZone: UTC
  target:
    database: $mytarget
    bucket: 2
  job: $small
YAML
# Writes run through the initial snapshot, so the changelog check covers the snapshot's overlap
# with the binlog it continues from.
step "writer start"
bash "$cdc/writer.sh" start mysql "$mydb" "w-$run"
step "apply $myname"
kubectl apply -f "$evidence/mysql-source.yaml"
await "$myname" mysql-admission 900 "state('orders') == 'Ready' and state('customers') == 'Ready' \
  and state('typed') == 'Ready' and reason('no_key') == 'NoPrimaryKey' \
  and reason('no_guard') == 'VersionGuardMissing' and reason('blobs') == 'UnsupportedBinaryColumn' \
  and reason('tokens') == 'UnsupportedBinaryColumn' and condition('SourceVerified') == 'True' \
  and condition('TargetIsolated') == 'True' and live_all()" || true

step "new table invoices, written to from the start"
{
  python3 "$cdc/mysql_contract.py" table "$mydb" invoices
  cat <<SQL
SET SESSION cte_max_recursion_depth = 100000;
INSERT INTO $mydb.invoices (id, label, amount, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 3000)
  SELECT i, CONCAT('inv-', i), i * 2, 1 FROM n;
SQL
} >"$evidence/sql/mysql-new-table.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/mysql-new-table.sql" >/dev/null
await "$myname" mysql-new-table-enrolled 900 "state('invoices') == 'Ready' and live_all()" || true
restored "$myname" mysql-new-table-restored "$myname-cdc"

step "supported schema change"
cat >"$evidence/sql/mysql-schema-supported.sql" <<SQL
USE $mydb;
ALTER TABLE orders ADD COLUMN channel VARCHAR(16) NULL;
ALTER TABLE orders MODIFY note VARCHAR(128) NULL;
UPDATE orders SET channel = 'web', note = REPEAT('n', 100), version_no = version_no + 1 WHERE id <= 20;
SQL
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/mysql-schema-supported.sql" >/dev/null

step "new table ledger, then an unsupported change on it"
{
  python3 "$cdc/mysql_contract.py" table "$mydb" ledger
  echo "INSERT INTO $mydb.ledger (id, label, amount, version_no) VALUES (1, 'l1', 1, 1), (2, 'l2', 2, 1);"
} >"$evidence/sql/mysql-ledger.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/mysql-ledger.sql" >/dev/null
await "$myname" mysql-ledger-enrolled 900 "state('ledger') == 'Ready' and live_all()" || true
cat >"$evidence/sql/mysql-schema-unsupported.sql" <<SQL
USE $mydb;
ALTER TABLE ledger MODIFY amount VARCHAR(32) NOT NULL;
UPDATE ledger SET amount = 'n/a', version_no = version_no + 1 WHERE id = 1;
SQL
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/mysql-schema-unsupported.sql" >/dev/null
if await "$myname" mysql-unsupported-change-isolated 900 \
  "state('ledger') == 'Failed' and reason('ledger') == 'JobFailed' and state('orders') == 'Ready' \
  and state('invoices') == 'Ready' and live_all()" \
  && warning_event "$myname" TableFailed; then
  verdict mysql-unsupported-change-alerted PASS "Warning TableFailed recorded"
else
  verdict mysql-unsupported-change-alerted FAIL "no Warning TableFailed"
fi

# A failed table's target missed changes: deselected and selected again, it must not snapshot
# into the data it holds.
step "deselect ledger, then select it again"
kubectl -n "$JOB_NAMESPACE" patch lakesource "$myname" --type merge -p '{"spec":{"tables":{"exclude":["ledger"]}}}'
await "$myname" mysql-deselected 300 "'ledger' not in tables and live_all()" || true
kubectl -n "$JOB_NAMESPACE" patch lakesource "$myname" --type merge -p '{"spec":{"tables":{"exclude":[]}}}'
await "$myname" mysql-reselected-refused 300 \
  "state('ledger') == 'Rejected' and reason('ledger') == 'TargetNotEmpty' and live_all()" || true

step "change the declaration: TaskManager memory"
kubectl -n "$JOB_NAMESPACE" patch lakesource "$myname" --type merge \
  -p '{"spec":{"job":{"taskManager":{"memory":"4096m"}}}}'
sleep 20
await "$myname" mysql-declaration-change 900 "live_all() and condition('SpecApplied') == 'True'" || true
if [[ "$(kubectl -n "$JOB_NAMESPACE" get flinkdeployment "$myname-cdc" -o jsonpath='{.spec.taskManager.resource.memory}')" == 4096m ]]; then
  verdict mysql-declaration-applied PASS "taskManager.resource.memory=4096m"
else
  verdict mysql-declaration-applied FAIL "memory not applied"
fi
restored "$myname" mysql-upgrade-restored "$myname-cdc"

# Paimon 2.0.0 CDC operators have no stable ids: a new parallelism would not restore the job's
# state, so the operator keeps the deployed one and says so.
step "declare parallelism 2"
kubectl -n "$JOB_NAMESPACE" patch lakesource "$myname" --type merge -p '{"spec":{"job":{"parallelism":2}}}'
if await "$myname" mysql-parallelism-pinned 300 \
  "condition('SpecApplied') == 'False' and live_all()" \
  && [[ "$(kubectl -n "$JOB_NAMESPACE" get flinkdeployment "$myname-cdc" -o jsonpath='{.spec.job.parallelism}')" == 1 ]] \
  && warning_event "$myname" ParallelismPinned; then
  verdict mysql-parallelism-kept PASS "parallelism 1 kept, Warning ParallelismPinned"
else
  verdict mysql-parallelism-kept FAIL "parallelism changed or no warning"
fi
kubectl -n "$JOB_NAMESPACE" patch lakesource "$myname" --type merge -p '{"spec":{"job":{"parallelism":1}}}'
await "$myname" mysql-parallelism-restored 300 "condition('SpecApplied') == 'True' and live_all()" || true

step "fault: delete the TaskManager pods"
kubectl -n "$JOB_NAMESPACE" delete pod -l "app=$myname-cdc,component=taskmanager" --wait=false
sleep 30
await "$myname" mysql-taskmanager-loss 900 "live_all()" || true
step "fault: delete the JobManager pod"
kubectl -n "$JOB_NAMESPACE" delete pod -l "app=$myname-cdc,component=jobmanager" --wait=false
sleep 30
await "$myname" mysql-jobmanager-loss 900 "live_all()" || true
restored "$myname" mysql-failover-restored "$myname-cdc"

step "suspend"
kubectl -n "$JOB_NAMESPACE" patch lakesource "$myname" --type merge -p '{"spec":{"suspend":true}}'
await "$myname" mysql-suspended 600 "phase == 'Suspended' and condition('JobRunning') == 'False'" || true
sleep 60
step "resume"
kubectl -n "$JOB_NAMESPACE" patch lakesource "$myname" --type merge -p '{"spec":{"suspend":false}}'
sleep 20
await "$myname" mysql-resumed 900 "live_all() and condition('JobRunning') == 'True'" || true
restored "$myname" mysql-resume-restored "$myname-cdc"

step "writer stop: $(bash "$cdc/writer.sh" stop "w-$run")"
for spec in orders:id customers:id invoices:id typed:id; do
  converge mysql "$mydb" "$mytarget" "${spec%%:*}" "${spec##*:}"
done

# Recreating a job without its state would snapshot again into targets holding data.
step "delete the job behind the operator's back"
kubectl -n "$JOB_NAMESPACE" delete flinkdeployment "$myname-cdc" --wait=true
if await "$myname" mysql-job-lost 300 \
  "state('orders') == 'Failed' and reason('orders') == 'JobStateLost' and len(jobs) == 0" \
  && warning_event "$myname" JobLost; then
  sleep 60
  if [[ -z "$(kubectl -n "$JOB_NAMESPACE" get flinkdeployment -l "colvenna-lake.io/source=$myname" -o name)" ]]; then
    verdict mysql-job-not-recreated PASS "tables failed with JobStateLost; no fresh job"
  else
    verdict mysql-job-not-recreated FAIL "a job was recreated"
  fi
else
  verdict mysql-job-not-recreated FAIL "the tables did not fail with JobStateLost, or no JobLost warning event"
fi

step "delete $myname (Retain)"
kubectl -n "$JOB_NAMESPACE" get secret "$myname-catalog-token" -o jsonpath='{.data.token}' | base64 -d >"$work/my.token"
kubectl -n "$JOB_NAMESPACE" delete lakesource "$myname" --timeout=10m
admin_open
if [[ -z "$(kubectl -n "$JOB_NAMESPACE" get flinkdeployment -l "colvenna-lake.io/source=$myname" -o name)" ]] \
  && [[ "$(token_status "$work/my.token")" == 401 ]] \
  && admin_call GET "/v1/colvenna/databases/$mytarget/tables/orders" >/dev/null; then
  verdict mysql-deleted PASS "jobs gone, token refused with 401, target tables retained"
else
  verdict mysql-deleted FAIL "deployments, token or retained tables not as expected"
fi
admin_close

# --------------------------------------------------------------------------------- PostgreSQL
pgdb="pg_$run" pgtarget="ods_pg_$run" pgname="pg-$run" publication="colvenna_pg_$run"
step "postgres fixture $pgdb"
python3 "$cdc/pg_contract.py" reset "$pgdb" >"$evidence/sql/pg-reset.sql"
python3 "$cdc/pg_contract.py" database "$pgdb" >"$evidence/sql/pg-database.sql"
{
  python3 "$cdc/pg_contract.py" setup "$publication"
  cat <<SQL
INSERT INTO accounts SELECT i, 'owner-' || i, i * 10, 1 FROM generate_series(1, 2000) i;
INSERT INTO payments SELECT i, CASE WHEN i % 2 = 0 THEN 'east' ELSE 'west' END, i, 1 FROM generate_series(1, 1500) i;
SQL
} >"$evidence/sql/pg-setup.sql"
bash "$FIXTURES_DIR/sources/sources.sh" postgres postgres "$evidence/sql/pg-reset.sql" >/dev/null
bash "$FIXTURES_DIR/sources/sources.sh" postgres postgres "$evidence/sql/pg-database.sql" >/dev/null
bash "$FIXTURES_DIR/sources/sources.sh" postgres "$pgdb" "$evidence/sql/pg-setup.sql" >/dev/null

cat >"$evidence/postgres-source.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: $pgname, namespace: $JOB_NAMESPACE}
spec:
  type: postgres
  connection:
    host: $(stack_output "$TEST_POSTGRES_STACK" PostgresEndpoint)
    database: $pgdb
    schema: public
    publication: $publication
    credentialsSecret: {name: postgres-cdc}
    caConfigMap: rds-ca
  target:
    database: $pgtarget
  job: $small
  deletionPolicy: Delete
YAML
step "writer start"
bash "$cdc/writer.sh" start postgres "$pgdb" "pw-$run"
step "apply $pgname"
kubectl apply -f "$evidence/postgres-source.yaml"
await "$pgname" postgres-admission 900 "state('accounts') == 'Ready' and state('payments') == 'Ready' \
  and state('typed') == 'Ready' and state('ledger') == 'Ready' \
  and reason('partial_identity') == 'ReplicaIdentityNotFull' and reason('unpublished') == 'NotInPublication' \
  and condition('SourceVerified') == 'True' and live_all()" || true

step "new table refunds, written to from the start"
{
  python3 "$cdc/pg_contract.py" table "$publication" refunds
  echo "INSERT INTO refunds SELECT i, 'refund-' || i, i, 1 FROM generate_series(1, 1000) i;"
} >"$evidence/sql/pg-new-table.sql"
bash "$FIXTURES_DIR/sources/sources.sh" postgres "$pgdb" "$evidence/sql/pg-new-table.sql" >/dev/null
await "$pgname" postgres-new-table-enrolled 900 "state('refunds') == 'Ready' and live_all()" || true

step "supported schema change: add a column"
cat >"$evidence/sql/pg-schema.sql" <<SQL
ALTER TABLE accounts ADD COLUMN note varchar(32);
UPDATE accounts SET note = 'noted', version_no = version_no + 1 WHERE id <= 20;
SQL
bash "$FIXTURES_DIR/sources/sources.sh" postgres "$pgdb" "$evidence/sql/pg-schema.sql" >/dev/null

step "unsupported schema change on ledger"
# The source's replication slots are colvenna_<the first 8 of its uid>_<table> (SourceJobs.slotName).
pgslots="colvenna_$(kubectl -n "$JOB_NAMESPACE" get lakesource "$pgname" -o jsonpath='{.metadata.uid}' \
  | tr -d - | cut -c1-8)_"
cat >"$evidence/sql/pg-schema-unsupported.sql" <<SQL
ALTER TABLE ledger ALTER COLUMN amount TYPE text;
UPDATE ledger SET amount = 'n/a', version_no = version_no + 1 WHERE id = 1;
SQL
bash "$FIXTURES_DIR/sources/sources.sh" postgres "$pgdb" "$evidence/sql/pg-schema-unsupported.sql" >/dev/null
if await "$pgname" postgres-unsupported-change-isolated 900 \
  "state('ledger') == 'Failed' and state('accounts') == 'Ready' and live_all() \
  and '$pgname-ledger' not in jobs and len(pending_slots) == 0" \
  && warning_event "$pgname" TableFailed; then
  echo "SELECT slot_name FROM pg_replication_slots WHERE slot_name = '${pgslots}ledger';" >"$evidence/sql/pg-ledger-slot.sql"
  if [[ -z "$(bash "$FIXTURES_DIR/sources/sources.sh" postgres "$pgdb" "$evidence/sql/pg-ledger-slot.sql")" ]]; then
    verdict postgres-failed-table-slot-dropped PASS "ledger left the job; its slot is gone"
  else
    verdict postgres-failed-table-slot-dropped FAIL "ledger's slot is still there"
  fi
else
  verdict postgres-failed-table-slot-dropped FAIL "ledger did not fail alone, or no TableFailed warning event"
fi

step "change the declaration: TaskManager memory"
kubectl -n "$JOB_NAMESPACE" patch lakesource "$pgname" --type merge \
  -p '{"spec":{"job":{"taskManager":{"memory":"4096m"}}}}'
sleep 20
await "$pgname" postgres-declaration-change 900 "live_all() and condition('SpecApplied') == 'True'" || true
restored "$pgname" postgres-upgrade-restored "$pgname-accounts"
step "fault: delete the accounts JobManager"
kubectl -n "$JOB_NAMESPACE" delete pod -l "app=$pgname-accounts,component=jobmanager" --wait=false
sleep 30
await "$pgname" postgres-jobmanager-loss 900 "live_all()" || true
restored "$pgname" postgres-failover-restored "$pgname-accounts"
step "suspend"
kubectl -n "$JOB_NAMESPACE" patch lakesource "$pgname" --type merge -p '{"spec":{"suspend":true}}'
await "$pgname" postgres-suspended 600 "phase == 'Suspended'" || true
sleep 60
step "resume"
kubectl -n "$JOB_NAMESPACE" patch lakesource "$pgname" --type merge -p '{"spec":{"suspend":false}}'
sleep 20
await "$pgname" postgres-resumed 900 "live_all() and condition('JobRunning') == 'True'" || true
step "writer stop: $(bash "$cdc/writer.sh" stop "pw-$run")"
for spec in accounts:id payments:id,region refunds:id typed:id; do
  converge postgres "$pgdb" "$pgtarget" "${spec%%:*}" "${spec##*:}"
done

step "delete $pgname (Delete)"
kubectl -n "$JOB_NAMESPACE" delete lakesource "$pgname" --timeout=10m
echo "SELECT slot_name FROM pg_replication_slots WHERE slot_name LIKE '${pgslots}%';" \
  >"$evidence/sql/pg-slots.sql"
slots="$(bash "$FIXTURES_DIR/sources/sources.sh" postgres "$pgdb" "$evidence/sql/pg-slots.sql" | tee "$evidence/pg-slots-after-delete.txt")"
admin_open
table_status="$(curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/curlrc" \
  "https://localhost:$ADMIN_PORT/v1/colvenna/databases/$pgtarget/tables/accounts")"
admin_close
if [[ -z "$slots" && "$table_status" == 404 ]] \
  && [[ -z "$(kubectl -n "$JOB_NAMESPACE" get flinkdeployment -l "colvenna-lake.io/source=$pgname" -o name)" ]]; then
  verdict postgres-deleted PASS "jobs and replication slots gone, target table dropped (404)"
else
  verdict postgres-deleted FAIL "slots=[$slots] table=$table_status"
fi

kubectl -n "$JOB_NAMESPACE" get events -o json >"$evidence/events.json"
echo "verdicts:"
cat "$evidence/verdicts.tsv"
exit "$failed"
