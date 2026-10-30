#!/usr/bin/env bash
# What the dashboards run's pod scripts (marks.sh, load.sh, burst.sh) share; bi_pod mounts it
# beside them.

# now <var>: sets <var> to the pod's clock in milliseconds without starting a process (bash's
# EPOCHREALTIME), for the load pod's loop. Its own variable's name is one no caller passes.
now() { local now_us="${EPOCHREALTIME/[.,]/}"; printf -v "$1" '%s' "${now_us%???}"; }

# The pod's clock in milliseconds, on stdout.
ms() { local ms_now; now ms_now; echo "$ms_now"; }

# pause <ms>: sleeps that long, if it is more than nothing.
pause() {
  local s
  if [ "$1" -gt 0 ]; then
    printf -v s '%d.%03d' $(($1 / 1000)) $(($1 % 1000))
    sleep "$s"
  fi
}
