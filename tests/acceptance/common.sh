#!/usr/bin/env bash
# What the acceptance tests share beyond deploy/scripts/lib/common.sh: their namespaces, the images
# of the stand-ins they run (the user's MongoDB, orchestrator and collector), and helpers.
# shellcheck disable=SC2034  # settings used by the scripts that source this file
# shellcheck source-path=SCRIPTDIR source=../../deploy/scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/../../deploy/scripts/lib/common.sh"
TESTS_DIR="$REPO_DIR/tests"
ACCEPTANCE_DIR="$TESTS_DIR/acceptance"
FIXTURES_DIR="$TESTS_DIR/fixtures"

# The catalog tests' engines (Flink, Spark) run here, as the catalog stack's test-flink and
# test-spark roles expect.
ENGINE_NAMESPACE=colvenna-test-engines
# The development Kafka's clients, and a namespace that may not reach it.
KAFKA_CLIENT_NAMESPACE=colvenna-kafka-client
KAFKA_OUTSIDER_NAMESPACE=colvenna-kafka-outsider
# The stand-ins' images (deploy/images/third-party.tsv), each the environment's own copy: the
# environment keeps the digest even after upstream drops it.
# Kafka clients: the Strimzi 1.2.0 image of Kafka 4.3.1, as the brokers run it.
KAFKA_IMAGE="$(third_party_image strimzi-kafka)"
AWS_CLI_IMAGE="$(third_party_image aws-cli)"
# The user's MongoDB (MongoDB 7.0.45, its linux/amd64 image; Flink CDC 3.5 supports 7.0).
MONGO_IMAGE="$(third_party_image mongo)"
# The user's orchestrator (Airflow 2.10.5, Python 3.12, multi-arch index).
AIRFLOW_IMAGE="$(third_party_image airflow)"
# The user's collector (Debezium Server 3.6.3.Final, multi-arch index). quay.io rebuilds a release's
# image on a new base and drops the old index (306ed983… by 2026-10-20, a91f6e06… by 2026-10-21,
# 2a0c7703… by 2026-10-23, e6ccbfbf… by 2026-10-24, #234): the environment's copy outlives it.
DEBEZIUM_SERVER_IMAGE="$(third_party_image debezium-server)"

# forwarded_port <log>: the local port of a `kubectl port-forward ... :<port>` started in the
# background with its output in <log> (kubectl picks a free port, so the UATs of two environments can
# run on one host at once), once it forwards; fails after 60 s without it.
forwarded_port() {
  local port="" end=$((SECONDS + 60))
  while ((SECONDS < end)); do
    port="$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9][0-9]*\) .*/\1/p' "$1" 2>/dev/null | head -1)"
    [[ -z "$port" ]] || { echo "$port"; return 0; }
    sleep 1
  done
  echo "no port-forward: $(tail -2 "$1" 2>/dev/null | tr '\n' ' ')" >&2
  return 1
}

# prepare_engine_namespace <service-account> <role-arn>: the restricted, catalog-client engine
# namespace, the service account with its IRSA role, and the catalog CA as a ConfigMap.
prepare_engine_namespace() {
  local sa="$1" role="$2" ca
  kubectl create namespace "$ENGINE_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl label namespace "$ENGINE_NAMESPACE" --overwrite \
    pod-security.kubernetes.io/enforce=restricted colvenna-lake.io/catalog-client=true >/dev/null
  kubectl -n "$ENGINE_NAMESPACE" create serviceaccount "$sa" --dry-run=client -o yaml \
    | kubectl apply -f - >/dev/null
  kubectl -n "$ENGINE_NAMESPACE" annotate serviceaccount "$sa" --overwrite \
    "eks.amazonaws.com/role-arn=$role" >/dev/null
  ca="$(mktemp)"
  kubectl -n "$CATALOG_NAMESPACE" get secret colvenna-catalog-ca-key -o jsonpath='{.data.ca\.crt}' \
    | base64 -d >"$ca"
  kubectl -n "$ENGINE_NAMESPACE" create configmap colvenna-catalog-ca --from-file=ca.crt="$ca" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  rm -f "$ca"
}

# prepare_flink_runtime: what a session or application cluster's JobManager (service account
# test-flink) needs to start its TaskManagers, and nothing more.
prepare_flink_runtime() {
  cat <<YAML | kubectl apply -f - >/dev/null
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: {name: test-flink-runtime, namespace: $ENGINE_NAMESPACE}
rules:
  - apiGroups: [""]
    resources: [pods]
    verbs: [get, list, watch, create, delete]
  - apiGroups: [""]
    resources: [configmaps]
    verbs: [get, list, watch, create, update, patch, delete]
  - apiGroups: [""]
    resources: [services]
    verbs: [get, list, watch]
  # Its own Deployment, which owns the TaskManager pods it creates.
  - apiGroups: [apps]
    resources: [deployments]
    verbs: [get]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: {name: test-flink-runtime, namespace: $ENGINE_NAMESPACE}
subjects:
  - {kind: ServiceAccount, name: test-flink, namespace: $ENGINE_NAMESPACE}
roleRef: {kind: Role, name: test-flink-runtime, apiGroup: rbac.authorization.k8s.io}
YAML
}

# catalog_replicas: "<pod> <node> <source-sha>" per running colvenna-catalog replica; terminating ones
# do not count.
catalog_replicas() {
  kubectl -n "$CATALOG_NAMESPACE" get pods -l app.kubernetes.io/name=colvenna-catalog "$LIVE_PODS" -o json \
    | python3 -c 'import json,sys; [print(p["metadata"]["name"], p["spec"]["nodeName"], p["metadata"].get("annotations", {}).get("colvenna-lake/source-sha", "-")) for p in json.load(sys.stdin)["items"] if p["status"].get("phase") == "Running" and not p["metadata"].get("deletionTimestamp")]'
}

# wait_job <namespace> <job> <timeout-seconds>: returns as soon as the Job completes (0) or fails (1).
wait_job() {
  local namespace="$1" job="$2" end=$((SECONDS + $3)) conditions
  while ((SECONDS < end)); do
    conditions="$(kubectl -n "$namespace" get job "$job" \
      -o jsonpath='{range .status.conditions[?(@.status=="True")]}{.type} {end}' 2>/dev/null || true)"
    case "$conditions" in
      *Complete*) return 0 ;;
      *Failed*) return 1 ;;
    esac
    sleep 10
  done
  return 1
}

# secret_field <secret-arn> <field>: one field of a Secrets Manager JSON secret, on stdout.
secret_field() {
  aws secretsmanager get-secret-value --secret-id "$1" --query SecretString --output text \
    | python3 -c 'import json,sys; sys.stdout.write(str(json.load(sys.stdin)[sys.argv[1]]))' "$2"
}

# colvenna_cli: the CLI built from this tree for this machine (colvenna-cli/check.sh build).
colvenna_cli() {
  local arch
  case "$(uname -m)" in
    aarch64) arch=arm64 ;;
    *) arch=amd64 ;;
  esac
  local bin="$REPO_DIR/colvenna-cli/dist/colvenna-linux-$arch" mode="" candidate=""
  # An environment installed as a release (install.sh --release) has the candidate's binaries, built
  # from its built_from commit, not HEAD's: they are taken by their SHA-256, never built over.
  if [[ -s "$STATE_DIR/install-mode" ]]; then
    read -r _ mode candidate <"$STATE_DIR/install-mode"
  fi
  if [[ "$mode" == release ]]; then
    local want
    want="$(python3 "$DEPLOY_DIR/scripts/release/manifest.py" get "$REPO_DIR/release/$candidate/manifest.json" \
      "cli.binaries.colvenna-linux-$arch")"
    if [[ ! -x "$bin" || "$(sha256sum "$bin" | cut -d' ' -f1)" != "$want" ]]; then
      echo "$bin is not $candidate's: COLVENNA_ENV=$COLVENNA_ENV deploy/install.sh --release $candidate --only cli" >&2
      return 1
    fi
  elif [[ ! -x "$bin" ]] || ! grep -q "$(git -C "$REPO_DIR" rev-parse --short HEAD)" <<<"$("$bin" version)"; then
    bash "$REPO_DIR/colvenna-cli/check.sh" build >&2
  fi
  echo "$bin"
}

# source_diagnostics <lakesource> <dir>: what a source's readiness depends on, kept before the run
# cleans it up (#245: a table that stayed Snapshotting left only its status behind): the source and
# its jobs, the operator's log lines about it, the logs of its pods (and of their last restart), and
# the events about the source and its pods. Never fails the caller.
source_diagnostics() {
  local name="$1" dir="$2" op pod k=(kubectl --request-timeout=60s)
  mkdir -p "$dir"
  "${k[@]}" -n "$JOB_NAMESPACE" get lakesource "$name" -o yaml >"$dir/lakesource.yaml" 2>&1 || true
  "${k[@]}" -n "$JOB_NAMESPACE" get flinkdeployment -l "colvenna-lake.io/source=$name" -o yaml \
    >"$dir/flinkdeployments.yaml" 2>&1 || true
  op="$("${k[@]}" -n "$OPERATOR_NAMESPACE" get pods -l app.kubernetes.io/name=colvenna-operator -o name 2>/dev/null \
    | sed -n 1p || true)"
  if [[ -n "$op" ]]; then
    "${k[@]}" -n "$OPERATOR_NAMESPACE" logs "$op" --since=3h 2>&1 | grep -wF "$name" >"$dir/operator.log" || true
  fi
  for pod in $("${k[@]}" -n "$JOB_NAMESPACE" get pods -o name 2>/dev/null | grep -F "/$name-" || true); do
    "${k[@]}" -n "$JOB_NAMESPACE" logs "$pod" --all-containers --prefix --tail=20000 >"$dir/${pod#pod/}.log" 2>&1 || true
    "${k[@]}" -n "$JOB_NAMESPACE" logs "$pod" --all-containers --prefix --tail=20000 --previous \
      >"$dir/${pod#pod/}.previous.log" 2>/dev/null || rm -f "$dir/${pod#pod/}.previous.log"
    "${k[@]}" -n "$JOB_NAMESPACE" get events --field-selector "involvedObject.name=${pod#pod/}" -o yaml \
      >"$dir/${pod#pod/}.events.yaml" 2>&1 || true
  done
  "${k[@]}" -n "$JOB_NAMESPACE" get events --field-selector "involvedObject.name=$name" -o yaml \
    >"$dir/events.yaml" 2>&1 || true
  echo "diagnostics of $name in $dir"
}
