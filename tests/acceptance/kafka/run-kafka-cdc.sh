#!/usr/bin/env bash
# Acceptance on EKS: a declared MySQL source moved from its binlog to the user's Kafka, under
# continuous source writes, with a downstream LakeTable reading it throughout. Debezium Server
# (the user's collector) writes Debezium JSON with schemas to the development Kafka
# (tests/fixtures/kafka/colvenna-dev-kafka, standing in for the user's cluster). Writes PASS/FAIL lines to
# <evidence>/verdicts.tsv and fails if any check fails.
#
# - The source ingests orders and segments from the binlog; DWD (orders with their segment, a
#   lookup join) reads ODS.
# - The switch: spec.transport.kafka is added while the writer runs. The direct job goes first;
#   the Kafka job compacts the targets and moves them to a deduplicated lookup changelog, then
#   starts an overlap before their last write, dropping the replayed changes of rows deleted
#   meanwhile.
# - Kafka's own failure modes: records the job already read sent again (counted stale, nothing
#   written), records that are not Debezium changes (counted, a Warning event, nothing written),
#   the collector losing its position (stopped while 5 rows are deleted, then a new snapshot of
#   every row: absorbed, and the deletes it never sent found by reconciling with the source and
#   written while the job is suspended), and the job deleted behind the operator's back
#   (recreated from the targets, not the consumer group).
# - At the end, with the writer stopped: ODS orders and segments equal the source tables, DWD the
#   source join, and every changelog (ODS across the switch, DWD) replays with each row's version
#   rising: nothing read twice reached the tables or their readers.
# - Deletion: jobs gone, tables dropped, tokens refused.
#
# usage: run-kafka-cdc.sh <run-id> <evidence-dir>; KEEP_KAFKA=1 leaves the development Kafka.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-kafka-cdc.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-kafka-cdc.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
flows="$REPO_DIR/tests/acceptance"
cdc="$flows/cdc"
mkdir -p "$evidence"/{status,dumps,sql,tables,kafka-client}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
for image in operator mysql kafka sql; do
  echo "$image $(image_ref "$image")" | tee -a "$evidence/images.txt"
done
echo "debezium $DEBEZIUM_SERVER_IMAGE" | tee -a "$evidence/images.txt"
failed=0
step() { echo "$(date -u +%FT%TZ)	$*" | tee -a "$evidence/timeline.tsv"; }
verdict() {
  # One line per verdict: a detail's newlines and tabs become spaces.
  local detail="${3:-}"
  detail="${detail//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${detail//$'\t'/ }" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS ]] || failed=1
}

# Names without underscores: the topics are Kubernetes resources, named after the database.
db="kcdc$run" ods="ods_kcdc$run" dwd="dwd_kcdc$run"
source_name="lkc-$run" dwd_name="dwk-$run" debezium="dbz-$run"
prefix="kafka-$run"
orders_topic="$prefix.$db.orders" segments_topic="$prefix.$db.segments"
bootstrap="$DEV_KAFKA_RELEASE-kafka-bootstrap.$DEV_KAFKA_NAMESPACE.svc:9093"
client_label=colvenna-lake.io/dev-kafka-client
small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
work="$(mktemp -d)"
chmod 700 "$work"
# leave: on any exit before the run's own clean-up at the end (a failed step under set -e), what the
# run declared is removed as that clean-up removes it, so a failure leaves nothing for the next tests
# of the namespace.
finished=false
# shellcheck disable=SC2317  # invoked through the EXIT trap
leave() {
  admin_close
  if ! $finished; then
    echo "$(date -u +%FT%TZ) leaving early: removing tk-$run, $dwd_name, $source_name and $debezium" >&2
    bash "$cdc/writer.sh" stop "tk-$run" >/dev/null 2>&1 || true
    kubectl -n "$JOB_NAMESPACE" delete deployment "$debezium" --ignore-not-found --wait=true >/dev/null 2>&1 || true
    kubectl -n "$JOB_NAMESPACE" delete laketable "$dwd_name" --ignore-not-found --timeout=10m >/dev/null 2>&1 || true
    kubectl -n "$JOB_NAMESPACE" delete lakesource "$source_name" --ignore-not-found --timeout=10m >/dev/null 2>&1 || true
    kubectl -n "$JOB_NAMESPACE" delete configmap "$debezium" "$source_name-kafka-ca" --ignore-not-found >/dev/null 2>&1 || true
    kubectl -n "$JOB_NAMESPACE" delete secret "$debezium-kafka" "$source_name-kafka" --ignore-not-found >/dev/null 2>&1 || true
  fi
  rm -rf "$work"
}
trap leave EXIT
ensure_rds_ca "$JOB_NAMESPACE"

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
  python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); s=(d.get("source") or d.get("table")).get("status",{}); print(s.get("phase"), s.get("message"), s.get("transport"), s.get("kafka")); [print(c["type"], c["status"], c.get("reason"), c.get("message")) for c in s.get("conditions",[])]' \
    "$file" 2>/dev/null || true
  return 1
}

warning_event() {
  grep -qx "$2" <<<"$(kubectl -n "$JOB_NAMESPACE" get events --field-selector "involvedObject.name=$1,type=Warning" \
    -o jsonpath='{range .items[*]}{.reason}{"\n"}{end}')"
}

# events_of <name>: the object's events, oldest first, as "<time> <reason> <message>". Search it with
# grep -q <<<"$(events_of ...)", never through a pipe: grep -q stops reading at its match, and
# kubectl, still writing, dies of SIGPIPE and fails the pipeline (pipefail) though the event is there.
events_of() {
  kubectl -n "$JOB_NAMESPACE" get events --field-selector "involvedObject.name=$1" \
    --sort-by=.metadata.creationTimestamp \
    -o jsonpath='{range .items[*]}{.metadata.creationTimestamp} {.reason} {.message}{"\n"}{end}'
}

token_status() {
  printf 'header = "Authorization: Bearer %s"\n' "$(cat "$1")" >"$ADMIN_DIR/probe.curlrc"
  curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/probe.curlrc" \
    "https://localhost:$ADMIN_PORT/v1/config?warehouse=colvenna"
}

table_http() {
  curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/curlrc" \
    "https://localhost:$ADMIN_PORT/v1/colvenna/databases/$1/tables/$2"
}

# status_field <python-expression over the source status s>
status_field() {
  kubectl -n "$JOB_NAMESPACE" get lakesource "$source_name" -o json \
    | python3 -c 'import json,sys; s=json.load(sys.stdin).get("status",{}); print(eval(sys.argv[1]))' "$1"
}

# client <name> <user> <script>: a Kafka client in the job namespace as the user, the cluster CA
# trusted; the script finds its settings in /tmp/client.properties.
client() {
  local pod="$1" user="$2" script="$3"
  kubectl -n "$DEV_KAFKA_NAMESPACE" get secret "$user" -o jsonpath='{.data.sasl\.jaas\.config}' \
    | base64 -d >"$work/jaas"
  kubectl -n "$JOB_NAMESPACE" create secret generic "$pod" --from-file=sasl.jaas.config="$work/jaas" \
    --from-file=ca.crt="$work/kafka-ca.pem" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  rm -f "$work/jaas"
  cat >"$evidence/kafka-client/$pod.yaml" <<YAML
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
$(printf '%s\n' "$script" | sed 's/^/          /')
      resources: {requests: {cpu: 100m, memory: 512Mi}, limits: {memory: 512Mi}}
      volumeMounts: [{name: client, mountPath: /client, readOnly: true}]
  volumes: [{name: client, secret: {secretName: $pod}}]
YAML
  local status=0
  run_pod "$JOB_NAMESPACE" "$pod" "$evidence/kafka-client/$pod.yaml" || status=1
  kubectl -n "$JOB_NAMESPACE" delete secret "$pod" --ignore-not-found >/dev/null
  return "$status"
}

bin=/opt/kafka/bin
# end_offset <topic> <partition>: the partition's end offset, 0 when the client cannot tell.
end_offset() {
  local end
  end="$(client "ends-$RANDOM" tamper \
    "$bin/kafka-get-offsets.sh --bootstrap-server $bootstrap --command-config /tmp/client.properties --topic $1 --partitions $2 --time latest" \
    | awk -F: -v t="$1" '$1 == t {print $3}')" || true
  echo "${end:-0}"
}

# compare_table <label> <source-table> <lake-table> <key> <version>: rows equal the source
# table's, and the full changelog replays with rising versions to them.
compare_table() {
  local label="$1" attempt rows="$evidence/dumps/$1.rows.jsonl" source="$evidence/dumps/$1.source.tsv"
  for attempt in $(seq 1 15); do
    if [[ "$2" == *.sql ]]; then
      bash "$FIXTURES_DIR/sources/sources.sh" mysql-query "$2" >"$source"
    else
      bash "$FIXTURES_DIR/sources/sources.sh" mysql-table "$db" "$2" >"$source"
    fi
    bash "$flows/lake.sh" rows "$rows" "$3" >/dev/null
    if python3 "$cdc/compare.py" rows --lake "$rows" --source "$source" --table "$3" \
      --key "$4" >"$evidence/dumps/$label.rows-verdict.json"; then
      verdict "rows-$label" PASS "$(cat "$evidence/dumps/$label.rows-verdict.json")"
      bash "$flows/lake.sh" changelog "$evidence/dumps/$label.changelog.jsonl" "$3" >/dev/null
      if python3 "$cdc/compare.py" changelog --lake "$evidence/dumps/$label.changelog.jsonl" \
        --rows "$rows" --table "$3" --key "$4" --version "$5" \
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

# ------------------------------------------------------------------- the user's Kafka
step "development Kafka with the connector's topics and the users debezium, ingest, tamper"
cat >"$evidence/kafka-values.yaml" <<YAML
topics:
  - {name: dev-events, partitions: 3, retentionMs: 86400000}
  - {name: $orders_topic, partitions: 2, retentionMs: 86400000}
  - {name: $segments_topic, partitions: 1, retentionMs: 86400000}
users:
  - {name: dev-producer, write: [dev-events]}
  - {name: dev-consumer, read: [dev-events], groups: [dev-]}
  - {name: debezium, write: [$orders_topic, $segments_topic]}
  # The source's group is colvenna.<namespace>.<name>.<uid>.
  - {name: ingest, read: [$orders_topic, $segments_topic], groups: [colvenna.]}
  # Sends records again and records that are not changes, as a faulty producer would.
  - {name: tamper, read: [$orders_topic], write: [$orders_topic]}
YAML
bash "$REPO_DIR/tests/fixtures/kafka/dev-kafka.sh" install "$evidence/kafka" -f "$evidence/kafka-values.yaml"
for r in "kafkatopic/$orders_topic" "kafkatopic/$segments_topic" kafkauser/debezium \
  kafkauser/ingest kafkauser/tamper; do
  kubectl -n "$DEV_KAFKA_NAMESPACE" wait "$r" --for=condition=Ready --timeout=5m >/dev/null
done
for ns in "$JOB_NAMESPACE" "$OPERATOR_NAMESPACE"; do
  kubectl label namespace "$ns" --overwrite "$client_label=true" >/dev/null
done
kubectl -n "$DEV_KAFKA_NAMESPACE" get secret "$DEV_KAFKA_RELEASE-cluster-ca-cert" \
  -o jsonpath='{.data.ca\.crt}' | base64 -d >"$work/kafka-ca.pem"
kubectl -n "$JOB_NAMESPACE" create configmap "$source_name-kafka-ca" --from-file=ca.pem="$work/kafka-ca.pem" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$DEV_KAFKA_NAMESPACE" get secret ingest -o jsonpath='{.data.password}' \
  | base64 -d >"$work/password"
kubectl -n "$JOB_NAMESPACE" create secret generic "$source_name-kafka" --from-literal=username=ingest \
  --from-file=password="$work/password" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
rm -f "$work/password"

# ------------------------------------------------------------------------- the source
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
bash "$FIXTURES_DIR/sources/sources.sh" users >/dev/null
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
step "writer start; apply $source_name (binlog)"
bash "$cdc/writer.sh" start mysql "$db" "tk-$run"
kubectl apply -f "$evidence/source.yaml"
await lakesource "$source_name" direct-ready 900 \
  "state('orders') == 'Ready' and state('segments') == 'Ready' and live_all()" || true

dwd_query="SELECT o.id AS order_id, o.customer_id, s.label AS segment, o.amount, o.state, o.version_no
FROM (SELECT *, PROCTIME() AS pt FROM $ods.orders) AS o
JOIN $ods.segments FOR SYSTEM_TIME AS OF o.pt AS s ON o.customer_id = s.id"
cat >"$evidence/tables/$dwd_name.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTable
metadata: {name: $dwd_name, namespace: $JOB_NAMESPACE}
spec:
  target: {database: $dwd, table: orders}
  primaryKey: [order_id]
  upstream: ["$ods.orders", "$ods.segments"]
  query: |
    ${dwd_query//$'\n'/$'\n'    }
  deletionPolicy: Delete
  # Kept for a day so the full changelog can be replayed at the end.
  tableOptions: {snapshot.time-retained: 1 d}
  job: $small
YAML
step "apply $dwd_name, reading ODS"
kubectl apply -f "$evidence/tables/$dwd_name.yaml"
await laketable "$dwd_name" dwd-running 900 "phase == 'Running' and live_all()" || true

# ------------------------------------------------------------------------ the collector
# Debezium Server as the user runs it: its offsets and schema history in the pod (lost with it),
# the database password and Kafka login from Secrets through the environment, never the config.
step "Debezium Server $debezium: snapshot, then the binlog, into $prefix.*"
kubectl -n "$DEV_KAFKA_NAMESPACE" get secret debezium -o jsonpath='{.data.sasl\.jaas\.config}' \
  | base64 -d >"$work/jaas"
kubectl -n "$JOB_NAMESPACE" create secret generic "$debezium-kafka" --from-file=jaas="$work/jaas" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
rm -f "$work/jaas"
cat >"$work/application.properties" <<PROPS
debezium.sink.type=kafka
debezium.sink.kafka.producer.bootstrap.servers=$bootstrap
debezium.sink.kafka.producer.security.protocol=SASL_SSL
debezium.sink.kafka.producer.sasl.mechanism=SCRAM-SHA-512
debezium.sink.kafka.producer.sasl.jaas.config=\${KAFKA_JAAS}
debezium.sink.kafka.producer.ssl.truststore.type=PEM
debezium.sink.kafka.producer.ssl.truststore.location=/kafka-ca/ca.pem
debezium.sink.kafka.producer.key.serializer=org.apache.kafka.common.serialization.StringSerializer
debezium.sink.kafka.producer.value.serializer=org.apache.kafka.common.serialization.StringSerializer
debezium.source.connector.class=io.debezium.connector.mysql.MySqlConnector
debezium.source.offset.storage.file.filename=/debezium/data/offsets.dat
debezium.source.offset.flush.interval.ms=5000
debezium.source.database.hostname=$(stack_output "$TEST_MYSQL_STACK" MySqlEndpoint)
debezium.source.database.port=3306
debezium.source.database.user=colvenna_cdc
debezium.source.database.password=\${MYSQL_PASSWORD}
debezium.source.database.server.id=7100
debezium.source.database.ssl.mode=verify_identity
debezium.source.database.ssl.truststore=/debezium/data/truststore.p12
debezium.source.database.ssl.truststore.password=changeit
debezium.source.database.connectionTimeZone=UTC
debezium.source.topic.prefix=$prefix
debezium.source.database.include.list=$db
debezium.source.table.include.list=$db.orders,$db.segments
debezium.source.include.schema.changes=false
debezium.source.schema.history.internal=io.debezium.storage.file.history.FileSchemaHistory
debezium.source.schema.history.internal.file.filename=/debezium/data/schema-history.dat
debezium.source.schema.history.internal.store.only.captured.tables.ddl=true
debezium.source.snapshot.mode=initial
debezium.source.snapshot.locking.mode=none
debezium.source.column.propagate.source.type=.*
debezium.format.key=json
debezium.format.value=json
debezium.format.key.schemas.enable=true
debezium.format.value.schemas.enable=true
quarkus.log.console.json=false
PROPS
kubectl -n "$JOB_NAMESPACE" create configmap "$debezium" --from-file=application.properties="$work/application.properties" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
cp "$work/application.properties" "$evidence/debezium.properties"
cat >"$evidence/debezium.yaml" <<YAML
apiVersion: apps/v1
kind: Deployment
metadata: {name: $debezium, namespace: $JOB_NAMESPACE}
spec:
  replicas: 1
  strategy: {type: Recreate}
  selector: {matchLabels: {app: $debezium}}
  template:
    metadata: {labels: {app: $debezium}}
    spec:
      automountServiceAccountToken: false
      securityContext: {runAsNonRoot: true, runAsUser: 185, fsGroup: 185, seccompProfile: {type: RuntimeDefault}}
      initContainers:
        - name: truststore
          image: $DEBEZIUM_SERVER_IMAGE
          securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
          command: ["bash", "-ec"]
          args:
            - |
              # The RDS bundle holds many certificates; keytool imports one per file.
              awk '/BEGIN CERT/{n++} {print > ("/tmp/rds-" n ".pem")}' /rds/ca.pem
              for f in /tmp/rds-*.pem; do
                keytool -importcert -noprompt -alias "\$(basename "\$f" .pem)" -file "\$f" \
                  -keystore /debezium/data/truststore.p12 -storetype PKCS12 -storepass changeit >/dev/null
              done
          volumeMounts:
            - {name: data, mountPath: /debezium/data}
            - {name: rds, mountPath: /rds, readOnly: true}
      containers:
        - name: debezium
          image: $DEBEZIUM_SERVER_IMAGE
          securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
          env:
            - name: MYSQL_PASSWORD
              valueFrom: {secretKeyRef: {name: mysql-cdc, key: password}}
            - name: KAFKA_JAAS
              valueFrom: {secretKeyRef: {name: $debezium-kafka, key: jaas}}
          resources: {requests: {cpu: 250m, memory: 1Gi}, limits: {memory: 1Gi}}
          volumeMounts:
            # Only the file: the image keeps more in /debezium/config.
            - {name: config, mountPath: /debezium/config/application.properties, subPath: application.properties, readOnly: true}
            - {name: data, mountPath: /debezium/data}
            - {name: kafka-ca, mountPath: /kafka-ca, readOnly: true}
      volumes:
        - {name: config, configMap: {name: $debezium}}
        - {name: data, emptyDir: {}}
        - {name: rds, configMap: {name: rds-ca}}
        - {name: kafka-ca, configMap: {name: $source_name-kafka-ca}}
YAML
kubectl apply -f "$evidence/debezium.yaml"
kubectl -n "$JOB_NAMESPACE" rollout status "deployment/$debezium" --timeout=10m
# The snapshot is in once the orders topic holds at least the 5000 fixture rows.
for _ in $(seq 1 60); do
  total=$(( $(end_offset "$orders_topic" 0) + $(end_offset "$orders_topic" 1) ))
  ((total >= 5000)) && break
  sleep 15
done
# The log whole: grep -q would stop reading and fail kubectl (and the pipeline) with SIGPIPE.
debezium_log="$(kubectl -n "$JOB_NAMESPACE" logs "deployment/$debezium" 2>/dev/null || true)"
if ((total >= 5000)) && grep -qi "streaming" <<<"$debezium_log"; then
  verdict debezium-streaming PASS "$total records on $orders_topic"
else
  verdict debezium-streaming FAIL "$total records on $orders_topic"
fi
sleep 60

# ------------------------------------------------------------------------- the switch
step "switch $source_name to Kafka while the writer runs"
kubectl -n "$JOB_NAMESPACE" patch lakesource "$source_name" --type merge -p "$(cat <<JSON
{"spec": {"transport": {"kafka": {"bootstrapServers": "$bootstrap", "topicPrefix": "$prefix",
  "credentialsSecret": {"name": "$source_name-kafka"}, "caConfigMap": "$source_name-kafka-ca"}}}}
JSON
)"
await lakesource "$source_name" kafka-running 900 \
  "live_all() and phase == 'Running' and condition('TransportReady') == 'True' \
  and jobs == ['$source_name-kafka'] \
  and state('orders') == 'Ready' and state('segments') == 'Ready'" || true
events_of "$source_name" >"$evidence/dumps/switch-events.txt"
if python3 - "$evidence/dumps/switch-events.txt" "$source_name" <<'PY'
import sys
lines = open(sys.argv[1]).read().splitlines()
name = sys.argv[2]
def first(reason, text):
    return next((i for i, l in enumerate(lines) if l.split(" ")[1] == reason and text in l), None)
deleted = first("JobDeleted", name + "-cdc")
created = first("JobCreated", name + "-kafka")
switched = first("ChangelogSwitched", "")
sys.exit(0 if None not in (deleted, switched, created) and deleted < created < switched else 1)
PY
then
  verdict switch-ordered PASS "direct job deleted, then the Kafka job created; it switched the changelogs"
else
  verdict switch-ordered FAIL "$(tr '\n' ';' <"$evidence/dumps/switch-events.txt")"
fi
jm="$(kubectl -n "$JOB_NAMESPACE" get pod -l "app=$source_name-kafka,component=jobmanager" -o name | sed -n 1p)"
kubectl -n "$JOB_NAMESPACE" logs "$jm" | grep "KAFKA_CDC_START\|TARGET_SWITCHED" >"$evidence/dumps/switch-start.txt" || true
if grep -q "lastWrite=[0-9]" "$evidence/dumps/switch-start.txt" \
  && [[ "$(grep -c TARGET_SWITCHED "$evidence/dumps/switch-start.txt")" == 2 ]]; then
  verdict switch-starts-before-last-write PASS "$(head -c 400 "$evidence/dumps/switch-start.txt")"
else
  verdict switch-starts-before-last-write FAIL "no KAFKA_CDC_START with a last write, or not both targets compacted and switched"
fi
sleep 120

# ------------------------------------------------------------------- Kafka's failure modes
await lakesource "$source_name" kafka-caught-up 600 "lag < 200 and live_all()" || true
step "records the job already read, sent again"
stale_before="$(status_field 's.get("transport",{}).get("stale") or 0')"
end0="$(end_offset "$orders_topic" 0)"
client "replay-$RANDOM" tamper \
  "$bin/kafka-console-consumer.sh --bootstrap-server $bootstrap --command-config /tmp/client.properties --topic $orders_topic --partition 0 --offset $((end0 > 200 ? end0 - 200 : 0)) --max-messages 200 --timeout-ms 60000 --formatter-property print.key=true --formatter-property key.separator=\$'\\t' >/tmp/all.txt || true
# Only key<TAB>value lines (the tools may print warnings), and no tombstones: they print as null
# and are no changes to send again.
grep \$'\\t' /tmp/all.txt | grep -v \$'\\tnull\$' >/tmp/replay.txt || true
echo replayed \$(wc -l </tmp/replay.txt) deletes \$(grep -c '\"op\":\"d\"' /tmp/replay.txt || true)
$bin/kafka-console-producer.sh --bootstrap-server $bootstrap --command-config /tmp/client.properties --topic $orders_topic --reader-property parse.key=true --reader-property key.separator=\$'\\t' </tmp/replay.txt" \
  | tee "$evidence/dumps/replay.txt" || true
line="$(grep '^replayed' "$evidence/dumps/replay.txt" || true)"
read -r _ replayed _ deletes <<<"${line:-replayed 0 deletes 0}"
expected=$((replayed - deletes))
sleep 90
stale_after="$(status_field 's.get("transport",{}).get("stale") or 0')"
if ((replayed > 0 && stale_after - stale_before == expected)); then
  verdict replay-dropped-as-stale PASS "$replayed sent again ($deletes deletes absorbed), stale +$expected"
else
  verdict replay-dropped-as-stale FAIL "$replayed sent again, $deletes deletes, stale $stale_before -> $stale_after"
fi

step "records that are not Debezium changes"
unparsable_before="$(status_field 's.get("transport",{}).get("unparsable") or 0')"
client "garbage-$RANDOM" tamper \
  "printf '%s\\t%s\\n' garbage 'not json' '{\"id\":999999}' '{\"op\":\"c\",\"after\":{\"id\":999999,\"version_no\":1}}' '{\"id\":999998}' '{\"schema\":{},\"payload\":{\"op\":\"t\",\"source\":{\"table\":\"orders\"}}}' >/tmp/garbage.txt
$bin/kafka-console-producer.sh --bootstrap-server $bootstrap --command-config /tmp/client.properties --topic $orders_topic --reader-property parse.key=true --reader-property key.separator=\$'\\t' </tmp/garbage.txt
echo sent \$(wc -l </tmp/garbage.txt)" | tee "$evidence/dumps/garbage.txt" || true
await lakesource "$source_name" unparsable-counted 300 \
  "transport.get('unparsable', 0) == $unparsable_before + 3 and live_all()" || true
if warning_event "$source_name" UnparsableEvents; then
  verdict unparsable-alerted PASS "Warning UnparsableEvents"
else
  verdict unparsable-alerted FAIL "no warning"
fi

# The collector stops, rows are deleted while it is down, and it starts again without its
# offsets (its data volume is an emptyDir): it snapshots again, and those deletes are in no record.
step "the collector loses its position: stopped, 5 rows deleted meanwhile, started from scratch"
# The writer pauses across the collector's new snapshot. Debezium snapshots with
# snapshot.locking.mode=none (RDS grants no global read lock): a row committed between its snapshot's
# view and the binlog position it then streams from reaches no record, and no source event will
# ever bring it (uate2, rc.1: one insert lost for good). A collector whose own snapshot loses
# inserts is the collector's defect, which the lake cannot repair: the reconciliation finds the row
# missing and rechecks for ever (the missing warning is a product follow-up). What this step tests
# are the deletes made while the collector is down, which no snapshot can carry.
step "writer paused: $(bash "$cdc/writer.sh" stop "tk-$run")"
kubectl -n "$JOB_NAMESPACE" scale "deployment/$debezium" --replicas=0
kubectl -n "$JOB_NAMESPACE" wait --for=delete pod -l "app=$debezium" --timeout=5m || true
cat >"$evidence/sql/deleted-while-down.sql" <<SQL
DELETE FROM $db.orders WHERE id IN (SELECT id FROM (SELECT id FROM $db.orders ORDER BY id LIMIT 5 OFFSET 2000) AS gone);
SQL
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/deleted-while-down.sql" >/dev/null
kubectl -n "$JOB_NAMESPACE" scale "deployment/$debezium" --replicas=1
kubectl -n "$JOB_NAMESPACE" rollout status "deployment/$debezium" --timeout=10m
# Its new snapshot done once it streams the binlog; then the writer writes again (and the tables'
# rechecks see new commits).
snapshot_done=false
for _ in $(seq 1 60); do
  debezium_log="$(kubectl -n "$JOB_NAMESPACE" logs "deployment/$debezium" 2>/dev/null || true)"
  if grep -qiE 'Starting streaming|Snapshot ended with SnapshotResult \[status=COMPLETED' <<<"$debezium_log"; then
    snapshot_done=true
    break
  fi
  sleep 10
done
$snapshot_done || echo "$(date -u +%FT%TZ) the collector's new snapshot was not seen to end within 10 minutes" >&2
bash "$cdc/writer.sh" start mysql "$db" "tk-$run"
step "writer resumed after the collector's new snapshot"
sleep 120
await lakesource "$source_name" resnapshot-absorbed 900 "live_all() and phase == 'Running' and lag < 500" || true
if grep -q " SnapshotAgain " <<<"$(events_of "$source_name")"; then
  verdict snapshot-again-noticed PASS "$(events_of "$source_name" | grep " SnapshotAgain " | tail -1 | cut -c1-300)"
else
  verdict snapshot-again-noticed FAIL "no SnapshotAgain event; transport $(status_field 's.get("transport")')"
fi
# The operator reconciles the tables with the source, finds the deletes that never came, and
# writes them while the job is suspended.
end=$((SECONDS + 1200))
until grep -q " DeletesRepaired orders" <<<"$(events_of "$source_name")"; do
  ((SECONDS < end)) || break
  sleep 15
done
lost="$(events_of "$source_name" | grep " DeletesLost orders" | tail -1 | sed -n 's/.*orders: \([0-9]*\) rows.*/\1/p')"
if [[ "${lost:-0}" -ge 5 ]] && grep -q " DeletesRepaired orders" <<<"$(events_of "$source_name")"; then
  verdict deletes-lost-repaired PASS "$(events_of "$source_name" | grep -E " DeletesLost orders| DeletesRepaired orders" | tail -2 | tr '\n' ';' | cut -c1-500)"
else
  verdict deletes-lost-repaired FAIL "lost=${lost:-none}; $(events_of "$source_name" | grep -E "DeletesLost|DeletesRepaired|TableReconciled" | tr '\n' ';' | cut -c1-500)"
fi
await lakesource "$source_name" resnapshot-reconciled 1200 \
  "not rechecking('orders') and not rechecking('segments') and live_all() and phase == 'Running'" || true

step "delete the Kafka job behind the operator's back"
kubectl -n "$JOB_NAMESPACE" delete flinkdeployment "$source_name-kafka" --wait=true
await lakesource "$source_name" kafka-recreated 900 "live_all() and phase == 'Running'" || true
if grep -q "JobRecreated $source_name-kafka reads from an overlap" <<<"$(events_of "$source_name")"; then
  verdict kafka-recreated-from-tables PASS "JobRecreated"
else
  verdict kafka-recreated-from-tables FAIL "no JobRecreated"
fi
sleep 120

# --------------------------------------------------------------------------- the end
step "writer stop: $(bash "$cdc/writer.sh" stop "tk-$run")"
await lakesource "$source_name" kafka-drained 900 "lag == 0 and live_all()" || true
events_of "$source_name" | grep -E "SnapshotAgain|DeletesLost|DeletesRepaired|TableReconciled" \
  >"$evidence/dumps/reconciled-events.txt" || true
sleep 90
compare_table ods-orders orders "$ods.orders" id version_no
compare_table ods-segments segments "$ods.segments" id version_no
cat >"$evidence/sql/dwd-expected.sql" <<SQL
SELECT o.id AS order_id, o.customer_id, s.label AS segment, o.amount, o.state, o.version_no
FROM $db.orders o JOIN $db.segments s ON o.customer_id = s.id;
SQL
compare_table dwd "$evidence/sql/dwd-expected.sql" "$dwd.orders" order_id version_no
status_field 's.get("transport")' >"$evidence/dumps/transport-counts.txt"

finished=true
step "delete $dwd_name and $source_name (Delete)"
for name in "$dwd_name" "$source_name"; do
  kubectl -n "$JOB_NAMESPACE" get secret "$name-catalog-token" -o jsonpath='{.data.token}' | base64 -d >"$work/$name.token"
done
kubectl -n "$JOB_NAMESPACE" delete laketable "$dwd_name" --timeout=10m
kubectl -n "$JOB_NAMESPACE" delete lakesource "$source_name" --timeout=10m
admin_open
ods_table="$(table_http "$ods" orders)" dwd_table="$(table_http "$dwd" orders)"
source_token="$(token_status "$work/$source_name.token")" dwd_token="$(token_status "$work/$dwd_name.token")"
admin_close
if [[ "$ods_table" == 404 && "$dwd_table" == 404 && "$source_token" == 401 && "$dwd_token" == 401 ]] \
  && [[ -z "$(kubectl -n "$JOB_NAMESPACE" get flinkdeployment -o name | grep -- "-$run-" || true)" ]]; then
  verdict kafka-cdc-deleted PASS "jobs gone, tables dropped (404), tokens refused (401)"
else
  verdict kafka-cdc-deleted FAIL "ods=$ods_table dwd=$dwd_table tokens=$source_token/$dwd_token"
fi
kubectl -n "$JOB_NAMESPACE" delete deployment "$debezium" --ignore-not-found --wait=true >/dev/null
kubectl -n "$JOB_NAMESPACE" delete configmap "$debezium" "$source_name-kafka-ca" --ignore-not-found >/dev/null
kubectl -n "$JOB_NAMESPACE" delete secret "$debezium-kafka" "$source_name-kafka" --ignore-not-found >/dev/null
python3 "$cdc/mysql_contract.py" reset "$db" >"$evidence/sql/reset.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/reset.sql" >/dev/null
if [[ "${KEEP_KAFKA:-}" != 1 ]]; then
  step "remove the development Kafka and its data"
  bash "$REPO_DIR/tests/fixtures/kafka/dev-kafka.sh" purge "$evidence/kafka" --yes
fi

kubectl -n "$JOB_NAMESPACE" get events -o json >"$evidence/events.json"
echo "verdicts:"
cat "$evidence/verdicts.tsv"
exit "$failed"
