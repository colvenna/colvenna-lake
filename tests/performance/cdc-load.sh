#!/usr/bin/env bash
# The release performance run's change load (release.sh starts it in a pod of the MySQL client
# image), until deleted: RATE changes a second on the source's orders, open-loop, in transactions of
# ten once warm. Transaction k (from 0) inserts the orders START_ID + 5k .. START_ID + 5k + 4; from
# k = 100 on it also updates the last three of transaction k - 100's (a version up each), and from
# k = 1000 on it deletes the first two of transaction k - 1000's. So every statement changes the
# rows it names (a transaction that did not commit is left out of those after it), keys are never
# reused (the source contract), and the table grows by three orders a transaction.
# Lines (perf.py rate):
#   load <ms> <transactions> <changes> <late-ms> <failed>
#       every second: committed so far, how far behind its schedule the latest transaction was, and
#       how many did not commit so far
#   fail <k>                                       not committed (the session broke); not counted
# Environment: SOURCE_HOST SOURCE_DB LOAD_USER RATE START_ID; the password in /in/password, the RDS
# CA in /ca.
set -u
ms() { date +%s%3N; }
pause() { if [ "$1" -gt 0 ]; then sleep "$(($1 / 1000)).$(printf '%03d' $(($1 % 1000)))"; fi; }
source_sql() {
  MYSQL_PWD="$(cat /in/password)" mysql --ssl-mode=VERIFY_IDENTITY --ssl-ca=/ca/ca.pem -h "$SOURCE_HOST" \
    -u "$LOAD_USER" "$SOURCE_DB" -N --batch "$@"
}
open_session() {
  coproc SRC { source_sql --unbuffered 2>&1; }
}
open_session
declare -A failed=()
# Ten changes a transaction once warm: RATE / 10 transactions a second, one due every 10000 / RATE ms.
period_us=$((10000000 / RATE))
start=$(ms)
k=0 committed=0 changes=0 late=0 fails=0 next_line=$((start + 1000))
while true; do
  due=$((start + k * period_us / 1000))
  pause $((due - $(ms)))
  now=$(ms)
  late=$((now - due))
  first=$((START_ID + 5 * k))
  sql="START TRANSACTION; INSERT INTO orders (id, customer_id, amount, state, version_no) VALUES"
  for i in 0 1 2 3 4; do
    id=$((first + i))
    sql+=" ($id, $((1 + id % 200)), $((id % 1000)).50, 'created', 1)"
    [ "$i" -lt 4 ] && sql+=","
  done
  sql+=";"
  n=5
  if [ "$k" -ge 100 ] && [ -z "${failed[$((k - 100))]:-}" ]; then
    u=$((START_ID + 5 * (k - 100)))
    sql+=" UPDATE orders SET amount = amount + 1, state = 'paid', version_no = version_no + 1 WHERE id IN ($((u + 2)), $((u + 3)), $((u + 4)));"
    n=$((n + 3))
  fi
  if [ "$k" -ge 1000 ] && [ -z "${failed[$((k - 1000))]:-}" ]; then
    d=$((START_ID + 5 * (k - 1000)))
    sql+=" DELETE FROM orders WHERE id IN ($d, $((d + 1)));"
    n=$((n + 2))
  fi
  sql+=" COMMIT; SELECT $k;"
  got=""
  # A session that died is gone from SRC: no write to it (set -u would end the load), a reconnect.
  if [ -n "${SRC[1]:-}" ] && printf '%s\n' "$sql" 1>&"${SRC[1]}" 2>/dev/null \
    && read -r -t 10 -u "${SRC[0]}" got 2>/dev/null && [ "$got" = "$k" ]; then
    committed=$((committed + 1))
    changes=$((changes + n))
  else
    # The session broke or the transaction failed: whether its first order is there decides.
    kill "${SRC_PID:-0}" 2>/dev/null
    wait "${SRC_PID:-0}" 2>/dev/null
    if [ "$(source_sql -e "SELECT COUNT(*) FROM orders WHERE id = $first" 2>/dev/null)" = 1 ]; then
      committed=$((committed + 1))
      changes=$((changes + n))
    else
      echo "fail $k"
      failed[$k]=1
      fails=$((fails + 1))
    fi
    sleep 1
    open_session
  fi
  k=$((k + 1))
  if [ "$now" -ge "$next_line" ]; then
    echo "load $now $committed $changes $late $fails"
    next_line=$((next_line + 1000))
  fi
done
