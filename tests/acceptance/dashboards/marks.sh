#!/usr/bin/env bash
# The dashboards run's marker pod (run-dashboards.sh), until deleted: a source transaction every
# tenth of a second, each one order marked by its id (from BASE), and each read path polled once a
# second.
# Lines (dashboards.py reads them):
#   mark <id> <source-ms> <pod-ms>    committed; MySQL's clock right after it, the pod's on reading
#   markx <id>                        committed, its time unknown (the session broke)
#   skip <id>                         not committed
#   <path> <start-ms> <end-ms> OK|ERR <values>
#       live, mv, dws, hotm: the markers the path holds (count, highest id) through the external
#       view, the materialized view over ODS, the one over DWS and the internal table; totals: the
#       dashboard's rows; hot: the internal dimension's
# Environment: SOURCE_HOST SOURCE_DB MARK_USER DORIS_HOST DATABASE ANALYST OPS BASE; passwords in
# /in (marker, analyst, ops), the RDS CA in /ca.
# shellcheck source-path=SCRIPTDIR
set -u
# shellcheck source=pod-lib.sh
source "$(dirname "$0")/lib.sh"

# poll <path> <user> <password-file> <sql>
poll() {
  local s e out st
  while true; do
    s=$(ms)
    if out=$(MYSQL_PWD="$(cat "$3")" mysql -h "$DORIS_HOST" -P 9030 -u "$2" --connect-timeout=5 \
      -N --batch -e "SET query_timeout = 30; $4" 2>&1); then
      st=OK
    else
      st=ERR
    fi
    e=$(ms)
    echo "$1 $s $e $st $(head -1 <<<"$out" | tr '\t' ' ' | tr -s ' ' | cut -c1-160)"
    pause $((1000 - (e - s)))
  done
}
poll live "$ANALYST" /in/analyst "SELECT n, id FROM $DATABASE.marker_live" &
poll mv "$ANALYST" /in/analyst "SELECT n, id FROM $DATABASE.marker_mv" &
poll dws "$ANALYST" /in/analyst "SELECT n, id FROM $DATABASE.marker_dws" &
poll totals "$ANALYST" /in/analyst "SELECT COUNT(*), SUM(orders) FROM $DATABASE.region_totals" &
poll hot "$OPS" /in/ops "SELECT COUNT(*) FROM $DATABASE.customers_hot" &
poll hotm "$OPS" /in/ops "SELECT n, id FROM $DATABASE.marker_hot" &

source_sql() {
  MYSQL_PWD="$(cat /in/marker)" mysql --ssl-mode=VERIFY_IDENTITY --ssl-ca=/ca/ca.pem -h "$SOURCE_HOST" \
    -u "$MARK_USER" "$SOURCE_DB" -N --batch "$@"
}
# One session: each marker an autocommitted insert, then the source's clock.
open_session() {
  coproc SRC { source_sql --unbuffered 2>&1; }
}
open_session
id="$BASE"
due=$(ms)
while true; do
  got="" at=""
  # A session that died is gone from SRC: no write to it (set -u would end the markers), a reconnect.
  if [ -n "${SRC[1]:-}" ] && printf "INSERT INTO orders (id, customer_id, amount, state, version_no) VALUES (%d, %d, 0, 'created', 1); SELECT %d, CAST(ROUND(UNIX_TIMESTAMP(NOW(6)) * 1000) AS UNSIGNED);\n" \
    "$id" $((1 + id % 200)) "$id" 1>&"${SRC[1]}" 2>/dev/null \
    && read -r -t 10 -u "${SRC[0]}" got at 2>/dev/null && [ "$got" = "$id" ]; then
    echo "mark $id $at $(ms)"
  else
    # The session broke or the insert failed: whether the order is there decides.
    kill "${SRC_PID:-0}" 2>/dev/null
    wait "${SRC_PID:-0}" 2>/dev/null
    if [ "$(source_sql -e "SELECT COUNT(*) FROM orders WHERE id = $id" 2>/dev/null)" = 1 ]; then
      echo "markx $id"
    else
      echo "skip $id"
    fi
    sleep 1
    open_session
  fi
  id=$((id + 1))
  due=$((due + 100))
  pause $((due - $(ms)))
done
