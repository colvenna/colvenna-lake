#!/usr/bin/env bash
# run_pod_out: run_pod (common.sh) for an output larger than a container's log keeps. The kubelet
# rotates a container's log past 10 MiB (containerLogMaxSize, checked every 10 s) and kubectl logs
# reads the current file only, so such an output read from the log can come back without its
# beginning, and look like data (#244). Here the pod writes the output to a file and stays up until
# it is copied out.

# The last line of the container script of a run_pod_out pod, once its command has written /out/out
# (an emptyDir at /out) and succeeded: records the file's size in /out/done, then keeps the pod up
# for 15 minutes at most. Deleting the pod ends it at once: sh as PID 1 ignores a SIGTERM it does
# not trap. On its own line of the script: a failed command must have stopped it before (sh -e).
# shellcheck disable=SC2016,SC2034  # expanded in the pod; used in the pod manifests of the callers
POD_OUT_DONE='wc -c </out/out >/out/size && mv /out/size /out/done && trap "exit 0" TERM && i=0 && while [ "$i" -lt 900 ]; do sleep 1; i=$((i + 1)); done'

# run_pod_out <namespace> <name> <manifest> [<seconds>]: runs a pod whose one container writes
# /out/out and ends its script with $POD_OUT_DONE; once the pod records the size (within 300
# seconds, as run_pod waits, unless given), prints the file as copied from the pod and deletes the
# pod. Fails with the pod's log when the pod fails or ends first, and when none of three copies
# comes back with the recorded size (printing nothing then).
run_pod_out() {
  local namespace="$1" name="$2" manifest="$3" end=$((SECONDS + ${4:-300})) phase size="" copy got
  kubectl -n "$namespace" delete pod "$name" --ignore-not-found --wait=true >/dev/null
  # A pod that never starts fails at once, also where the caller runs this with errexit off.
  kubectl -n "$namespace" apply -f "$manifest" >/dev/null || return 1
  while :; do
    phase="$(kubectl -n "$namespace" get pod "$name" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    if [[ "$phase" == Running ]]; then
      size="$(kubectl -n "$namespace" exec "$name" -- cat /out/done 2>/dev/null || true)"
      size="${size//[[:space:]]/}"
      [[ ! "$size" =~ ^[0-9]+$ ]] || break
    fi
    if [[ "$phase" == Succeeded || "$phase" == Failed ]] || ((SECONDS >= end)); then
      kubectl -n "$namespace" logs "$name" >&2 || true
      kubectl -n "$namespace" delete pod "$name" --ignore-not-found >/dev/null
      echo "Pod $name did not succeed (${phase:-no phase}): no output recorded in it" >&2
      return 1
    fi
    sleep 2
  done
  copy="$(mktemp)"
  for _ in 1 2 3; do
    if kubectl -n "$namespace" exec "$name" -- cat /out/out >"$copy"; then
      got="$(wc -c <"$copy")"
      ((got != size)) || break
      echo "Pod $name: a copy of its output came back with $got of $size bytes" >&2
    else
      got=-1
      echo "Pod $name: a copy of its output failed" >&2
    fi
  done
  kubectl -n "$namespace" delete pod "$name" --ignore-not-found >/dev/null
  if ((got != size)); then
    rm -f "$copy"
    echo "Pod $name: its output did not come back whole" >&2
    return 1
  fi
  cat "$copy"
  rm -f "$copy"
}
