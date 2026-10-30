#!/usr/bin/env bash
# Removes a whole Colvenna Lake environment that deploy/install.sh made, data included, and then checks
# that the account holds nothing of it. The environment's name must be given again after --yes.
#
# usage: COLVENNA_ENV=<env> deploy/uninstall.sh --yes <env>
#
# In order:
#   1. In the cluster, while the operators that own them still run: the declarations (their
#      finalizers are colvenna-operator's), the Flink deployments, the Doris clusters and the Kafka
#      resources; then every Helm release (the entry's first, while the load balancer controller
#      can release their target group bindings) and every volume claim, until no EBS volume of the
#      cluster is left.
#   2. The project database's restores that deploy/scripts/catalog/restore-metadata.sh kept
#      (--keep-instance, <foundation stack>-restore-...: in the foundation's subnets, they would keep
#      it from being deleted); then the stacks, newest first: entry, open layer, operator, catalog,
#      sources, foundation.
#   3. What the stacks keep on purpose or leave behind: the warehouse, audit log and access-log buckets
#      (their logging off, then emptied), every manual snapshot of the RDS instances (the final ones
#      among them), the Cognito user pool (deletion protection off first), the log groups, the
#      secrets (without their recovery window), the cluster's OIDC provider, the entry's DNS records,
#      and any volume of the cluster still there.
#   4. A check of the account for anything of the environment (stacks, roles, repositories,
#      buckets, databases, snapshots, secrets, log groups, user pools, volumes, DNS records, tagged
#      resources); it fails if anything is found.
# One log goes to UNINSTALL_EVIDENCE (default deploy/.state/<env>/uninstall-<UTC time>/), and
# leftovers.txt lists what step 4 found. The region, account, hosted zone and entry domain are the
# ones the environment's local state records (deploy/env.sh); the caller's credentials must be of the
# account, and a region holding no stack of it, where no uninstall has begun, is refused before
# anything is deleted, as the entry's DNS records are global. So are settings the state does not
# record yet (an environment installed before they were): a hosted zone that is not a public zone of
# the account of that name, and an entry domain other than the one the entry stack serves. Once
# step 4 finds nothing, the record goes too, so the name may be used again in any region, and so does
# the record of how it was installed (install.sh's install-mode), so a new install may be either way.
set -euo pipefail
DEPLOY="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source-path=SCRIPTDIR source=env.sh
source "$DEPLOY/env.sh" ""  # not the caller's arguments
# shellcheck source-path=SCRIPTDIR source=scripts/lib/preflight.sh
source "$DEPLOY/scripts/lib/preflight.sh"
export PATH="$DEPLOY/.tools:$PATH"
if [[ "${1:-}" != --yes || "${2:-}" != "$COLVENNA_ENV" ]]; then
  echo "usage: COLVENNA_ENV=$COLVENNA_ENV deploy/uninstall.sh --yes $COLVENNA_ENV (deletes it all, data included)" >&2
  exit 1
fi
assert_account
state="$DEPLOY/.state/$COLVENNA_ENV"
ev="${UNINSTALL_EVIDENCE:-$state/uninstall-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$ev"
exec > >(tee -a "$ev/uninstall.log") 2>&1
log() { echo "$(date -u +%FT%TZ) $*"; }
stacks=("$ENTRY_STACK" "$OPEN_LAYER_STACK" "$OPERATOR_STACK" "$CATALOG_STACK" "$TEST_POSTGRES_STACK"
  "$TEST_MYSQL_STACK" "$FOUNDATION_STACK")
stack_exists() { aws cloudformation describe-stacks --stack-name "$1" >/dev/null 2>&1; }
# Matches by the environment's own seven stack names, never by a prefix two environments (or the
# development PoC) could share. starting <field> [lower|as-is] [<path>]: a JMESPath test that <field> starts
# with <path> and one of them (lowercased, or as they are) and a hyphen, as CloudFormation names what it
# generates; stack_list: them as a JMESPath list.
# CloudFormation cuts a long stack name short in the names it generates (to 25 characters or more as
# seen: ColvennaLakeUate2Foundati-..., colvennalakeuate2foundatio-...), so a cut of one at least 23
# characters long counts too: two environments' stack names (ColvennaLake<Env><Part>, env names at most
# 8 characters) never share more than their first 22, lowercased (tests/acceptance/deploy/deploy_checks.py
# goes by the same rule).
starting() {
  local s cut tests=()
  for s in "${stacks[@]}"; do
    [[ "${2:-}" == lower ]] && s="$(tr '[:upper:]' '[:lower:]' <<<"$s")"
    tests+=("starts_with($1, '${3:-}$s-')")
    for ((cut = 23; cut < ${#s}; cut++)); do
      tests+=("starts_with($1, '${3:-}${s:0:cut}-')")
    done
  done
  printf '%s' "${tests[0]}"
  printf ' || %s' "${tests[@]:1}"
}
stack_list="[$(printf "'%s'," "${stacks[@]}" | sed 's/,$//')]"
# The log groups of the stacks' Lambda functions, which Lambda makes and no stack holds.
lambda_logs="$(starting logGroupName as-is /aws/lambda/)"
# The wrong region would find none of the environment's stacks, yet still delete its DNS records.
# Once here, the settings are recorded, so a resumed run cannot be given others.
if [[ ! -s "$state/uninstall.noted" ]]; then
  live="$(aws cloudformation list-stacks --output text --query \
    "StackSummaries[?contains($stack_list, StackName) && StackStatus!='DELETE_COMPLETE'].StackName")"
  if [[ -z "$live" ]]; then
    echo "No stack of $COLVENNA_ENV in $COLVENNA_REGION, and no uninstall begun ($state/uninstall.noted):" \
      "is $COLVENNA_REGION its region?" >&2
    exit 1
  fi
fi
# Settings the state does not record yet (an environment installed before they were) are the caller's
# word, checked here as install.sh checks them. A wrong hosted zone would fail only at the DNS records,
# once the stacks are gone, and the run could not be resumed with the right one; a wrong entry domain
# would delete none of the entry's records, pass the check for leftovers, which looks under it, and
# forget the settings, the real records left behind. A zone recorded is not checked again: install.sh
# checked it, and one deleted since must not keep the rest of the environment from being deleted.
if [[ ! -s "$state/hosted-zone-id" || ! -s "$state/hosted-zone-name" ]]; then
  assert_hosted_zone
fi
assert_entry_domain false
record_settings
output() {
  aws cloudformation describe-stacks --stack-name "$1" \
    --query "Stacks[0].Outputs[?OutputKey=='$2'].OutputValue | [0]" --output text 2>/dev/null || true
}
# physical <stack> <type>: the physical ids of the stack's resources of that type, nested stacks too.
physical() {
  local stack="$1" type="$2" nested
  aws cloudformation list-stack-resources --stack-name "$stack" \
    --query "StackResourceSummaries[?ResourceType=='$type'].PhysicalResourceId" --output text \
    2>/dev/null | tr '\t' '\n' | grep -v '^None$' || true
  for nested in $(aws cloudformation list-stack-resources --stack-name "$stack" \
    --query "StackResourceSummaries[?ResourceType=='AWS::CloudFormation::Stack'].PhysicalResourceId" \
    --output text 2>/dev/null | tr '\t' '\n' | grep -v '^None$' || true); do
    physical "$nested" "$type"
  done
}

# What the stacks leave behind, noted before they go, and kept across runs: a run that dies after
# deleting a stack must still find what that stack held.
notes="$state/uninstall.noted"
touch "$notes"
note() { [[ -n "$2" && "$2" != None ]] && printf '%s=%s\n' "$1" "$2" >>"$notes"; return 0; }
cluster="$(output "$FOUNDATION_STACK" ClusterName)"
note cluster "$cluster"
note vpc "$(output "$FOUNDATION_STACK" VpcId)"
if [[ -n "$cluster" && "$cluster" != None ]]; then
  note issuer "$(aws eks describe-cluster --name "$cluster" --query cluster.identity.oidc.issuer \
    --output text 2>/dev/null || true)"
fi
note user_pool "$(output "$ENTRY_STACK" UserPoolId)"
for s in "${stacks[@]}"; do
  stack_exists "$s" || continue
  while read -r v; do note bucket "$v"; done < <(physical "$s" AWS::S3::Bucket)
  while read -r v; do note database "$v"; done < <(physical "$s" AWS::RDS::DBInstance)
  while read -r v; do note log_group "$v"; done < <(physical "$s" AWS::Logs::LogGroup)
done
# Once each, in the order noted: the last of a kind is the newest (a reinstall makes a new cluster).
awk '!seen[$0]++' "$notes" >"$notes.tmp" && mv "$notes.tmp" "$notes"
noted() { sed -n "s/^$1=//p" "$notes"; }
cluster="$(noted cluster | tail -1)" vpc="$(noted vpc | tail -1)" issuer="$(noted issuer | tail -1)"
user_pool="$(noted user_pool | tail -1)"
mapfile -t clusters < <(noted cluster)
mapfile -t buckets < <(noted bucket)
mapfile -t databases < <(noted database)
mapfile -t log_groups < <(noted log_group)
cp "$notes" "$ev/noted.txt"
log "noted: $(tr '\n' ' ' <"$notes")"

# ------------------------------------------------------------------------------- 1. cluster
if [[ -n "$cluster" ]] && aws eks describe-cluster --name "$cluster" >/dev/null 2>&1; then
  export KUBECONFIG="$ev/kubeconfig"
  aws eks update-kubeconfig --name "$cluster" --kubeconfig "$KUBECONFIG" >/dev/null
  # delete_all <crd>...: every object of those kinds, cluster-wide, waiting out their finalizers.
  delete_all() {
    local crd
    for crd in "$@"; do
      kubectl get crd "$crd" >/dev/null 2>&1 || continue
      kubectl delete "$crd" --all -A --wait=true --timeout=15m >/dev/null 2>&1 \
        || log "WARN: not every $crd deleted"
    done
  }
  log "declarations, while colvenna-operator runs"
  delete_all laketasks.colvenna-lake.io lakeerasures.colvenna-lake.io lakedashboards.colvenna-lake.io \
    lakeicebergpublications.colvenna-lake.io lakewaivers.colvenna-lake.io lakequalities.colvenna-lake.io \
    laketables.colvenna-lake.io lakesources.colvenna-lake.io
  log "Flink, Doris and Kafka resources, while their operators run"
  delete_all flinksessionjobs.flink.apache.org flinkdeployments.flink.apache.org \
    flinkstatesnapshots.flink.apache.org dorisclusters.doris.selectdb.com kafkatopics.kafka.strimzi.io \
    kafkausers.kafka.strimzi.io kafkas.kafka.strimzi.io kafkanodepools.kafka.strimzi.io
  log "Helm releases: the entry's first, the controllers last"
  helm list -A -o json >"$ev/releases.json"
  last='^(aws-load-balancer-controller|colvenna-strimzi|doris-operator|flink-kubernetes-operator)$'
  while read -r release namespace; do
    [[ "$release" =~ $last ]] && continue
    helm uninstall "$release" -n "$namespace" --wait --timeout 10m >/dev/null 2>&1 \
      || log "WARN: helm uninstall $release in $namespace"
  done < <(python3 -c 'import json,sys; [print(r["name"], r["namespace"]) for r in sorted(json.load(open(sys.argv[1])), key=lambda r: r["namespace"] not in ("colvenna-console", "colvenna-bi"))]' "$ev/releases.json")
  log "namespaces of the platform and the flows"
  doomed=()
  for ns in $(kubectl get namespaces -o jsonpath='{.items[*].metadata.name}'); do
    case "$ns" in
      # The cluster's own and its add-ons' (amazon-guardduty), and the operators' until last.
      default | kube-* | amazon-* | flink-operator | doris-operator | colvenna-lake-kafka) ;;
      *) doomed+=("$ns") ;;
    esac
  done
  if [[ ${#doomed[@]} -gt 0 ]]; then
    kubectl delete namespace "${doomed[@]}" --wait=false >/dev/null 2>&1 || true
    kubectl wait namespace "${doomed[@]}" --for=delete --timeout=15m >/dev/null 2>&1 \
      || log "WARN: namespaces left: $(kubectl get namespaces -o name | tr '\n' ' ')"
  fi
  while read -r release namespace; do
    helm uninstall "$release" -n "$namespace" --wait --timeout 10m >/dev/null 2>&1 || true
  done < <(python3 -c 'import json,re,sys; [print(r["name"], r["namespace"]) for r in json.load(open(sys.argv[1])) if re.match(sys.argv[2], r["name"])]' "$ev/releases.json" "$last")
  log "volume claims, until the cluster's EBS volumes are gone"
  kubectl delete pvc --all -A --wait=true --timeout=10m >/dev/null 2>&1 || true
  kubectl get namespaces,pvc,pv -A >"$ev/cluster-after.txt" 2>&1 || true
  for _ in $(seq 60); do
    [[ "$(kubectl get pv -o name 2>/dev/null | wc -l)" == 0 ]] && break
    sleep 10
  done
fi

# -------------------------------------------------------------------------------- 2. stacks
restore_prefix="$(tr '[:upper:]' '[:lower:]' <<<"$FOUNDATION_STACK")-restore-"
restores="$(aws rds describe-db-instances --output text --query \
  "DBInstances[?starts_with(DBInstanceIdentifier, '$restore_prefix')].[DBInstanceIdentifier,DBInstanceStatus]")"
while read -r d status; do
  [[ -n "$d" ]] || continue
  if [[ "$status" != deleting ]]; then
    log "delete RDS instance $d, a restore kept by restore-metadata.sh"
    aws rds delete-db-instance --db-instance-identifier "$d" --skip-final-snapshot \
      --delete-automated-backups >/dev/null
  fi
  aws rds wait db-instance-deleted --db-instance-identifier "$d"
done <<<"$restores"
for s in "${stacks[@]}"; do
  stack_exists "$s" || continue
  log "delete stack $s"
  aws cloudformation delete-stack --stack-name "$s"
  if ! aws cloudformation wait stack-delete-complete --stack-name "$s" 2>/dev/null; then
    if [[ "$s" == "$FOUNDATION_STACK" && "$vpc" == vpc-* ]]; then
      # GuardDuty's agent endpoint and its security group keep the VPC (docs/history/m1/poc-environment.md).
      log "VPC $vpc still in use: deleting GuardDuty's endpoint and security group in it, then retrying"
      for e in $(aws ec2 describe-vpc-endpoints --filters "Name=vpc-id,Values=$vpc" \
        "Name=tag:GuardDutyManaged,Values=true" --query 'VpcEndpoints[].VpcEndpointId' --output text); do
        aws ec2 delete-vpc-endpoints --vpc-endpoint-ids "$e" >/dev/null
      done
      for _ in $(seq 30); do
        [[ -z "$(aws ec2 describe-vpc-endpoints --filters "Name=vpc-id,Values=$vpc" \
          "Name=tag:GuardDutyManaged,Values=true" "Name=vpc-endpoint-state,Values=available,deleting" \
          --query 'VpcEndpoints[].VpcEndpointId' --output text)" ]] && break
        sleep 10
      done
      for g in $(aws ec2 describe-security-groups --filters "Name=vpc-id,Values=$vpc" \
        "Name=tag:GuardDutyManaged,Values=true" --query 'SecurityGroups[].GroupId' --output text); do
        aws ec2 delete-security-group --group-id "$g"
      done
      aws cloudformation delete-stack --stack-name "$s"
      aws cloudformation wait stack-delete-complete --stack-name "$s"
    else
      aws cloudformation describe-stack-events --stack-name "$s" --max-items 20 \
        --query "StackEvents[?contains(ResourceStatus, 'FAILED')].[LogicalResourceId,ResourceStatusReason]" \
        --output text >&2 || true
      echo "Deleting $s failed" >&2
      exit 1
    fi
  fi
done

# -------------------------------------------------------------------------------- 3. leftovers
# The warehouse logs its access into the audit log bucket: every bucket's logging stops first. Log
# delivery runs late, so a bucket that takes objects while it is being deleted is emptied again.
for b in "${buckets[@]}"; do
  if aws s3api head-bucket --bucket "$b" >/dev/null 2>&1; then
    aws s3api put-bucket-logging --bucket "$b" --bucket-logging-status '{}'
  fi
done
for b in "${buckets[@]}"; do
  if aws s3api head-bucket --bucket "$b" >/dev/null 2>&1; then
    log "empty and delete bucket $b"
    for attempt in 1 2 3; do
      aws s3 rm "s3://$b" --recursive --quiet
      if aws s3api delete-bucket --bucket "$b" 2>"$ev/delete-bucket.err"; then
        break
      fi
      if [[ "$attempt" == 3 ]] || ! grep -q BucketNotEmpty "$ev/delete-bucket.err"; then
        cat "$ev/delete-bucket.err" >&2
        exit 1
      fi
      log "bucket $b took objects while it was emptied (late log delivery); again in 60 s"
      sleep 60
    done
  fi
done
for d in "${databases[@]}"; do
  # The final snapshot is a manual one; automated ones go with their instance.
  for snap in $(aws rds describe-db-snapshots --db-instance-identifier "$d" --snapshot-type manual \
    --query 'DBSnapshots[].DBSnapshotIdentifier' --output text); do
    log "delete RDS snapshot $snap"
    aws rds delete-db-snapshot --db-snapshot-identifier "$snap" >/dev/null
  done
done
if [[ "$user_pool" == "${COLVENNA_REGION}_"* ]] && aws cognito-idp describe-user-pool --user-pool-id "$user_pool" >/dev/null 2>&1; then
  log "delete Cognito user pool $user_pool"
  domain="$(aws cognito-idp describe-user-pool --user-pool-id "$user_pool" --query UserPool.Domain --output text)"
  if [[ -n "$domain" && "$domain" != None ]]; then
    aws cognito-idp delete-user-pool-domain --user-pool-id "$user_pool" --domain "$domain"
  fi
  aws cognito-idp update-user-pool --user-pool-id "$user_pool" --deletion-protection INACTIVE
  aws cognito-idp delete-user-pool --user-pool-id "$user_pool"
fi
# The stacks' log groups, the cluster's and the databases' logs, and the CDK handlers' logs.
groups=("${log_groups[@]}")
[[ -n "$cluster" ]] && groups+=("/aws/eks/$cluster/cluster")
for d in "${databases[@]}"; do
  mapfile -t -O "${#groups[@]}" groups < <(aws logs describe-log-groups \
    --log-group-name-prefix "/aws/rds/instance/$d/" --query 'logGroups[].logGroupName' --output text | tr '\t' '\n')
done
# Listed under the start all the environment's stack names share, cut or not: ColvennaLake<Env>.
mapfile -t -O "${#groups[@]}" groups < <(aws logs describe-log-groups --log-group-name-prefix \
  "/aws/lambda/$(env_stack "")" --query "logGroups[?$lambda_logs].logGroupName" --output text | tr '\t' '\n')
for g in "${groups[@]}"; do
  [[ -n "$g" ]] || continue
  if aws logs delete-log-group --log-group-name "$g" 2>/dev/null; then log "deleted log group $g"; fi
done
# Secrets the stacks deleted wait out a recovery window; end it. CloudFormation names generated ones
# after their resource, so they are found by the stack that made them.
env_secrets() {
  aws secretsmanager list-secrets --include-planned-deletion --query "SecretList[?starts_with(Name, \
'$(env_name colvenna-lake)/') || Tags[?Key=='aws:cloudformation:stack-name' && contains($stack_list, Value)]].$1" \
    --output text | tr '\t' '\n'
}
for secret in $(env_secrets ARN); do
  log "delete secret $secret"
  aws secretsmanager delete-secret --secret-id "$secret" --force-delete-without-recovery >/dev/null
done
if [[ -n "$issuer" && "$issuer" != None ]]; then
  arn="arn:aws:iam::$COLVENNA_ACCOUNT:oidc-provider/${issuer#https://}"
  if aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$arn" >/dev/null 2>&1; then
    log "delete OIDC provider $arn"
    aws iam delete-open-id-connect-provider --open-id-connect-provider-arn "$arn"
  fi
fi
for c in "${clusters[@]}"; do
  for v in $(aws ec2 describe-volumes --filters "Name=tag:ebs.csi.aws.com/cluster-name,Values=$c" \
    --query "Volumes[?State=='available'].VolumeId" --output text); do
    log "delete volume $v of $c"
    aws ec2 delete-volume --volume-id "$v"
  done
done
domain="$ENTRY_DOMAIN." zone="$HOSTED_ZONE_ID"
aws route53 list-resource-record-sets --hosted-zone-id "$zone" --output json \
  | python3 -c 'import json,sys; d=sys.argv[1]; [print(json.dumps(r)) for r in json.load(sys.stdin)["ResourceRecordSets"] if r["Name"] == d or r["Name"].endswith("." + d)]' "$domain" \
  >"$ev/dns-left.jsonl"
while read -r record; do
  [[ -n "$record" ]] || continue
  log "delete DNS record $(python3 -c 'import json,sys; r=json.loads(sys.argv[1]); print(r["Name"], r["Type"])' "$record")"
  aws route53 change-resource-record-sets --hosted-zone-id "$zone" --change-batch \
    "{\"Changes\":[{\"Action\":\"DELETE\",\"ResourceRecordSet\":$record}]}" >/dev/null
done <"$ev/dns-left.jsonl"

# --------------------------------------------------------------------------------- 4. check
name="$(env_name colvenna-lake)"
# found <kind> <command...>: one "<kind> <name>" line per thing the command lists; a command that
# fails counts as something found, so a denied or throttled call never reads as "nothing left".
found() {
  local kind="$1" out err
  shift
  err="$(mktemp)"
  if ! out="$("$@" 2>"$err")"; then
    echo "check-failed $kind: $(tr '\n' ' ' <"$err" | cut -c1-200)"
    rm -f "$err"
    return 0
  fi
  rm -f "$err"
  tr '\t' '\n' <<<"$out" | grep -Ev '^(None)?$' | sed "s|^|$kind |" || true
}
# dns_records: the entry's records still in the zone, certificate validation records included.
dns_records() {
  aws route53 list-resource-record-sets --hosted-zone-id "$zone" --output json | python3 -c '
import json, sys
d = sys.argv[1]
for r in json.load(sys.stdin)["ResourceRecordSets"]:
    if r["Name"] == d or r["Name"].endswith("." + d):
        print(r["Name"], r["Type"])' "$domain"
}
check() {
  found stack aws cloudformation list-stacks --output text --query \
    "StackSummaries[?contains($stack_list, StackName) && StackStatus!='DELETE_COMPLETE'].StackName"
  found role aws iam list-roles --output text \
    --query "Roles[?starts_with(RoleName, '$name-') || $(starting RoleName)].RoleName"
  found repository aws ecr describe-repositories --output text \
    --query "repositories[?starts_with(repositoryName, '$name/')].repositoryName"
  found bucket aws s3api list-buckets --output text --query "Buckets[?$(starting Name lower)].Name"
  found database aws rds describe-db-instances --output text \
    --query "DBInstances[?$(starting DBInstanceIdentifier lower)].DBInstanceIdentifier"
  found snapshot aws rds describe-db-snapshots --output text \
    --query "DBSnapshots[?$(starting DBInstanceIdentifier lower)].DBSnapshotIdentifier"
  found secret env_secrets Name
  found log-group aws logs describe-log-groups --output text --query "logGroups[?contains(logGroupName, \
'$name/') || $lambda_logs \
|| starts_with(logGroupName, '/aws/eks/${cluster:-none}/') \
|| $(starting logGroupName lower /aws/rds/instance/)].logGroupName"
  found user-pool aws cognito-idp list-user-pools --max-results 60 --output text \
    --query "UserPools[?Name == '$name' || starts_with(Name, '$name-')].Name"
  for c in "${clusters[@]}"; do
    found volume aws ec2 describe-volumes --output text --query 'Volumes[].VolumeId' \
      --filters "Name=tag:ebs.csi.aws.com/cluster-name,Values=$c"
  done
  found dns dns_records
  found tagged tagged_present
}
# tagged_present: the environment's tagged resources that still exist. The tagging index keeps a
# deleted resource for a while (a deleted NAT gateway for an hour), so each ARN of a kind the stacks
# make is looked up itself; one whose lookup says it is gone does not count, any other is listed.
tagged_present() {
  local arn id out
  for arn in $(aws resourcegroupstaggingapi get-resources --output text \
    --tag-filters "Key=Environment,Values=$COLVENNA_ENV" --query 'ResourceTagMappingList[].ResourceARN'); do
    id="${arn##*/}"
    case "$arn" in
      arn:aws:ec2:*:natgateway/*)
        out="$(aws ec2 describe-nat-gateways --nat-gateway-ids "$id" --query 'NatGateways[0].State' \
          --output text 2>&1)" || true ;;
      arn:aws:ec2:*:vpc-endpoint/*)
        out="$(aws ec2 describe-vpc-endpoints --vpc-endpoint-ids "$id" \
          --query 'VpcEndpoints[0].State' --output text 2>&1)" || true ;;
      arn:aws:ec2:*:vpc/*) out="$(aws ec2 describe-vpcs --vpc-ids "$id" --output text 2>&1)" || true ;;
      arn:aws:ec2:*:subnet/*) out="$(aws ec2 describe-subnets --subnet-ids "$id" --output text 2>&1)" || true ;;
      arn:aws:ec2:*:security-group/*)
        out="$(aws ec2 describe-security-groups --group-ids "$id" --output text 2>&1)" || true ;;
      arn:aws:ec2:*:route-table/*)
        out="$(aws ec2 describe-route-tables --route-table-ids "$id" --output text 2>&1)" || true ;;
      arn:aws:ec2:*:internet-gateway/*)
        out="$(aws ec2 describe-internet-gateways --internet-gateway-ids "$id" --output text 2>&1)" || true ;;
      arn:aws:ec2:*:elastic-ip/*)
        out="$(aws ec2 describe-addresses --allocation-ids "$id" --output text 2>&1)" || true ;;
      arn:aws:ec2:*:vpc-flow-log/*)
        # An unknown flow log id is no error: the listing is just empty.
        out="$(aws ec2 describe-flow-logs --flow-log-ids "$id" --query 'FlowLogs[0].FlowLogStatus' \
          --output text 2>&1)" || true
        if [[ "$out" == None ]]; then out=deleted; fi ;;
      arn:aws:cognito-idp:*:userpool/*)
        out="$(aws cognito-idp describe-user-pool --user-pool-id "$id" --output text 2>&1)" || true ;;
      *) out="" ;;
    esac
    [[ "$out" =~ NotFound|does\ not\ exist|^deleted$ ]] || printf '%s\n' "$arn"
  done
}
# Deletions settle within minutes (the tagging index, scheduled secrets): look three times.
for attempt in 1 2 3; do
  check >"$ev/leftovers.txt"
  [[ -s "$ev/leftovers.txt" ]] || break
  if [[ "$attempt" != 3 ]]; then
    log "something listed; checking again in 2 minutes"
    sleep 120
  fi
done
if [[ -s "$ev/leftovers.txt" ]]; then
  echo "Left of $COLVENNA_ENV in the account:" >&2
  cat "$ev/leftovers.txt" >&2
  exit 1
fi
# The environment is gone; its name may be used again, in any region, installed either way.
rm -f "$notes" "$state/install-mode"
forget_settings
log "nothing of $COLVENNA_ENV is left in the account; evidence: $ev"
