#!/usr/bin/env bash
# The environment the deploy scripts and the acceptance tests work on, sourced by
# deploy/scripts/lib/common.sh, install.sh and uninstall.sh.
#
# COLVENNA_ENV=<name> (required; a lowercase letter, then lowercase letters and digits, at most 8): one
# complete environment in an AWS account. Its stacks, AWS resources, entry domain and local state
# are its own, so several environments live side by side.
#
# The environment's settings, each required for a new environment (none has a default but the entry
# domain's):
#   COLVENNA_REGION=<region>  the standard AWS region (partition aws, e.g. us-west-2 or
#                             eu-central-1) the environment lives in, exported as AWS_REGION and
#                             AWS_DEFAULT_REGION for the AWS CLI, CDK and SDKs. China (cn-...) and
#                             GovCloud regions are other partitions, with their own ARNs and
#                             endpoints: not supported.
#   COLVENNA_ACCOUNT=<id>     the 12-digit AWS account it is installed into. The caller's own
#                             credentials (their AWS_PROFILE, or the default chain) must be of it:
#                             assert_account.
#   COLVENNA_HOSTED_ZONE_ID=<id>, COLVENNA_HOSTED_ZONE_NAME=<name>
#                             a public Route 53 hosted zone of that account, which the web entry's
#                             names go into and whose records validate the entry's certificate:
#                             assert_hosted_zone checks it is there, public, and of that name.
#   COLVENNA_ENTRY_DOMAIN=<domain>
#                             the web entry's domain, <app>.<domain>, within the zone (default
#                             colvenna-<env>.<zone name>); the environment's own, uninstall.sh
#                             deletes every record under it.
# install.sh records them in the environment's local state, deploy/.state/<env>/ (one file each),
# once it has checked them and the name; afterwards they are read from there, and another value is
# refused. uninstall.sh deletes the record once nothing of the environment is left.
#
# A caller that needs only the environment's stack names (install.sh --list, which prints its steps)
# sources it with the argument names-only: this file then stops once they are set, and neither reads
# nor requires the settings. An argument, not a variable: an exported one would reach every script.
# shellcheck disable=SC2034  # names used by the scripts that source this file
colvenna_names_only="${1:-}"
COLVENNA_ENV="${COLVENNA_ENV:-}"
if [[ ! "$COLVENNA_ENV" =~ ^[a-z][a-z0-9]{0,7}$ ]]; then
  echo "COLVENNA_ENV=<env>: a lowercase letter, then lowercase letters and digits, at most 8 in all" >&2
  exit 1
fi
# Reserved: the development PoC (tag history/m1-m5) named its stacks and resources after m<n>,
# under the product's former name; an environment named so would read as one of them.
if [[ "$COLVENNA_ENV" =~ ^(m[0-9]?|poc)$ ]]; then
  echo "COLVENNA_ENV $COLVENNA_ENV is reserved: the development PoC's names start so" >&2
  exit 1
fi
# Reserved too: the release registry's repositories are colvenna-lake-release/...
# (deploy/scripts/release).
if [[ "$COLVENNA_ENV" == release ]]; then
  echo "COLVENNA_ENV release is reserved: the release registry's names start so" >&2
  exit 1
fi

# env_stack <component>: the environment's CloudFormation stack,
# Foundation -> ColvennaLakeUatFoundation.
env_stack() {
  printf 'ColvennaLake%s%s\n' "${COLVENNA_ENV^}" "$1"
}
FOUNDATION_STACK="$(env_stack Foundation)"
CATALOG_STACK="$(env_stack Catalog)"
OPERATOR_STACK="$(env_stack Operator)"
OPEN_LAYER_STACK="$(env_stack OpenLayer)"
ENTRY_STACK="$(env_stack Entry)"
# The acceptance tests' source databases (tests/fixtures/sources).
TEST_MYSQL_STACK="$(env_stack TestMysql)"
TEST_POSTGRES_STACK="$(env_stack TestPostgres)"
# The names alone (install.sh --list): no settings read, none required.
if [[ "$colvenna_names_only" == names-only ]]; then
  return 0
fi

# standard_region <region>: whether it names a standard AWS region, as deploy/aws/src/env.ts's
# standardRegion does: three parts, the last a digit, and not cn-...; GovCloud's (us-gov-west-1) have
# four.
standard_region() {
  [[ "$1" =~ ^[a-z]{2}-[a-z]+-[1-9]$ && "$1" != cn-* ]]
}
# dns_name <name>: whether it is a lowercase DNS name of two labels or more, without the final dot.
dns_name() {
  [[ "$1" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?$ && ${#1} -le 253 ]]
}
COLVENNA_STATE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.state/$COLVENNA_ENV"
COLVENNA_REGION_FILE="$COLVENNA_STATE_DIR/region"
# <variable>:<file>: each setting and the file of the local state it is recorded in.
COLVENNA_SETTINGS=(COLVENNA_REGION:region COLVENNA_ACCOUNT:account COLVENNA_HOSTED_ZONE_ID:hosted-zone-id
  COLVENNA_HOSTED_ZONE_NAME:hosted-zone-name COLVENNA_ENTRY_DOMAIN:entry-domain)
[[ -z "${COLVENNA_HOSTED_ZONE_NAME:-}" ]] || COLVENNA_HOSTED_ZONE_NAME="${COLVENNA_HOSTED_ZONE_NAME%.}"
for colvenna_setting in "${COLVENNA_SETTINGS[@]}"; do
  colvenna_name="${colvenna_setting%%:*}" colvenna_file="$COLVENNA_STATE_DIR/${colvenna_setting#*:}"
  if [[ -s "$colvenna_file" ]]; then
    if [[ -n "${!colvenna_name:-}" && "${!colvenna_name}" != "$(<"$colvenna_file")" ]]; then
      if [[ "$colvenna_name" == COLVENNA_REGION ]]; then
        echo "Environment $COLVENNA_ENV is in $(<"$colvenna_file")," \
          "not $COLVENNA_REGION ($colvenna_file)" >&2
      else
        echo "Environment $COLVENNA_ENV has $colvenna_name=$(<"$colvenna_file")," \
          "not ${!colvenna_name} ($colvenna_file)" >&2
      fi
      exit 1
    fi
    printf -v "$colvenna_name" '%s' "$(<"$colvenna_file")"
  fi
done
unset colvenna_setting colvenna_name colvenna_file
if ! standard_region "${COLVENNA_REGION:-}"; then
  echo "COLVENNA_REGION=<region>: environment $COLVENNA_ENV's standard AWS region, e.g. us-west-2" \
    "(China and GovCloud regions are not supported)" >&2
  exit 1
fi
if [[ ! "${COLVENNA_ACCOUNT:-}" =~ ^[0-9]{12}$ ]]; then
  echo "COLVENNA_ACCOUNT=<account>: the 12-digit AWS account environment $COLVENNA_ENV" \
    "is installed into" >&2
  exit 1
fi
if [[ ! "${COLVENNA_HOSTED_ZONE_ID:-}" =~ ^Z[A-Z0-9]{1,31}$ ]] \
  || ! dns_name "${COLVENNA_HOSTED_ZONE_NAME:-}"; then
  echo "COLVENNA_HOSTED_ZONE_ID=<Z...> COLVENNA_HOSTED_ZONE_NAME=<name>: the public Route 53" \
    "hosted zone of account $COLVENNA_ACCOUNT that environment $COLVENNA_ENV's web entry names go into" >&2
  exit 1
fi
COLVENNA_ENTRY_DOMAIN="${COLVENNA_ENTRY_DOMAIN:-colvenna-$COLVENNA_ENV.$COLVENNA_HOSTED_ZONE_NAME}"
if ! dns_name "$COLVENNA_ENTRY_DOMAIN" \
  || [[ "$COLVENNA_ENTRY_DOMAIN" != *".$COLVENNA_HOSTED_ZONE_NAME" ]]; then
  echo "COLVENNA_ENTRY_DOMAIN=$COLVENNA_ENTRY_DOMAIN: a lowercase domain within the zone" \
    "$COLVENNA_HOSTED_ZONE_NAME (below it, not the zone itself)" >&2
  exit 1
fi
export COLVENNA_REGION AWS_REGION="$COLVENNA_REGION" AWS_DEFAULT_REGION="$COLVENNA_REGION"
export COLVENNA_ACCOUNT COLVENNA_HOSTED_ZONE_ID COLVENNA_HOSTED_ZONE_NAME COLVENNA_ENTRY_DOMAIN

# record_settings: the settings into the environment's local state (install.sh, once they and the
# name are checked; uninstall.sh, so a resumed run cannot be given others).
record_settings() {
  local setting name
  mkdir -p "$COLVENNA_STATE_DIR"
  for setting in "${COLVENNA_SETTINGS[@]}"; do
    name="${setting%%:*}"
    printf '%s\n' "${!name}" >"$COLVENNA_STATE_DIR/${setting#*:}"
  done
}

# forget_settings: the record gone (uninstall.sh, once nothing of the environment is left), so the
# name may be used again, anywhere.
forget_settings() {
  local setting
  for setting in "${COLVENNA_SETTINGS[@]}"; do
    rm -f "$COLVENNA_STATE_DIR/${setting#*:}"
  done
}

# aws_read <what> <aws arguments...>: the AWS CLI's answer, its standard output alone; when the call
# fails, says it cannot read <what>, with the CLI's error on one line, and fails.
aws_read() {
  local what="$1" err out status=0
  shift
  err="$(mktemp)"
  out="$(aws "$@" 2>"$err")" || status=$?
  if ((status != 0)); then
    echo "Cannot read $what: $(tr -s '\n' ' ' <"$err" | sed 's/^ //')" >&2
    rm -f "$err"
    return "$status"
  fi
  rm -f "$err"
  printf '%s\n' "$out"
}

# assert_account: the caller's credentials are of COLVENNA_ACCOUNT, as STS in the environment's region
# answers (a region without endpoints, or one the account has not enabled, cannot answer: refused).
assert_account() {
  local account
  account="$(aws_read "the caller's account in $COLVENNA_REGION (AWS STS)" sts get-caller-identity \
    --query Account --output text)" || exit 1
  if [[ "$account" != "$COLVENNA_ACCOUNT" ]]; then
    echo "The credentials are of AWS account $account, not $COLVENNA_ACCOUNT" \
      "(COLVENNA_ACCOUNT of $COLVENNA_ENV): use credentials of $COLVENNA_ACCOUNT" \
      "(AWS_PROFILE=<profile>, or the default chain)" >&2
    exit 1
  fi
}

# assert_hosted_zone: COLVENNA_HOSTED_ZONE_ID is a public hosted zone of the account, named
# COLVENNA_HOSTED_ZONE_NAME.
assert_hosted_zone() {
  local zone
  zone="$(aws_read "hosted zone $COLVENNA_HOSTED_ZONE_ID in account $COLVENNA_ACCOUNT" \
    route53 get-hosted-zone --id "$COLVENNA_HOSTED_ZONE_ID" --output json)" || exit 1
  python3 - "$zone" "$COLVENNA_HOSTED_ZONE_ID" "$COLVENNA_HOSTED_ZONE_NAME" <<'PY' || exit 1
import json
import sys

zone, zone_id, name = json.loads(sys.argv[1])["HostedZone"], sys.argv[2], sys.argv[3]
if zone.get("Config", {}).get("PrivateZone", True):
    sys.exit(f"Hosted zone {zone_id} is private: the web entry's names need a public one")
if zone["Name"].rstrip(".") != name:
    sys.exit(f"Hosted zone {zone_id} is {zone['Name'].rstrip('.')}, not COLVENNA_HOSTED_ZONE_NAME {name}")
PY
}

# env_name <name>: the environment's name of an AWS resource (ECR repository, IAM role, secret,
# log group), its first "colvenna-lake" made "colvenna-lake-<env>": colvenna-lake/colvenna-jobs ->
# colvenna-lake-uat/colvenna-jobs, /colvenna-lake/console-audit -> /colvenna-lake-uat/console-audit.
env_name() {
  printf '%s\n' "${1/colvenna-lake/colvenna-lake-$COLVENNA_ENV}"
}

# The web entry's names, <app>.<ENTRY_DOMAIN>, in the hosted zone: the settings COLVENNA_ENTRY_DOMAIN,
# COLVENNA_HOSTED_ZONE_ID and COLVENNA_HOSTED_ZONE_NAME under the names the scripts read them by.
ENTRY_DOMAIN="$COLVENNA_ENTRY_DOMAIN"
HOSTED_ZONE_ID="$COLVENNA_HOSTED_ZONE_ID"
HOSTED_ZONE_NAME="$COLVENNA_HOSTED_ZONE_NAME"
