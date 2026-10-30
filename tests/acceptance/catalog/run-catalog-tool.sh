#!/usr/bin/env bash
# Runs one catalog tool against colvenna-catalog (mode rest), as a Kubernetes Job, as the
# catalog-migration identity with data tokens (READ on the database, and WRITE on the named tables
# for the write probe).
# Tools: dump (CatalogStateDump; writes <evidence>/<mode>.state, one JSON object per table), history
# (SnapshotHistory; writes <evidence>/<mode>.history, one line per snapshot) and write (WriteProbe;
# one row per table, a PROBE line each in <evidence>/probes.txt). Fails if the
# tool fails. For checks that the catalog refuses something, EXPECT_FAILURE=<extended regex> inverts
# that: the tool must fail, and with the given reason in its log; any other failure still fails.
# Usage: run-catalog-tool.sh rest <evidence-dir> <tool> <database> <table>...
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
usage="usage: run-catalog-tool.sh rest <evidence-dir> <tool> <database> <table>..."
mode="${1:?$usage}"
evidence="${2:?$usage}"
tool="${3:?$usage}"
database="${4:?$usage}"
shift 4
tables=("$@")
case "$mode" in
  rest) ;;
  *) echo "$usage" >&2; exit 1 ;;
esac
case "$tool" in
  dump) java_args=(CatalogStateDump "$mode" "$database" "${tables[@]}") ;;
  history) java_args=(SnapshotHistory "$mode" "$database" "${tables[@]}") ;;
  write) java_args=(WriteProbe "$mode" "$database" "${tables[@]}") ;;
  *) echo "Unknown tool $tool" >&2; exit 1 ;;
esac
mkdir -p "$evidence"
sha="$(committed_sha)"
name="catalog-$tool-$(date -u +%H%M%S)"
mvn -B -q -f "$REPO_DIR/tests/acceptance/catalog/migration/pom.xml" -DskipTests clean package
jar="$REPO_DIR/tests/acceptance/catalog/migration/target/catalog-migration-0.1.0.jar"

# colvenna-catalog as catalog-migration: a bearer token, the catalog CA in a trust store, data tokens.
namespace="$ENGINE_NAMESPACE"
sa=test-flink
prepare_engine_namespace test-flink "$(stack_output "$CATALOG_STACK" FlinkEngineRoleArn)"
trap admin_close EXIT
admin_open
ensure_principal_token catalog-migration "$namespace" catalog-migration-token P1D
ensure_grant catalog-migration DATABASE "$database" - READ
if [[ "$tool" == write ]]; then
  for t in "${tables[@]}"; do
    GRANT_PURPOSE="catalog write probe" ensure_grant catalog-migration TABLE "$database" "$t" WRITE
  done
fi
env_yaml="
            - {name: COLVENNA_CATALOG_URI, value: \"https://colvenna-catalog.$CATALOG_NAMESPACE.svc:8181\"}
            - {name: COLVENNA_CATALOG_TOKEN_FILE, value: /token/token}"
init_yaml="
      initContainers:
        - name: truststore
          image: $FLINK_IMAGE
          securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: [\"ALL\"]}}
          command: [\"sh\", \"-ec\"]
          args:
            - cp \"\$JAVA_HOME/lib/security/cacerts\" /trust/cacerts && chmod 600 /trust/cacerts && keytool -importcert -noprompt -alias colvenna-catalog -file /ca/ca.crt -keystore /trust/cacerts -storepass changeit
          volumeMounts:
            - {name: ca, mountPath: /ca}
            - {name: trust, mountPath: /trust}"
rest_mounts="
            - {name: token, mountPath: /token, readOnly: true}
            - {name: trust, mountPath: /trust, readOnly: true}"
rest_volumes="
        - name: token
          secret: {secretName: catalog-migration-token}
        - name: ca
          configMap: {name: colvenna-catalog-ca}"
trust_args="-Djavax.net.ssl.trustStore=/trust/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
kubectl -n "$namespace" create configmap "$name" --from-file=migration.jar="$jar" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
cat >"$evidence/$mode-$tool-job.yaml" <<YAML
apiVersion: batch/v1
kind: Job
metadata:
  name: $name
  namespace: $namespace
  annotations:
    colvenna-lake/source-sha: "$sha"
spec:
  backoffLimit: 0
  activeDeadlineSeconds: 3600
  template:
    spec:
      restartPolicy: Never
      serviceAccountName: $sa
      securityContext:
        runAsNonRoot: true
        runAsUser: 9999
        runAsGroup: 9999
        seccompProfile: {type: RuntimeDefault}$init_yaml
      containers:
        - name: tool
          image: $FLINK_IMAGE
          securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
          command: ["sh", "-c"]
          args:
            - exec java -Xmx6g -XX:ActiveProcessorCount=2 $trust_args -cp "/opt/migration/migration.jar:/opt/flink/lib/*" com.colvenna.lake.acceptance.migration.${java_args[*]}
          env:$env_yaml
          resources:
            requests: {cpu: "2", memory: 7Gi}
            limits: {memory: 7Gi}
          volumeMounts:
            - {name: jar, mountPath: /opt/migration, readOnly: true}$rest_mounts
      volumes:
        - name: jar
          configMap: {name: $name}
        - name: trust
          emptyDir: {}$rest_volumes
YAML
kubectl apply -f "$evidence/$mode-$tool-job.yaml" >/dev/null
status=0
wait_job "$namespace" "$name" 3600 || status=1
log="$evidence/$mode-$tool.log"
kubectl -n "$namespace" logs "job/$name" -c tool >"$log" 2>&1 || true
if [[ "$tool" == dump ]]; then
  grep '^STATE ' "$log" | sed 's/^STATE //' >"$evidence/$mode.state" || true
elif [[ "$tool" == history ]]; then
  grep '^HISTORY [^ ]* [0-9]' "$log" | sed 's/^HISTORY //' >"$evidence/$mode.history" || true
fi
grep '^PROBE ' "$log" >>"$evidence/probes.txt" || true
kubectl -n "$namespace" delete job "$name" --wait=false >/dev/null 2>&1 || true
kubectl -n "$namespace" delete configmap "$name" >/dev/null 2>&1 || true
if [[ -n "${EXPECT_FAILURE:-}" ]]; then
  if [[ "$status" == 0 ]]; then
    echo "The $mode catalog accepted $tool on ${tables[*]}" >&2
    exit 1
  fi
  reason="$(grep -m1 -oE "$EXPECT_FAILURE" "$log" || true)"
  if [[ -z "$reason" ]]; then
    echo "$tool ($mode) failed, but not with /$EXPECT_FAILURE/; see $log" >&2
    exit 1
  fi
  echo "$mode catalog refused $tool on ${tables[*]}: $reason"
  exit 0
fi
[[ "$status" == 0 ]] || { echo "$tool ($mode) failed; see $log" >&2; exit 1; }
echo "$tool ($mode) on ${tables[*]} done; see $log"
