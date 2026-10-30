#!/usr/bin/env bash
# Deploys ColvennaLakeRelease, the release registry (deploy/aws/src/release-stack.ts): the
# colvenna-lake-release/... ECR repositories that release candidates are built into (make-manifest.sh)
# and installed from (deploy/install.sh --release), in the caller's account and COLVENNA_RELEASE_REGION
# (default us-west-2). It belongs to no environment: no COLVENNA_ENV, and no environment's install or
# uninstall touches it. From a committed tree; deploying it again changes nothing that a candidate
# holds.
# Usage: [COLVENNA_RELEASE_REGION=<region>] [COLVENNA_RELEASE_PULL_ACCOUNTS=<account>,<account>] \
#          deploy/scripts/release/deploy-registry.sh
#   COLVENNA_RELEASE_PULL_ACCOUNTS  other accounts whose installs (deploy/install.sh --release) may pull
#                                   the release's images: a repository policy lets their IAM principals
#                                   pull, nothing more; none by default
set -euo pipefail
REPO_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
# shellcheck source-path=SCRIPTDIR source=../lib/git.sh
source "$REPO_DIR/deploy/scripts/lib/git.sh"
# shellcheck source-path=SCRIPTDIR source=../lib/cdk.sh
source "$REPO_DIR/deploy/scripts/lib/cdk.sh"
export AWS_REGION="${COLVENNA_RELEASE_REGION:-us-west-2}"
export AWS_DEFAULT_REGION="$AWS_REGION"
sha="$(committed_sha)"
stack="$(python3 "$REPO_DIR/deploy/scripts/release/manifest.py" stack)"
# The caller's account: the CDK app synthesizes for the account it is given, and refuses other
# credentials.
account="$(aws sts get-caller-identity --query Account --output text)"
echo "$stack in account $account, $AWS_REGION, pull accounts: ${COLVENNA_RELEASE_PULL_ACCOUNTS:-none}, at $sha"
cd "$REPO_DIR/deploy/aws"
npm ci --no-audit --no-fund >/dev/null
npm run check >/dev/null
npm test >/dev/null
cdk_deploy "$stack" --require-approval never -c component=release -c account="$account" -c sourceSha="$sha" \
  ${COLVENNA_RELEASE_PULL_ACCOUNTS:+-c pullAccounts="$COLVENNA_RELEASE_PULL_ACCOUNTS"}
