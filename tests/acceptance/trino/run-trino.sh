#!/usr/bin/env bash
# Acceptance on EKS: a user's existing Trino reads a lake table, and the boundaries it meets.
# Writes PASS/FAIL lines to <evidence>/verdicts.tsv and fails if any check fails.
#
# - A Kafka source writes clicks (an append table with Iceberg metadata); a LakeIcebergPublication
#   publishes them to a Polaris catalog role granted to Trino's principal. Trino 484 reads
#   them with its Iceberg connector over Polaris's REST API (OAuth2 client credentials, vended
#   credentials): the rows are those Paimon reads natively, every column, at the snapshot
#   published, also after more events arrive; an earlier snapshot by time travel; the table's
#   metadata tables.
# - A MySQL source writes orders (a primary-key table with Iceberg metadata), published too. Its
#   Iceberg view is the fully compacted data: Trino does not find it before the first full
#   compaction, then reads Paimon's rows; after updates and deletes it still reads the compacted
#   rows, and after the next full compaction Paimon's again.
# - Authentication: no credentials, or a wrong client secret, get no token. Object storage:
#   without vended credentials Trino (no AWS identity of its own) reads no data. Authorization: a
#   principal with no grant reads neither the table nor its metadata tables; nobody writes, drops
#   or creates through Trino. Trino's own users all read with the catalog principal's grants: no
#   per-user authorization is inherited.
# - Nothing reaches Trino over the network but its own pod. The native Paimon connector
#   (apache/paimon-trino) has no release; Trino is not part of the platform's install.
#
# usage: run-trino.sh <run-id> <evidence-dir>; needs install-polaris.sh and an operator with
# Polaris configured (install-operator.sh).
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
# shellcheck source=trino.sh
source "$ACCEPTANCE_DIR/trino/trino.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-trino.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-trino.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
here="$REPO_DIR/tests/acceptance/trino"
lake="$REPO_DIR/tests/acceptance/lake.sh"
mkdir -p "$evidence"/{status,trino,sql,catalogs}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
for image in operator kafka mysql; do
  echo "$image $(image_ref "$image")" | tee -a "$evidence/images.txt"
done
echo "trino $TRINO_IMAGE" | tee -a "$evidence/images.txt"
failed=0
step() { echo "$(date -u +%FT%TZ)	$*" | tee -a "$evidence/timeline.tsv"; }
verdict() {
  # One line per verdict: a detail's newlines and tabs become spaces.
  local detail="${3:-}"
  detail="${detail//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${detail//$'\t'/ }" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS ]] || failed=1
}
ods="ods_trino_$run" topic="trino-$run" kafka_name="te-$run" pub="te-$run-clicks"
mysql_db="trinop_$run" ods_pk="ods_trinop_$run" cdc_name="tp-$run" pub_pk="tp-$run-orders"
orders="$ods_pk.orders" cdc="$REPO_DIR/tests/acceptance/cdc"
clicks="$ods.clicks" role="trino_read_$run" none_role="trino_none_$run"
principal_name="trino-trino-$run" outsider_name="trino-outsider-$run"
bootstrap="$DEV_KAFKA_RELEASE-kafka-bootstrap.$DEV_KAFKA_NAMESPACE.svc:9093"
small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
work="$(mktemp -d)"
chmod 700 "$work"
trap 'polaris_close; admin_close; rm -rf "$work"' EXIT

get() { kubectl -n "$JOB_NAMESPACE" get "$1" "$2" -o json >"$3"; }

# await <label> <timeout-seconds> <kind> <name> <python expression over st, cond(), table()>
await() {
  local label="$1" end=$((SECONDS + $2)) started=$SECONDS file="$evidence/status/$1.json"
  while ((SECONDS < end)); do
    if get "$3" "$4" "$file" 2>/dev/null && python3 - "$file" "$5" <<'PY'; then
import json, sys
st = json.load(open(sys.argv[1])).get("status") or {}
def cond(t):
    return next((c.get("status") for c in st.get("conditions", []) if c.get("type") == t), None)
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
  return 1
}

# judge <label> <trino.py args>...
judge() {
  local label="$1"
  shift
  if python3 "$here/trino.py" "$@" >"$evidence/trino/$label.verdict.json"; then
    verdict "$label" PASS "$(head -c 400 "$evidence/trino/$label.verdict.json")"
  else
    verdict "$label" FAIL "$(head -c 700 "$evidence/trino/$label.verdict.json")"
  fi
}

# ask <label> <sql> [<trino user>]: the statement's answer, kept as evidence.
ask() {
  trino_sql "$evidence/trino/$1.csv" "$2" "${3:-analyst}" || true
}

# produce <name> <count>: clicks at now, to the topic as the producer; waits for them.
total=0
produce() {
  local pod="trino-produce-$RANDOM"
  python3 - "$1" "$2" >"$evidence/sql/$1.txt" <<'PY'
import datetime, json, sys
name, count = sys.argv[1], int(sys.argv[2])
now = datetime.datetime.now(datetime.timezone.utc).isoformat()
for i in range(count):
    e = {"event_id": f"{name}-{i}", "user_id": 1 + i % 40, "event_time": now}
    print(f"{e['event_id']}|{json.dumps(e)}")
PY
  kubectl -n "$DEV_KAFKA_NAMESPACE" get secret trino-producer -o jsonpath='{.data.sasl\.jaas\.config}' \
    | base64 -d >"$work/jaas"
  kubectl -n "$JOB_NAMESPACE" create secret generic "$pod" --from-file=sasl.jaas.config="$work/jaas" \
    --from-file=ca.crt="$work/kafka-ca.pem" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  rm -f "$work/jaas"
  kubectl -n "$JOB_NAMESPACE" create configmap "$pod" --from-file=batch.txt="$evidence/sql/$1.txt" \
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
  total=$((total + $2))
  # A miss is a FAIL verdict; the run goes on to its cleanup.
  await "events-$1" 600 lakesource "$kafka_name" \
    "int((st.get('events') or {}).get('accepted') or 0) >= $total" || true
}

# published_at_latest <label> [<db.table> <publication>]: the catalog points at Paimon's latest
# snapshot of the table (clicks unless given).
published_at_latest() {
  bash "$lake" table "$work/head.jsonl" "${2:-$clicks}" >/dev/null
  latest="$(python3 -c 'import json,sys; print([json.loads(l) for l in open(sys.argv[1]) if l.startswith("{")][0]["snapshot"])' "$work/head.jsonl")"
  await "$1" 300 lakeicebergpublication "${3:-$pub}" \
    "st.get('phase') == 'Published' and int(published.get('snapshot') or 0) >= $latest"
}

# compact <task>: a full compaction of orders, waited for.
compact() {
  kubectl apply -f - >/dev/null <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: $1, namespace: $JOB_NAMESPACE}
spec:
  type: compact
  table: {database: $ods_pk, table: orders}
  compact: {full: true}
  resources: {cpu: 0.5, memory: 2048m}
YAML
  await "compacted-$1" 900 laketask "$1" "st.get('phase') == 'Succeeded'"
}

# The orders as Trino reads them, every column as text.
orders_sql="SELECT CAST(id AS VARCHAR), CAST(customer_id AS VARCHAR), CAST(amount AS VARCHAR), state, note, CAST(version_no AS VARCHAR) FROM iceberg.$orders"

# ------------------------------------------------------------------------ the published table
step "Kafka topic $topic and source $kafka_name"
cat >"$evidence/kafka-values.yaml" <<YAML
topics:
  - {name: dev-events, partitions: 3, retentionMs: 86400000}
  - {name: $topic, partitions: 1, retentionMs: 86400000}
users:
  - {name: dev-producer, write: [dev-events]}
  - {name: dev-consumer, read: [dev-events], groups: [dev-]}
  - {name: trino-producer, write: [$topic]}
  - {name: trino-ingest, read: [$topic], groups: [colvenna.]}
YAML
bash "$REPO_DIR/tests/fixtures/kafka/dev-kafka.sh" install "$evidence/kafka" -f "$evidence/kafka-values.yaml"
for r in "kafkatopic/$topic" kafkauser/trino-producer kafkauser/trino-ingest; do
  kubectl -n "$DEV_KAFKA_NAMESPACE" wait "$r" --for=condition=Ready --timeout=5m >/dev/null
done
for ns in "$JOB_NAMESPACE" "$OPERATOR_NAMESPACE"; do
  kubectl label namespace "$ns" --overwrite colvenna-lake.io/dev-kafka-client=true >/dev/null
done
kubectl -n "$DEV_KAFKA_NAMESPACE" get secret "$DEV_KAFKA_RELEASE-cluster-ca-cert" \
  -o jsonpath='{.data.ca\.crt}' | base64 -d >"$work/kafka-ca.pem"
kubectl -n "$JOB_NAMESPACE" create configmap "$kafka_name-kafka-ca" --from-file=ca.pem="$work/kafka-ca.pem" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$DEV_KAFKA_NAMESPACE" get secret trino-ingest -o jsonpath='{.data.password}' | base64 -d \
  >"$work/ingest"
kubectl -n "$JOB_NAMESPACE" create secret generic "$kafka_name-kafka" --from-literal=username=trino-ingest \
  --from-file=password="$work/ingest" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
rm -f "$work/ingest"
cat >"$evidence/source.yaml" <<YAML
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
    database: $ods
    table: clicks
    tableOptions: {metadata.iceberg.storage: table-location, snapshot.time-retained: 1 d}
  governance: {owner: web-team, level: L2, retention: 7d}
  job: $small
  deletionPolicy: Delete
YAML
kubectl apply -f "$evidence/source.yaml"
await source-running 900 lakesource "$kafka_name" \
  "st.get('phase') == 'Running' and cond('TargetReady') == 'True'" || true
produce b1 40

step "MySQL fixture $mysql_db and source $cdc_name: orders, a primary-key table"
{
  python3 "$cdc/mysql_contract.py" reset "$mysql_db"
  python3 "$cdc/mysql_contract.py" setup "$mysql_db"
  cat <<SQL
USE $mysql_db;
INSERT INTO orders (id, customer_id, amount, state, note, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 60)
  SELECT i, 1 + i % 20, ROUND(i * 1.25, 2), 'created', NULL, 1 FROM n;
SQL
} >"$evidence/sql/setup.sql"
bash "$REPO_DIR/tests/fixtures/sources/sources.sh" users >/dev/null
bash "$REPO_DIR/tests/fixtures/sources/sources.sh" mysql "$evidence/sql/setup.sql" >/dev/null
ensure_rds_ca "$JOB_NAMESPACE"
cat >"$evidence/source-pk.yaml" <<YAML
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
    database: $ods_pk
    bucket: 2
    tableOptions: {metadata.iceberg.storage: table-location, snapshot.time-retained: 1 d}
  governance: {owner: shop-team, level: L2, retention: 7d}
  job: $small
  deletionPolicy: Delete
YAML
kubectl apply -f "$evidence/source-pk.yaml"
await cdc-source-ready 900 lakesource "$cdc_name" "table('orders').get('state') == 'Ready'" || true

step "Polaris: Trino's principal (granted the table) and an outsider's (granted nothing)"
kubectl create namespace "$TRINO_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
polaris_open polaris-root
for r in "$role" "$none_role"; do
  polaris_ensure "/api/management/v1/catalogs/$POLARIS_CATALOG/catalog-roles" \
    "{\"catalogRole\": {\"name\": \"$r\"}}"
done
# principal <name> <principal-role> <catalog-role>: its credentials in a Secret of Trino's.
principal() {
  polaris_ensure /api/management/v1/principal-roles "{\"principalRole\": {\"name\": \"$2\"}}"
  polaris_call PUT "/api/management/v1/principal-roles/$2/catalog-roles/$POLARIS_CATALOG" \
    "{\"catalogRole\": {\"name\": \"$3\"}}" >/dev/null
  if ! kubectl -n "$TRINO_NAMESPACE" get secret "$1" >/dev/null 2>&1; then
    polaris_call POST /api/management/v1/principals "{\"principal\": {\"name\": \"$1\"}}" \
      | python3 -c '
import json, os, sys
c = json.load(sys.stdin)["credentials"]
open(os.path.join(sys.argv[1], "client-id"), "w").write(c["clientId"])
open(os.path.join(sys.argv[1], "client-secret"), "w").write(c["clientSecret"])' "$work"
    kubectl -n "$TRINO_NAMESPACE" create secret generic "$1" --from-file=client-id="$work/client-id" \
      --from-file=client-secret="$work/client-secret" >/dev/null
    rm -f "$work/client-secret"
  fi
  polaris_call PUT "/api/management/v1/principals/$1/principal-roles" \
    "{\"principalRole\": {\"name\": \"$2\"}}" >/dev/null
}
principal "$principal_name" "trino_trino_$run" "$role"
principal "$outsider_name" "trino_outsiders_$run" "$none_role"
polaris_close
cat >"$evidence/publication.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeIcebergPublication
metadata: {name: $pub, namespace: $JOB_NAMESPACE}
spec:
  table: {database: $ods, table: clicks}
  columns: [event_id, user_id, event_time_ms, dt, extra_json, _topic, _partition, _offset]
  readers: [$role]
---
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeIcebergPublication
metadata: {name: $pub_pk, namespace: $JOB_NAMESPACE}
spec:
  table: {database: $ods_pk, table: orders}
  columns: [id, customer_id, amount, state, note, version_no]
  readers: [$role]
YAML
kubectl apply -f "$evidence/publication.yaml"
await published 600 lakeicebergpublication "$pub" \
  "st.get('phase') == 'Published' and st.get('readers') == ['$role']" || true

step "Trino 484 with five catalogs: the right one, and four that must fail"
# catalog <name> <secret> <vended> [<wrong secret>]
catalog() {
  local id secret
  id="$(kubectl -n "$TRINO_NAMESPACE" get secret "$2" -o jsonpath='{.data.client-id}' | base64 -d)"
  secret="$(kubectl -n "$TRINO_NAMESPACE" get secret "$2" -o jsonpath='{.data.client-secret}' | base64 -d)"
  [[ -z "${4:-}" ]] || secret="$4"
  cat >"$work/$1.properties" <<PROPS
connector.name=iceberg
iceberg.catalog.type=rest
iceberg.rest-catalog.uri=https://polaris.$POLARIS_NAMESPACE.svc:8181/api/catalog
iceberg.rest-catalog.warehouse=$POLARIS_CATALOG
iceberg.rest-catalog.security=OAUTH2
iceberg.rest-catalog.oauth2.credential=$id:$secret
iceberg.rest-catalog.oauth2.scope=PRINCIPAL_ROLE:ALL
iceberg.rest-catalog.oauth2.server-uri=https://polaris.$POLARIS_NAMESPACE.svc:8181/api/catalog/v1/oauth/tokens
iceberg.rest-catalog.vended-credentials-enabled=$3
iceberg.rest-catalog.metrics-reporting-enabled=false
fs.s3.enabled=true
s3.region=$AWS_REGION
PROPS
  # Kept as evidence without the secret.
  sed 's/oauth2.credential=.*/oauth2.credential=<id>:<secret>/' "$work/$1.properties" \
    >"$evidence/catalogs/$1.properties"
}
mkdir -p "$work/catalogs"
catalog iceberg "$principal_name" true
catalog iceberg_novend "$principal_name" false
catalog iceberg_wrong "$principal_name" true "not-the-secret"
catalog iceberg_outsider "$outsider_name" true
# No credentials at all: Polaris is asked anonymously.
cat >"$work/iceberg_anonymous.properties" <<PROPS
connector.name=iceberg
iceberg.catalog.type=rest
iceberg.rest-catalog.uri=https://polaris.$POLARIS_NAMESPACE.svc:8181/api/catalog
iceberg.rest-catalog.warehouse=$POLARIS_CATALOG
iceberg.rest-catalog.security=NONE
iceberg.rest-catalog.vended-credentials-enabled=true
iceberg.rest-catalog.metrics-reporting-enabled=false
fs.s3.enabled=true
s3.region=$AWS_REGION
PROPS
cp "$work/iceberg_anonymous.properties" "$evidence/catalogs/"
mv "$work"/*.properties "$work/catalogs/"
bash "$REPO_DIR/tests/fixtures/trino/install-trino.sh" "$evidence/trino-install" "$work/catalogs"
rm -rf "$work/catalogs"

# ----------------------------------------------------------------------------- the reads
step "Trino reads what Paimon reads, at the snapshot published"
published_at_latest published-at-latest-b1 || true
bash "$lake" rows "$evidence/status/lake-b1.jsonl" "$clicks" >/dev/null
# Every column: the lake's are its values as text, NULL as nothing.
ask read-b1 "SELECT event_id, CAST(user_id AS VARCHAR), CAST(event_time_ms AS VARCHAR), dt, extra_json, _topic, CAST(_partition AS VARCHAR), CAST(_offset AS VARCHAR) FROM iceberg.$ods.clicks"
judge trino-reads-what-paimon-reads same "$evidence/trino/read-b1.csv" "$evidence/status/lake-b1.jsonl" event_id user_id event_time_ms dt extra_json _topic _partition _offset
get lakeicebergpublication "$pub" "$evidence/status/publication-b1.json"
snapshot="$(python3 -c 'import json,sys; print((json.load(open(sys.argv[1]))["status"].get("published") or {}).get("snapshot", ""))' "$evidence/status/publication-b1.json" || true)"
b1_rows="$(grep -c '"type":"ROW"' "$evidence/status/lake-b1.jsonl" || true)"
ask snapshot-b1 "SELECT CAST(snapshot_id AS VARCHAR) FROM iceberg.$ods.\"clicks\$snapshots\" ORDER BY committed_at DESC LIMIT 1"
judge trino-sees-the-published-snapshot equal "$evidence/trino/snapshot-b1.csv" "$snapshot"
produce b2 25
published_at_latest published-at-latest-b2 || true
bash "$lake" rows "$evidence/status/lake-b2.jsonl" "$clicks" >/dev/null
ask read-b2 "SELECT event_id, CAST(user_id AS VARCHAR), CAST(event_time_ms AS VARCHAR), dt, extra_json, _topic, CAST(_partition AS VARCHAR), CAST(_offset AS VARCHAR) FROM iceberg.$ods.clicks"
judge trino-follows-new-commits same "$evidence/trino/read-b2.csv" "$evidence/status/lake-b2.jsonl" event_id user_id event_time_ms dt extra_json _topic _partition _offset
# Iceberg's history and metadata tables are the table's: what was published stays readable.
ask travel-b1 "SELECT CAST(count(*) AS VARCHAR) FROM iceberg.$ods.clicks FOR VERSION AS OF CAST(${snapshot:-0} AS BIGINT)"
judge time-travel-reads-the-earlier-snapshot equal "$evidence/trino/travel-b1.csv" "$b1_rows"
ask files "SELECT CAST(count(*) AS VARCHAR) FROM iceberg.$ods.\"clicks\$files\""
if [[ "$(cat "$evidence/trino/files.csv")" =~ ^[1-9][0-9]*$ ]]; then
  verdict metadata-tables-readable PASS "clicks\$files lists $(cat "$evidence/trino/files.csv") data files"
else
  verdict metadata-tables-readable FAIL "$(head -c 300 "$evidence/trino/files.csv")"
fi

step "orders: not in the catalog before a full compaction; then Paimon's rows, before and after changes"
# Its first metadata holds files not compacted yet: withheld, so Trino finds nothing.
await orders-withheld-until-compacted 600 lakeicebergpublication "$pub_pk" \
  "st.get('phase') == 'Pending' and not published and not st.get('readers')" || true
ask orders-before "SELECT count(*) FROM iceberg.$orders"
judge orders-not-served-before-compaction failed "$evidence/trino/orders-before.csv" "does not exist" "not found"
compact "c1-$run" || true
published_at_latest orders-published-after-compaction "$orders" "$pub_pk" || true
bash "$lake" rows "$evidence/status/lake-orders-c1.jsonl" "$orders" >/dev/null || true
ask orders-c1 "$orders_sql"
judge trino-reads-the-compacted-orders same "$evidence/trino/orders-c1.csv" "$evidence/status/lake-orders-c1.jsonl" id customer_id amount state note version_no
cat >"$evidence/sql/changes.sql" <<SQL
USE $mysql_db;
UPDATE orders SET amount = amount + 100, state = 'paid', version_no = version_no + 1 WHERE id <= 10;
DELETE FROM orders WHERE id > 55;
SQL
bash "$REPO_DIR/tests/fixtures/sources/sources.sh" mysql "$evidence/sql/changes.sql" >/dev/null
# The changes reach the lake; its fully compacted view, which is what is published, is as it was:
# the rows of the first compaction (nothing compacts the ODS table but the tasks here, its writer
# does not), which is Paimon's $ro.
end=$((SECONDS + 300)) started=$SECONDS rows=""
while ((SECONDS < end)); do
  bash "$lake" rows "$evidence/status/lake-orders-changed.jsonl" "$orders" >/dev/null || true
  rows="$(grep -c '"type":"ROW"' "$evidence/status/lake-orders-changed.jsonl" || true)"
  [[ "$rows" == 55 ]] && break
  sleep 15
done
if [[ "$rows" == 55 ]]; then
  verdict changes-reached-the-lake PASS "$((SECONDS - started))s: Paimon reads 55 orders"
else
  verdict changes-reached-the-lake FAIL "Paimon reads ${rows:-no} orders"
fi
published_at_latest orders-published-after-changes "$orders" "$pub_pk" || true
ask orders-stale "$orders_sql"
judge trino-reads-the-compacted-orders-until-the-next-compaction same "$evidence/trino/orders-stale.csv" "$evidence/status/lake-orders-c1.jsonl" id customer_id amount state note version_no
compact "c2-$run" || true
published_at_latest orders-published-after-c2 "$orders" "$pub_pk" || true
bash "$lake" rows "$evidence/status/lake-orders-c2.jsonl" "$orders" >/dev/null || true
ask orders-c2 "$orders_sql"
judge trino-reads-the-changed-orders same "$evidence/trino/orders-c2.csv" "$evidence/status/lake-orders-c2.jsonl" id customer_id amount state note version_no
ask orders-paid "SELECT CAST(count(*) AS VARCHAR) FROM iceberg.$orders WHERE state = 'paid'"
judge updated-orders-visible equal "$evidence/trino/orders-paid.csv" 10
ask orders-gone "SELECT CAST(count(*) AS VARCHAR) FROM iceberg.$orders WHERE id > 55"
judge deleted-orders-gone equal "$evidence/trino/orders-gone.csv" 0

# ------------------------------------------------------------------------------ boundaries
step "authentication, object storage, authorization"
ask anonymous "SELECT count(*) FROM iceberg_anonymous.$ods.clicks"
judge anonymous-gets-nothing failed "$evidence/trino/anonymous.csv" notauthorizedexception "not authorized" unauthorized
ask wrong "SELECT count(*) FROM iceberg_wrong.$ods.clicks"
judge wrong-secret-gets-no-token failed "$evidence/trino/wrong.csv" unauthorized_client invalid_client "not authorized"
ask novend "SELECT count(*) FROM iceberg_novend.$ods.clicks"
judge no-vended-credentials-no-data failed "$evidence/trino/novend.csv" "unable to load credentials" "access denied"
ask outsider "SELECT count(*) FROM iceberg_outsider.$ods.clicks"
judge ungranted-principal-reads-nothing failed "$evidence/trino/outsider.csv" forbiddenexception "not authorized"
ask outsider-files "SELECT count(*) FROM iceberg_outsider.$ods.\"clicks\$files\""
judge ungranted-principal-reads-no-metadata-table failed "$evidence/trino/outsider-files.csv" forbiddenexception "not authorized"
# Every column the table requires, so that the write reaches S3 and Polaris.
ask insert "INSERT INTO iceberg.$ods.clicks (event_id, user_id, event_time_ms, dt, extra_json, _topic, _partition, _offset) VALUES ('trino-forged', 1, 1, '2026-10-17', NULL, 'm4t', 0, 0)"
judge insert-refused failed "$evidence/trino/insert.csv" accessdeniedexception forbiddenexception
ask drop "DROP TABLE iceberg.$ods.clicks"
judge drop-refused failed "$evidence/trino/drop.csv" forbiddenexception
ask create "CREATE TABLE iceberg.$ods.trino_forged (id BIGINT)"
judge create-refused failed "$evidence/trino/create.csv" forbiddenexception
ask count-after "SELECT count(*) FROM iceberg.$ods.clicks"
judge nothing-written equal "$evidence/trino/count-after.csv" "$total"
ask alice "SELECT count(*) FROM iceberg.$ods.clicks" alice
ask bob "SELECT count(*) FROM iceberg.$ods.clicks" bob
if cmp -s "$evidence/trino/alice.csv" "$evidence/trino/bob.csv" && [[ "$(cat "$evidence/trino/alice.csv")" == "$total" ]]; then
  verdict trino-users-share-the-principal PASS "alice and bob both read $total rows: Trino's users are not told apart, what any may read is the catalog principal's grants"
else
  verdict trino-users-share-the-principal FAIL "alice: $(cat "$evidence/trino/alice.csv"); bob: $(cat "$evidence/trino/bob.csv")"
fi

step "nothing reaches Trino over the network; no paimon-trino release; not in the platform's install"
if [[ -z "$(kubectl -n "$TRINO_NAMESPACE" get services,ingresses -o name)" ]]; then
  verdict trino-has-no-service PASS "no Service or Ingress in $TRINO_NAMESPACE"
else
  verdict trino-has-no-service FAIL "$(kubectl -n "$TRINO_NAMESPACE" get services,ingresses -o name | tr '\n' ' ')"
fi
ip="$(kubectl -n "$TRINO_NAMESPACE" get pod -l app=trino --field-selector=status.phase=Running \
  -o jsonpath='{.items[0].status.podIP}' 2>/dev/null || true)"
# The port is there: the pod itself reaches it on its own address.
own="$(kubectl -n "$TRINO_NAMESPACE" exec deploy/trino -c trino -- \
  bash -c "exec 3<>/dev/tcp/$ip/8080 && echo reached || echo refused" 2>&1 | tail -1 || true)"
if [[ -n "$ip" && "$own" == reached ]]; then
  verdict trino-listens-on-its-address PASS "$ip:8080 from Trino's pod: $own"
else
  verdict trino-listens-on-its-address FAIL "ip '$ip', from Trino's pod: $own"
fi
cat >"$work/probe.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: trino-probe-$run}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 70, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: probe
      image: $POSTGRES_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["sh", "-c"]
      args: ["if wget -q -T 10 -O /dev/null http://$ip:8080/v1/info; then echo reached; else echo refused; fi"]
YAML
got="$(run_pod "$JOB_NAMESPACE" "trino-probe-$run" "$work/probe.yaml" | tail -1 || true)"
if [[ -n "$ip" && "$got" == refused ]]; then
  verdict trino-unreachable-from-other-pods PASS "a pod of $JOB_NAMESPACE: $got"
else
  verdict trino-unreachable-from-other-pods FAIL "a pod of $JOB_NAMESPACE: $got"
fi
code="$(curl -s -o /dev/null -w '%{http_code}' https://repo1.maven.org/maven2/org/apache/paimon/paimon-trino-476/maven-metadata.xml || true)"
if [[ "$code" == 404 ]]; then
  verdict paimon-trino-unreleased PASS "Maven Central has no org.apache.paimon:paimon-trino-476 ($code)"
else
  verdict paimon-trino-unreleased FAIL "Maven Central answered $code"
fi
if ! helm template colvenna-operator "$REPO_DIR/deploy/helm/colvenna-operator" --set image.repository=r \
  --set image.digest=sha256:0 --set catalog.caCert=ca --set jobs.checkpointRoot=s3://b/p \
  --set jobs.roleArns.colvenna-data=arn:aws:iam::1:role/r --set 'managedNamespaces={colvenna-data}' >"$work/chart.yaml"; then
  verdict trino-not-in-the-platform-install FAIL "the operator chart did not render"
elif grep -qi trino "$work/chart.yaml"; then
  verdict trino-not-in-the-platform-install FAIL "the operator chart names Trino"
else
  verdict trino-not-in-the-platform-install PASS "the operator chart renders nothing of Trino"
fi

# ------------------------------------------------------------------------------- clean up
step "clean up"
kubectl -n "$JOB_NAMESPACE" delete lakeicebergpublication "$pub" "$pub_pk" --wait=true --timeout=5m >/dev/null || true
kubectl -n "$JOB_NAMESPACE" delete laketask "c1-$run" "c2-$run" --ignore-not-found >/dev/null || true
kubectl -n "$JOB_NAMESPACE" delete lakesource "$kafka_name" "$cdc_name" --wait=true --timeout=10m >/dev/null || true
python3 "$cdc/mysql_contract.py" reset "$mysql_db" >"$evidence/sql/reset.sql"
bash "$REPO_DIR/tests/fixtures/sources/sources.sh" mysql "$evidence/sql/reset.sql" >/dev/null || true
kubectl delete namespace "$TRINO_NAMESPACE" --wait=false >/dev/null || true

echo
awk -F'\t' '{print $3}' "$evidence/verdicts.tsv" | sort | uniq -c
exit "$failed"
