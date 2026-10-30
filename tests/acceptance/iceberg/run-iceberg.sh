#!/usr/bin/env bash
# Acceptance on EKS: lake tables published, read-only, to Polaris's Iceberg catalog, and read there
# by Spark with Iceberg, from declaration to withdrawal. Writes PASS/FAIL lines to
# <evidence>/verdicts.tsv and fails if any check fails.
#
# - A MySQL source (primary-key orders) and a Kafka source (append clicks, by day) declare their
#   tables with Iceberg metadata; a LakeIcebergPublication publishes each to a reader catalog role.
# - Primary key: the Iceberg view is the fully compacted data (Paimon's $ro): not in the catalog
#   before the first full compaction, then equal to Paimon's rows; updates and deletes reach it
#   with the next one. A full compaction leaves the manifests, so every snapshot keeps its
#   Iceberg metadata.
# - Append: each commit reaches the catalog within a minute; the Iceberg snapshot is the Paimon
#   snapshot, row for row. An added field takes the table out of the catalog until the
#   publication lists it; a tag reaches the readers.
# - A table classified above L2 is withdrawn and comes back once it is L2 again. With Polaris down,
#   the writers go on; the catalog catches up once it is back.
# - A role taken out of the readers reads no more; the reader cannot write, drop, create, alter or
#   rename; an outsider cannot read; nobody calls the catalog or management API anonymously, and
#   the management port is closed to readers; the vended credentials read only the table's own
#   files, write nothing, and expire within 15 minutes; a reader pod has no other way to the data.
#   Deleting a publication takes the table out of the catalog; credentials vended before still
#   read until they expire.
#
# usage: run-iceberg.sh <run-id> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/polaris-admin.sh
source "$REPO_DIR/deploy/scripts/lib/polaris-admin.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-iceberg.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-iceberg.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
here="$REPO_DIR/tests/acceptance/iceberg"
spark="$REPO_DIR/tests/acceptance/spark-sql.sh"
lake="$REPO_DIR/tests/acceptance/lake.sh"
cdc="$REPO_DIR/tests/acceptance/cdc"
mkdir -p "$evidence"/{status,spark,sql}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
for image in operator kafka mysql sql; do
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
readers=colvenna-readers
mysql_db="ice_$run" ods="ods_ice_$run" ods_k="ods_icek_$run" topic="ice-$run"
cdc_name="ib-$run" kafka_name="ie-$run"
orders="$ods.orders" clicks="$ods_k.clicks"
pub_orders="ib-$run-orders" pub_clicks="ie-$run-clicks"
read_role="ice_read_$run" none_role="ice_none_$run"
reader="ice-reader-$run" outsider="ice-outsider-$run" native="ice-paimon-$run"
bootstrap="$DEV_KAFKA_RELEASE-kafka-bootstrap.$DEV_KAFKA_NAMESPACE.svc:9093"
small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
work="$(mktemp -d)"
chmod 700 "$work"
# Polaris is scaled to 0 for one check: whatever happens, it is back at 1 when the run ends.
trap 'kubectl -n "$POLARIS_NAMESPACE" scale "deploy/$POLARIS_RELEASE" --replicas=1 >/dev/null 2>&1 || true
  polaris_close; admin_close; rm -rf "$work"' EXIT

get() { kubectl -n "$JOB_NAMESPACE" get "$1" "$2" -o json >"$3"; }

# await <label> <timeout-seconds> <kind> <name> <python expression over s, st, cond(), reason()>
await() {
  local label="$1" end=$((SECONDS + $2)) started=$SECONDS file="$evidence/status/$1.json"
  while ((SECONDS < end)); do
    if get "$3" "$4" "$file" 2>/dev/null && python3 - "$file" "$5" <<'PY'; then
import json, sys
s = json.load(open(sys.argv[1]))
st = s.get("status") or {}
def cond(t):
    return next((c.get("status") for c in st.get("conditions", []) if c.get("type") == t), None)
def reason(t):
    return next((c.get("reason") for c in st.get("conditions", []) if c.get("type") == t), None)
published = st.get("published") or {}
def table(name):
    return next((t for t in st.get("tables", []) if t.get("name") == name), {})
sys.exit(0 if eval(sys.argv[2]) else 1)
PY
      verdict "$label" PASS "$((SECONDS - started))s"
      return 0
    fi
    sleep 5
  done
  verdict "$label" FAIL "not within $2s: $5"
  python3 -c 'import json,sys; s=json.load(open(sys.argv[1])).get("status",{}); print(s.get("phase"), s.get("message"))' "$file" || true
  return 1
}

# field <json-file> <a.b.c>: one value of a JSON object, empty when absent.
field() {
  python3 - "$1" "$2" <<'PY'
import json, sys
v = json.load(open(sys.argv[1]))
for k in sys.argv[2].split("."):
    v = v.get(k) if isinstance(v, dict) else None
print("" if v is None else v)
PY
}

# spark_run <label> <sql-file> <open-secret> [<paimon-secret>]: the statements' rows.
spark_run() {
  READER_NAMESPACE="$readers" OPEN_SECRET="$3" PAIMON_SECRET="${4:-}" \
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

# produce <batch-file>: key|value lines to the topic as the producer.
produce() {
  local pod="ice-produce-$RANDOM"
  kubectl -n "$DEV_KAFKA_NAMESPACE" get secret ice-producer -o jsonpath='{.data.sasl\.jaas\.config}' \
    | base64 -d >"$work/jaas"
  kubectl -n "$JOB_NAMESPACE" create secret generic "$pod" --from-file=sasl.jaas.config="$work/jaas" \
    --from-file=ca.crt="$work/kafka-ca.pem" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  rm -f "$work/jaas"
  kubectl -n "$JOB_NAMESPACE" create configmap "$pod" --from-file=batch.txt="$1" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  cat >"$work/$pod.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $pod}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 1001, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: kafka
      image: $KAFKA_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["bash", "-ec"]
      args:
        - |
          printf 'security.protocol=SASL_SSL\nsasl.mechanism=SCRAM-SHA-512\nssl.truststore.type=PEM\nssl.truststore.location=/client/ca.crt\n' >/tmp/client.properties
          printf 'sasl.jaas.config=%s\n' "\$(cat /client/sasl.jaas.config)" >>/tmp/client.properties
          /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server $bootstrap --producer.config /tmp/client.properties --topic $topic --property parse.key=true --property key.separator='|' </data/batch.txt
      resources: {requests: {cpu: 100m, memory: 512Mi}, limits: {memory: 512Mi}}
      volumeMounts:
        - {name: client, mountPath: /client, readOnly: true}
        - {name: data, mountPath: /data, readOnly: true}
  volumes:
    - {name: client, secret: {secretName: $pod}}
    - {name: data, configMap: {name: $pod}}
YAML
  run_pod "$JOB_NAMESPACE" "$pod" "$work/$pod.yaml" >/dev/null || true
  kubectl -n "$JOB_NAMESPACE" delete secret,configmap "$pod" --ignore-not-found >/dev/null
}

# events <name> <count> [campaign]: a batch of clicks at now; prints the batch file.
total=0
events() {
  python3 - "$1" "$2" "${3:-}" >"$evidence/sql/$1.txt" <<'PY'
import datetime, json, sys
name, count, campaign = sys.argv[1], int(sys.argv[2]), sys.argv[3]
now = datetime.datetime.now(datetime.timezone.utc)
for i in range(count):
    e = {"event_id": f"{name}-{i}", "user_id": 1 + i % 40, "event_time": now.isoformat()}
    if campaign:
        e["campaign"] = campaign
    print(f"{e['event_id']}|{json.dumps(e)}")
PY
  produce "$evidence/sql/$1.txt"
  total=$((total + $2))
  # A miss is a FAIL verdict; the run goes on to its cleanup.
  await "events-$1" 600 lakesource "$kafka_name" \
    "int((st.get('events') or {}).get('accepted') or 0) >= $total" || true
}

# compact <task>: a full compaction of orders, waited for.
compact() {
  kubectl apply -f - >/dev/null <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: $1, namespace: $JOB_NAMESPACE}
spec:
  type: compact
  table: {database: $ods, table: orders}
  compact: {full: true}
  resources: {cpu: 0.5, memory: 2048m}
YAML
  await "compacted-$1" 900 laketask "$1" "st.get('phase') == 'Succeeded'" || true
}

# published_at_latest <label> <publication> <db.table>: the catalog points at Paimon's latest.
published_at_latest() {
  local snapshot
  bash "$lake" table "$work/head.jsonl" "$3" >/dev/null
  snapshot="$(python3 -c 'import json,sys; print([json.loads(l) for l in open(sys.argv[1]) if l.startswith("{")][0]["snapshot"])' "$work/head.jsonl")"
  await "$1" 300 lakeicebergpublication "$2" \
    "st.get('phase') == 'Published' and int(published.get('snapshot') or 0) >= $snapshot"
}

# ------------------------------------------------------------------ sources and identities
step "Kafka topic $topic, MySQL fixture $mysql_db"
cat >"$evidence/kafka-values.yaml" <<YAML
topics:
  - {name: dev-events, partitions: 3, retentionMs: 86400000}
  - {name: $topic, partitions: 1, retentionMs: 86400000}
users:
  - {name: dev-producer, write: [dev-events]}
  - {name: dev-consumer, read: [dev-events], groups: [dev-]}
  - {name: ice-producer, write: [$topic]}
  - {name: ice-ingest, read: [$topic], groups: [colvenna.]}
YAML
bash "$REPO_DIR/tests/fixtures/kafka/dev-kafka.sh" install "$evidence/kafka" -f "$evidence/kafka-values.yaml"
for r in "kafkatopic/$topic" kafkauser/ice-producer kafkauser/ice-ingest; do
  kubectl -n "$DEV_KAFKA_NAMESPACE" wait "$r" --for=condition=Ready --timeout=5m >/dev/null
done
for ns in "$JOB_NAMESPACE" "$OPERATOR_NAMESPACE"; do
  kubectl label namespace "$ns" --overwrite colvenna-lake.io/dev-kafka-client=true >/dev/null
done
kubectl -n "$DEV_KAFKA_NAMESPACE" get secret "$DEV_KAFKA_RELEASE-cluster-ca-cert" \
  -o jsonpath='{.data.ca\.crt}' | base64 -d >"$work/kafka-ca.pem"
kubectl -n "$JOB_NAMESPACE" create configmap "$kafka_name-kafka-ca" --from-file=ca.pem="$work/kafka-ca.pem" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$DEV_KAFKA_NAMESPACE" get secret ice-ingest -o jsonpath='{.data.password}' | base64 -d \
  >"$work/ingest"
kubectl -n "$JOB_NAMESPACE" create secret generic "$kafka_name-kafka" --from-literal=username=ice-ingest \
  --from-file=password="$work/ingest" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
rm -f "$work/ingest"
ensure_rds_ca "$JOB_NAMESPACE"
bash "$REPO_DIR/tests/fixtures/sources/sources.sh" users >/dev/null
{
  python3 "$cdc/mysql_contract.py" reset "$mysql_db"
  python3 "$cdc/mysql_contract.py" setup "$mysql_db"
  cat <<SQL
USE $mysql_db;
INSERT INTO orders (id, customer_id, amount, state, note, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 100)
  SELECT i, 1 + i % 20, ROUND(i * 1.25, 2), 'created', NULL, 1 FROM n;
SQL
} >"$evidence/sql/setup.sql"
bash "$REPO_DIR/tests/fixtures/sources/sources.sh" mysql "$evidence/sql/setup.sql" >/dev/null

cat >"$evidence/sources.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: $cdc_name, namespace: $JOB_NAMESPACE}
spec:
  type: mysql
  connection:
    host: $(stack_output "$TEST_MYSQL_STACK" MySqlEndpoint)
    database: $mysql_db
    credentialsSecret: {name: mysql-cdc}
    inspectorSecret: {name: mysql-inspect}
    caConfigMap: rds-ca
    serverTimeZone: UTC
  tables: {include: [orders]}
  target:
    database: $ods
    bucket: 2
    tableOptions: {metadata.iceberg.storage: table-location, snapshot.time-retained: 1 d}
  governance: {owner: shop-team, level: L2, retention: 7d}
  job: $small
  deletionPolicy: Delete
---
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: $kafka_name, namespace: $JOB_NAMESPACE}
spec:
  type: kafka
  kafka:
    bootstrapServers: $bootstrap
    topics: [$topic]
    credentialsSecret: {name: $kafka_name-kafka}
    caConfigMap: $kafka_name-kafka-ca
  events:
    fields:
      - {name: event_id, type: STRING, required: true, maxLength: 64}
      - {name: user_id, type: BIGINT}
    eventTime: {field: event_time}
    maxOutOfOrderness: 1m
    idleness: 30s
  target:
    database: $ods_k
    table: clicks
    tableOptions: {metadata.iceberg.storage: table-location, snapshot.time-retained: 1 d}
  governance: {owner: web-team, level: L2, retention: 7d}
  job: $small
  deletionPolicy: Delete
YAML
step "declare $cdc_name and $kafka_name"
kubectl apply -f "$evidence/sources.yaml"
await cdc-source-ready 900 lakesource "$cdc_name" "table('orders').get('state') == 'Ready'" || true
await kafka-source-running 900 lakesource "$kafka_name" \
  "st.get('phase') == 'Running' and cond('TargetReady') == 'True'" || true

step "Polaris principals and roles; a native Paimon reader for comparison"
polaris_open polaris-root
for role in "$read_role" "$none_role"; do
  polaris_ensure "/api/management/v1/catalogs/$POLARIS_CATALOG/catalog-roles" \
    "{\"catalogRole\": {\"name\": \"$role\"}}"
done
# principal <name> <principal-role> <catalog-role>: its credentials in a Secret of the readers'.
principal() {
  polaris_ensure /api/management/v1/principal-roles "{\"principalRole\": {\"name\": \"$2\"}}"
  polaris_call PUT "/api/management/v1/principal-roles/$2/catalog-roles/$POLARIS_CATALOG" \
    "{\"catalogRole\": {\"name\": \"$3\"}}" >/dev/null
  if ! kubectl -n "$readers" get secret "$1" >/dev/null 2>&1; then
    polaris_call POST /api/management/v1/principals "{\"principal\": {\"name\": \"$1\"}}" \
      | python3 -c '
import json, os, sys
c = json.load(sys.stdin)["credentials"]
open(os.path.join(sys.argv[1], "client-id"), "w").write(c["clientId"])
open(os.path.join(sys.argv[1], "client-secret"), "w").write(c["clientSecret"])' "$work"
    kubectl create namespace "$readers" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    kubectl -n "$readers" create secret generic "$1" --from-file=client-id="$work/client-id" \
      --from-file=client-secret="$work/client-secret" >/dev/null
    rm -f "$work/client-secret"
  fi
  polaris_call PUT "/api/management/v1/principals/$1/principal-roles" \
    "{\"principalRole\": {\"name\": \"$2\"}}" >/dev/null
}
principal "$reader" "ice_readers_$run" "$read_role"
principal "$outsider" "ice_outsiders_$run" "$none_role"
polaris_close
admin_open
ensure_principal_token "$native" "$readers" "$native-token" P1D
for t in "$orders" "$clicks"; do
  GRANT_PURPOSE="native comparison reads" GRANT_APPROVER=requester GRANT_APPROVAL=issue-34 \
    GRANT_DAYS=2 ensure_grant "$native" TABLE "${t%%.*}" "${t#*.}" READ
done
admin_close

# publications <clicks-columns> <clicks-readers>: both publications, the columns reviewed listed.
publications() {
  cat <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeIcebergPublication
metadata: {name: $pub_orders, namespace: $JOB_NAMESPACE}
spec:
  table: {database: $ods, table: orders}
  columns: [id, customer_id, amount, state, note, version_no]
  readers: [$read_role]
---
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeIcebergPublication
metadata: {name: $pub_clicks, namespace: $JOB_NAMESPACE}
spec:
  table: {database: $ods_k, table: clicks}
  columns: [$1]
  readers: [$2]
YAML
}
clicks_columns="event_id, user_id, event_time_ms, dt, extra_json, _topic, _partition, _offset"
publications "$clicks_columns" "$read_role" >"$evidence/publications.yaml"
step "publish $orders and $clicks"
kubectl apply -f "$evidence/publications.yaml"
# A primary-key table's view is its fully compacted files: none before the first full compaction,
# so nothing in the catalog and nobody granted.
await orders-withheld-until-compacted 600 lakeicebergpublication "$pub_orders" \
  "st.get('phase') == 'Pending' and reason('Published') == 'MetadataIncomplete' and not st.get('readers')" || true

# ---------------------------------------------------------------- primary key: orders
step "orders: not in the catalog before a full compaction; then the compacted data"
cat >"$evidence/sql/pk-before.sql" <<SQL
SELECT COUNT(*) FROM open.$orders;
SQL
spark_run pk-before "$evidence/sql/pk-before.sql" "$reader"
judge orders-not-served-before-compaction failed "$evidence/spark/pk-before.out" 1 NOT_FOUND
compact "c1-$run"
get laketask "c1-$run" "$evidence/status/c1-task.json"
if [[ "$(field "$evidence/status/c1-task.json" status.result.detail)" == *"manifests not compacted: the table keeps Iceberg metadata"* ]]; then
  verdict compaction-keeps-the-metadata-chain PASS "$(field "$evidence/status/c1-task.json" status.result.detail)"
else
  verdict compaction-keeps-the-metadata-chain FAIL "$(field "$evidence/status/c1-task.json" status.result.detail)"
fi
published_at_latest orders-published-after-compaction "$pub_orders" "$orders" || true
cat >"$evidence/sql/pk-after.sql" <<SQL
SELECT id, amount, state FROM open.$orders;
SELECT id, amount, state FROM paimon.$orders;
SELECT COUNT(*) FROM open.$orders;
SQL
spark_run pk-after "$evidence/sql/pk-after.sql" "$reader" "$native-token"
judge iceberg-equals-paimon-after-compaction same "$evidence/spark/pk-after.out" 1 2
judge iceberg-has-all-orders has "$evidence/spark/pk-after.out" 3 100

step "orders: 10 updated, 5 deleted in MySQL; the view is Paimon's \$ro until the next full compaction"
cat >"$evidence/sql/changes.sql" <<SQL
USE $mysql_db;
UPDATE orders SET amount = amount + 100, state = 'paid', version_no = version_no + 1 WHERE id <= 10;
DELETE FROM orders WHERE id > 95;
SQL
bash "$REPO_DIR/tests/fixtures/sources/sources.sh" mysql "$evidence/sql/changes.sql" >/dev/null
sleep 90
published_at_latest orders-published-after-changes "$pub_orders" "$orders" || true
cat >"$evidence/sql/pk-stale.sql" <<SQL
SELECT id, amount, state FROM open.$orders;
SELECT id, amount, state FROM paimon.$ods.\`orders\$ro\`;
SQL
spark_run pk-stale "$evidence/sql/pk-stale.sql" "$reader" "$native-token"
judge iceberg-is-ro-after-changes same "$evidence/spark/pk-stale.out" 1 2
compact "c2-$run"
published_at_latest orders-published-after-c2 "$pub_orders" "$orders" || true
cat >"$evidence/sql/pk-changed.sql" <<SQL
SELECT id, amount, state FROM open.$orders;
SELECT id, amount, state FROM paimon.$orders;
SELECT COUNT(*) FROM open.$orders WHERE id > 95;
SELECT COUNT(*) FROM open.$orders WHERE state = 'paid';
SQL
spark_run pk-changed "$evidence/sql/pk-changed.sql" "$reader" "$native-token"
judge iceberg-equals-paimon-after-changes same "$evidence/spark/pk-changed.out" 1 2
judge deleted-orders-gone has "$evidence/spark/pk-changed.out" 3 0
judge updated-orders-visible has "$evidence/spark/pk-changed.out" 4 10

# --------------------------------------------------------------------- append: clicks
step "clicks: each commit reaches the catalog; the Iceberg snapshot is the Paimon snapshot"
events b1 50
published_at_latest clicks-published "$pub_clicks" "$clicks" || true
get lakeicebergpublication "$pub_clicks" "$evidence/status/clicks-b1.json"
s1="$(field "$evidence/status/clicks-b1.json" status.published.snapshot)"
python3 - "$evidence/status/clicks-b1.json" >"$evidence/status/visibility-b1.txt" <<'PY'
import datetime, json, sys
p = json.load(open(sys.argv[1]))["status"]["published"]
c = datetime.datetime.fromisoformat(p["committedAt"].replace("Z", "+00:00"))
d = datetime.datetime.fromisoformat(p["publishedAt"].replace("Z", "+00:00"))
print(int((d - c).total_seconds()))
PY
wait_s="$(cat "$evidence/status/visibility-b1.txt")"
if ((wait_s <= 90)); then
  verdict visibility-within-window PASS "snapshot $s1 committed to published in ${wait_s}s (window 90 s)"
else
  verdict visibility-within-window FAIL "snapshot $s1 took ${wait_s}s to be published"
fi
cat >"$evidence/sql/append.sql" <<SQL
SELECT event_id, user_id, dt FROM open.$clicks VERSION AS OF $s1;
SELECT event_id, user_id, dt FROM paimon.$clicks VERSION AS OF $s1;
SELECT snapshot_id FROM open.$clicks.snapshots WHERE snapshot_id = $s1;
SQL
spark_run append "$evidence/sql/append.sql" "$reader" "$native-token"
judge append-snapshot-equals-paimon same "$evidence/spark/append.out" 1 2
judge iceberg-snapshot-is-paimon-snapshot has "$evidence/spark/append.out" 3 "$s1"

step "clicks: an added field, withdrawn until reviewed; and a tag"
python3 - "$evidence/sources.yaml" "$evidence/sources-v2.yaml" <<'PY'
import sys
s = open(sys.argv[1]).read()
s = s.replace("      - {name: user_id, type: BIGINT}\n", "      - {name: user_id, type: BIGINT}\n      - {name: campaign, type: STRING}\n")
open(sys.argv[2], "w").write(s)
PY
kubectl apply -f "$evidence/sources-v2.yaml" >/dev/null
await field-added 600 lakesource "$kafka_name" \
  "st.get('phase') == 'Running' and 'campaign' in json.dumps((st.get('appliedEvents') or {}))" || true
await unreviewed-column-withdrawn 300 lakeicebergpublication "$pub_clicks" \
  "st.get('phase') == 'Withheld' and reason('Publishable') == 'ColumnsUnreviewed' and 'campaign' in (st.get('message') or '') and not st.get('published')" || true
cat >"$evidence/sql/unreviewed.sql" <<SQL
SELECT COUNT(*) FROM open.$clicks;
SQL
spark_run unreviewed "$evidence/sql/unreviewed.sql" "$reader"
judge unreviewed-column-not-served failed "$evidence/spark/unreviewed.out" 1 NOT_FOUND
publications "$clicks_columns, campaign" "$read_role" >"$evidence/publications-v2.yaml"
kubectl apply -f "$evidence/publications-v2.yaml" >/dev/null
await reviewed-column-republished 300 lakeicebergpublication "$pub_clicks" "st.get('phase') == 'Published'" || true
events b2 10 autumn
kubectl apply -f - >/dev/null <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: tag-$run, namespace: $JOB_NAMESPACE}
spec:
  type: createTag
  table: {database: $ods_k, table: clicks}
  createTag: {name: eod-$run, timeRetained: 2d}
YAML
await tagged 600 laketask "tag-$run" "st.get('phase') == 'Succeeded'" || true
events b3 5 autumn
published_at_latest clicks-published-after-field "$pub_clicks" "$clicks" || true
get lakeicebergpublication "$pub_clicks" "$evidence/status/clicks-b3.json"
s3="$(field "$evidence/status/clicks-b3.json" status.published.snapshot)"
cat >"$evidence/sql/field-tag.sql" <<SQL
DESCRIBE open.$clicks;
SELECT event_id, user_id, campaign, dt FROM open.$clicks VERSION AS OF $s3;
SELECT event_id, user_id, campaign, dt FROM paimon.$clicks VERSION AS OF $s3;
SELECT name, type FROM open.$clicks.refs;
SQL
spark_run field-tag "$evidence/sql/field-tag.sql" "$reader" "$native-token"
judge added-field-visible has "$evidence/spark/field-tag.out" 1 campaign
judge rows-with-added-field-equal same "$evidence/spark/field-tag.out" 2 3
judge tag-visible has "$evidence/spark/field-tag.out" 4 "eod-$run"

# --------------------------------------------------------------------- gates and outage
step "clicks classified L3: withdrawn; L2 again: back"
python3 - "$evidence/sources-v2.yaml" "$evidence/sources-l3.yaml" <<'PY'
import sys
s = open(sys.argv[1]).read()
s = s.replace("governance: {owner: web-team, level: L2, retention: 7d}", "governance: {owner: web-team, level: L3, retention: 7d}")
open(sys.argv[2], "w").write(s)
PY
kubectl apply -f "$evidence/sources-l3.yaml" >/dev/null
await withdrawn-when-restricted 300 lakeicebergpublication "$pub_clicks" \
  "st.get('phase') == 'Withheld' and reason('Publishable') == 'Restricted' and not st.get('published')" || true
cat >"$evidence/sql/withdrawn.sql" <<SQL
SELECT COUNT(*) FROM open.$clicks;
SQL
spark_run withdrawn "$evidence/sql/withdrawn.sql" "$reader"
judge withdrawn-table-not-found failed "$evidence/spark/withdrawn.out" 1 NOT_FOUND
kubectl apply -f "$evidence/sources-v2.yaml" >/dev/null
await republished-when-l2 300 lakeicebergpublication "$pub_clicks" "st.get('phase') == 'Published'" || true

step "Polaris down: the writers go on, the catalog catches up once it is back"
kubectl -n "$POLARIS_NAMESPACE" scale "deploy/$POLARIS_RELEASE" --replicas=0 >/dev/null
kubectl -n "$POLARIS_NAMESPACE" wait --for=delete pod -l app.kubernetes.io/name=polaris --timeout=5m >/dev/null || true
before="$(field "$evidence/status/clicks-b3.json" status.published.snapshot)"
events b4 20 autumn
await publication-unavailable 300 lakeicebergpublication "$pub_clicks" \
  "cond('Published') == 'False' and reason('Published') == 'Unavailable'" || true
await writers-unaffected 120 lakesource "$kafka_name" "st.get('phase') == 'Running'" || true
kubectl -n "$POLARIS_NAMESPACE" scale "deploy/$POLARIS_RELEASE" --replicas=1 >/dev/null
kubectl -n "$POLARIS_NAMESPACE" rollout status "deploy/$POLARIS_RELEASE" --timeout=5m >/dev/null
published_at_latest caught-up-after-outage "$pub_clicks" "$clicks" || true
get lakeicebergpublication "$pub_clicks" "$evidence/status/clicks-b4.json"
after="$(field "$evidence/status/clicks-b4.json" status.published.snapshot)"
if ((after > before)); then
  verdict catalog-caught-up PASS "published snapshot $before before the outage, $after after"
else
  verdict catalog-caught-up FAIL "still $after"
fi

# ---------------------------------------------------------------------------- security
step "boundaries: a revoked reader, the reader writes nothing, an outsider reads nothing, nobody anonymous"
publications "$clicks_columns, campaign" "" >"$evidence/publications-revoked.yaml"
kubectl apply -f "$evidence/publications-revoked.yaml" >/dev/null
await reader-revoked 300 lakeicebergpublication "$pub_clicks" \
  "st.get('phase') == 'Published' and not st.get('readers')" || true
cat >"$evidence/sql/revoked.sql" <<SQL
SELECT COUNT(*) FROM open.$clicks;
SQL
spark_run revoked "$evidence/sql/revoked.sql" "$reader"
judge revoked-reader-refused failed "$evidence/spark/revoked.out" 1 Forbidden
kubectl apply -f "$evidence/publications-v2.yaml" >/dev/null
await reader-granted-again 300 lakeicebergpublication "$pub_clicks" \
  "st.get('readers') == ['$read_role']" || true
cat >"$evidence/sql/reader-writes.sql" <<SQL
INSERT INTO open.$clicks (event_id, user_id, campaign, event_time_ms, dt, extra_json, _topic, _partition, _offset) VALUES ('x', 1, 'c', 1, '2026-10-17', NULL, 't', 0, 0);
DROP TABLE open.$clicks;
CREATE TABLE open.$ods_k.made_by_reader (id BIGINT);
SELECT COUNT(*) FROM open.$clicks;
ALTER TABLE open.$clicks ADD COLUMN added_by_reader STRING;
ALTER TABLE open.$clicks RENAME TO $ods_k.renamed_by_reader;
SQL
spark_run reader-writes "$evidence/sql/reader-writes.sql" "$reader"
judge reader-cannot-insert failed "$evidence/spark/reader-writes.out" 1 Exception
judge reader-cannot-drop failed "$evidence/spark/reader-writes.out" 2 Forbidden
judge reader-cannot-create failed "$evidence/spark/reader-writes.out" 3 Exception
judge reader-still-reads has "$evidence/spark/reader-writes.out" 4 ""
judge reader-cannot-alter failed "$evidence/spark/reader-writes.out" 5 Forbidden
judge reader-cannot-rename failed "$evidence/spark/reader-writes.out" 6 Forbidden
cat >"$evidence/sql/outsider.sql" <<SQL
SELECT COUNT(*) FROM open.$clicks;
SQL
spark_run outsider "$evidence/sql/outsider.sql" "$outsider"
judge outsider-cannot-read failed "$evidence/spark/outsider.out" 1 Forbidden

polaris_open polaris-root
code="$(polaris_curl -s -o /dev/null -w '%{http_code}' "$(polaris_url "/api/catalog/v1/$POLARIS_CATALOG/namespaces/$ods_k/tables/clicks")")"
if [[ "$code" == 401 ]]; then
  verdict anonymous-refused PASS "loadTable without a token answers 401"
else
  verdict anonymous-refused FAIL "loadTable without a token answers $code"
fi
polaris_close
# From a reader's namespace, without a token: the catalog's configuration, its namespaces and the
# management API refuse; the management port (health, metrics) is not reachable.
cat >"$work/anonymous.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: ice-anonymous-$run}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 1000, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: curl
      image: $CURL_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["sh", "-c"]
      args:
        - |
          for path in "/api/catalog/v1/config?warehouse=$POLARIS_CATALOG" "/api/catalog/v1/$POLARIS_CATALOG/namespaces" /api/management/v1/catalogs /api/management/v1/principals; do
            echo "\$path \$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 --cacert /ca/ca.crt "https://polaris.$POLARIS_NAMESPACE.svc:8181\$path")"
          done
          if curl -s -o /dev/null --max-time 8 "http://polaris.$POLARIS_NAMESPACE.svc:8182/q/metrics"; then echo "8182 open"; else echo "8182 closed"; fi
      volumeMounts: [{name: ca, mountPath: /ca, readOnly: true}]
  volumes: [{name: ca, configMap: {name: colvenna-ca}}]
YAML
run_pod "$readers" "ice-anonymous-$run" "$work/anonymous.yaml" >"$evidence/status/anonymous.txt" 2>&1 || true
for path in config namespaces catalogs principals; do
  line="$(grep -E "/$path(\\?| )" "$evidence/status/anonymous.txt" | head -1)"
  if [[ "$line" == *" 401" ]]; then
    verdict "anonymous-$path-refused" PASS "$line"
  else
    verdict "anonymous-$path-refused" FAIL "${line:-no answer}"
  fi
done
if grep -q '^8182 closed$' "$evidence/status/anonymous.txt"; then
  verdict management-port-closed-to-readers PASS "8182 not reachable from $readers"
else
  verdict management-port-closed-to-readers FAIL "$(grep 8182 "$evidence/status/anonymous.txt")"
fi
# The reader's vended credentials: its table's files, read only.
polaris_open "$reader" "$readers"
polaris_curl -sS -K "$POLARIS_DIR/curlrc" -H 'X-Iceberg-Access-Delegation: vended-credentials' \
  "$(polaris_url "/api/catalog/v1/$POLARIS_CATALOG/namespaces/$ods_k/tables/clicks")" >"$work/load.json"
polaris_close
python3 - "$work/load.json" "$work" <<'PY'
import json, os, sys
t = json.load(open(sys.argv[1]))
c = t.get("config") or {}
for cred in t.get("storage-credentials") or []:
    c.update(cred.get("config") or {})
for name, key in (("ak", "s3.access-key-id"), ("sk", "s3.secret-access-key"), ("st", "s3.session-token")):
    open(os.path.join(sys.argv[2], name), "w").write(c.get(key, "") + "\n")
open(os.path.join(sys.argv[2], "own"), "w").write(t["metadata-location"])
open(os.path.join(sys.argv[2], "expires"), "w").write(c.get("s3.session-token-expires-at-ms", "") + "\n")
PY
vended_at="$(date +%s)"
expires_ms="$(cat "$work/expires")"
# 900 s, Polaris's STORAGE_CREDENTIAL_DURATION_SECONDS; a minute more for the clocks.
if [[ -n "$expires_ms" ]] && ((expires_ms / 1000 - vended_at <= 960)); then
  verdict vended-credentials-expire-within-15m PASS "expire $((expires_ms / 1000 - vended_at))s after they were vended"
else
  verdict vended-credentials-expire-within-15m FAIL "expiry: ${expires_ms:-not given}"
fi
own="$(cat "$work/own")"
bucket="$(echo "$own" | cut -d/ -f3)"
own_key="$(echo "$own" | cut -d/ -f4-)"
admin_open
other_key="$(admin_call GET "/v1/colvenna/databases/$ods/tables/orders" | python3 -c 'import json,sys; print(json.load(sys.stdin)["path"].split("/", 3)[3] + "/schema/schema-0")')"
admin_close
(
  # Only the vended credentials: not this host's profile.
  unset AWS_PROFILE AWS_DEFAULT_PROFILE
  IFS= read -r AWS_ACCESS_KEY_ID <"$work/ak"
  IFS= read -r AWS_SECRET_ACCESS_KEY <"$work/sk"
  IFS= read -r AWS_SESSION_TOKEN <"$work/st"
  export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_REGION
  aws s3api head-object --bucket "$bucket" --key "$own_key" >"$evidence/status/vended-own.txt" 2>&1 \
    && echo own-read-ok >>"$evidence/status/vended-own.txt"
  aws s3api head-object --bucket "$bucket" --key "$other_key" >"$evidence/status/vended-other.txt" 2>&1 \
    || echo other-read-denied >>"$evidence/status/vended-other.txt"
  echo x | aws s3 cp - "s3://$bucket/${own_key%/*}/written-by-reader" >"$evidence/status/vended-write.txt" 2>&1 \
    || echo write-denied >>"$evidence/status/vended-write.txt"
)
if grep -q own-read-ok "$evidence/status/vended-own.txt"; then
  verdict vended-reads-own-table PASS "head-object $own_key"
else
  verdict vended-reads-own-table FAIL "$(tail -2 "$evidence/status/vended-own.txt")"
fi
if grep -q other-read-denied "$evidence/status/vended-other.txt" \
  && grep -q -E '403|Forbidden' "$evidence/status/vended-other.txt"; then
  verdict vended-cannot-read-other-table PASS "$(grep -o -E '\(403\)[^.]*' "$evidence/status/vended-other.txt" | head -1)"
else
  verdict vended-cannot-read-other-table FAIL "$(tail -2 "$evidence/status/vended-other.txt")"
fi
if grep -q write-denied "$evidence/status/vended-write.txt" \
  && grep -q AccessDenied "$evidence/status/vended-write.txt"; then
  verdict vended-cannot-write PASS "$(grep -o 'AccessDenied[^:]*' "$evidence/status/vended-write.txt" | head -1)"
else
  verdict vended-cannot-write FAIL "$(tail -2 "$evidence/status/vended-write.txt")"
fi

# A reader pod without vended credentials: no AWS identity, no way to the data.
cat >"$work/nocreds.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: ice-nocreds-$run}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 1000, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: aws
      image: $AWS_CLI_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      env: [{name: HOME, value: /tmp}, {name: AWS_REGION, value: $AWS_REGION}]
      command: ["sh", "-c", "aws s3 ls s3://$bucket/$own_key 2>&1; echo exit=\$?"]
YAML
run_pod "$readers" "ice-nocreds-$run" "$work/nocreds.yaml" >"$evidence/status/nocreds.txt" 2>&1 || true
if grep -q 'exit=0' "$evidence/status/nocreds.txt"; then
  verdict reader-pod-has-no-other-way FAIL "$(head -2 "$evidence/status/nocreds.txt")"
else
  verdict reader-pod-has-no-other-way PASS "$(grep -m1 -v '^$' "$evidence/status/nocreds.txt" | cut -c1-160)"
fi

# --------------------------------------------------------------------------- withdrawal
step "deleting the publications takes the tables out of the catalog"
kubectl -n "$JOB_NAMESPACE" delete lakeicebergpublication "$pub_orders" "$pub_clicks" --timeout=5m >/dev/null
polaris_open polaris-root
for t in "$ods/orders" "$ods_k/clicks"; do
  code="$(polaris_code GET "/api/catalog/v1/$POLARIS_CATALOG/namespaces/${t%/*}/tables/${t#*/}")"
  if [[ "$code" == 404 ]]; then
    verdict "withdrawn-${t#*/}" PASS "loadTable answers 404 after deletion"
  else
    verdict "withdrawn-${t#*/}" FAIL "loadTable answers $code"
  fi
done
polaris_close
# Credentials vended before the withdrawal still read until they expire (STS credentials cannot be
# revoked one by one): the window the LLD gives.
(
  unset AWS_PROFILE AWS_DEFAULT_PROFILE
  IFS= read -r AWS_ACCESS_KEY_ID <"$work/ak"
  IFS= read -r AWS_SECRET_ACCESS_KEY <"$work/sk"
  IFS= read -r AWS_SESSION_TOKEN <"$work/st"
  export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
  aws --region "$AWS_REGION" s3api head-object --bucket "$bucket" --key "$own_key" \
    >"$evidence/status/vended-after-withdrawal.txt" 2>&1 \
    && echo still-reads >>"$evidence/status/vended-after-withdrawal.txt"
) || true
rm -f "$work/ak" "$work/sk" "$work/st"
left=$((${expires_ms:-0} / 1000 - $(date +%s)))
if grep -q still-reads "$evidence/status/vended-after-withdrawal.txt" && ((left > 0 && left <= 960)); then
  verdict vended-credentials-outlive-withdrawal-until-expiry PASS "still read ${left}s before they expire"
else
  verdict vended-credentials-outlive-withdrawal-until-expiry FAIL "$(tail -1 "$evidence/status/vended-after-withdrawal.txt"); ${left}s left"
fi

step "clean up"
kubectl -n "$JOB_NAMESPACE" delete laketask "c1-$run" "c2-$run" "tag-$run" --ignore-not-found >/dev/null
kubectl -n "$JOB_NAMESPACE" delete lakesource "$cdc_name" "$kafka_name" --timeout=10m >/dev/null || true
python3 "$cdc/mysql_contract.py" reset "$mysql_db" >"$evidence/sql/reset.sql"
bash "$REPO_DIR/tests/fixtures/sources/sources.sh" mysql "$evidence/sql/reset.sql" >/dev/null || true
kubectl -n "$JOB_NAMESPACE" delete secret "$kafka_name-kafka" --ignore-not-found >/dev/null
kubectl -n "$JOB_NAMESPACE" delete configmap "$kafka_name-kafka-ca" --ignore-not-found >/dev/null
kubectl -n "$readers" delete secret "$reader" "$outsider" "$native-token" --ignore-not-found >/dev/null
admin_open
revoke_principal "$native" >/dev/null 2>&1 || true
admin_close

echo
cut -f3 "$evidence/verdicts.tsv" | sort | uniq -c
awk -F'\t' '$3 != "PASS"' "$evidence/verdicts.tsv"
exit "$failed"
