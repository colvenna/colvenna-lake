#!/usr/bin/env bash
# Runs the Flink catalog flow (FlinkCatalogFlow) as a Kubernetes Job against colvenna-catalog.
# The pod's own AWS identity reaches no table data; every table file access uses the per-table
# credentials colvenna-catalog vends. Usage: run-flink-flow.sh <run-id> <evidence-dir>
# FLOW_CLASS and FLOW_ARGS run another flow class of the same jar (default: FlinkCatalogFlow on
# the run's database); FLOW_DEADLINE is the Job deadline in seconds (default 1800); FLOW_GRANTS
# replaces the flow's default grant (see ensure_flow_grants).
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run_id="${1:?usage: run-flink-flow.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-flink-flow.sh <run-id> <evidence-dir>}"
mkdir -p "$evidence"
sha="$(committed_sha)"
database="flow_${run_id//-/_}"
flow_class="${FLOW_CLASS:-com.colvenna.lake.acceptance.flow.FlinkCatalogFlow}"
read -r -a flow_args <<<"${FLOW_ARGS:-$database}"
deadline="${FLOW_DEADLINE:-1800}"
image="$FLINK_IMAGE"
role="$(stack_output "$CATALOG_STACK" FlinkEngineRoleArn)"

mvn -B -q -f "$REPO_DIR/tests/acceptance/catalog/flink/pom.xml" -DskipTests clean package
jar="$REPO_DIR/tests/acceptance/catalog/flink/target/flink-catalog-flow-0.1.0.jar"
sha256sum "$jar" >"$evidence/flow-jar.sha256"

prepare_engine_namespace test-flink "$role"
kubectl -n "$ENGINE_NAMESPACE" create configmap "flink-flow-$run_id" --from-file=flow.jar="$jar" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

admin_open
trap admin_close EXIT
ensure_principal_token test-flink "$ENGINE_NAMESPACE" test-flink-token P2D
# Least privilege: the flow owns its database and nothing else, unless FLOW_GRANTS says otherwise.
ensure_flow_grants test-flink "DATABASE ${flow_args[0]} - MANAGE"

job="flink-flow-$run_id"
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
      serviceAccountName: test-flink
      securityContext:
        runAsNonRoot: true
        runAsUser: 9999
        runAsGroup: 9999
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
            - java
            - -Xmx4g
            # The pod's CPU request, not the node's: the local cluster sizes its parallelism, and so
            # its S3 upload buffers, from the CPU count.
            - -XX:ActiveProcessorCount=2
            - -Dlog4j.configurationFile=/opt/flink/conf/log4j-console.properties
            - -Djavax.net.ssl.trustStore=/trust/cacerts
            - -Djavax.net.ssl.trustStorePassword=changeit
            - -cp
            - "/opt/flow/flow.jar:/opt/flink/lib/*:/opt/flink/usrlib/*"
            - $flow_class
$(printf '            - "%s"\n' "${flow_args[@]}")
          env:
            - {name: COLVENNA_CATALOG_URI, value: "https://colvenna-catalog.$CATALOG_NAMESPACE.svc:8181"}
            - {name: COLVENNA_CATALOG_TOKEN_FILE, value: /token/token}
          resources:
            requests: {cpu: "2", memory: 6Gi}
            limits: {memory: 6Gi}
          volumeMounts:
            - {name: flow, mountPath: /opt/flow, readOnly: true}
            - {name: token, mountPath: /token, readOnly: true}
            - {name: trust, mountPath: /trust, readOnly: true}
            - {name: tmp, mountPath: /tmp}
      volumes:
        - name: flow
          configMap: {name: flink-flow-$run_id}
        - name: token
          secret: {secretName: test-flink-token}
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
  echo "Flink flow failed; see $evidence/flow.log" >&2
  exit 1
fi
kubectl -n "$ENGINE_NAMESPACE" logs "job/$job" >"$evidence/flow.log"
grep '^STEP ' "$evidence/flow.log" >"$evidence/steps.txt"
grep -q "^FLOW PASS ${flow_args[0]}" "$evidence/flow.log"
echo "$database" >"$evidence/database.txt"
echo "Flink flow PASS ($database)"
