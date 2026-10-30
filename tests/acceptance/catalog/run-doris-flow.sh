#!/usr/bin/env bash
# Starts a one-FE, one-BE Doris 4.1.4 cluster (the images with the self-built Paimon 2.0.0 reader),
# connects it to colvenna-catalog with a bearer token and data tokens, runs the read-only checks in
# doris-checks.sql against the tables the Flink and Spark flows wrote, and removes the cluster.
# The Doris pods' AWS identity reaches no table data: native and JNI readers use vended tokens.
# Usage: run-doris-flow.sh <run-id> <database> <evidence-dir>
# DORIS_CHECKS runs another checks file of the same form; DORIS_EXPLAIN_TABLE names the append
# table whose splits must all go to the native reader (default: events). DORIS_FLOW_CPU is what the
# FE and the BE each request (default 2; their limit stays 4).
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run_id="${1:?usage: run-doris-flow.sh <run-id> <database> <evidence-dir>}"
database="${2:?usage: run-doris-flow.sh <run-id> <database> <evidence-dir>}"
evidence="${3:?usage: run-doris-flow.sh <run-id> <database> <evidence-dir>}"
# The run id names Kubernetes objects and goes into the cleanup's label selector: a DNS label only.
[[ "$run_id" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || { echo "run id $run_id is not a DNS label" >&2; exit 1; }
mkdir -p "$evidence"
sha="$(committed_sha)"
fe_image="$DORIS_FE_IMAGE"
be_image="$DORIS_BE_IMAGE"
mysql_image="$MYSQL_CLIENT_IMAGE"
role="$(stack_output "$CATALOG_STACK" DorisEngineRoleArn)"
name="doris-$run_id"
work="$(mktemp -d)"
chmod 700 "$work"

cleanup() {
  local status=$? claims left
  admin_close
  if [[ "${KEEP_DORIS:-false}" != true ]]; then
    kubectl -n "$DORIS_NAMESPACE" delete doriscluster "$name" --ignore-not-found --wait=true \
      >/dev/null 2>&1 || true
    # The Doris Operator labels a component's volume claims with the component's StatefulSet,
    # <name>-fe or <name>-be, not the cluster: the platform's colvenna-doris is never among them.
    kubectl -n "$DORIS_NAMESPACE" delete pvc -l "app.doris.ownerreference/name in ($name-fe,$name-be)" \
      --ignore-not-found >/dev/null 2>&1 || true
    kubectl -n "$DORIS_NAMESPACE" delete secret "$name-auth" "$name-sql" --ignore-not-found \
      >/dev/null 2>&1 || true
    kubectl -n "$DORIS_NAMESPACE" delete configmap "$name-fe" --ignore-not-found >/dev/null 2>&1 || true
    # Checked by name (<volume>-<name>-<component>-<ordinal>), not by the label the delete used.
    if ! claims="$(kubectl -n "$DORIS_NAMESPACE" get pvc -o name 2>&1)"; then
      echo "Doris $name: cannot confirm its volume claims are gone: $claims" >&2
      ((status != 0)) || status=1
    elif left="$(grep -E -- "-$name-(fe|be)-[0-9]+$" <<<"$claims")"; then
      echo "Doris $name left volume claims in $DORIS_NAMESPACE: $left" >&2
      ((status != 0)) || status=1
    fi
  fi
  rm -rf "$work"
  exit "$status"
}
trap cleanup EXIT

kubectl create namespace "$DORIS_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label namespace "$DORIS_NAMESPACE" --overwrite \
  pod-security.kubernetes.io/enforce=baseline colvenna-lake.io/catalog-client=true >/dev/null
kubectl -n "$DORIS_NAMESPACE" create serviceaccount doris --dry-run=client -o yaml \
  | kubectl apply -f - >/dev/null
kubectl -n "$DORIS_NAMESPACE" annotate serviceaccount doris --overwrite \
  "eks.amazonaws.com/role-arn=$role" >/dev/null
ensure_storage_class

# Trust store for the FE and BE JVMs: the JDK's public CAs plus the colvenna-catalog CA.
kubectl -n "$CATALOG_NAMESPACE" get secret colvenna-catalog-ca-key -o jsonpath='{.data.ca\.crt}' \
  | base64 -d >"$work/ca.crt"
cp "$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")/lib/security/cacerts" \
  "$work/truststore.p12"
chmod 600 "$work/truststore.p12"
keytool -importcert -noprompt -alias colvenna-catalog -file "$work/ca.crt" \
  -keystore "$work/truststore.p12" -storepass changeit >/dev/null
kubectl -n "$DORIS_NAMESPACE" create secret generic colvenna-catalog-truststore \
  --from-file=truststore.p12="$work/truststore.p12" --dry-run=client -o yaml \
  | kubectl apply -f - >/dev/null

# Root password: random, set through the FE's initial verifier, never logged.
openssl rand -hex 32 | tr -d '\n' >"$work/root-password"
verifier="$(python3 - "$work/root-password" <<'PY'
import hashlib, sys
password = open(sys.argv[1]).read().encode()
print("*" + hashlib.sha1(hashlib.sha1(password).digest()).hexdigest().upper())
PY
)"
kubectl -n "$DORIS_NAMESPACE" create secret generic "$name-auth" --type=kubernetes.io/basic-auth \
  --from-literal=username=root --from-file=password="$work/root-password" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
{
  cat "$REPO_DIR/deploy/scripts/doris/fe-default.conf"
  printf 'skip_localhost_auth_check = false\nenable_fqdn_mode = true\n'
  printf 'initial_root_password = %s\n' "$verifier"
} >"$work/fe.conf"
kubectl -n "$DORIS_NAMESPACE" create configmap "$name-fe" --from-file=fe.conf="$work/fe.conf" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

trust_opts="-Djavax.net.ssl.trustStore=/etc/colvenna-trust/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12"
cat >"$evidence/doris-cluster.yaml" <<YAML
apiVersion: doris.selectdb.com/v1
kind: DorisCluster
metadata:
  name: $name
  annotations:
    colvenna-lake/source-sha: "$sha"
spec:
  authSecret: $name-auth
  feSpec:
    replicas: 1
    image: $fe_image
    serviceAccount: doris
    nodeSelector: {colvenna-lake/role: query}
    requests: {cpu: "${DORIS_FLOW_CPU:-2}", memory: 10Gi}
    limits: {cpu: "4", memory: 12Gi}
    configMapInfo: {configMapName: $name-fe}
    envVars:
      - {name: JAVA_TOOL_OPTIONS, value: "$trust_opts"}
    secrets:
      - {secretName: colvenna-catalog-truststore, mountPath: /etc/colvenna-trust}
    persistentVolumes:
      - name: fe-meta
        mountPath: /opt/apache-doris/fe/doris-meta
        persistentVolumeClaimSpec:
          storageClassName: colvenna-gp3
          accessModes: [ReadWriteOnce]
          resources: {requests: {storage: 10Gi}}
    service: {type: ClusterIP}
  beSpec:
    replicas: 1
    image: $be_image
    skipDefaultSystemInit: true
    serviceAccount: doris
    nodeSelector: {colvenna-lake/role: query}
    requests: {cpu: "${DORIS_FLOW_CPU:-2}", memory: 12Gi}
    limits: {cpu: "4", memory: 16Gi}
    envVars:
      - {name: JAVA_TOOL_OPTIONS, value: "$trust_opts"}
    secrets:
      - {secretName: colvenna-catalog-truststore, mountPath: /etc/colvenna-trust}
    service: {type: ClusterIP}
YAML
kubectl -n "$DORIS_NAMESPACE" apply -f "$evidence/doris-cluster.yaml" >/dev/null
for component in fe be; do
  ready=false
  for _ in $(seq 1 120); do
    if grep -q true <<<"$(kubectl -n "$DORIS_NAMESPACE" get pod "$name-$component-0" \
      -o jsonpath='{.status.containerStatuses[0].ready}' 2>/dev/null)"; then
      ready=true
      break
    fi
    [[ "$component" != be ]] || touch_doris_without_be "$DORIS_NAMESPACE" "$name"
    sleep 10
  done
  kubectl -n "$DORIS_NAMESPACE" get pod "$name-$component-0" -o wide >>"$evidence/pods.txt"
  if [[ "$ready" != true ]]; then
    doris_touch_report "$name" | tee "$evidence/doris-touches.txt"
    echo "Doris $component did not become ready" >&2
    exit 1
  fi
done
doris_touch_report "$name" | tee "$evidence/doris-touches.txt"

admin_open
ensure_principal_token doris "$DORIS_NAMESPACE" doris-token P2D
# Doris only reads: its credentials can read the tables and write nothing but consumer progress.
ensure_grant doris DATABASE "$database" - READ
token="$(kubectl -n "$DORIS_NAMESPACE" get secret doris-token -o jsonpath='{.data.token}' | base64 -d)"
{
  printf "CREATE CATALOG colvenna PROPERTIES ('type'='paimon', 'paimon.catalog.type'='rest',"
  printf " 'uri'='https://colvenna-catalog.%s.svc:8181', 'warehouse'='colvenna'," "$CATALOG_NAMESPACE"
  printf " 'paimon.rest.token.provider'='bear', 'paimon.rest.token'='%s'," "$token"
  printf " 'paimon.rest.data-token.enabled'='true');\n"
} >"$work/catalog.sql"
sed "s/@DB@/$database/g" "${DORIS_CHECKS:-$REPO_DIR/tests/acceptance/catalog/doris-checks.sql}" >"$work/checks.sql"
kubectl -n "$DORIS_NAMESPACE" create secret generic "$name-sql" \
  --from-file=catalog.sql="$work/catalog.sql" --from-file=checks.sql="$work/checks.sql" \
  --from-file=password="$work/root-password" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# run_checks <label> <first: true|false>: runs checks.sql (after creating the catalog the first
# time) from a client pod and judges the CHECK lines and the native-reader split count.
run_checks() {
  local label="$1" first="$2" create=""
  if [[ "$first" == true ]]; then
    create="q < /sql/catalog.sql"
  fi
  cat >"$work/client.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata:
  name: $name-$label
spec:
  restartPolicy: Never
  securityContext:
    runAsNonRoot: true
    runAsUser: 999
    seccompProfile: {type: RuntimeDefault}
  containers:
    - name: mysql
      image: $mysql_image
      securityContext:
        allowPrivilegeEscalation: false
        capabilities: {drop: ["ALL"]}
      command: ["sh", "-ec"]
      args:
        - |
          export MYSQL_PWD="\$(cat /sql/password)"
          q() { mysql -h $name-fe-service -P 9030 -uroot --batch --skip-column-names "\$@"; }
          $create
          q < /sql/checks.sql
          echo EXPLAIN-BEGIN
          q -e "EXPLAIN VERBOSE SELECT * FROM colvenna.$database.${DORIS_EXPLAIN_TABLE:-events} WHERE id >= 0"
          echo EXPLAIN-END
      volumeMounts:
        - {name: sql, mountPath: /sql, readOnly: true}
  volumes:
    - name: sql
      secret: {secretName: $name-sql}
YAML
  run_pod "$DORIS_NAMESPACE" "$name-$label" "$work/client.yaml" >"$evidence/$label.out"
  python3 - "$evidence/$label.out" "$evidence/$label.txt" <<'PY'
import re, sys
lines = [l.rstrip("\n") for l in open(sys.argv[1]) if l.strip()]
out, failed = [], False
# The native reader must take every split of the append table, on the vended credentials only.
explain = "\n".join(lines[lines.index("EXPLAIN-BEGIN") + 1:lines.index("EXPLAIN-END")])
native = re.search(r"paimonNativeReadSplits=(\d+)/(\d+)", explain)
ok = bool(native) and int(native.group(1)) > 0 and native.group(1) == native.group(2)
failed |= not ok
out.append(f"CHECK native-reader-splits expected=all actual={native.group(0) if native else 'none'} "
           f"{'OK' if ok else 'FAIL'}")
for line in lines:
    parts = line.split("\t")
    if len(parts) == 3 and parts[0].startswith("CHECK "):
        ok = parts[1] == parts[2] or (parts[1] == ">0" and parts[2].isdigit() and int(parts[2]) > 0)
        failed |= not ok
        out.append(f"{parts[0]} expected={parts[1]} actual={parts[2]} {'OK' if ok else 'FAIL'}")
open(sys.argv[2], "w").write("\n".join(out) + "\n")
print("\n".join(out))
sys.exit(1 if failed or not out else 0)
PY
}

run_checks checks true
# Data-token renewal: the same checks after the first data tokens have expired, and with
# DORIS_RECHECK_EVERY_MINUTES also every that many minutes in between, as an active client.
if [[ -n "${DORIS_RECHECK_AFTER_MINUTES:-}" ]]; then
  end=$((SECONDS + DORIS_RECHECK_AFTER_MINUTES * 60))
  round=0
  if [[ -n "${DORIS_RECHECK_EVERY_MINUTES:-}" ]]; then
    while ((SECONDS + DORIS_RECHECK_EVERY_MINUTES * 60 < end)); do
      sleep "$((DORIS_RECHECK_EVERY_MINUTES * 60))"
      round=$((round + 1))
      run_checks "active-$round" false
    done
  fi
  sleep "$((end > SECONDS ? end - SECONDS : 0))"
  run_checks recheck false
fi
echo "Doris flow PASS ($database)"
