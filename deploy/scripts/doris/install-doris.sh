#!/usr/bin/env bash
# Installs the platform's Doris for dashboards (LLD "看板服务"): one FE and one BE of the
# Doris 4.1.4 images (with the self-built Paimon 2.0.0 reader) on the query nodes, through the
# Doris Operator. In-cluster only: its Services are ClusterIP, and its NetworkPolicy admits the FE
# and BE to each other, the Doris Operator, and on the FE's MySQL port only colvenna-operator's
# namespace (colvenna-operator manages the dashboards' users and objects), this namespace's admin
# client pods and namespaces labelled colvenna-lake.io/doris-client.
# root's password is generated once into a Secret and never printed; Doris reads the lake only with
# the tokens colvenna-catalog vends (its pods' AWS role reaches no table data). The query audit log,
# table and file, keeps a query 7 days (the acceptance contract: a platform copy of personal data,
# within 7 days). The BE's local() table function reads no file.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
assert_account
ensure_kubeconfig
work="$(mktemp -d)"
chmod 700 "$work"
trap 'rm -rf "$work"' EXIT
role="$(stack_output "$CATALOG_STACK" DorisEngineRoleArn)"

# The Doris namespace and service account: the role's trust names them.
kubectl create namespace "$DORIS_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label namespace "$DORIS_NAMESPACE" --overwrite \
  pod-security.kubernetes.io/enforce=baseline colvenna-lake.io/catalog-client=true >/dev/null
kubectl -n "$DORIS_NAMESPACE" create serviceaccount "$DORIS_SERVICE_ACCOUNT" --dry-run=client -o yaml \
  | kubectl apply -f - >/dev/null
kubectl -n "$DORIS_NAMESPACE" annotate serviceaccount "$DORIS_SERVICE_ACCOUNT" --overwrite \
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

# root's password, once: the FE takes its verifier at first start; a rerun keeps both.
if ! kubectl -n "$DORIS_NAMESPACE" get secret "$DORIS_ADMIN_SECRET" >/dev/null 2>&1; then
  openssl rand -hex 32 | tr -d '\n' >"$work/root-password"
  kubectl -n "$DORIS_NAMESPACE" create secret generic "$DORIS_ADMIN_SECRET" \
    --type=kubernetes.io/basic-auth --from-literal=username=root \
    --from-file=password="$work/root-password" >/dev/null
fi
kubectl -n "$DORIS_NAMESPACE" get secret "$DORIS_ADMIN_SECRET" -o jsonpath='{.data.password}' \
  | base64 -d >"$work/root-password"
verifier="$(python3 - "$work/root-password" <<'PY'
import hashlib, sys
password = open(sys.argv[1]).read().encode()
print("*" + hashlib.sha1(hashlib.sha1(password).digest()).hexdigest().upper())
PY
)"
rm -f "$work/root-password"
{
  # The query nodes are shared: a smaller heap than the image's default.
  sed 's/-Xmx8192m -Xms8192m/-Xmx3072m -Xms3072m/' "$REPO_DIR/deploy/scripts/doris/fe-default.conf"
  printf 'skip_localhost_auth_check = false\nenable_fqdn_mode = true\n'
  # A query leaves the audit file 7 days after it, as it leaves the audit table: a day's file
  # rolls at its end and goes 5 days later, at a roll. Dropped partitions and tables stay in the
  # recycle bin a day (LakeErasure's DorisStorage counts on it).
  printf 'audit_log_roll_interval = DAY\naudit_log_delete_age = 5d\ncatalog_trash_expire_second = 86400\n'
  printf 'initial_root_password = %s\n' "$verifier"
} >"$work/fe.conf"
kubectl -n "$DORIS_NAMESPACE" create configmap "$DORIS_CLUSTER-fe" --from-file=fe.conf="$work/fe.conf" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
# The BE's, as the image ships it (be-default.conf), but for the files local() may read: any user
# may call it (Doris 4.1.4 checks no privilege), and by default it reads all of the BE's home, the
# storage of every dashboard's tables and the logs with them. An empty directory instead.
be_files=/opt/apache-doris/be/user_files_none
{
  cat "$(dirname "$0")/be-default.conf"
  printf 'user_files_secure_path = %s\n' "$be_files"
} >"$work/be.conf"
kubectl -n "$DORIS_NAMESPACE" create configmap "$DORIS_CLUSTER-be" --from-file=be.conf="$work/be.conf" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

trust_opts="-Djavax.net.ssl.trustStore=/etc/colvenna-trust/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12"
cat >"$work/cluster.yaml" <<YAML
apiVersion: doris.selectdb.com/v1
kind: DorisCluster
metadata:
  name: $DORIS_CLUSTER
  namespace: $DORIS_NAMESPACE
  labels: {app.kubernetes.io/part-of: colvenna-lake}
spec:
  authSecret: $DORIS_ADMIN_SECRET
  feSpec:
    replicas: 1
    image: $DORIS_FE_IMAGE
    serviceAccount: $DORIS_SERVICE_ACCOUNT
    nodeSelector: {colvenna-lake/role: query}
    requests: {cpu: 250m, memory: 4Gi}
    limits: {cpu: "2", memory: 5Gi}
    configMapInfo: {configMapName: $DORIS_CLUSTER-fe}
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
    image: $DORIS_BE_IMAGE
    skipDefaultSystemInit: true
    serviceAccount: $DORIS_SERVICE_ACCOUNT
    configMapInfo: {configMapName: $DORIS_CLUSTER-be}
    nodeSelector: {colvenna-lake/role: query}
    requests: {cpu: 500m, memory: 6Gi}
    limits: {cpu: "3", memory: 10Gi}
    envVars:
      - {name: JAVA_TOOL_OPTIONS, value: "$trust_opts"}
    secrets:
      - {secretName: colvenna-catalog-truststore, mountPath: /etc/colvenna-trust}
    persistentVolumes:
      - name: be-storage
        mountPath: /opt/apache-doris/be/storage
        persistentVolumeClaimSpec:
          storageClassName: colvenna-gp3
          accessModes: [ReadWriteOnce]
          resources: {requests: {storage: 20Gi}}
    service: {type: ClusterIP}
YAML
kubectl apply -f "$work/cluster.yaml" >/dev/null

# The FE and BE reach each other; the Doris Operator its FE; and on the FE's MySQL port only: the
# operator, this namespace's admin client pods and namespaces labelled colvenna-lake.io/doris-client.
# The FE's and BE's HTTP ports (no authentication of their own) are for the cluster itself.
cat >"$work/networkpolicy.yaml" <<YAML
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: {name: $DORIS_CLUSTER, namespace: $DORIS_NAMESPACE}
spec:
  podSelector: {matchLabels: {app.doris.cluster: $DORIS_CLUSTER}}
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector: {matchLabels: {app.doris.cluster: $DORIS_CLUSTER}}
    - from:
        - namespaceSelector: {matchLabels: {kubernetes.io/metadata.name: doris-operator}}
      ports: [{port: 9030, protocol: TCP}, {port: 8030, protocol: TCP}]
    - from:
        - podSelector: {matchLabels: {colvenna-lake.io/doris-admin: "true"}}
        - namespaceSelector: {matchLabels: {kubernetes.io/metadata.name: $OPERATOR_NAMESPACE}}
        - namespaceSelector: {matchLabels: {colvenna-lake.io/doris-client: "true"}}
      ports: [{port: 9030, protocol: TCP}]
YAML
kubectl apply -f "$work/networkpolicy.yaml" >/dev/null

# Ready as applied, not as before: an upgrade that changes the DorisCluster (the images' repository,
# for one) leaves the old pods ready until the Doris Operator replaces them (#261).
for component in fe be; do
  image="$DORIS_FE_IMAGE"
  [[ "$component" == fe ]] || image="$DORIS_BE_IMAGE"
  ready=false
  for _ in $(seq 1 90); do
    if doris_component_current "$DORIS_NAMESPACE" "$DORIS_CLUSTER" "$component" "$image"; then
      ready=true
      break
    fi
    [[ "$component" != be ]] || touch_doris_without_be "$DORIS_NAMESPACE" "$DORIS_CLUSTER"
    sleep 10
  done
  [[ "$ready" == true ]] || { doris_touch_report "$DORIS_CLUSTER"; echo "Doris $component did not become ready" >&2; exit 1; }
done
doris_touch_report "$DORIS_CLUSTER"

# admin, Doris's second built-in account, starts without a password: it gets one no one keeps
# (Doris 4.1 cannot lock an account). root is the operator's and this script's only (LLD "看板服务":
# 管理账号边界).
openssl rand -hex 32 | tr -d '\n' >"$work/admin-password"
printf "SET PASSWORD FOR 'admin'@'%%' = PASSWORD('%s');\n" "$(cat "$work/admin-password")" >"$work/admin.sql"
rm -f "$work/admin-password"
doris_sql "$work/admin.sql" >/dev/null
# Workload groups: root and admin run in platform, unbounded; each dashboard's users in its own
# (the operator makes them). Doris lets any user switch to normal, so normal is kept small: 2
# queries at once, 4 waiting, 10 s of waiting at most.
cat >"$work/groups.sql" <<'SQL'
CREATE WORKLOAD GROUP IF NOT EXISTS platform PROPERTIES ('max_concurrency' = '2147483647');
SET PROPERTY FOR 'root' 'default_workload_group' = 'platform';
SET PROPERTY FOR 'admin' 'default_workload_group' = 'platform';
ALTER WORKLOAD GROUP normal PROPERTIES ('max_concurrency' = '2', 'max_queue_size' = '4', 'queue_timeout' = '10000');
SQL
doris_sql "$work/groups.sql" >/dev/null
# The query audit table keeps 7 daily partitions, today's included.
cat >"$work/audit.sql" <<'SQL'
SET GLOBAL enable_audit_plugin = true;
ALTER TABLE __internal_schema.audit_log SET ("dynamic_partition.start" = "-6");
SHOW CREATE TABLE __internal_schema.audit_log;
SQL
doris_sql "$work/audit.sql" | grep -o '"dynamic_partition.start" = "[^"]*"'
# A running BE keeps the configuration it started with: as Doris reports what each runs with, any
# without this one is restarted (once: it then starts with the ConfigMap's).
printf "SHOW BACKEND CONFIG LIKE 'user_files_secure_path';\n" >"$work/backend-config.sql"
# awk's own exit status, not grep -q on a pipe: under pipefail a failed query reads as no mismatch,
# as before, and no reader stops early.
if doris_sql "$work/backend-config.sql" | awk -F'\t' -v want="$be_files" '$4 != want {f = 1} END {exit !f}'; then
  kubectl -n "$DORIS_NAMESPACE" delete pod "$DORIS_CLUSTER-be-0" --wait=true >/dev/null
  for _ in $(seq 1 90); do
    doris_sql "$work/backend-config.sql" 2>/dev/null \
      | awk -F'\t' -v want="$be_files" '$4 == want {f = 1} END {exit !f}' && break
    sleep 10
  done
fi
doris_sql "$work/backend-config.sql" | awk -F'\t' -v want="$be_files" '$4 != want {bad = 1} END {exit bad}' \
  || { echo "a Doris BE does not run with user_files_secure_path = $be_files" >&2; exit 1; }
echo "Doris $DORIS_CLUSTER in $DORIS_NAMESPACE: FE $DORIS_CLUSTER-fe-service:9030"
