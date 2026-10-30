#!/usr/bin/env bash
# The dashboards run's ad-hoc burst (run-dashboards.sh): for MINUTES, each of the ad-hoc
# dashboard's users runs its heavy query on three connections at once, back to back (Doris lets
# each two); the users marked normal first switch to Doris's normal workload group, as any user
# may. Lines: "<user> <start-ms> <end-ms> OK|ERR <first value>". What ran at once in each group is
# judged from Doris's audit log (dashboards.py burst).
# Environment: DORIS_HOST DATABASE MINUTES USERS ("<doris user>:<password file>:own|normal ...").
# shellcheck source-path=SCRIPTDIR
set -u
# shellcheck source=pod-lib.sh
source "$(dirname "$0")/lib.sh"
end=$(($(date +%s) + MINUTES * 60))
for spec in $USERS; do
  user="${spec%%:*}"
  rest="${spec#*:}"
  file="${rest%%:*}"
  mode="${rest#*:}"
  group=""
  if [ "$mode" = normal ]; then group="SET workload_group = 'normal';"; fi
  for _ in 1 2 3; do
    (
      while [ "$(date +%s)" -lt "$end" ]; do
        s=$(ms)
        if out=$(MYSQL_PWD="$(cat "$file")" mysql -h "$DORIS_HOST" -P 9030 -u "$user" --connect-timeout=10 \
          -N --batch -e "SET query_timeout = 120; $group SELECT region, pairs, amount FROM $DATABASE.explore" 2>&1); then
          st=OK
        else
          st=ERR
        fi
        echo "$user $s $(ms) $st $(head -1 <<<"$out" | tr ' \t' '__' | cut -c1-100)"
        if [ "$st" = ERR ]; then sleep 1; fi
      done
    ) &
  done
done
wait
echo "burst done"
