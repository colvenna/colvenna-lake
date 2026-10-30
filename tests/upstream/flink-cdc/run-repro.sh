#!/usr/bin/env bash
# Local checks of Flink CDC versions (repro/, repro-pg/), each against a
# fresh source in Docker on this machine (synthetic rows, loopback only):
#   run-repro.sh <evidence-dir> failover [<version>...]  FLINK-40741 on MySQL: a table added on
#                                                         restore, then a failover (ITERATIONS runs
#                                                         a version, default 5: the loss depends on
#                                                         where the failover lands)
#   run-repro.sh <evidence-dir> pg-leaf [<version>...]   the same question for a PostgreSQL leaf
#                                                         partition (NEWLY=true snapshots it)
#   run-repro.sh <evidence-dir> binary [<version>...]    VARBINARY keys and non-UTF-8 bytes
#   run-repro.sh <evidence-dir> upgrade <from> <to>      a savepoint of one version restored in
#                                                         the other
# Versions default to 3.5.0 and 3.7.0-1.20; CHECKPOINT_MS (default 1000) sets the jobs' checkpoint
# interval. Writes <evidence>/<mode>/result-*.txt, each run's logs and summary.txt; exits non-zero
# if any run failed.
# shellcheck source-path=SCRIPTDIR
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
usage="usage: run-repro.sh <evidence-dir> failover|pg-leaf|binary [<version>...] | upgrade <from> <to>"
evidence="${1:?$usage}"
mode="${2:?$usage}"
shift 2
(($# > 0)) || set -- 3.5.0 3.7.0-1.20
iterations="${ITERATIONS:-5}" checkpoint="${CHECKPOINT_MS:-1000}" newly="${NEWLY:-false}"
out="$evidence/$mode"
mkdir -p "$out"
name="cdc37-repro-$$"
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT

# shellcheck source=lib.sh
source "$here/lib.sh"

# test_run <module> <class> <version> <label> <property>...: one run; its result line goes to the
# summary.
status=0
test_run() {
  local module="$1" class="$2" version="$3" label="$4" rc=0 result
  shift 4
  rm -f "$here/$module/target/result-$version.txt"
  (cd "$here/$module" && mvn -B -q test "-Dtest=$class" "-Dcdc.version=$version" \
    "-Dcheckpoint.ms=$checkpoint" "$@") >"$out/mvn-$label.log" 2>&1 || rc=$?
  cp "$here/$module/target/repro.log" "$out/repro-$label.log" 2>/dev/null || true
  result="$(cat "$here/$module/target/result-$version.txt" 2>/dev/null \
    || echo "RESULT cdc=$version no result (mvn exit $rc)")"
  echo "$result" >"$out/result-$label.txt"
  printf '%s\n' "$result" | while IFS= read -r line; do echo "$label $line"; done | tee -a "$out/summary.txt"
  ((rc == 0)) || status=1
}

case "$mode" in
  failover)
    for version in "$@"; do
      for n in $(seq 1 "$iterations"); do
        port="$(mysql_up "$name")"
        test_run repro NewlyAddedFailoverTest "$version" "$version-$checkpoint-$n" "-Dmysql.port=$port"
      done
    done
    ;;
  pg-leaf)
    for version in "$@"; do
      for n in $(seq 1 "$iterations"); do
        port="$(postgres_up "$name")"
        test_run repro-pg PostgresLeafFailoverTest "$version" "$version-newly-$newly-$n" "-Dpg.port=$port" \
          "-Dpg.container=$name" "-Dpg.newly=$newly"
      done
    done
    ;;
  binary)
    # ASCII bytes: Flink CDC's split on a binary key alone; high bytes: Paimon's decoding too.
    for version in "$@"; do
      for ascii in true false; do
        port="$(mysql_up "$name")"
        test_run repro BinaryKeyTest "$version" "$version-ascii-$ascii" "-Dmysql.port=$port" \
          "-Dbinary.ascii=$ascii"
      done
    done
    ;;
  upgrade)
    from="${1:?$usage}" to="${2:?$usage}"
    port="$(mysql_up "$name")"
    state="$(mktemp -d)"
    test_run repro UpgradeTest "$from" "before-$from" "-Dmysql.port=$port" -Dphase=before "-Dstate.dir=$state"
    test_run repro UpgradeTest "$to" "after-$to" "-Dmysql.port=$port" -Dphase=after "-Dstate.dir=$state"
    rm -rf "$state"
    ;;
  *) echo "$usage" >&2; exit 1 ;;
esac
exit "$status"
