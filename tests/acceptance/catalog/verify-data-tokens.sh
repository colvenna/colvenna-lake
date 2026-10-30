#!/usr/bin/env bash
# Repeatable checks of per-table data tokens against real S3, after the Flink flow:
#   1. a vended credential reads and lists its own table, and nothing else: not a sibling table,
#      not a table of another database, not the bare prefix, not outside the warehouse;
#   2. the engine pods' own AWS identities reach no table data;
#   3. no flow configuration carries static S3 keys.
# Credentials stay in a 700 work directory and environment variables, never in arguments or logs.
# Usage: verify-data-tokens.sh <database> <flow-evidence-root> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
database="${1:?usage: verify-data-tokens.sh <database> <flow-evidence-root> <evidence-dir>}"
flows="${2:?usage: verify-data-tokens.sh <database> <flow-evidence-root> <evidence-dir>}"
evidence="${3:?usage: verify-data-tokens.sh <database> <flow-evidence-root> <evidence-dir>}"
mkdir -p "$evidence"
out="$evidence/data-token-checks.txt"
: >"$out"
bucket="$(stack_output "$FOUNDATION_STACK" WarehouseBucket)"
other="${database}_other"
work="$(mktemp -d)"
chmod 700 "$work"
: >"$work/empty"
trap 'admin_close; rm -rf "$work"' EXIT
admin_open

# expect <label> <expected> <actual>
expect() {
  printf '%s expected=%s actual=%s\n' "$1" "$2" "$3" | tee -a "$out"
  if [[ "$2" != "$3" ]]; then
    echo "FAIL: $1" | tee -a "$out" >&2
    exit 1
  fi
}

# A table in a second database, so the cross-domain checks have a real object to aim at.
admin_call POST /v1/colvenna/databases "{\"name\":\"$other\",\"options\":{}}" >/dev/null 2>&1 || true
admin_call POST "/v1/colvenna/databases/$other/tables" "$(python3 - "$other" <<'PY'
import json, sys
print(json.dumps({"identifier": {"database": sys.argv[1], "object": "secrets"},
                  "schema": {"fields": [{"id": 0, "name": "id", "type": "INT"}],
                             "partitionKeys": [], "primaryKeys": [], "options": {}}}))
PY
)" >/dev/null 2>&1 || true

# table_key <database> <table>: the table root as a bucket key, from GetTable.
table_key() {
  admin_call GET "/v1/colvenna/databases/$1/tables/$2" | python3 -c '
import json, sys
path = json.load(sys.stdin)["path"]
print(path.split("://", 1)[1].split("/", 1)[1].strip("/"))'
}
orders="$(table_key "$database" orders)"
events="$(table_key "$database" events)"
secrets="$(table_key "$other" secrets)"
printf 'orders=%s\nevents=%s\nother=%s\n' "$orders" "$events" "$secrets" >"$evidence/table-keys.txt"

# Dedicated probe identities, so the checks use exactly what any service identity gets: they must
# exist before they can be granted anything.
for principal in token-probe token-reader; do
  admin_call POST /colvenna/admin/v1/principals \
    "{\"name\":\"$principal\",\"description\":\"catalog data-token checks\"}" >/dev/null 2>&1 || true
done
# credentials <principal> <file> [<expected status>]: the principal's vended credentials for orders.
credentials() {
  admin_call POST /colvenna/admin/v1/principals \
    "{\"name\":\"$1\",\"description\":\"catalog data-token checks\"}" >/dev/null 2>&1 || true
  admin_call POST "/colvenna/admin/v1/principals/$1/tokens" '{"ttl":"PT1H"}' \
    | python3 -c 'import json,sys; sys.stdout.write(json.load(sys.stdin)["token"])' >"$work/$2.token"
  printf 'header = "Authorization: Bearer %s"\n' "$(cat "$work/$2.token")" >"$work/$2.curlrc"
  curl -s -o "$work/$2.json" -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$work/$2.curlrc" \
    "https://localhost:$ADMIN_PORT/v1/colvenna/databases/$database/tables/orders/token"
}
# The writer probe may write orders; the reader may only read it; the outsider has no grant.
ensure_grant token-probe TABLE "$database" orders WRITE
ensure_grant token-reader TABLE "$database" orders READ
expect "writer identity gets credentials" 200 "$(credentials token-probe vended)"
expect "read-only identity gets credentials" 200 "$(credentials token-reader reader)"
expect "identity without a grant gets none" 403 "$(credentials token-outsider outsider)"
python3 - "$work/vended.json" "$evidence/vended-keys.txt" <<'PY'
import json, sys
token = json.load(open(sys.argv[1]))["token"]
open(sys.argv[2], "w").write("\n".join(sorted(token)) + "\n")
PY

# s3 <label> <allow|deny|missing> <aws s3api arguments...>, run with the vended credential only.
s3() {
  local label="$1" expected="$2" actual
  shift 2
  if (
    unset AWS_PROFILE AWS_SESSION_TOKEN AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
    eval "$(python3 - "$work/${creds:-vended}.json" <<'PY'
import json, shlex, sys
t = json.load(open(sys.argv[1]))["token"]
for env, key in (("AWS_ACCESS_KEY_ID", "s3.access-key-id"),
                 ("AWS_SECRET_ACCESS_KEY", "s3.secret-access-key"),
                 ("AWS_SESSION_TOKEN", "s3.session-token")):
    print(f"export {env}={shlex.quote(t[key])}")
PY
)"
    aws s3api "$@" >/dev/null 2>"$work/err"
  ); then
    actual=allow
  elif grep -q "AccessDenied\|Forbidden\|(403)" "$work/err"; then
    actual=deny
  elif grep -q "Not Found\|(404)\|NoSuchKey" "$work/err"; then
    actual=missing
  else
    actual="error:$(head -c 200 "$work/err" | tr '\n' ' ')"
  fi
  expect "$label" "$expected" "$actual"
}

# exists <key>: the object is there, by the operator's own identity. A 403 from a probe then means
# denied, not missing (without list access S3 also answers 403 for missing keys).
exists() {
  if ! aws s3api head-object --bucket "$bucket" --key "$1" >/dev/null 2>&1; then
    expect "probe target exists: $1" yes no
  fi
}
for key in "$orders/schema/schema-0" "$events/schema/schema-0" "$secrets/schema/schema-0"; do
  exists "$key"
done

s3 "own table: list" allow list-objects-v2 --bucket "$bucket" --prefix "$orders/" --max-items 1
s3 "own table: read schema" allow head-object --bucket "$bucket" --key "$orders/schema/schema-0"
s3 "own table: missing key is 404, not 403" missing head-object --bucket "$bucket" \
  --key "$orders/snapshot/snapshot-999999"
s3 "own table: write" allow put-object --bucket "$bucket" --key "$orders/token-probe" \
  --body "$work/empty"
s3 "own table: delete" allow delete-object --bucket "$bucket" --key "$orders/token-probe"
creds=reader s3 "read-only identity: read" allow head-object --bucket "$bucket" \
  --key "$orders/schema/schema-0"
creds=reader s3 "read-only identity: write data" deny put-object --bucket "$bucket" \
  --key "$orders/token-reader" --body "$work/empty"
creds=reader s3 "read-only identity: delete data" deny delete-object --bucket "$bucket" \
  --key "$orders/schema/schema-0"
creds=reader s3 "read-only identity: write consumer progress" allow put-object --bucket "$bucket" \
  --key "$orders/consumer/consumer-token-reader" --body "$work/empty"
creds=reader s3 "read-only identity: remove its consumer progress" allow delete-object \
  --bucket "$bucket" --key "$orders/consumer/consumer-token-reader"
s3 "sibling table: list" deny list-objects-v2 --bucket "$bucket" --prefix "$events/" --max-items 1
s3 "sibling table: read schema" deny head-object --bucket "$bucket" --key "$events/schema/schema-0"
s3 "sibling table: write" deny put-object --bucket "$bucket" --key "$events/token-probe" \
  --body "$work/empty"
s3 "bare table prefix: list (would show orders_*)" deny list-objects-v2 --bucket "$bucket" \
  --prefix "$orders" --max-items 1
s3 "database directory: list" deny list-objects-v2 --bucket "$bucket" --prefix "${orders%/*}/" \
  --max-items 1
s3 "other database: read schema" deny head-object --bucket "$bucket" --key "$secrets/schema/schema-0"
s3 "other database: list" deny list-objects-v2 --bucket "$bucket" --prefix "$secrets/" --max-items 1
s3 "whole bucket: list" deny list-objects-v2 --bucket "$bucket" --max-items 1
s3 "outside the warehouse: write" deny put-object --bucket "$bucket" --key "token-probe" \
  --body "$work/empty"

# Engine pods' own identities: Flink state only, no table data. Also a pod with no IRSA role, which
# falls back to whatever the node offers: if that could read table data, the flows' success would
# not prove they used vended credentials.
kubectl -n "$ENGINE_NAMESPACE" create serviceaccount no-irsa --dry-run=client -o yaml \
  | kubectl apply -f - >/dev/null
for pair in "$ENGINE_NAMESPACE test-flink" "$ENGINE_NAMESPACE test-spark" "$DORIS_NAMESPACE doris" \
  "$ENGINE_NAMESPACE no-irsa"; do
  read -r ns sa <<<"$pair"
  cat >"$work/pod.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata:
  name: probe-$sa
spec:
  restartPolicy: Never
  serviceAccountName: $sa
  securityContext:
    runAsNonRoot: true
    runAsUser: 1000
    seccompProfile: {type: RuntimeDefault}
  containers:
    - name: aws
      image: $AWS_CLI_IMAGE
      securityContext:
        allowPrivilegeEscalation: false
        capabilities: {drop: ["ALL"]}
      env:
        - {name: HOME, value: /tmp}
      command: ["sh", "-c"]
      args:
        - |
          r() { if aws s3api "\$@" >/dev/null 2>/tmp/err; then echo allow;
                elif grep -q 'AccessDenied\|Forbidden\|(403)' /tmp/err; then echo deny;
                elif grep -q 'Unable to locate credentials\|NoCredentials' /tmp/err; then echo deny;
                else echo error; fi; }
          echo identity=\$(aws sts get-caller-identity --query Arn --output text 2>/dev/null || echo none)
          echo read=\$(r head-object --bucket $bucket --key $orders/schema/schema-0)
          echo list=\$(r list-objects-v2 --bucket $bucket --prefix $orders/ --max-items 1)
          echo warehouse=\$(r list-objects-v2 --bucket $bucket --prefix warehouse/ --max-items 1)
YAML
  result="$(run_pod "$ns" "probe-$sa" "$work/pod.yaml")"
  identity="$(grep '^identity=' <<<"$result" | cut -d= -f2-)"
  if [[ "$sa" == no-irsa ]]; then
    printf 'pod without IRSA runs as: %s\n' "$identity" | tee -a "$out"
  else
    expect "$sa pod identity is its IRSA role" "$(env_name "colvenna-lake-$sa")" \
      "$(sed -E 's|.*:assumed-role/([^/]+)/.*|\1|' <<<"$identity")"
  fi
  for check in read list warehouse; do
    expect "$sa pod identity: $check table data" deny "$(grep "^$check=" <<<"$result" | cut -d= -f2)"
  done
done

# No static S3 key anywhere a flow is configured: the applied manifests, the flow sources that build
# the catalog options, and the live objects of the engine namespaces. Key IDs and S3 credential
# option names both count.
pattern='AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|(fs\.s3a\.|s3\.)(access|secret)[-.]key|session[-.]token'
mapfile -t manifests < <(find "$flows" -name '*.yaml' -o -name '*.sql' | sort)
if [[ ${#manifests[@]} -eq 0 ]]; then
  expect "flow manifests found under $flows" ">0" 0
fi
for ns in "$ENGINE_NAMESPACE" "$DORIS_NAMESPACE"; do
  kubectl -n "$ns" get configmaps,jobs,pods,dorisclusters -o yaml >"$work/live-$ns.yaml" 2>/dev/null \
    || kubectl -n "$ns" get configmaps,jobs,pods -o yaml >"$work/live-$ns.yaml"
done
hits="$(grep -lE "$pattern" "${manifests[@]}" "$work"/live-*.yaml \
  "$ACCEPTANCE_DIR"/catalog/*/src/main/java/com/colvenna/lake/acceptance/flow/*.java 2>/dev/null || true)"
printf 'scanned %s manifests, %s live namespace dumps, the flow sources\n' \
  "${#manifests[@]}" "$(find "$work" -name 'live-*.yaml' | wc -l)" | tee -a "$out"
expect "flow configurations without S3 keys" none "${hits:-none}"

echo PASS | tee -a "$out"
