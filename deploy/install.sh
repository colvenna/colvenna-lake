#!/usr/bin/env bash
# Installs a complete Colvenna Lake environment into an AWS account (COLVENNA_ACCOUNT), in the
# environment's standard AWS region, from a clean, committed tree, in order:
#   the foundation (VPC, EKS, the project RDS, the warehouse bucket), the third-party images it runs
#   (copied by digest into its own ECR, deploy/images/third-party.tsv), the Flink and Doris operators,
#   Colvenna Catalog (a product of its own: the release deploy/images/colvenna-catalog.json pins, its
#   image and chart by digest), colvenna-operator and its job images, Doris, monitoring, the open layer
#   (Marquez, Polaris) and the web entry (Cognito, the load balancer, the console, BI), then checks it.
# With --fixtures it also installs what the acceptance flows use beside the platform: the test
# sources (RDS MySQL and PostgreSQL with their CDC logins), the Strimzi operator the development
# Kafka runs on, and the Spark reader image. The flows install the development Kafka and MongoDB
# themselves.
#
# usage: COLVENNA_ENV=<env> [COLVENNA_REGION=<region> COLVENNA_ACCOUNT=<account>
#        COLVENNA_HOSTED_ZONE_ID=<id> COLVENNA_HOSTED_ZONE_NAME=<zone> [COLVENNA_ENTRY_DOMAIN=<domain>]]
#        deploy/install.sh [--fixtures] [--release <name>] [--list] [--from <step>] [--only <step>]
#   COLVENNA_ENV the environment (deploy/env.sh): its stacks, resources, entry domain and local
#               state are its own
#   the settings, needed the first time and recorded once checked (deploy/env.sh):
#   COLVENNA_REGION its standard AWS region, e.g. us-west-2
#   COLVENNA_ACCOUNT the AWS account; the caller's credentials (their AWS_PROFILE, or the default
#               chain) must be of it
#   COLVENNA_HOSTED_ZONE_ID, COLVENNA_HOSTED_ZONE_NAME a public Route 53 hosted zone of that
#               account, for the web entry's names
#   COLVENNA_ENTRY_DOMAIN the entry's domain within the zone, <app>.<domain> (default
#               colvenna-<env>.<zone>)
#   --release   installs release candidate <name> (v1.0.0-rc.<n>), or the release promoted from one
#               (v1.0.0, release/README.md), from a checkout of its tag: the images and the colvenna
#               CLI its manifest (release/<name>/manifest.json; a release's is its candidate's under
#               its own tags, the same digests) pins, copied by digest from the release registry
#               COLVENNA_RELEASE_REGISTRY=<account>.dkr.ecr.<region>.amazonaws.com (required with it;
#               the manifest names no registry); nothing is built. Without it, every image is built
#               here from this commit (the development path), but Colvenna Catalog's, its release's,
#               which is copied by digest from where the catalog publishes it, GitHub Container
#               Registry, anonymously (or from COLVENNA_CATALOG_REGISTRY=<registry>[/<prefix>] when
#               given, a registry holding the same digests: an ECR one is read with the AWS
#               credentials), and the third-party ones, copied from COLVENNA_RELEASE_REGISTRY when
#               given and holding them, else from upstream.
#   --list      prints the steps and exits; it needs COLVENNA_ENV alone, none of the settings
#   --from      starts at that step, the ones before it done (at the same commit)
#   --only      runs that one step
# Before it changes anything, it prints the account, region, cluster and version it installs; with
# --release it first checks the manifest, the tree and the release registry
# (deploy/scripts/release/check-release.sh), and stops if any is wrong. An environment is installed
# at one commit one way, from source or as a release, as deploy/.state/<env>/install-mode records;
# a run the other way at that commit is refused.
# Every step can run again: each script converges what it installs. One log per step and
# steps.tsv (step, outcome, seconds) go to INSTALL_EVIDENCE, by default
# deploy/.state/<env>/install-<UTC time>/.
set -euo pipefail
DEPLOY="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$DEPLOY/.." && pwd)"

fixtures=false from="" only="" list=false release=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --fixtures) fixtures=true ;;
    --release) release="${2:?--release <candidate or release>}"; shift ;;
    --list) list=true ;;
    --from) from="${2:?--from <step>}"; shift ;;
    --only) only="${2:?--only <step>}"; shift ;;
    *) sed -n '/^# usage:/,/^#   --only/p' "$0" >&2; exit 1 ;;
  esac
  shift
done
# --list prints the steps, which name the environment's stacks: the name is enough, the settings are
# neither read nor required (deploy/env.sh's names-only argument, never an environment variable a
# caller could have exported).
names_only=""
[[ "$list" == true ]] && names_only="names-only"
# shellcheck source-path=SCRIPTDIR source=env.sh
source "$DEPLOY/env.sh" "$names_only"
# shellcheck source-path=SCRIPTDIR source=scripts/lib/preflight.sh
source "$DEPLOY/scripts/lib/preflight.sh"
export PATH="$DEPLOY/.tools:$PATH"
# Two write and two query nodes: what the platform, its jobs and the acceptance flows need.
export WRITE_NODES="${WRITE_NODES:-2}" QUERY_NODES="${QUERY_NODES:-2}"

# <step>|<fixture: only with --fixtures>|<what it installs>
STEPS=(
  "tools|no|kubectl, Helm and crane, pinned by checksum (deploy/.tools)"
  "foundation|no|$FOUNDATION_STACK: VPC, EKS ($WRITE_NODES write and $QUERY_NODES query nodes), RDS, warehouse bucket"
  "third-party|no|the third-party images the platform (with --fixtures, the fixtures too) runs, by digest, in the environment's ECR"
  "engines|no|Flink Kubernetes Operator 1.16.1 and Doris Operator 25.8.0"
  "flink-image|no|the Flink 1.20.4 + Paimon 2.0.0 base image, in the environment's ECR"
  "sources|yes|$TEST_MYSQL_STACK and $TEST_POSTGRES_STACK: the test sources"
  "catalog-stack|no|$CATALOG_STACK: colvenna-catalog's image repository, roles and database secret"
  "catalog|no|Colvenna Catalog $(python3 "$DEPLOY/scripts/release/manifest.py" catalog candidate): its release's image and chart by digest, its database, private CA and Helm release"
  "strimzi|yes|the Strimzi operator the development Kafka runs on"
  "operator-stack|no|$OPERATOR_STACK: the operator's and jobs' image repositories, the jobs' roles"
  "operator-image|no|colvenna-operator and the five colvenna-jobs images"
  "doris-image|no|the query engine's FE and BE (based on Apache Doris 4.1.4, with the Paimon 2.0.0 reader), by digest, in the environment's ECR"
  "doris|no|the platform's Doris (1 FE, 1 BE) for dashboards"
  "operator|no|colvenna-operator: CRDs, its catalog identity, the managed namespace"
  "monitoring|no|Prometheus with the alert rules, and Grafana"
  "source-logins|yes|the test sources' CDC logins and the flows' source Secrets"
  "open-stack|no|$OPEN_LAYER_STACK: Polaris's and the Iceberg readers' roles"
  "marquez|no|Marquez, the lineage catalog"
  "polaris|no|Polaris, the open layer's Iceberg catalog"
  "readers|yes|the Spark Iceberg reader image"
  "entry-stack|no|$ENTRY_STACK: Cognito, certificate, load balancer, names, pod roles"
  "lbc|no|the AWS Load Balancer Controller, bound to the stack's target groups"
  "console|no|colvenna-console behind the entry"
  "bi|no|the BI interface (Superset) behind the entry"
  "operator-again|no|colvenna-operator again, now pointed also at Marquez, Polaris and BI"
  "cli|no|the colvenna CLI binaries (colvenna-cli/dist)"
  "verify|no|colvenna-catalog's health, the operator, and the entry refusing the unsigned"
)
if $list; then
  for s in "${STEPS[@]}"; do
    IFS='|' read -r name fixture what <<<"$s"
    printf '%-15s %s%s\n' "$name" "$what" "$([[ "$fixture" == yes ]] && echo ' (--fixtures)')"
  done
  exit 0
fi
names=()
for s in "${STEPS[@]}"; do names+=("${s%%|*}"); done
for n in "$from" "$only"; do
  if [[ -n "$n" && " ${names[*]} " != *" $n "* ]]; then
    echo "No step $n; COLVENNA_ENV=$COLVENNA_ENV deploy/install.sh --list" >&2
    exit 1
  fi
done

if [[ -n "$(git -C "$REPO" status --porcelain)" ]]; then
  echo "Commit or remove local changes first; an install is traceable to its commit" >&2
  exit 1
fi
sha="$(git -C "$REPO" rev-parse HEAD)"
# The settings against the account, before anything is made or recorded: the caller's credentials
# are of COLVENNA_ACCOUNT, and the entry's hosted zone is a public zone of it, of that name.
assert_account
assert_hosted_zone
assert_release_registry || exit 1
version="source $sha: every image built here, but Colvenna Catalog's, its pinned release" mode=source
# The arguments that say what is installed, for a retry to give again.
again=()
$fixtures && again+=(--fixtures)
if [[ -n "$release" ]]; then
  check=("$release")
  $fixtures && check+=(--fixtures)
  bash "$DEPLOY/scripts/release/check-release.sh" "${check[@]}"
  again+=(--release "$release")
  built_from="$(python3 "$DEPLOY/scripts/release/manifest.py" get "$REPO/release/$release/manifest.json" \
    source.built_from)"
  version="release $release: images built from $built_from, copied by digest from $COLVENNA_RELEASE_REGISTRY"
  version+=" (release/$release/manifest.json)"
  mode="release $release"
fi
# One way per commit: the images an environment holds for a commit are either built from it or a
# release's, and the steps of one install read them by that commit; a run in the other way would mix
# them (an image tag the other way made is refused, but only once the run is under way).
mode_file="$DEPLOY/.state/$COLVENNA_ENV/install-mode"
if [[ -s "$mode_file" ]]; then
  recorded="$(<"$mode_file")"
  if [[ "${recorded%% *}" == "$sha" && "${recorded#* }" != "$mode" ]]; then
    if [[ "${recorded#* }" == source ]]; then
      way="without --release"
    else
      way="with --${recorded#* }"
    fi
    echo "Environment $COLVENNA_ENV was installed at $sha as ${recorded#* }, not $mode. Run install.sh" \
      "$way again at this commit, or check out another (a release's tag); for a new start, uninstall" \
      "it (uninstall.sh removes $mode_file with the rest)" >&2
    exit 1
  fi
fi
# An environment's name is the account's, not the region's: IAM roles and the entry's DNS names are
# global. A new environment (no foundation stack in its region) whose name another one holds stops
# here, before anything is made or its settings recorded: roles named colvenna-lake-<env>-..., or
# generated by CloudFormation from one of its stacks, <stack>-.... Only a foundation stack that does
# not exist is a new environment (lib/preflight.sh's foundation_outputs): any other failure stops.
new=false
foundation="$(foundation_outputs)"
if [[ -z "$foundation" ]]; then
  new=true
  of_stacks="$(for s in "$FOUNDATION_STACK" "$CATALOG_STACK" "$OPERATOR_STACK" "$OPEN_LAYER_STACK" \
    "$ENTRY_STACK" "$TEST_MYSQL_STACK" "$TEST_POSTGRES_STACK"; do
    printf " || starts_with(RoleName, '%s-')" "$s"
  done)"
  roles="$(aws iam list-roles --output text \
    --query "Roles[?starts_with(RoleName, 'colvenna-lake-$COLVENNA_ENV-')$of_stacks].RoleName")"
  mapfile -t taken < <(tr '\t' '\n' <<<"$roles" | grep -v '^$')
  if ((${#taken[@]} > 0)); then
    echo "Environment $COLVENNA_ENV is not in $COLVENNA_REGION, yet ${#taken[@]} IAM roles of its name exist" \
      "(${taken[0]}, ...): another region holds it, or an uninstall did not finish; choose another" \
      "name, or finish that uninstall in its region" >&2
    exit 1
  fi
fi
# will_run <step>: whether this run runs that step (--from, --only).
will_run() {
  local s started=false
  for s in "${STEPS[@]}"; do
    [[ -z "$from" || "${s%%|*}" == "$from" ]] && started=true
    if [[ "${s%%|*}" == "$1" ]]; then
      $started && [[ -z "$only" || "$only" == "$1" ]]
      return
    fi
  done
  return 1
}
# The environment can be installed as asked (deploy/scripts/lib/preflight.sh), still before anything
# is made: a run that deploys a stack finds the region CDK-bootstrapped, EKS offers the foundation's
# Kubernetes version, two availability zones offer both node types (a new environment's first two,
# an existing one's own), a new environment has room for its VPC and Elastic IPs under the region's
# quotas, the entry domain is the environment's own, and a source install that copies the Doris
# images has them to copy.
for s in foundation sources catalog-stack operator-stack open-stack entry-stack; do
  if will_run "$s"; then
    assert_cdk_bootstrap
    break
  fi
done
assert_kubernetes_version
zones="$(choose_zones)"
if $new; then
  assert_room
fi
assert_entry_domain "$new"
if [[ -z "$release" ]] && will_run doris-image; then
  assert_doris_source
fi
if [[ -z "$release" ]] && will_run catalog; then
  assert_catalog_source
fi
# What is about to be installed where, before anything is.
cluster="$(aws cloudformation describe-stacks --stack-name "$FOUNDATION_STACK" \
  --query "Stacks[0].Outputs[?OutputKey=='ClusterName'].OutputValue | [0]" --output text 2>/dev/null \
  || echo "none yet: $FOUNDATION_STACK makes it")"
target="$(printf '%-12s %s\n' account "$COLVENNA_ACCOUNT" \
  identity "$(aws sts get-caller-identity --query Arn --output text)" region "$COLVENNA_REGION ($zones)" \
  environment "$COLVENNA_ENV$($fixtures && echo ' (with --fixtures)')" cluster "$cluster" \
  entry "$ENTRY_DOMAIN (hosted zone $HOSTED_ZONE_NAME, $HOSTED_ZONE_ID)" version "$version")"
echo "$target"
record_settings
printf '%s %s\n' "$sha" "$mode" >"$mode_file"
ev="${INSTALL_EVIDENCE:-$DEPLOY/.state/$COLVENNA_ENV/install-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$ev"
echo "$target" >>"$ev/install.log"
echo "environment $COLVENNA_ENV in $COLVENNA_REGION at $sha by $(aws sts get-caller-identity --query Arn --output text)" \
  | tee -a "$ev/install.log"

scripts="$DEPLOY/scripts" fixture_scripts="$REPO/tests/fixtures"
# The third-party step copies the fixtures' images too with --fixtures (the acceptance tests run them).
third_party_scope=()
$fixtures && third_party_scope=(--fixtures)
run_step() {
  # A release copies what the manifest pins in place of every build.
  if [[ -n "$release" ]]; then
    local images=(bash "$scripts/release/install-images.sh" "$release")
    case "$1" in
      third-party) "${images[@]}" third-party "${third_party_scope[@]}"; return ;;
      flink-image) "${images[@]}" flink; return ;;
      catalog) "${images[@]}" catalog && bash "$scripts/catalog/install-catalog.sh" "$ev/catalog"; return ;;
      operator-image) "${images[@]}" operator; return ;;
      doris-image) "${images[@]}" doris; return ;;
      readers) "${images[@]}" readers; return ;;
      console) "${images[@]}" console && bash "$scripts/entry/install-console.sh"; return ;;
      bi) "${images[@]}" bi && bash "$scripts/entry/install-bi.sh" "$ev/bi"; return ;;
      cli) "${images[@]}" cli; return ;;
    esac
  fi
  case "$1" in
    tools) bash "$scripts/foundation/install-tools.sh" ;;
    foundation) bash "$scripts/foundation/deploy.sh" ;;
    third-party) bash "$scripts/foundation/copy-third-party.sh" "${third_party_scope[@]}" ;;
    engines) bash "$scripts/foundation/setup-operators.sh" ;;
    flink-image) bash "$scripts/foundation/build-flink-image.sh" ;;
    sources) bash "$fixture_scripts/sources/deploy-sources.sh" ;;
    catalog-stack) bash "$scripts/catalog/deploy-stack.sh" ;;
    catalog) bash "$scripts/catalog/fetch-catalog.sh" && bash "$scripts/catalog/install-catalog.sh" "$ev/catalog" ;;
    strimzi) bash "$fixture_scripts/kafka/install-strimzi.sh" ;;
    operator-stack) bash "$scripts/operator/deploy-stack.sh" ;;
    operator-image) bash "$scripts/operator/build-images.sh" ;;
    doris-image) bash "$scripts/doris/copy-images.sh" ;;
    doris) bash "$scripts/doris/install-doris.sh" ;;
    operator | operator-again) bash "$scripts/operator/install-operator.sh" ;;
    monitoring) bash "$scripts/monitoring/install-monitoring.sh" ;;
    source-logins) bash "$fixture_scripts/sources/sources.sh" prepare ;;
    open-stack) bash "$scripts/open-layer/deploy-stack.sh" ;;
    marquez) bash "$scripts/open-layer/install-marquez.sh" "$ev/marquez" ;;
    polaris) bash "$scripts/open-layer/install-polaris.sh" "$ev/polaris" ;;
    readers) bash "$fixture_scripts/readers/build-readers.sh" ;;
    entry-stack) bash "$scripts/entry/deploy-stack.sh" ;;
    lbc) bash "$scripts/entry/install-lbc.sh" ;;
    console) bash "$scripts/entry/build-console.sh" && bash "$scripts/entry/install-console.sh" ;;
    bi) bash "$scripts/entry/build-bi.sh" && bash "$scripts/entry/install-bi.sh" "$ev/bi" ;;
    cli) bash "$REPO/colvenna-cli/check.sh" build ;;
    verify) verify ;;
  esac
}

# The installed environment answers: colvenna-catalog healthy and refusing every caller without valid
# credentials, the operator running, and every entry host sending a caller without a session to
# Cognito's sign-in (never the application).
verify() {
  bash "$scripts/catalog/check-health.sh" "$ev/health"
  bash "$scripts/catalog/verify-catalog.sh" "$ev/catalog-access"
  KUBECONFIG="$DEPLOY/.state/$COLVENNA_ENV/kubeconfig" kubectl -n colvenna-system rollout status \
    deployment/colvenna-operator --timeout=5m
  local sign_in host code location
  # This environment's own Cognito domain, not just any.
  sign_in="$(aws cloudformation describe-stacks --stack-name "$ENTRY_STACK" \
    --query "Stacks[0].Outputs[?OutputKey=='SignInDomain'].OutputValue | [0]" --output text)"
  for host in console bi; do
    read -r code location < <(curl -s --max-time 20 -o /dev/null \
      -w '%{http_code} %{redirect_url}\n' "https://$host.$ENTRY_DOMAIN/")
    if [[ "$code" != 302 || "$location" != "$sign_in/oauth2/authorize?"* ]]; then
      echo "https://$host.$ENTRY_DOMAIN/ answered $code $location to a caller without a session" >&2
      return 1
    fi
    echo "https://$host.$ENTRY_DOMAIN/: 302 to $sign_in sign-in"
  done
}

started=false
for s in "${STEPS[@]}"; do
  IFS='|' read -r name fixture what <<<"$s"
  [[ -z "$from" || "$name" == "$from" ]] && started=true
  $started || continue
  [[ -n "$only" && "$name" != "$only" ]] && continue
  if [[ "$fixture" == yes ]] && ! $fixtures; then
    printf '%s\tskipped (no --fixtures)\t0\n' "$name" | tee -a "$ev/steps.tsv"
    continue
  fi
  echo "$(date -u +%FT%TZ) $name: $what" | tee -a "$ev/install.log"
  begin=$SECONDS
  # In a subshell of its own with errexit on: called from an if, a function would ignore it.
  set +e
  (set -e; run_step "$name") >"$ev/$name.log" 2>&1
  status=$?
  set -e
  if [[ "$status" == 0 ]]; then
    printf '%s\tok\t%s\n' "$name" "$((SECONDS - begin))" | tee -a "$ev/steps.tsv"
  else
    printf '%s\tFAILED\t%s\n' "$name" "$((SECONDS - begin))" | tee -a "$ev/steps.tsv"
    tail -20 "$ev/$name.log" >&2
    echo "Step $name failed; its log: $ev/$name.log. Fix it, then:" \
      "COLVENNA_ENV=$COLVENNA_ENV ${COLVENNA_RELEASE_REGISTRY:+COLVENNA_RELEASE_REGISTRY=$COLVENNA_RELEASE_REGISTRY }${COLVENNA_CATALOG_REGISTRY:+COLVENNA_CATALOG_REGISTRY=$COLVENNA_CATALOG_REGISTRY }deploy/install.sh" \
      "${again[*]} --from $name" >&2
    exit 1
  fi
done
echo "Installed $COLVENNA_ENV at $sha (${version%%:*}); evidence: $ev" | tee -a "$ev/install.log"
