#!/usr/bin/env bash
# Installs colvenna-operator from the images build-images.sh recorded for the current SHA.
#  1. The operator's own colvenna-catalog identity: an admin principal (it creates the identities of
#     the sources it manages) with catalog-wide MANAGE (it creates their databases and reads their
#     tables to reconcile them), for 90 days: every run grants it anew once it has expired or is about
#     to (ensure_grant, #22). Its token lands in a Secret in the operator namespace.
#  2. The Helm release, with the managed namespaces (MANAGED_NAMESPACES, comma-separated, default
#     colvenna-data), each with the ColvennaLakeOperator job role that trusts it: deploy-stack.sh with the same
#     MANAGED_NAMESPACES first.
# OPERATOR_TOKEN_TTL (default P90D) sets the token lifetime; rerunning issues a new token only
# when the Secret is missing.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/catalog-admin.sh
source "$(dirname "$0")/../lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
sha="$(committed_sha)"

# Every managed namespace has its own job role in ColvennaLakeOperator, before anything is changed.
roles="$(stack_output "$OPERATOR_STACK" JobRoleArns)"
python3 - "$roles" "$MANAGED_NAMESPACES" <<'PY'
import json, sys
roles = json.loads(sys.argv[1]) if sys.argv[1].startswith("{") else {}
missing = [ns for ns in sys.argv[2].split(",") if not ns or ns not in roles]
if missing:
    sys.exit(f"The operator stack has no job role for {missing}: "
             "run deploy-stack.sh with the same MANAGED_NAMESPACES first")
PY

# The operator calls colvenna-catalog, whose NetworkPolicy admits the catalog client label; so do the
# jobs in the managed namespaces.
ensure_namespace "$OPERATOR_NAMESPACE"
IFS=, read -ra managed <<<"$MANAGED_NAMESPACES"
for ns in "${managed[@]}"; do
  ensure_namespace "$ns"
  ensure_rds_ca "$ns"
done
# Earlier chart versions created the job namespace; Helm deletes what a new version drops unless
# the live object says keep.
kubectl annotate namespace "$JOB_NAMESPACE" --overwrite helm.sh/resource-policy=keep >/dev/null
# Helm installs crds/ only on first install; apply them on every install so changes arrive.
kubectl apply --server-side --force-conflicts -f "$REPO_DIR/deploy/helm/colvenna-operator/crds/" >/dev/null

admin_open
trap admin_close EXIT
if ! kubectl -n "$OPERATOR_NAMESPACE" get secret colvenna-operator-catalog-token >/dev/null 2>&1; then
  status="$(curl -s -o "$ADMIN_DIR/principal.out" -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" \
    -K "$ADMIN_DIR/curlrc" -H 'Content-Type: application/json' \
    --data "{\"name\":\"$OPERATOR_PRINCIPAL\",\"description\":\"colvenna-operator\",\"admin\":true}" \
    "https://localhost:$ADMIN_PORT/colvenna/admin/v1/principals")"
  if [[ "$status" != 200 && "$status" != 409 ]]; then
    echo "Creating $OPERATOR_PRINCIPAL failed with $status: $(cat "$ADMIN_DIR/principal.out")" >&2
    exit 1
  fi
  token="$ADMIN_DIR/operator.token"
  admin_call POST "/colvenna/admin/v1/principals/$OPERATOR_PRINCIPAL/tokens" \
    "{\"ttl\":\"${OPERATOR_TOKEN_TTL:-P90D}\",\"description\":\"colvenna-operator-catalog-token\"}" \
    | python3 -c 'import json,sys; sys.stdout.write(json.load(sys.stdin)["token"])' >"$token"
  [[ -s "$token" ]] || { echo "Could not issue the operator token" >&2; exit 1; }
  kubectl -n "$OPERATOR_NAMESPACE" create secret generic colvenna-operator-catalog-token \
    --from-file=token="$token" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  rm -f "$token"
fi
GRANT_PURPOSE="colvenna-operator manages declared sources" GRANT_APPROVER=requester \
  GRANT_APPROVAL=issue-24 GRANT_DAYS=90 ensure_grant "$OPERATOR_PRINCIPAL" CATALOG - - MANAGE
ca="$ADMIN_DIR/ca.crt"

# The warehouse bucket keeps no object versions and no replicas: an erasure's storage evidence.
bucket="$(helm -n "$CATALOG_NAMESPACE" get values colvenna-catalog -o json \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["warehouse"]["root"].split("/")[2])')"
versioning="$(aws s3api get-bucket-versioning --bucket "$bucket" --query Status --output text)"
replication="$(aws s3api get-bucket-replication --bucket "$bucket" >/dev/null 2>&1 && echo yes || echo no)"
storage=""
if [[ "$versioning" == None && "$replication" == no ]]; then
  storage="bucket $bucket: versioning never enabled, no replication (checked $(date -u +%FT%TZ) by $(aws sts get-caller-identity --query Arn --output text))"
fi

# The platform's Doris, when install-doris.sh made it: the operator serves LakeDashboards there.
doris=""
if kubectl -n "$DORIS_NAMESPACE" get secret "$DORIS_ADMIN_SECRET" >/dev/null 2>&1; then
  doris="$DORIS_CLUSTER-fe-service.$DORIS_NAMESPACE.svc"
fi

# The lineage catalog, when deploy/scripts/open-layer/install-marquez.sh made it: the operator reports
# OpenLineage events there (its namespace is the one labelled to reach it).
lineage=""
if kubectl -n colvenna-lineage get service marquez >/dev/null 2>&1; then
  lineage="http://marquez.colvenna-lineage.svc:5000/api/v1/lineage"
fi

# The open layer's Iceberg catalog, when deploy/scripts/open-layer/install-polaris.sh made it: the operator
# publishes LakeIcebergPublications there with its own Polaris principal.
polaris=""
if kubectl -n "$OPERATOR_NAMESPACE" get secret polaris-operator >/dev/null 2>&1; then
  polaris="https://polaris.colvenna-polaris.svc:8181"
fi

# The BI interface, when deploy/scripts/entry/install-bi.sh made it: the operator publishes dashboard
# users' Doris identities to its Secret.
bi=""
if kubectl -n colvenna-bi get secret colvenna-bi-doris-users >/dev/null 2>&1; then
  bi="colvenna-bi"
fi

values="$(mktemp)"
python3 - "$values" "$sha" "$(image_ref operator)" "$(image_ref mysql)" "$(image_ref postgres)" \
  "$(image_ref mongodb)" "$(image_ref sql)" "$(image_ref kafka)" "$roles" \
  "$(stack_output "$OPERATOR_STACK" CheckpointRoot)" "$MANAGED_NAMESPACES" "$storage" "$doris" \
  "$DORIS_NAMESPACE" "$DORIS_ADMIN_SECRET" "$lineage" "$polaris" "$bi" <<'PY'
import json, sys
(out, sha, operator, mysql, postgres, mongodb, sql, kafka, roles, checkpoints, namespaces, storage,
 doris, doris_namespace, doris_secret, lineage, polaris, bi) = sys.argv[1:]
repository, digest = operator.split("@", 1)
roles, namespaces = json.loads(roles), namespaces.split(",")
json.dump({
    "image": {"repository": repository, "digest": digest},
    "podAnnotations": {"colvenna-lake.io/source-sha": sha},
    "jobs": {
        "images": {"mysql": mysql, "postgres": postgres, "mongodb": mongodb, "sql": sql,
                   "kafka": kafka},
        "roleArns": {ns: roles[ns] for ns in namespaces},
        "checkpointRoot": checkpoints,
    },
    "managedNamespaces": namespaces,
    "nodeSelector": {"colvenna-lake/role": "write"},
    "storageEvidence": storage,
    "doris": {"host": doris, "namespace": doris_namespace, "adminSecret": doris_secret},
    "lineage": {"url": lineage},
    "polaris": {"uri": polaris},
    "bi": {"namespace": bi},
}, open(out, "w"))
PY
helm_upgrade "$OPERATOR_RELEASE" "$REPO_DIR/deploy/helm/colvenna-operator" \
  --namespace "$OPERATOR_NAMESPACE" -f "$values" --set-file catalog.caCert="$ca" \
  --wait --timeout 5m
rm -f "$values"
kubectl -n "$OPERATOR_NAMESPACE" rollout status deployment/"$OPERATOR_RELEASE" --timeout=5m
kubectl -n "$OPERATOR_NAMESPACE" get pods -l app.kubernetes.io/name=colvenna-operator -o wide
