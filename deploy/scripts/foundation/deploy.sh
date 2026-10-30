#!/usr/bin/env bash
set -euo pipefail
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
# shellcheck source-path=SCRIPTDIR source=../lib/preflight.sh
source "$(dirname "$0")/../lib/preflight.sh"

assert_account
# The region is install.sh's to record, once it has checked the environment's name; a foundation
# deployed before that could land a second environment of the name in another region.
if [[ ! -s "$COLVENNA_REGION_FILE" ]]; then
  echo "No region recorded for $COLVENNA_ENV ($COLVENNA_REGION_FILE): deploy the foundation through" \
    "COLVENNA_ENV=$COLVENNA_ENV COLVENNA_REGION=$COLVENNA_REGION deploy/install.sh (--only foundation)" >&2
  exit 1
fi
# The cluster's admin: the caller's IAM user or role (an assumed role itself, not its temporary
# session).
admin_arn="$(aws sts get-caller-identity --query Arn --output text)"
if [[ "$admin_arn" =~ ^arn:aws:sts::$COLVENNA_ACCOUNT:assumed-role/([^/]+)/ ]]; then
  admin_arn="$(aws iam get-role --role-name "${BASH_REMATCH[1]}" --query Role.Arn --output text)"
fi
if [[ "$admin_arn" != arn:aws:iam::$COLVENNA_ACCOUNT:* ]]; then
  echo "Use an IAM user or role of account $COLVENNA_ACCOUNT, not $admin_arn" >&2
  exit 1
fi

operator_ip="$(curl -fsSL https://checkip.amazonaws.com | tr -d '\n')"
python3 - "$operator_ip" <<'PY'
import ipaddress
import sys
ipaddress.IPv4Address(sys.argv[1])
PY
operator_cidr="$operator_ip/32"

cd "$DEPLOY_DIR/aws"
if [[ -n "$(git status --porcelain=v1)" ]]; then
  echo "Commit or discard tracked/untracked source changes before deploying" >&2
  exit 1
fi
source_sha="$(git rev-parse HEAD)"
npm ci --no-audit --no-fund
npm run check
npm test
# The VPC's two availability zones, given to the stack rather than looked up: an existing
# foundation's own (a VPC does not move), else the region's first two, by name, that offer both node
# types (foundation-spec.json); lib/preflight.sh's choose_zones refuses when two do not.
zones="$(choose_zones)"
echo "availability zones: $zones"
# WRITE_NODES and QUERY_NODES, when set, size the node groups (the stack's default is 1 each).
nodes=()
[[ -n "${WRITE_NODES:-}" ]] && nodes+=(-c "writeNodes=$WRITE_NODES")
[[ -n "${QUERY_NODES:-}" ]] && nodes+=(-c "queryNodes=$QUERY_NODES")
cdk_deploy "$FOUNDATION_STACK" \
  --require-approval never \
  --outputs-file "$STATE_DIR/foundation-outputs.json" \
  "${CDK_ENV_CONTEXT[@]}" -c component=foundation "${nodes[@]}" \
  -c "adminPrincipalArn=$admin_arn" \
  -c "operatorCidr=$operator_cidr" \
  -c "availabilityZones=$zones" \
  -c "sourceSha=$source_sha"

cluster_name="$(stack_output "$FOUNDATION_STACK" ClusterName)"
deployed_sha="$(stack_output "$FOUNDATION_STACK" SourceSha)"
if [[ "$deployed_sha" != "$source_sha" ]]; then
  echo "Deployed source SHA mismatch: $deployed_sha, expected $source_sha" >&2
  exit 1
fi
# wait_eks_update <update id> <what> [<node group>]: waits until an update of the cluster, or of
# one of its node groups, succeeds.
wait_eks_update() {
  local update_status nodegroup=()
  [[ -z "${3:-}" ]] || nodegroup=(--nodegroup-name "$3")
  while true; do
    update_status="$(aws eks describe-update --name "$cluster_name" "${nodegroup[@]}" --update-id "$1" \
      --query update.status --output text)"
    case "$update_status" in
      Successful) return ;;
      Failed|Cancelled)
        echo "EKS $2 update $1 ended: $update_status" >&2
        exit 1
        ;;
    esac
    sleep 15
  done
}
# The upgrade policy the stack declares, on an existing cluster too: the stack's handler sets it only
# when it creates one. Then the date standard support for the cluster's version ends.
support_type="$(stack_output "$FOUNDATION_STACK" UpgradeSupportType)"
if [[ "$(aws eks describe-cluster --name "$cluster_name" --query cluster.upgradePolicy.supportType \
  --output text)" != "$support_type" ]]; then
  wait_eks_update "$(aws eks update-cluster-config --name "$cluster_name" \
    --upgrade-policy "supportType=$support_type" --query update.id --output text)" "upgrade policy"
fi
kubernetes_version="$(stack_output "$FOUNDATION_STACK" KubernetesVersion)"
# The node groups follow the control plane, one at a time, to its version's latest AMI: their
# nodes are replaced one by one and the pods move. The stack cannot do it (foundation-stack.ts).
for nodegroup in $(aws eks list-nodegroups --cluster-name "$cluster_name" --query nodegroups --output text); do
  if [[ "$(aws eks describe-nodegroup --cluster-name "$cluster_name" --nodegroup-name "$nodegroup" \
    --query nodegroup.version --output text)" != "$kubernetes_version" ]]; then
    echo "upgrading node group $nodegroup to Kubernetes $kubernetes_version"
    wait_eks_update "$(aws eks update-nodegroup-version --cluster-name "$cluster_name" \
      --nodegroup-name "$nodegroup" --query update.id --output text)" "node group version" "$nodegroup"
  fi
done
standard_end="$(aws eks describe-cluster-versions --cluster-versions "$kubernetes_version" \
  --query 'clusterVersions[0].endOfStandardSupportDate' --output text)"
echo "Kubernetes $kubernetes_version ($support_type): standard support ends ${standard_end%%T*};" \
  "upgrade before then (docs/manual/install.md, 7)"
if (($(date -d "$standard_end" +%s) - $(date +%s) < 90 * 86400)); then
  echo "WARNING: standard support for Kubernetes $kubernetes_version ends in under 90 days;" \
    "after it the control plane costs \$0.50 an hour more (EKS Extended Support)" >&2
fi
manifest_status="$(aws cloudformation list-stack-resources --stack-name "$FOUNDATION_STACK" \
  --output json | jq -r '[.StackResourceSummaries[]
    | select(.LogicalResourceId | contains("AuthenticatedPublicInfo"))
    | .ResourceStatus] | if length == 1 then .[0] else "None" end')"
if [[ "$manifest_status" != "CREATE_COMPLETE" && "$manifest_status" != "UPDATE_COMPLETE" ]]; then
  echo "Authenticated EKS public-info binding was not applied: $manifest_status" >&2
  exit 1
fi
bootstrap_cidr="$(stack_output "$FOUNDATION_STACK" BootstrapApiCidr)"
python3 - "$bootstrap_cidr" <<'PY'
import ipaddress
import sys
network = ipaddress.IPv4Network(sys.argv[1], strict=True)
if network.prefixlen != 32 or not network.network_address.is_global:
    raise ValueError("bootstrap EIP must be a public IPv4 /32")
PY
if [[ "$bootstrap_cidr" == "$operator_cidr" ]]; then
  echo "Bootstrap EIP unexpectedly matches the operator address" >&2
  exit 1
fi
bootstrap_address="${bootstrap_cidr%/32}"
aws ec2 describe-addresses --public-ips "$bootstrap_address" --output json \
  > "$STATE_DIR/eks-bootstrap-address.json"
if ! jq -e '
  (.Addresses | length == 1)
  and .Addresses[0].AssociationId == null
  and .Addresses[0].NetworkInterfaceId == null
  and .Addresses[0].InstanceId == null
' "$STATE_DIR/eks-bootstrap-address.json" >/dev/null; then
  echo "Bootstrap EIP is associated with a network resource" >&2
  exit 1
fi
update_api_cidr() {
  local target_cidr="$1" update_id
  update_id="$(aws eks update-cluster-config \
    --name "$cluster_name" \
    --resources-vpc-config "endpointPublicAccess=true,endpointPrivateAccess=true,publicAccessCidrs=$target_cidr" \
    --query update.id --output text)"
  wait_eks_update "$update_id" "endpoint restriction"
}
actual_cidr="$(aws eks describe-cluster --name "$cluster_name" --query 'cluster.resourcesVpcConfig.publicAccessCidrs[0]' --output text)"
if [[ "$actual_cidr" != "$operator_cidr" ]]; then
  if [[ "$actual_cidr" != "$bootstrap_cidr" ]]; then
    update_api_cidr "$bootstrap_cidr"
  fi
  update_api_cidr "$operator_cidr"
fi

close_api_on_failure() {
  local result="$1" close_id close_status
  if [[ "$result" == "0" ]]; then
    return
  fi
  echo "EKS authentication verification failed; restoring the bootstrap CIDR" >&2
  if ! close_id="$(aws eks update-cluster-config \
    --name "$cluster_name" \
    --resources-vpc-config "endpointPublicAccess=true,endpointPrivateAccess=true,publicAccessCidrs=$bootstrap_cidr" \
    --query update.id --output text)"; then
    echo "URGENT: Could not restore the bootstrap CIDR for $cluster_name" >&2
    return
  fi
  while true; do
    if ! close_status="$(aws eks describe-update --name "$cluster_name" \
      --update-id "$close_id" --query update.status --output text)"; then
      echo "URGENT: Could not verify EKS API closure for $cluster_name" >&2
      return
    fi
    case "$close_status" in
      Successful) break ;;
      Failed|Cancelled)
        echo "URGENT: EKS API closure ended: $close_status" >&2
        return
        ;;
    esac
    sleep 15
  done
}
trap 'close_api_on_failure "$?"' EXIT

actual_cidr="$(aws eks describe-cluster --name "$cluster_name" --query 'cluster.resourcesVpcConfig.publicAccessCidrs[0]' --output text)"
if [[ "$actual_cidr" != "$operator_cidr" ]]; then
  echo "EKS endpoint CIDR mismatch: $actual_cidr, expected $operator_cidr" >&2
  exit 1
fi
aws eks update-kubeconfig --name "$cluster_name" \
  --kubeconfig "$KUBECONFIG" \
  --alias "colvenna-lake-$COLVENNA_ENV"
binding="$(kubectl get clusterrolebinding system:public-info-viewer -o json)"
if ! jq -e --arg source_sha "$source_sha" --arg operator_cidr "$operator_cidr" '
  .metadata.annotations["rbac.authorization.kubernetes.io/autoupdate"] == "false"
  and .metadata.annotations["colvenna-lake/source-sha"] == $source_sha
  and .metadata.annotations["colvenna-lake/operator-cidr"] == $operator_cidr
  and [.subjects[]?.name] == ["system:authenticated"]
' <<< "$binding" >/dev/null; then
  echo "EKS public-info binding no longer enforces authentication" >&2
  exit 1
fi
endpoint="$(aws eks describe-cluster --name "$cluster_name" --query cluster.endpoint --output text)"
aws eks describe-cluster --name "$cluster_name" --query cluster.certificateAuthority.data \
  --output text | base64 -d > "$STATE_DIR/eks-ca.crt"
for path in /version /livez /readyz /healthz; do
  http_code="$(curl --cacert "$STATE_DIR/eks-ca.crt" --silent --show-error --max-time 10 \
    --output /dev/null --write-out '%{http_code}' "$endpoint$path")"
  if [[ "$http_code" != "401" && "$http_code" != "403" ]]; then
    echo "EKS public $path allows anonymous HTTP $http_code" >&2
    exit 1
  fi
done
trap - EXIT
echo "Deployed cluster $cluster_name, public API limited to $actual_cidr"
echo "KUBECONFIG=$KUBECONFIG"
