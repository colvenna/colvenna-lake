#!/usr/bin/env bash
# Deploys ColvennaLake<Env>OpenLayer (Polaris's pod and read roles, the reader image repository) from a
# committed tree.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
assert_account
sha="$(committed_sha)"
bucket="$(stack_output "$FOUNDATION_STACK" WarehouseBucket)"
cluster="$(stack_output "$FOUNDATION_STACK" ClusterName)"
issuer="$(aws eks describe-cluster --name "$cluster" --query cluster.identity.oidc.issuer --output text)"
cd "$DEPLOY_DIR/aws"
npm ci --no-audit --no-fund >/dev/null
npm run check >/dev/null
npm test >/dev/null
cdk_deploy "$OPEN_LAYER_STACK" --require-approval never "${CDK_ENV_CONTEXT[@]}" -c component=open-layer \
  -c bucketName="$bucket" -c oidcIssuer="$issuer" -c sourceSha="$sha"
