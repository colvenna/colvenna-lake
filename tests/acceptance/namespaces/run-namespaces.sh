#!/usr/bin/env bash
# Acceptance on EKS: a second managed namespace's jobs reach their own Flink state and no other
# namespace's. Run after deploy-stack.sh and install-operator.sh with MANAGED_NAMESPACES naming
# JOB_NAMESPACE and the second namespace, with the development Kafka installed (tests/acceptance/catalog/
# dev-kafka.sh). Writes PASS/FAIL lines to <evidence>/verdicts.tsv and fails if any check fails.
#
# - Each managed namespace's job service account has its own role (ColvennaLakeOperator's JobRoleArns).
# - A Kafka event source declared in the second namespace, on the development Kafka with a topic
#   and a user of its own, runs; its job completes checkpoints under flink-state/<second>/.
# - As each namespace's job identity: its own state can be listed, written, read and deleted, and
#   its state root HEADs 404 (Hadoop's S3A, which the erasure check lists the root with, fails on
#   403); the other namespace's state can be neither listed, read nor written, nor its role
#   assumed; the table data can be neither listed nor read.
# - Deleting the source removes its job; the topic, the user and the probes' objects go too.
#
# usage: run-namespaces.sh <run-id> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-namespaces.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-namespaces.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
IFS=, read -ra managed <<<"$MANAGED_NAMESPACES"
second=""
for ns in "${managed[@]}"; do
  [[ "$ns" != "$JOB_NAMESPACE" && -z "$second" ]] && second="$ns"
done
[[ -n "$second" ]] || { echo "MANAGED_NAMESPACES names no namespace beside $JOB_NAMESPACE" >&2; exit 1; }
roles="$(stack_output "$OPERATOR_STACK" JobRoleArns)"
[[ "$roles" == "{"* ]] || { echo "$OPERATOR_STACK has no JobRoleArns: run deploy-stack.sh" >&2; exit 1; }
kubectl -n "$DEV_KAFKA_NAMESPACE" get kafka "$DEV_KAFKA_RELEASE" >/dev/null \
  || { echo "No development Kafka: tests/fixtures/kafka/dev-kafka.sh install first" >&2; exit 1; }
mkdir -p "$evidence/probes"
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
echo "managed namespaces: $JOB_NAMESPACE and $second"
echo "operator $(kubectl -n "$OPERATOR_NAMESPACE" get deployment "$OPERATOR_RELEASE" \
  -o jsonpath='{.spec.template.spec.containers[0].image}')" | tee "$evidence/images.txt"
failed=0
step() { echo "$(date -u +%FT%TZ)	$*" | tee -a "$evidence/timeline.tsv"; }
verdict() {
  # One line per verdict: a detail's newlines and tabs become spaces.
  local detail="${3:-}"
  detail="${detail//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${detail//$'\t'/ }" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS ]] || failed=1
}

name="ns-$run" topic="ns-$run" user="ns-$run" db="ods_ns_$run"
bootstrap="$DEV_KAFKA_RELEASE-kafka-bootstrap.$DEV_KAFKA_NAMESPACE.svc:9093"
client_label=colvenna-lake.io/dev-kafka-client
bucket="$(stack_output "$FOUNDATION_STACK" WarehouseBucket)"
small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
work="$(mktemp -d)"
chmod 700 "$work"
# shellcheck disable=SC2317  # invoked through the EXIT trap
cleanup() {
  kubectl -n "$second" delete lakesource "$name" --ignore-not-found --timeout=10m >/dev/null 2>&1 || true
  kubectl -n "$DEV_KAFKA_NAMESPACE" delete kafkauser "$user" --ignore-not-found >/dev/null 2>&1 || true
  kubectl -n "$DEV_KAFKA_NAMESPACE" delete kafkatopic "$topic" --ignore-not-found >/dev/null 2>&1 || true
  kubectl -n "$second" delete secret "$name-kafka" --ignore-not-found >/dev/null 2>&1 || true
  kubectl -n "$second" delete configmap "$name-kafka-ca" --ignore-not-found >/dev/null 2>&1 || true
  kubectl label namespace "$second" "$client_label-" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# ------------------------------------------------------------------- one role per namespace
step "the job service accounts and their roles"
seen=""
for ns in "${managed[@]}"; do
  got="$(kubectl -n "$ns" get serviceaccount colvenna-flink \
    -o jsonpath='{.metadata.annotations.eks\.amazonaws\.com/role-arn}')"
  want="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get(sys.argv[2], ""))' \
    "$roles" "$ns")"
  # Its own role, named after it, and no other namespace's.
  if [[ -n "$want" && "$got" == "$want" && "$got" == */"$(env_name colvenna-lake-jobs-)$ns" \
    && "$seen" != *" $got "* ]]; then
    verdict "role-$ns" PASS "$got"
  else
    verdict "role-$ns" FAIL "annotated '$got', $OPERATOR_STACK has '$want'"
  fi
  seen="$seen $got "
done

# ------------------------------------------------------- a source in the second namespace
step "topic $topic and user $user on the development Kafka"
kubectl -n "$DEV_KAFKA_NAMESPACE" apply -f - >/dev/null <<YAML
apiVersion: kafka.strimzi.io/v1
kind: KafkaTopic
metadata: {name: $topic, labels: {strimzi.io/cluster: $DEV_KAFKA_RELEASE}}
spec: {partitions: 1, replicas: 1, config: {retention.ms: 86400000}}
---
apiVersion: kafka.strimzi.io/v1
kind: KafkaUser
metadata: {name: $user, labels: {strimzi.io/cluster: $DEV_KAFKA_RELEASE}}
spec:
  authentication: {type: scram-sha-512}
  authorization:
    type: simple
    acls:
      - resource: {type: topic, name: $topic, patternType: literal}
        operations: [Read, Describe]
      # The source's group is colvenna.<namespace>.<name>.<uid>.
      - resource: {type: group, name: colvenna., patternType: prefix}
        operations: [Read]
YAML
kubectl -n "$DEV_KAFKA_NAMESPACE" wait "kafkatopic/$topic" "kafkauser/$user" --for=condition=Ready \
  --timeout=5m >/dev/null
kubectl -n "$DEV_KAFKA_NAMESPACE" wait "secret/$user" --for=create --timeout=2m >/dev/null
# The operator probes the cluster, the job reads it; the development Kafka admits these labels.
for ns in "$second" "$OPERATOR_NAMESPACE"; do
  kubectl label namespace "$ns" --overwrite "$client_label=true" >/dev/null
done
kubectl -n "$DEV_KAFKA_NAMESPACE" get secret "$DEV_KAFKA_RELEASE-cluster-ca-cert" \
  -o jsonpath='{.data.ca\.crt}' | base64 -d >"$work/kafka-ca.pem"
kubectl -n "$second" create configmap "$name-kafka-ca" --from-file=ca.pem="$work/kafka-ca.pem" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$DEV_KAFKA_NAMESPACE" get secret "$user" -o jsonpath='{.data.password}' \
  | base64 -d >"$work/password"
kubectl -n "$second" create secret generic "$name-kafka" --from-literal=username="$user" \
  --from-file=password="$work/password" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
rm -f "$work/password"

cat >"$evidence/source.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: $name, namespace: $second}
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
    eventTime: {field: event_time}
  target: {database: $db, table: events}
  job: $small
  deletionPolicy: Delete
YAML
step "apply $name in $second"
kubectl apply -f "$evidence/source.yaml" >/dev/null

# ready: the source's phase and conditions, "Running True True" once it runs.
ready() {
  kubectl -n "$second" get lakesource "$name" -o json | python3 -c '
import json, sys
s = json.load(sys.stdin).get("status", {})
c = {x["type"]: x["status"] for x in s.get("conditions", [])}
print(s.get("phase"), c.get("SourceReady"), c.get("JobRunning"), "|", s.get("message"))'
}
end=$((SECONDS + 900))
state=""
while ((SECONDS < end)); do
  state="$(ready 2>/dev/null || true)"
  [[ "$state" == "Running True True |"* ]] && break
  sleep 10
done
kubectl -n "$second" get lakesource "$name" -o yaml >"$evidence/source-status.yaml"
if [[ "$state" == "Running True True |"* ]]; then
  verdict second-source-running PASS "$state"
else
  verdict second-source-running FAIL "$state"
fi

# A completed checkpoint: Flink writes its _metadata last, under the job's own state prefix.
deployment="$(kubectl -n "$second" get flinkdeployment -l "colvenna-lake.io/source=$name" \
  -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
prefix="flink-state/$second/"
checkpoint=""
end=$((SECONDS + 600))
while [[ -n "$deployment" ]] && ((SECONDS < end)); do
  checkpoint="$(aws s3api list-objects-v2 --bucket "$bucket" --prefix "$prefix$deployment-" \
    --query 'Contents[].Key' --output text 2>/dev/null | tr '\t' '\n' \
    | grep -E '/checkpoints/[0-9a-f]{32}/chk-[0-9]+/_metadata$' | head -1 || true)"
  [[ -n "$checkpoint" ]] && break
  sleep 15
done
if [[ -n "$checkpoint" ]]; then
  verdict second-checkpoint-completed PASS "s3://$bucket/$checkpoint"
else
  verdict second-checkpoint-completed FAIL "no completed checkpoint under s3://$bucket/$prefix$deployment-*"
fi

# ------------------------------------------------------------------------------ isolation
# probe <namespace> <other> <other's object> <table object>: as the namespace's job identity, one
# line per request: "<label> OK", "<label> 404" or "<label> 403".
probe() {
  local ns="$1" other="$2" theirs="$3" table="$4" pod="ns-probe-$run-$1" role
  role="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])[sys.argv[2]])' "$roles" "$2")"
  cat >"$work/probe.sh" <<SH
b=$bucket own=flink-state/$ns other=flink-state/$other key=flink-state/$ns/$pod
t() {
  local label="\$1"; shift
  if out="\$("\$@" 2>&1)"; then echo "\$label OK"
  elif grep -qE '\(404\)|Not Found|NoSuchKey' <<<"\$out"; then echo "\$label 404"
  elif grep -qE '\(403\)|AccessDenied|Forbidden' <<<"\$out"; then echo "\$label 403"
  else echo "\$label ERROR \$(tr '\n' ' ' <<<"\$out" | cut -c1-160)"; fi
}
echo "identity \$(aws sts get-caller-identity --query Arn --output text)"
t own-list aws s3api list-objects-v2 --bucket \$b --prefix \$own/ --max-items 1
t own-root-head aws s3api head-object --bucket \$b --key \$own
t own-put aws s3api put-object --bucket \$b --key \$key --body /probe/probe.sh
t own-get aws s3api get-object --bucket \$b --key \$key /tmp/got
t own-delete aws s3api delete-object --bucket \$b --key \$key
t other-list aws s3api list-objects-v2 --bucket \$b --prefix \$other/ --max-items 1
t other-list-bare aws s3api list-objects-v2 --bucket \$b --prefix \$other --max-items 1
t other-root-head aws s3api head-object --bucket \$b --key \$other
t other-get aws s3api get-object --bucket \$b --key $theirs /tmp/theirs
t other-put aws s3api put-object --bucket \$b --key \$other/$pod --body /probe/probe.sh
t tables-list aws s3api list-objects-v2 --bucket \$b --prefix warehouse/ --max-items 1
t tables-get aws s3api get-object --bucket \$b --key $table /tmp/table
t other-assume aws sts assume-role-with-web-identity --role-arn $role --role-session-name probe \
  --web-identity-token "\$(cat "\$AWS_WEB_IDENTITY_TOKEN_FILE")"
SH
  kubectl -n "$ns" create configmap "$pod" --from-file=probe.sh="$work/probe.sh" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  cat >"$work/$pod.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $pod}
spec:
  serviceAccountName: colvenna-flink
  restartPolicy: Never
  securityContext: {runAsNonRoot: true, runAsUser: 1000, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: aws
      image: $AWS_CLI_IMAGE
      command: ["bash", "/probe/probe.sh"]
      env: [{name: HOME, value: /tmp}, {name: AWS_REGION, value: $AWS_REGION}]
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      resources: {requests: {cpu: 100m, memory: 256Mi}, limits: {memory: 256Mi}}
      volumeMounts: [{name: probe, mountPath: /probe, readOnly: true}]
  volumes: [{name: probe, configMap: {name: $pod}}]
YAML
  run_pod "$ns" "$pod" "$work/$pod.yaml" >"$evidence/probes/$ns.txt" 2>&1 || true
  kubectl -n "$ns" delete configmap "$pod" --ignore-not-found >/dev/null
}
# expect <namespace> <label> <answer>
expect() {
  local got
  got="$(grep -m1 "^$2 " "$evidence/probes/$1.txt" | cut -d' ' -f2- || true)"
  if [[ "$got" == "$3" ]]; then
    verdict "$1-$2" PASS "$got"
  else
    verdict "$1-$2" FAIL "got '${got:-nothing}', want $3"
  fi
}
step "isolation probes as each namespace's job identity"
# first_key <prefix>: an object that exists there (listed with this run's AWS identity), or nothing.
first_key() {
  aws s3api list-objects-v2 --bucket "$bucket" --prefix "$1" --max-items 1 --output json \
    | python3 -c 'import json,sys; c = json.load(sys.stdin).get("Contents") or []; print(c[0]["Key"] if c else "")' \
    || true
}
theirs_first="$(first_key "flink-state/$JOB_NAMESPACE/")"
table="$(first_key warehouse/)"
# Each probe reads an object that exists: a 403 for a missing one would prove nothing.
for pair in "existing-state-$JOB_NAMESPACE:$theirs_first" "existing-state-$second:$checkpoint" \
  "existing-table-object:$table"; do
  if [[ -n "${pair#*:}" ]]; then
    verdict "${pair%%:*}" PASS "${pair#*:}"
  else
    verdict "${pair%%:*}" FAIL "nothing to read"
  fi
done
probe "$second" "$JOB_NAMESPACE" "${theirs_first:-none}" "${table:-none}"
probe "$JOB_NAMESPACE" "$second" "${checkpoint:-none}" "${table:-none}"
for ns in "$second" "$JOB_NAMESPACE"; do
  if grep -q "^identity .*assumed-role/$(env_name colvenna-lake-jobs-)$ns/" "$evidence/probes/$ns.txt"; then
    verdict "$ns-identity" PASS "$(grep -m1 '^identity' "$evidence/probes/$ns.txt")"
  else
    verdict "$ns-identity" FAIL "$(head -3 "$evidence/probes/$ns.txt" | tr '\n' ' ')"
  fi
  for label in own-list own-put own-get own-delete; do
    expect "$ns" "$label" OK
  done
  expect "$ns" own-root-head 404
  for label in other-list other-list-bare other-root-head other-get other-put tables-list \
    tables-get other-assume; do
    expect "$ns" "$label" 403
  done
done

# ------------------------------------------------------------------------------- deletion
step "delete $name"
kubectl -n "$second" delete lakesource "$name" --timeout=10m >/dev/null || true
left="$(kubectl -n "$second" get flinkdeployment -l "colvenna-lake.io/source=$name" -o name 2>/dev/null || true)"
if [[ -z "$left" ]] && ! kubectl -n "$second" get lakesource "$name" >/dev/null 2>&1; then
  verdict second-source-deleted PASS "no LakeSource, no FlinkDeployment"
else
  verdict second-source-deleted FAIL "left: $left"
fi
passed="$(grep -c $'\tPASS\t' "$evidence/verdicts.tsv" || true)"
echo "$passed PASS, $(grep -c $'\tFAIL\t' "$evidence/verdicts.tsv" || true) FAIL"
exit "$failed"
