#!/usr/bin/env bash
# The exposure inventory of one environment, for the release check (#208): what of the account, in
# the environment's region, can be reached from the internet, whose it is, and how it authenticates.
# Read-only: AWS describe, list and get calls, and kubectl get. Raw answers go to <evidence>/raw/;
# exposure.py classify turns them into inventory.tsv (the environment's resources, and the release
# registry's), others.tsv (the rest of the account in the region) and these checks, in
# <evidence>/verdicts.tsv:
#   - no Lambda function URL on any function of any region the account has enabled, whoever's;
#   - of the environment, only the entry's load balancer, its NAT gateway and the unassociated
#     address of the EKS endpoint's bootstrap CIDR have public addresses; the entry signs in first;
#   - the EKS endpoint takes IAM only, from /32s; no database is public; no LoadBalancer or NodePort
#     Service, no Ingress; every bucket blocks public access and plain HTTP; the user pool takes no
#     self sign-up and asks for TOTP; no repository, function or secret policy names everyone; no API
#     Gateway or CloudFront distribution of the environment; its DNS names point at the entry.
#
# usage: COLVENNA_ENV=<env> tests/acceptance/security/inventory.sh <new evidence-dir>
# The directory must hold no earlier run: the inventory's verdict rows carry its own id (also in
# raw/env.json), and run-denials.sh then counts no row but this inventory's and its own.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
assert_account
ensure_kubeconfig
evidence="${1:?usage: inventory.sh <new evidence-dir>}"
here="$(cd "$(dirname "$0")" && pwd)"
raw="$evidence/raw"
if [[ -e "$raw" || -e "$evidence/verdicts.tsv" || -e "$evidence/inventory.log" ]]; then
  echo "$evidence already holds a run's evidence: inventory into a new directory" >&2
  exit 1
fi
inventory_id="inventory-$(date -u +%Y%m%dT%H%M%SZ)-$(openssl rand -hex 3)"
mkdir -p "$raw"/{lb,s3,cognito,ecr,secrets,lambda}
exec > >(tee -a "$evidence/inventory.log") 2>&1
account="$(aws sts get-caller-identity --query Account --output text)"
scripts_sha="$(committed_sha)"
echo "$(date -u +%FT%TZ) $inventory_id of $COLVENNA_ENV in $COLVENNA_REGION, account $account, by $(aws sts get-caller-identity --query Arn --output text); scripts at $scripts_sha"
echo "$scripts_sha" >"$evidence/scripts-sha.txt"
failed=0
verdict() {
  local detail="${3:-}"
  detail="${detail//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${detail//$'\t'/ }	$inventory_id" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS || "$2" == N/A ]] || failed=1
}

# save <file> <aws arguments...>: the answer as JSON. An answer that the thing does not exist (no
# policy, no Block Public Access configuration, no encryption) is saved as null; any other failure
# stops the inventory: a resource it could not read is not one it found clean.
save() {
  local file="$1" err
  shift
  err="$(mktemp)"
  if aws "$@" --output json >"$file" 2>"$err"; then
    rm -f "$err"
    return 0
  fi
  if grep -qE 'NoSuchBucketPolicy|NoSuchPublicAccessBlockConfiguration|ServerSideEncryptionConfigurationNotFoundError|RepositoryPolicyNotFoundException|ResourceNotFoundException|NoSuchPublicAccessBlock' "$err"; then
    echo null >"$file"
    rm -f "$err"
    return 0
  fi
  echo "aws $*: $(cat "$err")" >&2
  rm -f "$err"
  return 1
}

# --- The environment's stacks and what they made ---------------------------------------------------
stacks=()
for s in "$FOUNDATION_STACK" "$CATALOG_STACK" "$OPERATOR_STACK" "$OPEN_LAYER_STACK" "$ENTRY_STACK" \
  "$TEST_MYSQL_STACK" "$TEST_POSTGRES_STACK"; do
  if out="$(aws cloudformation describe-stacks --stack-name "$s" --query 'Stacks[0].StackName' --output text 2>&1)"; then
    stacks+=("$s")
  elif [[ "$out" != *"$s does not exist"* ]]; then
    echo "Cannot read $s: $out" >&2
    exit 1
  fi
done
# Nested stacks (the EKS providers'), breadth first, by name: their resources are the environment's too.
queue=("${stacks[@]}") stack_names=()
: >"$raw/stack-resources.tsv"
while ((${#queue[@]} > 0)); do
  s="${queue[0]}"
  queue=("${queue[@]:1}")
  stack_names+=("$s")
  aws cloudformation list-stack-resources --stack-name "$s" \
    --query 'StackResourceSummaries[].[ResourceType,LogicalResourceId,PhysicalResourceId]' --output text \
    | awk -F'\t' -v s="$s" 'BEGIN {OFS = "\t"} {print s, $1, $2, $3}' >>"$raw/stack-resources.tsv"
  while read -r physical; do
    queue+=("$(cut -d/ -f2 <<<"$physical")")
  done < <(awk -F'\t' -v s="$s" '$1 == s && $2 == "AWS::CloudFormation::Stack" && $4 ~ /^arn:/ {print $4}' \
    "$raw/stack-resources.tsv")
done
python3 -c '
import json, sys
rows = [l.rstrip("\n").split("\t") for l in open(sys.argv[1]) if l.strip()]
print(json.dumps([{"stack": r[0], "type": r[1], "logical": r[2], "physical": r[3]} for r in rows], indent=1))
' "$raw/stack-resources.tsv" >"$raw/stack-resources.json"

vpc="$(stack_output "$FOUNDATION_STACK" VpcId)"
cluster="$(stack_output "$FOUNDATION_STACK" ClusterName)"
warehouse="$(stack_output "$FOUNDATION_STACK" WarehouseBucket)"
alb_arn="" alb_dns="" signin="" pool="" client=""
apps=()
if [[ " ${stacks[*]} " == *" $ENTRY_STACK "* ]]; then
  alb_arn="$(entry_output LoadBalancerArn)"
  alb_dns="$(entry_output LoadBalancerDnsName)"
  signin="$(entry_output SignInDomain)"
  pool="$(entry_output UserPoolId)"
  client="$(entry_output UserPoolClientId)"
  for app in console bi; do
    [[ "$(entry_output "${app}Url")" == https://* ]] && apps+=("$app")
  done
fi
python3 -c '
import json, sys
keys = ["env", "region", "account", "inventory_id", "vpc", "cluster", "warehouse_bucket", "entry_stack",
        "entry_domain", "alb_arn", "alb_dns", "signin", "pool", "client"]
values = sys.argv[1:len(keys) + 1]
rest = sys.argv[len(keys) + 1:]
d = dict(zip(keys, values))
d["alb_name"] = d["alb_arn"].split("/")[2] if d["alb_arn"].count("/") >= 3 else ""
split = rest.index("--")
d["stacks"], d["apps"] = rest[:split], rest[split + 1:]
print(json.dumps(d, indent=1))
' "$COLVENNA_ENV" "$COLVENNA_REGION" "$account" "$inventory_id" "$vpc" "$cluster" "$warehouse" "$ENTRY_STACK" "$ENTRY_DOMAIN" \
  "$alb_arn" "$alb_dns" "$signin" "$pool" "$client" "${stack_names[@]}" -- "${apps[@]}" >"$raw/env.json"
echo "stacks: ${stack_names[*]}; VPC $vpc; cluster $cluster"
# physical <type>: the environment's resources of one CloudFormation type.
physical() { awk -F'\t' -v t="$1" '$2 == t {print $4}' "$raw/stack-resources.tsv"; }

# --- Addresses and load balancers ------------------------------------------------------------------
save "$raw/addresses.json" ec2 describe-addresses
save "$raw/enis-public.json" ec2 describe-network-interfaces --filters 'Name=association.public-ip,Values=*' \
  --query 'NetworkInterfaces[].{NetworkInterfaceId:NetworkInterfaceId,VpcId:VpcId,InterfaceType:InterfaceType,Description:Description,Association:Association,Attachment:{InstanceId:Attachment.InstanceId}}'
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); json.dump({"NetworkInterfaces": d}, open(sys.argv[1], "w"), indent=1)' \
  "$raw/enis-public.json"
save "$raw/load-balancers.json" elbv2 describe-load-balancers
save "$raw/classic-load-balancers.json" elb describe-load-balancers
while IFS=$'\t' read -r arn name; do
  mkdir -p "$raw/lb/$name"
  save "$raw/lb/$name/listeners.json" elbv2 describe-listeners --load-balancer-arn "$arn"
  while IFS=$'\t' read -r listener port policy; do
    save "$raw/lb/$name/rules-$port.json" elbv2 describe-rules --listener-arn "$listener"
    if [[ "$policy" != None ]]; then
      save "$raw/lb/$name/ssl-$port.json" elbv2 describe-ssl-policies --names "$policy"
    fi
  done < <(python3 -c 'import json,sys; [print(l["ListenerArn"], l["Port"], l.get("SslPolicy") or "None", sep="\t") for l in json.load(open(sys.argv[1]))["Listeners"]]' \
    "$raw/lb/$name/listeners.json")
done < <(python3 -c 'import json,sys; [print(l["LoadBalancerArn"], l["LoadBalancerName"], sep="\t") for l in json.load(open(sys.argv[1]))["LoadBalancers"] if l.get("VpcId") == sys.argv[2]]' \
  "$raw/load-balancers.json" "$vpc")

# --- The cluster -----------------------------------------------------------------------------------
save "$raw/eks.json" eks describe-cluster --name "$cluster" \
  --query 'cluster.{name:name,version:version,resourcesVpcConfig:resourcesVpcConfig,accessConfig:accessConfig}'
mkdir -p "$raw/eks-others"
for other in $(aws eks list-clusters --query 'clusters' --output text); do
  [[ "$other" == "$cluster" ]] && continue
  save "$raw/eks-others/$other.json" eks describe-cluster --name "$other" \
    --query 'cluster.{name:name,public:resourcesVpcConfig.endpointPublicAccess,cidrs:resourcesVpcConfig.publicAccessCidrs}'
done
python3 -c 'import glob,json,sys; print(json.dumps([json.load(open(f)) for f in sorted(glob.glob(sys.argv[1] + "/*.json"))], indent=1))' \
  "$raw/eks-others" >"$raw/eks-others.json"
kubectl get services -A -o json | python3 -c 'import json,sys; print(json.dumps([{"namespace": s["metadata"]["namespace"], "name": s["metadata"]["name"], "type": s["spec"]["type"]} for s in json.load(sys.stdin)["items"]], indent=1))' \
  >"$raw/k8s-services.json"
kubectl get ingress -A -o json | python3 -c 'import json,sys; print(json.dumps([{"namespace": i["metadata"]["namespace"], "name": i["metadata"]["name"]} for i in json.load(sys.stdin)["items"]], indent=1))' \
  >"$raw/k8s-ingresses.json"
kubectl get pods -A -o json | python3 -c '
import json, sys
out = []
for pod in json.load(sys.stdin)["items"]:
    spec = pod["spec"]
    ports = [p.get("hostPort") for c in spec.get("containers", []) for p in c.get("ports", []) if p.get("hostPort")]
    if spec.get("hostNetwork") or ports:
        out.append({"namespace": pod["metadata"]["namespace"], "name": pod["metadata"]["name"],
                    "hostNetwork": bool(spec.get("hostNetwork")), "hostPorts": sorted(set(ports))})
print(json.dumps(out, indent=1))' >"$raw/k8s-host-ports.json"

# --- Buckets ---------------------------------------------------------------------------------------
save "$raw/s3-account.json" s3control get-public-access-block --account-id "$account"
for bucket in $(physical AWS::S3::Bucket); do
  mkdir -p "$raw/s3/$bucket"
  save "$raw/s3/$bucket/bpa.json" s3api get-public-access-block --bucket "$bucket"
  save "$raw/s3/$bucket/policy.json" s3api get-bucket-policy --bucket "$bucket"
  save "$raw/s3/$bucket/policy-status.json" s3api get-bucket-policy-status --bucket "$bucket"
  save "$raw/s3/$bucket/acl.json" s3api get-bucket-acl --bucket "$bucket"
  save "$raw/s3/$bucket/encryption.json" s3api get-bucket-encryption --bucket "$bucket"
done

# --- The user pools --------------------------------------------------------------------------------
save "$raw/cognito-pools.json" cognito-idp list-user-pools --max-results 60
for id in $(python3 -c 'import json,sys; [print(p["Id"]) for p in json.load(open(sys.argv[1]))["UserPools"]]' "$raw/cognito-pools.json"); do
  mkdir -p "$raw/cognito/$id"
  save "$raw/cognito/$id/pool.json" cognito-idp describe-user-pool --user-pool-id "$id" \
    --query 'UserPool.{Id:Id,Name:Name,Domain:Domain,CustomDomain:CustomDomain,MfaConfiguration:MfaConfiguration,AllowAdminCreateUserOnly:AdminCreateUserConfig.AllowAdminCreateUserOnly}'
done
for id in $(physical AWS::Cognito::UserPool); do
  for c in $(aws cognito-idp list-user-pool-clients --user-pool-id "$id" --query 'UserPoolClients[].ClientId' --output text); do
    # Never the secret itself: only whether there is one (JMESPath's null literal is in backticks).
    # shellcheck disable=SC2016
    save "$raw/cognito/$id/client-$c.json" cognito-idp describe-user-pool-client --user-pool-id "$id" --client-id "$c" \
      --query 'UserPoolClient.{ClientId:ClientId,HasSecret:ClientSecret!=`null`,ExplicitAuthFlows:ExplicitAuthFlows,AllowedOAuthFlows:AllowedOAuthFlows,AllowedOAuthScopes:AllowedOAuthScopes,CallbackURLs:CallbackURLs,LogoutURLs:LogoutURLs,SupportedIdentityProviders:SupportedIdentityProviders}'
  done
done

# --- Lambda functions and their URLs ---------------------------------------------------------------
env_functions="$(physical AWS::Lambda::Function)"
# List all enabled regions (opted-in-not-required means standard regions, opted-in means explicitly enabled).
all_regions="$(aws ec2 describe-regions --filters 'Name=opt-in-status,Values=opt-in-not-required,opted-in' --query 'Regions[].RegionName' --output text)"
for region in $all_regions; do
  dir="$raw/lambda/$region"
  mkdir -p "$dir"
  if ! aws lambda list-functions --region "$region" --query 'Functions[].FunctionName' --output json >"$dir/functions.json"; then
    echo "Failed to list Lambda functions in $region" >&2
    exit 1
  fi
  # shellcheck disable=SC2016  # the inner shell's own arguments
  python3 -c 'import json,sys; [print(f) for f in json.load(open(sys.argv[1]))]' "$dir/functions.json" \
    | xargs -r -P 8 -I{} sh -c 'aws lambda list-function-url-configs --region "$1" --function-name "$2" --output json >"$3/$2.urls.json" || exit 1' \
      _ "$region" {} "$dir"
  n="$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))))' "$dir/functions.json")"
  got="$(find "$dir" -name '*.urls.json' | wc -l)"
  if [[ "$got" != "$n" ]]; then
    echo "Read the URLs of $got of the $n functions in $region" >&2
    exit 1
  fi
  if [[ "$region" == "$COLVENNA_REGION" ]]; then
    for f in $env_functions; do
      save "$dir/$f.policy.json" lambda get-policy --function-name "$f"
    done
  fi
done

# --- Security groups, APIs, distributions, databases ----------------------------------------------
save "$raw/security-groups-open-ipv4.json" ec2 describe-security-groups --filters 'Name=ip-permission.cidr,Values=0.0.0.0/0'
save "$raw/security-groups-open-ipv6.json" ec2 describe-security-groups --filters 'Name=ip-permission.ipv6-cidr,Values=::/0'
save "$raw/apigateway-rest.json" apigateway get-rest-apis
save "$raw/apigateway-v2.json" apigatewayv2 get-apis
save "$raw/cloudfront.json" cloudfront list-distributions \
  --query 'DistributionList.{Items:Items[].{Id:Id,DomainName:DomainName,Aliases:Aliases,Origins:{Items:Origins.Items[].{DomainName:DomainName}}}}'
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])) or {}; json.dump({"DistributionList": d.get("DistributionList", d)}, open(sys.argv[1], "w"), indent=1)' \
  "$raw/cloudfront.json"
save "$raw/rds-instances.json" rds describe-db-instances \
  --query 'DBInstances[].{id:DBInstanceIdentifier,public:PubliclyAccessible,vpc:DBSubnetGroup.VpcId,address:Endpoint.Address,port:Endpoint.Port}'

# --- Image repositories and secrets ----------------------------------------------------------------
save "$raw/ecr-repositories.json" ecr describe-repositories --query 'repositories[].{repositoryName:repositoryName,repositoryUri:repositoryUri}'
mapfile -t env_repos < <(physical AWS::ECR::Repository)
for repo in $(python3 -c '
import json, sys
env_repos = set(sys.argv[3:])
for r in json.load(open(sys.argv[1])):
    n = r["repositoryName"]
    if n in env_repos or n.startswith("colvenna-lake-%s/" % sys.argv[2]) or n.startswith("colvenna-lake-release/"):
        print(n)' "$raw/ecr-repositories.json" "$COLVENNA_ENV" "${env_repos[@]}"); do
  save "$raw/ecr/${repo//\//__}.policy.json" ecr get-repository-policy --repository-name "$repo"
done
for arn in $(physical AWS::SecretsManager::Secret); do
  name="${arn##*:}"
  save "$raw/secrets/${name//\//__}.policy.json" secretsmanager get-resource-policy --secret-id "$arn" --query '{ResourcePolicy:ResourcePolicy}'
done

# --- The environment's DNS names -------------------------------------------------------------------
aws route53 list-resource-record-sets --hosted-zone-id "$HOSTED_ZONE_ID" --output json \
  --query "ResourceRecordSets[?ends_with(Name, '$ENTRY_DOMAIN.')]" >"$raw/route53.json"

# --- Classify --------------------------------------------------------------------------------------
python3 -I "$here/exposure.py" classify "$evidence" >"$evidence/inventory-checks.txt"
while IFS=$'\t' read -r label result detail; do verdict "$label" "$result" "$detail"; done <"$evidence/inventory-checks.txt"
echo "inventory: $(($(wc -l <"$evidence/inventory.tsv") - 1)) resources of the environment and the release," \
  "$(($(wc -l <"$evidence/others.tsv") - 1)) others in $COLVENNA_REGION; $(grep -c '	PASS	' "$evidence/verdicts.tsv") PASS," \
  "$(grep -c '	FAIL	' "$evidence/verdicts.tsv" || true) FAIL"
exit "$failed"
