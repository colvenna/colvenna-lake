#!/usr/bin/env bash
# What the release performance run's scripts (run-release.sh, release.sh, run-events.sh) share
# beyond tests/acceptance/common.sh: the account and cluster check, the tools jar
# (tests/performance/tools), the snapshot observer's pod and catalog identity, and the clock
# sampler.
# shellcheck disable=SC2034  # settings used by the scripts that source this file
# shellcheck source-path=SCRIPTDIR source=../acceptance/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/../acceptance/common.sh"
# shellcheck source=../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
PERFORMANCE_DIR="$TESTS_DIR/performance"
# Every script here works on the environment's account and cluster.
assert_account
ensure_kubeconfig

# tools_jar: the tools jar, PERF_TOOLS_JAR when run-release.sh built it already (two runs at once
# must not both clean the module's target/), else built here; prints its path.
tools_jar() {
  if [[ -n "${PERF_TOOLS_JAR:-}" ]]; then
    echo "$PERF_TOOLS_JAR"
    return
  fi
  mvn -B -q -f "$PERFORMANCE_DIR/tools/pom.xml" -DskipTests clean package >&2
  echo "$PERFORMANCE_DIR/tools/target/performance-tools-0.1.0.jar"
}

# tools_configmap <name>: the tools jar as ConfigMap <name> (key tools.jar) in the job namespace.
tools_configmap() {
  kubectl -n "$JOB_NAMESPACE" create configmap "$1" --from-file=tools.jar="$(tools_jar)" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
}

# observer_identity <principal> <database>...: a catalog identity of its own that reads those
# databases, its token in Secret <principal>-token of the job namespace (two days). The grants'
# approval names the run (PERF_APPROVAL, by default "release performance run <principal>").
observer_identity() {
  local principal="$1" database
  shift
  admin_open
  ensure_principal_token "$principal" "$JOB_NAMESPACE" "$principal-token" P2D
  for database in "$@"; do
    GRANT_PURPOSE="release performance evidence reads" GRANT_APPROVER=requester \
      GRANT_APPROVAL="${PERF_APPROVAL:-release performance run $principal}" GRANT_DAYS=2 \
      ensure_grant "$principal" DATABASE "$database" - READ
  done
  admin_close
}

# observer_withdraw <principal>: its grants and tokens revoked, its Secret deleted.
observer_withdraw() {
  admin_open
  revoke_principal "$1" || true
  admin_close
  kubectl -n "$JOB_NAMESPACE" delete secret "$1-token" --ignore-not-found >/dev/null
}

# observer_start <pod> <principal> <tools-configmap> <spec>...: the snapshot observer
# (tools/SnapshotObserver), on this commit's colvenna-jobs image, as the principal; it writes
# /out/observed.jsonl in the pod until observer_collect stops it.
observer_start() {
  local pod="$1" principal="$2" tools="$3" file
  shift 3
  file="$(mktemp)"
  cat >"$file" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $pod, labels: {colvenna-lake.io/role: performance}}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 9999, runAsGroup: 9999, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: observe
      image: $(image_ref mysql)
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      env:
        - {name: COLVENNA_CATALOG_URI, value: "https://colvenna-catalog.$CATALOG_NAMESPACE.svc:8181"}
        - {name: COLVENNA_CATALOG_TOKEN_FILE, value: /token/token}
      command: ["sh", "-c"]
      args:
        - |
          cp "\$JAVA_HOME/lib/security/cacerts" /tmp/cacerts && chmod 600 /tmp/cacerts
          keytool -importcert -noprompt -alias colvenna-catalog -file /ca/ca.crt -keystore /tmp/cacerts -storepass changeit >/dev/null
          java -Xmx1g -Djavax.net.ssl.trustStore=/tmp/cacerts -Djavax.net.ssl.trustStorePassword=changeit \
            -Dlog4j2.level=WARN -cp "/opt/flink/lib/*:/opt/flink/usrlib/*:/tools/tools.jar" \
            com.colvenna.lake.performance.SnapshotObserver /out/observed.jsonl /out/stop $* 2>/out/stderr
          echo "exit \$?" >/out/exited
          # Stays until deleted, so that the lines can be copied out.
          sleep 86400
      resources: {requests: {cpu: 500m, memory: 1536Mi}, limits: {memory: 1536Mi}}
      volumeMounts:
        - {name: token, mountPath: /token, readOnly: true}
        - {name: ca, mountPath: /ca, readOnly: true}
        - {name: tools, mountPath: /tools, readOnly: true}
        - {name: out, mountPath: /out}
  volumes:
    - {name: token, secret: {secretName: $principal-token}}
    - {name: ca, configMap: {name: colvenna-catalog-ca}}
    - {name: tools, configMap: {name: $tools}}
    - {name: out, emptyDir: {}}
YAML
  kubectl -n "$JOB_NAMESPACE" delete pod "$pod" --ignore-not-found --wait=true >/dev/null
  kubectl -n "$JOB_NAMESPACE" apply -f "$file" >/dev/null
  rm -f "$file"
  kubectl -n "$JOB_NAMESPACE" wait --for=condition=Ready "pod/$pod" --timeout=300s >/dev/null
}

# observer_collect <pod> <tables> <out-file>: stops the observer, waits (5 minutes at most) for
# the END line of each of its <tables>, copies its lines to <out-file> and deletes the pod. Fails
# when an END is missing (the lines are copied all the same).
observer_collect() {
  local pod="$1" tables="$2" out="$3" ends=0 status=0
  kubectl -n "$JOB_NAMESPACE" exec "$pod" -- touch /out/stop || status=1
  for _ in $(seq 1 60); do
    ends="$(kubectl -n "$JOB_NAMESPACE" exec "$pod" -- grep -c '"type":"END"' /out/observed.jsonl \
      2>/dev/null || true)"
    [[ "${ends:-0}" -ge "$tables" ]] && break
    sleep 5
  done
  kubectl -n "$JOB_NAMESPACE" exec "$pod" -- cat /out/observed.jsonl >"$out" || status=1
  kubectl -n "$JOB_NAMESPACE" exec "$pod" -- cat /out/stderr >"$out.stderr" 2>/dev/null || true
  kubectl -n "$JOB_NAMESPACE" delete pod "$pod" --ignore-not-found --wait=false >/dev/null
  if [[ "${ends:-0}" -lt "$tables" ]]; then
    echo "$pod: $ends of $tables tables ended" >&2
    status=1
  fi
  return "$status"
}

# The clock probes' image: BusyBox 1.37.0 (multi-arch index), whose adjtimex reads the kernel's
# clock discipline without privileges (deploy/images/third-party.tsv).
CLOCK_PROBE_IMAGE="$(third_party_image busybox)"

# clock_probe <probes> <node>: probe pod <probes>-<node's first name part>, pinned to the node, in
# the job namespace (made once; a new node gets its own on the sampler's next round); prints its
# name.
clock_probe() {
  local pod="$1-${2%%.*}"
  echo "$pod"
  kubectl -n "$JOB_NAMESPACE" get pod "$pod" >/dev/null 2>&1 && return 0
  kubectl -n "$JOB_NAMESPACE" apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $pod, labels: {colvenna-lake.io/clock-probe: "$1"}}
spec:
  nodeName: $2
  restartPolicy: Never
  automountServiceAccountToken: false
  terminationGracePeriodSeconds: 0
  securityContext: {runAsNonRoot: true, runAsUser: 65534, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: probe
      image: $CLOCK_PROBE_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["sleep", "86400"]
      resources: {requests: {cpu: 1m, memory: 8Mi}, limits: {memory: 16Mi}}
YAML
}

# clock_sampler <out-file> <probes> <namespace>/<pod-name-regex>...: in the background, once a
# minute, each node's clock as its kernel keeps it (adjtimex in a probe pod <probes>-<node> on it:
# synchronized or not, and the maximum error; the nodes' chrony follows Amazon Time Sync), and for
# every running pod matched the clock of its node, as lines
# "<ms> <namespace>/<pod> <node> <maximum-error-ms> <synchronized 1|0>" (perf.py: a sample that
# compares two clocks is valid only with both measured within 90 s of it, synchronized, their
# maximum errors summing to at most a second). Not kubectl exec's own timing: from the sampling
# machine its round trip is a second and more, too long to compare clocks with. Prints the
# sampler's pid; clock_probes_remove <probes> deletes the probes.
clock_sampler() {
  local out="$1" probes="$2"
  shift 2
  (
    # Only the loop's own descriptors: a copy of a caller's (bash saves stdout across a function's
    # redirection) would hold that caller's pipe open, and a command substitution reading it would
    # never end.
    for fd in /proc/"$BASHPID"/fd/*; do
      fd="${fd##*/}"
      if ((fd > 2)); then eval "exec $fd>&-" 2>/dev/null; fi
    done
    # A failed read is a missing sample, not the end of the sampling.
    set +e
    while true; do
      declare -A node_clock=()
      now="$(date +%s%3N)"
      for node in $(kubectl get nodes -o jsonpath='{.items[*].metadata.name}' 2>/dev/null); do
        probe="$(clock_probe "$probes" "$node")" || continue
        reading="$(kubectl -n "$JOB_NAMESPACE" exec "$probe" -- adjtimex 2>/dev/null)" || continue
        maxerror="$(awk '$1 == "maxerror:" {print $2}' <<<"$reading")"
        [[ "$maxerror" =~ ^[0-9]+$ ]] || continue
        synced=0
        [[ "$reading" == *"(clock synchronized)"* ]] && synced=1
        node_clock[$node]="$(((maxerror + 999) / 1000)) $synced"
      done
      for target in "$@"; do
        kubectl -n "${target%%/*}" get pods --field-selector=status.phase=Running \
          -o jsonpath='{range .items[*]}{.metadata.name} {.spec.nodeName}{"\n"}{end}' 2>/dev/null \
          | while read -r pod node; do
            [[ "$pod" =~ ${target#*/} ]] || continue
            echo "$now ${target%%/*}/$pod $node ${node_clock[$node]:-? 0}" >>"$out"
          done
      done
      unset node_clock
      sleep 60
    done
  ) </dev/null >/dev/null 2>&1 &
  echo $!
}

clock_probes_remove() {
  kubectl -n "$JOB_NAMESPACE" delete pod -l "colvenna-lake.io/clock-probe=$1" --ignore-not-found \
    --wait=false >/dev/null 2>&1 || true
}
