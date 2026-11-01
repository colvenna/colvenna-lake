#!/usr/bin/env bash
# Negative and positive access checks of an installed colvenna-catalog, run from pods inside the cluster.
# Covers valid, missing, forged, expired, revoked and disabled credentials, a service identity on
# the admin API, plain HTTP, the loopback-only health port, and the NetworkPolicy.
# Writes one line per check to <evidence-dir>/access-checks.txt and fails on the first violation.
# Tokens reach the probe pods as Secret files and curl reads them from files, never from arguments.
# Usage: verify-catalog.sh <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/catalog-admin.sh
source "$(dirname "$0")/../lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
evidence="${1:?usage: verify-catalog.sh <evidence-dir>}"
mkdir -p "$evidence"
out="$evidence/access-checks.txt"
: >"$out"

# A catalog client's namespace and one that is not, both made for these checks and removed after.
client_ns=colvenna-catalog-client-check
outsider_ns=colvenna-catalog-outsider
# shellcheck disable=SC2317  # invoked through the EXIT trap
cleanup() {
  kubectl delete namespace "$client_ns" "$outsider_ns" --wait=false --ignore-not-found >/dev/null 2>&1 \
    || true
  admin_close
}
trap cleanup EXIT
for ns in "$client_ns" "$outsider_ns"; do
  kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl label namespace "$ns" --overwrite pod-security.kubernetes.io/enforce=restricted >/dev/null
done
kubectl label namespace "$client_ns" --overwrite colvenna-lake.io/catalog-client=true >/dev/null
kubectl label namespace "$outsider_ns" --overwrite colvenna-lake.io/catalog-client- >/dev/null 2>&1 || true

admin_open
tokens="$ADMIN_DIR/tokens"
mkdir -m 700 "$tokens"
cp "$ADMIN_DIR/token" "$tokens/admin"
# issue <principal> <ttl> <file>: a token for the principal, written to $tokens/<file>.
issue() {
  admin_call POST /colvenna/admin/v1/principals "{\"name\":\"$1\",\"description\":\"catalog access checks\"}" \
    >/dev/null 2>&1 || true
  admin_call POST "/colvenna/admin/v1/principals/$1/tokens" "{\"ttl\":\"$2\"}" >"$tokens/$3.json"
  python3 -c 'import json,sys; sys.stdout.write(json.load(open(sys.argv[1]))["token"])' \
    "$tokens/$3.json" >"$tokens/$3"
}
# Fresh identities per run: the service identity is disabled again at the end.
run="$(date -u +%Y%m%d%H%M%S)"
service="verify-service-$run"
issue "$service" P1D service
issue "$service" PT1M expired
issue "$service" P1D revoked
revoked_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' \
  "$tokens/revoked.json")"
admin_call DELETE "/colvenna/admin/v1/tokens/$revoked_id" >/dev/null
issue "verify-disabled-$run" P1D disabled
admin_call POST "/colvenna/admin/v1/principals/verify-disabled-$run/disable" >/dev/null
rm -f "$tokens"/*.json
for ns in "$client_ns" "$outsider_ns"; do
  kubectl -n "$ns" create configmap colvenna-catalog-ca --from-file=ca.crt="$ADMIN_DIR/ca.crt" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
done
kubectl -n "$client_ns" create secret generic verify-tokens --from-file="$tokens" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$outsider_ns" create secret generic verify-tokens --from-file=admin="$tokens/admin" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
url="https://colvenna-catalog.$CATALOG_NAMESPACE.svc:8181"
config="$url/v1/config?warehouse=colvenna"
pod="$(kubectl -n "$CATALOG_NAMESPACE" get pods -l app.kubernetes.io/name=colvenna-catalog "$LIVE_PODS" \
  -o jsonpath='{.items[0].metadata.name}')"
pod_ip="$(kubectl -n "$CATALOG_NAMESPACE" get pod "$pod" -o jsonpath='{.status.podIP}')"

# probe <namespace> <name> <shell script>; prints the script's stdout. In the script, `code`
# prints the HTTP status of a request and `as <token-file>` sends that token as a header file.
probe() {
  local ns="$1" name="$2" script="$3" manifest
  manifest="$(mktemp)"
  cat >"$manifest" <<YAML
apiVersion: v1
kind: Pod
metadata:
  name: $name
spec:
  restartPolicy: Never
  securityContext:
    runAsNonRoot: true
    runAsUser: 100
    seccompProfile: {type: RuntimeDefault}
  containers:
    - name: curl
      image: $CURL_IMAGE
      securityContext:
        allowPrivilegeEscalation: false
        capabilities: {drop: ["ALL"]}
      volumeMounts:
        - {name: ca, mountPath: /ca}
        - {name: tokens, mountPath: /tokens}
      command: ["sh", "-c"]
      args:
        - |
          code() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 --cacert /ca/ca.crt "\$@" || true; }
          as() { printf 'Authorization: Bearer %s' "\$(cat "/tokens/\$1")" >"/tmp/\$1.h"; echo "@/tmp/\$1.h"; }
$(printf '%s\n' "$script" | sed 's/^/          /')
  volumes:
    - name: ca
      configMap: {name: colvenna-catalog-ca}
    - name: tokens
      secret: {secretName: verify-tokens}
YAML
  run_pod "$ns" "$name" "$manifest"
  rm -f "$manifest"
}

expect() {
  printf '%s expected=%s actual=%s\n' "$1" "$2" "$3" | tee -a "$out"
  if [[ "$2" != "$3" ]]; then
    echo "FAIL: $1" | tee -a "$out" >&2
    exit 1
  fi
}

# The PT1M token must be past its lifetime and the authentication cache.
sleep 70
result="$(probe "$client_ns" verify-client "
echo admin=\$(code -H \"\$(as admin)\" \"$config\")
echo service=\$(code -H \"\$(as service)\" \"$config\")
echo anonymous=\$(code \"$config\")
echo forged=\$(code -H 'Authorization: Bearer colvenna_forged' \"$config\")
echo expired=\$(code -H \"\$(as expired)\" \"$config\")
echo revoked=\$(code -H \"\$(as revoked)\" \"$config\")
echo disabled=\$(code -H \"\$(as disabled)\" \"$config\")
echo service_admin_api=\$(code -H \"\$(as service)\" \"$url/colvenna/admin/v1/principals\")
echo anonymous_admin_api=\$(code \"$url/colvenna/admin/v1/principals\")
echo plaintext=\$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://colvenna-catalog.$CATALOG_NAMESPACE.svc:8181/v1/config || true)
echo health_from_pod=\$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://$pod_ip:8182/healthz || true)
")"
echo "$result" >"$evidence/client-probe.txt"
value() { grep "^$1=" <<<"$result" | cut -d= -f2; }
expect "client namespace, admin token" 200 "$(value admin)"
expect "client namespace, service token" 200 "$(value service)"
expect "client namespace, no token" 401 "$(value anonymous)"
expect "client namespace, forged token" 401 "$(value forged)"
expect "client namespace, expired token" 401 "$(value expired)"
expect "client namespace, revoked token" 401 "$(value revoked)"
expect "client namespace, token of a disabled principal" 401 "$(value disabled)"
expect "service identity on the admin API" 403 "$(value service_admin_api)"
expect "admin API without token" 401 "$(value anonymous_admin_api)"
expect "plain HTTP to the TLS port gets no HTTP response" 000 "$(value plaintext)"
expect "health port from another pod" 000 "$(value health_from_pod)"

# Inside the catalog pod: the health port answers on loopback and refuses the pod address.
expect "health port on 127.0.0.1 inside the pod" 200 \
  "$(kubectl -n "$CATALOG_NAMESPACE" exec "$pod" -- \
    curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:8182/healthz || true)"
expect "health port on the pod address inside the pod" 000 \
  "$(kubectl -n "$CATALOG_NAMESPACE" exec "$pod" -- \
    curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://$pod_ip:8182/healthz" || true)"

result="$(probe "$outsider_ns" verify-outsider "
echo outsider=\$(code -H \"\$(as admin)\" \"$config\")
")"
echo "$result" >"$evidence/outsider-probe.txt"
expect "unlabelled namespace is blocked by NetworkPolicy" 000 "$(value outsider)"

admin_call POST "/colvenna/admin/v1/principals/$service/disable" >/dev/null
echo PASS | tee -a "$out"
