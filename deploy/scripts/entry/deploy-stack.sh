#!/usr/bin/env bash
# Deploys ColvennaLake<Env>Entry (the public entry: Cognito, certificate, load balancer, names, pod roles, the
# console image repository) from a committed tree. SESSION_TIMEOUT_SECONDS sets how long a sign-in
# lasts at the load balancer (default 8 hours).
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
assert_account
assert_hosted_zone
sha="$(committed_sha)"
cluster="$(stack_output "$FOUNDATION_STACK" ClusterName)"
vpc="$(stack_output "$FOUNDATION_STACK" VpcId)"
issuer="$(aws eks describe-cluster --name "$cluster" --query cluster.identity.oidc.issuer --output text)"
cluster_sg="$(aws eks describe-cluster --name "$cluster" \
  --query cluster.resourcesVpcConfig.clusterSecurityGroupId --output text)"
# The VPC's public subnets (the ones tagged for internet-facing load balancers), by zone.
subnets="$(aws ec2 describe-subnets --filters "Name=vpc-id,Values=$vpc" "Name=tag:kubernetes.io/role/elb,Values=1" \
  --query 'sort_by(Subnets, &AvailabilityZone)[].[SubnetId,CidrBlock,AvailabilityZone]' --output text)"
ids="$(awk '{print $1}' <<<"$subnets" | paste -sd,)"
cidrs="$(awk '{print $2}' <<<"$subnets" | paste -sd,)"
zones="$(awk '{print $3}' <<<"$subnets" | paste -sd,)"
cd "$DEPLOY_DIR/aws"
npm ci --no-audit --no-fund >/dev/null
npm run check >/dev/null
npm test >/dev/null
cdk_deploy "$ENTRY_STACK" --require-approval never "${CDK_ENV_CONTEXT[@]}" -c component=entry \
  -c sourceSha="$sha" -c oidcIssuer="$issuer" -c vpcId="$vpc" -c publicSubnetIds="$ids" \
  -c publicSubnetCidrs="$cidrs" -c availabilityZones="$zones" -c clusterSecurityGroupId="$cluster_sg" \
  -c hostedZoneId="$HOSTED_ZONE_ID" -c hostedZoneName="$HOSTED_ZONE_NAME" -c domain="$ENTRY_DOMAIN" \
  -c sessionTimeoutSeconds="${SESSION_TIMEOUT_SECONDS:-28800}"
