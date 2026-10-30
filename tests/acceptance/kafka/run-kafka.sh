#!/usr/bin/env bash
# Acceptance on EKS: a declared Kafka event source on the development Kafka
# (tests/fixtures/kafka/colvenna-dev-kafka, standing in for the user's cluster), from declaration to
# deletion. Writes PASS/FAIL lines to <evidence>/verdicts.tsv and fails if any check fails.
#
# - The source reaches the cluster with its own SCRAM user over verified TLS; status shows the
#   topic, its partitions and the group's lag. Two more declarations, suspended, show what the
#   probe refuses: a user that may describe the topic but not read it (NotAuthorized), and a
#   topic that does not exist (TopicMissing).
# - Each batch has valid events (some with an undeclared field) and one record per rejection
#   reason; one event behind the watermark is rejected as LATE.
# - The user's password changes: the source says AuthenticationFailed, and recovers once its
#   Secret has the new one. The cluster becomes unreachable (the namespaces lose the client
#   label): Unreachable, then back.
# - Upgrade (TaskManager memory); a second topic declared (the job starts again from what its
#   tables hold, not from restored offsets); a job deleted behind the operator's back with its
#   group's offsets reset to the beginning (recreated, continuing after the offsets its tables
#   hold, not the group's); a new optional field; suspend and resume.
# - At the end, the events table holds exactly the valid events sent, field by field, the
#   rejections table the expected reasons and no payload, and every offset up to each
#   partition's end is in exactly one of the two tables; the status counts agree.
# - Deletion drops both tables and revokes the identity.
#
# usage: run-kafka.sh <run-id> <evidence-dir>; KEEP_KAFKA=1 leaves the development Kafka installed.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-kafka.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-kafka.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
flows="$REPO_DIR/tests/acceptance"
kafka="$flows/kafka"
mkdir -p "$evidence"/{status,dumps,batches}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
for image in operator kafka; do
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

name="ev-$run" topic="ev-$run" topic_b="ev-$run-b" db="ods_kafka_$run"
bootstrap="$DEV_KAFKA_RELEASE-kafka-bootstrap.$DEV_KAFKA_NAMESPACE.svc:9093"
client_label=colvenna-lake.io/dev-kafka-client
# The shared nodes: half a CPU per JobManager and TaskManager, bursting to one.
small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
work="$(mktemp -d)"
chmod 700 "$work"
trap 'admin_close; rm -rf "$work"' EXIT

# capture <file>: the source and its deployments, for status.py.
capture() {
  {
    printf '{"source":'
    kubectl -n "$JOB_NAMESPACE" get lakesource "$name" -o json
    printf ',"deployments":'
    kubectl -n "$JOB_NAMESPACE" get flinkdeployment -l "colvenna-lake.io/source=$name" -o json \
      | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["items"]))'
    printf '}'
  } >"$1"
}

# await <label> <timeout-seconds> <expression>
await() {
  local label="$1" end=$((SECONDS + $2)) started=$SECONDS file="$evidence/status/$1.json"
  while ((SECONDS < end)); do
    if capture "$file" 2>/dev/null && python3 "$flows/status.py" check "$file" "$3"; then
      verdict "$label" PASS "$((SECONDS - started))s"
      return 0
    fi
    sleep 10
  done
  # Before the run cleans up: what the readiness depended on (#245).
  source_diagnostics "$name" "$evidence/diagnostics/$label"
  verdict "$label" FAIL "not within $2s: $3"
  python3 -c 'import json,sys; s=json.load(open(sys.argv[1]))["source"].get("status",{}); print(s.get("phase"), s.get("message"), s.get("kafka"), s.get("events"))' "$file" || true
  return 1
}

warning_event() {
  grep -qx "$1" <<<"$(kubectl -n "$JOB_NAMESPACE" get events --field-selector "involvedObject.name=$name,type=Warning" \
    -o jsonpath='{range .items[*]}{.reason}{"\n"}{end}')"
}

normal_event() {
  grep -qx "$1" <<<"$(kubectl -n "$JOB_NAMESPACE" get events --field-selector "involvedObject.name=$name" \
    -o jsonpath='{range .items[*]}{.reason}{"\n"}{end}')"
}

token_status() {
  printf 'header = "Authorization: Bearer %s"\n' "$(cat "$1")" >"$ADMIN_DIR/probe.curlrc"
  curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/probe.curlrc" \
    "https://localhost:$ADMIN_PORT/v1/config?warehouse=colvenna"
}

# client <name> <user> <script> [<file>]: a Kafka client in the job namespace with the user's
# credential and the cluster CA; <file> is mounted at /data/batch.txt.
client() {
  local pod="$1" user="$2" script="$3" file="${4:-}"
  kubectl -n "$DEV_KAFKA_NAMESPACE" get secret "$user" -o jsonpath='{.data.sasl\.jaas\.config}' \
    | base64 -d >"$work/jaas"
  kubectl -n "$JOB_NAMESPACE" create secret generic "$pod" --from-file=sasl.jaas.config="$work/jaas" \
    --from-file=ca.crt="$work/kafka-ca.pem" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  rm -f "$work/jaas"
  local status=0
  if [[ -n "$file" ]]; then
    kubectl -n "$JOB_NAMESPACE" create configmap "$pod" --from-file=batch.txt="$file" \
      --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  else
    kubectl -n "$JOB_NAMESPACE" create configmap "$pod" --from-literal=batch.txt= \
      --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  fi
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
$(printf '%s\n' "$script" | sed 's/^/          /')
      resources: {requests: {cpu: 100m, memory: 512Mi}, limits: {memory: 512Mi}}
      volumeMounts:
        - {name: client, mountPath: /client, readOnly: true}
        - {name: data, mountPath: /data, readOnly: true}
  volumes:
    - {name: client, secret: {secretName: $pod}}
    - {name: data, configMap: {name: $pod}}
YAML
  # The Secret holds the user's login: it goes whether the pod succeeded or not.
  run_pod "$JOB_NAMESPACE" "$pod" "$work/$pod.yaml" || status=1
  kubectl -n "$JOB_NAMESPACE" delete secret,configmap "$pod" --ignore-not-found >/dev/null
  return "$status"
}

# produce <batch-file> [<topic>]: sends key|value lines to the topic.
produce() {
  client "produce-$RANDOM" producer \
    "/opt/kafka/bin/kafka-console-producer.sh --bootstrap-server $bootstrap --producer.config /tmp/client.properties --topic ${2:-$topic} --property parse.key=true --property key.separator='|' </data/batch.txt
echo produced \$(wc -l </data/batch.txt)" "$1"
}

# batch <name> <count> [<topic>]: generates and sends a batch; its expectations go beside it.
batch() {
  python3 "$kafka/events.py" batch "$1" "$2" --expected "$evidence/batches/$1.expected.json" \
    >"$evidence/batches/$1.txt"
  produce "$evidence/batches/$1.txt" "${3:-$topic}"
}

# caught_up <label>: the group has consumed everything sent.
caught_up() {
  await "$1" 600 "lag == 0 and live_all() and phase == 'Running'"
}

# ----------------------------------------------------------------------- the user's Kafka
step "development Kafka with topic $topic and the users producer, ingest"
cat >"$evidence/kafka-values.yaml" <<YAML
topics:
  - {name: dev-events, partitions: 3, retentionMs: 86400000}
  - {name: $topic, partitions: 2, retentionMs: 86400000}
  - {name: $topic_b, partitions: 1, retentionMs: 86400000}
users:
  - {name: dev-producer, write: [dev-events]}
  - {name: dev-consumer, read: [dev-events], groups: [dev-]}
  - {name: producer, write: [$topic, $topic_b]}
  # The source's group is colvenna.<namespace>.<name>.<uid>.
  # The prefix lets the probe tell a missing topic from a forbidden one.
  - {name: ingest, read: [$topic, $topic_b], readPrefixes: [ev-$run], groups: [colvenna.]}
  # May describe the topic (Write brings Describe) and use the group, not read the topic.
  - {name: peek, write: [$topic], groups: [colvenna.]}
YAML
bash "$REPO_DIR/tests/fixtures/kafka/dev-kafka.sh" install "$evidence/kafka" -f "$evidence/kafka-values.yaml"
for r in "kafkatopic/$topic" "kafkatopic/$topic_b" kafkauser/producer kafkauser/ingest \
  kafkauser/peek; do
  kubectl -n "$DEV_KAFKA_NAMESPACE" wait "$r" --for=condition=Ready --timeout=5m >/dev/null
done
for ns in "$JOB_NAMESPACE" "$OPERATOR_NAMESPACE"; do
  kubectl label namespace "$ns" --overwrite "$client_label=true" >/dev/null
done
kubectl -n "$DEV_KAFKA_NAMESPACE" get secret "$DEV_KAFKA_RELEASE-cluster-ca-cert" \
  -o jsonpath='{.data.ca\.crt}' | base64 -d >"$work/kafka-ca.pem"
kubectl -n "$JOB_NAMESPACE" create configmap "$name-kafka-ca" --from-file=ca.pem="$work/kafka-ca.pem" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
# credentials [<user> <secret>]: the source's Secret takes the user's current password (the
# ingest user's, into $name-kafka, by default).
credentials() {
  local user="${1:-ingest}" secret="${2:-$name-kafka}"
  kubectl -n "$DEV_KAFKA_NAMESPACE" get secret "$user" -o jsonpath='{.data.password}' \
    | base64 -d >"$work/password"
  kubectl -n "$JOB_NAMESPACE" create secret generic "$secret" --from-literal=username="$user" \
    --from-file=password="$work/password" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  rm -f "$work/password"
}
credentials

# --------------------------------------------------------------------------- the source
cat >"$evidence/source.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: $name, namespace: $JOB_NAMESPACE}
spec:
  type: kafka
  kafka:
    bootstrapServers: $bootstrap
    topics: [$topic]
    credentialsSecret: {name: $name-kafka}
    caConfigMap: $name-kafka-ca
  events:
    fields:
      - {name: event_id, type: STRING, required: true, maxLength: 64}
      - {name: event_type, type: STRING, required: true, values: [click, view, purchase]}
      - {name: amount, type: "DECIMAL(12,2)", min: 0, max: 100000}
      - {name: user_id, type: BIGINT}
    eventTime: {field: event_time}
    maxOutOfOrderness: 1m
    idleness: 30s
  target: {database: $db, table: events}
  job: $small
  deletionPolicy: Delete
YAML
step "apply $name"
kubectl apply -f "$evidence/source.yaml"
await kafka-running 900 "phase == 'Running' and live_all() and condition('SourceReady') == 'True' \
  and condition('SourceVerified') == 'True' and condition('TargetReady') == 'True'" || true

# refused <name> <topic> <secret> <reason> <label>: a suspended declaration the probe refuses.
refused() {
  cat >"$evidence/$1.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: $1, namespace: $JOB_NAMESPACE}
spec:
  type: kafka
  suspend: true
  kafka:
    bootstrapServers: $bootstrap
    topics: [$2]
    credentialsSecret: {name: $3}
    caConfigMap: $name-kafka-ca
  events:
    fields: [{name: event_id, type: STRING, required: true}]
    eventTime: {field: event_time}
  target: {database: ${db}_$5, table: events}
  job: $small
  deletionPolicy: Delete
YAML
  kubectl apply -f "$evidence/$1.yaml" >/dev/null
  local saved="$name"
  name="$1"
  if await "kafka-refused-$5" 600 "condition('SourceReady') == 'False' and kafka_reason == '$4'" \
    && warning_event "Source$4"; then
    verdict "kafka-refused-$5-alerted" PASS "Warning Source$4"
  else
    verdict "kafka-refused-$5-alerted" FAIL "no warning"
  fi
  name="$saved"
  kubectl -n "$JOB_NAMESPACE" delete lakesource "$1" --timeout=10m >/dev/null
}
step "refused: a user that may describe the topic but not read it, and a topic that does not exist"
credentials peek "$name-peek"
refused "ev-$run-peek" "$topic" "$name-peek" NotAuthorized peek
refused "ev-$run-gone" "ev-$run-missing" "$name-kafka" TopicMissing gone
kubectl -n "$JOB_NAMESPACE" delete secret "$name-peek" --ignore-not-found >/dev/null

step "batch b1, then one event behind the watermark"
batch b1 300
sleep 60
python3 "$kafka/events.py" late l1 --expected "$evidence/batches/l1.expected.json" \
  >"$evidence/batches/l1.txt"
produce "$evidence/batches/l1.txt"
caught_up kafka-b1-consumed || true
await kafka-b1-rejections 300 "rejected_by('LATE') == 1 and rejected_by('NOT_JSON_OBJECT') == 1 \
  and rejected_by('TOO_OLD') == 1 and accepted == 300" || true

# ---------------------------------------------------------------------- broken access
step "the ingest user's password changes; the source's Secret still has the old one"
kubectl -n "$DEV_KAFKA_NAMESPACE" delete secret ingest >/dev/null
kubectl -n "$DEV_KAFKA_NAMESPACE" wait secret/ingest --for=create --timeout=2m >/dev/null 2>&1 \
  || sleep 30
if await kafka-credentials-refused 600 \
  "condition('SourceReady') == 'False' and kafka_reason == 'AuthenticationFailed'" \
  && warning_event SourceAuthenticationFailed; then
  verdict kafka-credentials-alerted PASS "Warning SourceAuthenticationFailed"
else
  verdict kafka-credentials-alerted FAIL "no warning"
fi
credentials
await kafka-credentials-renewed 900 "condition('SourceReady') == 'True' and live_all()" || true
batch b2 100
caught_up kafka-b2-consumed || true

step "the cluster becomes unreachable: the namespaces lose the client label"
for ns in "$JOB_NAMESPACE" "$OPERATOR_NAMESPACE"; do
  kubectl label namespace "$ns" "$client_label-" >/dev/null
done
if await kafka-unreachable 600 "condition('SourceReady') == 'False' and kafka_reason == 'Unreachable'" \
  && warning_event SourceUnreachable; then
  verdict kafka-unreachable-alerted PASS "Warning SourceUnreachable"
else
  verdict kafka-unreachable-alerted FAIL "no warning"
fi
for ns in "$JOB_NAMESPACE" "$OPERATOR_NAMESPACE"; do
  kubectl label namespace "$ns" --overwrite "$client_label=true" >/dev/null
done
await kafka-reachable-again 900 "condition('SourceReady') == 'True' and live_all()" || true
batch b3 100
caught_up kafka-b3-consumed || true

# ------------------------------------------------------------------------- the job
step "upgrade: TaskManager memory"
kubectl -n "$JOB_NAMESPACE" patch lakesource "$name" --type merge \
  -p '{"spec":{"job":{"taskManager":{"memory":"3072m"}}}}'
sleep 20
await kafka-upgraded 900 "live_all() and condition('SpecApplied') == 'True' and 'chk-' in str(restored('$name-events'))" || true
batch b4 100
caught_up kafka-b4-consumed || true

step "a second topic: the job starts again from what its tables hold"
kubectl -n "$JOB_NAMESPACE" patch lakesource "$name" --type json \
  -p "[{\"op\":\"add\",\"path\":\"/spec/kafka/topics/-\",\"value\":\"$topic_b\"}]"
sleep 20
if await kafka-topic-added 900 "live_all() and phase == 'Running' and kafka_topics == 2" \
  && normal_event JobDeleted; then
  verdict kafka-topics-change-restarts-from-tables PASS "JobDeleted, then a job from the tables"
else
  verdict kafka-topics-change-restarts-from-tables FAIL "no JobDeleted"
fi
batch b4b 100 "$topic_b"
caught_up kafka-b4b-consumed || true

step "delete the job behind the operator's back, and reset its group to the beginning"
group="$(kubectl -n "$JOB_NAMESPACE" get lakesource "$name" -o json \
  | python3 -c 'import json,sys; m=json.load(sys.stdin)["metadata"]; print("colvenna.%s.%s.%s" % (m["namespace"], m["name"], m["uid"][:8]))')"
kubectl -n "$JOB_NAMESPACE" delete flinkdeployment "$name-events" --wait=true
client "reset-$RANDOM" ingest \
  "/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server $bootstrap --command-config /tmp/client.properties --group $group --reset-offsets --to-earliest --all-topics --execute" \
  | tee "$evidence/dumps/group-reset.txt" || true
if await kafka-job-recreated 900 "live_all() and phase == 'Running'" && normal_event JobRecreated; then
  verdict kafka-recreated-from-tables PASS "JobRecreated"
else
  verdict kafka-recreated-from-tables FAIL "no JobRecreated"
fi
jm="$(kubectl -n "$JOB_NAMESPACE" get pod -l "app=$name-events,component=jobmanager" -o name | sed -n 1p)"
kubectl -n "$JOB_NAMESPACE" logs "$jm" | grep KAFKA_EVENTS_START >"$evidence/dumps/recreated-start.txt" || true
batch b5 100
caught_up kafka-b5-consumed || true

step "a new optional field: campaign"
kubectl -n "$JOB_NAMESPACE" patch lakesource "$name" --type json \
  -p '[{"op":"add","path":"/spec/events/fields/-","value":{"name":"campaign","type":"STRING"}}]'
sleep 20
if await kafka-field-added 900 "live_all() and condition('SpecApplied') == 'True'" && normal_event FieldAdded; then
  verdict kafka-field-evolved PASS "FieldAdded"
else
  verdict kafka-field-evolved FAIL "no FieldAdded"
fi
batch b6 100
caught_up kafka-b6-consumed || true

step "suspend, send while suspended, resume"
kubectl -n "$JOB_NAMESPACE" patch lakesource "$name" --type merge -p '{"spec":{"suspend":true}}'
await kafka-suspended 600 "phase == 'Suspended'" || true
batch b7 100
await kafka-lag-while-suspended 300 "lag > 0" || true
kubectl -n "$JOB_NAMESPACE" patch lakesource "$name" --type merge -p '{"spec":{"suspend":false}}'
caught_up kafka-resumed-consumed || true

# --------------------------------------------------------------------------- the end
step "every record once"
client "ends-$RANDOM" ingest \
  "/opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server $bootstrap --command-config /tmp/client.properties --topic '$topic.*' --time latest" \
  | grep "^$topic" >"$evidence/dumps/ends.txt"
bash "$flows/lake.sh" rows "$evidence/dumps/events.jsonl" "$db.events" >/dev/null
bash "$flows/lake.sh" rows "$evidence/dumps/rejections.jsonl" "$db.events_rejections" >/dev/null
if python3 "$kafka/events.py" judge --rows "$evidence/dumps/events.jsonl" \
  --rejections "$evidence/dumps/rejections.jsonl" --ends "$evidence/dumps/ends.txt" \
  --table "$db.events" --rejections-table "$db.events_rejections" \
  "$evidence"/batches/*.expected.json >"$evidence/dumps/judge.json"; then
  verdict kafka-every-record-once PASS "$(cat "$evidence/dumps/judge.json")"
else
  verdict kafka-every-record-once FAIL "$(cat "$evidence/dumps/judge.json")"
fi
want="$(python3 -c 'import json,sys; v=json.load(open(sys.argv[1])); print(v["expectedEvents"], sum(v["expectedRejections"].values()))' "$evidence/dumps/judge.json")"
await kafka-status-counts 300 "accepted == ${want% *} and sum([rejected_by(r) for r in \
  ['NOT_JSON_OBJECT','MISSING_FIELD','WRONG_TYPE','TOO_LONG','NOT_ALLOWED_VALUE','OUT_OF_RANGE', \
  'BAD_EVENT_TIME','TOO_OLD','TOO_NEW','LATE','INVALID_SIZE']]) == ${want#* }" || true

step "delete $name (Delete)"
kubectl -n "$JOB_NAMESPACE" get secret "$name-catalog-token" -o jsonpath='{.data.token}' | base64 -d >"$work/token"
kubectl -n "$JOB_NAMESPACE" delete lakesource "$name" --timeout=10m
admin_open
events_table="$(curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/curlrc" \
  "https://localhost:$ADMIN_PORT/v1/colvenna/databases/$db/tables/events")"
rejections_table="$(curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/curlrc" \
  "https://localhost:$ADMIN_PORT/v1/colvenna/databases/$db/tables/events_rejections")"
token="$(token_status "$work/token")"
admin_close
if [[ "$events_table" == 404 && "$rejections_table" == 404 && "$token" == 401 ]] \
  && [[ -z "$(kubectl -n "$JOB_NAMESPACE" get flinkdeployment -l "colvenna-lake.io/source=$name" -o name)" ]]; then
  verdict kafka-deleted PASS "job gone, both tables dropped (404), token refused (401)"
else
  verdict kafka-deleted FAIL "events=$events_table rejections=$rejections_table token=$token"
fi
kubectl -n "$JOB_NAMESPACE" delete secret "$name-kafka" --ignore-not-found >/dev/null
kubectl -n "$JOB_NAMESPACE" delete configmap "$name-kafka-ca" --ignore-not-found >/dev/null
if [[ "${KEEP_KAFKA:-}" != 1 ]]; then
  step "remove the development Kafka and its data"
  bash "$REPO_DIR/tests/fixtures/kafka/dev-kafka.sh" purge "$evidence/kafka" --yes
fi

kubectl -n "$JOB_NAMESPACE" get events -o json >"$evidence/events.json"
echo "verdicts:"
cat "$evidence/verdicts.tsv"
exit "$failed"
