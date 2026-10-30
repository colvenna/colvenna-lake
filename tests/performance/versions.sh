#!/usr/bin/env bash
# The release performance run's version markers (release.sh starts this in a pod of the MySQL client
# image): RATE keys a second on the source's orders, from FIRST up, until /out/stop exists; then
# /out/stopped, once the last op is written down (every op committed is in the log, as perf.py's
# prefixes need). Key FIRST + j is inserted at tick j (version 1), updated at tick j + UPDATE_AFTER
# (version 2) and deleted at tick j + DELETE_AFTER, ticks RATE a second, so once warm every second
# brings RATE of each. Each op is a transaction of its own, then MySQL's clock; its row carries the
# version (version_no), a checksum of key and version (amount, perf.py checks it in every layer)
# and the op's sequence number (note).
# Lines, in /out/versions.log (perf.py layers reads them):
#   op <seq> I|U|D <key> <version> <cents> <source-ms> <pod-before-ms> <pod-after-ms>
#       committed; MySQL's clock right after it, the pod's before the call and on reading the answer
#       (MySQL's lies between: perf.py bounds the two clocks' difference by them)
#   opx <seq> I|U|D <key> <version> <cents>                        committed, its time unknown
#   skip <seq> I|U|D <key> <version> <cents>                       not committed (nor, for an insert,
#                                                                  the key's later ops)
# Environment: SOURCE_HOST SOURCE_DB LOAD_USER RATE FIRST UPDATE_AFTER DELETE_AFTER; the password in
# /in/password, the RDS CA in /ca.
set -u
ms() { date +%s%3N; }
pause() { if [ "$1" -gt 0 ]; then sleep "$(($1 / 1000)).$(printf '%03d' $(($1 % 1000)))"; fi; }
out=/out/versions.log
source_sql() {
  MYSQL_PWD="$(cat /in/password)" mysql --ssl-mode=VERIFY_IDENTITY --ssl-ca=/ca/ca.pem -h "$SOURCE_HOST" \
    -u "$LOAD_USER" "$SOURCE_DB" -N --batch "$@"
}
open_session() {
  coproc SRC { source_sql --unbuffered 2>&1; }
}
# checksum <key> <version>: the amount in cents, as perf.py computes it.
checksum() { echo $((($1 * 7919 + $2 * 104729) % 9999991)); }
decimal() { printf '%d.%02d' $(($1 / 100)) $(($1 % 100)); }
# committed <kind> <key>: whether the op is in the table (after a broken session).
committed() {
  local got
  case "$1" in
    I) got="$(source_sql -e "SELECT COUNT(*) FROM orders WHERE id = $2" 2>/dev/null)"; [ "$got" = 1 ] ;;
    U) got="$(source_sql -e "SELECT version_no FROM orders WHERE id = $2" 2>/dev/null)"; [ "$got" = 2 ] ;;
    D) got="$(source_sql -e "SELECT COUNT(*) FROM orders WHERE id = $2" 2>/dev/null)"; [ "$got" = 0 ] ;;
  esac
}
declare -A skipped=()
seq=0
# run_op <kind> <key> <version>
run_op() {
  local kind="$1" key="$2" version="$3" c sql got at before
  seq=$((seq + 1))
  c="$(checksum "$key" "$version")"
  if [ -n "${skipped[$key]:-}" ]; then
    echo "skip $seq $kind $key $version $c" >>"$out"
    return
  fi
  case "$kind" in
    I) sql="INSERT INTO orders (id, customer_id, amount, state, note, version_no) VALUES ($key, $((1 + key % 200)), $(decimal "$c"), 'created', 'v $seq', 1);" ;;
    U) sql="UPDATE orders SET amount = $(decimal "$c"), state = 'paid', note = 'v $seq', version_no = 2 WHERE id = $key;" ;;
    D) sql="DELETE FROM orders WHERE id = $key;" ;;
  esac
  got="" at="" before=$(ms)
  if [ -n "${SRC[1]:-}" ] && printf '%s SELECT %d, CAST(ROUND(UNIX_TIMESTAMP(NOW(6)) * 1000) AS UNSIGNED);\n' \
    "$sql" "$seq" 1>&"${SRC[1]}" 2>/dev/null && read -r -t 10 -u "${SRC[0]}" got at 2>/dev/null \
    && [ "$got" = "$seq" ]; then
    echo "op $seq $kind $key $version $c $at $before $(ms)" >>"$out"
    return
  fi
  # The session broke or the op failed: whether it is in the table decides.
  kill "${SRC_PID:-0}" 2>/dev/null
  wait "${SRC_PID:-0}" 2>/dev/null
  if committed "$kind" "$key"; then
    echo "opx $seq $kind $key $version $c" >>"$out"
  else
    echo "skip $seq $kind $key $version $c" >>"$out"
    [ "$kind" = I ] && skipped[$key]=1
  fi
  sleep 1
  open_session
}
open_session
period_us=$((1000000 / RATE))
start=$(ms)
j=0
while [ ! -e /out/stop ]; do
  pause $((start + j * period_us / 1000 - $(ms)))
  run_op I $((FIRST + j)) 1
  if [ "$j" -ge "$UPDATE_AFTER" ]; then run_op U $((FIRST + j - UPDATE_AFTER)) 2; fi
  if [ "$j" -ge "$DELETE_AFTER" ]; then run_op D $((FIRST + j - DELETE_AFTER)) 2; fi
  j=$((j + 1))
done
touch /out/stopped
# Stays until deleted, so that the log can be copied out.
sleep 86400
