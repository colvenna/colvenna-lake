#!/usr/bin/env bash
# The release performance run (docs/uat/performance.md): the default-scale tier on an environment
# that deploy/install.sh --fixtures installed, with nothing else declared on it. At once, for
# MINUTES (120) of steady load:
#   - the dashboards run with PERF=1 (tests/acceptance/dashboards/run-dashboards.sh d<run>): a
#     MySQL source, DWD and DWS, its markers every tenth of a second, 10 fixed dashboard queries a
#     second with point lookups, an ad-hoc burst in the middle; plus, through release.sh, the change
#     load, the version markers (VERSION_RATE keys a second, each inserted, updated and deleted;
#     together with the load and the dashboards run's markers the source takes CDC_RATE, 200,
#     changes a second), their Doris paths and prober, the snapshot observer on ODS, DWD and DWS,
#     and the clock sampler;
#   - the events run (run-events.sh e<run>): a Kafka event source at KAFKA_RATE (2,000) events a
#     second of EVENT_BYTES (1,024), producing before the window starts and ending when the
#     dashboards run's load does.
# Then perf.py judges each path and kind of change against the acceptance contract's 2.1
# (layers.json, events.json, beside the dashboards run's doris/load.json) and writes release.md and
# release.json: PASS, NOT_JUDGED (some row short of 10,000 valid samples) or FAIL. A run whose
# parts did not end cleanly, or that left anything behind, fails (statuses.json). Exit 0 on PASS
# only.
#
# It needs the environment alone: it refuses to start while anything is declared in the managed
# namespaces (LakeSource, LakeTable, LakeDashboard, LakeQuality, LakeErasure,
# LakeIcebergPublication, a LakeTask not finished), a Flink deployment or a test pod runs there,
# or the development Kafka is installed or its data kept: a UAT, or another test, is using it. Do
# not start one beside it.
#
# usage: COLVENNA_ENV=<env> tests/performance/run-release.sh <run-id> <evidence-root>
#   MINUTES (120, 30 to 150), CDC_RATE (200), VERSION_RATE (2), KAFKA_RATE (2000), EVENT_BYTES
#   (1024, 128 to 2048); IMAGES_SHA as for the acceptance tests. About MINUTES + 55 minutes in all.
# shellcheck source-path=SCRIPTDIR source=lib.sh
source "$(dirname "$0")/lib.sh"
run="${1:?usage: run-release.sh <run-id> <evidence-root>}"
root="${2:?usage: run-release.sh <run-id> <evidence-root>}"
[[ "$run" =~ ^[a-z0-9]{1,10}$ ]] || { echo "run id: lowercase letters and digits, at most 10" >&2; exit 1; }
minutes="${MINUTES:-120}" cdc_rate="${CDC_RATE:-200}" kafka_rate="${KAFKA_RATE:-2000}"
event_bytes="${EVENT_BYTES:-1024}" version_rate="${VERSION_RATE:-2}"
for n in "$minutes" "$cdc_rate" "$kafka_rate" "$event_bytes" "$version_rate"; do
  [[ "$n" =~ ^[1-9][0-9]*$ ]] || { echo "MINUTES, CDC_RATE, VERSION_RATE, KAFKA_RATE, EVENT_BYTES: whole numbers" >&2; exit 1; }
done
((minutes >= 30)) || { echo "MINUTES: the acceptance contract's steady state is 30 minutes at least" >&2; exit 1; }
# The marker and load pods' lines come back through kubectl logs, which reads only the container's
# current log file: the load pod writes some 65 KB a minute (the marker pod some 34 KB), so past
# about two and a half hours the kubelet's 10 MiB rotation would drop its first lines (and
# dashboards.py would find its log not whole).
((minutes <= 150)) || { echo "MINUTES: 150 at most (the load pod's log rotates beyond)" >&2; exit 1; }
((event_bytes >= 128 && event_bytes <= 2048)) || { echo "EVENT_BYTES: 128 to 2048" >&2; exit 1; }
((version_rate <= 9)) || { echo "VERSION_RATE: 1 to 9 keys a second" >&2; exit 1; }
# The source's rate: the dashboards run's markers (10 a second), the version markers (an insert, an
# update and a delete per key) and the change load.
load_rate=$((cdc_rate - 10 - 3 * version_rate))
((load_rate >= 10)) || { echo "CDC_RATE: at least 20 + 3 x VERSION_RATE changes a second" >&2; exit 1; }
sha="$(committed_sha)"
mkdir -p "$root"
root="$(cd "$root" && pwd)"
[[ ! -e "$root/release.md" ]] || { echo "$root holds a run already; use a new evidence root" >&2; exit 1; }
exec > >(tee -a "$root/run.log") 2>&1
step() { echo "$(date -u +%FT%TZ)	$*" | tee -a "$root/timeline.tsv"; }

# occupants: what on the environment a test declared or runs, one per line.
occupants() {
  local ns kind item
  for ns in ${MANAGED_NAMESPACES//,/ }; do
    for kind in lakesource laketable lakedashboard lakequality lakeerasure lakeicebergpublication \
      flinkdeployment; do
      for item in $(kubectl -n "$ns" get "$kind" -o name 2>/dev/null); do
        echo "$ns/$item"
      done
    done
    # Tasks that finished are records; the ones still queued or running are someone's.
    kubectl -n "$ns" get laketask -o json 2>/dev/null | python3 -c 'import json,sys; [print(sys.argv[1] + "/laketask/" + t["metadata"]["name"]) for t in json.load(sys.stdin)["items"] if (t.get("status") or {}).get("phase") not in ("Succeeded", "Failed")]' "$ns"
    for item in $(kubectl -n "$ns" get pods -l 'colvenna-lake.io/role' -o name 2>/dev/null) \
      $(kubectl -n "$ns" get pods -l 'colvenna-lake.io/clock-probe' -o name 2>/dev/null); do
      echo "$ns/$item"
    done
  done
  for item in $(kubectl -n colvenna-bi-client get pods -l 'colvenna-lake.io/role' -o name 2>/dev/null); do
    echo "colvenna-bi-client/$item"
  done
  if helm status "$DEV_KAFKA_RELEASE" --namespace "$DEV_KAFKA_NAMESPACE" >/dev/null 2>&1; then
    echo "helm release $DEV_KAFKA_NAMESPACE/$DEV_KAFKA_RELEASE"
  fi
  if kubectl -n "$DEV_KAFKA_NAMESPACE" get configmap "$DEV_KAFKA_RELEASE-retained-data" >/dev/null 2>&1; then
    echo "kept data of the development Kafka (tests/fixtures/kafka/dev-kafka.sh purge)"
  fi
}

# ------------------------------------------------------------------------- alone on the environment
step "is the environment alone?"
mapfile -t busy < <(occupants)
if ((${#busy[@]} > 0)); then
  printf 'Not alone on %s: %s\n' "$COLVENNA_ENV" "${busy[*]}" >&2
  echo "The release performance run needs the environment to itself; finish or remove these first" >&2
  exit 2
fi

# ------------------------------------------------------------------------- what is measured
{
  echo "run $run of tests at $sha, images of $(images_sha)"
  echo "by $(aws sts get-caller-identity --query Arn --output text)"
  echo "environment $COLVENNA_ENV in $COLVENNA_REGION, cluster $(kubectl config current-context)"
  aws eks describe-cluster --name "$(stack_output "$FOUNDATION_STACK" ClusterName)" \
    --query 'cluster.[version,platformVersion]' --output text 2>/dev/null | sed 's/^/eks /' || true
  kubectl get nodes -L colvenna-lake/role,node.kubernetes.io/instance-type,topology.kubernetes.io/zone \
    --no-headers | awk '{print "node", $1, $6, $7, $8, $5}'
  for image in operator mysql sql kafka; do echo "image $image $(image_ref "$image")"; done
  echo "image query-engine-fe $DORIS_FE_IMAGE"
  echo "image query-engine-be $DORIS_BE_IMAGE"
  echo "load: $minutes minutes; MySQL $cdc_rate changes a second (load $load_rate, version markers $((3 * version_rate)), markers 10); Kafka $kafka_rate events a second of $event_bytes bytes; dashboards 10 queries a second"
} | tee "$root/environment.txt"

mvn -B -q -f "$PERFORMANCE_DIR/tools/pom.xml" -DskipTests clean package
PERF_TOOLS_JAR="$root/performance-tools.jar"
cp "$PERFORMANCE_DIR/tools/target/performance-tools-0.1.0.jar" "$PERF_TOOLS_JAR"
sha256sum "$PERF_TOOLS_JAR" >"$root/performance-tools.sha256"
export PERF_TOOLS_JAR PERF_APPROVAL="release performance run $run at $sha"

# ------------------------------------------------------------------------- the run
events_pid=""
# shellcheck disable=SC2317  # invoked through the EXIT trap
finish() {
  touch "$root/stop-events"
  if [[ -n "$events_pid" ]]; then wait "$events_pid" 2>/dev/null || true; fi
}
trap finish EXIT
step "events run e$run"
STOP_FILE="$root/stop-events" READY_FILE="$root/events-ready" KAFKA_RATE="$kafka_rate" \
  EVENT_BYTES="$event_bytes" bash "$PERFORMANCE_DIR/run-events.sh" "e$run" "$root/events" \
  >"$root/events.log" 2>&1 &
events_pid=$!
step "dashboards run d$run with the change load, the version markers, the observer and the clocks"
range="order_id >= 5000000 AND order_id < 10000000"
dashboards_status=0
PERF=1 LOAD_MINUTES="$minutes" PERF_EXTRA="$PERFORMANCE_DIR/release.sh" \
  CDC_LOAD_RATE="$load_rate" VERSION_RATE="$version_rate" PERF_STOP_FILE="$root/stop-events" \
  PERF_EVENTS_READY="$root/events-ready" \
  DASHBOARDS_DWS_EXTRA=", SUM(CASE WHEN $range THEN 1 ELSE 0 END) AS rel_live, SUM(CASE WHEN $range THEN version_no ELSE 0 END) AS rel_versions, SUM(CASE WHEN $range THEN amount ELSE 0 END) AS rel_amount" \
  bash "$ACCEPTANCE_DIR/dashboards/run-dashboards.sh" "d$run" "$root/dashboards" \
  >"$root/dashboards.log" 2>&1 || dashboards_status=$?
step "dashboards run ended: exit $dashboards_status; waiting for the events run"
touch "$root/stop-events"
events_status=0
wait "$events_pid" || events_status=$?
events_pid=""
step "events run ended: exit $events_status"
# Deletions that do not wait (pods terminating) get three minutes.
for _ in $(seq 1 18); do
  mapfile -t left < <(occupants)
  ((${#left[@]} == 0)) && break
  sleep 10
done
step "left behind: ${left[*]:-nothing}"
# The dashboards run's own exit status is no measure here: it fails on its performance checks,
# which perf.py replaces; its teardown result is recorded apart, and its functional checks are in
# the report.
python3 - "$root/statuses.json" "$events_status" "$(cat "$root/dashboards/release/stop-status" 2>/dev/null || echo 1)" \
  "$(cat "$root/dashboards/teardown-status" 2>/dev/null || echo 1)" "${#left[@]}" <<'PY'
import json, sys
json.dump({"events_run": int(sys.argv[2]), "release_stop_and_cleanup": int(sys.argv[3]),
           "dashboards_teardown": int(sys.argv[4]), "left_behind": int(sys.argv[5])},
          open(sys.argv[1], "w"))
PY

# ------------------------------------------------------------------------- the verdicts
dash="$root/dashboards"
read -r load_from load_to <"$dash/doris/window.txt" || { load_from=0 load_to=0; }
step "the window: $(date -u -d "@$((load_from / 1000))" +%FT%TZ) to $(date -u -d "@$((load_to / 1000))" +%FT%TZ)"
python3 "$PERFORMANCE_DIR/perf.py" layers "$dash/release" "$dash/doris/marks.log" "$root/layers.json" \
  --window "$load_from" "$load_to" --run "d$run" --cdc-target "$cdc_rate" >"$root/layers.txt" || true
python3 "$PERFORMANCE_DIR/perf.py" events "$root/events" "$root/events.json" \
  --window "$load_from" "$load_to" --run "e$run" --broker-namespace "$DEV_KAFKA_NAMESPACE" \
  --rate "$kafka_rate" >"$root/events.txt" || true
outcome=0
python3 "$PERFORMANCE_DIR/perf.py" report "$root" "$root/release.md" || outcome=$?
step "release performance: $(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["outcome"])' "$root/release.json")"
exit "$outcome"
