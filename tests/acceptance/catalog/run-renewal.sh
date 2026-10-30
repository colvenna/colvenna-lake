#!/usr/bin/env bash
# Data-token renewal acceptance. With data tokens that live PT1H30M (the shortest the catalog
# allows), three clients run concurrently past the first token's expiry:
#   - Flink: a checkpointed streaming write for 100 minutes on a session cluster, so TaskManagers
#     write the files with their own tokens; restarts are disabled, so a lapse fails the run;
#   - Spark: one session that writes and reads, waits 95 minutes, then writes and reads again, also
#     through a table object held across the wait;
#   - Doris: the read-only checks, repeated 95 minutes later on the same catalog.
# Afterwards the catalog audit must show, for each engine identity, a table whose credentials were
# renewed: a fresh issuance at least 15 minutes after an earlier one and before that one expired.
# 15 minutes is past any start-up burst, and below both renewal points: Paimon's RESTTokenFileIO
# refreshes once less than an hour is left (30 minutes in), and a client that asks per query, as
# Doris does, gets a fresh token once the cached one has 70 minutes or less (20 minutes in).
# An idle client renews only on its next use, after the expiry, so the gate applies to the clients
# that keep using their credentials: in this run Flink, whose write never pauses. Spark and Doris,
# idle through their wait, must instead pass their second round, after the expiry. With
# RENEWAL_ACTIVE=true, Spark and Doris read every 5 minutes for 40 minutes, Flink sits the run out,
# and the gate applies to Spark and Doris.
# The catalog must be installed with DATA_TOKEN_DURATION=PT1H30M.
# The three run at once on two write and two query nodes beside the platform only with smaller
# requests than each flow's own, the Flink session cluster on the write nodes and Spark beside Doris
# on the query nodes. At their defaults the second TaskManager found no room; left anywhere, the
# session cluster took the query node the Doris BE needed; with Spark on the write nodes too, they
# had not the memory (2026-10-21).
# Usage: run-renewal.sh <run-id> <database-of-a-finished-flow-run> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
assert_account
ensure_kubeconfig
run_id="${1:?usage: run-renewal.sh <run-id> <database> <evidence-dir>}"
read_db="${2:?usage: run-renewal.sh <run-id> <database> <evidence-dir>}"
evidence="${3:?usage: run-renewal.sh <run-id> <database> <evidence-dir>}"
mkdir -p "$evidence"
flows="$(dirname "$0")"
export DORIS_FLOW_CPU="${DORIS_FLOW_CPU:-1}" SPARK_FLOW_CPU="${SPARK_FLOW_CPU:-500m}" FLOW_CLIENT_CPU="${FLOW_CLIENT_CPU:-500m}"
export FLOW_JM_CPU="${FLOW_JM_CPU:-0.5}" FLOW_TM_CPU="${FLOW_TM_CPU:-0.5}" FLOW_NODE_ROLE="${FLOW_NODE_ROLE:-write}"
export SPARK_FLOW_NODE_ROLE="${SPARK_FLOW_NODE_ROLE:-query}"
duration="$(kubectl -n "$CATALOG_NAMESPACE" get configmap colvenna-catalog-config \
  -o jsonpath='{.data.COLVENNA_DATA_TOKEN_DURATION}')"
if [[ "$duration" != PT1H30M ]]; then
  echo "Install the catalog with DATA_TOKEN_DURATION=PT1H30M first (now: $duration)" >&2
  exit 1
fi
db="renew_${run_id//-/_}"
start="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "start=$start database=$db token-lifetime=$duration" | tee "$evidence/run.txt"
declare -A pids

engines=(flink spark doris)
gated=(test-flink)
spark_args="$db 95"
doris_every=""
doris_minutes=95
if [[ "${RENEWAL_ACTIVE:-false}" == true ]]; then
  engines=(spark doris)
  gated=(test-spark doris)
  spark_args="$db 40 5"
  doris_every=5
  doris_minutes=40
else
  FLOW_DEADLINE=7800 bash "$flows/run-flink-deployment.sh" "$run_id-renew" "$evidence/flink" \
    com.colvenna.lake.acceptance.flow.FlinkRenewalFlow "$db" 100 90 >"$evidence/flink-run.log" 2>&1 &
  pids[flink]=$!
fi
FLOW_CLASS=com.colvenna.lake.acceptance.flow.SparkRenewalFlow FLOW_ARGS="$spark_args" \
  FLOW_DEADLINE=7800 bash "$flows/run-spark-flow.sh" "$run_id-renew" "$db" "$evidence/spark" \
  >"$evidence/spark-run.log" 2>&1 &
pids[spark]=$!
DORIS_RECHECK_AFTER_MINUTES="$doris_minutes" DORIS_RECHECK_EVERY_MINUTES="$doris_every" \
  bash "$flows/run-doris-flow.sh" "$run_id-renew" "$read_db" "$evidence/doris" \
  >"$evidence/doris-run.log" 2>&1 &
pids[doris]=$!
status=0
for engine in "${engines[@]}"; do
  if wait "${pids[$engine]}"; then
    echo "$engine PASS" | tee -a "$evidence/run.txt"
  else
    echo "$engine FAIL" | tee -a "$evidence/run.txt"
    status=1
  fi
done

# Fresh (not cached) issuances per engine identity since the start, from the catalog audit.
work="$(mktemp -d)"
chmod 700 "$work"
trap 'rm -rf "$work"' EXIT
cat >"$work/audit.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata:
  name: colvenna-catalog-audit-renewal
spec:
  restartPolicy: Never
  securityContext:
    runAsNonRoot: true
    runAsUser: 70
    seccompProfile: {type: RuntimeDefault}
  containers:
    - name: psql
      image: $POSTGRES_IMAGE
      securityContext:
        allowPrivilegeEscalation: false
        capabilities: {drop: ["ALL"]}
      env:
        - {name: PGHOST, value: "$(stack_output "$FOUNDATION_STACK" CatalogEndpoint)"}
        - {name: PGDATABASE, value: colvenna_catalog}
        - {name: PGSSLMODE, value: verify-full}
        - {name: PGSSLROOTCERT, value: /ca/ca.pem}
        - name: PGUSER
          valueFrom: {secretKeyRef: {name: colvenna-catalog-db, key: username}}
        - name: PGPASSWORD
          valueFrom: {secretKeyRef: {name: colvenna-catalog-db, key: password}}
      command: ["psql", "-v", "ON_ERROR_STOP=1", "-At", "-F", " ", "-c"]
      args:
        - >-
          WITH fresh AS (SELECT principal, resource, event_time,
          (detail->>'expiresAt')::timestamptz AS expires FROM audit_events
          WHERE action = 'ISSUE_DATA_TOKEN' AND outcome = 'SUCCEEDED'
          AND detail->>'cached' = 'false' AND event_time >= '$start')
          SELECT a.principal, a.resource, count(DISTINCT b.event_time)
          FROM fresh a JOIN fresh b ON a.principal = b.principal AND a.resource = b.resource
          AND b.event_time >= a.event_time + interval '15 minutes' AND b.event_time < a.expires
          GROUP BY 1, 2 ORDER BY 1, 2
      volumeMounts:
        - {name: ca, mountPath: /ca}
  volumes:
    - name: ca
      configMap: {name: colvenna-catalog-db-ca}
YAML
run_pod "$CATALOG_NAMESPACE" colvenna-catalog-audit-renewal "$work/audit.yaml" \
  | tee "$evidence/issuances.txt"
for principal in "${gated[@]}"; do
  renewed="$(awk -v p="$principal" '$1 == p {print $2}' "$evidence/issuances.txt" | tr '\n' ' ')"
  if [[ -z "$renewed" ]]; then
    echo "$principal: no table renewed before its credentials expired" | tee -a "$evidence/run.txt"
    status=1
  else
    echo "$principal: renewed before expiry for $renewed" | tee -a "$evidence/run.txt"
  fi
done
[[ "$status" == 0 ]] && echo "Renewal PASS" | tee -a "$evidence/run.txt"
exit "$status"
