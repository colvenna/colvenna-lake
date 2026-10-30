#!/usr/bin/env bash
# Installs the monitoring stack (deploy/helm/colvenna-monitoring): Prometheus with Colvenna Lake's alert
# rules, scraping the operator and colvenna-catalog, and Grafana with its panels. Its namespace is the
# one labelled for monitoring, whose pods alone the operator's and catalog's metrics ports admit.
# Grafana's admin password is generated once into a Secret and never printed; sign in through an
# authenticated kubectl port-forward. Nothing is exposed outside the cluster.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
assert_account
ensure_kubeconfig
# Not a catalog client: its pods scrape metrics ports only.
kubectl create namespace "$MONITORING_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label namespace "$MONITORING_NAMESPACE" --overwrite \
  pod-security.kubernetes.io/enforce=restricted colvenna-lake.io/monitoring=true >/dev/null
if ! kubectl -n "$MONITORING_NAMESPACE" get secret "$GRAFANA_ADMIN_SECRET" >/dev/null 2>&1; then
  work="$(mktemp -d)"
  chmod 700 "$work"
  printf "%s" "$(openssl rand -hex 24)" >"$work/password"
  kubectl -n "$MONITORING_NAMESPACE" create secret generic "$GRAFANA_ADMIN_SECRET" \
    --from-file=admin-password="$work/password" >/dev/null
  rm -rf "$work"
fi
helm_upgrade "$MONITORING_RELEASE" "$REPO_DIR/deploy/helm/colvenna-monitoring" \
  --namespace "$MONITORING_NAMESPACE" --set grafana.adminSecret="$GRAFANA_ADMIN_SECRET" \
  --set targets.operator.namespace="$OPERATOR_NAMESPACE" --set targets.catalog.namespace="$CATALOG_NAMESPACE" \
  --set images.prometheus="$(third_party_image prometheus)" --set images.grafana="$(third_party_image grafana)" \
  --wait --timeout 10m
kubectl -n "$MONITORING_NAMESPACE" rollout status "deployment/$MONITORING_RELEASE-prometheus" --timeout=5m
kubectl -n "$MONITORING_NAMESPACE" rollout status "deployment/$MONITORING_RELEASE-grafana" --timeout=5m
echo "Prometheus and Grafana in $MONITORING_NAMESPACE"
