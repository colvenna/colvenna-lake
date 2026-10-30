#!/usr/bin/env bash
# Acceptance: what deploy/install.sh, deploy/uninstall.sh and the foundation's deploy.sh refuse,
# and that a refused run makes nothing (UAT-DEP-05). Writes PASS/FAIL lines to
# <evidence>/verdicts.tsv and fails if any check fails.
#
# Each refusal runs the real script and expects a non-zero exit with its message, and no setting
# recorded (deploy/env.sh's COLVENNA_SETTINGS files) and no install evidence started for the name it was
# given. Then, across the account:
# nothing of the test's own fresh name (no stack in any region tried, no IAM role), and the
# environment under test (COLVENNA_ENV) unchanged: its recorded settings and its stacks' last updates.
#
# Besides names, regions, steps, trees and uninstall, the environment's settings (#212,
# deploy/env.sh): an account, hosted zone or entry domain missing, malformed, not the caller's, not
# public, outside the zone, or other than the one recorded; and a region that cannot hold an
# environment (deploy/scripts/lib/preflight.sh): no EKS of the foundation's Kubernetes version, no
# two zones with both node types, no room under the VPC or Elastic IP quota. The last, and a private
# hosted zone, are tried with a stub aws first on PATH that answers one read-only call as such a
# region or zone would (STUB_CASE) and passes every other to the real CLI. The runs keep the environment's account and hosted zone (so a
# fresh name reaches the checks after them), never its entry domain.
#
# The wrong-region uninstall is tried with a real environment too, one that lives in the other region
# and is about to go anyway (UNINSTALL_PROBE_ENV, default the first of plat and uate2 found; never the
# environment under test, never uat, and never give it one you keep): from a clone without its region record,
# uninstall.sh must refuse because the region holds none of its stacks, not because the name is
# unknown.
#
# Safety: a refusal that stopped working must not make anything. The runs are given a region
# whose endpoints do not exist (us-fake-1) where they can be, and install.sh `--only tools`, which
# installs local binaries only; npm, npx, cdk, helm and kubectl are stubs that fail, so no stack
# and nothing in a cluster can be deployed; uninstall.sh is only ever given the fresh name or the
# disposable probe environment, never the environment under test. Environments' stacks are matched by
# their exact names (ColvennaLake<Env><Component>), so uat is never taken for uate2. The runs that must not
# see the environment's local state (a name
# held by another region, a dirty tree) work in a throw-away clone of this commit.
#
# usage: COLVENNA_ENV=<env> tests/acceptance/deploy/run-refusals.sh <run-id> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/preflight.sh
source "$DEPLOY_DIR/scripts/lib/preflight.sh"
assert_account
run="${1:?usage: run-refusals.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-refusals.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
mkdir -p "$evidence/runs"
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text); environment $COLVENNA_ENV in $COLVENNA_REGION"
failed=0
verdict() {
  local detail="${3:-}"
  detail="${detail//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${detail//$'\t'/ }" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS ]] || failed=1
}

held="$COLVENNA_ENV" home="$COLVENNA_REGION"
other=us-east-2
[[ "$home" != us-east-2 ]] || other=us-west-2
# A name of the test's own, at most 8 characters, that nothing in the account has.
fresh="zr${run:0:6}"
fresh_stack="$(printf 'ColvennaLake%s' "${fresh^}")"
work="$(mktemp -d)"
chmod 700 "$work"
trap 'rm -rf "$work"' EXIT
clone="$work/tree"
git clone -q --shared --no-checkout "$REPO_DIR" "$clone"
git -C "$clone" checkout -q --detach "$sha"
mkdir "$work/stubs"
for tool in npm npx cdk helm kubectl; do
  printf '#!/bin/sh\necho "%s: blocked by run-refusals.sh" >&2\nexit 97\n' "$tool" >"$work/stubs/$tool"
  chmod +x "$work/stubs/$tool"
done
# aws: the real CLI, but for the one answer STUB_CASE fakes.
cat >"$work/stubs/aws" <<STUB
#!/usr/bin/env bash
real="$(command -v aws)"
case "\${STUB_CASE:-}:\$*" in
  zone-private:"route53 get-hosted-zone "*)
    echo "{\"HostedZone\": {\"Id\": \"/hostedzone/\$COLVENNA_HOSTED_ZONE_ID\", \"Name\": \"\$COLVENNA_HOSTED_ZONE_NAME.\", \"Config\": {\"PrivateZone\": true}}}" ;;
  kubernetes-version-missing:"eks describe-cluster-versions "*) echo '{"clusterVersions": []}' ;;
  node-types-missing:"ec2 describe-instance-type-offerings "*) echo '{"InstanceTypeOfferings": []}' ;;
  node-types-in-one-zone:"ec2 describe-instance-type-offerings "*)
    "\$real" "\$@" | python3 -c 'import json, sys
answer = json.load(sys.stdin)["InstanceTypeOfferings"]
first = sorted({o["Location"] for o in answer})[0]
print(json.dumps({"InstanceTypeOfferings": [o for o in answer if o["Location"] == first]}))' ;;
  vpc-quota-full:"service-quotas get-service-quota --service-code vpc "*) echo '{"Quota": {"Value": 0.0}}' ;;
  address-quota-full:"service-quotas get-service-quota --service-code ec2 "*) echo '{"Quota": {"Value": 0.0}}' ;;
  room:"service-quotas get-service-quota "* | room:"service-quotas get-aws-default-service-quota "*)
    echo '{"Quota": {"Value": 1000.0}}' ;;
  *) exec "\$real" "\$@" ;;
esac
STUB
chmod +x "$work/stubs/aws"

# env_stacks <env>: the environment's seven stacks, as deploy/env.sh's env_stack names them.
env_stacks() {
  local component
  for component in Foundation Catalog Operator OpenLayer Entry TestMysql TestPostgres; do
    printf 'ColvennaLake%s%s\n' "${1^}" "$component"
  done
}
# stacks_of <env> <region>: the environment's live stacks there, nested ones included (<stack>-...),
# name, last update and status, one per line.
stacks_of() {
  local names
  mapfile -t names < <(env_stacks "$1")
  aws cloudformation list-stacks --region "$2" --output json --query \
    "StackSummaries[?StackStatus!='DELETE_COMPLETE'].[StackName,LastUpdatedTime,StackStatus]" \
    | python3 -c '
import json, sys
names = sys.argv[1:]
for name, updated, status in sorted(json.load(sys.stdin)):
    if any(name == n or name.startswith(n + "-") for n in names):
        print(name, updated, status, sep="\t")' "${names[@]}"
}
roles_of() {
  aws iam list-roles --output text --query "Roles[?starts_with(RoleName, 'colvenna-lake-$1-') || starts_with(RoleName, '$2')].RoleName"
}
if [[ -n "$(roles_of "$fresh" "$fresh_stack")" || -n "$(stacks_of "$fresh" "$home")" || -n "$(stacks_of "$fresh" "$other")" ]]; then
  echo "The test's name $fresh is taken in the account; give another run id" >&2
  exit 1
fi
stacks_of "$held" "$home" >"$evidence/held-stacks-before.txt"
stacks_of "$held" "$other" >"$evidence/held-stacks-other-region-before.txt"
# held_settings: the environment's recorded settings (deploy/env.sh's COLVENNA_SETTINGS files).
held_settings() {
  local setting
  for setting in "${COLVENNA_SETTINGS[@]}"; do
    printf '%s=%s\n' "${setting%%:*}" "$(cat "$COLVENNA_STATE_DIR/${setting#*:}" 2>/dev/null || echo '(none)')"
  done
}
held_settings >"$evidence/held-settings-before.txt"

# refused <label> <tree> <message-regex> <env-assignments...> -- <command...>: runs the command in
# the tree with those variables, the environment's own (COLVENNA_ENV, COLVENNA_REGION, the AWS region, the
# kubeconfig, the image commit) taken out, and the stubs first on PATH; PASS when it exits non-zero,
# says the message, and left no region record or install evidence for the name it was given.
refused() {
  local label="$1" tree="$2" message="$3" name="" status=0 out="$evidence/runs/$1.log"
  shift 3
  local assignments=()
  while [[ "$1" != -- ]]; do
    assignments+=("$1")
    [[ "$1" != COLVENNA_ENV=* ]] || name="${1#COLVENNA_ENV=}"
    shift
  done
  shift
  (cd "$tree" && /usr/bin/env -u COLVENNA_ENV -u COLVENNA_REGION -u COLVENNA_ENTRY_DOMAIN -u AWS_REGION -u AWS_DEFAULT_REGION \
    -u KUBECONFIG -u IMAGES_SHA -u INSTALL_EVIDENCE -u UNINSTALL_EVIDENCE PATH="$work/stubs:$PATH" "${assignments[@]}" "$@") \
    >"$out" 2>&1 </dev/null || status=$?
  local left="" setting
  if [[ "$tree" == "$clone" && -n "$name" ]]; then
    for setting in "${COLVENNA_SETTINGS[@]}"; do
      [[ ! -e "$clone/deploy/.state/$name/${setting#*:}" ]] \
        || left+="${setting#*:} recorded: $(cat "$clone/deploy/.state/$name/${setting#*:}"); "
    done
    if compgen -G "$clone/deploy/.state/$name/install-*" >/dev/null; then left+="install evidence started; "; fi
  fi
  if ((status != 0)) && grep -Eq "$message" "$out" && [[ -z "$left" ]]; then
    verdict "$label" PASS "exit $status: $(grep -Em1 "$message" "$out" | cut -c1-200)"
  else
    verdict "$label" FAIL "exit $status, ${left}output: $(tail -3 "$out" | tr '\n' ' ' | cut -c1-300)"
  fi
}

install=deploy/install.sh uninstall=deploy/uninstall.sh foundation=deploy/scripts/foundation/deploy.sh
name_rule='COLVENNA_ENV=<env>: a lowercase letter, then lowercase letters and digits'
region_rule='COLVENNA_REGION=<region>: environment .* standard AWS region'

# Names: the rule, and the development PoC's reserved names.
refused name-uppercase "$clone" "$name_rule" COLVENNA_ENV=Uat COLVENNA_REGION=us-fake-1 -- bash "$install" --only tools
refused name-digit-first "$clone" "$name_rule" COLVENNA_ENV=1abc COLVENNA_REGION=us-fake-1 -- bash "$install" --only tools
refused name-too-long "$clone" "$name_rule" COLVENNA_ENV=abcdefghi COLVENNA_REGION=us-fake-1 -- bash "$install" --only tools
refused name-reserved-m1 "$clone" 'COLVENNA_ENV m1 is reserved' COLVENNA_ENV=m1 COLVENNA_REGION=us-fake-1 -- bash "$install" --only tools
refused name-reserved-poc "$clone" 'COLVENNA_ENV poc is reserved' COLVENNA_ENV=poc COLVENNA_REGION=us-fake-1 -- bash "$install" --only tools

# Regions: none given, China, GovCloud, not a region name; a standard name without endpoints; a
# region the account has not enabled.
refused region-missing "$clone" "$region_rule" "COLVENNA_ENV=$fresh" -- bash "$install" --only tools
refused region-china "$clone" "$region_rule" "COLVENNA_ENV=$fresh" COLVENNA_REGION=cn-north-1 -- bash "$install" --only tools
refused region-china-northwest "$clone" "$region_rule" "COLVENNA_ENV=$fresh" COLVENNA_REGION=cn-northwest-1 -- bash "$install" --only tools
refused region-govcloud "$clone" "$region_rule" "COLVENNA_ENV=$fresh" COLVENNA_REGION=us-gov-west-1 -- bash "$install" --only tools
refused region-malformed "$clone" "$region_rule" "COLVENNA_ENV=$fresh" COLVENNA_REGION=uswest2 -- bash "$install" --only tools
# The account is checked first, by STS in the region: one without endpoints, or not enabled, cannot answer.
refused region-unreachable "$clone" "Cannot read the caller's account in us-fake-1" "COLVENNA_ENV=$fresh" COLVENNA_REGION=us-fake-1 \
  -- bash "$install" --only tools
disabled="$(aws ec2 describe-regions --all-regions --region "$home" --output text \
  --query "Regions[?OptInStatus=='not-opted-in'].RegionName | sort(@) | [0]")"
if [[ "$disabled" =~ ^[a-z]{2}-[a-z]+-[1-9]$ ]]; then
  refused region-not-enabled "$clone" "Cannot read the caller's account in $disabled" "COLVENNA_ENV=$fresh" "COLVENNA_REGION=$disabled" \
    -- bash "$install" --only tools
else
  verdict region-not-enabled FAIL "no region of the account is left not enabled to try (${disabled:-none})"
fi

# The environment's name in another region, from a tree that has not recorded its region: the
# account's IAM roles of that name stop it before anything is made or recorded.
refused name-held-by-another-region "$clone" "Environment $held is not in $other, yet [0-9]+ IAM roles of its name exist" \
  "COLVENNA_ENV=$held" "COLVENNA_REGION=$other" -- bash "$install" --only tools
# The environment's own tree, another region than the one it recorded.
refused region-not-the-recorded-one "$REPO_DIR" "Environment $held is in $home, not $other" \
  "COLVENNA_ENV=$held" "COLVENNA_REGION=$other" -- bash "$install" --only tools

# Steps and options.
refused step-unknown-only "$REPO_DIR" 'No step nosuch' "COLVENNA_ENV=$held" -- bash "$install" --only nosuch
refused step-unknown-from "$REPO_DIR" 'No step nosuch' "COLVENNA_ENV=$held" -- bash "$install" --from nosuch
refused option-unknown "$REPO_DIR" '# usage: COLVENNA_ENV=<env>' "COLVENNA_ENV=$held" -- bash "$install" --bogus

# A tree with local changes: an install must be traceable to its commit.
touch "$clone/scratch.txt"
refused tree-dirty "$clone" 'Commit or remove local changes first' "COLVENNA_ENV=$fresh" COLVENNA_REGION=us-fake-1 \
  -- bash "$install" --only tools
rm -f "$clone/scratch.txt"

# The settings (#212): the account, the hosted zone, the entry domain, for the fresh name in the
# environment's region; then the environment's recorded ones changed.
account="$COLVENNA_ACCOUNT" zone_id="$COLVENNA_HOSTED_ZONE_ID" zone_name="$COLVENNA_HOSTED_ZONE_NAME"
other_account=111122223333
[[ "$account" != "$other_account" ]] || other_account=444455556666
account_rule='COLVENNA_ACCOUNT=<account>: the 12-digit AWS account'
zone_rule='COLVENNA_HOSTED_ZONE_ID=<Z...> COLVENNA_HOSTED_ZONE_NAME=<name>'
domain_rule='COLVENNA_ENTRY_DOMAIN=.*: a lowercase domain within the zone'
in_home=("COLVENNA_ENV=$fresh" "COLVENNA_REGION=$home")
refused account-missing "$clone" "$account_rule" "${in_home[@]}" COLVENNA_ACCOUNT= -- bash "$install" --only tools
refused account-malformed "$clone" "$account_rule" "${in_home[@]}" COLVENNA_ACCOUNT=12345 -- bash "$install" --only tools
refused account-not-the-callers "$clone" "The credentials are of AWS account $account, not $other_account" \
  "${in_home[@]}" "COLVENNA_ACCOUNT=$other_account" -- bash "$install" --only tools
refused uninstall-account-not-the-callers "$clone" "The credentials are of AWS account $account, not $other_account" \
  "${in_home[@]}" "COLVENNA_ACCOUNT=$other_account" -- bash "$uninstall" --yes "$fresh"
refused zone-missing "$clone" "$zone_rule" "${in_home[@]}" COLVENNA_HOSTED_ZONE_ID= -- bash "$install" --only tools
refused zone-malformed "$clone" "$zone_rule" "${in_home[@]}" COLVENNA_HOSTED_ZONE_ID=not-a-zone -- bash "$install" --only tools
refused zone-not-in-account "$clone" "Cannot read hosted zone Z00000000NOSUCHZONE0 in account $account" "${in_home[@]}" \
  COLVENNA_HOSTED_ZONE_ID=Z00000000NOSUCHZONE0 -- bash "$install" --only tools
refused zone-name-mismatch "$clone" "Hosted zone $zone_id is $zone_name, not COLVENNA_HOSTED_ZONE_NAME other-$zone_name" \
  "${in_home[@]}" "COLVENNA_HOSTED_ZONE_NAME=other-$zone_name" -- bash "$install" --only tools
# The environment's zone, which the stub answers as a private one.
refused zone-private "$clone" "Hosted zone $zone_id is private" "${in_home[@]}" STUB_CASE=zone-private \
  -- bash "$install" --only tools
refused entry-domain-outside-the-zone "$clone" "$domain_rule" "${in_home[@]}" \
  "COLVENNA_ENTRY_DOMAIN=colvenna-$fresh.example.org" -- bash "$install" --only tools
refused entry-domain-is-the-zone "$clone" "$domain_rule" "${in_home[@]}" "COLVENNA_ENTRY_DOMAIN=$zone_name" \
  -- bash "$install" --only tools
refused entry-domain-upper-case "$clone" "$domain_rule" "${in_home[@]}" "COLVENNA_ENTRY_DOMAIN=Colvenna-$fresh.$zone_name" \
  -- bash "$install" --only tools
# The entry domain: the environment's own (records under it), and one under its entry. These and the
# Doris cases below come after the room check, which a region already holding environments may fail
# (us-east-2's Elastic IPs with uate2 in it): the stub answers the quotas as roomy (STUB_CASE=room).
refused entry-domain-not-free "$clone" "COLVENNA_ENTRY_DOMAIN $ENTRY_DOMAIN is not free: hosted zone $zone_name has" \
  "${in_home[@]}" STUB_CASE=room "COLVENNA_ENTRY_DOMAIN=$ENTRY_DOMAIN" -- bash "$install" --only tools
refused entry-domain-under-another-entry "$clone" "COLVENNA_ENTRY_DOMAIN x.$ENTRY_DOMAIN is under $ENTRY_DOMAIN, another environment's entry" \
  "${in_home[@]}" STUB_CASE=room "COLVENNA_ENTRY_DOMAIN=x.$ENTRY_DOMAIN" -- bash "$install" --only tools
# A source install's Doris images: a source that does not hold them, one that is no ECR repository.
# The doris-image step alone, so that the check runs; were it to pass, the step stops at the missing
# foundation before it copies anything.
refused doris-source-without-the-images "$clone" "does not hold the query engine's FE" "${in_home[@]}" \
  STUB_CASE=room "DORIS_IMAGE_SOURCE=$ECR_REGISTRY/$(env_name colvenna-lake/colvenna-catalog)" -- bash "$install" --only doris-image
refused doris-source-malformed "$clone" "DORIS_IMAGE_SOURCE must be an ECR repository in a standard region" \
  "${in_home[@]}" STUB_CASE=room DORIS_IMAGE_SOURCE=docker.io/apache/doris -- bash "$install" --only doris-image
refused recorded-account-changed "$REPO_DIR" "Environment $held has COLVENNA_ACCOUNT=$account, not $other_account" \
  "COLVENNA_ENV=$held" "COLVENNA_ACCOUNT=$other_account" -- bash "$install" --only tools
refused recorded-zone-changed "$REPO_DIR" "Environment $held has COLVENNA_HOSTED_ZONE_ID=$zone_id, not Z0OTHERZONE" \
  "COLVENNA_ENV=$held" COLVENNA_HOSTED_ZONE_ID=Z0OTHERZONE -- bash "$install" --only tools
refused recorded-entry-domain-changed "$REPO_DIR" "Environment $held has COLVENNA_ENTRY_DOMAIN=.*, not other.$zone_name" \
  "COLVENNA_ENV=$held" "COLVENNA_ENTRY_DOMAIN=other.$zone_name" -- bash "$install" --only tools

# A region that cannot hold the environment (lib/preflight.sh), the fresh name in the environment's
# region, each with the one answer the stub fakes.
version="$(foundation_spec kubernetesVersion)"
refused kubernetes-version-missing "$clone" "EKS in $home does not offer Kubernetes $version" "${in_home[@]}" \
  STUB_CASE=kubernetes-version-missing -- bash "$install" --only tools
refused node-types-missing "$clone" "No two availability zones of $home offer both node types, .*: 0 of" \
  "${in_home[@]}" STUB_CASE=node-types-missing -- bash "$install" --only tools
refused node-types-in-one-zone "$clone" "No two availability zones of $home offer both node types, .*: 1 of" \
  "${in_home[@]}" STUB_CASE=node-types-in-one-zone -- bash "$install" --only tools
refused vpc-quota-full "$clone" "$home has [0-9]+ VPCs of its quota of 0 \(vpc L-F678F1CE" "${in_home[@]}" \
  STUB_CASE=vpc-quota-full -- bash "$install" --only tools
refused address-quota-full "$clone" "$home has [0-9]+ Elastic IPs of its quota of 0 \(ec2 L-0263D0A3" \
  "${in_home[@]}" STUB_CASE=address-quota-full -- bash "$install" --only tools

# The foundation alone, for a name whose region is not recorded (a real region: it checks the
# account first).
refused foundation-without-region "$clone" "No region recorded for $fresh" "COLVENNA_ENV=$fresh" "COLVENNA_REGION=$other" \
  -- bash "$foundation"

# Uninstall: without the name confirmed, and in a region that holds nothing of the name.
refused uninstall-unconfirmed "$clone" "usage: COLVENNA_ENV=$fresh deploy/uninstall.sh --yes $fresh" \
  "COLVENNA_ENV=$fresh" COLVENNA_REGION=us-fake-1 -- bash "$uninstall"
refused uninstall-other-name "$clone" "usage: COLVENNA_ENV=$fresh deploy/uninstall.sh --yes $fresh" \
  "COLVENNA_ENV=$fresh" COLVENNA_REGION=us-fake-1 -- bash "$uninstall" --yes "$held"
refused uninstall-region-holds-nothing "$clone" "No stack of $fresh in $other, and no uninstall begun" \
  "COLVENNA_ENV=$fresh" "COLVENNA_REGION=$other" -- bash "$uninstall" --yes "$fresh"

# A real environment's uninstall, in a region where it has nothing.
probe="" probe_home=""
for candidate in ${UNINSTALL_PROBE_ENV:-plat uate2}; do
  # Never the environment under test, never uat (the long-lived reference environment).
  if [[ "$candidate" == "$held" || "$candidate" == uat || ! "$candidate" =~ ^[a-z][a-z0-9]{0,7}$ ]]; then
    echo "UNINSTALL_PROBE_ENV: $candidate is not a disposable environment to try; skipped"
    continue
  fi
  for region in "$home" "$other"; do
    if grep -q "^ColvennaLake${candidate^}Foundation	" <<<"$(stacks_of "$candidate" "$region")"; then
      probe="$candidate" probe_home="$region"
      break 2
    fi
  done
done
if [[ -z "$probe" ]]; then
  verdict uninstall-real-env-wrong-region FAIL \
    "no disposable environment (${UNINSTALL_PROBE_ENV:-plat uate2}) lives in $home or $other to try it with"
else
  wrong="$other"
  [[ "$probe_home" != "$other" ]] || wrong="$home"
  stacks_of "$probe" "$probe_home" >"$evidence/probe-stacks-before.txt"
  if [[ -n "$(stacks_of "$probe" "$wrong")" ]]; then
    verdict uninstall-real-env-wrong-region FAIL "$probe has stacks in $wrong too: no wrong region to try"
  else
    refused uninstall-real-env-wrong-region "$clone" "No stack of $probe in $wrong, and no uninstall begun" \
      "COLVENNA_ENV=$probe" "COLVENNA_REGION=$wrong" -- bash "$uninstall" --yes "$probe"
    stacks_of "$probe" "$probe_home" >"$evidence/probe-stacks-after.txt"
    if ! cmp -s "$evidence/probe-stacks-before.txt" "$evidence/probe-stacks-after.txt"; then
      verdict uninstall-real-env-untouched FAIL "$probe's stacks in $probe_home changed: $(diff "$evidence/probe-stacks-before.txt" "$evidence/probe-stacks-after.txt" | tr '\n' ' ' | cut -c1-200)"
    fi
  fi
fi

# --------------------------------------------------------------------------- nothing made
left=""
for region in "$home" "$other"; do
  stacks="$(stacks_of "$fresh" "$region")"
  [[ -z "$stacks" ]] || left+="stacks in $region: $stacks; "
done
roles="$(roles_of "$fresh" "$fresh_stack")"
[[ -z "$roles" ]] || left+="roles: $roles; "
recorded="$(for setting in "${COLVENNA_SETTINGS[@]}"; do find "$clone/deploy/.state" -name "${setting#*:}" 2>/dev/null; done \
  | tr '\n' ' ')"
[[ -z "$recorded" ]] || left+="settings recorded in the clone: $recorded; "
if [[ -z "$left" ]]; then
  verdict nothing-made PASS "no stack of $fresh in $home or $other, no role, no setting recorded"
else
  verdict nothing-made FAIL "$left"
fi
stacks_of "$held" "$home" >"$evidence/held-stacks-after.txt"
stacks_of "$held" "$other" >"$evidence/held-stacks-other-region-after.txt"
if cmp -s "$evidence/held-stacks-before.txt" "$evidence/held-stacks-after.txt" \
  && cmp -s "$evidence/held-stacks-other-region-before.txt" "$evidence/held-stacks-other-region-after.txt" \
  && cmp -s "$evidence/held-settings-before.txt" <(held_settings); then
  verdict environment-untouched PASS "$held: its settings as recorded (in $home), $(wc -l <"$evidence/held-stacks-after.txt") stacks there not updated, none in $other"
else
  verdict environment-untouched FAIL "$(diff "$evidence/held-stacks-before.txt" "$evidence/held-stacks-after.txt" | tr '\n' ' ' | cut -c1-300)"
fi
echo "verdicts:"
cat "$evidence/verdicts.tsv"
exit "$failed"
