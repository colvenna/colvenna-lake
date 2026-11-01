#!/usr/bin/env bash
# Health of colvenna-catalog and of the platform services it needs, one line per check:
#   - colvenna-catalog: every replica ready, on more than one node, under a PodDisruptionBudget, each
#     answering its loopback readiness probe (which checks PostgreSQL);
#   - the metadata database: the RDS instance in a status it serves connections in (backing-up, its
#     daily backup, among them), and the catalog schema at the version the running server applies
#     (the schema_version of the Colvenna Catalog release this commit pins,
#     deploy/images/colvenna-catalog.json);
#   - the Flink Kubernetes Operator and the Doris operator available, the Doris operator with the
#     requests and limits setup-operators.sh gives it; with --dev-kafka also the Strimzi operator
#     and the dev profile's Kafka.
# Writes <evidence>/health.txt and fails if any check fails.
# Usage: check-health.sh <evidence-dir> [--dev-kafka]
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
assert_account
ensure_kubeconfig
evidence="${1:?usage: check-health.sh <evidence-dir> [--dev-kafka]}"
mkdir -p "$evidence"
out="$evidence/health.txt"
: >"$out"
failed=0
check() { # check <name> <ok:true|false> <detail>
  printf '%s %s %s\n' "$1" "$([[ "$2" == true ]] && echo OK || echo FAIL)" "$3" | tee -a "$out"
  [[ "$2" == true ]] || failed=1
}
available() { # available <namespace> <deployment>: ready replicas equal to the desired ones
  local ready desired
  ready="$(kubectl -n "$1" get deploy "$2" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)"
  desired="$(kubectl -n "$1" get deploy "$2" -o jsonpath='{.spec.replicas}' 2>/dev/null || true)"
  check "$1/$2" "$([[ -n "$desired" && "${ready:-0}" == "$desired" ]] && echo true || echo false)" \
    "${ready:-0}/${desired:-absent} ready"
}
# metadata_database <endpoint>: the RDS instance at the endpoint, in a status it serves connections
# in. By the RDS user guide ("Viewing Amazon RDS DB instance status", and the downtime notes of
# "Settings for DB instances"), those are:
#   available;
#   backing-up, the daily automatic backup (a Single-AZ instance's I/O pauses for seconds as it
#     starts);
#   configuring-enhanced-monitoring, configuring-iam-database-auth, configuring-log-exports and
#     resetting-master-credentials: changes RDS makes without downtime (the catalog logs in as
#     colvenna_catalog, not as the master user);
#   storage-optimization and storage-initialization: storage work in the background, the instance
#     available meanwhile.
# Any other status fails, and so does one RDS adds later: among them stopped, stopping, starting,
# rebooting, modifying (a class change, with downtime, is one), upgrading, maintenance, renaming and
# the VPC moves (the endpoint or the network changing), storage-full (writes fail), creating,
# deleting, failed, restore-error, inaccessible-* and incompatible-*. The status is the detail's
# first value.
metadata_database() {
  local rds status ok=false
  rds="$(aws rds describe-db-instances \
    --query "DBInstances[?Endpoint.Address=='$1'].[DBInstanceStatus,EngineVersion,MultiAZ,BackupRetentionPeriod] | [0]" \
    --output text)"
  read -r status _ <<<"$rds"
  case "$status" in
    available | backing-up | configuring-enhanced-monitoring | configuring-iam-database-auth \
      | configuring-log-exports | resetting-master-credentials | storage-optimization \
      | storage-initialization) ok=true ;;
  esac
  check metadata-database "$ok" "status, version, multi-AZ, backup days: $rds"
}

available "$CATALOG_NAMESPACE" colvenna-catalog
# On as many nodes as the chart keeps them apart across: min(replicas, write node group size), so a
# group of one node passes with every replica on it.
nodes="$(kubectl -n "$CATALOG_NAMESPACE" get pods -l app.kubernetes.io/name=colvenna-catalog "$LIVE_PODS" \
  -o jsonpath='{range .items[*]}{.spec.nodeName}{"\n"}{end}' | awk 'NF' | sort -u | wc -l)"
replicas="$(kubectl -n "$CATALOG_NAMESPACE" get deploy colvenna-catalog -o jsonpath='{.spec.replicas}' 2>/dev/null || echo 0)"
eligible="$(write_node_group_size 2>/dev/null || echo 0)"
apart=$((replicas < eligible ? replicas : eligible))
check catalog-spread "$( ((nodes >= apart && apart > 0)) && echo true || echo false)" \
  "replicas on $nodes nodes; $replicas replicas, a write node group of $eligible"
pdb="$(kubectl -n "$CATALOG_NAMESPACE" get pdb colvenna-catalog -o jsonpath='{.spec.minAvailable}' 2>/dev/null || true)"
check catalog-disruption-budget "$([[ -n "$pdb" ]] && echo true || echo false)" "minAvailable ${pdb:-none}"
for pod in $(kubectl -n "$CATALOG_NAMESPACE" get pods -l app.kubernetes.io/name=colvenna-catalog "$LIVE_PODS" -o name); do
  if kubectl -n "$CATALOG_NAMESPACE" exec "${pod#pod/}" -- \
    curl -fsS -o /dev/null --max-time 5 http://127.0.0.1:8182/readyz >/dev/null 2>&1; then
    check "${pod#pod/}-readyz" true "PostgreSQL reachable"
  else
    check "${pod#pod/}-readyz" false "not ready"
  fi
done

endpoint="$(stack_output "$FOUNDATION_STACK" CatalogEndpoint)"
metadata_database "$endpoint"
expected="$(python3 "$DEPLOY_DIR/scripts/release/manifest.py" catalog schema_version)"
release="$(python3 "$DEPLOY_DIR/scripts/release/manifest.py" catalog candidate)"
manifest="$(mktemp)"
cat >"$manifest" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: colvenna-catalog-health-schema}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 70, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: psql
      image: $POSTGRES_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      env:
        - {name: PGHOST, value: "$endpoint"}
        - {name: PGDATABASE, value: colvenna_catalog}
        - {name: PGSSLMODE, value: verify-full}
        - {name: PGSSLROOTCERT, value: /ca/ca.pem}
        - name: PGUSER
          valueFrom: {secretKeyRef: {name: colvenna-catalog-db, key: username}}
        - name: PGPASSWORD
          valueFrom: {secretKeyRef: {name: colvenna-catalog-db, key: password}}
      command: ["psql", "-At", "-c", "SELECT max(version) FROM colvenna_schema_version"]
      volumeMounts: [{name: ca, mountPath: /ca}]
  volumes: [{name: ca, configMap: {name: colvenna-catalog-db-ca}}]
YAML
applied="$(run_pod "$CATALOG_NAMESPACE" colvenna-catalog-health-schema "$manifest" 2>/dev/null || true)"
rm -f "$manifest"
check catalog-schema "$([[ "${applied:-}" == "$expected" ]] && echo true || echo false)" \
  "database at schema version ${applied:-unknown}, Colvenna Catalog $release applies $expected"

available flink-operator flink-kubernetes-operator
available doris-operator doris-operator
resources="$(kubectl -n doris-operator get deployment doris-operator -o jsonpath='{range .spec.template.spec.containers[0].resources}{.requests.cpu} {.requests.memory} {.limits.cpu} {.limits.memory}{end}' 2>/dev/null || true)"
check doris-operator-resources "$([[ "$resources" == "${DORIS_OPERATOR_RESOURCES[*]}" ]] && echo true || echo false)" \
  "requests and limits (cpu, memory): ${resources:-none}; expected ${DORIS_OPERATOR_RESOURCES[*]}"
if [[ "${2:-}" == --dev-kafka ]]; then
  available "$DEV_KAFKA_NAMESPACE" strimzi-cluster-operator
  ready="$(kubectl -n "$DEV_KAFKA_NAMESPACE" get kafka "$DEV_KAFKA_RELEASE" \
    -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)"
  check dev-kafka "$([[ "$ready" == True ]] && echo true || echo false)" "Kafka $DEV_KAFKA_RELEASE ready: ${ready:-absent}"
fi
exit "$failed"
