#!/usr/bin/env bash
# Runs a flow class of the Flink flow jar on a Flink session cluster (FlinkDeployment, Flink
# Kubernetes Operator) instead of in one JVM. A client Job submits it with `flink run`, so the
# flow's main method runs in the client while table files are written by TaskManager pods, which
# fetch and renew their own data tokens from colvenna-catalog. Restarts are disabled on the cluster.
# Usage: run-flink-deployment.sh <run-id> <evidence-dir> <flow-class> [<flow-argument>...]
# Optional: FLOW_DEADLINE in seconds (default 1800), PARALLELISM (default 2), FLOW_CLIENT_CPU (the
# client Job's CPU request, default 1), FLOW_JM_CPU and FLOW_TM_CPU (the session cluster's, default
# 1 each), FLOW_NODE_ROLE (nodes of that colvenna-lake/role only; default any).
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run_id="${1:?usage: run-flink-deployment.sh <run-id> <evidence-dir> <class> [args...]}"
evidence="${2:?usage: run-flink-deployment.sh <run-id> <evidence-dir> <class> [args...]}"
flow_class="${3:?usage: run-flink-deployment.sh <run-id> <evidence-dir> <class> [args...]}"
shift 3
flow_args=("$@")
deadline="${FLOW_DEADLINE:-1800}"
parallelism="${PARALLELISM:-2}"
# FLOW_NODE_ROLE pins the cluster and its client to nodes of that role (colvenna-lake/role).
node_selector=""
[[ -z "${FLOW_NODE_ROLE:-}" ]] || node_selector="nodeSelector: {colvenna-lake/role: $FLOW_NODE_ROLE}"
mkdir -p "$evidence"
sha="$(committed_sha)"
name="test-flink-$run_id"

mvn -B -q -f "$REPO_DIR/tests/acceptance/catalog/flink/pom.xml" -DskipTests clean package
jar="$REPO_DIR/tests/acceptance/catalog/flink/target/flink-catalog-flow-0.1.0.jar"
sha256sum "$jar" >"$evidence/flow-jar.sha256"
prepare_engine_namespace test-flink "$(stack_output "$CATALOG_STACK" FlinkEngineRoleArn)"
kubectl -n "$ENGINE_NAMESPACE" create configmap "$name-jar" --from-file=flow.jar="$jar" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
prepare_flink_runtime

# shellcheck disable=SC2317  # invoked through the EXIT trap
cleanup() {
  admin_close
  if [[ "${KEEP_FLINK:-false}" != true ]]; then
    kubectl -n "$ENGINE_NAMESPACE" delete job "$name-client" --ignore-not-found >/dev/null 2>&1 || true
    kubectl -n "$ENGINE_NAMESPACE" delete flinkdeployment "$name" --ignore-not-found \
      --wait=true >/dev/null 2>&1 || true
    kubectl -n "$ENGINE_NAMESPACE" delete configmap "$name-jar" --ignore-not-found >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
admin_open
ensure_principal_token test-flink "$ENGINE_NAMESPACE" test-flink-token P2D
# Least privilege: the flow owns its database and nothing else.
ensure_grant test-flink DATABASE "${flow_args[0]}" - MANAGE

trust_opts="-Djavax.net.ssl.trustStore=/trust/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
truststore_init="
      - name: truststore
        image: $FLINK_IMAGE
        securityContext:
          allowPrivilegeEscalation: false
          capabilities: {drop: [\"ALL\"]}
        command: [\"sh\", \"-ec\"]
        args:
          - |
            cp \"\$JAVA_HOME/lib/security/cacerts\" /trust/cacerts
            chmod 600 /trust/cacerts
            keytool -importcert -noprompt -alias colvenna-catalog -file /ca/ca.crt -keystore /trust/cacerts -storepass changeit
        volumeMounts:
          - {name: ca, mountPath: /ca}
          - {name: trust, mountPath: /trust}"
cat >"$evidence/flink-session.yaml" <<YAML
apiVersion: flink.apache.org/v1beta1
kind: FlinkDeployment
metadata:
  name: $name
  namespace: $ENGINE_NAMESPACE
  annotations:
    colvenna-lake/source-sha: "$sha"
spec:
  image: $FLINK_IMAGE
  flinkVersion: v1_20
  serviceAccount: test-flink
  flinkConfiguration:
    taskmanager.numberOfTaskSlots: "1"
    restart-strategy.type: none
    # Paimon caches its S3 FileIO across jobs of a session cluster, so its HTTP threads keep the
    # first job's (closed) class loader as context class loader; commons-logging then looks up a
    # resource through it. The lookup still works; Flink's safety net would fail the job.
    classloader.check-leaked-classloader: "false"
    kubernetes.rest-service.exposed.type: ClusterIP
    web.submit.enable: "false"
    web.cancel.enable: "false"
    web.rescale.enable: "false"
    env.java.opts.all: "$trust_opts"
  jobManager:
    resource: {cpu: ${FLOW_JM_CPU:-1}, memory: "2048m"}
  taskManager:
    resource: {cpu: ${FLOW_TM_CPU:-1}, memory: "3072m"}
  podTemplate:
    spec:
      $node_selector
      securityContext:
        runAsNonRoot: true
        runAsUser: 9999
        runAsGroup: 9999
        seccompProfile: {type: RuntimeDefault}
      initContainers:$truststore_init
      containers:
        - name: flink-main-container
          securityContext:
            allowPrivilegeEscalation: false
            capabilities: {drop: ["ALL"]}
          volumeMounts:
            - {name: trust, mountPath: /trust, readOnly: true}
      volumes:
        - name: ca
          configMap: {name: colvenna-catalog-ca}
        - name: trust
          emptyDir: {}
YAML
kubectl apply -f "$evidence/flink-session.yaml" >/dev/null
for _ in $(seq 1 60); do
  if [[ "$(kubectl -n "$ENGINE_NAMESPACE" get flinkdeployment "$name" \
    -o jsonpath='{.status.jobManagerDeploymentStatus}' 2>/dev/null)" == READY ]]; then
    break
  fi
  sleep 10
done
if [[ "$(kubectl -n "$ENGINE_NAMESPACE" get flinkdeployment "$name" \
  -o jsonpath='{.status.jobManagerDeploymentStatus}')" != READY ]]; then
  echo "Flink session cluster $name did not become ready" >&2
  exit 1
fi

args_yaml="$(printf '            - "%s"\n' "${flow_args[@]}")"
cat >"$evidence/client-job.yaml" <<YAML
apiVersion: batch/v1
kind: Job
metadata:
  name: $name-client
  namespace: $ENGINE_NAMESPACE
  annotations:
    colvenna-lake/source-sha: "$sha"
spec:
  backoffLimit: 0
  activeDeadlineSeconds: $deadline
  template:
    spec:
      restartPolicy: Never
      serviceAccountName: test-flink
      $node_selector
      securityContext:
        runAsNonRoot: true
        runAsUser: 9999
        runAsGroup: 9999
        seccompProfile: {type: RuntimeDefault}
      initContainers:$truststore_init
      containers:
        - name: client
          image: $FLINK_IMAGE
          securityContext:
            allowPrivilegeEscalation: false
            capabilities: {drop: ["ALL"]}
          env:
            - {name: COLVENNA_CATALOG_URI, value: "https://colvenna-catalog.$CATALOG_NAMESPACE.svc:8181"}
            - {name: COLVENNA_CATALOG_TOKEN_FILE, value: /token/token}
            - {name: FLINK_ENV_JAVA_OPTS, value: "$trust_opts"}
          command:
            - /opt/flink/bin/flink
            - run
            - -m
            - $name-rest:8081
            - -p
            - "$parallelism"
            - -c
            - $flow_class
            - /opt/flow/flow.jar
$args_yaml
          resources:
            requests: {cpu: "${FLOW_CLIENT_CPU:-1}", memory: 2Gi}
            limits: {memory: 2Gi}
          volumeMounts:
            - {name: flow, mountPath: /opt/flow, readOnly: true}
            - {name: token, mountPath: /token, readOnly: true}
            - {name: trust, mountPath: /trust, readOnly: true}
            - {name: tmp, mountPath: /tmp}
      volumes:
        - name: flow
          configMap: {name: $name-jar}
        - name: token
          secret: {secretName: test-flink-token}
        - name: ca
          configMap: {name: colvenna-catalog-ca}
        - name: trust
          emptyDir: {}
        - name: tmp
          emptyDir: {}
YAML
kubectl apply -f "$evidence/client-job.yaml" >/dev/null
status=0
wait_job "$ENGINE_NAMESPACE" "$name-client" "$deadline" || status=1
kubectl -n "$ENGINE_NAMESPACE" logs "job/$name-client" -c client >"$evidence/client.log" 2>&1 || true
for pod in $(kubectl -n "$ENGINE_NAMESPACE" get pods -l "app=$name" -o jsonpath='{.items[*].metadata.name}'); do
  kubectl -n "$ENGINE_NAMESPACE" logs "$pod" -c flink-main-container >"$evidence/$pod.log" 2>&1 || true
done
kubectl -n "$ENGINE_NAMESPACE" get pods -l "app=$name" -o wide >"$evidence/pods.txt" 2>&1 || true
grep -E '^(STEP|PROGRESS|RESULT|FLOW) ' "$evidence/client.log" >"$evidence/steps.txt" || true
if [[ "$status" != 0 ]] || ! grep -q "^FLOW PASS ${flow_args[0]}" "$evidence/steps.txt"; then
  echo "Flink session run $name did not pass; see $evidence/client.log" >&2
  exit 1
fi
echo "Flink session run PASS (${flow_args[0]})"
