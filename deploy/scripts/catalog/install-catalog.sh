#!/usr/bin/env bash
# Installs Colvenna Catalog, the release this tree pins (deploy/images/colvenna-catalog.json), on the
# cluster, under the names Colvenna Lake has always given it: namespace, release and service account
# colvenna-catalog, its Secrets, and the database and user colvenna_catalog. So an environment whose
# catalog Colvenna Lake built itself (before #295) is taken over in place, its metadata, tokens and
# grants kept:
#   1. creates the colvenna_catalog role and database on the project RDS instance;
#   2. issues the private CA (kept in the cluster) and a server certificate for the Service;
#   3. installs the release's Helm chart with its image, both as fetch-catalog.sh fetched and checked
#      them (catalog-release.json), with Colvenna Lake's values (catalog_values);
#   4. keeps the platform's admin token, in the Secret colvenna-catalog-admin-token, working, never
#      printing it: bootstrap-admin issues it when the Secret is missing or the catalog refuses its
#      token; a token with fewer than TOKEN_RENEW_DAYS (default 30) days left is renewed
#      (ensure_admin_token);
# verify-catalog.sh then checks that callers without valid credentials, and pods outside the client
# namespaces, are refused. It also upgrades the catalog to the release a later commit pins (install.sh
# --only catalog, after fetch-catalog.sh): every value is given anew each run, none reused from what
# the Helm release stored.
# Usage: [TOKEN_RENEW_DAYS=<days>] install-catalog.sh <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/catalog-admin.sh
source "$(dirname "$0")/../lib/catalog-admin.sh"

# catalog_values: the chart's values, one helm argument per line, from IMAGE (<repository>@<digest>),
# BUCKET, DB_ENDPOINT, SERVER_ROLE, DATA_ROLE, WRITE_NODES, SHA (this commit), CATALOG_CANDIDATE and
# CATALOG_BUILT_FROM, and the environment. Colvenna Lake's own chart set the same values under the
# same keys; the catalog's chart has them all.
catalog_values() {
  printf '%s\n' \
    --set "image.repository=${IMAGE%@*}" --set "image.digest=${IMAGE#*@}" \
    --set replicas=2 --set spreadAcrossNodes=true --set "eligibleNodes=$WRITE_NODES" \
    --set-string 'nodeSelector.colvenna-lake/role=write' \
    --set serviceAccount.name=colvenna-catalog --set "serviceAccount.roleArn=$SERVER_ROLE" \
    --set "environment=$COLVENNA_ENV" --set "region=$COLVENNA_REGION" \
    --set warehouse.name=colvenna --set "warehouse.root=s3://$BUCKET/warehouse" \
    --set "database.url=jdbc:postgresql://$DB_ENDPOINT:5432/colvenna_catalog?sslmode=verify-full&sslrootcert=/etc/colvenna-catalog/db-ca/ca.pem" \
    --set database.poolSize=16 --set database.auxPoolSize=4 \
    --set databaseSecret=colvenna-catalog-db --set databaseCaConfigMap=colvenna-catalog-db-ca \
    --set tlsSecret=colvenna-catalog-tls \
    --set dataTokens.mode=aws-sts --set "dataTokens.roleArn=$DATA_ROLE" \
    --set "dataTokens.duration=${DATA_TOKEN_DURATION:-PT3H}" \
    --set-json 'resources={"requests":{"cpu":"500m","memory":"1Gi"},"limits":{"cpu":"2","memory":"2Gi"}}' \
    --set clientNamespaceLabel.key=colvenna-lake.io/catalog-client --set-string clientNamespaceLabel.value=true \
    --set metricsClientLabel=colvenna-lake.io/monitoring \
    --set internalLoadBalancer.enabled=false \
    --set-string "podAnnotations.colvenna-lake/source-sha=$SHA" \
    --set-string "podAnnotations.colvenna-catalog/release=$CATALOG_CANDIDATE" \
    --set-string "podAnnotations.colvenna-catalog/source-sha=$CATALOG_BUILT_FROM"
}

# The platform's admin token lasts 90 days (bootstrap-admin's and the admin API's).
ADMIN_TOKEN_TTL=P90D
ADMIN_TOKEN_SECRET=colvenna-catalog-admin-token

# bootstrap_admin_token: a new admin token from bootstrap-admin in a catalog pod, straight into the
# admin token Secret, never printed, with its id (lib/catalog-admin.sh's store_secret_token).
# bootstrap-admin needs only the metadata database (it creates the principal admin if missing), so
# it works when no admin token does. It describes its tokens as "bootstrap", and logs the id of the
# one it issued ("Issued token <id> for admin ..."; Colvenna Catalog v1.0.0, Main.bootstrapAdmin).
bootstrap_admin_token() {
  local pod id
  pod="$(kubectl -n "$CATALOG_NAMESPACE" get pods -l app.kubernetes.io/name=colvenna-catalog "$LIVE_PODS" \
    -o jsonpath='{.items[0].metadata.name}')"
  # bootstrap-admin writes only a file that is not there yet.
  kubectl -n "$CATALOG_NAMESPACE" exec "$pod" -- sh -c 'rm -f /tmp/admin-token'
  kubectl -n "$CATALOG_NAMESPACE" exec "$pod" -- \
    java -cp '/opt/colvenna-catalog/colvenna-catalog.jar:/opt/colvenna-catalog/lib/*' \
    com.colvenna.catalog.Main bootstrap-admin admin "$ADMIN_TOKEN_TTL" /tmp/admin-token \
    >"$evidence/bootstrap-admin.log"
  # Read and remove in one step, so the token never stays behind in the pod.
  # shellcheck disable=SC2016  # expanded by the pod's shell
  kubectl -n "$CATALOG_NAMESPACE" exec "$pod" -- \
    sh -c 'cat /tmp/admin-token; status=$?; rm -f /tmp/admin-token; exit $status' \
    >"$work/admin-token"
  id="$(sed -n 's/.*Issued token \([0-9a-f-]\{36\}\) for admin .*/\1/p' "$evidence/bootstrap-admin.log" | tail -n 1)"
  if [[ -z "$id" ]]; then
    echo "bootstrap-admin logged no token id: the Secret's expiry is found by the token's description" >&2
  fi
  store_secret_token "$CATALOG_NAMESPACE" "$ADMIN_TOKEN_SECRET" "$work/admin-token" "$id"
  rm -f "$work/admin-token"
}

# ensure_admin_token: the admin token Secret holds a token the catalog accepts, with at least
# TOKEN_RENEW_DAYS (default 30) days left (lib/catalog-admin.sh's token_state):
#   - no Secret, or a token the catalog refuses (expired, revoked, or unknown after a restore of the
#     metadata database): bootstrap-admin again, as the first install does;
#   - fewer days left: a new token through the admin API, issued with the token itself and
#     described as the Secret;
#   - else it is kept.
# A new token goes into the Secret with its id, by which the next run finds its expiry, and is
# checked to work. The old token is not revoked: it expires by itself, so a script holding it keeps
# working until then.
ensure_admin_token() {
  local state="missing (no Secret $ADMIN_TOKEN_SECRET)"
  if kubectl -n "$CATALOG_NAMESPACE" get secret "$ADMIN_TOKEN_SECRET" >/dev/null 2>&1; then
    admin_open
    state="$(secret_token_state "$CATALOG_NAMESPACE" "$ADMIN_TOKEN_SECRET" admin bootstrap \
      "$ADMIN_TOKEN_SECRET")"
  fi
  echo "admin token: $state"
  case "$state" in
    keep*)
      admin_close
      return 0
      ;;
    renew*)
      issue_secret_token admin "$CATALOG_NAMESPACE" "$ADMIN_TOKEN_SECRET" "$ADMIN_TOKEN_TTL"
      ;;
    missing* | invalid*)
      admin_close
      bootstrap_admin_token
      admin_open
      ;;
    *)
      admin_close
      echo "Unexpected state of the admin token, $state: nothing issued" >&2
      return 1
      ;;
  esac
  state="$(secret_token_state "$CATALOG_NAMESPACE" "$ADMIN_TOKEN_SECRET" admin bootstrap \
    "$ADMIN_TOKEN_SECRET")"
  admin_close
  if [[ "$state" == invalid* ]]; then
    echo "colvenna-catalog refuses the admin token just issued, $state: is the principal admin disabled?" >&2
    return 1
  fi
  echo "admin token: new ${state#* }"
}

assert_account
ensure_kubeconfig
evidence="${1:?usage: install-catalog.sh <evidence-dir>}"
mkdir -p "$evidence"
# This commit (committed_sha refuses a dirty tree), which the pods are annotated with.
SHA="$(committed_sha)"
# The release fetch-catalog.sh fetched and checked: the one this tree pins, its image in the
# environment's repository, its chart package as pulled.
tool="$DEPLOY_DIR/scripts/release/manifest.py"
CATALOG_CANDIDATE="$(python3 "$tool" catalog candidate)" CATALOG_BUILT_FROM="$(python3 "$tool" catalog built_from)"
record="$STATE_DIR/catalog-release.json"
fetched() { python3 "$tool" get "$record" "$1"; }
if [[ ! -s "$record" || "$(fetched candidate)" != "$CATALOG_CANDIDATE" \
  || "$(fetched image)" != *"@$(python3 "$tool" catalog image.digest)" \
  || "$(fetched chart_digest)" != "$(python3 "$tool" catalog chart.digest)" ]]; then
  echo "Colvenna Catalog $CATALOG_CANDIDATE is not fetched for $COLVENNA_ENV: run fetch-catalog.sh first" >&2
  exit 1
fi
IMAGE="$(fetched image)"
chart="$STATE_DIR/$(fetched chart)"
if [[ ! -f "$chart" || "$(sha256sum "$chart" | cut -d' ' -f1)" != "$(fetched chart_sha256)" ]]; then
  echo "$chart is not the chart package fetch-catalog.sh pulled: run it again" >&2
  exit 1
fi
image_repository="${IMAGE%@*}"
if [[ "$(tag_digest "${image_repository#*/}" "$CATALOG_CANDIDATE")" != "${IMAGE#*@}" ]]; then
  echo "No image tagged $CATALOG_CANDIDATE with digest ${IMAGE#*@} in ${image_repository#*/}: run fetch-catalog.sh" >&2
  exit 1
fi

BUCKET="$(stack_output "$FOUNDATION_STACK" WarehouseBucket)"
DB_ENDPOINT="$(stack_output "$FOUNDATION_STACK" CatalogEndpoint)"
master_secret="$(stack_output "$FOUNDATION_STACK" CatalogSecretArn)"
SERVER_ROLE="$(stack_output "$CATALOG_STACK" ServerRoleArn)"
DATA_ROLE="$(stack_output "$CATALOG_STACK" DataRoleArn)"
db_secret="$(stack_output "$CATALOG_STACK" CatalogDatabaseSecretArn)"

work="$(mktemp -d)"
chmod 700 "$work"
# shellcheck disable=SC2317  # invoked through the EXIT trap
cleanup() {
  kubectl -n "$CATALOG_NAMESPACE" delete secret colvenna-catalog-db-bootstrap --ignore-not-found \
    >/dev/null 2>&1 || true
  admin_close
  rm -rf "$work"
}
trap cleanup EXIT

kubectl create namespace "$CATALOG_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label namespace "$CATALOG_NAMESPACE" --overwrite \
  pod-security.kubernetes.io/enforce=restricted >/dev/null

# --- metadata database ------------------------------------------------------------------------
curl -fsSL https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem -o "$work/rds-ca.pem"
grep -q "BEGIN CERTIFICATE" "$work/rds-ca.pem"
kubectl -n "$CATALOG_NAMESPACE" create configmap colvenna-catalog-db-ca \
  --from-file=ca.pem="$work/rds-ca.pem" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

aws secretsmanager get-secret-value --secret-id "$master_secret" --query SecretString \
  --output text >"$work/master.json"
aws secretsmanager get-secret-value --secret-id "$db_secret" --query SecretString \
  --output text >"$work/catalog.json"
python3 - "$work" <<'PY'
import json, sys
work = sys.argv[1]
master = json.load(open(f"{work}/master.json"))
catalog = json.load(open(f"{work}/catalog.json"))
open(f"{work}/PGUSER", "w").write(master["username"])
open(f"{work}/PGPASSWORD", "w").write(master["password"])
open(f"{work}/CATALOG_PASSWORD", "w").write(catalog["password"])
open(f"{work}/username", "w").write(catalog["username"])
open(f"{work}/password", "w").write(catalog["password"])
PY
kubectl -n "$CATALOG_NAMESPACE" create secret generic colvenna-catalog-db \
  --from-file=username="$work/username" --from-file=password="$work/password" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$CATALOG_NAMESPACE" create secret generic colvenna-catalog-db-bootstrap \
  --from-file=PGUSER="$work/PGUSER" --from-file=PGPASSWORD="$work/PGPASSWORD" \
  --from-file=CATALOG_PASSWORD="$work/CATALOG_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
cat >"$work/db-bootstrap.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata:
  name: colvenna-catalog-db-bootstrap
spec:
  restartPolicy: Never
  securityContext:
    runAsNonRoot: true
    runAsUser: 70
    seccompProfile: {type: RuntimeDefault}
  containers:
    - name: psql
      image: $POSTGRES_IMAGE
      securityContext:
        allowPrivilegeEscalation: false
        capabilities: {drop: ["ALL"]}
      envFrom:
        - secretRef: {name: colvenna-catalog-db-bootstrap}
      env:
        - {name: PGHOST, value: "$DB_ENDPOINT"}
        - {name: PGDATABASE, value: postgres}
        - {name: PGSSLMODE, value: verify-full}
        - {name: PGSSLROOTCERT, value: /ca/ca.pem}
      volumeMounts:
        - {name: ca, mountPath: /ca}
      command: ["sh", "-ec"]
      args:
        - |
          # The password reaches psql through its environment, never its arguments.
          psql -v ON_ERROR_STOP=1 <<'SQL'
          \getenv pw CATALOG_PASSWORD
          SELECT format('CREATE ROLE colvenna_catalog LOGIN PASSWORD %L', :'pw')
            WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'colvenna_catalog') \gexec
          ALTER ROLE colvenna_catalog WITH LOGIN PASSWORD :'pw';
          SELECT 'CREATE DATABASE colvenna_catalog OWNER colvenna_catalog'
            WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'colvenna_catalog') \gexec
          REVOKE ALL ON DATABASE colvenna_catalog FROM PUBLIC;
          GRANT CONNECT, TEMPORARY ON DATABASE colvenna_catalog TO colvenna_catalog;
          SQL
          echo database-ready
  volumes:
    - name: ca
      configMap: {name: colvenna-catalog-db-ca}
YAML
run_pod "$CATALOG_NAMESPACE" colvenna-catalog-db-bootstrap "$work/db-bootstrap.yaml" \
  | tee "$evidence/db-bootstrap.log"
grep -q '^database-ready$' "$evidence/db-bootstrap.log"
kubectl -n "$CATALOG_NAMESPACE" delete secret colvenna-catalog-db-bootstrap >/dev/null

# --- TLS ----------------------------------------------------------------------------------------
if kubectl -n "$CATALOG_NAMESPACE" get secret colvenna-catalog-ca-key >/dev/null 2>&1; then
  kubectl -n "$CATALOG_NAMESPACE" get secret colvenna-catalog-ca-key -o jsonpath='{.data.ca\.key}' \
    | base64 -d >"$work/ca.key"
  kubectl -n "$CATALOG_NAMESPACE" get secret colvenna-catalog-ca-key -o jsonpath='{.data.ca\.crt}' \
    | base64 -d >"$work/ca.crt"
else
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 825 \
    -subj "/CN=colvenna-catalog private CA" -keyout "$work/ca.key" -out "$work/ca.crt" 2>/dev/null
  kubectl -n "$CATALOG_NAMESPACE" create secret generic colvenna-catalog-ca-key \
    --from-file=ca.key="$work/ca.key" --from-file=ca.crt="$work/ca.crt" >/dev/null
fi
service=colvenna-catalog
names="DNS:$service,DNS:$service.$CATALOG_NAMESPACE,DNS:$service.$CATALOG_NAMESPACE.svc"
names="$names,DNS:$service.$CATALOG_NAMESPACE.svc.cluster.local,DNS:localhost"
openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -subj "/CN=$service.$CATALOG_NAMESPACE.svc" -keyout "$work/server.key" -out "$work/server.csr" \
  2>/dev/null
openssl x509 -req -in "$work/server.csr" -CA "$work/ca.crt" -CAkey "$work/ca.key" \
  -CAcreateserial -days 397 -out "$work/server.crt" \
  -extfile <(printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth\n' "$names") 2>/dev/null
# No trailing newline: the Secret value is used verbatim as the key store password.
openssl rand -hex 24 | tr -d '\n' >"$work/password"
openssl pkcs12 -export -in "$work/server.crt" -inkey "$work/server.key" -certfile "$work/ca.crt" \
  -name colvenna-catalog -out "$work/keystore.p12" -passout "file:$work/password"
kubectl -n "$CATALOG_NAMESPACE" create secret generic colvenna-catalog-tls \
  --from-file=keystore.p12="$work/keystore.p12" --from-file=password="$work/password" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
cp "$work/ca.crt" "$evidence/colvenna-catalog-ca.crt"
openssl x509 -in "$work/server.crt" -noout -subject -ext subjectAltName -enddate \
  >"$evidence/server-certificate.txt"

# --- chart --------------------------------------------------------------------------------------
# The write node group's size: the chart keeps the replicas on that many of its nodes, two at most.
WRITE_NODES="$(write_node_group_size)"
# Every value, an upgrade's too. What an environment installed before #295 stored has no client or
# monitoring label (Colvenna Lake's own chart had them as defaults): reused, the catalog chart's
# defaults would take their place, and its NetworkPolicy would shut out every client and Prometheus.
mapfile -t values < <(catalog_values)
helm_upgrade colvenna-catalog "$chart" --namespace "$CATALOG_NAMESPACE" --wait --timeout 10m \
  "${values[@]}" >"$evidence/helm.txt"
kubectl -n "$CATALOG_NAMESPACE" rollout status deploy/colvenna-catalog --timeout=5m \
  >>"$evidence/helm.txt"
kubectl -n "$CATALOG_NAMESPACE" get pods -o wide >"$evidence/pods.txt"
kubectl -n "$CATALOG_NAMESPACE" get pods -o jsonpath='{range .items[*]}{.status.containerStatuses[0].imageID}{"\n"}{end}' \
  >"$evidence/running-image-ids.txt"
cp "$record" "$evidence/catalog-release.json"

# --- admin token --------------------------------------------------------------------------------
ensure_admin_token

# The operator identity reaches catalog objects only through a recorded grant, for 7 days: every run
# grants it anew once it has expired or is about to (ensure_grant, #22).
admin_open
GRANT_PURPOSE="platform operations: flow databases, fixtures and cleanup" \
  ensure_grant admin CATALOG - - MANAGE
# Paimon's default database, which Flink and Spark catalogs look up when they start; every
# identity sees it, none reaches its objects without a grant.
admin_call POST /v1/colvenna/databases '{"name":"default","options":{}}' >/dev/null 2>&1 || true
admin_close
echo "Colvenna Catalog $CATALOG_CANDIDATE ($IMAGE, built from $CATALOG_BUILT_FROM) installed from $SHA" \
  | tee "$evidence/RESULT"
