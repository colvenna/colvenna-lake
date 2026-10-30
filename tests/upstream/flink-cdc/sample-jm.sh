#!/usr/bin/env bash
# The JobManager's heap of a source's job, once a minute, while an acceptance run goes
# on (snapshot split metadata stays in the enumerator's state on the JobManager; its release can
# only be observed there). Lines "<utc> <pod> <heap used> <heap max>" until <minutes> pass.
#   sample-jm.sh <namespace> <deployment name prefix> <out-file> <minutes>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../../acceptance/common.sh
source "$(dirname "$0")/../../acceptance/common.sh"
ensure_kubeconfig
namespace="${1:?usage: sample-jm.sh <namespace> <prefix> <out> <minutes>}"
prefix="${2:?usage: sample-jm.sh <namespace> <prefix> <out> <minutes>}"
out="${3:?usage: sample-jm.sh <namespace> <prefix> <out> <minutes>}"
end=$((SECONDS + ${4:?usage: sample-jm.sh <namespace> <prefix> <out> <minutes>} * 60))
while ((SECONDS < end)); do
  # No pod yet (or between restarts) is no sample, not the end: common.sh sets errexit and
  # pipefail, so grep finding nothing would end the loop.
  pod="$(kubectl -n "$namespace" get pods -o name 2>/dev/null | grep "^pod/$prefix" | grep -v taskmanager | head -1 || true)"
  if [[ -n "$pod" ]]; then
    heap="$(kubectl -n "$namespace" exec "${pod#pod/}" -c flink-main-container -- \
      curl -s "localhost:8081/jobmanager/metrics?get=Status.JVM.Memory.Heap.Used,Status.JVM.Memory.Heap.Max" 2>/dev/null \
      | python3 -c 'import json,sys; m={x["id"]: x["value"] for x in json.load(sys.stdin)}; print(m.get("Status.JVM.Memory.Heap.Used"), m.get("Status.JVM.Memory.Heap.Max"))' 2>/dev/null || true)"
    [[ -n "$heap" ]] && echo "$(date -u +%FT%TZ) ${pod#pod/} $heap" >>"$out"
  fi
  sleep 60
done
