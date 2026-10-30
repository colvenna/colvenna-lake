#!/usr/bin/env bash
# Polaris management and catalog API helpers, sourced by install-polaris.sh and the acceptance
# scripts that read through Polaris. Reaches Polaris through kubectl port-forward
# (IAM-authenticated, RBAC-checked) over TLS, with an OAuth2 token of a principal whose credentials
# are in a Secret; no credential or token reaches a process argument.
# shellcheck disable=SC2034  # settings used by the scripts that source this file
# shellcheck source-path=SCRIPTDIR
# shellcheck source=common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

POLARIS_NAMESPACE=colvenna-polaris
POLARIS_RELEASE=polaris
POLARIS_SERVICE_ACCOUNT=polaris
# The platform's catalog external readers query: EXTERNAL, a read-only facade over the metadata
# the operator points it at.
POLARIS_CATALOG=colvenna_open
POLARIS_REALM=POLARIS
# Polaris 1.8.0 and its admin tool (deploy/images/third-party.tsv).
POLARIS_IMAGE="$(third_party_image polaris)"
POLARIS_ADMIN_IMAGE="$(third_party_image polaris-admin-tool)"
POLARIS_CHART_URL=https://downloads.apache.org/polaris/helm-chart/1.8.0/polaris-1.8.0.tgz
POLARIS_CHART_SHA512=693e77c18bd51c4e4fcdd28b42aa5401c90055eeb8d5575398074f7f3325c07b3c16b8801e7f2c31145f33b9976e525b319d621722d0d1bf9b0889c5d9d12ad4
# Namespaces whose pods may call Polaris (the operator's, the readers').
POLARIS_CLIENT_LABEL=colvenna-lake.io/iceberg-client

POLARIS_PORT="${POLARIS_PORT:-$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')}"

# polaris_open [<secret> [<namespace>]]: a port-forward to Polaris and a token of the principal
# whose client-id and client-secret are in that Secret (default polaris-root, in Polaris's
# namespace), in $POLARIS_DIR.
polaris_open() {
  local secret="${1:-polaris-root}" namespace="${2:-$POLARIS_NAMESPACE}"
  POLARIS_DIR="$(mktemp -d)"
  chmod 700 "$POLARIS_DIR"
  kubectl -n "$CATALOG_NAMESPACE" get secret colvenna-catalog-ca-key -o jsonpath='{.data.ca\.crt}' \
    | base64 -d >"$POLARIS_DIR/ca.crt"
  kubectl -n "$POLARIS_NAMESPACE" port-forward "svc/$POLARIS_RELEASE" "$POLARIS_PORT:8181" \
    >"$POLARIS_DIR/port-forward.log" 2>&1 &
  POLARIS_FORWARD_PID=$!
  local up=0
  for _ in $(seq 1 30); do
    if ! kill -0 "$POLARIS_FORWARD_PID" 2>/dev/null; then
      echo "port-forward to Polaris exited: $(cat "$POLARIS_DIR/port-forward.log")" >&2
      return 1
    fi
    # Our own forward must be the one answering: another process could hold the port.
    if curl -s -o /dev/null --cacert "$POLARIS_DIR/ca.crt" \
      --resolve "polaris.$POLARIS_NAMESPACE.svc:$POLARIS_PORT:127.0.0.1" \
      "https://polaris.$POLARIS_NAMESPACE.svc:$POLARIS_PORT/api/catalog/v1/config"; then
      up=1
      break
    fi
    sleep 1
  done
  ((up)) || { echo "port-forward to Polaris did not come up" >&2; return 1; }
  polaris_token "$secret" "$namespace" >"$POLARIS_DIR/token"
  printf 'header = "Authorization: Bearer %s"\n' "$(cat "$POLARIS_DIR/token")" >"$POLARIS_DIR/curlrc"
}

polaris_close() {
  if [[ -n "${POLARIS_FORWARD_PID:-}" ]]; then
    kill "$POLARIS_FORWARD_PID" 2>/dev/null || true
    wait "$POLARIS_FORWARD_PID" 2>/dev/null || true
  fi
  rm -rf "${POLARIS_DIR:-/nonexistent}"
}

# polaris_url <path>
polaris_url() { echo "https://polaris.$POLARIS_NAMESPACE.svc:$POLARIS_PORT$1"; }

# polaris_curl <curl args>...: curl with the CA and the forward's name resolution.
polaris_curl() {
  curl -sS --cacert "$POLARIS_DIR/ca.crt" \
    --resolve "polaris.$POLARIS_NAMESPACE.svc:$POLARIS_PORT:127.0.0.1" "$@"
}

# polaris_token <secret> [<namespace>]: an access token of that Secret's principal (client
# credentials grant).
polaris_token() {
  local id secret form namespace="${2:-$POLARIS_NAMESPACE}"
  id="$(kubectl -n "$namespace" get secret "$1" -o jsonpath='{.data.client-id}' | base64 -d)"
  secret="$(kubectl -n "$namespace" get secret "$1" -o jsonpath='{.data.client-secret}' \
    | base64 -d)"
  form="$POLARIS_DIR/token-form"
  printf 'grant_type=client_credentials&client_id=%s&client_secret=%s&scope=PRINCIPAL_ROLE:ALL' \
    "$id" "$secret" >"$form"
  polaris_curl --fail-with-body -X POST --data "@$form" \
    "$(polaris_url /api/catalog/v1/oauth/tokens)" \
    | python3 -c 'import json,sys; sys.stdout.write(json.load(sys.stdin)["access_token"])'
  rm -f "$form"
}

# polaris_call <method> <path> [json-body]: prints the body; fails on HTTP >= 400.
polaris_call() {
  local args=(--fail-with-body -K "$POLARIS_DIR/curlrc" -X "$1")
  if [[ -n "${3:-}" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$3")
  fi
  polaris_curl "${args[@]}" "$(polaris_url "$2")"
}

# polaris_code <method> <path> [json-body]: only the HTTP status.
polaris_code() {
  local args=(-o /dev/null -w '%{http_code}' -K "$POLARIS_DIR/curlrc" -X "$1")
  if [[ -n "${3:-}" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$3")
  fi
  polaris_curl "${args[@]}" "$(polaris_url "$2")"
}

# polaris_ensure <path> <json-body>: creates with POST; a 409 (it exists) is fine.
polaris_ensure() {
  local code
  code="$(polaris_code POST "$1" "$2")"
  if [[ "$code" != 2* && "$code" != 409 ]]; then
    echo "POST $1 answered $code" >&2
    return 1
  fi
}
