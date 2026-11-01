#!/usr/bin/env bash
# Whether the environment can be installed as asked, read-only, sourced after deploy/env.sh: by
# deploy/install.sh before anything is made or recorded, by foundation/deploy.sh for its zones, by
# doris/copy-images.sh for its source and by deploy/uninstall.sh for the entry domain. The region can
# hold it and is CDK-bootstrapped, its entry domain is its own, and a source install has the Doris
# images to copy. What the foundation needs comes from
# deploy/aws/src/foundation-spec.json, which the stack reads too: its Kubernetes version (pinned with
# the stack's kubectl layer, KubectlV36Layer: not a free choice) and the two node groups' instance
# types. Every AWS answer is read whole, as JSON, and judged here, so a refusal names what is missing
# and what was found.

PREFLIGHT_SPEC="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../aws/src" && pwd)/foundation-spec.json"

# foundation_spec <key>: kubernetesVersion, writeInstanceType or queryInstanceType.
foundation_spec() {
  python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$PREFLIGHT_SPEC" "$1"
}

# stack_outputs <stack>: the stack's outputs as JSON, or nothing when the stack does not exist. Only
# "does not exist" is no stack: any other failure (a region the account has not enabled, throttling,
# expired credentials, a denied call) stops, so that nothing is taken for absent wrongly.
stack_outputs() {
  local stack="$1" err out status=0
  err="$(mktemp)"
  out="$(aws cloudformation describe-stacks --stack-name "$stack" --query 'Stacks[0].Outputs' \
    --output json 2>"$err")" || status=$?
  if ((status != 0)); then
    if ! grep -q "$stack does not exist" "$err"; then
      echo "Cannot read $stack in $COLVENNA_REGION: $(tr -s '\n' ' ' <"$err" | sed 's/^ //')" >&2
      rm -f "$err"
      return 1
    fi
    out=""
  fi
  rm -f "$err"
  printf '%s\n' "$out"
}

# foundation_outputs: the foundation stack's outputs as JSON, or nothing when it does not exist (a new
# environment).
foundation_outputs() {
  stack_outputs "$FOUNDATION_STACK"
}

# foundation_vpc: the foundation's VPC, or nothing for a new environment.
foundation_vpc() {
  local outputs
  outputs="$(foundation_outputs)" || return 1
  [[ -z "$outputs" ]] || python3 -c 'import json, sys
print(next((o["OutputValue"] for o in json.loads(sys.argv[1]) or [] if o["OutputKey"] == "VpcId"), ""))' "$outputs"
}

# choose_zones: the foundation's two availability zones, comma-separated. An existing foundation's
# are its VPC's (a VPC does not move), and must still offer both node types; a new one takes the
# region's first two, by name, that offer both. Refuses otherwise, naming each zone's missing types.
choose_zones() {
  local vpc write query zones offerings
  write="$(foundation_spec writeInstanceType)" query="$(foundation_spec queryInstanceType)"
  vpc="$(foundation_vpc)" || return 1
  if [[ "$vpc" == vpc-* ]]; then
    zones="$(aws ec2 describe-subnets --filters "Name=vpc-id,Values=$vpc" --output json)" || return 1
  else
    zones="$(aws ec2 describe-availability-zones --filters Name=opt-in-status,Values=opt-in-not-required \
      Name=state,Values=available --output json)" || return 1
  fi
  offerings="$(aws ec2 describe-instance-type-offerings --location-type availability-zone \
    --filters "Name=instance-type,Values=$write,$query" --output json)" || return 1
  python3 - "$zones" "$offerings" "$write" "$query" "$vpc" "$COLVENNA_REGION" <<'PY'
import json
import sys

zones_json, offerings_json, write, query, vpc, region = sys.argv[1:]
answer = json.loads(zones_json)
if vpc:
    zones = sorted({s["AvailabilityZone"] for s in answer.get("Subnets", [])})
else:
    zones = sorted(z["ZoneName"] for z in answer.get("AvailabilityZones", []))
offered = {}
for o in json.loads(offerings_json).get("InstanceTypeOfferings", []):
    offered.setdefault(o["Location"], set()).add(o["InstanceType"])
need = {write, query}
lacking = {z: sorted(need - offered.get(z, set())) for z in zones if not need <= offered.get(z, set())}
said = "; ".join(f"{z} lacks {', '.join(t)}" for z, t in sorted(lacking.items()))
if vpc:
    if len(zones) != 2 or lacking:
        sys.exit(f"The VPC {vpc} of the environment is in {len(zones)} availability zones of {region}"
                 f" ({', '.join(zones) or 'none'}){': ' + said if said else ''}; it needs two that offer"
                 f" {write} and {query}")
    print(",".join(zones))
else:
    good = [z for z in zones if z not in lacking]
    if len(good) < 2:
        sys.exit(f"No two availability zones of {region} offer both node types, {write} and {query}:"
                 f" {len(good)} of {len(zones)} do{' (' + said + ')' if said else ''}")
    print(",".join(good[:2]))
PY
}

# assert_kubernetes_version: EKS in the region offers the foundation's Kubernetes version, in
# standard or extended support.
assert_kubernetes_version() {
  local version answer
  version="$(foundation_spec kubernetesVersion)"
  answer="$(aws eks describe-cluster-versions --cluster-versions "$version" --output json)" || return 1
  python3 - "$answer" "$version" "$COLVENNA_REGION" <<'PY'
import json
import sys

answer, version, region = sys.argv[1:]
found = [v.get("versionStatus") or v.get("status") for v in json.loads(answer).get("clusterVersions", [])
         if v.get("clusterVersion") == version]
if not any(s in ("STANDARD_SUPPORT", "EXTENDED_SUPPORT") for s in found):
    sys.exit(f"EKS in {region} does not offer Kubernetes {version}, the foundation's"
             f" (found: {', '.join(map(str, found)) or 'no such version'})")
PY
}

# quota <service> <code>: the region's applied value of a service quota, else its default.
quota() {
  local answer
  answer="$(aws service-quotas get-service-quota --service-code "$1" --quota-code "$2" --output json 2>/dev/null)" \
    || answer="$(aws service-quotas get-aws-default-service-quota --service-code "$1" --quota-code "$2" \
      --output json)" || return 1
  python3 -c 'import json, sys; print(int(json.loads(sys.argv[1])["Quota"]["Value"]))' "$answer"
}

# assert_room: room under the region's quotas for what a new environment's foundation allocates
# (foundation-stack.ts): one VPC, and two Elastic IPs, its NAT gateway's and the unassociated
# address the EKS endpoint first allows.
assert_room() {
  local vpcs vpc_quota addresses address_quota
  vpcs="$(aws ec2 describe-vpcs --output json | python3 -c 'import json, sys; print(len(json.load(sys.stdin)["Vpcs"]))')"
  vpc_quota="$(quota vpc L-F678F1CE)" || return 1
  addresses="$(aws ec2 describe-addresses --output json \
    | python3 -c 'import json, sys; print(len(json.load(sys.stdin)["Addresses"]))')"
  address_quota="$(quota ec2 L-0263D0A3)" || return 1
  if ((vpcs + 1 > vpc_quota)); then
    echo "$COLVENNA_REGION has $vpcs VPCs of its quota of $vpc_quota (vpc L-F678F1CE, VPCs per Region):" \
      "a new environment needs 1 more" >&2
    return 1
  fi
  if ((addresses + 2 > address_quota)); then
    echo "$COLVENNA_REGION has $addresses Elastic IPs of its quota of $address_quota (ec2 L-0263D0A3," \
      "EC2-VPC Elastic IPs): a new environment needs 2 more" >&2
    return 1
  fi
  echo "room in $COLVENNA_REGION: $vpcs of $vpc_quota VPCs and $addresses of $address_quota Elastic IPs in use;" \
    "a new environment takes 1 and 2"
}

# The CDK bootstrap every stack is deployed with (cdk bootstrap: the CDKToolkit stack, its roles and
# asset storage), as the SSM parameter of the default qualifier records its version. Each stack's
# CheckBootstrapVersion rule (aws-cdk-lib's DefaultStackSynthesizer) requires version 6 or newer.
CDK_BOOTSTRAP_PARAMETER=/cdk-bootstrap/hnb659fds/version
CDK_BOOTSTRAP_VERSION=6

# assert_cdk_bootstrap: the account has CDK-bootstrapped the region, recently enough for the stacks;
# otherwise a new account's install would record its settings and fail only at the foundation step.
# The fix it names runs the CDK CLI of deploy/aws without the app (--app ''): the app synthesizes
# only with the context the deploy scripts give it.
assert_cdk_bootstrap() {
  local err answer status=0 fix
  fix="(cd deploy/aws && npm ci && npx cdk bootstrap aws://$COLVENNA_ACCOUNT/$COLVENNA_REGION --app '')"
  err="$(mktemp)"
  answer="$(aws ssm get-parameter --name "$CDK_BOOTSTRAP_PARAMETER" --output json 2>"$err")" || status=$?
  if ((status != 0)); then
    if grep -q ParameterNotFound "$err"; then
      echo "Account $COLVENNA_ACCOUNT has not CDK-bootstrapped $COLVENNA_REGION (no SSM parameter" \
        "$CDK_BOOTSTRAP_PARAMETER), which every stack is deployed with: run $fix, then install again" >&2
    else
      echo "Cannot read the CDK bootstrap version of $COLVENNA_REGION ($CDK_BOOTSTRAP_PARAMETER):" \
        "$(tr -s '\n' ' ' <"$err" | sed 's/^ //')" >&2
    fi
    rm -f "$err"
    return 1
  fi
  rm -f "$err"
  python3 - "$answer" "$CDK_BOOTSTRAP_VERSION" "$COLVENNA_REGION" "$fix" <<'PY'
import json
import sys

answer, need, region, fix = sys.argv[1:]
version = json.loads(answer)["Parameter"]["Value"]
if not version.isdigit() or int(version) < int(need):
    sys.exit(f"The CDK bootstrap of {region} is version {version}; the stacks need {need} or newer: run {fix},"
             " then install again")
PY
}

# assert_entry_domain <new>: the entry domain is the environment's own; uninstall.sh deletes every
# record at or under it. A new environment's (<new> true): no record of the hosted zone is at or under
# it, no other environment's entry (a console. or bi. record) is above it, and no other environment
# this checkout's local state records has it, or one above or below it. An existing environment that
# has not recorded its entry domain yet (installed before the settings were; install.sh, and
# uninstall.sh before it deletes anything): it is the one its entry stack serves, when there is one.
assert_entry_domain() {
  local records others outputs url
  if [[ "$1" == true ]]; then
    records="$(aws_read "the records of hosted zone $COLVENNA_HOSTED_ZONE_ID" route53 list-resource-record-sets \
      --hosted-zone-id "$COLVENNA_HOSTED_ZONE_ID" --output json)" || return 1
    others="$(for f in "$(dirname "$COLVENNA_STATE_DIR")"/*/entry-domain; do
      [[ ! -s "$f" || "$f" == "$COLVENNA_STATE_DIR/entry-domain" ]] || printf '%s %s\n' "$(basename "$(dirname "$f")")" "$(<"$f")"
    done)"
    python3 - "$records" "$others" "$COLVENNA_ENTRY_DOMAIN" "$COLVENNA_HOSTED_ZONE_NAME" "$COLVENNA_ENV" <<'PY'
import json
import sys

records, others, domain, zone, env = sys.argv[1:]
names = sorted({r["Name"].rstrip(".").lower() for r in json.loads(records)["ResourceRecordSets"]})
under = [n for n in names if n == domain or n.endswith("." + domain)]
if under:
    sys.exit(f"COLVENNA_ENTRY_DOMAIN {domain} is not free: hosted zone {zone} has {len(under)} records at or under it"
             f" ({', '.join(under[:3])}{', ...' if len(under) > 3 else ''}), which uninstalling {env} would delete")
labels = domain.split(".")
for a in (".".join(labels[i:]) for i in range(1, len(labels) - len(zone.split(".")))):
    entry = [n for n in (f"console.{a}", f"bi.{a}") if n in names]
    if entry:
        sys.exit(f"COLVENNA_ENTRY_DOMAIN {domain} is under {a}, another environment's entry ({entry[0]}): its uninstall"
                 " would delete this one's records")
for line in others.splitlines():
    other, theirs = line.split(" ", 1)
    if theirs == domain or theirs.endswith("." + domain) or domain.endswith("." + theirs):
        sys.exit(f"COLVENNA_ENTRY_DOMAIN {domain} overlaps environment {other}'s entry domain {theirs}"
                 f" (deploy/.state/{other}/entry-domain): each environment's is its own")
PY
  elif [[ ! -s "$COLVENNA_STATE_DIR/entry-domain" ]]; then
    # Only an entry stack that does not exist leaves nothing to compare with: one that cannot be read
    # (throttled, denied) stops, rather than let a domain through unchecked.
    outputs="$(stack_outputs "$ENTRY_STACK")" || return 1
    url="$([[ -z "$outputs" ]] || python3 -c 'import json, sys
print(next((o["OutputValue"] for o in json.loads(sys.argv[1]) or [] if o["OutputKey"] == "consoleUrl"), ""))' "$outputs")"
    if [[ -n "$url" && "$url" != "https://console.$COLVENNA_ENTRY_DOMAIN" ]]; then
      echo "Environment $COLVENNA_ENV's entry stack serves ${url#https://}, not console.$COLVENNA_ENTRY_DOMAIN:" \
        "give COLVENNA_ENTRY_DOMAIN=${url#https://console.} (the domain it was installed with)" >&2
      return 1
    fi
  fi
}

# pinned_doris <fe|be>: the Doris image digest lib/common.sh pins, which install-doris.sh runs.
pinned_doris() {
  sed -n "s/^DORIS_${1^^}_DIGEST=//p" "$(dirname "${BASH_SOURCE[0]}")/common.sh"
}

# doris_repository: the environment's repository of the query engine's images,
# colvenna-lake-<env>/query-engine (lib/common.sh's DORIS_REPOSITORY_NAME).
doris_repository() {
  env_name "$(sed -n 's/^DORIS_REPOSITORY_NAME=//p' "$(dirname "${BASH_SOURCE[0]}")/common.sh")"
}

# doris_source: the ECR repository a source install copies the Doris images from: DORIS_IMAGE_SOURCE,
# else, in the release registry COLVENNA_RELEASE_REGISTRY, the query engine's repository of the newest
# release manifest of the current schema and names in the checkout (release/<candidate>/manifest.json)
# that pins these digests (a manifest names no registry); nothing when neither.
doris_source() {
  local repository
  if [[ -n "${DORIS_IMAGE_SOURCE:-}" ]]; then
    printf '%s\n' "$DORIS_IMAGE_SOURCE"
  elif [[ -n "${COLVENNA_RELEASE_REGISTRY:-}" ]]; then
    repository="$(python3 "$(dirname "${BASH_SOURCE[0]}")/../release/manifest.py" doris-source \
      "$(dirname "${BASH_SOURCE[0]}")/../../../release" "$(pinned_doris fe)" "$(pinned_doris be)")" || return 1
    [[ -z "$repository" ]] || printf '%s/%s\n' "$COLVENNA_RELEASE_REGISTRY" "$repository"
  fi
}

# MANIFEST_TYPES: the manifest types an image (an index or an image, OCI or Docker) or a Helm chart
# pushed as an OCI artifact (an OCI image manifest) may have: the Doris images', Colvenna Catalog's
# image and chart.
MANIFEST_TYPES=(application/vnd.oci.image.index.v1+json application/vnd.oci.image.manifest.v1+json
  application/vnd.docker.distribution.manifest.list.v2+json application/vnd.docker.distribution.manifest.v2+json)

# doris_source_parts <source>: whether the source is an ECR repository in a standard region,
# <account>.dkr.ecr.<region>.amazonaws.com/<repository>, saying so when not; sets DORIS_SOURCE_ACCOUNT,
# DORIS_SOURCE_REGION and DORIS_SOURCE_REPOSITORY from it.
doris_source_parts() {
  # The groups first: standard_region's own [[ =~ ]] replaces BASH_REMATCH.
  DORIS_SOURCE_ACCOUNT="" DORIS_SOURCE_REGION="" DORIS_SOURCE_REPOSITORY=""
  if [[ "$1" =~ ^([0-9]{12})\.dkr\.ecr\.([a-z0-9-]+)\.amazonaws\.com/([a-z0-9._/-]+)$ ]]; then
    DORIS_SOURCE_ACCOUNT="${BASH_REMATCH[1]}" DORIS_SOURCE_REGION="${BASH_REMATCH[2]}"
    DORIS_SOURCE_REPOSITORY="${BASH_REMATCH[3]}"
  fi
  if ! standard_region "$DORIS_SOURCE_REGION"; then
    echo "DORIS_IMAGE_SOURCE must be an ECR repository in a standard region," \
      "<account>.dkr.ecr.<region>.amazonaws.com/<repository>: $1" >&2
    return 1
  fi
}

# among_digests <digest> <batch-get-image's digests, as --output text gives them>: whether the digest is
# one of them. Read by digest, ECR answers an image for each tag the digest has (two for a release's
# image, tagged with its candidate's name and its own, #13), so the digests are what is held, never
# how many images came back.
among_digests() {
  local digests=" ${2//[$'\t\n']/ } "
  [[ -n "$1" && "$digests" == *" $1 "* ]]
}

# doris_images_held: whether the environment's repository (doris_repository) holds both pinned
# Doris images already. A failed read counts as not, and so does a repository that does not exist
# yet: the source is checked then, as when they are not there.
doris_images_held() {
  local held
  held="$(aws ecr batch-get-image --repository-name "$(doris_repository)" \
    --image-ids "imageDigest=$(pinned_doris fe)" "imageDigest=$(pinned_doris be)" \
    --accepted-media-types "${MANIFEST_TYPES[@]}" --query 'images[].imageId.imageDigest' --output text \
    2>/dev/null || true)"
  among_digests "$(pinned_doris fe)" "$held" && among_digests "$(pinned_doris be)" "$held"
}

# assert_doris_source: a source install can put the Doris images into the environment's repository:
# they are there already, or the source (doris_source) is an ECR repository in a standard region from
# which the caller can read both digests, with pull rights alone (ecr:BatchGetImage). A
# DORIS_IMAGE_SOURCE given is checked first, even when nothing is to be copied: a malformed one is
# said as such, not as a failed login to a region it does not name.
assert_doris_source() {
  local fe be source answer
  fe="$(pinned_doris fe)" be="$(pinned_doris be)"
  if [[ -n "${DORIS_IMAGE_SOURCE:-}" ]]; then
    doris_source_parts "$DORIS_IMAGE_SOURCE" || return 1
  fi
  ! doris_images_held || return 0
  source="$(doris_source)" || return 1
  if [[ -z "$source" ]]; then
    echo "No source of the query engine's images (based on Apache Doris 4.1.4): give" \
      "COLVENNA_RELEASE_REGISTRY=<account>.dkr.ecr.<region>.amazonaws.com," \
      "the release registry, when a release manifest in release/ (of the current schema and" \
      "names) pins FE $fe and BE $be, or else" \
      "DORIS_IMAGE_SOURCE=<account>.dkr.ecr.<region>.amazonaws.com/<repository>, a repository holding them" >&2
    return 1
  fi
  doris_source_parts "$source" || return 1
  answer="$(aws_read "the query engine's images in $source" ecr batch-get-image \
    --region "$DORIS_SOURCE_REGION" --registry-id "$DORIS_SOURCE_ACCOUNT" \
    --repository-name "$DORIS_SOURCE_REPOSITORY" \
    --image-ids "imageDigest=$fe" "imageDigest=$be" --accepted-media-types "${MANIFEST_TYPES[@]}" \
    --output json)" || return 1
  python3 - "$answer" "$source" "$fe" "$be" <<'PY'
import json
import sys

answer, source, fe, be = sys.argv[1:]
held = {i["imageId"]["imageDigest"] for i in json.loads(answer).get("images", [])}
missing = [f"{name} {digest}" for name, digest in (("FE", fe), ("BE", be)) if digest not in held]
if missing:
    sys.exit(f"{source} does not hold the query engine's {' and '.join(missing)} (DORIS_IMAGE_SOURCE,"
             " or the release registry's): give a repository that does")
PY
}

# assert_catalog_source: a source install's catalog step can fetch the Colvenna Catalog release this
# tree pins (deploy/images/colvenna-catalog.json) as fetch-catalog.sh will: its source
# (COLVENNA_CATALOG_REGISTRY, else the pin's registry, GitHub Container Registry) gives the pinned
# image and chart by digest, an ECR one to the caller with pull rights alone (ecr:BatchGetImage), any
# other to an anonymous reader. A release install takes them from its candidate's copies instead,
# which check-release.sh checks.
assert_catalog_source() {
  local tool candidate parts registry account region kind ref digest answer
  tool="$(dirname "${BASH_SOURCE[0]}")/../release/manifest.py"
  candidate="$(python3 "$tool" catalog candidate)" || return 1
  parts="$(python3 "$tool" catalog-source "${COLVENNA_CATALOG_REGISTRY:-}")" || return 1
  IFS=$'\t' read -r registry account region <<<"$parts"
  if [[ -z "$region" ]]; then
    if ! answer="$(python3 "$tool" catalog-held "$registry" 2>&1)"; then
      echo "No Colvenna Catalog $candidate (deploy/images/colvenna-catalog.json) for an anonymous reader in" \
        "$registry: $answer" >&2
      if [[ -z "${COLVENNA_CATALOG_REGISTRY:-}" ]]; then
        echo "Until it is public there, give COLVENNA_CATALOG_REGISTRY=<registry>[/<prefix>], a registry" \
          "holding the same digests, e.g. the maintainers' release registry" \
          "<account>.dkr.ecr.<region>.amazonaws.com/colvenna-catalog-release" >&2
      fi
      return 1
    fi
    return 0
  fi
  for kind in image chart; do
    ref="$registry/$(python3 "$tool" catalog "$kind.repository")" digest="$(python3 "$tool" catalog "$kind.digest")"
    answer="$(aws_read "Colvenna Catalog $candidate's $kind in $registry" ecr batch-get-image \
      --region "$region" --registry-id "$account" --repository-name "${ref#*/}" \
      --image-ids "imageDigest=$digest" --accepted-media-types "${MANIFEST_TYPES[@]}" \
      --query 'images[].imageId.imageDigest' --output text)" || return 1
    if ! among_digests "$digest" "$answer"; then
      echo "COLVENNA_CATALOG_REGISTRY: ${registry%%/*} holds no ${ref#*/}@$digest, Colvenna Catalog $candidate's" \
        "$kind (deploy/images/colvenna-catalog.json): give a registry that does" >&2
      return 1
    fi
  done
}

# assert_release_registry: COLVENNA_RELEASE_REGISTRY, when given (an install from a release needs it; a
# source install may take its Doris and third-party images from it), is an ECR registry in a standard
# region, <account>.dkr.ecr.<region>.amazonaws.com: refused here, before anything is made, rather
# than at the step that first copies from it.
assert_release_registry() {
  [[ -n "${COLVENNA_RELEASE_REGISTRY:-}" ]] || return 0
  python3 "$(dirname "${BASH_SOURCE[0]}")/../release/manifest.py" registry "$COLVENNA_RELEASE_REGISTRY" >/dev/null
}
