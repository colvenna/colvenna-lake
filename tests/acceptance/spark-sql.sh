#!/usr/bin/env bash
# Runs a SQL file with spark-sql (Spark 3.5.8, local mode) in the readers' namespace, as an
# external reader does:
#   - catalog `open`: Polaris's colvenna_open over Iceberg REST, as the Polaris principal whose
#     client-id and client-secret are in the Secret given by OPEN_SECRET (in the readers'
#     namespace), with vended credentials;
#   - catalog `paimon`: colvenna-catalog natively, as the principal whose token is in the Secret given
#     by PAIMON_SECRET (key token), when set.
# The pod has no AWS identity of its own: it reaches table data only with vended credentials.
# Each statement's rows follow a line "@@ <n>"; a failed statement leaves "@@ <n> ERROR <message>",
# then the end of its error output, each line after "@@# ", and the next ones still run (spark-sql
# stops at the first error, so each runs by itself).
# usage: spark-sql.sh <out-file> <sql-file>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=common.sh
source "$(dirname "$0")/common.sh"
# shellcheck source=../../deploy/scripts/lib/polaris-admin.sh
source "$REPO_DIR/deploy/scripts/lib/polaris-admin.sh"
assert_account
ensure_kubeconfig
out="${1:?usage: spark-sql.sh <out-file> <sql-file>}"
sql="${2:?usage: spark-sql.sh <out-file> <sql-file>}"
READER_NAMESPACE="${READER_NAMESPACE:-colvenna-readers}"
# The reader images of this SHA, or the newest built (they change with their jars, not the tree).
readers="$STATE_DIR/readers-$(git -C "$REPO_DIR" rev-parse HEAD).json"
if [[ ! -f "$readers" ]]; then
  readers="$(find "$STATE_DIR" -regextype posix-extended \
    -regex ".*/readers-[0-9a-f]{40}\.json" -printf '%T@ %p\n' | sort -n | tail -1 \
    | cut -d' ' -f2-)"
fi
image="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["spark"])' "$readers")"
pod="spark-sql-$RANDOM"
work="$(mktemp -d)"
chmod 700 "$work"
trap 'kubectl -n "$READER_NAMESPACE" delete pod "$pod" --ignore-not-found >/dev/null 2>&1 || true; kubectl -n "$READER_NAMESPACE" delete configmap "$pod" --ignore-not-found >/dev/null 2>&1 || true; rm -rf "$work"' EXIT

kubectl create namespace "$READER_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label namespace "$READER_NAMESPACE" --overwrite pod-security.kubernetes.io/enforce=restricted \
  colvenna-lake.io/catalog-client=true "$POLARIS_CLIENT_LABEL=true" >/dev/null
kubectl -n "$CATALOG_NAMESPACE" get secret colvenna-catalog-ca-key -o jsonpath='{.data.ca\.crt}' \
  | base64 -d >"$work/ca.crt"
kubectl -n "$READER_NAMESPACE" create configmap colvenna-ca --from-file=ca.crt="$work/ca.crt" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
# One statement per spark-sql run: statements end with ";" at the end of a line.
python3 - "$sql" "$work/statements" <<'PY'
import os, sys
text = open(sys.argv[1]).read()
os.makedirs(sys.argv[2])
statements, current = [], []
for line in text.splitlines():
    if line.strip().startswith("--") or not line.strip():
        continue
    current.append(line)
    if line.rstrip().endswith(";"):
        statements.append("\n".join(current))
        current = []
if current:
    statements.append("\n".join(current) + ";")
for i, s in enumerate(statements, 1):
    open(os.path.join(sys.argv[2], "%03d.sql" % i), "w").write(s + "\n")
PY
kubectl -n "$READER_NAMESPACE" create configmap "$pod" --from-file="$work/statements" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

secret_volumes="" secret_mounts=""
if [[ -n "${OPEN_SECRET:-}" ]]; then
  secret_volumes+="    - {name: open, secret: {secretName: $OPEN_SECRET}}"$'\n'
  secret_mounts+="        - {name: open, mountPath: /open, readOnly: true}"$'\n'
fi
if [[ -n "${PAIMON_SECRET:-}" ]]; then
  secret_volumes+="    - {name: paimon, secret: {secretName: $PAIMON_SECRET}}"$'\n'
  secret_mounts+="        - {name: paimon, mountPath: /paimon, readOnly: true}"$'\n'
fi
cat >"$work/pod.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $pod}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  nodeSelector: {colvenna-lake/role: query}
  securityContext: {runAsNonRoot: true, runAsUser: 185, runAsGroup: 185, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: spark
      image: $image
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      env:
        - {name: HOME, value: /tmp}
        - {name: AWS_REGION, value: $AWS_REGION}
      command: ["bash", "-c"]
      args:
        - |
          set -u
          cp "\$JAVA_HOME/lib/security/cacerts" /tmp/cacerts && chmod 600 /tmp/cacerts
          keytool -importcert -noprompt -alias colvenna -file /ca/ca.crt -keystore /tmp/cacerts \
            -storepass changeit >/dev/null
          conf=/tmp/spark.conf
          {
            echo "spark.driver.extraJavaOptions -Djavax.net.ssl.trustStore=/tmp/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
            echo "spark.sql.extensions org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions,org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions"
            echo "spark.ui.enabled false"
            if [ -d /open ]; then
              echo "spark.sql.catalog.open org.apache.iceberg.spark.SparkCatalog"
              echo "spark.sql.catalog.open.type rest"
              echo "spark.sql.catalog.open.uri https://polaris.$POLARIS_NAMESPACE.svc:8181/api/catalog"
              echo "spark.sql.catalog.open.warehouse $POLARIS_CATALOG"
              echo "spark.sql.catalog.open.credential \$(cat /open/client-id):\$(cat /open/client-secret)"
              echo "spark.sql.catalog.open.scope PRINCIPAL_ROLE:ALL"
              echo "spark.sql.catalog.open.header.X-Iceberg-Access-Delegation vended-credentials"
              echo "spark.sql.catalog.open.io-impl org.apache.iceberg.aws.s3.S3FileIO"
              echo "spark.sql.catalog.open.client.region $AWS_REGION"
              echo "spark.sql.catalog.open.cache-enabled false"
            fi
            if [ -d /paimon ]; then
              echo "spark.sql.catalog.paimon org.apache.paimon.spark.SparkCatalog"
              echo "spark.sql.catalog.paimon.metastore rest"
              echo "spark.sql.catalog.paimon.uri https://colvenna-catalog.$CATALOG_NAMESPACE.svc:8181"
              echo "spark.sql.catalog.paimon.warehouse colvenna"
              echo "spark.sql.catalog.paimon.token.provider bear"
              echo "spark.sql.catalog.paimon.token \$(cat /paimon/token)"
              echo "spark.sql.catalog.paimon.data-token.enabled true"
              echo "spark.sql.catalog.paimon.cache-enabled false"
            fi
          } >\$conf
          chmod 600 \$conf
          for f in /sql/*.sql; do
            n=\$(basename \$f .sql)
            if /opt/spark/bin/spark-sql --master 'local[2]' --properties-file \$conf -S -f \$f \
              >/tmp/out 2>/tmp/err; then
              echo "@@ \$n"
              cat /tmp/out
            else
              # Not the log lines (yy/MM/dd HH:mm:ss LEVEL) or stack frames: the error itself, as an
              # exception or Spark's [ERROR_CLASS] message.
              grep -v -E '^\s*at |^\s*\.\.\. [0-9]+ more|^[0-9]{2}/[0-9]{2}/[0-9]{2} [0-9:]{8} |^\s*$' /tmp/err \
                >/tmp/said || true
              said=\$(grep -E 'Exception|Error|^\[[A-Z_.]+\]' /tmp/said | head -3 | tr '\n' ' ' | cut -c1-600)
              [ -n "\$said" ] || said=\$(head -3 /tmp/said | tr '\n' ' ' | cut -c1-600)
              echo "@@ \$n ERROR \$said"
              tail -10 /tmp/said | sed 's/^/@@# /'
            fi
          done
      resources: {requests: {cpu: 500m, memory: 2Gi}, limits: {memory: 3Gi}}
      volumeMounts:
        - {name: sql, mountPath: /sql, readOnly: true}
        - {name: ca, mountPath: /ca, readOnly: true}
${secret_mounts}  volumes:
    - {name: sql, configMap: {name: $pod}}
    - {name: ca, configMap: {name: colvenna-ca}}
${secret_volumes}
YAML
kubectl -n "$READER_NAMESPACE" apply -f "$work/pod.yaml" >/dev/null
if ! kubectl -n "$READER_NAMESPACE" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/$pod" \
  --timeout="${SPARK_TIMEOUT:-900s}" >/dev/null; then
  kubectl -n "$READER_NAMESPACE" logs "$pod" >"$out" 2>&1 || true
  echo "spark-sql pod $pod did not succeed; its log is in $out" >&2
  exit 1
fi
kubectl -n "$READER_NAMESPACE" logs "$pod" >"$out"
echo "$(grep -c '^@@' "$out") statements in $out"
