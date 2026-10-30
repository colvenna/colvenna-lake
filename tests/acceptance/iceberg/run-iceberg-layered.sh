#!/usr/bin/env bash
# Acceptance on EKS: a published layered table kept fresh by its writer's own full compactions
# (LLD "开放层": a writer that compacts gets compaction.optimization-interval, 1 h, on a table
# writing Iceberg metadata), with no compaction task of anyone's. Writes PASS/FAIL lines to
# <evidence>/verdicts.tsv and fails if any check fails.
#
# - A MySQL source writes ODS orders; a LakeTable makes DWD orders from them (a primary-key table
#   whose writer compacts: a lookup changelog), with Iceberg metadata; a LakeIcebergPublication
#   publishes it. A trickle of updates keeps the writer writing, as a live source does.
# - The table gets the interval; it is published once the writer's first full compaction ran, and
#   Spark with Iceberg reads Paimon's fully compacted rows ($ro) there.
# - Ten orders paid in MySQL reach the Iceberg readers with a later compaction of the writer's
#   (its ordinary compactions, or at the latest the interval's full one); no LakeTask compacted the
#   table.
#
# usage: run-iceberg-layered.sh <run-id> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/polaris-admin.sh
source "$REPO_DIR/deploy/scripts/lib/polaris-admin.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-iceberg-layered.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-iceberg-layered.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
here="$REPO_DIR/tests/acceptance/iceberg"
spark="$REPO_DIR/tests/acceptance/spark-sql.sh"
cdc="$REPO_DIR/tests/acceptance/cdc"
mkdir -p "$evidence"/{status,spark,sql}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
for image in operator mysql sql; do
  echo "$image $(image_ref "$image")" | tee -a "$evidence/images.txt"
done
failed=0
step() { echo "$(date -u +%FT%TZ)	$*" | tee -a "$evidence/timeline.tsv"; }
verdict() {
  local why="${3:-}"
  why="${why//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${why//$'\t'/ }" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS ]] || failed=1
}
readers=colvenna-readers
db="icel_$run" ods="ods_icel_$run" dwd="dwd_icel_$run" source_name="il-$run" table_name="ild-$run"
pub="ild-$run-orders" orders="$dwd.orders" read_role="icel_read_$run"
reader="icel-reader-$run" native="icel-paimon-$run"
small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
work="$(mktemp -d)"
chmod 700 "$work"
trickle=""
# stop_trickle: the trickle's loop and whatever it is running.
stop_trickle() {
  if [[ -n "$trickle" ]]; then
    pkill -P "$trickle" 2>/dev/null || true
    kill "$trickle" 2>/dev/null || true
    trickle=""
  fi
}
trap 'stop_trickle; polaris_close; admin_close; rm -rf "$work"' EXIT

get() { kubectl -n "$JOB_NAMESPACE" get "$1" "$2" -o json >"$3"; }

# await <label> <timeout-seconds> <kind> <name> <python expression over st, published, table()>
await() {
  local label="$1" end=$((SECONDS + $2)) started=$SECONDS file="$evidence/status/$1.json"
  while ((SECONDS < end)); do
    if get "$3" "$4" "$file" 2>/dev/null && python3 - "$file" "$5" <<'PY'; then
import json, sys
st = json.load(open(sys.argv[1])).get("status") or {}
published = st.get("published") or {}
def table(name):
    return next((t for t in st.get("tables", []) if t.get("name") == name), {})
sys.exit(0 if eval(sys.argv[2]) else 1)
PY
      verdict "$label" PASS "$((SECONDS - started))s"
      return 0
    fi
    sleep 20
  done
  verdict "$label" FAIL "not within $2s: $5"
  return 1
}

# spark_run <label> <sql-file>: the statements' rows, as the reader, with Paimon native beside.
spark_run() {
  READER_NAMESPACE="$readers" OPEN_SECRET="$reader" PAIMON_SECRET="$native-token" \
    bash "$spark" "$evidence/spark/$1.out" "$2" >/dev/null || true
}

# judge <label> <iceberg.py args>...: one verdict from a spark-sql output.
judge() {
  local label="$1"
  shift
  if python3 "$here/iceberg.py" "$@" >"$evidence/spark/$label.verdict.json"; then
    verdict "$label" PASS "$(head -c 300 "$evidence/spark/$label.verdict.json")"
  else
    verdict "$label" FAIL "$(head -c 600 "$evidence/spark/$label.verdict.json")"
  fi
}

# ------------------------------------------------------------------------- source and table
step "mysql fixture $db; source $source_name; layered table $table_name, published"
{
  python3 "$cdc/mysql_contract.py" reset "$db"
  python3 "$cdc/mysql_contract.py" setup "$db"
  cat <<SQL
USE $db;
INSERT INTO orders (id, customer_id, amount, state, note, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 60)
  SELECT i, 1 + i % 20, ROUND(i * 1.25, 2), 'created', NULL, 1 FROM n;
SQL
} >"$evidence/sql/setup.sql"
bash "$REPO_DIR/tests/fixtures/sources/sources.sh" users >/dev/null
bash "$REPO_DIR/tests/fixtures/sources/sources.sh" mysql "$evidence/sql/setup.sql" >/dev/null
ensure_rds_ca "$JOB_NAMESPACE"
cat >"$evidence/declarations.yaml" <<YAML
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
  tables: {include: [orders]}
  target: {database: $ods}
  governance: {owner: shop-team, level: L2, retention: 7d}
  job: $small
  deletionPolicy: Delete
---
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTable
metadata: {name: $table_name, namespace: $JOB_NAMESPACE}
spec:
  target: {database: $dwd, table: orders}
  primaryKey: [id]
  upstream: [$ods.orders]
  query: |
    SELECT id, customer_id, amount, state, version_no FROM $ods.orders
  tableOptions: {metadata.iceberg.storage: table-location}
  governance: {owner: analytics, level: L2, retention: 7d}
  deletionPolicy: Delete
  job: $small
---
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeIcebergPublication
metadata: {name: $pub, namespace: $JOB_NAMESPACE}
spec:
  table: {database: $dwd, table: orders}
  columns: [id, customer_id, amount, state, version_no]
  readers: [$read_role]
YAML
kubectl apply -f "$evidence/declarations.yaml"
await source-ready 900 lakesource "$source_name" "table('orders').get('state') == 'Ready'" || true
await table-running 900 laketable "$table_name" "st.get('phase') == 'Running'" || true
running_at="$(date -u +%FT%TZ)"

# A row changed every 4 minutes, as a live source changes: the writer writes, and so compacts.
(
  i=0
  while sleep 240; do
    i=$((i + 1))
    printf 'USE %s;\nUPDATE orders SET amount = amount + 0.01, version_no = version_no + 1 WHERE id = %d;\n' \
      "$db" $((60 - i % 20)) >"$work/trickle.sql"
    bash "$REPO_DIR/tests/fixtures/sources/sources.sh" mysql "$work/trickle.sql" >/dev/null 2>&1 || true
  done
) &
trickle=$!

step "the interval on the table; the readers' identities"
admin_open
admin_call GET "/v1/colvenna/databases/$dwd/tables/orders" >"$evidence/status/dwd-table.json"
interval="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["schema"]["options"].get("compaction.optimization-interval", ""))' "$evidence/status/dwd-table.json")"
if [[ "$interval" == "1 h" ]]; then
  verdict writer-gets-the-interval PASS "compaction.optimization-interval = $interval"
else
  verdict writer-gets-the-interval FAIL "compaction.optimization-interval = '$interval'"
fi
kubectl create namespace "$readers" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
ensure_principal_token "$native" "$readers" "$native-token" P1D
GRANT_PURPOSE="native comparison reads" GRANT_APPROVER=requester GRANT_APPROVAL=issue-34 \
  GRANT_DAYS=2 ensure_grant "$native" TABLE "$dwd" orders READ
admin_close
polaris_open polaris-root
polaris_ensure "/api/management/v1/catalogs/$POLARIS_CATALOG/catalog-roles" \
  "{\"catalogRole\": {\"name\": \"$read_role\"}}"
polaris_ensure /api/management/v1/principal-roles "{\"principalRole\": {\"name\": \"icel_readers_$run\"}}"
polaris_call PUT "/api/management/v1/principal-roles/icel_readers_$run/catalog-roles/$POLARIS_CATALOG" \
  "{\"catalogRole\": {\"name\": \"$read_role\"}}" >/dev/null
if ! kubectl -n "$readers" get secret "$reader" >/dev/null 2>&1; then
  polaris_call POST /api/management/v1/principals "{\"principal\": {\"name\": \"$reader\"}}" \
    | python3 -c '
import json, os, sys
c = json.load(sys.stdin)["credentials"]
open(os.path.join(sys.argv[1], "client-id"), "w").write(c["clientId"])
open(os.path.join(sys.argv[1], "client-secret"), "w").write(c["clientSecret"])' "$work"
  kubectl create namespace "$readers" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl -n "$readers" create secret generic "$reader" --from-file=client-id="$work/client-id" \
    --from-file=client-secret="$work/client-secret" >/dev/null
  rm -f "$work/client-secret"
fi
polaris_call PUT "/api/management/v1/principals/$reader/principal-roles" \
  "{\"principalRole\": {\"name\": \"icel_readers_$run\"}}" >/dev/null
polaris_close

# ---------------------------------------------------------------------- the writer's compactions
step "published once the writer fully compacts: at most the interval after it started"
# And its reader granted: Published says the catalog points at the snapshot; the readers' grant is
# ReadersReady, applied in the same round only when their catalog role already exists, which this
# test creates after the declaration (the operator's next round grants it, within 30 s).
await published-by-the-writer 5400 lakeicebergpublication "$pub" \
  "st.get('phase') == 'Published' and published.get('snapshot') and st.get('readers') == ['$read_role']" || true
get lakeicebergpublication "$pub" "$evidence/status/first.json"
first="$(python3 -c 'import json,sys; print((json.load(open(sys.argv[1]))["status"].get("published") or {}).get("snapshot", ""))' "$evidence/status/first.json")"
cat >"$evidence/sql/first.sql" <<SQL
SELECT id, amount, state, version_no FROM open.$orders;
SELECT id, amount, state, version_no FROM paimon.$dwd.\`orders\$ro\`;
SQL
spark_run first "$evidence/sql/first.sql"
judge iceberg-is-the-writers-compacted-rows same "$evidence/spark/first.out" 1 2

step "ten orders paid; the readers see them after a later full compaction of the writer's"
cat >"$evidence/sql/changes.sql" <<SQL
USE $db;
UPDATE orders SET state = 'paid', version_no = version_no + 1 WHERE id <= 10;
SQL
bash "$REPO_DIR/tests/fixtures/sources/sources.sh" mysql "$evidence/sql/changes.sql" >/dev/null
changed_at=$SECONDS
cat >"$evidence/sql/paid.sql" <<SQL
SELECT COUNT(*) FROM open.$orders WHERE state = 'paid';
SQL
end=$((SECONDS + 5400)) seen=""
while ((SECONDS < end)); do
  sleep 300
  spark_run paid "$evidence/sql/paid.sql"
  if python3 "$here/iceberg.py" has "$evidence/spark/paid.out" 1 10 >/dev/null; then
    seen=$((SECONDS - changed_at))
    break
  fi
done
# At the latest the interval's full compaction, with a margin for the polling and the next write.
if [[ -n "$seen" ]] && ((seen <= 4500)); then
  verdict changes-visible-by-the-writers-compaction PASS "the ten paid orders reached the readers ${seen}s after the change (polled every 300 s)"
else
  verdict changes-visible-by-the-writers-compaction FAIL "the ten paid orders: ${seen:+${seen}s after the change, past 4500 s}${seen:-not within 5400 s}"
fi
cat >"$evidence/sql/after.sql" <<SQL
SELECT id, amount, state, version_no FROM open.$orders;
SELECT id, amount, state, version_no FROM paimon.$dwd.\`orders\$ro\`;
SELECT COUNT(*) FROM open.$orders WHERE state = 'paid';
SELECT COUNT(*) FROM paimon.$dwd.\`orders\$ro\` WHERE state = 'paid';
SQL
spark_run after "$evidence/sql/after.sql"
judge iceberg-is-the-writers-compacted-rows-after-changes same "$evidence/spark/after.out" 1 2
judge iceberg-has-the-paid-orders has "$evidence/spark/after.out" 3 10
judge ro-has-the-paid-orders has "$evidence/spark/after.out" 4 10
get lakeicebergpublication "$pub" "$evidence/status/after.json"
last="$(python3 -c 'import json,sys; print((json.load(open(sys.argv[1]))["status"].get("published") or {}).get("snapshot", ""))' "$evidence/status/after.json")"
kubectl -n "$JOB_NAMESPACE" get laketask -o json >"$evidence/status/tasks.json"
compactions="$(python3 -c '
import json, sys
print(sum(1 for t in json.load(open(sys.argv[1]))["items"]
          if t["spec"]["type"] == "compact" and t["spec"]["table"]["database"] == sys.argv[2]))' "$evidence/status/tasks.json" "$dwd")"
if [[ "$compactions" == 0 && -n "$first" && -n "$last" && "$last" -gt "$first" ]]; then
  verdict no-compaction-task-needed PASS "published snapshots $first, then $last, since $running_at; no LakeTask compacted $orders"
else
  verdict no-compaction-task-needed FAIL "compaction tasks: $compactions; published $first then $last"
fi

# --------------------------------------------------------------------------------- clean up
step "clean up"
stop_trickle
kubectl -n "$JOB_NAMESPACE" delete lakeicebergpublication "$pub" --wait=true --timeout=5m >/dev/null || true
kubectl -n "$JOB_NAMESPACE" delete laketable "$table_name" --wait=true --timeout=10m >/dev/null || true
kubectl -n "$JOB_NAMESPACE" delete lakesource "$source_name" --wait=true --timeout=10m >/dev/null || true
python3 "$cdc/mysql_contract.py" reset "$db" >"$evidence/sql/reset.sql"
bash "$REPO_DIR/tests/fixtures/sources/sources.sh" mysql "$evidence/sql/reset.sql" >/dev/null || true
kubectl -n "$readers" delete secret "$reader" "$native-token" --ignore-not-found >/dev/null
polaris_open polaris-root
polaris_code DELETE "/api/management/v1/principals/$reader" >/dev/null || true
polaris_code DELETE "/api/management/v1/principal-roles/icel_readers_$run" >/dev/null || true
polaris_code DELETE "/api/management/v1/catalogs/$POLARIS_CATALOG/catalog-roles/$read_role" >/dev/null || true
polaris_close
admin_open
revoke_principal "$native" >/dev/null 2>&1 || true
admin_close

echo
cut -f3 "$evidence/verdicts.tsv" | sort | uniq -c
exit "$failed"
