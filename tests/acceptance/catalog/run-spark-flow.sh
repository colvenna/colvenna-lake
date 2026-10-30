#!/usr/bin/env bash
# Runs the Spark catalog flow (SparkCatalogFlow) in local mode as a Kubernetes Job against
# colvenna-catalog, on the tables the Flink flow created. The pod's AWS identity reaches no table data.
# Usage: run-spark-flow.sh <run-id> <database> <evidence-dir>
# FLOW_CLASS and FLOW_ARGS run another flow class of the same jar (default: SparkCatalogFlow on
# <database>); FLOW_DEADLINE is the Job deadline in seconds (default 1800); FLOW_GRANTS replaces
# the flow's default grant (see ensure_flow_grants); SPARK_FLOW_CPU is the pod's CPU request
# (default 2); SPARK_FLOW_NODE_ROLE puts it on nodes of that colvenna-lake/role
# (default any).
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run_id="${1:?usage: run-spark-flow.sh <run-id> <database> <evidence-dir>}"
database="${2:?usage: run-spark-flow.sh <run-id> <database> <evidence-dir>}"
evidence="${3:?usage: run-spark-flow.sh <run-id> <database> <evidence-dir>}"
mkdir -p "$evidence"
sha="$(committed_sha)"
flow_class="${FLOW_CLASS:-com.colvenna.lake.acceptance.flow.SparkCatalogFlow}"
read -r -a flow_args <<<"${FLOW_ARGS:-$database}"
deadline="${FLOW_DEADLINE:-1800}"
node_selector=""
[[ -z "${SPARK_FLOW_NODE_ROLE:-}" ]] || node_selector="nodeSelector: {colvenna-lake/role: $SPARK_FLOW_NODE_ROLE}"
image="$(cat "$STATE_DIR/spark-image-ref.txt")"
role="$(stack_output "$CATALOG_STACK" SparkEngineRoleArn)"

mvn -B -q -f "$REPO_DIR/tests/acceptance/catalog/spark/pom.xml" -DskipTests clean package
jar="$REPO_DIR/tests/acceptance/catalog/spark/target/spark-catalog-flow-0.1.0.jar"
sha256sum "$jar" >"$evidence/flow-jar.sha256"
prepare_engine_namespace test-spark "$role"
kubectl -n "$ENGINE_NAMESPACE" create configmap "spark-flow-$run_id" --from-file=flow.jar="$jar" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

admin_open
trap admin_close EXIT
ensure_principal_token test-spark "$ENGINE_NAMESPACE" test-spark-token P2D
ensure_flow_grants test-spark "DATABASE ${flow_args[0]} - MANAGE"

job="spark-flow-$run_id"
cat >"$evidence/job.yaml" <<YAML
apiVersion: batch/v1
kind: Job
metadata:
  name: $job
  annotations:
    colvenna-lake/source-sha: "$sha"
spec:
  backoffLimit: 0
  activeDeadlineSeconds: $deadline
  template:
    spec:
      restartPolicy: Never
      serviceAccountName: test-spark
      $node_selector
      securityContext:
        runAsNonRoot: true
        runAsUser: 185
        runAsGroup: 185
        seccompProfile: {type: RuntimeDefault}
      initContainers:
        - name: truststore
          image: $image
          securityContext:
            allowPrivilegeEscalation: false
            capabilities: {drop: ["ALL"]}
          command: ["sh", "-ec"]
          args:
            - |
              cp "\$JAVA_HOME/lib/security/cacerts" /trust/cacerts
              chmod 600 /trust/cacerts
              keytool -importcert -noprompt -alias colvenna-catalog -file /ca/ca.crt \
                -keystore /trust/cacerts -storepass changeit
          volumeMounts:
            - {name: ca, mountPath: /ca}
            - {name: trust, mountPath: /trust}
      containers:
        - name: flow
          image: $image
          securityContext:
            allowPrivilegeEscalation: false
            capabilities: {drop: ["ALL"]}
          command:
            - /opt/spark/bin/spark-submit
            - --master
            - local[4]
            - --driver-memory
            - 4g
            - --driver-java-options
            - -Djavax.net.ssl.trustStore=/trust/cacerts -Djavax.net.ssl.trustStorePassword=changeit
            - --conf
            - spark.ui.enabled=false
            - --conf
            - spark.local.dir=/tmp/spark
            - --class
            - $flow_class
            - /opt/flow/flow.jar
$(printf '            - "%s"\n' "${flow_args[@]}")
          env:
            - {name: COLVENNA_CATALOG_URI, value: "https://colvenna-catalog.$CATALOG_NAMESPACE.svc:8181"}
            - {name: COLVENNA_CATALOG_TOKEN_FILE, value: /token/token}
            - {name: SPARK_USER, value: spark}
            - {name: HOME, value: /tmp}
          resources:
            requests: {cpu: "${SPARK_FLOW_CPU:-2}", memory: 6Gi}
            limits: {memory: 6Gi}
          volumeMounts:
            - {name: flow, mountPath: /opt/flow, readOnly: true}
            - {name: token, mountPath: /token, readOnly: true}
            - {name: trust, mountPath: /trust, readOnly: true}
            - {name: tmp, mountPath: /tmp}
      volumes:
        - name: flow
          configMap: {name: spark-flow-$run_id}
        - name: token
          secret: {secretName: test-spark-token}
        - name: ca
          configMap: {name: colvenna-catalog-ca}
        - name: trust
          emptyDir: {}
        - name: tmp
          emptyDir: {}
YAML
kubectl -n "$ENGINE_NAMESPACE" apply -f "$evidence/job.yaml" >/dev/null
if ! wait_job "$ENGINE_NAMESPACE" "$job" "$deadline"; then
  kubectl -n "$ENGINE_NAMESPACE" logs "job/$job" --all-containers >"$evidence/flow.log" 2>&1 || true
  echo "Spark flow failed; see $evidence/flow.log" >&2
  exit 1
fi
kubectl -n "$ENGINE_NAMESPACE" logs "job/$job" >"$evidence/flow.log"
grep '^STEP ' "$evidence/flow.log" >"$evidence/steps.txt"
grep -q "^FLOW PASS ${flow_args[0]}" "$evidence/flow.log"
echo "Spark flow PASS ($database)"
