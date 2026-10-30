#!/usr/bin/env bash
# Runs the acceptance tests as the UAT does (docs/uat/cases.md), on an environment that
# deploy/install.sh --fixtures installed at this commit: functional checks only, in the order and
# the groups the tests' shared state allows. Each test gets its own run id and evidence directory
# under <evidence-root>; runs.tsv records each one's exit status, start and seconds, and
# summary.tsv each verdict.
#
# usage: COLVENNA_ENV=<env> tests/acceptance/run-uat.sh <evidence-root> [<phase>...]
#   phases, in order (all when none is named):
#     prep        the images of this commit (install.sh again, judged as an upgrade or a repeat by
#                 deploy/run-reinstall.sh, then the Spark image); later runs of this script with
#                 the same <evidence-root> read the images of the commit prep ran at, so tests
#                 changed since run without reinstalling
#     deploy      what install.sh refuses, a second install at this commit that changes nothing,
#                 an install interrupted and resumed
#     catalog     colvenna-catalog through Flink, Spark and Doris: data tokens, authorization, logs
#     faults      commit faults, then recovery from a backup (each alone)
#     renewal     token renewal, alone, with the catalog at 90-minute tokens for its run
#     ingest      CDC, layered tables beside tasks, MongoDB beside enforcement, monitoring
#     kafka       the development Kafka chain: events, Kafka-carried CDC, quality, Trino, Iceberg
#     namespaces  a second managed namespace, then back to one
#     solo        alone in the namespace: lineage, governance, Iceberg erasure, operations, dashboards
#     console     BI and the pipeline view
#     entry       the web entry, with 10-minute sessions for its run
#     nodes       the node of a CDC job's TaskManager lost, then the query node of Doris's FE (each alone)
#     summary     summary.tsv from what ran
# RUN_TAG (default: the UTC month, day, hour and minute) makes the run ids unique. To run one test
# again after a fix, see rerun-uat.sh. The tests' port-forwards take free local ports, so the UATs of two
# environments can run on one host at once. The tests are called through /usr/bin/env: another `env`
# earlier on PATH (an installer's ~/.local/bin/env) once ran nothing, and the runs said exit 0.
set -euo pipefail
# shellcheck source-path=SCRIPTDIR source=common.sh
source "$(dirname "$0")/common.sh"
assert_account
root="${1:?usage: run-uat.sh <evidence-root> [<phase>...]}"
shift
phases=("$@")
[[ ${#phases[@]} -gt 0 ]] || phases=(prep deploy catalog faults renewal ingest kafka namespaces solo console entry nodes summary)
mkdir -p "$root"
root="$(cd "$root" && pwd)"
tag="${RUN_TAG:-$(date -u +%m%d%H%M)}"
[[ "$tag" =~ ^[0-9a-z]{1,8}$ ]] || { echo "RUN_TAG: at most 8 lowercase letters and digits" >&2; exit 1; }
acceptance="$ACCEPTANCE_DIR" catalog="$ACCEPTANCE_DIR/catalog" scripts="$DEPLOY_DIR/scripts"
# log <text>: a timestamped line, on the screen and in uat.log.
log() { echo "$(date -u +%FT%TZ) $*" | tee -a "$root/uat.log"; }
# The commit the environment was installed at (the prep phase records it): the tests read its
# images even when they themselves are of a later commit that changed tests only.
if [[ -s "$root/installed-sha" ]]; then
  IMAGES_SHA="$(cat "$root/installed-sha")"
  export IMAGES_SHA
fi
log "UAT of $COLVENNA_ENV: tests at $(git -C "$REPO_DIR" rev-parse HEAD), images of ${IMAGES_SHA:-the same}, run tag $tag: ${phases[*]}"

# run <label> <command>...: one test, its output in <label>.log, its row in runs.tsv. Never fails:
# a test's FAIL is its verdicts', and the next test runs.
run() {
  local label="$1" begin=$SECONDS status=0
  shift
  log "start $label: $*"
  ("$@") >"$root/$label.log" 2>&1 || status=$?
  printf '%s\t%s\t%s\t%s\n' "$label" "$status" "$(date -u -d "@$(($(date +%s) - (SECONDS - begin)))" +%FT%TZ)" \
    "$((SECONDS - begin))" >>"$root/runs.tsv"
  log "end $label: exit $status after $((SECONDS - begin))s"
}
# id <code>: the run id of one test, unique to this UAT.
id() { printf '%s%s' "$1" "$tag"; }
# ev <label>: the evidence directory of one test.
ev() { printf '%s/%s' "$root" "$1"; }
flow_db="flow_$(id cf)"

phase_prep() {
  # This commit's images: every step converges, and image references are kept per commit. Judged:
  # an upgrade when the environment ran an older commit (UAT-DEP-06), else a repeat (also when it
  # ran a commit that differs from this one only under release/: a candidate's tag and the commit its
  # images were built from).
  run reinstall bash "$acceptance/deploy/run-reinstall.sh" "$(id rei)" "$(ev reinstall)"
  run spark-image bash "$catalog/build-spark-image.sh"
  git -C "$REPO_DIR" rev-parse HEAD >"$root/installed-sha"
  unset IMAGES_SHA
}

phase_deploy() {
  # Refusals make nothing (UAT-DEP-05); a second install at this commit changes nothing (UAT-DEP-03);
  # one interrupted in a step resumes with --from (UAT-DEP-04). A release install's refusals
  # (UAT-DEP-08) need the candidate, from UAT_INSTALL_ARGS' --release.
  run deploy-refusals bash "$acceptance/deploy/run-refusals.sh" "$(id ref)" "$(ev deploy-refusals)"
  if [[ " ${UAT_INSTALL_ARGS:-} " == *" --release "* ]]; then
    run deploy-release-refusals bash "$acceptance/deploy/run-release-refusals.sh" "$(id rrf)" \
      "$(ev deploy-release-refusals)"
  else
    log "deploy-release-refusals: not a release install (UAT_INSTALL_ARGS has no --release)"
  fi
  run deploy-repeat bash "$acceptance/deploy/run-reinstall.sh" "$(id rep)" "$(ev deploy-repeat)"
  run deploy-resume bash "$acceptance/deploy/run-resume.sh" "$(id res)" "$(ev deploy-resume)"
}

phase_catalog() {
  local since
  since="$(date -u +%FT%TZ)"
  run catalog-flink bash "$catalog/run-flink-flow.sh" "$(id cf)" "$(ev catalog-flink)"
  run catalog-spark bash "$catalog/run-spark-flow.sh" "$(id cs)" "$flow_db" "$(ev catalog-spark)"
  run catalog-doris bash "$catalog/run-doris-flow.sh" "$(id cd)" "$flow_db" "$(ev catalog-doris)"
  run catalog-session bash "$catalog/run-flink-deployment.sh" "$(id cx)" "$(ev catalog-session)" \
    com.colvenna.lake.acceptance.flow.FlinkCatalogFlow "flow_$(id cx)"
  run catalog-tokens bash "$catalog/verify-data-tokens.sh" "$flow_db" "$root" "$(ev catalog-tokens)"
  run catalog-authorization bash "$catalog/verify-authorization.sh" "$flow_db" "$(ev catalog-authorization)"
  run catalog-access bash "$scripts/catalog/verify-catalog.sh" "$(ev catalog-access)"
  run catalog-health bash "$scripts/catalog/check-health.sh" "$(ev catalog-health)"
  run catalog-logs bash "$catalog/check-catalog-logs.sh" "$since" "$(ev catalog-logs)"
}

phase_faults() {
  run catalog-commit-faults bash "$catalog/run-commit-faults.sh" "$(id ck)" "$(ev catalog-commit-faults)"
  run catalog-recovery bash "$catalog/run-recovery.sh" "$(id cr)" "$(ev catalog-recovery)"
}

phase_renewal() {
  # Its own database, where Flink and Spark each ran once, and a catalog vending 90-minute tokens;
  # then the renewal itself, about two hours, alone: beside other tests, its second TaskManager
  # waits for room past Flink's slot timeout, and the run fails.
  local db
  db="flow_$(id rf)"
  run renewal-flink bash "$catalog/run-flink-flow.sh" "$(id rf)" "$(ev renewal-flink)"
  run renewal-spark bash "$catalog/run-spark-flow.sh" "$(id rs)" "$db" "$(ev renewal-spark)"
  run renewal-catalog /usr/bin/env DATA_TOKEN_DURATION=PT1H30M bash "$scripts/catalog/install-catalog.sh" \
    "$(ev renewal-catalog)"
  run renewal bash "$catalog/run-renewal.sh" "$(id rn)" "$db" "$(ev renewal)"
  run renewal-catalog-back bash "$scripts/catalog/install-catalog.sh" "$(ev renewal-catalog-back)"
}

phase_ingest() {
  # The groups that fit two write and two query nodes: the CDC alone (about five Flink deployments
  # at a time), then two by two, then the monitoring alone.
  local outside="$flow_db.orders"
  run cdc bash "$acceptance/cdc/run-cdc.sh" "$(id cdc)" "$(ev cdc)"
  run tables /usr/bin/env OUTSIDE_TABLE="$outside" bash "$acceptance/tables/run-tables.sh" "$(id tbl)" "$(ev tables)" &
  run tasks /usr/bin/env OUTSIDE_TABLE="$outside" bash "$acceptance/tasks/run-tasks.sh" "$(id tsk)" "$(ev tasks)" &
  wait
  run mongo bash "$acceptance/mongo/run-mongo.sh" "$(id mgo)" "$(ev mongo)" &
  run enforcement bash "$acceptance/governance/run-enforcement.sh" "$(id enf)" "$(ev enforcement)" &
  wait
  run monitoring bash "$acceptance/monitoring/run-monitoring.sh" "$(id mon)" "$(ev monitoring)"
}

phase_kafka() {
  # One development Kafka, its topics and users replaced by each test's install: strictly one by one.
  run kafka /usr/bin/env KEEP_KAFKA=1 bash "$acceptance/kafka/run-kafka.sh" "$(id kfk)" "$(ev kafka)"
  run kafka-cdc /usr/bin/env KEEP_KAFKA=1 bash "$acceptance/kafka/run-kafka-cdc.sh" "$(id kcd)" "$(ev kafka-cdc)"
  run quality bash "$acceptance/quality/run-quality.sh" "$(id qal)" "$(ev quality)"
  run trino bash "$acceptance/trino/run-trino.sh" "$(id trn)" "$(ev trino)"
  run iceberg bash "$acceptance/iceberg/run-iceberg.sh" "$(id ice)" "$(ev iceberg)"
  run iceberg-layered bash "$acceptance/iceberg/run-iceberg-layered.sh" "$(id icl)" "$(ev iceberg-layered)"
}

phase_namespaces() {
  local two="$JOB_NAMESPACE,colvenna-second"
  run namespaces-stack /usr/bin/env MANAGED_NAMESPACES="$two" bash "$scripts/operator/deploy-stack.sh"
  run namespaces-operator /usr/bin/env MANAGED_NAMESPACES="$two" bash "$scripts/operator/install-operator.sh"
  run namespaces /usr/bin/env MANAGED_NAMESPACES="$two" bash "$acceptance/namespaces/run-namespaces.sh" "$(id ns)" "$(ev namespaces)"
  run namespaces-operator-back bash "$scripts/operator/install-operator.sh"
  run namespaces-stack-back bash "$scripts/operator/deploy-stack.sh"
  kubectl delete namespace colvenna-second --ignore-not-found --wait=false >/dev/null 2>&1 || true
  aws s3 rm "s3://$(stack_output "$FOUNDATION_STACK" WarehouseBucket)/flink-state/colvenna-second/" \
    --recursive --quiet || true
}

phase_solo() {
  # Each sees, or erases, everything declared in the namespace: one at a time.
  run lineage bash "$acceptance/lineage/run-lineage.sh" "$(id lin)" "$(ev lineage)"
  run governance bash "$acceptance/governance/run-governance.sh" "$(id gov)" "$(ev governance)"
  run iceberg-erasure bash "$acceptance/iceberg/run-iceberg-erasure.sh" "$(id ier)" "$(ev iceberg-erasure)"
  run operations bash "$acceptance/operations/run-operations.sh" "$(id ops)" "$(ev operations)"
  run dashboards bash "$acceptance/dashboards/run-dashboards.sh" "$(id dsh)" "$(ev dashboards)"
}

phase_console() {
  run bi bash "$acceptance/bi/run-bi.sh" "$(id bi)" "$(ev bi)" &
  run pipeline bash "$acceptance/pipeline/run-pipeline.sh" "$(id pip)" "$(ev pipeline)" &
  wait
}

phase_entry() {
  # Short sessions, so the run sees one end; every other session in the environment too, meanwhile.
  run entry-stack /usr/bin/env SESSION_TIMEOUT_SECONDS=600 bash "$scripts/entry/deploy-stack.sh"
  run entry bash "$acceptance/entry/run-entry.sh" "$(id ent)" "$(ev entry)"
  run entry-stack-back bash "$scripts/entry/deploy-stack.sh"
}

phase_nodes() {
  # Each takes a node away from the whole environment: alone, last.
  run node-job bash "$acceptance/faults/run-node-loss.sh" "$(id nlj)" "$(ev node-job)" job
  run node-query bash "$acceptance/faults/run-node-loss.sh" "$(id nlq)" "$(ev node-query)" query
}

# summary: one row per verdict (the tests' verdicts.tsv, the catalog checks' PASS and FAIL lines),
# and per test that ended non-zero without one.
phase_summary() {
  python3 "$acceptance/uat_report.py" summary "$root" | tee "$root/summary.tsv" \
    | awk -F'\t' '{n[$3]++} END {for (k in n) print k, n[k]}'
}

for phase in "${phases[@]}"; do
  case "$phase" in
    prep | deploy | catalog | faults | renewal | ingest | kafka | namespaces | solo | console | entry | nodes | summary) ;;
    *) echo "No phase $phase" >&2; exit 1 ;;
  esac
  "phase_$phase"
done
log "done; runs in $root/runs.tsv"
