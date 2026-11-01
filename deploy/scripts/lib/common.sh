#!/usr/bin/env bash
# What every deploy script shares: the environment (deploy/env.sh), AWS identity, cluster access,
# stack outputs, the components' names and images, and small helpers. tests/acceptance/common.sh
# adds the acceptance tests' own.
# shellcheck disable=SC2034  # settings used by the scripts that source this file
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DEPLOY_DIR="$REPO_DIR/deploy"
# shellcheck source-path=SCRIPTDIR source=../../env.sh
source "$DEPLOY_DIR/env.sh" ""  # not the caller's arguments
# AWS credentials are the caller's own (their AWS_PROFILE, or the default chain), of COLVENNA_ACCOUNT:
# every script asserts it first (env.sh's assert_account).
# kubectl, Helm and crane (deploy/scripts/foundation/install-tools.sh), and the caches the image
# builds download into.
TOOLS_DIR="$DEPLOY_DIR/.tools"
export PATH="$TOOLS_DIR:$PATH"
# The environment's local state, never committed: its kubeconfig, the image references its builds
# recorded, admin scratch files.
STATE_DIR="$DEPLOY_DIR/.state/$COLVENNA_ENV"
mkdir -p "$STATE_DIR"
export KUBECONFIG="$STATE_DIR/kubeconfig"
# helm_upgrade: every install script's `helm upgrade --install`, recovering a release an interrupted
# earlier run left pending (deploy/scripts/lib/helm.sh).
# shellcheck source-path=SCRIPTDIR source=helm.sh
source "$(dirname "${BASH_SOURCE[0]}")/helm.sh"
# cdk_deploy: every deploy script's `cdk deploy`, run again when the CDK CLI resolved no account from
# the credentials (deploy/scripts/lib/cdk.sh).
# shellcheck source-path=SCRIPTDIR source=cdk.sh
source "$(dirname "${BASH_SOURCE[0]}")/cdk.sh"
# The manifest gate's yaml dependency comes with the CDK package (deploy/aws).
export NODE_PATH="$DEPLOY_DIR/aws/node_modules"
ECR_REGISTRY="$COLVENNA_ACCOUNT.dkr.ecr.$COLVENNA_REGION.amazonaws.com"
# What every CDK synthesis of the environment's stacks is given (deploy/aws/src/app.ts): its name, and
# the account it is of, which the caller's credentials must be too.
CDK_ENV_CONTEXT=(-c "env=$COLVENNA_ENV" -c "account=$COLVENNA_ACCOUNT")

CATALOG_NAMESPACE=colvenna-catalog
# LIVE_PODS: kubectl's field selector for the pods not finished, neither Succeeded nor Failed. A
# finished pod is no replica: the kubelet's graceful node shutdown (a patch reboot) leaves its pods
# Failed ("Pod was terminated in response to imminent node shutdown") beside the ones their ReplicaSet
# starts in their place, and they stay until deleted (#15). A pod that is listed to be counted, waited
# for or exec'd into is a live one.
LIVE_PODS="--field-selector=status.phase!=Succeeded,status.phase!=Failed"
# Doris images run as root: their own namespace at Pod Security baseline, not restricted.
DORIS_NAMESPACE=colvenna-doris
# The development Kafka (tests/fixtures/kafka/dev-kafka.sh): its release, in the one namespace the
# Strimzi operator watches; check-health.sh --dev-kafka reports it.
DEV_KAFKA_RELEASE=colvenna-dev
DEV_KAFKA_NAMESPACE=colvenna-lake-kafka

# The third-party images (deploy/images/third-party.tsv: name, scope, upstream reference, what),
# each run from the environment's own copy, never from upstream, which may drop a digest: the
# third-party step (deploy/scripts/foundation/copy-third-party.sh, or release/install-images.sh)
# copies them by digest into THIRD_PARTY_REPOSITORY, each tagged <name>-<the digest's first 12 hex
# digits>. A test of a later commit that pins another digest needs copy-third-party.sh first.
THIRD_PARTY_LIST="$DEPLOY_DIR/images/third-party.tsv"
THIRD_PARTY_REPOSITORY="$ECR_REGISTRY/$(env_name colvenna-lake/third-party)"
declare -gA THIRD_PARTY_UPSTREAM=()
# Each row checked as manifest.py's third_party_images checks it: a malformed one stops whatever
# sources this file, before any image is named from it.
third_party_row=0
while IFS= read -r third_party_line || [[ -n "$third_party_line" ]]; do
  third_party_row=$((third_party_row + 1))
  [[ -n "${third_party_line// /}" && "$third_party_line" != \#* ]] || continue
  third_party_fields=()
  IFS=$'\t' read -r -a third_party_fields <<<"$third_party_line"
  if [[ ${#third_party_fields[@]} -ne 4 || "$third_party_line" == *$'\t\t'* || "$third_party_line" == $'\t'* \
    || "$third_party_line" == *$'\t' || ! "${third_party_fields[0]}" =~ ^[a-z][a-z0-9-]*[a-z0-9]$ \
    || ${#third_party_fields[0]} -gt 40 || ! "${third_party_fields[1]}" =~ ^(platform|fixtures)$ \
    || ! "${third_party_fields[2]}" =~ ^[a-z0-9.-]+\.[a-z]+/[a-z0-9._/-]+(:[A-Za-z0-9._-]+)?@sha256:[0-9a-f]{64}$ \
    || -n "${THIRD_PARTY_UPSTREAM[${third_party_fields[0]}]:-}" ]]; then
    echo "$THIRD_PARTY_LIST:$third_party_row: not a new name, its scope (platform or fixtures), its upstream" \
      "reference by digest and what runs it, tab-separated: $third_party_line" >&2
    exit 1
  fi
  THIRD_PARTY_UPSTREAM[${third_party_fields[0]}]="${third_party_fields[2]}"
done <"$THIRD_PARTY_LIST"
unset third_party_row third_party_line third_party_fields
# third_party_digest <name>, third_party_tag <name>: its pinned digest, and its tag in the
# environment's repository; a name the list lacks fails.
third_party_digest() {
  if [[ -z "${THIRD_PARTY_UPSTREAM[$1]:-}" ]]; then
    echo "No third-party image $1 in $THIRD_PARTY_LIST" >&2
    return 1
  fi
  printf '%s\n' "${THIRD_PARTY_UPSTREAM[$1]##*@}"
}
third_party_tag() {
  local digest
  digest="$(third_party_digest "$1")" || return 1
  printf '%s-%s\n' "$1" "${digest:7:12}"
}
# third_party_image <name>: the environment's copy, <repository>:<tag>@<digest>; pulled by digest.
third_party_image() {
  local digest tag
  digest="$(third_party_digest "$1")" && tag="$(third_party_tag "$1")" || return 1
  printf '%s:%s@%s\n' "$THIRD_PARTY_REPOSITORY" "$tag" "$digest"
}
POSTGRES_IMAGE="$(third_party_image postgres)"
CURL_IMAGE="$(third_party_image curl)"
MYSQL_CLIENT_IMAGE="$(third_party_image mysql)"

# Engine images, in the environment's own ECR: the Flink base image
# (deploy/scripts/foundation/build-flink-image.sh records it), and the query engine, FE and BE, by
# digest: a modified build based on Apache Doris 4.1.4 with its Paimon 2.0.0 reader
# (deploy/images/doris), in a repository named for it, not for Doris (release/trademarks.md);
# deploy/scripts/doris/copy-images.sh puts them into the environment's repository, each under the
# tag doris_tag gives it.
FLINK_IMAGE="$(cat "$STATE_DIR/flink-image-ref.txt" 2>/dev/null || true)"
# The query engine's repository, colvenna-lake-<env>/query-engine in every environment (lib/preflight.sh
# reads this line).
DORIS_REPOSITORY_NAME=colvenna-lake/query-engine
DORIS_REPOSITORY="$ECR_REGISTRY/$(env_name "$DORIS_REPOSITORY_NAME")"
# The pinned FE and BE, which install-doris.sh runs by digest (DORIS_FE_IMAGE, DORIS_BE_IMAGE); their
# tags there begin with DORIS_TAG (doris_tag; deploy/aws/src/foundation-stack.ts reads this line for
# the lifecycle rules that keep them).
DORIS_TAG=4.1.4-paimon-2.0.0
DORIS_FE_DIGEST=sha256:8ef385893d63adae41f980d626dbf3818320a2fe008841bcca19a1f9b0c6104b
DORIS_BE_DIGEST=sha256:d98355172b05a3430665033dc47bb1fbe80e0510a7de994d09fc867a0d746645
DORIS_FE_IMAGE="$DORIS_REPOSITORY@$DORIS_FE_DIGEST"
DORIS_BE_IMAGE="$DORIS_REPOSITORY@$DORIS_BE_DIGEST"
# doris_tag <fe|be> <digest>: the image's tag in the environment's repository,
# <DORIS_TAG>-<fe|be>-<the digest's first 12 hex digits>, as a third-party copy's (third_party_tag):
# the repository's tags cannot be rewritten, so each pinned digest has one of its own, and one pinned
# anew sits beside the one before it (#278). doris/copy-images.sh and release/install-images.sh both
# tag by it; the foundation stack's lifecycle rules keep every image so tagged.
doris_tag() {
  if [[ ! "${1:-}" =~ ^(fe|be)$ || ! "${2:-}" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    echo "doris_tag: not fe or be and a sha256 digest: $*" >&2
    return 1
  fi
  printf '%s-%s-%s\n' "$DORIS_TAG" "$1" "${2:7:12}"
}

# The Doris Operator's own pod (deploy/scripts/foundation/setup-operators.sh; check-health.sh checks
# them): requests, then limits, of CPU and memory.
DORIS_OPERATOR_RESOURCES=(100m 128Mi 500m 512Mi)

# write_node_group_size: the desired size of the write node group, from EKS (the nodes there are
# now may be short of it, a lost one not replaced yet); the colvenna-catalog chart keeps its replicas on
# that many nodes, two at most (eligibleNodes). Fails, saying why, without a registered write node.
write_node_group_size() {
  local group size
  group="$(kubectl get nodes -l colvenna-lake/role=write \
    -o jsonpath='{range .items[*]}{.metadata.labels.eks\.amazonaws\.com/nodegroup}{"\n"}{end}' | head -1)"
  if [[ -z "$group" ]]; then
    echo "No write node registered in the cluster: its node group's size cannot be read" >&2
    return 1
  fi
  size="$(aws eks describe-nodegroup --cluster-name "$(stack_output "$FOUNDATION_STACK" ClusterName)" \
    --nodegroup-name "$group" --query nodegroup.scalingConfig.desiredSize --output text)"
  [[ "$size" =~ ^[0-9]+$ ]] || { echo "No size for the write node group $group: $size" >&2; return 1; }
  echo "$size"
}

OPERATOR_NAMESPACE=colvenna-system
OPERATOR_RELEASE=colvenna-operator
# The default managed namespace, where declarations go unless the install names others.
JOB_NAMESPACE=colvenna-data
# The operator's managed namespaces, comma-separated (JOB_NAMESPACE unless set): the operator
# stack gives each its own job role, and install-operator.sh hands the operator all of them.
MANAGED_NAMESPACES="${MANAGED_NAMESPACES:-$JOB_NAMESPACE}"
OPERATOR_PRINCIPAL=colvenna-operator
# The jobs' foundation: Flink 1.20.4 on Java 11 with Paimon 2.0.0.
FOUNDATION_IMAGE="$FLINK_IMAGE"
# The monitoring stack (deploy/helm/colvenna-monitoring).
MONITORING_NAMESPACE=colvenna-monitoring
MONITORING_RELEASE=colvenna-monitoring
# Grafana's admin password (key admin-password), made once by install-monitoring.sh.
GRAFANA_ADMIN_SECRET=colvenna-grafana-admin
# The platform's Doris for dashboards (install-doris.sh), in its own namespace: its service account
# is the one the catalog stack's Doris role trusts. root's password is in DORIS_ADMIN_SECRET.
DORIS_CLUSTER=colvenna-doris
DORIS_SERVICE_ACCOUNT=doris
DORIS_ADMIN_SECRET=colvenna-doris-admin

# The entry's names (ENTRY_DOMAIN, HOSTED_ZONE_ID, HOSTED_ZONE_NAME) come from deploy/env.sh.
CONSOLE_NAMESPACE=colvenna-console
CONSOLE_RELEASE=colvenna-console
BI_NAMESPACE=colvenna-bi
BI_RELEASE=colvenna-bi
# The Secret the operator publishes dashboard users' Doris identities to (BiCredentials).
BI_DORIS_USERS_SECRET=colvenna-bi-doris-users
# The AWS Load Balancer Controller, only to bind pods to the stack's target groups.
LBC_NAMESPACE=kube-system
LBC_RELEASE=aws-load-balancer-controller
LBC_CHART_VERSION=3.6.0
LBC_CHART_SHA256=ee0403fcd713d6f1d28b1ff17d0d6d47e011cc4dd727a0aed0d95ae46897226a

# stack_output <stack> <key>
stack_output() {
  aws cloudformation describe-stacks --stack-name "$1" \
    --query "Stacks[0].Outputs[?OutputKey=='$2'].OutputValue | [0]" --output text
}

# entry_output <key>: an output of the entry stack.
entry_output() {
  stack_output "$ENTRY_STACK" "$1"
}

ensure_kubeconfig() {
  # Serialized: concurrent scripts would otherwise rewrite the same file at once.
  flock "$STATE_DIR/kubeconfig.lock" aws eks update-kubeconfig \
    --name "$(stack_output "$FOUNDATION_STACK" ClusterName)" --kubeconfig "$KUBECONFIG" >/dev/null
}

# assert_cluster: kubectl talks to the environment's cluster.
assert_cluster() {
  local expected actual
  expected="$(stack_output "$FOUNDATION_STACK" ClusterName)"
  actual="$(kubectl config view --minify -o jsonpath='{.clusters[0].name}')"
  if [[ "$actual" != *"$expected"* ]]; then
    echo "kubectl context is $actual, expected $expected" >&2
    exit 1
  fi
}

# ensure_storage_class: colvenna-gp3, encrypted gp3 EBS volumes for the platform's and the tests' claims.
ensure_storage_class() {
  cat <<YAML | kubectl apply -f - >/dev/null
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: colvenna-gp3
provisioner: ebs.csi.aws.com
parameters: {type: gp3, encrypted: "true"}
reclaimPolicy: Delete
volumeBindingMode: WaitForFirstConsumer
YAML
}

# committed_sha: refuses to build or deploy anything but a clean, committed tree; prints its SHA.
# shellcheck source-path=SCRIPTDIR source=git.sh
source "$DEPLOY_DIR/scripts/lib/git.sh"

# Runs a pod to completion in a namespace and prints its log; fails if the pod fails.
run_pod() {
  local namespace="$1" name="$2" manifest="$3"
  kubectl -n "$namespace" delete pod "$name" --ignore-not-found --wait=true >/dev/null
  kubectl -n "$namespace" apply -f "$manifest" >/dev/null
  if ! kubectl -n "$namespace" wait --for=jsonpath='{.status.phase}'=Succeeded \
    "pod/$name" --timeout=300s >/dev/null; then
    kubectl -n "$namespace" logs "$name" >&2 || true
    kubectl -n "$namespace" delete pod "$name" --ignore-not-found >/dev/null
    echo "Pod $name did not succeed" >&2
    return 1
  fi
  kubectl -n "$namespace" logs "$name"
  kubectl -n "$namespace" delete pod "$name" --ignore-not-found >/dev/null
}
# run_pod_out: as run_pod, for an output too large for a container's log (#244).
# shellcheck source-path=SCRIPTDIR source=pod-out.sh
source "$DEPLOY_DIR/scripts/lib/pod-out.sh"

# The commit whose images the lookups below read: the current one, or IMAGES_SHA, the commit an
# environment was installed at when tests of a later commit (tests only) run against it.
images_sha() {
  if [[ -n "${IMAGES_SHA:-}" ]]; then printf '%s\n' "$IMAGES_SHA"; else git -C "$REPO_DIR" rev-parse HEAD; fi
}

# image_ref <name>: the pinned reference deploy/scripts/operator/build-images.sh recorded for the
# commit (operator, foundation, or a colvenna-jobs image).
image_ref() {
  local file sha
  sha="$(images_sha)"
  file="$STATE_DIR/operator-images-$sha.json"
  if [[ ! -f "$file" ]]; then
    echo "No images built for commit $sha; run deploy/scripts/operator/build-images.sh" >&2
    return 1
  fi
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$file" "$1"
}

# tag_digest <repository> <tag> [<aws option>...]: the digest an ECR repository of the account holds
# under the tag (in the environment's region, or the --region given), or nothing when it holds none:
# fetch-catalog.sh, install-catalog.sh and release/make-manifest.sh check by it that a tag holds the
# digest they copy, install or release (a tag of these IMMUTABLE repositories holds one for good).
tag_digest() {
  aws ecr describe-images --repository-name "$1" --image-ids "imageTag=$2" "${@:3}" \
    --query 'imageDetails[0].imageDigest' --output text 2>/dev/null || true
}

# ensure_namespace <name> [<label>...]: a restricted namespace whose pods may reach colvenna-catalog.
# The installer owns it, not a chart: uninstalling a chart must not delete what is declared there.
ensure_namespace() {
  local name="$1"
  shift
  kubectl create namespace "$name" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl label namespace "$name" --overwrite pod-security.kubernetes.io/enforce=restricted \
    colvenna-lake.io/catalog-client=true "$@" >/dev/null
}

# ensure_rds_ca <namespace>: ConfigMap rds-ca (key ca.pem) with the AWS RDS CA bundle, so source
# connections verify the server certificate and host name.
ensure_rds_ca() {
  local file
  file="$(mktemp)"
  curl -fsSL https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem -o "$file"
  grep -q 'BEGIN CERTIFICATE' "$file"
  kubectl -n "$1" create configmap rds-ca --from-file=ca.pem="$file" --dry-run=client -o yaml \
    | kubectl apply -f - >/dev/null
  rm -f "$file"
}

# touch_doris_without_be <namespace> <cluster>: if the cluster's BE StatefulSet is not there yet,
# annotates the DorisCluster (colvenna-lake/reconcile=<time>), which makes the Doris Operator reconcile
# it again. Doris Operator 25.8.0 makes the BE only once the FE's Service lists a ready address; it
# watches no Endpoints, so when its last reconcile after the FE turns ready reads them a moment too
# early, nothing brings it back for up to its 10-hour resync (docs/design/colvenna-operator-lld.md, 看板服务;
# apache/doris-operator#495). The annotation stays on the cluster; it changes nothing else. A failed
# kubectl call is left to the caller's next pass. DORIS_TOUCHES counts the touches, and
# DORIS_BE_SEEN_AFTER the seconds from the first call to the first that found the StatefulSet:
# doris_touch_report prints them, so the evidence shows whether the race happened.
DORIS_TOUCHES=0 DORIS_BE_SEEN_AFTER="" DORIS_WAIT_BEGAN=""
touch_doris_without_be() {
  local namespace="$1" cluster="$2" found
  [[ -n "$DORIS_WAIT_BEGAN" ]] || DORIS_WAIT_BEGAN=$SECONDS
  found="$(kubectl -n "$namespace" get statefulset "$cluster-be" --ignore-not-found -o name 2>/dev/null)" \
    || return 0
  if [[ -n "$found" ]]; then
    [[ -n "$DORIS_BE_SEEN_AFTER" ]] || DORIS_BE_SEEN_AFTER=$((SECONDS - DORIS_WAIT_BEGAN))
    return 0
  fi
  kubectl -n "$namespace" annotate doriscluster "$cluster" --overwrite "colvenna-lake/reconcile=$(date +%s)" \
    >/dev/null 2>&1 || return 0
  DORIS_TOUCHES=$((DORIS_TOUCHES + 1))
  echo "No $cluster-be StatefulSet yet: touched DorisCluster $cluster for the Doris Operator to reconcile it"
}

# doris_touch_report <cluster>: one line, how often touch_doris_without_be touched the cluster before
# its BE StatefulSet was there, and when it was first seen.
doris_touch_report() {
  if [[ -z "$DORIS_WAIT_BEGAN" ]]; then
    echo "Doris $1 BE: ready at the first look, nothing to touch (apache/doris-operator#495)"
  elif [[ -n "$DORIS_BE_SEEN_AFTER" ]]; then
    echo "Doris $1 BE: $DORIS_TOUCHES touches before its StatefulSet was seen, ${DORIS_BE_SEEN_AFTER}s after the wait for it began (apache/doris-operator#495)"
  else
    echo "Doris $1 BE: $DORIS_TOUCHES touches, its StatefulSet never seen (apache/doris-operator#495)"
  fi
}

# doris_component_current <namespace> <cluster> <fe|be> <image>: whether the component runs <image>
# and is ready: its StatefulSet rolled out (the generation observed, one revision, every replica
# ready) and its first pod running <image>, ready. Right after a DorisCluster with another image is
# applied the old pods are still ready, until the Doris Operator updates the StatefulSet and replaces
# them; a SQL client that connects then meets an FE going down (#261). Not caught: a change of the
# spec that keeps the images (the old StatefulSet looks settled until the operator updates it).
doris_component_current() {
  local namespace="$1" cluster="$2" component="$3" image="$4" generation observed current update replicas ready
  local rollout='{.metadata.generation} {.status.observedGeneration} {.status.currentRevision}'
  rollout+=' {.status.updateRevision} {.spec.replicas} {.status.readyReplicas}'
  read -r generation observed current update replicas ready <<<"$(kubectl -n "$namespace" get statefulset \
    "$cluster-$component" --ignore-not-found -o jsonpath="$rollout" 2>/dev/null)"
  [[ -n "$generation" && "$generation" == "$observed" && -n "$current" && "$current" == "$update" \
    && "$replicas" == "${ready:-0}" ]] || return 1
  [[ "$(kubectl -n "$namespace" get pod "$cluster-$component-0" \
    -o jsonpath='{.spec.containers[0].image} {.status.containerStatuses[0].ready}' 2>/dev/null)" == "$image true" ]]
}

# doris_sql <sql-file> [<mysql-flags>]: runs a SQL file as Doris root from an admin client pod (the
# file travels as a Secret: it may set passwords); rows tab-separated on stdout, copied from the pod
# (run_pod_out: a view's rows can be more than a container's log keeps).
doris_sql() {
  local file="$1" flags="${2:---skip-column-names}" pod="doris-admin-$RANDOM" dir
  dir="$(mktemp -d)"
  chmod 700 "$dir"
  kubectl -n "$DORIS_NAMESPACE" create secret generic "$pod" --from-file=run.sql="$file" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  cat >"$dir/pod.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata:
  name: $pod
  namespace: $DORIS_NAMESPACE
  labels: {colvenna-lake.io/doris-admin: "true"}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 999, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: mysql
      image: $MYSQL_CLIENT_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["sh", "-ec"]
      args:
        - |
          export MYSQL_PWD="\$(cat /admin/password)"
          # The pod succeeds either way, so a failing statement's error comes back as its output.
          { mysql -h $DORIS_CLUSTER-fe-service -P 9030 -uroot --batch $flags < /sql/run.sql 2>&1 \
            && echo DORIS-SQL-OK || echo DORIS-SQL-FAILED; } >/out/out
          $POD_OUT_DONE
      volumeMounts:
        - {name: admin, mountPath: /admin, readOnly: true}
        - {name: sql, mountPath: /sql, readOnly: true}
        - {name: out, mountPath: /out}
  volumes:
    - {name: admin, secret: {secretName: $DORIS_ADMIN_SECRET}}
    - {name: sql, secret: {secretName: $pod}}
    - {name: out, emptyDir: {}}
YAML
  local status=0
  run_pod_out "$DORIS_NAMESPACE" "$pod" "$dir/pod.yaml" >"$dir/out" || status=$?
  kubectl -n "$DORIS_NAMESPACE" delete secret "$pod" --ignore-not-found >/dev/null
  grep -v '^DORIS-SQL-\(OK\|FAILED\)$' "$dir/out" >"$dir/rows" || true
  cat "$dir/rows"
  if [[ "$status" -eq 0 ]] && ! grep -qx DORIS-SQL-OK "$dir/out"; then
    status=1
    # The rows go to stdout, which callers often discard: the error goes to stderr too (#261), any
    # password a failing statement echoes masked.
    { echo "Doris SQL $file failed:"; sed -E "s/PASSWORD\('[^']*'\)/PASSWORD('<masked>')/g" "$dir/rows"; } >&2
  fi
  rm -rf "$dir"
  return "$status"
}

# record_image <name> <reference>: the pinned reference of an entry image (console, bi) built from
# the current SHA, kept with the other in $STATE_DIR/entry-images-<sha>.json.
record_image() {
  local file
  file="$STATE_DIR/entry-images-$(git -C "$REPO_DIR" rev-parse HEAD).json"
  python3 - "$file" "$1" "$2" <<'PY'
import json, os, sys
path, name, ref = sys.argv[1:]
images = json.load(open(path)) if os.path.exists(path) else {}
images[name] = ref
json.dump(images, open(path, "w"), indent=2)
PY
}

# entry_image <name>: the pinned reference build-console.sh or build-bi.sh recorded for the current
# SHA.
entry_image() {
  local file
  file="$STATE_DIR/entry-images-$(images_sha).json"
  if [[ ! -f "$file" ]]; then
    echo "No images built for commit $(images_sha); run deploy/scripts/entry/build-console.sh and build-bi.sh" >&2
    return 1
  fi
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$file" "$1"
}
