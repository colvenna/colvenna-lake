#!/usr/bin/env bash
# Installs Apache Polaris 1.8.0, the open layer's Iceberg REST catalog (LLD "开放层"), and prepares
# what the operator publishes into:
#  1. Its metadata database on the project RDS (database and role polaris).
#  2. TLS signed by colvenna-catalog's private CA, the OAuth2 token keys, and the official chart pinned
#     by checksum and image digest, with no LoadBalancer or other exposure (the chart's default
#     extra service is a LoadBalancer) and a NetworkPolicy admitting only labelled namespaces.
#  3. The root principal (bootstrap), whose credentials stay in a Secret.
#  4. The catalog colvenna_open: EXTERNAL, so Iceberg clients cannot create, commit or purge tables;
#     its storage configuration assumes the read-only role to read metadata and vend readers
#     their credentials.
#  5. The operator's principal: it may send notifications (CATALOG_MANAGE_METADATA) and grant
#     readers' catalog roles (CATALOG_MANAGE_ACCESS), and holds no data privilege.
# Rerunning keeps the database password, keys and principals.
# usage: install-polaris.sh <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/polaris-admin.sh
source "$(dirname "$0")/../lib/polaris-admin.sh"
assert_account
ensure_kubeconfig
evidence="${1:?usage: install-polaris.sh <evidence-dir>}"
mkdir -p "$evidence"
sha="$(committed_sha)"
work="$(mktemp -d)"
chmod 700 "$work"
# shellcheck disable=SC2317  # invoked through the EXIT trap
cleanup() {
  polaris_close
  kubectl -n "$POLARIS_NAMESPACE" delete secret polaris-db-bootstrap polaris-bootstrap \
    --ignore-not-found >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

polaris_role="$(stack_output "$OPEN_LAYER_STACK" PolarisRoleArn)"
read_role="$(stack_output "$OPEN_LAYER_STACK" IcebergReadRoleArn)"
warehouse="$(stack_output "$OPEN_LAYER_STACK" WarehouseRoot)"
db_endpoint="$(stack_output "$FOUNDATION_STACK" CatalogEndpoint)"

kubectl create namespace "$POLARIS_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label namespace "$POLARIS_NAMESPACE" --overwrite \
  pod-security.kubernetes.io/enforce=restricted >/dev/null
# The operator publishes; readers' namespaces are labelled by whoever runs them.
kubectl label namespace "$OPERATOR_NAMESPACE" --overwrite "$POLARIS_CLIENT_LABEL=true" >/dev/null

# --- metadata database ------------------------------------------------------------------------
curl -fsSL https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem -o "$work/rds-ca.pem"
grep -q "BEGIN CERTIFICATE" "$work/rds-ca.pem"
# The password is made once; the URL is written each time. Its currentSchema is where Polaris's
# tables are (the bootstrap does not select a schema of its own).
if kubectl -n "$POLARIS_NAMESPACE" get secret polaris-db >/dev/null 2>&1; then
  kubectl -n "$POLARIS_NAMESPACE" get secret polaris-db -o jsonpath='{.data.password}' | base64 -d \
    >"$work/password"
else
  openssl rand -hex 24 | tr -d '\n' >"$work/password"
fi
printf polaris >"$work/username"
printf 'jdbc:postgresql://%s:5432/polaris?sslmode=verify-full&sslrootcert=/deployments/db-ca/ca.pem&currentSchema=polaris_schema' \
  "$db_endpoint" >"$work/jdbcUrl"
kubectl -n "$POLARIS_NAMESPACE" create secret generic polaris-db --from-file=username="$work/username" \
  --from-file=password="$work/password" --from-file=jdbcUrl="$work/jdbcUrl" --dry-run=client -o yaml \
  | kubectl apply -f - >/dev/null
rm -f "$work/password"
kubectl -n "$POLARIS_NAMESPACE" create configmap polaris-db-ca --from-file=ca.pem="$work/rds-ca.pem" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$POLARIS_NAMESPACE" get secret polaris-db -o jsonpath='{.data.password}' | base64 -d \
  >"$work/POLARIS_PASSWORD"
aws secretsmanager get-secret-value --secret-id "$(stack_output "$FOUNDATION_STACK" CatalogSecretArn)" \
  --query SecretString --output text | python3 -c '
import json, os, sys
s = json.load(sys.stdin)
open(os.path.join(sys.argv[1], "PGUSER"), "w").write(s["username"])
open(os.path.join(sys.argv[1], "PGPASSWORD"), "w").write(s["password"])' "$work"
kubectl -n "$POLARIS_NAMESPACE" create secret generic polaris-db-bootstrap \
  --from-file=PGUSER="$work/PGUSER" --from-file=PGPASSWORD="$work/PGPASSWORD" \
  --from-file=POLARIS_PASSWORD="$work/POLARIS_PASSWORD" --dry-run=client -o yaml \
  | kubectl apply -f - >/dev/null
rm -f "$work/PGUSER" "$work/PGPASSWORD" "$work/POLARIS_PASSWORD"
cat >"$work/db.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: polaris-db-bootstrap}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 70, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: psql
      image: $POSTGRES_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      envFrom: [{secretRef: {name: polaris-db-bootstrap}}]
      env:
        - {name: PGHOST, value: "$db_endpoint"}
        - {name: PGDATABASE, value: postgres}
        - {name: PGSSLMODE, value: verify-full}
        - {name: PGSSLROOTCERT, value: /ca/ca.pem}
      volumeMounts: [{name: ca, mountPath: /ca}]
      command: ["sh", "-ec"]
      args:
        - |
          # The password reaches psql through its environment, never its arguments.
          psql -v ON_ERROR_STOP=1 <<'SQL'
          \getenv pw POLARIS_PASSWORD
          SELECT format('CREATE ROLE polaris LOGIN PASSWORD %L', :'pw')
            WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'polaris') \gexec
          ALTER ROLE polaris WITH LOGIN PASSWORD :'pw';
          GRANT polaris TO CURRENT_USER;
          SELECT 'CREATE DATABASE polaris OWNER polaris'
            WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'polaris') \gexec
          REVOKE ALL ON DATABASE polaris FROM PUBLIC;
          GRANT CONNECT, TEMPORARY, CREATE ON DATABASE polaris TO polaris;
          \c polaris
          CREATE SCHEMA IF NOT EXISTS polaris_schema AUTHORIZATION polaris;
          SQL
          echo database-ready
  volumes: [{name: ca, configMap: {name: polaris-db-ca}}]
YAML
run_pod "$POLARIS_NAMESPACE" polaris-db-bootstrap "$work/db.yaml" | tee "$evidence/db-bootstrap.log"
grep -q '^database-ready$' "$evidence/db-bootstrap.log"
kubectl -n "$POLARIS_NAMESPACE" delete secret polaris-db-bootstrap >/dev/null

# --- TLS and token keys -----------------------------------------------------------------------
kubectl -n "$CATALOG_NAMESPACE" get secret colvenna-catalog-ca-key -o jsonpath='{.data.ca\.key}' \
  | base64 -d >"$work/ca.key"
kubectl -n "$CATALOG_NAMESPACE" get secret colvenna-catalog-ca-key -o jsonpath='{.data.ca\.crt}' \
  | base64 -d >"$work/ca.crt"
service="$POLARIS_RELEASE"
names="DNS:$service,DNS:$service.$POLARIS_NAMESPACE,DNS:$service.$POLARIS_NAMESPACE.svc"
names="$names,DNS:$service.$POLARIS_NAMESPACE.svc.cluster.local,DNS:localhost"
openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -subj "/CN=$service.$POLARIS_NAMESPACE.svc" -keyout "$work/tls.key" -out "$work/tls.csr" 2>/dev/null
openssl x509 -req -in "$work/tls.csr" -CA "$work/ca.crt" -CAkey "$work/ca.key" -CAcreateserial \
  -days 397 -out "$work/tls.crt" \
  -extfile <(printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth\n' "$names") 2>/dev/null
rm -f "$work/ca.key"
kubectl -n "$POLARIS_NAMESPACE" create secret tls polaris-tls --cert="$work/tls.crt" \
  --key="$work/tls.key" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
openssl x509 -in "$work/tls.crt" -noout -subject -ext subjectAltName -enddate \
  >"$evidence/server-certificate.txt"
if ! kubectl -n "$POLARIS_NAMESPACE" get secret polaris-token-keys >/dev/null 2>&1; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$work/private.pem" 2>/dev/null
  openssl pkey -in "$work/private.pem" -pubout -out "$work/public.pem"
  kubectl -n "$POLARIS_NAMESPACE" create secret generic polaris-token-keys \
    --from-file=private.pem="$work/private.pem" --from-file=public.pem="$work/public.pem" >/dev/null
fi

# --- chart --------------------------------------------------------------------------------------
tools="$TOOLS_DIR"
mkdir -p "$tools"
chart="$tools/polaris-1.8.0.tgz"
[[ -f "$chart" ]] || curl -fsSL "$POLARIS_CHART_URL" -o "$chart"
printf '%s  %s\n' "$POLARIS_CHART_SHA512" "$chart" | sha512sum --check --quiet
python3 - "$work/values.yaml" "$POLARIS_IMAGE" "$polaris_role" "$sha" "$POLARIS_REALM" <<'PY'
import json, sys
out, image, role, sha, realm = sys.argv[1:]
repository, tag = image.split(":", 1)
values = {
    "image": {"repository": repository, "tag": tag},
    "serviceAccount": {"create": True, "name": "polaris",
                       "annotations": {"eks.amazonaws.com/role-arn": role}},
    "podAnnotations": {"colvenna-lake.io/source-sha": sha},
    # The chart's default is an extra LoadBalancer service: no exposure beyond ClusterIP.
    "extraServices": [],
    "nodeSelector": {"colvenna-lake/role": "query"},
    "resources": {"requests": {"cpu": "250m", "memory": "1Gi"}, "limits": {"memory": "1536Mi"}},
    "persistence": {"type": "relational-jdbc",
                    "relationalJdbc": {"secret": {"name": "polaris-db"},
                                       "additionalProperties": {"currentSchema": "polaris_schema"}}},
    "authentication": {"type": "internal",
                       "tokenBroker": {"type": "rsa-key-pair", "maxTokenGeneration": "PT1H",
                                       "secret": {"name": "polaris-token-keys"}}},
    "realmContext": {"realms": [realm], "requireHeader": False},
    "features": {
        "SUPPORTED_CATALOG_STORAGE_TYPES": ["S3"],
        # STS's shortest: a withdrawn table's vended credentials expire within 15 minutes. Polaris
        # reuses a credential for at most the cache duration, which must be shorter.
        "STORAGE_CREDENTIAL_DURATION_SECONDS": 900,
        "STORAGE_CREDENTIAL_CACHE_DURATION_SECONDS": 300,
    },
    "advancedConfig": {
        "quarkus.http.access-log.enabled": True,
        # TLS only, on the API port; health and metrics stay on the management port.
        "quarkus.http.ssl-port": 8181,
        "quarkus.http.port": 8180,
        "quarkus.http.insecure-requests": "disabled",
        "quarkus.http.ssl.certificate.files": "/deployments/tls/tls.crt",
        "quarkus.http.ssl.certificate.key-files": "/deployments/tls/tls.key",
    },
    "extraVolumes": [
        {"name": "tls", "secret": {"secretName": "polaris-tls"}},
        {"name": "db-ca", "configMap": {"name": "polaris-db-ca"}},
    ],
    "extraVolumeMounts": [
        {"name": "tls", "mountPath": "/deployments/tls", "readOnly": True},
        {"name": "db-ca", "mountPath": "/deployments/db-ca", "readOnly": True},
    ],
}
json.dump(values, open(out, "w"), indent=2)
PY
cp "$work/values.yaml" "$evidence/values.json"
helm template "$POLARIS_RELEASE" "$chart" -n "$POLARIS_NAMESPACE" -f "$work/values.yaml" \
  >"$evidence/rendered.yaml"
if grep -Eq 'type: (LoadBalancer|NodePort)|kind: (Ingress|Gateway|HTTPRoute)' "$evidence/rendered.yaml"; then
  echo "The rendered chart exposes Polaris beyond ClusterIP" >&2
  exit 1
fi

# Bootstrap the realm and root principal before the server starts against the database.
if ! kubectl -n "$POLARIS_NAMESPACE" get secret polaris-root >/dev/null 2>&1; then
  printf root >"$work/client-id"
  openssl rand -hex 24 | tr -d '\n' >"$work/client-secret"
  printf '%s:\n  client-id: %s\n  client-secret: %s\n' "$POLARIS_REALM" "$(cat "$work/client-id")" \
    "$(cat "$work/client-secret")" >"$work/credentials.yaml"
  kubectl -n "$POLARIS_NAMESPACE" create secret generic polaris-bootstrap \
    --from-file=credentials.yaml="$work/credentials.yaml" >/dev/null
  cat >"$work/bootstrap.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: polaris-bootstrap}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 10000, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: admin
      image: $POLARIS_ADMIN_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      args: ["bootstrap", "-f", "/in/credentials.yaml"]
      env:
        - {name: POLARIS_PERSISTENCE_TYPE, value: relational-jdbc}
        - {name: QUARKUS_DATASOURCE_USERNAME, valueFrom: {secretKeyRef: {name: polaris-db, key: username}}}
        - {name: QUARKUS_DATASOURCE_PASSWORD, valueFrom: {secretKeyRef: {name: polaris-db, key: password}}}
        - {name: QUARKUS_DATASOURCE_JDBC_URL, valueFrom: {secretKeyRef: {name: polaris-db, key: jdbcUrl}}}
      resources: {requests: {cpu: 100m, memory: 512Mi}, limits: {memory: 768Mi}}
      volumeMounts:
        - {name: in, mountPath: /in, readOnly: true}
        - {name: db-ca, mountPath: /deployments/db-ca, readOnly: true}
        - {name: tmp, mountPath: /tmp}
  volumes:
    - {name: in, secret: {secretName: polaris-bootstrap}}
    - {name: db-ca, configMap: {name: polaris-db-ca}}
    - {name: tmp, emptyDir: {}}
YAML
  run_pod "$POLARIS_NAMESPACE" polaris-bootstrap "$work/bootstrap.yaml" >"$evidence/bootstrap.log"
  kubectl -n "$POLARIS_NAMESPACE" delete secret polaris-bootstrap >/dev/null
  kubectl -n "$POLARIS_NAMESPACE" create secret generic polaris-root \
    --from-file=client-id="$work/client-id" --from-file=client-secret="$work/client-secret" >/dev/null
  rm -f "$work/client-secret" "$work/credentials.yaml"
fi

helm_upgrade "$POLARIS_RELEASE" "$chart" --namespace "$POLARIS_NAMESPACE" \
  -f "$work/values.yaml" --wait --timeout 10m >"$evidence/helm.txt"
kubectl -n "$POLARIS_NAMESPACE" rollout status "deploy/$POLARIS_RELEASE" --timeout=5m >>"$evidence/helm.txt"
kubectl apply -f - >/dev/null <<YAML
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: {name: polaris, namespace: $POLARIS_NAMESPACE}
spec:
  podSelector: {matchLabels: {app.kubernetes.io/name: polaris}}
  policyTypes: [Ingress]
  ingress:
    - from: [{namespaceSelector: {matchLabels: {$POLARIS_CLIENT_LABEL: "true"}}}]
      ports: [{port: 8181, protocol: TCP}]
    - from: [{namespaceSelector: {matchLabels: {colvenna-lake.io/monitoring: "true"}}}]
      ports: [{port: 8182, protocol: TCP}]
YAML
kubectl -n "$POLARIS_NAMESPACE" get pods -o wide >"$evidence/pods.txt"

# --- catalog and the operator's principal ------------------------------------------------------
polaris_open polaris-root
python3 - "$work/catalog.json" "$POLARIS_CATALOG" "$warehouse" "$read_role" "$COLVENNA_REGION" <<'PY'
import json, sys
out, name, warehouse, role, region = sys.argv[1:]
json.dump({"catalog": {
    "type": "EXTERNAL", "name": name,
    "properties": {"default-base-location": warehouse},
    "storageConfigInfo": {"storageType": "S3", "roleArn": role, "region": region,
                          "allowedLocations": [warehouse]},
}}, open(out, "w"))
PY
polaris_ensure /api/management/v1/catalogs "$(cat "$work/catalog.json")"
polaris_call GET "/api/management/v1/catalogs/$POLARIS_CATALOG" >"$evidence/catalog.json"
polaris_ensure /api/management/v1/principal-roles '{"principalRole": {"name": "colvenna_publisher"}}'
polaris_ensure "/api/management/v1/catalogs/$POLARIS_CATALOG/catalog-roles" \
  '{"catalogRole": {"name": "publisher"}}'
for privilege in CATALOG_MANAGE_METADATA CATALOG_MANAGE_ACCESS; do
  polaris_call PUT "/api/management/v1/catalogs/$POLARIS_CATALOG/catalog-roles/publisher/grants" \
    "{\"grant\": {\"type\": \"catalog\", \"privilege\": \"$privilege\"}}" >/dev/null
done
polaris_call PUT "/api/management/v1/principal-roles/colvenna_publisher/catalog-roles/$POLARIS_CATALOG" \
  '{"catalogRole": {"name": "publisher"}}' >/dev/null
if ! kubectl -n "$POLARIS_NAMESPACE" get secret polaris-operator >/dev/null 2>&1; then
  polaris_call POST /api/management/v1/principals '{"principal": {"name": "colvenna-operator"}}' \
    | python3 -c '
import json, os, sys
c = json.load(sys.stdin)["credentials"]
open(os.path.join(sys.argv[1], "client-id"), "w").write(c["clientId"])
open(os.path.join(sys.argv[1], "client-secret"), "w").write(c["clientSecret"])' "$work"
  for ns in "$POLARIS_NAMESPACE" "$OPERATOR_NAMESPACE"; do
    kubectl -n "$ns" create secret generic polaris-operator --from-file=client-id="$work/client-id" \
      --from-file=client-secret="$work/client-secret" --dry-run=client -o yaml \
      | kubectl apply -f - >/dev/null
  done
  rm -f "$work/client-secret"
fi
polaris_call PUT /api/management/v1/principals/colvenna-operator/principal-roles \
  '{"principalRole": {"name": "colvenna_publisher"}}' >/dev/null
polaris_call GET "/api/management/v1/catalogs/$POLARIS_CATALOG/catalog-roles/publisher/grants" \
  >"$evidence/publisher-grants.json"
echo "Polaris $POLARIS_IMAGE in $POLARIS_NAMESPACE, catalog $POLARIS_CATALOG over $warehouse"
