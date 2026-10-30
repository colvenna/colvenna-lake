#!/usr/bin/env bash
# The release performance run's Kafka path: a declared Kafka event source on the development Kafka
# under a fixed event load, and when each event became readable in its events table. run-release.sh
# runs it beside the dashboards run; alone, it runs MINUTES (120) after a 5-minute warm-up.
# - The development Kafka (tests/fixtures/kafka/colvenna-dev-kafka) with topic pev-<run>: 2 partitions,
#   the broker's clock on every record (message.timestamp.type=LogAppendTime); a producer user and
#   the source's ingest user.
# - LakeSource pev-<run>: the events (event_id, seq, pad, event_time) into ods_pev_<run>.events.
# - The producer (tools/EventProducer, on the Kafka image): KAFKA_RATE events a second (2,000) of
#   EVENT_BYTES (1,024), open-loop, the broker's receipt of every SAMPLE_EVERY-th (100) written:
#   <evidence>/producer.log.
# - The snapshot observer on the events table: the offsets each snapshot brought, per partition
#   (<evidence>/observed.jsonl); the clocks of the nodes of the broker, the source's TaskManager,
#   the producer and the observer once a minute (<evidence>/clocks.tsv).
# - READY_FILE, when set, gets "producing" once the broker has acknowledged the producer's first
#   events, or "failed" if the run ends before (release.sh waits for it before the window starts).
# - It ends when STOP_FILE exists (run-release.sh creates it as the dashboards run's load ends), or
#   after the warm-up and MINUTES; then the producer stops, the source catches up, the observer
#   stops. <evidence>/window.txt: the producer's first and last send (ms). perf.py events judges.
# - Teardown: the source and its tables, the run's pods and identities, the development Kafka and
#   its data (KEEP_KAFKA=1 keeps the Kafka). A teardown that leaves any of it fails the run.
# usage: run-events.sh <run-id> <evidence-dir>
# shellcheck source-path=SCRIPTDIR source=lib.sh
source "$(dirname "$0")/lib.sh"
run="${1:?usage: run-events.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-events.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
rate="${KAFKA_RATE:-2000}" bytes="${EVENT_BYTES:-1024}" sample_every="${SAMPLE_EVERY:-100}"
minutes="${MINUTES:-120}" stop_file="${STOP_FILE:-}" ready_file="${READY_FILE:-}"
for n in "$rate" "$bytes" "$sample_every" "$minutes"; do
  [[ "$n" =~ ^[1-9][0-9]*$ ]] || { echo "KAFKA_RATE, EVENT_BYTES, SAMPLE_EVERY, MINUTES: whole numbers" >&2; exit 1; }
done
# The producer's own bounds, before anything is made.
((bytes >= 128 && bytes <= 2048)) || { echo "EVENT_BYTES: 128 to 2048" >&2; exit 1; }
# The source's job: a TaskManager of one CPU parses and writes the events (EVENTS_JOB replaces it).
job="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 1, memory: 3072m}}"
job="${EVENTS_JOB:-$job}"
sha="$(committed_sha)"
flows="$REPO_DIR/tests/acceptance"
mkdir -p "$evidence"/{status,kafka}
evidence="$(cd "$evidence" && pwd)"
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
for image in operator kafka mysql; do
  echo "$image $(image_ref "$image")" | tee -a "$evidence/images.txt"
done
step() { echo "$(date -u +%FT%TZ)	$*" | tee -a "$evidence/timeline.tsv"; }

name="pev-$run" topic="pev-$run" db="ods_pev_$run"
producer="produce-$run" observer="observe-$run" principal="perf-$run" tools="perf-tools-$run"
bootstrap="$DEV_KAFKA_RELEASE-kafka-bootstrap.$DEV_KAFKA_NAMESPACE.svc:9093"
client_label=colvenna-lake.io/dev-kafka-client
work="$(mktemp -d)"
chmod 700 "$work"
sampler=""
# teardown: everything the run made, whatever happened; the run fails if any of it is left.
teardown() {
  local status=$? left=0
  if [[ -n "$ready_file" && ! -s "$ready_file" ]]; then echo failed >"$ready_file"; fi
  if [[ -n "$sampler" ]]; then kill "$sampler" 2>/dev/null || true; fi
  clock_probes_remove "clock-$run"
  kubectl -n "$JOB_NAMESPACE" delete pod "$producer" "$observer" --ignore-not-found --wait=false >/dev/null || left=1
  kubectl -n "$JOB_NAMESPACE" delete lakesource "$name" --ignore-not-found --timeout=10m || left=1
  kubectl -n "$JOB_NAMESPACE" delete secret "$name-kafka" "$producer" --ignore-not-found >/dev/null || left=1
  kubectl -n "$JOB_NAMESPACE" delete configmap "$name-kafka-ca" "$tools" --ignore-not-found >/dev/null || left=1
  observer_withdraw "$principal" >/dev/null || left=1
  if [[ "${KEEP_KAFKA:-}" != 1 ]]; then
    bash "$REPO_DIR/tests/fixtures/kafka/dev-kafka.sh" purge "$evidence/kafka" --yes || left=1
  fi
  rm -rf "$work"
  if ((left != 0)); then
    step "teardown left some of the run behind"
  fi
  if ((status == 0)); then status=$left; fi
  exit "$status"
}
trap teardown EXIT

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
# await <label> <timeout-seconds> <expression>: the expression held (0) or not in time (1).
await() {
  local label="$1" end=$((SECONDS + $2)) started=$SECONDS file="$evidence/status/$1.json"
  while ((SECONDS < end)); do
    if capture "$file" 2>/dev/null && python3 "$flows/status.py" check "$file" "$3"; then
      step "$label after $((SECONDS - started))s"
      return 0
    fi
    sleep 10
  done
  step "$label: not within $2s: $3"
  return 1
}

# ----------------------------------------------------------------------- the development Kafka
step "development Kafka: topic $topic (2 partitions, the broker's append time), users producer and ingest"
cat >"$evidence/kafka-values.yaml" <<YAML
# The run's events, about $bytes bytes at $rate a second for a few hours, on one broker.
storage: {type: persistent-claim, size: 60Gi}
# dev-events and its users stay: dev-kafka.sh install checks the cluster with them.
topics:
  - {name: dev-events, partitions: 3, retentionMs: 86400000}
  - name: $topic
    partitions: 2
    retentionMs: 86400000
    config: {message.timestamp.type: LogAppendTime}
users:
  - {name: dev-producer, write: [dev-events]}
  - {name: dev-consumer, read: [dev-events], groups: [dev-]}
  - {name: producer, write: [$topic]}
  - {name: ingest, read: [$topic], groups: [colvenna.]}
YAML
bash "$REPO_DIR/tests/fixtures/kafka/dev-kafka.sh" install "$evidence/kafka" -f "$evidence/kafka-values.yaml"
for r in "kafkatopic/$topic" kafkauser/producer kafkauser/ingest; do
  kubectl -n "$DEV_KAFKA_NAMESPACE" wait "$r" --for=condition=Ready --timeout=5m >/dev/null
done
kubectl -n "$DEV_KAFKA_NAMESPACE" get kafkatopic "$topic" -o jsonpath='{.spec.config}' \
  >"$evidence/kafka/topic-config.json"
for ns in "$JOB_NAMESPACE" "$OPERATOR_NAMESPACE"; do
  kubectl label namespace "$ns" --overwrite "$client_label=true" >/dev/null
done
kubectl -n "$DEV_KAFKA_NAMESPACE" get secret "$DEV_KAFKA_RELEASE-cluster-ca-cert" \
  -o jsonpath='{.data.ca\.crt}' | base64 -d >"$work/kafka-ca.pem"
kubectl -n "$JOB_NAMESPACE" create configmap "$name-kafka-ca" --from-file=ca.pem="$work/kafka-ca.pem" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$DEV_KAFKA_NAMESPACE" get secret ingest -o jsonpath='{.data.password}' | base64 -d >"$work/password"
kubectl -n "$JOB_NAMESPACE" create secret generic "$name-kafka" --from-literal=username=ingest \
  --from-file=password="$work/password" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
rm -f "$work/password"

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
      - {name: seq, type: BIGINT, required: true}
      - {name: pad, type: STRING, maxLength: 2048}
    eventTime: {field: event_time, format: epoch-millis}
  target: {database: $db, table: events}
  governance: {owner: perf-team, level: L2, retention: 7d}
  job: $job
  deletionPolicy: Delete
YAML
step "apply $name"
kubectl apply -f "$evidence/source.yaml"
await events-running 900 "phase == 'Running' and live_all() and condition('SourceReady') == 'True' \
  and condition('TargetReady') == 'True'"

# --------------------------------------------------------------------------- the observer
tools_configmap "$tools"
observer_identity "$principal" "$db"
observer_start "$observer" "$principal" "$tools" "offsets=$db.events,_partition,_offset"
step "observer $observer on $db.events"

# --------------------------------------------------------------------------- the producer
kubectl -n "$DEV_KAFKA_NAMESPACE" get secret producer -o jsonpath='{.data.sasl\.jaas\.config}' \
  | base64 -d >"$work/jaas"
kubectl -n "$JOB_NAMESPACE" create secret generic "$producer" --from-file=sasl.jaas.config="$work/jaas" \
  --from-file=ca.crt="$work/kafka-ca.pem" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
rm -f "$work/jaas"
# At most the warm-up and MINUTES, or a long run's limit when STOP_FILE ends it.
seconds=$(((minutes + 5) * 60))
[[ -z "$stop_file" ]] || seconds=$((6 * 3600))
kubectl -n "$JOB_NAMESPACE" delete pod "$producer" --ignore-not-found --wait=true >/dev/null
kubectl -n "$JOB_NAMESPACE" apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $producer, labels: {colvenna-lake.io/role: performance}}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 1001, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: produce
      image: $KAFKA_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["bash", "-c"]
      args:
        - |
          printf 'bootstrap.servers=$bootstrap\nsecurity.protocol=SASL_SSL\nsasl.mechanism=SCRAM-SHA-512\nssl.truststore.type=PEM\nssl.truststore.location=/client/ca.crt\n' >/tmp/client.properties
          printf 'sasl.jaas.config=%s\n' "\$(cat /client/sasl.jaas.config)" >>/tmp/client.properties
          java -Xmx768m -cp "/opt/kafka/libs/*:/tools/tools.jar" com.colvenna.lake.performance.EventProducer \
            /tmp/client.properties $topic $rate $seconds $bytes $sample_every $run /out/stop /out/producer.log \
            2>/out/stderr
          echo "exit \$?" >/out/exited
          # Stays until deleted, so that the receipts can be copied out.
          sleep 86400
      resources: {requests: {cpu: "1", memory: 1Gi}, limits: {memory: 1Gi}}
      volumeMounts:
        - {name: client, mountPath: /client, readOnly: true}
        - {name: tools, mountPath: /tools, readOnly: true}
        - {name: out, mountPath: /out}
  volumes:
    - {name: client, secret: {secretName: $producer}}
    - {name: tools, configMap: {name: $tools}}
    - {name: out, emptyDir: {}}
YAML
kubectl -n "$JOB_NAMESPACE" wait --for=condition=Ready "pod/$producer" --timeout=300s >/dev/null
# Producing once the broker acknowledged some: a stat line with acknowledgements.
for _ in $(seq 1 60); do
  kubectl -n "$JOB_NAMESPACE" exec "$producer" -- grep -qE '^stat [0-9]+ [0-9]+ [1-9]' \
    /out/producer.log 2>/dev/null && break
  sleep 5
done
kubectl -n "$JOB_NAMESPACE" exec "$producer" -- grep -qE '^stat [0-9]+ [0-9]+ [1-9]' /out/producer.log
if [[ -n "$ready_file" ]]; then echo producing >"$ready_file"; fi
step "producer $producer: $rate events a second of $bytes bytes, every ${sample_every}th receipt written"
sampler="$(clock_sampler "$evidence/clocks.tsv" "clock-$run" "$DEV_KAFKA_NAMESPACE/^$DEV_KAFKA_RELEASE-[a-z-]+-[0-9]+\$" \
  "$JOB_NAMESPACE/^$name-.*taskmanager" "$JOB_NAMESPACE/^$producer\$" "$JOB_NAMESPACE/^$observer\$")"

# ------------------------------------------------------------------------------ the run
if [[ -n "$stop_file" ]]; then
  step "until $stop_file exists"
  while [[ ! -e "$stop_file" ]] && ! kubectl -n "$JOB_NAMESPACE" exec "$producer" -- test -e /out/exited 2>/dev/null; do
    sleep 30
  done
else
  step "$minutes minutes after a 5-minute warm-up"
  sleep "$seconds"
fi
kubectl -n "$JOB_NAMESPACE" exec "$producer" -- touch /out/stop || true
for _ in $(seq 1 60); do
  kubectl -n "$JOB_NAMESPACE" exec "$producer" -- test -e /out/exited 2>/dev/null && break
  sleep 5
done
kubectl -n "$JOB_NAMESPACE" exec "$producer" -- cat /out/producer.log >"$evidence/producer.log" || true
kubectl -n "$JOB_NAMESPACE" exec "$producer" -- cat /out/stderr >"$evidence/producer.stderr" 2>/dev/null || true
python3 - "$evidence/producer.log" >"$evidence/window.txt" <<'PY'
import sys
stats = [line.split() for line in open(sys.argv[1]) if line.startswith("stat ")]
print(stats[0][1] if stats else 0, stats[-1][1] if stats else 0)
PY
step "producer stopped: $(grep '^end ' "$evidence/producer.log" || echo 'no end line')"
await events-caught-up 900 "lag == 0 and live_all()" || true
# The last checkpoint commits what was read last.
sleep 60
observer_collect "$observer" 1 "$evidence/observed.jsonl" || true
kill "$sampler" 2>/dev/null || true
sampler=""
capture "$evidence/status/final.json" || true
step "observer: $(grep -c '"type":"OFFSETS"' "$evidence/observed.jsonl" || true) snapshots"
kubectl -n "$JOB_NAMESPACE" get events -o json >"$evidence/k8s-events.json" 2>/dev/null || true
