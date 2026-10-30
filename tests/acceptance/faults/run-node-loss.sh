#!/usr/bin/env bash
# Acceptance on EKS: a node of the environment lost while the platform runs, and what comes back
# (UAT-NODE-01, UAT-NODE-02). Writes PASS/FAIL lines to <evidence>/verdicts.tsv and fails if any
# check fails.
#
#   job    the node running a CDC job's TaskManager (LakeSource nl-<run> on the test MySQL, a writer
#          changing rows from another node all along), whatever its role: Flink's pods are not
#          pinned to write nodes. Its JobManager goes too when it runs there; when it runs
#          elsewhere it stays, and restarts the job's tasks. The node group replaces the node; the
#          job runs again, restored from a checkpoint; once the writes stop, every source row is in
#          ODS as it is at the source (none lost, none left over); colvenna-catalog (its replicas on
#          two nodes again) and every platform workload are back.
#   query  the query node running the platform Doris's FE. The node group replaces the node in its
#          zone, where Doris's volumes are; Doris comes back, a dashboard's external query and its
#          copy answer with rows written after the loss, colvenna-catalog, BI and the console answer
#          (their load balancer targets healthy), and every platform workload is back.
#
# The loss: NODE_LOSS=abrupt (the default) terminates the instance through EC2, as a failed host
# goes, with nothing drained; NODE_LOSS=drain terminates it through its Auto Scaling group, whose
# termination hook makes EKS drain it first. Either way the group's desired capacity stays and it
# launches a replacement. The new instance may come up tagged Patch Group=DEV by the account's
# automation (docs/manual/install.md §9): timeline.tsv records its tags.
#
# The whole environment loses a node: run it alone (run-uat.sh's nodes phase).
# usage: run-node-loss.sh <run-id> <evidence-dir> job|query
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
assert_account
ensure_kubeconfig
assert_cluster
usage="usage: run-node-loss.sh <run-id> <evidence-dir> job|query"
run="${1:?$usage}"
evidence="${2:?$usage}"
kind="${3:?$usage}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
[[ "$kind" == job || "$kind" == query ]] || { echo "$usage" >&2; exit 1; }
loss="${NODE_LOSS:-abrupt}"
[[ "$loss" == abrupt || "$loss" == drain ]] || { echo "NODE_LOSS: abrupt or drain" >&2; exit 1; }
sha="$(committed_sha)"
cdc="$ACCEPTANCE_DIR/cdc"
mkdir -p "$evidence"/{status,dumps,sql,doris}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run ($kind node, $loss) at $sha by $(aws sts get-caller-identity --query Arn --output text)"
echo "operator $(image_ref operator)" | tee -a "$evidence/images.txt"
echo "mysql $(image_ref mysql)" | tee -a "$evidence/images.txt"
failed=0

step() { echo "$(date -u +%FT%TZ)	$*" | tee -a "$evidence/timeline.tsv"; }
verdict() {
  local detail="${3:-}"
  detail="${detail//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${detail//$'\t'/ }" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS ]] || failed=1
}

db="node_$run" ods="ods_node_$run" source_name="nl-$run" dashboard="nq-$run" writer="nw-$run"
bi="colvenna-bi-client" fe="$DORIS_CLUSTER-fe-service.$DORIS_NAMESPACE.svc"
cluster="$(stack_output "$FOUNDATION_STACK" ClusterName)"
small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
work="$(mktemp -d)"
chmod 700 "$work"
writing=false made=false cleaned=false
# cleanup: on any exit, the writer stopped, and on an early one what the run made deleted too: the
# dashboard, the source (its tables with it, deletion policy Delete) and the MySQL database.
# shellcheck disable=SC2317  # invoked through the EXIT trap
cleanup() {
  if $writing; then bash "$cdc/writer.sh" stop "$writer" >/dev/null 2>&1 || true; fi
  if $made && ! $cleaned; then
    kubectl -n "$JOB_NAMESPACE" delete lakedashboard "$dashboard" --ignore-not-found --timeout=10m >/dev/null 2>&1 || true
    kubectl -n "$JOB_NAMESPACE" delete secret "$dashboard-analyst" --ignore-not-found >/dev/null 2>&1 || true
    kubectl -n "$JOB_NAMESPACE" delete lakesource "$source_name" --ignore-not-found --timeout=10m >/dev/null 2>&1 || true
    if python3 "$cdc/mysql_contract.py" reset "$db" >"$work/reset.sql"; then
      bash "$FIXTURES_DIR/sources/sources.sh" mysql "$work/reset.sql" >/dev/null 2>&1 || true
    fi
  fi
  rm -rf "$work"
}
trap cleanup EXIT

# capture <file>: the LakeSource and its deployments, for status.py.
capture() {
  {
    printf '{"source":'
    kubectl -n "$JOB_NAMESPACE" get lakesource "$source_name" -o json
    printf ',"deployments":'
    kubectl -n "$JOB_NAMESPACE" get flinkdeployment -l "colvenna-lake.io/source=$source_name" -o json \
      | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["items"]))'
    printf '}'
  } >"$1"
}
# restored_of <state-file>: what the source's job last restored from, by that state, or None.
restored_of() {
  python3 -c 'import json,sys; s=json.load(open(sys.argv[1]))["source"].get("status") or {}; print(next((j.get("restoredFrom") for j in s.get("jobs", []) if j.get("deployment") == sys.argv[2]), None))' \
    "$1" "$deployment"
}
# holds <label> <expression>: whether status.py says it holds now (the state kept as <label>.json).
holds() {
  capture "$evidence/status/$1.json" 2>/dev/null \
    && python3 "$ACCEPTANCE_DIR/status.py" check "$evidence/status/$1.json" "$2"
}
# await <label> <timeout-seconds> <expression>: polls until it holds; a verdict either way.
await() {
  local end=$((SECONDS + $2)) started=$SECONDS
  while ((SECONDS < end)); do
    if holds "$1" "$3"; then
      verdict "$1" PASS "$((SECONDS - started))s"
      return 0
    fi
    sleep 10
  done
  verdict "$1" FAIL "not within $2s: $3"
  return 1
}

# role_nodes <role>: the Ready nodes of that role, one per line.
role_nodes() {
  kubectl get nodes -l "colvenna-lake/role=$1" -o json | python3 -c '
import json, sys
for n in json.load(sys.stdin)["items"]:
    ready = any(c["type"] == "Ready" and c["status"] == "True" for c in n["status"].get("conditions", []))
    if ready and not n["spec"].get("unschedulable"):
        print(n["metadata"]["name"])'
}
# node_of <namespace> <pod>: the node a pod runs on.
node_of() { kubectl -n "$1" get pod "$2" -o jsonpath='{.spec.nodeName}'; }
# instance_of <node>: its EC2 instance id; zone_of <node>: its zone.
instance_of() { kubectl get node "$1" -o jsonpath='{.spec.providerID}' | sed 's|.*/||'; }
zone_of() { kubectl get node "$1" -o jsonpath='{.metadata.labels.topology\.kubernetes\.io/zone}'; }

# workloads_back: every Deployment and StatefulSet of the platform's namespaces has its replicas
# ready. Prints the ones that do not; a failed read (kubectl, the API server) is not ready either.
workloads_back() {
  local platform="$CATALOG_NAMESPACE $OPERATOR_NAMESPACE $DORIS_NAMESPACE $MONITORING_NAMESPACE $CONSOLE_NAMESPACE"
  platform+=" $BI_NAMESPACE colvenna-lineage colvenna-polaris flink-operator doris-operator kube-system"
  if ! kubectl get deployments,statefulsets -A -o json >"$work/workloads.json" 2>"$work/workloads.err"; then
    echo "could not read the workloads: $(tr '\n' ' ' <"$work/workloads.err" | cut -c1-200)"
    return 1
  fi
  python3 - "$platform" "$work/workloads.json" <<'PY'
import json, sys
try:
    items = json.load(open(sys.argv[2]))["items"]
except ValueError:
    print("could not read the workloads: not JSON")
    sys.exit(1)
platform = set(sys.argv[1].split())
short = []
for o in items:
    if o["metadata"]["namespace"] not in platform:
        continue
    want = o["spec"].get("replicas", 1)
    ready = o.get("status", {}).get("readyReplicas", 0)
    if ready < want:
        short.append("%s/%s/%s %s/%s" % (o["metadata"]["namespace"], o["kind"], o["metadata"]["name"], ready, want))
print(" ".join(short))
sys.exit(1 if short else 0)
PY
}

# --------------------------------------------------------------------------------- the source
step "mysql fixture $db; source $source_name"
{
  python3 "$cdc/mysql_contract.py" reset "$db"
  python3 "$cdc/mysql_contract.py" setup "$db"
  cat <<SQL
USE $db;
SET SESSION cte_max_recursion_depth = 100000;
INSERT INTO customers (id, name, region, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 200)
  SELECT i, CONCAT('customer-', i), ELT(1 + i % 4, 'north', 'south', 'east', 'west'), 1 FROM n;
INSERT INTO orders (id, customer_id, amount, state, note, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 5000)
  SELECT i, 1 + i % 200, ROUND(i * 1.25, 2), 'created', NULL, 1 FROM n;
SQL
} >"$evidence/sql/setup.sql"
# From here on, an early exit cleans up what the run made (the database first).
made=true
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/setup.sql" >/dev/null
ensure_rds_ca "$JOB_NAMESPACE"
cat >"$evidence/source.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: $source_name, namespace: $JOB_NAMESPACE}
spec:
  type: mysql
  connection:
    host: $(stack_output "$TEST_MYSQL_STACK" MySqlEndpoint)
    database: $db
    credentialsSecret: {name: mysql-cdc}
    inspectorSecret: {name: mysql-inspect}
    caConfigMap: rds-ca
    serverTimeZone: UTC
  tables: {include: [orders, customers]}
  target: {database: $ods, bucket: 2}
  governance: {owner: ops-team, level: L2, retention: 7d}
  job: $small
  deletionPolicy: Delete
YAML
kubectl apply -f "$evidence/source.yaml"
deployment="$source_name-cdc"
await source-ready 900 "state('orders') == 'Ready' and state('customers') == 'Ready' and live_all()" || {
  echo "The source never ran; nothing to lose a node under"
  exit 1
}

if [[ "$kind" == query ]]; then
  step "dashboard $dashboard over $ods.orders: an external query and a copy"
  kubectl create namespace "$bi" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl label namespace "$bi" --overwrite pod-security.kubernetes.io/enforce=restricted \
    colvenna-lake.io/doris-client=true >/dev/null
  openssl rand -hex 16 | tr -d '\n' >"$work/analyst"
  kubectl -n "$JOB_NAMESPACE" create secret generic "$dashboard-analyst" --from-file=password="$work/analyst" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  cat >"$evidence/dashboard.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeDashboard
metadata: {name: $dashboard, namespace: $JOB_NAMESPACE}
spec:
  tables: [$ods.orders]
  governance: {owner: bi-team, level: L2, retention: 7d}
  queries:
    - {name: live, serve: external, sql: "SELECT id, amount, state, version_no FROM $ods.orders"}
    - {name: by_state, serve: materialized, refresh: 1m, sql: "SELECT state, COUNT(*) AS n, MAX(id) AS top FROM $ods.orders GROUP BY state"}
  users:
    - {name: analyst, passwordSecret: {name: $dashboard-analyst}, queries: [live, by_state]}
YAML
  kubectl apply -f "$evidence/dashboard.yaml"
  ready=false
  for _ in $(seq 1 90); do
    phase="$(kubectl -n "$JOB_NAMESPACE" get lakedashboard "$dashboard" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    if [[ "$phase" == Ready ]]; then ready=true; break; fi
    sleep 10
  done
  kubectl -n "$JOB_NAMESPACE" get lakedashboard "$dashboard" -o json >"$evidence/status/dashboard.json"
  if $ready; then
    verdict dashboard-ready PASS "$dashboard Ready"
  else
    verdict dashboard-ready FAIL "phase ${phase:-none}: $(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("status",{}).get("message"))' "$evidence/status/dashboard.json")"
    exit 1
  fi
  database="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["status"]["database"])' "$evidence/status/dashboard.json")"
  analyst="$JOB_NAMESPACE.$dashboard.analyst"
  cat >"$work/query.sql" <<SQL
SELECT 'live', COUNT(*), MAX(id) FROM \`$database\`.live
SELECT 'copy', SUM(n), MAX(top) FROM \`$database\`.by_state
SQL
fi

# dashboard_answer <out-file>: the analyst's two queries, from a pod in the BI client namespace;
# "live <rows> <top id>" and "copy <rows> <top id>" lines, or the errors.
dashboard_answer() {
  local pod="nq-as-$RANDOM"
  kubectl -n "$bi" create secret generic "$pod" --from-file=password="$work/analyst" \
    --from-file=run.sql="$work/query.sql" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  cat >"$work/$pod.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $pod, namespace: $bi}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 999, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: mysql
      image: $MYSQL_CLIENT_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["bash", "-c"]
      args:
        - |
          export MYSQL_PWD="\$(cat /in/password)"
          while IFS= read -r q; do
            [[ -z "\$q" ]] && continue
            mysql -h $fe -P 9030 -u '$analyst' --connect-timeout=10 --batch --skip-column-names -e "\$q" 2>&1 | head -3
          done < /in/run.sql
      resources: {requests: {cpu: 10m, memory: 64Mi}, limits: {memory: 128Mi}}
      volumeMounts: [{name: in, mountPath: /in, readOnly: true}]
  volumes: [{name: in, secret: {secretName: $pod}}]
YAML
  run_pod "$bi" "$pod" "$work/$pod.yaml" >"$1" 2>&1 || true
  kubectl -n "$bi" delete secret "$pod" --ignore-not-found >/dev/null
  kubectl -n "$bi" delete pod "$pod" --ignore-not-found --wait=false >/dev/null 2>&1 || true
}
# top_of <answer-file> <live|copy>: the top id that query saw, or nothing.
top_of() { awk -v q="$2" -F'\t' '$1 == q && $3 ~ /^[0-9]+$/ {print $3}' "$1"; }

# ----------------------------------------------------------------------------- the node to lose
jm="$(kubectl -n "$JOB_NAMESPACE" get pods -l "app=$deployment,component=jobmanager" -o jsonpath='{.items[0].metadata.name}')"
tm="$(kubectl -n "$JOB_NAMESPACE" get pods -l "app=$deployment,component=taskmanager" -o jsonpath='{.items[0].metadata.name}')"
role_of() { kubectl get node "$1" -o jsonpath='{.metadata.labels.colvenna-lake/role}'; }
if [[ "$kind" == job ]]; then
  victim="$(node_of "$JOB_NAMESPACE" "$tm")"
else
  victim="$(node_of "$DORIS_NAMESPACE" "$DORIS_CLUSTER-fe-0")"
fi
[[ -n "$victim" ]] || { verdict node-lost FAIL "no node found for the $kind case"; exit 1; }
role="$(role_of "$victim")"
group="$(kubectl get node "$victim" -o jsonpath='{.metadata.labels.eks\.amazonaws\.com/nodegroup}')"
desired="$(aws eks describe-nodegroup --cluster-name "$cluster" --nodegroup-name "$group" \
  --query nodegroup.scalingConfig.desiredSize --output text)"
asg="$(aws eks describe-nodegroup --cluster-name "$cluster" --nodegroup-name "$group" \
  --query 'nodegroup.resources.autoScalingGroups[0].name' --output text)"
instance="$(instance_of "$victim")" zone="$(zone_of "$victim")"
mapfile -t before < <(role_nodes "$role")
kubectl get pods -A -o wide --field-selector "spec.nodeName=$victim" >"$evidence/pods-on-the-lost-node.txt"
kubectl get nodes -L colvenna-lake/role,topology.kubernetes.io/zone >"$evidence/nodes-before.txt"
kubectl get pv -o wide >"$evidence/volumes-before.txt"
step "to lose: $victim, a $role node ($instance, $zone, group $group of $desired, ASG $asg); job manager on $(node_of "$JOB_NAMESPACE" "$jm"), task manager on $(node_of "$JOB_NAMESPACE" "$tm")"
if [[ "${#before[@]}" -lt 2 ]]; then
  verdict node-lost FAIL "only ${#before[@]} $role node(s) ready: nowhere for the job to go meanwhile"
  exit 1
fi

step "writer $writer, kept off $victim"
WRITER_AVOID_NODE="$victim" bash "$cdc/writer.sh" start mysql "$db" "$writer"
writing=true
sleep 60
batches_before="$(kubectl -n "$JOB_NAMESPACE" logs "$writer" --tail=1 2>/dev/null || true)"
if [[ "$kind" == query ]]; then
  dashboard_answer "$evidence/doris/before.tsv"
  top_before="$(top_of "$evidence/doris/before.tsv" live)"
  [[ -n "$top_before" ]] || { verdict dashboard-answers-before FAIL "$(head -3 "$evidence/doris/before.tsv")"; exit 1; }
  step "the dashboard answers before the loss: $(tr '\n' ' ' <"$evidence/doris/before.tsv")"
fi

# new_tags <ready|finished>: the replacement's own tags (not AWS's, EKS's or Kubernetes'), kept and noted: the
# account's automation may add Patch Group=DEV some minutes after launch.
new_tags() {
  aws ec2 describe-tags --filters "Name=resource-id,Values=$new_instance" --output json >"$evidence/new-instance-tags-$1.json"
  step "the new instance's tags when $1: $(python3 -c 'import json,sys; print(", ".join("%s=%s" % (t["Key"], t["Value"]) for t in json.load(open(sys.argv[1]))["Tags"] if not t["Key"].startswith(("aws:", "eks:", "k8s.io", "kubernetes.io"))) or "none of its own")' "$evidence/new-instance-tags-$1.json")"
}

# ------------------------------------------------------------------------------------- the loss
# What the job last restored from: after the loss it must have restored again, from a checkpoint.
capture "$evidence/status/before-loss.json"
restored_before="$(restored_of "$evidence/status/before-loss.json")"
# Only an instance of this environment's cluster, in the node group named: never another's.
aws ec2 describe-tags --filters "Name=resource-id,Values=$instance" --output json >"$evidence/victim-tags.json"
tagged="$(python3 -c 'import json,sys; t={x["Key"]: x["Value"] for x in json.load(open(sys.argv[1]))["Tags"]}; print(t.get("eks:cluster-name"), t.get("aws:autoscaling:groupName"))' \
  "$evidence/victim-tags.json")"
if [[ "$tagged" != "$cluster $asg" ]]; then
  verdict node-lost FAIL "$instance is tagged cluster and group '$tagged', not '$cluster $asg': not terminated"
  exit 1
fi
step "lose $victim: $loss ($batches_before; the job last restored from $restored_before)"
lost_at=$SECONDS lost_time="$(date -u +%FT%TZ)"
if [[ "$loss" == abrupt ]]; then
  aws ec2 terminate-instances --instance-ids "$instance" --output json >"$evidence/terminate.json"
else
  aws autoscaling terminate-instance-in-auto-scaling-group --instance-id "$instance" \
    --no-should-decrement-desired-capacity --output json >"$evidence/terminate.json"
fi
verdict node-lost PASS "$instance ($victim, $zone) terminated through $([[ "$loss" == abrupt ]] && echo EC2 || echo "its Auto Scaling group")"

# The replacement: a new Ready node of the role (not the lost one), the role's count back to the
# group's desired size, which did not change.
replacement="" gone_after="" end=$((SECONDS + 1800))
while ((SECONDS < end)); do
  if [[ -z "$gone_after" ]] && ! grep -qx "$victim" <<<"$(role_nodes "$role")"; then
    gone_after=$((SECONDS - lost_at))
    step "$victim no longer a ready $role node, ${gone_after}s after the loss"
  fi
  mapfile -t now < <(role_nodes "$role")
  for n in "${now[@]}"; do
    if [[ " ${before[*]} " != *" $n "* ]]; then replacement="$n"; fi
  done
  if [[ -n "$replacement" && "${#now[@]}" -ge "$desired" ]] && ! grep -qx "$victim" <<<"$(printf '%s\n' "${now[@]}")"; then
    break
  fi
  replacement=""
  sleep 15
done
desired_after="$(aws eks describe-nodegroup --cluster-name "$cluster" --nodegroup-name "$group" \
  --query nodegroup.scalingConfig.desiredSize --output text)"
aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$asg" --output json \
  >"$evidence/asg-after.json"
aws autoscaling describe-scaling-activities --auto-scaling-group-name "$asg" --max-items 5 --output json \
  >"$evidence/asg-activities.json" || true
kubectl get nodes -L colvenna-lake/role,topology.kubernetes.io/zone >"$evidence/nodes-after.txt"
if [[ -n "$replacement" && "$desired_after" == "$desired" ]]; then
  new_instance="$(instance_of "$replacement")" new_zone="$(zone_of "$replacement")"
  verdict node-replaced PASS "$replacement ($new_instance, $new_zone) ready $((SECONDS - lost_at))s after the loss; desired size still $desired"
  new_tags ready
else
  verdict node-replaced FAIL "no replacement $role node within 1800s (desired $desired -> $desired_after)"
fi

# ----------------------------------------------------------------------------------- recovery
if [[ "$kind" == job ]]; then
  # Running again, restored anew from a checkpoint (chk-N), not from nothing: the job manager
  # restarts its tasks, or the operator redeploys a lost job manager.
  await job-recovered 1800 "live('$deployment') and state('orders') == 'Ready' and state('customers') == 'Ready' \
    and 'chk-' in str(restored('$deployment')) and str(restored('$deployment')) != '$restored_before'" || true
  restored_from="$(restored_of "$evidence/status/job-recovered.json" 2>/dev/null || true)"
  restarts="$(kubectl -n "$JOB_NAMESPACE" get flinkdeployment "$deployment" -o json | python3 -c '
import json, sys
s = json.load(sys.stdin).get("status", {})
print("job %s, lifecycle %s" % ((s.get("jobStatus") or {}).get("state"), s.get("lifecycleState")))')"
  step "job after the loss: $restarts; restored from ${restored_from:-?}"
  # Writes go on two minutes past the recovery, so the comparison covers rows from before, during
  # and after the loss.
  sleep 120
fi

if [[ "$kind" == query ]]; then
  # Doris on the zone's volumes again, and the dashboard answering with rows written after the loss.
  answered="" end=$((SECONDS + 1800))
  while ((SECONDS < end)); do
    dashboard_answer "$evidence/doris/after.tsv"
    live_top="$(top_of "$evidence/doris/after.tsv" live)" copy_top="$(top_of "$evidence/doris/after.tsv" copy)"
    if [[ -n "$live_top" && -n "$copy_top" ]] && ((live_top > top_before && copy_top > top_before)); then
      answered=$((SECONDS - lost_at))
      break
    fi
    sleep 30
  done
  kubectl -n "$DORIS_NAMESPACE" get pods,pvc -o wide >"$evidence/doris/pods-after.txt" 2>&1 || true
  if [[ -n "$answered" ]]; then
    verdict dashboard-answers-again PASS "${answered}s after the loss: live top id $live_top, copy top id $copy_top, both past $top_before (before the loss)"
  else
    verdict dashboard-answers-again FAIL "not within 1800s: $(tr '\n' ' ' <"$evidence/doris/after.tsv" | cut -c1-300)"
  fi
  fe_node="$(node_of "$DORIS_NAMESPACE" "$DORIS_CLUSTER-fe-0" 2>/dev/null || true)"
  be_node="$(node_of "$DORIS_NAMESPACE" "$DORIS_CLUSTER-be-0" 2>/dev/null || true)"
  step "Doris FE on ${fe_node:-?} ($(zone_of "${fe_node:-none}" 2>/dev/null)), BE on ${be_node:-?} ($(zone_of "${be_node:-none}" 2>/dev/null))"
  # The entry's targets: BI and the console behind the load balancer are healthy again.
  unhealthy="" tgs=0
  for arn in $(kubectl get targetgroupbindings -A -o jsonpath='{range .items[*]}{.spec.targetGroupARN}{"\n"}{end}'); do
    tgs=$((tgs + 1))
    health="$(aws elbv2 describe-target-health --target-group-arn "$arn" \
      --query 'TargetHealthDescriptions[].TargetHealth.State' --output text | tr '\t' ' ')"
    [[ " $health " == *" healthy "* ]] || unhealthy+="${arn##*/}: ${health:-none} "
  done
  if ((tgs > 0)) && [[ -z "$unhealthy" ]]; then
    verdict entry-targets-healthy PASS "$tgs target groups (console, BI) each with a healthy target"
  else
    verdict entry-targets-healthy FAIL "${unhealthy:-no target group bindings}"
  fi
fi

# Every platform workload back, with the time it took (the lost node's pods moved, or waited for
# the replacement in their volumes' zone).
back="" end=$((SECONDS + 1200))
while ((SECONDS < end)); do
  if missing="$(workloads_back)"; then back=$((SECONDS - lost_at)); break; fi
  sleep 15
done
if [[ -n "$back" ]]; then
  verdict platform-workloads-back PASS "every Deployment and StatefulSet of the platform ready ${back}s after the loss"
else
  verdict platform-workloads-back FAIL "not ready: $missing"
fi
if bash "$DEPLOY_DIR/scripts/catalog/check-health.sh" "$evidence/health" >/dev/null; then
  verdict catalog-healthy PASS "$(grep -c ' OK ' "$evidence/health/health.txt") health checks OK"
else
  verdict catalog-healthy FAIL "$(grep ' FAIL ' "$evidence/health/health.txt" | tr '\n' ' ')"
fi

# ------------------------------------------------------------------------------- correctness
step "writer stop: $(bash "$cdc/writer.sh" stop "$writer")"
writing=false
# The rows of both tables, written before, during and after the loss, against the source's.
for spec in orders:id customers:id; do
  table="${spec%%:*}" key="${spec##*:}" matched=false
  for attempt in $(seq 1 20); do
    bash "$FIXTURES_DIR/sources/sources.sh" mysql-table "$db" "$table" >"$evidence/dumps/$table.source.tsv"
    bash "$ACCEPTANCE_DIR/lake.sh" rows "$evidence/dumps/$table.rows.jsonl" "$ods.$table" >/dev/null
    if python3 "$cdc/compare.py" rows --lake "$evidence/dumps/$table.rows.jsonl" \
      --source "$evidence/dumps/$table.source.tsv" --table "$ods.$table" --key "$key" \
      >"$evidence/dumps/$table.verdict.json"; then
      matched=true
      break
    fi
    echo "attempt $attempt: $(cat "$evidence/dumps/$table.verdict.json")"
    sleep 20
  done
  if $matched; then
    verdict "rows-$table" PASS "$(cat "$evidence/dumps/$table.verdict.json")"
  else
    verdict "rows-$table" FAIL "$(cat "$evidence/dumps/$table.verdict.json")"
  fi
done

# ---------------------------------------------------------------------------------- clean up
cleaned=true
step "clean up: $([[ "$kind" == query ]] && echo "$dashboard, ")$source_name, $db"
if [[ "$kind" == query ]]; then
  kubectl -n "$JOB_NAMESPACE" delete lakedashboard "$dashboard" --timeout=10m || true
  kubectl -n "$JOB_NAMESPACE" delete secret "$dashboard-analyst" --ignore-not-found >/dev/null
fi
kubectl -n "$JOB_NAMESPACE" delete lakesource "$source_name" --timeout=10m || true
python3 "$cdc/mysql_contract.py" reset "$db" >"$evidence/sql/reset.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/reset.sql" >/dev/null
kubectl -n "$JOB_NAMESPACE" get events -o json >"$evidence/events.json"
[[ -z "${new_instance:-}" ]] || new_tags finished
step "lost at $lost_time; evidence $evidence"
echo "verdicts:"
cat "$evidence/verdicts.tsv"
exit "$failed"
