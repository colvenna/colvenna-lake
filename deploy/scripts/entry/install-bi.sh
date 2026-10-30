#!/usr/bin/env bash
# Installs the BI interface (deploy/helm/colvenna-bi: Superset 6.1.0 with colvenna_bi) behind the entry:
#  1. Its namespace, at Pod Security "restricted" and labelled to reach the platform's Doris.
#  2. Its metadata database on the project RDS (database and role superset), over verified TLS.
#  3. Superset's secret key, and the Secret the operator publishes dashboard users' Doris
#     identities to (made empty; the operator writes the entries and may change nothing else).
#  4. The release: the pod bound to the stack's target group, admitting the load balancer's
#     subnets only; Superset's schema and roles made by the chart's setup job first.
# Rerunning keeps the passwords and the secret key. Then rerun deploy/scripts/operator/install-operator.sh:
# it points the operator at the Secret once it exists.
# usage: install-bi.sh <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
assert_account
ensure_kubeconfig
evidence="${1:?usage: install-bi.sh <evidence-dir>}"
mkdir -p "$evidence"
image="$(entry_image bi)"
work="$(mktemp -d)"
chmod 700 "$work"
# shellcheck disable=SC2317  # invoked through the EXIT trap
cleanup() {
  kubectl -n "$BI_NAMESPACE" delete secret colvenna-bi-db-bootstrap --ignore-not-found >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT
db_endpoint="$(stack_output "$FOUNDATION_STACK" CatalogEndpoint)"

kubectl create namespace "$BI_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label namespace "$BI_NAMESPACE" --overwrite pod-security.kubernetes.io/enforce=restricted \
  colvenna-lake.io/doris-client=true >/dev/null

# --- metadata database --------------------------------------------------------------------------
curl -fsSL https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem -o "$work/rds-ca.pem"
grep -q "BEGIN CERTIFICATE" "$work/rds-ca.pem"
kubectl -n "$BI_NAMESPACE" create configmap colvenna-bi-rds-ca --from-file=ca.pem="$work/rds-ca.pem" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
if kubectl -n "$BI_NAMESPACE" get secret colvenna-bi-metadata >/dev/null 2>&1; then
  kubectl -n "$BI_NAMESPACE" get secret colvenna-bi-metadata -o jsonpath='{.data.password}' | base64 -d >"$work/password"
else
  openssl rand -hex 24 | tr -d '\n' >"$work/password"
fi
python3 - "$work" "$db_endpoint" <<'PY'
import os, sys
work, host = sys.argv[1:]
password = open(os.path.join(work, "password")).read()
open(os.path.join(work, "uri"), "w").write(
    "postgresql+psycopg2://superset:%s@%s:5432/superset?sslmode=verify-full"
    "&sslrootcert=/etc/colvenna-bi/rds-ca/ca.pem" % (password, host))
PY
kubectl -n "$BI_NAMESPACE" create secret generic colvenna-bi-metadata \
  --from-file=uri="$work/uri" --from-file=password="$work/password" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
cp "$work/password" "$work/SUPERSET_PASSWORD"
rm -f "$work/password" "$work/uri"
aws secretsmanager get-secret-value --secret-id "$(stack_output "$FOUNDATION_STACK" CatalogSecretArn)" \
  --query SecretString --output text | python3 -c '
import json, os, sys
s = json.load(sys.stdin)
open(os.path.join(sys.argv[1], "PGUSER"), "w").write(s["username"])
open(os.path.join(sys.argv[1], "PGPASSWORD"), "w").write(s["password"])' "$work"
kubectl -n "$BI_NAMESPACE" create secret generic colvenna-bi-db-bootstrap \
  --from-file=PGUSER="$work/PGUSER" --from-file=PGPASSWORD="$work/PGPASSWORD" \
  --from-file=SUPERSET_PASSWORD="$work/SUPERSET_PASSWORD" --dry-run=client -o yaml \
  | kubectl apply -f - >/dev/null
rm -f "$work/PGUSER" "$work/PGPASSWORD" "$work/SUPERSET_PASSWORD"
cat >"$work/db.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: colvenna-bi-db-bootstrap}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 70, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: psql
      image: $POSTGRES_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      envFrom: [{secretRef: {name: colvenna-bi-db-bootstrap}}]
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
          \getenv pw SUPERSET_PASSWORD
          SELECT format('CREATE ROLE superset LOGIN PASSWORD %L', :'pw')
            WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'superset') \gexec
          ALTER ROLE superset WITH LOGIN PASSWORD :'pw';
          GRANT superset TO CURRENT_USER;
          SELECT 'CREATE DATABASE superset OWNER superset'
            WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'superset') \gexec
          REVOKE ALL ON DATABASE superset FROM PUBLIC;
          GRANT CONNECT, TEMPORARY, CREATE ON DATABASE superset TO superset;
          SQL
          echo database-ready
  volumes: [{name: ca, configMap: {name: colvenna-bi-rds-ca}}]
YAML
run_pod "$BI_NAMESPACE" colvenna-bi-db-bootstrap "$work/db.yaml" | tee "$evidence/db-bootstrap.log"
grep -q '^database-ready$' "$evidence/db-bootstrap.log"
kubectl -n "$BI_NAMESPACE" delete secret colvenna-bi-db-bootstrap >/dev/null

# --- secrets ------------------------------------------------------------------------------------
if ! kubectl -n "$BI_NAMESPACE" get secret colvenna-bi-app >/dev/null 2>&1; then
  openssl rand -hex 32 | tr -d '\n' >"$work/secret-key"
  openssl rand -hex 32 | tr -d '\n' >"$work/guest-token-secret"
  kubectl -n "$BI_NAMESPACE" create secret generic colvenna-bi-app --from-file=secret-key="$work/secret-key" \
    --from-file=guest-token-secret="$work/guest-token-secret" >/dev/null
  rm -f "$work/secret-key" "$work/guest-token-secret"
fi
if ! kubectl -n "$BI_NAMESPACE" get secret "$BI_DORIS_USERS_SECRET" >/dev/null 2>&1; then
  kubectl -n "$BI_NAMESPACE" create secret generic "$BI_DORIS_USERS_SECRET" >/dev/null
fi

# --- release ------------------------------------------------------------------------------------
group="$(entry_output biTargetGroupArn)"
helm_upgrade "$BI_RELEASE" "$REPO_DIR/deploy/helm/colvenna-bi" --namespace "$BI_NAMESPACE" \
  --set image.repository="${image%@*}" --set image.digest="${image#*@}" --set region="$COLVENNA_REGION" \
  --set serviceAccount.roleArn="$(entry_output BiRoleArn)" \
  --set entry.publicUrl="$(entry_output biUrl)" --set entry.signInUrl="$(entry_output SignInDomain)" \
  --set entry.userPoolId="$(entry_output UserPoolId)" --set entry.clientId="$(entry_output UserPoolClientId)" \
  --set entry.loadBalancerArn="$(entry_output LoadBalancerArn)" --set entry.targetGroupArn="$group" \
  --set entry.sessionCookie="$(entry_output biSessionCookie)" \
  --set-json entry.roleGroups="$(entry_output RoleGroups)" \
  --set "entry.subnetCidrs={$(entry_output PublicSubnetCidrs)}" \
  --set doris.host="$DORIS_CLUSTER-fe-service.$DORIS_NAMESPACE.svc" \
  --set nodeSelector."colvenna-lake/role"=query \
  --wait --timeout 20m
kubectl -n "$BI_NAMESPACE" rollout status "deployment/$BI_RELEASE" --timeout=10m
for _ in $(seq 1 60); do
  healthy="$(aws elbv2 describe-target-health --target-group-arn "$group" \
    --query "length(TargetHealthDescriptions[?TargetHealth.State=='healthy'])" --output text)"
  [[ "$healthy" == 1 ]] && break
  sleep 10
done
[[ "$healthy" == 1 ]] || { echo "target group: $healthy of 1 targets healthy" >&2; exit 1; }
echo "colvenna-bi at $(entry_output biUrl): healthy"
