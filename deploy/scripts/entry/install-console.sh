#!/usr/bin/env bash
# Installs colvenna-console (deploy/helm/colvenna-console) behind the entry: the pods are bound to the
# stack's target group, accept connections from the load balancer's subnets only, and write their
# audit to the stack's log group; their controlled operations act in the managed namespace as the
# console's two users. Waits until the target group reports the pods healthy.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
assert_account
ensure_kubeconfig
image="$(entry_image console)"
# Not a catalog client; it reads the managed namespaces' declarations (Kubernetes API) and the
# monitoring stack's Prometheus, which admits namespaces labelled as its readers.
kubectl create namespace "$CONSOLE_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label namespace "$CONSOLE_NAMESPACE" --overwrite pod-security.kubernetes.io/enforce=restricted \
  colvenna-lake.io/prometheus-reader=true >/dev/null
# The key that signs the confirmations of irreversible operations: made once, kept on
# reinstall; random, never printed.
if ! kubectl -n "$CONSOLE_NAMESPACE" get secret colvenna-console-confirm >/dev/null 2>&1; then
  openssl rand 48 | kubectl -n "$CONSOLE_NAMESPACE" create secret generic colvenna-console-confirm --from-file=key=/dev/stdin >/dev/null
fi
group="$(entry_output consoleTargetGroupArn)"
helm_upgrade "$CONSOLE_RELEASE" "$REPO_DIR/deploy/helm/colvenna-console" --namespace "$CONSOLE_NAMESPACE" \
  --set image.repository="${image%@*}" --set image.digest="${image#*@}" --set region="$COLVENNA_REGION" \
  --set serviceAccount.roleArn="$(entry_output ConsoleRoleArn)" \
  --set entry.publicUrl="$(entry_output consoleUrl)" --set entry.signInUrl="$(entry_output SignInDomain)" \
  --set entry.userPoolId="$(entry_output UserPoolId)" --set entry.clientId="$(entry_output UserPoolClientId)" \
  --set entry.loadBalancerArn="$(entry_output LoadBalancerArn)" --set entry.targetGroupArn="$group" \
  --set "entry.subnetCidrs={$(entry_output PublicSubnetCidrs)}" \
  --set entry.sessionCookie="$(entry_output consoleSessionCookie)" \
  --set-json entry.roleGroups="$(entry_output RoleGroups)" \
  --set auditLogGroup="$(entry_output ConsoleAuditLogGroup)" \
  --set "pipeline.namespaces={$JOB_NAMESPACE}" \
  --set pipeline.prometheusUrl="http://$MONITORING_RELEASE-prometheus.$MONITORING_NAMESPACE.svc:9090" \
  --wait --timeout 10m
kubectl -n "$CONSOLE_NAMESPACE" rollout status "deployment/$CONSOLE_RELEASE" --timeout=5m
want="$(kubectl -n "$CONSOLE_NAMESPACE" get deployment "$CONSOLE_RELEASE" -o jsonpath='{.spec.replicas}')"
for _ in $(seq 1 60); do
  healthy="$(aws elbv2 describe-target-health --target-group-arn "$group" \
    --query "length(TargetHealthDescriptions[?TargetHealth.State=='healthy'])" --output text)"
  [[ "$healthy" == "$want" ]] && break
  sleep 10
done
[[ "$healthy" == "$want" ]] || { echo "target group: $healthy of $want targets healthy" >&2; exit 1; }
echo "colvenna-console at $(entry_output consoleUrl): $healthy targets healthy"
