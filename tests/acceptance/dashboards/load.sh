#!/usr/bin/env bash
# The dashboards run's load pod (run-dashboards.sh): for MINUTES, each second 10 fixed dashboard
# queries (totals), 5 point lookups of the internal table (hot, as ops) and 1 interactive lookup
# (lookup), open-loop: each query is sent when it is due, whether or not the ones before it
# answered, so a slow Doris cannot slow the load. Lines (dashboards.py load):
#   <kind> <due-ms> <sent> <answered> OK|ERR|SKIP [<first value of an error>]
# sent, answered: ms after due, when its client started and when it ended. The last line,
#   load done totals=<n> hot=<n> lookup=<n>
# counts the lines of each kind written before it: a log that lost lines (the kubelet keeps 10 MiB of
# a container's output, and the pod writes about 8 MiB in two hours) does not match it, and judges
# nothing. A response time counts from when the query was due, so a loop that falls behind its
# schedule adds its own lag to it: dashboards.py judges the schedule kept.
# Besides the clients, the loop starts only its pauses and, once a second, the count of the
# queries outstanding (each query also ran cat, date and a pipeline before: 16 queries a second
# needed more than the pod's 500m, and on a busy node the loop fell up to a minute behind).
# SKIP: more than 300 queries outstanding; none of the tick's queries is sent, and one totals line
# stands for them (sent and answered: when it was skipped), counted as an error.
# Environment: DORIS_HOST DATABASE ANALYST OPS MINUTES; passwords in /in (analyst, ops).
# shellcheck source-path=SCRIPTDIR
set -u
# shellcheck source=pod-lib.sh
source "$(dirname "$0")/lib.sh"

analyst_password="$(</in/analyst)"
ops_password="$(</in/ops)"

# fire <kind> <due-ms> <user> <password> <sql>
fire() {
  local out st sent answered first
  now sent
  if out=$(MYSQL_PWD="$4" mysql -h "$DORIS_HOST" -P 9030 -u "$3" --connect-timeout=10 \
    -N --batch -e "SET query_timeout = 60; $5" 2>&1); then
    st=OK
  else
    st=ERR
  fi
  now answered
  if [ "$st" = OK ]; then
    echo "$1 $2 $((sent - $2)) $((answered - $2)) OK"
  else
    first="${out%%$'\n'*}"
    first="${first%%$'\t'*}"
    first="${first// /_}"
    echo "$1 $2 $((sent - $2)) $((answered - $2)) ERR ${first:0:80}"
  fi
}

# now sets them (named here for set -u).
start=0 t=0
now start
end=$((start + MINUTES * 60000))
tick=0
outstanding=0
totals=0 hot=0 lookup=0
while true; do
  due=$((start + tick * 100))
  [ "$due" -ge "$end" ] && break
  now t
  pause $((due - t))
  # How many are still out, once a second (a pipeline: two processes).
  [ $((tick % 10)) -eq 0 ] && outstanding=$(jobs -rp | wc -l)
  if [ "$outstanding" -lt 300 ]; then
    fire totals "$due" "$ANALYST" "$analyst_password" \
      "SELECT region, orders, amount FROM $DATABASE.region_totals ORDER BY region" &
    totals=$((totals + 1))
    if [ $((tick % 2)) -eq 0 ]; then
      fire hot "$due" "$OPS" "$ops_password" \
        "SELECT name, region FROM $DATABASE.customers_hot WHERE id = $((RANDOM % 200 + 1))" &
      hot=$((hot + 1))
    fi
    if [ $((tick % 10)) -eq 0 ]; then
      fire lookup "$due" "$ANALYST" "$analyst_password" \
        "SELECT region, amount FROM $DATABASE.order_lookup WHERE order_id = $((RANDOM % 5000 + 1))" &
      lookup=$((lookup + 1))
    fi
  else
    now t
    echo "totals $due $((t - due)) $((t - due)) SKIP"
    totals=$((totals + 1))
  fi
  tick=$((tick + 1))
done
wait
echo "load done totals=$totals hot=$hot lookup=$lookup"
