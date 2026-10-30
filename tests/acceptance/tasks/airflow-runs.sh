#!/usr/bin/env bash
# In the tasks run's Airflow pod: a scheduler (SequentialExecutor, SQLite) with colvenna_lake_task.py,
# then one DAG run per conf in CONF_0..CONF_<RUNS-1>, in order, each to its end. Prints, per run,
# DAG_RUN <n> <state> and the COLVENNA_TASK lines and failures of its task logs; before that, the
# status an unauthenticated request to create a LakeTask gets (ANONYMOUS <code>).
set -uo pipefail
# The ConfigMap's volume links its files through ..data, which Airflow's DAG folder walk takes for
# a loop: its DAG processor stops. The DAG is read from a plain copy (AIRFLOW__CORE__DAGS_FOLDER).
mkdir -p "$AIRFLOW__CORE__DAGS_FOLDER"
cp -L /dags/colvenna_lake_task.py "$AIRFLOW__CORE__DAGS_FOLDER/"
airflow db migrate >/dev/null 2>&1
[[ -f /var/run/secrets/kubernetes.io/serviceaccount/namespace ]] && python - <<'PY'
import os, ssl, urllib.error, urllib.request
sa = "/var/run/secrets/kubernetes.io/serviceaccount"
ns = open(f"{sa}/namespace").read().strip()
url = f"https://kubernetes.default.svc/apis/colvenna-lake.io/v1alpha1/namespaces/{ns}/laketasks"
req = urllib.request.Request(url, data=b'{"kind":"LakeTask"}', method="POST",
                             headers={"Content-Type": "application/json"})
try:
    urllib.request.urlopen(req, context=ssl.create_default_context(cafile=f"{sa}/ca.crt"))
    print("ANONYMOUS 2xx")
except urllib.error.HTTPError as e:
    print(f"ANONYMOUS {e.code}")
except urllib.error.URLError as e:
    print(f"ANONYMOUS unreachable: {e.reason}")
PY
airflow scheduler >/tmp/scheduler.log 2>&1 &
end=$((SECONDS + 300))
# Each CLI call starts a whole Airflow process: look every 15 s, not more.
until grep -q '^colvenna_lake_task' <<<"$(airflow dags list -o plain 2>/dev/null)"; do
  ((SECONDS < end)) || { echo "DAG_MISSING the scheduler did not load colvenna_lake_task"; tail -20 /tmp/scheduler.log; exit 1; }
  sleep 15
done
airflow dags unpause colvenna_lake_task >/dev/null
for i in $(seq 0 $((RUNS - 1))); do
  conf="CONF_$i" run="colvenna-$i" state="" end=$((SECONDS + ${RUN_TIMEOUT:-3000}))
  airflow dags trigger colvenna_lake_task -r "$run" -c "${!conf}" >/dev/null
  while [[ "$state" != success && "$state" != failed ]]; do
    ((SECONDS < end)) || { state=timeout; break; }
    sleep 15
    state="$(airflow dags list-runs -d colvenna_lake_task -o json 2>/dev/null | python -c '
import json, sys
runs = [r for r in json.load(sys.stdin) if r["run_id"] == sys.argv[1]]
print(runs[0]["state"] if runs else "")' "$run")"
  done
  echo "DAG_RUN $i $state"
  cat /opt/airflow/logs/dag_id=colvenna_lake_task/run_id="$run"/task_id=*/attempt=*.log 2>/dev/null \
    | grep -E "COLVENNA_TASK|AirflowFailException|Marking task as" || true
done
