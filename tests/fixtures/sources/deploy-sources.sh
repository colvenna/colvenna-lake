#!/usr/bin/env bash
# Deploys the environment's test sources from a committed tree: a private MySQL 8.4 and a private
# PostgreSQL 16 (logical replication) RDS instance in the foundation's isolated database subnets,
# reachable only from the EKS cluster security group (deploy/aws/src/test-mysql-stack.ts,
# test-postgres-stack.ts). They stand in for the user's databases; the platform does not need them.
# tests/fixtures/sources/sources.sh prepare then creates their CDC logins. Evidence goes to
# deploy/.state/<env>/sources-<time>/.
set -euo pipefail
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../../acceptance/common.sh
source "$(dirname "$0")/../../acceptance/common.sh"
assert_account
cd "$DEPLOY_DIR/aws"
if [[ -n "$(git status --porcelain=v1)" ]]; then
  echo "Commit or discard source changes before deploying the sources" >&2
  exit 1
fi
source_sha="$(git rev-parse HEAD)"
evidence="$STATE_DIR/sources-$(date -u +%Y%m%d%H%M%S)"
mkdir -p "$evidence"

vpc_id="$(stack_output "$FOUNDATION_STACK" VpcId)"
cluster_name="$(stack_output "$FOUNDATION_STACK" ClusterName)"
eks_security_group_id="$(aws eks describe-cluster --name "$cluster_name" \
  --query cluster.resourcesVpcConfig.clusterSecurityGroupId --output text)"
# The foundation's two isolated database subnets (no route to the internet), by zone.
aws ec2 describe-subnets --filters "Name=vpc-id,Values=$vpc_id" \
  "Name=tag:aws-cdk:subnet-name,Values=database" "Name=tag:aws-cdk:subnet-type,Values=Isolated" \
  --output json >"$evidence/subnets.json"
mapfile -t subnets < <(jq -r '.Subnets | sort_by(.AvailabilityZone)[]
  | select(.MapPublicIpOnLaunch == false) | "\(.SubnetId) \(.AvailabilityZone)"' "$evidence/subnets.json")
if [[ "${#subnets[@]}" != 2 ]]; then
  echo "Expected two private isolated database subnets in $vpc_id" >&2
  exit 1
fi
aws ec2 describe-route-tables --filters "Name=vpc-id,Values=$vpc_id" --output json \
  >"$evidence/route-tables.json"
subnet_ids=() subnet_azs=() route_tables=()
for entry in "${subnets[@]}"; do
  subnet="${entry% *}"
  route_table="$(jq -r --arg s "$subnet" '[.RouteTables[] | select(any(.Associations[]?; .SubnetId == $s))
    | select(all(.Routes[]?; .DestinationCidrBlock != "0.0.0.0/0"))] | if length == 1 then .[0].RouteTableId
    else empty end' "$evidence/route-tables.json")"
  if [[ -z "$route_table" ]]; then
    echo "The route table of $subnet is ambiguous or routes to the internet" >&2
    exit 1
  fi
  subnet_ids+=("$subnet") subnet_azs+=("${entry#* }") route_tables+=("$route_table")
done
# The foundation database's subnet group, which the PostgreSQL source shares.
catalog_subnet_group="$(aws rds describe-db-instances --output json | jq -r \
  --arg endpoint "$(stack_output "$FOUNDATION_STACK" CatalogEndpoint)" \
  '.DBInstances[] | select(.Endpoint.Address == $endpoint) | .DBSubnetGroup.DBSubnetGroupName')"
if [[ -z "$catalog_subnet_group" ]]; then
  echo "No RDS instance answers at the foundation's CatalogEndpoint" >&2
  exit 1
fi

npm ci --no-audit --no-fund >/dev/null
npm run check >/dev/null
npm test >/dev/null
common=(
  "${CDK_ENV_CONTEXT[@]}"
  -c "sourceSha=$source_sha"
  -c "vpcId=$vpc_id"
  -c "eksSecurityGroupId=$eks_security_group_id"
  -c "isolatedSubnetIds=$(IFS=,; echo "${subnet_ids[*]}")"
  -c "isolatedSubnetAzs=$(IFS=,; echo "${subnet_azs[*]}")"
  -c "isolatedRouteTableIds=$(IFS=,; echo "${route_tables[*]}")"
)
cdk_deploy "$TEST_MYSQL_STACK" -c component=test-mysql "${common[@]}" \
  --require-approval never --outputs-file "$evidence/mysql-outputs.json" >"$evidence/mysql-deploy.txt"
cdk_deploy "$TEST_POSTGRES_STACK" -c component=test-postgres "${common[@]}" \
  -c "catalogSubnetGroupName=$catalog_subnet_group" \
  --require-approval never --outputs-file "$evidence/postgres-outputs.json" >"$evidence/postgres-deploy.txt"
for stack in "$TEST_MYSQL_STACK" "$TEST_POSTGRES_STACK"; do
  deployed="$(aws cloudformation describe-stacks --stack-name "$stack" \
    --query "Stacks[0].Outputs[?OutputKey=='SourceSha'].OutputValue | [0]" --output text)"
  if [[ "$deployed" != "$source_sha" ]]; then
    echo "$stack did not finish at $source_sha (it reports $deployed)" >&2
    exit 1
  fi
done
# A major-version upgrade that RDS's pre-checks refuse leaves the instance on its old version while
# the stack update itself completes.
want="$(stack_output "$TEST_MYSQL_STACK" MySqlEngineVersion)"
have="$(aws rds describe-db-instances --output text --query \
  "DBInstances[?Endpoint.Address=='$(stack_output "$TEST_MYSQL_STACK" MySqlEndpoint)'].EngineVersion | [0]")"
if [[ "$have" != "$want" ]]; then
  echo "The test MySQL runs $have, not $want: see its RDS events (PrePatchCompatibility.log)" >&2
  exit 1
fi
echo "Sources $TEST_MYSQL_STACK and $TEST_POSTGRES_STACK at $source_sha (MySQL $have); evidence: $evidence"
