#!/usr/bin/env bash
# The release performance run's additions to the dashboards run: run-dashboards.sh calls this as
# PERF_EXTRA (PERF=1 only), with the run's own names (source dash-<run>, tables dash-dwd-<run> and
# dash-dws-<run>; DWS must carry the version markers' totals, rel_live, rel_versions and
# rel_amount, which run-release.sh adds through DASHBOARDS_DWS_EXTRA).
#   release.sh start <run> <evidence> <db> <ods>.orders <dwd>.orders <dws>.segments <marker-base>
#       before the markers start, each confirmed running before it returns:
#       - the change load on the source's orders, CDC_LOAD_RATE changes a second (cdc-load.sh);
#       - the version markers, VERSION_RATE keys a second (2), each inserted, updated a minute later
#         and deleted two minutes after its insert (versions.sh);
#       - the snapshot observer on ODS and DWD (keys from 5,000,000: the version markers and the
#         dashboards run's markers, with version, amount and note) and DWS (every change);
#       - dashboard rel-<run>, the version markers' Doris paths (an external view and a
#         materialized view over ODS, a materialized view over DWS), and its prober
#         (doris-probe.sh) in the dashboards run's Doris client namespace;
#       - the clock sampler;
#       and, with PERF_EVENTS_READY set, the events run's producer (it writes "producing" there,
#       or "failed"; 20 minutes at most). Fails, and the dashboards run stops, if any of it fails.
#   release.sh stop <run> <evidence>
#       once the load is done and drained: the logs and lines copied into <evidence>/release
#       (cdc-load.log, versions.log, doris.log, observed.jsonl and ODS's alone in LakeDump's form,
#       ods-orders.jsonl, for dashboards.py fresh), the dashboard deleted; PERF_STOP_FILE, when set,
#       is created (run-release.sh stops the events run then); then cleanup. Its exit status, and
#       the cleanup's, go to <evidence>/release/stop-status.
#   release.sh cleanup <run>
#       what start made, whatever happened: the pods, the dashboard, the load's MySQL user, the
#       observer's identity, the clock probes; fails if any of it could not be removed
# shellcheck source-path=SCRIPTDIR source=lib.sh
source "$(dirname "$0")/lib.sh"
usage="usage: release.sh start <run> <evidence> <db> <ods.table> <dwd.table> <dws.table> <base> | stop <run> <evidence> | cleanup <run>"
command="${1:?$usage}"
run="${2:?$usage}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
observer="observe-$run" load="cdcload-$run" versions="versions-$run" prober="prober-$run"
board="rel-$run" principal="perf-$run" tools="perf-tools-$run" load_user="load_$run"
bi=colvenna-bi-client
# The version markers' keys: from here up to the dashboards run's markers (perf.py VERSION_FIRST).
version_first=5000000
# The clock sampler's pid, where cleanup finds it without the evidence directory.
sampler_pid="$STATE_DIR/perf-clocks-$run.pid"

cleanup() {
  local status=0
  if [[ -s "$sampler_pid" ]]; then
    kill "$(cat "$sampler_pid")" 2>/dev/null || true
    rm -f "$sampler_pid"
  fi
  clock_probes_remove "clock-$run"
  kubectl -n "$JOB_NAMESPACE" delete lakedashboard "$board" --ignore-not-found --timeout=5m \
    >/dev/null || status=1
  kubectl -n "$JOB_NAMESPACE" delete pod "$load" "$versions" "$observer" --ignore-not-found \
    --wait=false >/dev/null || status=1
  kubectl -n "$bi" delete pod "$prober" --ignore-not-found --wait=false >/dev/null || status=1
  kubectl -n "$JOB_NAMESPACE" delete secret "$load" "$board-prober" --ignore-not-found >/dev/null \
    || status=1
  kubectl -n "$JOB_NAMESPACE" delete configmap "$load" "$versions" "$tools" --ignore-not-found \
    >/dev/null || status=1
  kubectl -n "$bi" delete secret,configmap "$prober" --ignore-not-found >/dev/null || status=1
  observer_withdraw "$principal" || status=1
  local sql
  sql="$(mktemp)"
  echo "DROP USER IF EXISTS '$load_user'@'%';" >"$sql"
  bash "$FIXTURES_DIR/sources/sources.sh" mysql "$sql" >/dev/null || status=1
  rm -f "$sql"
  return "$status"
}

# mysql_pod <name> <script> <cpu> <NAME=value>...: a pod of the MySQL client image in the job
# namespace running <script> with the load user's password at /in, the RDS CA at /ca and an
# emptyDir at /out.
mysql_pod() {
  local name="$1" script="$2" cpu="$3" env="" kv
  shift 3
  for kv in "$@"; do
    env+="        - {name: ${kv%%=*}, value: \"${kv#*=}\"}"$'\n'
  done
  kubectl -n "$JOB_NAMESPACE" create configmap "$name" --from-file=run.sh="$script" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl -n "$JOB_NAMESPACE" delete pod "$name" --ignore-not-found --wait=true >/dev/null
  kubectl -n "$JOB_NAMESPACE" apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $name, labels: {colvenna-lake.io/role: performance}}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  terminationGracePeriodSeconds: 5
  securityContext: {runAsNonRoot: true, runAsUser: 999, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: run
      image: $MYSQL_CLIENT_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["bash", "/script/run.sh"]
      env:
${env%$'\n'}
      resources: {requests: {cpu: $cpu, memory: 128Mi}, limits: {memory: 256Mi}}
      volumeMounts:
        - {name: in, mountPath: /in, readOnly: true}
        - {name: script, mountPath: /script, readOnly: true}
        - {name: ca, mountPath: /ca, readOnly: true}
        - {name: out, mountPath: /out}
  volumes:
    - {name: in, secret: {secretName: $load}}
    - {name: script, configMap: {name: $name}}
    - {name: ca, configMap: {name: rds-ca}}
    - {name: out, emptyDir: {}}
YAML
  kubectl -n "$JOB_NAMESPACE" wait --for=condition=Ready "pod/$name" --timeout=180s >/dev/null
}

# until_true <seconds> <what> <command>...: retries the command every 5 s; fails with <what>.
until_true() {
  local end=$((SECONDS + $1)) what="$2"
  shift 2
  until "$@" >/dev/null 2>&1; do
    if ((SECONDS >= end)); then
      echo "Not within the time: $what" >&2
      return 1
    fi
    sleep 5
  done
}

load_running() {
  kubectl -n "$JOB_NAMESPACE" logs "$load" --tail=1 | awk '$1 == "load" && $3 > 0 {ok = 1} END {exit !ok}'
}
versions_running() {
  kubectl -n "$JOB_NAMESPACE" exec "$versions" -- grep -q '^op ' /out/versions.log
}
board_ready() {
  kubectl -n "$JOB_NAMESPACE" get lakedashboard "$board" -o json | python3 -c '
import json, sys
s = json.load(sys.stdin).get("status") or {}
q = {x["name"]: x for x in s.get("queries", [])}
sys.exit(0 if s.get("phase") == "Ready" and all(q.get(n, {}).get("refreshedAt") for n in ("rel_mv", "rel_dws")) else 1)'
}
prober_answering() {
  local path
  for path in live mv dws_dashboard; do
    kubectl -n "$bi" exec "$prober" -- grep -q "^$path .* OK " /out/doris.log || return 1
  done
}
events_producing() {
  [[ -s "$PERF_EVENTS_READY" ]] || return 1
  if [[ "$(cat "$PERF_EVENTS_READY")" != producing ]]; then
    # until_true silences the call: the reason goes to the run's log ($out, start's).
    echo "The events run did not start producing: $(cat "$PERF_EVENTS_READY")" >>"$out/release.log"
    exit 1
  fi
}

start() {
  local evidence="$1" db="$2" ods="$3" dwd="$4" dws="$5" out work rate version_rate host pid
  out="$evidence/release"
  mkdir -p "$out"
  rate="${CDC_LOAD_RATE:-184}" version_rate="${VERSION_RATE:-2}"
  if [[ ! "$rate" =~ ^[0-9]+$ ]] || ((rate < 10)); then
    echo "CDC_LOAD_RATE: changes a second, 10 at least" >&2
    exit 1
  fi
  if [[ ! "$version_rate" =~ ^[1-9]$ ]]; then
    echo "VERSION_RATE: keys a second, 1 to 9" >&2
    exit 1
  fi
  printf 'ods %s\ndwd %s\ndws %s\n' "$ods" "$dwd" "$dws" >"$out/tables"
  work="$(mktemp -d)"
  chmod 700 "$work"
  # shellcheck disable=SC2064  # the directory of this call
  trap "rm -rf '$work'" RETURN
  tools_configmap "$tools"
  observer_identity "$principal" "${ods%%.*}" "${dwd%%.*}" "${dws%%.*}"
  observer_start "$observer" "$principal" "$tools" \
    "rows=$ods,id,$version_first,version_no,amount,note" \
    "rows=$dwd,order_id,$version_first,version_no,amount" "rows=$dws"
  echo "observer $observer: $ods and $dwd from $version_first, $dws" | tee -a "$out/release.log"

  # The load's own MySQL user: it may change the orders, nothing else.
  openssl rand -hex 16 | tr -d '\n' >"$work/password"
  cat >"$work/user.sql" <<SQL
CREATE USER IF NOT EXISTS '$load_user'@'%' IDENTIFIED WITH caching_sha2_password BY '$(cat "$work/password")' REQUIRE SSL;
ALTER USER '$load_user'@'%' IDENTIFIED WITH caching_sha2_password BY '$(cat "$work/password")' REQUIRE SSL;
GRANT SELECT, INSERT, UPDATE, DELETE ON \`$db\`.\`orders\` TO '$load_user'@'%';
SQL
  bash "$FIXTURES_DIR/sources/sources.sh" mysql "$work/user.sql" >/dev/null
  kubectl -n "$JOB_NAMESPACE" create secret generic "$load" --from-file=password="$work/password" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  host="$(stack_output "$TEST_MYSQL_STACK" MySqlEndpoint)"
  # Above the dashboards run's writer (1,000,000 on), below the version markers.
  mysql_pod "$load" "$PERFORMANCE_DIR/cdc-load.sh" 300m "SOURCE_HOST=$host" "SOURCE_DB=$db" \
    "LOAD_USER=$load_user" "RATE=$rate" "START_ID=2000000"
  until_true 180 "the change load commits" load_running
  echo "change load $load: $rate changes a second on $db.orders" | tee -a "$out/release.log"
  mysql_pod "$versions" "$PERFORMANCE_DIR/versions.sh" 200m "SOURCE_HOST=$host" "SOURCE_DB=$db" \
    "LOAD_USER=$load_user" "RATE=$version_rate" "FIRST=$version_first" \
    "UPDATE_AFTER=$((60 * version_rate))" "DELETE_AFTER=$((120 * version_rate))"
  until_true 180 "the version markers commit" versions_running
  echo "version markers $versions: $version_rate keys a second from $version_first" \
    | tee -a "$out/release.log"

  # The version markers' Doris paths, to one user of their own.
  openssl rand -hex 16 | tr -d '\n' >"$work/prober"
  kubectl -n "$JOB_NAMESPACE" create secret generic "$board-prober" --from-file=password="$work/prober" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  local markers="SELECT COUNT(*) AS n, SUM(version_no) AS v, SUM(amount) AS a FROM $ods WHERE id >= $version_first AND id < $6"
  kubectl apply -f - >/dev/null <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeDashboard
metadata: {name: $board, namespace: $JOB_NAMESPACE}
spec:
  tables: [$ods, $dws]
  governance: {owner: perf-team, level: L2, retention: 7d}
  workload: {maxConcurrency: 8}
  queries:
    - {name: rel_live, serve: external, sql: "$markers"}
    - {name: rel_mv, serve: materialized, refresh: 1m, sql: "$markers"}
    - {name: rel_dws, serve: materialized, refresh: 1m,
       sql: "SELECT SUM(rel_live) AS n, SUM(rel_versions) AS v, SUM(rel_amount) AS a FROM $dws"}
  users:
    - {name: prober, passwordSecret: {name: $board-prober}, queries: [rel_live, rel_mv, rel_dws]}
YAML
  until_true 900 "dashboard $board ready, its copies refreshed" board_ready
  local database
  database="$(kubectl -n "$JOB_NAMESPACE" get lakedashboard "$board" -o jsonpath='{.status.database}')"
  kubectl -n "$bi" create secret generic "$prober" --from-file=password="$work/prober" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl -n "$bi" create configmap "$prober" --from-file=run.sh="$PERFORMANCE_DIR/doris-probe.sh" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl -n "$bi" delete pod "$prober" --ignore-not-found --wait=true >/dev/null
  kubectl -n "$bi" apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $prober, namespace: $bi, labels: {colvenna-lake.io/role: performance}}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  terminationGracePeriodSeconds: 5
  securityContext: {runAsNonRoot: true, runAsUser: 999, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: probe
      image: $MYSQL_CLIENT_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["bash", "/script/run.sh"]
      env:
        - {name: DORIS_HOST, value: "$DORIS_CLUSTER-fe-service.$DORIS_NAMESPACE.svc"}
        - {name: DATABASE, value: "$database"}
        - {name: PROBER, value: "$JOB_NAMESPACE.$board.prober"}
      resources: {requests: {cpu: 100m, memory: 128Mi}, limits: {memory: 256Mi}}
      volumeMounts:
        - {name: in, mountPath: /in, readOnly: true}
        - {name: script, mountPath: /script, readOnly: true}
        - {name: out, mountPath: /out}
  volumes:
    - {name: in, secret: {secretName: $prober}}
    - {name: script, configMap: {name: $prober}}
    - {name: out, emptyDir: {}}
YAML
  kubectl -n "$bi" wait --for=condition=Ready "pod/$prober" --timeout=180s >/dev/null
  until_true 180 "the prober answered on every path" prober_answering
  echo "dashboard $board and prober $prober: $database" | tee -a "$out/release.log"

  # The clocks the samples read: the nodes' of the version markers (MySQL's is checked against
  # them), the committing jobs (their TaskManagers), the prober, the observer and the load.
  pid="$(clock_sampler "$out/clocks.tsv" "clock-$run" \
    "$JOB_NAMESPACE/^(dash-$run|dash-dwd-$run|dash-dws-$run)-.*taskmanager" \
    "$JOB_NAMESPACE/^($versions|$observer|$load)\$" "$bi/^$prober\$")"
  echo "$pid" >"$sampler_pid"
  if [[ -n "${PERF_EVENTS_READY:-}" ]]; then
    until_true 1200 "the events run's producer" events_producing
    echo "the events run is producing" | tee -a "$out/release.log"
  fi
  date +%s%3N >"$out/started"
}

stop() {
  local evidence="$1" out status=0 ods
  out="$evidence/release"
  kubectl -n "$JOB_NAMESPACE" logs "$load" >"$out/cdc-load.log" 2>&1 || status=1
  echo "change load stopped: $(grep '^load ' "$out/cdc-load.log" | tail -1)" | tee -a "$out/release.log"
  # The version markers stop first and say so: every op they committed is then in their log.
  kubectl -n "$JOB_NAMESPACE" exec "$versions" -- touch /out/stop || status=1
  until_true 60 "the version markers stopped" kubectl -n "$JOB_NAMESPACE" exec "$versions" -- \
    test -e /out/stopped || status=1
  kubectl -n "$JOB_NAMESPACE" exec "$versions" -- cat /out/versions.log >"$out/versions.log" || status=1
  kubectl -n "$bi" exec "$prober" -- cat /out/doris.log >"$out/doris.log" || status=1
  kubectl -n "$JOB_NAMESPACE" delete pod "$load" "$versions" --ignore-not-found --wait=true >/dev/null \
    || status=1
  kubectl -n "$bi" delete pod "$prober" --ignore-not-found --wait=true >/dev/null || status=1
  echo "version markers: $(grep -c '^op ' "$out/versions.log") ops; prober: $(wc -l <"$out/doris.log") answers" \
    | tee -a "$out/release.log"
  observer_collect "$observer" 3 "$out/observed.jsonl" || status=1
  ods="$(awk '$1 == "ods" {print $2}' "$out/tables")"
  python3 - "$out/observed.jsonl" "$ods" >"$out/ods-orders.jsonl" <<'PY'
import json, sys
for line in open(sys.argv[1]):
    try:
        d = json.loads(line)
    except ValueError:
        continue
    if d.get("type") == "CHANGE" and d.get("table") == sys.argv[2]:
        sys.stdout.write(line)
PY
  echo "observer: $(wc -l <"$out/observed.jsonl") lines, $(wc -l <"$out/ods-orders.jsonl") of $ods" \
    | tee -a "$out/release.log"
  date +%s%3N >"$out/stopped"
  if [[ -n "${PERF_STOP_FILE:-}" ]]; then
    touch "$PERF_STOP_FILE"
  fi
  cleanup || status=1
  echo "$status" >"$out/stop-status"
  return "$status"
}

case "$command" in
  start)
    (($# == 8)) || { echo "$usage" >&2; exit 1; }
    start "$3" "$4" "$5" "$6" "$7" "$8"
    ;;
  stop)
    (($# == 3)) || { echo "$usage" >&2; exit 1; }
    stop "$3"
    ;;
  cleanup) cleanup ;;
  *) echo "$usage" >&2; exit 1 ;;
esac
