#!/usr/bin/env bash
# Installs the AWS Load Balancer Controller, only to keep pods registered in the entry stack's
# target groups (TargetGroupBinding). It handles no Ingress and no Service, and its IAM role
# (ColvennaLakeEntry's LoadBalancerControllerRole) can describe and register targets, nothing more: no
# Kubernetes object can make a load balancer through it. The chart is pinned by checksum, the
# image by digest, run from the environment's own copy (deploy/images/third-party.tsv; the chart
# writes <repository>:<tag>, and the tag carries the digest). keepTLSSecret: the chart's webhook certificate is made once and kept; by default
# every upgrade makes a new CA, which the webhook configuration trusts at once while the running
# controller still serves the old certificate until its mounted Secret is refreshed (a minute or
# more): a TargetGroupBinding written then, as install-console.sh does right after this step when no
# image is built in between (install.sh --release), is refused with "x509: certificate signed by
# unknown authority".
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
assert_account
ensure_kubeconfig
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
helm pull aws-load-balancer-controller --repo https://aws.github.io/eks-charts \
  --version "$LBC_CHART_VERSION" --destination "$work" >/dev/null
chart="$work/aws-load-balancer-controller-$LBC_CHART_VERSION.tgz"
printf '%s  %s\n' "$LBC_CHART_SHA256" "$chart" | sha256sum --check --quiet
helm_upgrade "$LBC_RELEASE" "$chart" --namespace "$LBC_NAMESPACE" \
  --set clusterName="$(stack_output "$FOUNDATION_STACK" ClusterName)" \
  --set region="$COLVENNA_REGION" --set vpcId="$(stack_output "$FOUNDATION_STACK" VpcId)" \
  --set image.repository="$THIRD_PARTY_REPOSITORY" \
  --set image.tag="$(third_party_tag aws-load-balancer-controller)@$(third_party_digest aws-load-balancer-controller)" \
  --set serviceAccount.annotations."eks\.amazonaws\.com/role-arn"="$(entry_output LoadBalancerControllerRoleArn)" \
  --set createIngressClassResource=false --set ingressClass=colvenna-lake-none \
  --set disableIngressClassAnnotation=true --set enableServiceMutatorWebhook=false \
  --set webhookConfig.disableIngressValidation=true \
  --set controllerConfig.featureGates.EnableServiceController=false \
  --set enableShield=false --set enableWaf=false --set enableWafv2=false \
  --set resources.requests.cpu=50m --set resources.requests.memory=128Mi --set keepTLSSecret=true \
  --wait --timeout 10m
kubectl -n "$LBC_NAMESPACE" rollout status "deployment/$LBC_RELEASE" --timeout=5m
echo "AWS Load Balancer Controller $LBC_CHART_VERSION in $LBC_NAMESPACE (target group bindings only)"
