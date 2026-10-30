#!/usr/bin/env bash
# Marquez, the lineage catalog the operator reports to (LLD "血缘"), and a port-forward to its
# API: Marquez has no authentication of its own, so it is reachable only inside the cluster from
# the namespaces labelled LINEAGE_CLIENT_LABEL, and from here through the Kubernetes API (IAM).
# shellcheck disable=SC2034  # settings used by the scripts that source this file
# shellcheck source-path=SCRIPTDIR
# shellcheck source=common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

LINEAGE_NAMESPACE=colvenna-lineage
LINEAGE_CLIENT_LABEL=colvenna-lake.io/lineage-client
# Marquez 0.51.1, its API and its web UI (deploy/images/third-party.tsv).
MARQUEZ_IMAGE="$(third_party_image marquez)"
MARQUEZ_WEB_IMAGE="$(third_party_image marquez-web)"
MARQUEZ_PORT="${MARQUEZ_PORT:-$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')}"

# marquez_open: forwards MARQUEZ_PORT to the API; MARQUEZ_URL is its base. marquez_close stops it.
marquez_open() {
  kubectl -n "$LINEAGE_NAMESPACE" port-forward svc/marquez "$MARQUEZ_PORT:5000" >/dev/null 2>&1 &
  MARQUEZ_FORWARD_PID=$!
  MARQUEZ_URL="http://localhost:$MARQUEZ_PORT"
  for _ in $(seq 1 30); do
    if curl -fs -o /dev/null "$MARQUEZ_URL/api/v1/namespaces"; then
      return 0
    fi
    sleep 1
  done
  echo "Marquez did not answer through the port-forward" >&2
  return 1
}

marquez_close() {
  if [[ -n "${MARQUEZ_FORWARD_PID:-}" ]]; then
    kill "$MARQUEZ_FORWARD_PID" 2>/dev/null || true
    wait "$MARQUEZ_FORWARD_PID" 2>/dev/null || true
    MARQUEZ_FORWARD_PID=""
  fi
}
