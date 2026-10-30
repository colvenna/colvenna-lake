#!/usr/bin/env bash
# The Trino that stands in for a user's existing one (install-trino.sh), and how to query
# it: through `kubectl exec`, so only an IAM-authenticated cluster user can.
# shellcheck disable=SC2034  # settings used by the scripts that source this file
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "${BASH_SOURCE[0]}")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/polaris-admin.sh
source "$REPO_DIR/deploy/scripts/lib/polaris-admin.sh"

TRINO_NAMESPACE=colvenna-trino
TRINO_IMAGE="$(third_party_image trino)"

# trino_sql <out-file> <sql> [<trino user>]: the statement's rows as CSV, or "ERROR <message>"
# when it fails; returns its exit code.
trino_sql() {
  local out="$1" sql="$2" user="${3:-analyst}" code=0
  kubectl -n "$TRINO_NAMESPACE" exec deploy/trino -c trino -- \
    trino --debug --user "$user" --output-format CSV_UNQUOTED --execute "$sql" >"$out" 2>"$out.err" \
    || code=$?
  if ((code != 0)); then
    # The failure and its causes: Trino's own message rarely says why (a 401, a 403, S3).
    printf 'ERROR %s\n' "$({ head -1 "$out.err"; grep -E '^Caused by: ' "$out.err"; } | tr '\n' ' ' \
      | cut -c1-1500)" >"$out"
  fi
  rm -f "$out.err"
  return "$code"
}
