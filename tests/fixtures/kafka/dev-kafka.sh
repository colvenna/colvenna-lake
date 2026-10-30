#!/usr/bin/env bash
# The development profile's optional test Kafka (tests/fixtures/kafka/colvenna-dev-kafka), declared
# through the Strimzi operator the cluster already runs, and what uninstalling it leaves behind.
# For development and testing only; production Kafka is not installed by the platform.
#   install <evidence> [<helm --set>...]   installs or upgrades release colvenna-dev and waits until
#                                          Kafka, its topics and its users are ready; onto data an
#                                          uninstall kept, under that data's cluster id
#   check <evidence> produce|consume <n>   a client in a namespace with the client label writes n
#                                          records, or reads n back from the start; then the
#                                          refusals, each beside its positive control: a client
#                                          without credentials, and a namespace without the label
#   uninstall <evidence>                   helm uninstall; lists the volume claims left behind and
#                                          records their cluster id in ConfigMap colvenna-dev-retained-data,
#                                          so that install reuses them
#   purge <evidence> --yes                 deletes the data: deleteClaim=true, then uninstall (or,
#                                          with nothing installed, the retained claims); none is left
# The release lives in colvenna-lake-kafka, the one namespace the operator watches. Uninstall through
# this script: a plain helm uninstall keeps the claims too, but not their cluster id.
# Records kept across an uninstall still expire with their topic's retention (1 day by default).
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../../acceptance/common.sh
source "$(dirname "$0")/../../acceptance/common.sh"
assert_account
ensure_kubeconfig
usage="usage: dev-kafka.sh install|check|uninstall|purge <evidence-dir> [...]"
command="${1:?$usage}"
evidence="${2:?$usage}"
shift 2
mkdir -p "$evidence"
release="$DEV_KAFKA_RELEASE"
namespace="$DEV_KAFKA_NAMESPACE"
topic=dev-events
bootstrap="$release-kafka-bootstrap.$namespace.svc:9093"
# Where uninstall records the cluster id of data it keeps.
retained="$release-retained-data"
chart="$REPO_DIR/tests/fixtures/kafka/colvenna-dev-kafka"
work="$(mktemp -d)"
chmod 700 "$work"
trap 'rm -rf "$work"' EXIT
log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" | tee -a "$evidence/dev-kafka.log"; }
fail() { log "FAIL: $*"; exit 1; }
claims() {
  kubectl -n "$namespace" get pvc -l "strimzi.io/cluster=$release" \
    -o jsonpath='{range .items[*]}{.metadata.name} {.status.phase} {.spec.volumeName}{"\n"}{end}'
}
installed() { helm status "$release" --namespace "$namespace" >/dev/null 2>&1; }
retained_id() {
  kubectl -n "$namespace" get configmap "$retained" -o jsonpath='{.data.clusterId}' 2>/dev/null || true
}
# release_operands: deletes the release's topics and users while the entity operator still runs,
# so that its finalizers are cleared; helm uninstall afterwards would race the operator's own exit.
release_operands() {
  kubectl -n "$namespace" delete kafkatopic,kafkauser -l "strimzi.io/cluster=$release" \
    --wait=true --timeout=5m >/dev/null
}
wait_gone() {
  for _ in $(seq 1 60); do
    [[ -z "$(kubectl -n "$namespace" get pods -l "strimzi.io/cluster=$release" -o name)" ]] && return 0
    sleep 5
  done
  return 1
}
# adopt <cluster-id>: the paused Kafka resource takes the retained volume's cluster id, checked,
# before anything reconciles it.
adopt() {
  for _ in $(seq 1 12); do
    kubectl -n "$namespace" patch "kafka/$release" --subresource status --type merge \
      -p "{\"status\":{\"clusterId\":\"$1\"}}" >/dev/null 2>&1 || true
    [[ "$(kubectl -n "$namespace" get "kafka/$release" -o jsonpath='{.status.clusterId}')" == "$1" ]] \
      && return 0
    sleep 5
  done
  return 1
}

# client_pod <namespace> <name> <user> <script>: runs a Kafka client with the user's SCRAM
# credential and the cluster CA as files; the script finds its settings in /tmp/client.properties
# and a `tcp <host:port>` test that a connection opens.
client_pod() {
  local ns="$1" name="$2" user="$3" script="$4" status=0
  kubectl -n "$namespace" get secret "$release-cluster-ca-cert" -o jsonpath='{.data.ca\.crt}' \
    | base64 -d >"$work/ca.crt"
  kubectl -n "$namespace" get secret "$user" -o jsonpath='{.data.sasl\.jaas\.config}' \
    | base64 -d >"$work/sasl.jaas.config"
  kubectl -n "$ns" create secret generic "$name" --from-file=ca.crt="$work/ca.crt" \
    --from-file=sasl.jaas.config="$work/sasl.jaas.config" --dry-run=client -o yaml \
    | kubectl apply -f - >/dev/null
  rm -f "$work/ca.crt" "$work/sasl.jaas.config"
  cat >"$evidence/$name.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $name, namespace: $ns}
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
          cat >/tmp/client.properties <<EOF
          security.protocol=SASL_SSL
          sasl.mechanism=SCRAM-SHA-512
          ssl.truststore.type=PEM
          ssl.truststore.location=/client/ca.crt
          EOF
          printf 'sasl.jaas.config=%s\n' "\$(cat /client/sasl.jaas.config)" >>/tmp/client.properties
          tcp() { timeout 10 bash -c "</dev/tcp/\${1%:*}/\${1#*:}" 2>/dev/null; }
$(printf '%s\n' "$script" | sed 's/^/          /')
      resources:
        requests: {cpu: 250m, memory: 512Mi}
        limits: {memory: 512Mi}
      volumeMounts: [{name: client, mountPath: /client, readOnly: true}]
  volumes: [{name: client, secret: {secretName: $name}}]
YAML
  run_pod "$ns" "$name" "$evidence/$name.yaml" || status=1
  kubectl -n "$ns" delete secret "$name" --ignore-not-found >/dev/null
  return "$status"
}

case "$command" in
  install)
    ensure_storage_class
    cluster_id="$(retained_id)"
    if [[ -n "$(claims)" ]] && { ! installed || [[ -n "$cluster_id" ]]; }; then
      # Data an earlier uninstall kept: reinstall onto it under the cluster id it was written with.
      [[ -n "$cluster_id" ]] || fail "retained volume claims without a recorded cluster id; purge them first"
      if ! installed; then
        helm_upgrade "$release" "$chart" --namespace "$namespace" --set storage.class=colvenna-gp3 \
          --set image="$KAFKA_IMAGE" --set recovery.clusterId="$cluster_id" "$@" >"$evidence/helm-install-paused.txt"
      fi
      adopt "$cluster_id" || fail "the Kafka resource did not take cluster id $cluster_id"
      log "reinstalling onto the retained data of cluster $cluster_id"
      helm_upgrade "$release" "$chart" --namespace "$namespace" --reuse-values \
        --set image="$KAFKA_IMAGE" --set recovery.clusterId= >"$evidence/helm-install.txt"
      kubectl -n "$namespace" delete configmap "$retained" >/dev/null
    else
      helm_upgrade "$release" "$chart" --namespace "$namespace" \
        --set storage.class=colvenna-gp3 --set image="$KAFKA_IMAGE" "$@" >"$evidence/helm-install.txt"
    fi
    helm -n "$namespace" get values "$release" -o yaml >"$evidence/values.yaml"
    kubectl -n "$namespace" wait "kafka/$release" --for=condition=Ready --timeout=20m >/dev/null
    for r in "kafkatopic/$topic" kafkauser/dev-producer kafkauser/dev-consumer; do
      kubectl -n "$namespace" wait "$r" --for=condition=Ready --timeout=5m >/dev/null
    done
    kubectl -n "$namespace" get kafka,kafkanodepool,kafkatopic,kafkauser \
      -l "strimzi.io/cluster=$release" >"$evidence/resources.txt"
    log "installed: $(claims | tr '\n' ';')"
    ;;
  check)
    mode="${1:?$usage}"
    count="${2:?$usage}"
    for ns in "$KAFKA_CLIENT_NAMESPACE" "$KAFKA_OUTSIDER_NAMESPACE"; do
      kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
      kubectl label namespace "$ns" --overwrite pod-security.kubernetes.io/enforce=restricted >/dev/null
    done
    kubectl label namespace "$KAFKA_CLIENT_NAMESPACE" --overwrite colvenna-lake.io/dev-kafka-client=true >/dev/null
    bin=/opt/kafka/bin
    if [[ "$mode" == produce ]]; then
      client_pod "$KAFKA_CLIENT_NAMESPACE" kafka-produce dev-producer \
        "seq 1 $count | sed 's/^/dev-/' | $bin/kafka-console-producer.sh --bootstrap-server $bootstrap --producer.config /tmp/client.properties --topic $topic
echo produced $count" | tee "$evidence/produce.txt"
    else
      # shellcheck disable=SC2016  # expanded by the pod's shell
      client_pod "$KAFKA_CLIENT_NAMESPACE" kafka-consume dev-consumer \
        "$bin/kafka-console-consumer.sh --bootstrap-server $bootstrap --consumer.config /tmp/client.properties --topic $topic --group dev-check-\$(date +%s) --from-beginning --max-messages $count --timeout-ms 90000 >/tmp/records || true
echo consumed \$(grep -c '^dev-' /tmp/records)" | tee "$evidence/consume.txt"
      grep -q "^consumed $count$" "$evidence/consume.txt" || fail "consumed other than $count records"
    fi
    log "$mode $count records: OK"
    # Without credentials: TLS but no SASL. The same client with them lists the topic first.
    client_pod "$KAFKA_CLIENT_NAMESPACE" kafka-anonymous dev-consumer \
      "$bin/kafka-topics.sh --bootstrap-server $bootstrap --command-config /tmp/client.properties --list | grep -qx $topic
echo with credentials: lists $topic
sed -i '/^sasl\\./d; s/^security.protocol=.*/security.protocol=SSL/' /tmp/client.properties
if timeout 60 $bin/kafka-topics.sh --bootstrap-server $bootstrap --command-config /tmp/client.properties --list >/tmp/out 2>&1; then echo ANONYMOUS-ACCEPTED; cat /tmp/out; exit 1; fi
if grep -qx $topic /tmp/out; then echo ANONYMOUS-LISTED; exit 1; fi
echo without credentials: refused" | tee "$evidence/anonymous.txt"
    # From a namespace without the label, valid credentials or not: the NetworkPolicy drops the
    # connection, while the name resolves and the client namespace connects.
    client_pod "$KAFKA_CLIENT_NAMESPACE" kafka-reach dev-consumer \
      "tcp $bootstrap && echo client namespace: connects" | tee "$evidence/reach.txt"
    client_pod "$KAFKA_OUTSIDER_NAMESPACE" kafka-outsider dev-consumer \
      "getent hosts ${bootstrap%:*} >/dev/null && echo outsider: name resolves
if tcp $bootstrap; then echo OUTSIDER-CONNECTED; exit 1; fi
echo outsider: connection dropped" | tee "$evidence/outsider.txt"
    log "refusals: $(tail -1 "$evidence/anonymous.txt"); $(tail -1 "$evidence/outsider.txt")"
    ;;
  uninstall)
    cluster_id="$(kubectl -n "$namespace" get "kafka/$release" -o jsonpath='{.status.clusterId}')"
    # Topics stay in Kafka with the data: the topic operator lets go of them before their resources
    # go, while it still runs to clear its finalizers.
    kubectl -n "$namespace" annotate kafkatopic -l "strimzi.io/cluster=$release" --overwrite \
      strimzi.io/managed=false >/dev/null
    release_operands
    helm uninstall "$release" --namespace "$namespace" >"$evidence/helm-uninstall.txt"
    wait_gone || fail "the Kafka pods did not go"
    claims >"$evidence/claims-after-uninstall.txt"
    if [[ -s "$evidence/claims-after-uninstall.txt" ]]; then
      # Kept data, kept visibly: the claims and, beside them, the cluster id they belong to.
      kubectl -n "$namespace" create configmap "$retained" --from-literal=clusterId="$cluster_id" \
        --from-file=claims="$evidence/claims-after-uninstall.txt" \
        --from-literal=retainedAt="$(date -u +%Y-%m-%dT%H:%M:%SZ)" --dry-run=client -o yaml \
        | kubectl apply -f - >/dev/null
      log "uninstalled; data retained: $(tr '\n' ';' <"$evidence/claims-after-uninstall.txt") cluster $cluster_id (configmap $retained)"
    else
      log "uninstalled; no data left"
    fi
    ;;
  purge)
    [[ "${1:-}" == --yes ]] || { echo "purge deletes the dev Kafka's data; pass --yes" >&2; exit 1; }
    if ! installed; then
      # Nothing installed: only retained data is left to delete.
      kubectl -n "$namespace" delete pvc -l "strimzi.io/cluster=$release" --wait=true >/dev/null
    else
      helm_upgrade "$release" "$chart" --namespace "$namespace" --reuse-values \
        --set storage.deleteClaim=true >"$evidence/helm-purge-upgrade.txt"
      # The operator marks each claim for deletion; it must have done so before the cluster goes.
      marked=false
      for _ in $(seq 1 60); do
        if [[ "$(kubectl -n "$namespace" get pvc -l "strimzi.io/cluster=$release" \
          -o jsonpath='{.items[*].metadata.annotations.strimzi\.io/delete-claim}')" == true ]]; then
          marked=true
          break
        fi
        sleep 5
      done
      [[ "$marked" == true || -z "$(claims)" ]] || fail "the operator did not mark the claims for deletion"
      release_operands
      helm uninstall "$release" --namespace "$namespace" >"$evidence/helm-uninstall.txt"
      wait_gone || fail "the Kafka pods did not go"
      for _ in $(seq 1 60); do
        [[ -z "$(claims)" ]] && break
        sleep 5
      done
    fi
    claims >"$evidence/claims-after-purge.txt"
    [[ ! -s "$evidence/claims-after-purge.txt" ]] || fail "volume claims left after purge"
    kubectl -n "$namespace" delete configmap "$retained" --ignore-not-found >/dev/null
    for ns in "$KAFKA_CLIENT_NAMESPACE" "$KAFKA_OUTSIDER_NAMESPACE"; do
      kubectl delete namespace "$ns" --ignore-not-found --wait=false >/dev/null
    done
    log "purged: no volume claim left"
    ;;
  *)
    echo "$usage" >&2
    exit 1
    ;;
esac
