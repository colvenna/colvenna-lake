#!/usr/bin/env bash
# The release performance run's Doris prober (release.sh starts it in a pod of the MySQL client
# image, in a Doris client namespace), until deleted: once a second each of the version markers'
# Doris paths, as the dashboard user PROBER:
#   live            rel_live, an external view over ODS
#   mv              rel_mv, a materialized view over ODS
#   dws_dashboard   rel_dws, a materialized view over DWS
# each answering the markers' totals (live keys, versions, amount). Lines, in /out/doris.log (perf.py
# layers reads them): "<path> <start-ms> <end-ms> OK|ERR <count> <versions> <amount>".
# Environment: DORIS_HOST DATABASE PROBER; its password in /in/password.
set -u
ms() { date +%s%3N; }
pause() { if [ "$1" -gt 0 ]; then sleep "$(($1 / 1000)).$(printf '%03d' $(($1 % 1000)))"; fi; }
# poll <path> <view>
poll() {
  local s e out st
  while true; do
    s=$(ms)
    if out=$(MYSQL_PWD="$(cat /in/password)" mysql -h "$DORIS_HOST" -P 9030 -u "$PROBER" \
      --connect-timeout=5 -N --batch -e "SET query_timeout = 30; SELECT n, v, a FROM $DATABASE.$2" 2>&1); then
      st=OK
    else
      st=ERR
    fi
    e=$(ms)
    echo "$1 $s $e $st $(head -1 <<<"$out" | tr '\t' ' ' | tr -s ' ' | cut -c1-160)" >>/out/doris.log
    pause $((1000 - (e - s)))
  done
}
poll live rel_live &
poll mv rel_mv &
poll dws_dashboard rel_dws &
wait
