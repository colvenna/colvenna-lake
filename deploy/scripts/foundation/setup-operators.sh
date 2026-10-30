#!/usr/bin/env bash
set -euo pipefail
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"

assert_account
assert_cluster

charts_dir="$TOOLS_DIR/charts"
mkdir -p "$charts_dir"
flink_chart="$charts_dir/flink-kubernetes-operator-1.16.1-helm.tgz"
doris_chart="$charts_dir/doris-operator-25.8.0.tgz"
if [[ ! -f "$flink_chart" ]]; then
  curl -fsSL https://downloads.apache.org/flink/flink-kubernetes-operator-1.16.1/flink-kubernetes-operator-1.16.1-helm.tgz \
    -o "$flink_chart"
fi
if [[ ! -f "$doris_chart" ]]; then
  curl -fsSL https://charts.selectdb.com/doris-operator-25.8.0.tgz -o "$doris_chart"
fi
printf '%s  %s\n' \
  e3e107e7bf0a9e9762d699f81d782991a974d3193532e4953a4d4a8e3e9d8579 "$flink_chart" \
  58eab110cd613bf4c39a9391dff937a80db25aca015bcc4ccf36cb1807dd1371 "$doris_chart" \
  | sha256sum --check

# Both operators run from the environment's own copy of their image, by digest
# (deploy/images/third-party.tsv): the chart's tag-pinned default image may be rebuilt or dropped
# upstream.
flink_values=(
  --set webhook.create=false
  --set image.repository="$THIRD_PARTY_REPOSITORY"
  --set image.digest="$(third_party_digest flink-kubernetes-operator)"
)
helm template flink-kubernetes-operator "$flink_chart" --namespace flink-operator "${flink_values[@]}" \
  | node "$REPO_DIR/deploy/scripts/lib/assert-private-manifests.cjs"
# The chart's default asks for 2 CPU and 4 GiB. The Doris Operator itself, measured on uat from the
# kubelet's stats (2026-10-21: 27 minutes with two DorisClusters made and removed beside the
# platform's, its caches holding all of uat's objects; then its restart with these values): at most
# 6m CPU and 40 MiB. Requests (lib/common.sh's DORIS_OPERATOR_RESOURCES): 100m and 128 MiB, enough
# for a cluster several times uat's size. Limits: 500m, which only throttles, and 512 MiB, above the
# request so that a larger cache or a busy start is not killed.
# The chart writes <repository>:<tag>: the tag carries the digest, which the pull uses.
doris_values=(
  --set dorisOperator.image.repository="$THIRD_PARTY_REPOSITORY"
  --set dorisOperator.image.tag="$(third_party_tag doris-operator)@$(third_party_digest doris-operator)"
  --set dorisOperator.image.imagePullPolicy=IfNotPresent
  --set "dorisOperator.resources.requests.cpu=${DORIS_OPERATOR_RESOURCES[0]}"
  --set "dorisOperator.resources.requests.memory=${DORIS_OPERATOR_RESOURCES[1]}"
  --set "dorisOperator.resources.limits.cpu=${DORIS_OPERATOR_RESOURCES[2]}"
  --set "dorisOperator.resources.limits.memory=${DORIS_OPERATOR_RESOURCES[3]}"
)
helm template doris-operator "$doris_chart" --namespace doris-operator "${doris_values[@]}" \
  | node "$REPO_DIR/deploy/scripts/lib/assert-private-manifests.cjs"

helm_upgrade flink-kubernetes-operator "$flink_chart" \
  --namespace flink-operator --create-namespace "${flink_values[@]}" \
  --wait --timeout 10m
kubectl rollout status deployment/flink-kubernetes-operator -n flink-operator --timeout=10m

helm_upgrade doris-operator "$doris_chart" \
  --namespace doris-operator --create-namespace "${doris_values[@]}" \
  --wait --timeout 10m
kubectl rollout status deployment/doris-operator -n doris-operator --timeout=10m

kubectl get crd flinkdeployments.flink.apache.org dorisclusters.doris.selectdb.com
