#!/usr/bin/env bash
# Writes what an environment is made of into <out-dir>, as raw JSON for deploy_checks.py: its
# stacks and each stack's resources (physical ids), its stacks' Secrets Manager secrets (names), the
# cluster's nodes, Helm releases, workloads (their images and pod annotations), pods, volumes, namespaces,
# CRDs and Secrets (names only, never their data), the number of images in each of its ECR
# repositories, and the names of the files in its local state; meta.json records the environment,
# its settings (deploy/env.sh's COLVENNA_SETTINGS, by variable), commit and time.
# With --account it also writes what the account holds of every environment and of the release
# registry, everything uninstall.sh deletes by name, in the environment's region and the standard
# regions given (default us-west-2 and us-east-2): ColvennaLake stacks, colvenna-lake ECR repositories,
# databases and their manual snapshots, user pools, secrets, log groups and the clusters' EBS
# volumes per region; IAM roles, OIDC providers, buckets and the entry DNS records for the account.
# Only what is of colvenna-lake: names it gives, its stacks' tags, and for volumes, EKS log groups and
# OIDC providers the clusters of its environments' foundation stacks (account-clusters-<region>.json),
# so other projects' resources in the account do not count.
# meta.json then also names the environment's cluster and its OIDC issuer, while they exist; once they
# are gone (after an uninstall), INVENTORY_CLUSTER and INVENTORY_ISSUER give them, so what is left of
# them is still looked for.
# Uninstall checks compare two of those.
#
# usage: COLVENNA_ENV=<env> tests/acceptance/deploy/inventory.sh <out-dir> [--account [<region>...]]
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
assert_account
out="${1:?usage: inventory.sh <out-dir> [--account [<region>...]]}"
shift
mkdir -p "$out"
account=false regions=(us-west-2 us-east-2)
if [[ "${1:-}" == --account ]]; then
  account=true
  shift
  (($# == 0)) || regions=("$@")
fi
# The environment's cluster and its OIDC issuer, while there is one: what of the account's EKS log
# groups, OIDC providers and volumes is its own.
cluster="$(stack_output "$FOUNDATION_STACK" ClusterName 2>/dev/null || true)"
issuer=""
[[ -z "$cluster" || "$cluster" == None ]] || issuer="$(aws eks describe-cluster --name "$cluster" \
  --query cluster.identity.oidc.issuer --output text 2>/dev/null || true)"
[[ -n "$cluster" && "$cluster" != None ]] || cluster="${INVENTORY_CLUSTER:-}"
[[ -n "$issuer" && "$issuer" != None ]] || issuer="${INVENTORY_ISSUER:-}"
settings=()
for setting in "${COLVENNA_SETTINGS[@]}"; do
  name="${setting%%:*}"
  settings+=("$name=${!name}")
done
python3 - "$out/meta.json" "$COLVENNA_ENV" "$COLVENNA_REGION" "$(git -C "$REPO_DIR" rev-parse HEAD)" "$cluster" "$issuer" \
  "${settings[@]}" <<'PY'
import json, sys, time
cluster, issuer = (v if v not in ("", "None") else None for v in sys.argv[5:7])
settings = dict(arg.split("=", 1) for arg in sys.argv[7:])
json.dump({"env": sys.argv[2], "region": sys.argv[3], "commit": sys.argv[4], "cluster": cluster, "issuer": issuer,
           "settings": settings, "taken_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())},
          open(sys.argv[1], "w"))
PY

if $account; then
  [[ " ${regions[*]} " == *" $COLVENNA_REGION "* ]] || regions+=("$COLVENNA_REGION")
  for region in "${regions[@]}"; do
    aws cloudformation list-stacks --region "$region" --output json --query \
      "StackSummaries[?starts_with(StackName, 'ColvennaLake') && StackStatus!='DELETE_COMPLETE'].{name: StackName, status: StackStatus, created: CreationTime, updated: LastUpdatedTime}" \
      >"$out/account-stacks-$region.json"
    aws ecr describe-repositories --region "$region" --output json \
      --query "repositories[?starts_with(repositoryName, 'colvenna-lake')].repositoryName" >"$out/account-repositories-$region.json"
    aws rds describe-db-instances --region "$region" --output json \
      --query "DBInstances[?starts_with(DBInstanceIdentifier, 'colvennalake')].DBInstanceIdentifier" >"$out/account-databases-$region.json"
    aws rds describe-db-snapshots --region "$region" --snapshot-type manual --output json \
      --query "DBSnapshots[?starts_with(DBInstanceIdentifier, 'colvennalake')].[DBSnapshotIdentifier, DBInstanceIdentifier]" \
      >"$out/account-snapshots-$region.json"
    aws cognito-idp list-user-pools --region "$region" --max-results 60 --output json \
      --query "UserPools[?starts_with(Name, 'colvenna-lake')].Name" >"$out/account-userpools-$region.json"
    aws secretsmanager list-secrets --region "$region" --include-planned-deletion --output json \
      --query "SecretList[].[Name, (Tags[?Key=='aws:cloudformation:stack-name'].Value)[0]]" \
      | python3 -c 'import json,sys; print(json.dumps([s for s in json.load(sys.stdin) if s[0].startswith("colvenna-lake") or (s[1] or "").startswith("ColvennaLake")]))' \
        >"$out/account-secrets-$region.json"
    # The clusters of Colvenna Lake's environments here: their foundation stacks' ClusterName.
    for stack in $(python3 -c 'import json,sys; print(" ".join(s["name"] for s in json.load(open(sys.argv[1])) if s["name"].endswith("Foundation")))' \
      "$out/account-stacks-$region.json"); do
      c="$(aws cloudformation describe-stacks --region "$region" --stack-name "$stack" --output text \
        --query "Stacks[0].Outputs[?OutputKey=='ClusterName'].OutputValue | [0]" 2>/dev/null || true)"
      [[ -n "$c" && "$c" != None ]] || continue
      printf '%s\t%s\t%s\n' "$stack" "$c" "$(aws eks describe-cluster --region "$region" --name "$c" \
        --query cluster.identity.oidc.issuer --output text 2>/dev/null || true)"
    done | python3 -c 'import json,sys; print(json.dumps([l.rstrip("\n").split("\t") for l in sys.stdin if l.strip()]))' \
      >"$out/account-clusters-$region.json"
    clusters="$(python3 -c 'import json,sys; print(" ".join([c[1] for c in json.load(open(sys.argv[1]))] + sys.argv[2:]))' \
      "$out/account-clusters-$region.json" "$cluster")"
    aws logs describe-log-groups --region "$region" --output json --query "logGroups[?contains(logGroupName, 'colvenna-lake') \
|| contains(logGroupName, 'ColvennaLake') || contains(logGroupName, 'colvennalake') || starts_with(logGroupName, '/aws/eks/')].logGroupName" \
      | python3 -c 'import json,sys; c=sys.argv[1].split(); print(json.dumps([g for g in json.load(sys.stdin) if not g.startswith("/aws/eks/") or g.split("/")[3] in c]))' "$clusters" \
        >"$out/account-loggroups-$region.json"
    aws ec2 describe-volumes --region "$region" --output json --filters Name=tag-key,Values=ebs.csi.aws.com/cluster-name \
      --query "Volumes[].[VolumeId, (Tags[?Key=='ebs.csi.aws.com/cluster-name'].Value)[0]]" \
      | python3 -c 'import json,sys; c=sys.argv[1].split(); print(json.dumps([v for v in json.load(sys.stdin) if v[1] in c]))' "$clusters" \
        >"$out/account-volumes-$region.json"
  done
  aws s3api list-buckets --output json --query "Buckets[?starts_with(Name, 'colvennalake')].Name" >"$out/account-buckets.json"
  # The OIDC providers of those clusters, and of the environment's own.
  aws iam list-open-id-connect-providers --output json --query 'OpenIDConnectProviderList[].Arn' \
    | python3 -c '
import json, sys
issuers = {sys.argv[1].removeprefix("https://")} - {""}
for path in sys.argv[2:]:
    issuers |= {c[2].removeprefix("https://") for c in json.load(open(path)) if len(c) > 2 and c[2] not in ("", "None")}
print(json.dumps([a for a in json.load(sys.stdin) if any(a.endswith("/" + i) for i in issuers)]))' \
      "$issuer" "$out"/account-clusters-*.json >"$out/account-oidc.json"
  # The entry domains of the environments: this one's (COLVENNA_ENTRY_DOMAIN) and every entry stack's in
  # the regions listed (its consoleUrl); the zone's records at or under one of them, and those of the
  # default names, <app>.colvenna-<env>., which an entry gone before its records were may have left.
  for region in "${regions[@]}"; do
    python3 -c 'import json, sys; [print(s["name"]) for s in json.load(open(sys.argv[1])) if s["name"].endswith("Entry")]' \
      "$out/account-stacks-$region.json" | while read -r stack; do
      aws cloudformation describe-stacks --region "$region" --stack-name "$stack" --output text \
        --query "Stacks[0].Outputs[?OutputKey=='consoleUrl'].OutputValue | [0]" | sed 's#^https://console\.##'
    done
  done | { cat; printf '%s\n' "$COLVENNA_ENTRY_DOMAIN"; } | grep -v '^None$' | sort -u >"$out/account-entry-domains.txt"
  aws route53 list-resource-record-sets --hosted-zone-id "$HOSTED_ZONE_ID" --output json | python3 -c '
import json, sys
domains = [d for d in open(sys.argv[1]).read().split() if d]
def kept(name):
    name = name.rstrip(".").lower()
    return ".colvenna-" in "." + name or any(name == d or name.endswith("." + d) for d in domains)
print(json.dumps([{"name": r["Name"], "type": r["Type"]} for r in json.load(sys.stdin)["ResourceRecordSets"]
                  if kept(r["Name"])]))' "$out/account-entry-domains.txt" >"$out/account-dns.json"
  aws iam list-roles --output json --query "Roles[?starts_with(RoleName, 'colvenna-lake-') || starts_with(RoleName, 'ColvennaLake')].RoleName" \
    >"$out/account-roles.json"
  printf '%s\n' "${regions[@]}" >"$out/account-regions.txt"
fi

# The environment itself: what is there of it (none of it may be, before a first install).
stacks=("$FOUNDATION_STACK" "$CATALOG_STACK" "$OPERATOR_STACK" "$OPEN_LAYER_STACK" "$ENTRY_STACK"
  "$TEST_MYSQL_STACK" "$TEST_POSTGRES_STACK")
printf '[' >"$out/stacks.json"
first=true
for s in "${stacks[@]}"; do
  if ! described="$(aws cloudformation describe-stacks --stack-name "$s" --output json 2>/dev/null)"; then
    continue
  fi
  $first || printf ',' >>"$out/stacks.json"
  first=false
  python3 -c 'import json,sys; s=json.load(sys.stdin)["Stacks"][0]; print(json.dumps({"name": s["StackName"], "status": s["StackStatus"], "created": s["CreationTime"], "updated": s.get("LastUpdatedTime")}))' \
    <<<"$described" >>"$out/stacks.json"
  aws cloudformation list-stack-resources --stack-name "$s" --output json \
    --query 'StackResourceSummaries[].{logical: LogicalResourceId, physical: PhysicalResourceId, type: ResourceType, status: ResourceStatus}' \
    >"$out/resources-$s.json"
done
printf ']\n' >>"$out/stacks.json"
for repository in $(aws ecr describe-repositories --output text \
  --query "repositories[?starts_with(repositoryName, '$(env_name colvenna-lake)/')].repositoryName"); do
  printf '%s\t%s\n' "$repository" "$(aws ecr describe-images --repository-name "$repository" \
    --query 'length(imageDetails)' --output text)"
done >"$out/images.tsv"
# [name, the stack that made it] of each secret one of the environment's stacks made; the listing
# reaches python on stdin, not as an argument, however long it is.
aws secretsmanager list-secrets --output json \
  --query "SecretList[].[Name, (Tags[?Key=='aws:cloudformation:stack-name'].Value)[0]]" \
  | python3 -c 'import json,sys; print(json.dumps([s for s in json.load(sys.stdin) if s[1] in sys.argv[1:]]))' \
    "${stacks[@]}" >"$out/secrets.json"
find "$STATE_DIR" -maxdepth 1 -type f -printf '%f\n' | sort >"$out/state.txt"
if aws cloudformation describe-stacks --stack-name "$FOUNDATION_STACK" >/dev/null 2>&1; then
  ensure_kubeconfig
  kubectl get nodes -o json >"$out/nodes.json"
  helm list -A -o json >"$out/helm.json"
  kubectl get deployments,statefulsets,daemonsets -A -o json >"$out/workloads.json"
  # Every pod too, whatever made it: the images that run (deploy_checks.py images).
  kubectl get pods -A -o json >"$out/pods.json"
  kubectl get pv -o json >"$out/volumes.json"
  kubectl get namespaces -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' >"$out/namespaces.txt"
  kubectl get crd -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' >"$out/crds.txt"
  kubectl get secrets -A -o jsonpath='{range .items[*]}{.metadata.namespace}/{.metadata.name}{"\n"}{end}' \
    >"$out/secret-names.txt"
fi
echo "inventory of $COLVENNA_ENV in $out"
