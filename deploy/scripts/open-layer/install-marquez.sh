#!/usr/bin/env bash
# Installs Marquez 0.51.1, the lineage catalog the operator reports OpenLineage events to in the
# lineage acceptance (LLD "血缘"; the user chooses the catalog, and DataHub or OpenMetadata take
# the same events). Marquez has no authentication, so nothing outside the cluster can reach it:
#  1. Its database on the project RDS (database and role marquez), over verified TLS.
#  2. The API and the web UI as ClusterIP services only (no LoadBalancer, NodePort or Ingress),
#     run as non-root under Pod Security "restricted".
#  3. A NetworkPolicy admitting to the API only the namespaces labelled LINEAGE_CLIENT_LABEL (the
#     operator's) and the web UI; the UI admits nothing. People reach either through
#     `kubectl port-forward`, which the cluster's IAM authentication guards.
# Rerunning keeps the database password. Then rerun deploy/scripts/operator/install-operator.sh: it
# points the operator at Marquez once the service exists.
# usage: install-marquez.sh <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/marquez.sh
source "$(dirname "$0")/../lib/marquez.sh"
assert_account
ensure_kubeconfig
evidence="${1:?usage: install-marquez.sh <evidence-dir>}"
mkdir -p "$evidence"
sha="$(committed_sha)"
work="$(mktemp -d)"
chmod 700 "$work"
# shellcheck disable=SC2317  # invoked through the EXIT trap
cleanup() {
  kubectl -n "$LINEAGE_NAMESPACE" delete secret marquez-db-bootstrap --ignore-not-found \
    >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT
db_endpoint="$(stack_output "$FOUNDATION_STACK" CatalogEndpoint)"

kubectl create namespace "$LINEAGE_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label namespace "$LINEAGE_NAMESPACE" --overwrite \
  pod-security.kubernetes.io/enforce=restricted >/dev/null
# The operator reports; nothing else is labelled.
kubectl label namespace "$OPERATOR_NAMESPACE" --overwrite "$LINEAGE_CLIENT_LABEL=true" >/dev/null

# --- database -----------------------------------------------------------------------------------
curl -fsSL https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem -o "$work/rds-ca.pem"
grep -q "BEGIN CERTIFICATE" "$work/rds-ca.pem"
kubectl -n "$LINEAGE_NAMESPACE" create configmap marquez-db-ca --from-file=ca.pem="$work/rds-ca.pem" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
if kubectl -n "$LINEAGE_NAMESPACE" get secret marquez-db >/dev/null 2>&1; then
  kubectl -n "$LINEAGE_NAMESPACE" get secret marquez-db -o jsonpath='{.data.password}' \
    | base64 -d >"$work/password"
else
  openssl rand -hex 24 | tr -d '\n' >"$work/password"
fi
printf marquez >"$work/username"
kubectl -n "$LINEAGE_NAMESPACE" create secret generic marquez-db \
  --from-file=username="$work/username" --from-file=password="$work/password" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
cp "$work/password" "$work/MARQUEZ_PASSWORD"
rm -f "$work/password"
aws secretsmanager get-secret-value --secret-id "$(stack_output "$FOUNDATION_STACK" CatalogSecretArn)" \
  --query SecretString --output text | python3 -c '
import json, os, sys
s = json.load(sys.stdin)
open(os.path.join(sys.argv[1], "PGUSER"), "w").write(s["username"])
open(os.path.join(sys.argv[1], "PGPASSWORD"), "w").write(s["password"])' "$work"
kubectl -n "$LINEAGE_NAMESPACE" create secret generic marquez-db-bootstrap \
  --from-file=PGUSER="$work/PGUSER" --from-file=PGPASSWORD="$work/PGPASSWORD" \
  --from-file=MARQUEZ_PASSWORD="$work/MARQUEZ_PASSWORD" --dry-run=client -o yaml \
  | kubectl apply -f - >/dev/null
rm -f "$work/PGUSER" "$work/PGPASSWORD" "$work/MARQUEZ_PASSWORD"
cat >"$work/db.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: marquez-db-bootstrap}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 70, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: psql
      image: $POSTGRES_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      envFrom: [{secretRef: {name: marquez-db-bootstrap}}]
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
          \getenv pw MARQUEZ_PASSWORD
          SELECT format('CREATE ROLE marquez LOGIN PASSWORD %L', :'pw')
            WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'marquez') \gexec
          ALTER ROLE marquez WITH LOGIN PASSWORD :'pw';
          GRANT marquez TO CURRENT_USER;
          SELECT 'CREATE DATABASE marquez OWNER marquez'
            WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'marquez') \gexec
          REVOKE ALL ON DATABASE marquez FROM PUBLIC;
          GRANT CONNECT, TEMPORARY, CREATE ON DATABASE marquez TO marquez;
          SQL
          echo database-ready
  volumes: [{name: ca, configMap: {name: marquez-db-ca}}]
YAML
run_pod "$LINEAGE_NAMESPACE" marquez-db-bootstrap "$work/db.yaml" | tee "$evidence/db-bootstrap.log"
grep -q '^database-ready$' "$evidence/db-bootstrap.log"
kubectl -n "$LINEAGE_NAMESPACE" delete secret marquez-db-bootstrap >/dev/null

# --- Marquez ------------------------------------------------------------------------------------
cat >"$work/marquez.yaml" <<YAML
apiVersion: v1
kind: ConfigMap
metadata: {name: marquez-config, namespace: $LINEAGE_NAMESPACE}
data:
  marquez.yml: |
    server:
      applicationConnectors:
        - {type: http, port: 5000, httpCompliance: RFC7230_LEGACY}
      adminConnectors:
        - {type: http, port: 5001}
    db:
      driverClass: org.postgresql.Driver
      url: jdbc:postgresql://$db_endpoint:5432/marquez?sslmode=verify-full&sslrootcert=/db-ca/ca.pem
      user: \${POSTGRES_USER}
      password: \${POSTGRES_PASSWORD}
    migrateOnStartup: true
    graphql: {enabled: false}
    search: {enabled: false}
    logging:
      level: INFO
      appenders: [{type: console}]
---
apiVersion: apps/v1
kind: Deployment
metadata: {name: marquez, namespace: $LINEAGE_NAMESPACE, labels: {app: marquez}}
spec:
  replicas: 1
  selector: {matchLabels: {app: marquez}}
  template:
    metadata:
      labels: {app: marquez}
      annotations: {colvenna-lake.io/source-sha: "$sha"}
    spec:
      automountServiceAccountToken: false
      securityContext: {runAsNonRoot: true, runAsUser: 1000, runAsGroup: 1000, seccompProfile: {type: RuntimeDefault}}
      containers:
        - name: marquez
          image: $MARQUEZ_IMAGE
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: {drop: ["ALL"]}
          env:
            - {name: MARQUEZ_CONFIG, value: /config/marquez.yml}
            - {name: JAVA_OPTS, value: "-Xmx512m -Djava.io.tmpdir=/tmp"}
            - {name: POSTGRES_USER, valueFrom: {secretKeyRef: {name: marquez-db, key: username}}}
            - {name: POSTGRES_PASSWORD, valueFrom: {secretKeyRef: {name: marquez-db, key: password}}}
          ports:
            - {name: api, containerPort: 5000}
            - {name: admin, containerPort: 5001}
          readinessProbe: {httpGet: {path: /healthcheck, port: admin}, periodSeconds: 10}
          livenessProbe: {httpGet: {path: /healthcheck, port: admin}, periodSeconds: 20, failureThreshold: 6, initialDelaySeconds: 60}
          resources: {requests: {cpu: 250m, memory: 768Mi}, limits: {memory: 1Gi}}
          volumeMounts:
            - {name: config, mountPath: /config, readOnly: true}
            - {name: db-ca, mountPath: /db-ca, readOnly: true}
            - {name: tmp, mountPath: /tmp}
      volumes:
        - {name: config, configMap: {name: marquez-config}}
        - {name: db-ca, configMap: {name: marquez-db-ca}}
        - {name: tmp, emptyDir: {}}
---
apiVersion: v1
kind: Service
metadata: {name: marquez, namespace: $LINEAGE_NAMESPACE}
spec:
  type: ClusterIP
  selector: {app: marquez}
  ports: [{name: api, port: 5000, targetPort: api}]
---
apiVersion: apps/v1
kind: Deployment
metadata: {name: marquez-web, namespace: $LINEAGE_NAMESPACE, labels: {app: marquez-web}}
spec:
  replicas: 1
  selector: {matchLabels: {app: marquez-web}}
  template:
    metadata:
      labels: {app: marquez-web}
      annotations: {colvenna-lake.io/source-sha: "$sha"}
    spec:
      automountServiceAccountToken: false
      securityContext: {runAsNonRoot: true, runAsUser: 1000, runAsGroup: 1000, seccompProfile: {type: RuntimeDefault}}
      containers:
        - name: web
          image: $MARQUEZ_WEB_IMAGE
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: {drop: ["ALL"]}
          env:
            - {name: MARQUEZ_HOST, value: marquez}
            - {name: MARQUEZ_PORT, value: "5000"}
            - {name: WEB_PORT, value: "3000"}
          ports: [{name: web, containerPort: 3000}]
          readinessProbe: {tcpSocket: {port: web}, periodSeconds: 10}
          resources: {requests: {cpu: 50m, memory: 128Mi}, limits: {memory: 256Mi}}
          volumeMounts: [{name: tmp, mountPath: /tmp}]
      volumes: [{name: tmp, emptyDir: {}}]
---
apiVersion: v1
kind: Service
metadata: {name: marquez-web, namespace: $LINEAGE_NAMESPACE}
spec:
  type: ClusterIP
  selector: {app: marquez-web}
  ports: [{name: web, port: 3000, targetPort: web}]
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: {name: marquez, namespace: $LINEAGE_NAMESPACE}
spec:
  podSelector: {matchLabels: {app: marquez}}
  policyTypes: [Ingress]
  ingress:
    - from:
        - namespaceSelector: {matchLabels: {$LINEAGE_CLIENT_LABEL: "true"}}
        - podSelector: {matchLabels: {app: marquez-web}}
      ports: [{port: 5000, protocol: TCP}]
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: {name: marquez-web, namespace: $LINEAGE_NAMESPACE}
spec:
  podSelector: {matchLabels: {app: marquez-web}}
  policyTypes: [Ingress]
  ingress: []
YAML
cp "$work/marquez.yaml" "$evidence/marquez.yaml"
if grep -Eq 'type: (LoadBalancer|NodePort)|kind: (Ingress|Gateway|HTTPRoute)' "$work/marquez.yaml"; then
  echo "The manifests expose Marquez beyond ClusterIP" >&2
  exit 1
fi
kubectl apply -f "$work/marquez.yaml" >/dev/null
kubectl -n "$LINEAGE_NAMESPACE" rollout status deploy/marquez --timeout=10m
kubectl -n "$LINEAGE_NAMESPACE" rollout status deploy/marquez-web --timeout=5m
kubectl -n "$LINEAGE_NAMESPACE" get pods,services,networkpolicies -o wide >"$evidence/objects.txt"
echo "Marquez is up; rerun deploy/scripts/operator/install-operator.sh to report lineage to it."
