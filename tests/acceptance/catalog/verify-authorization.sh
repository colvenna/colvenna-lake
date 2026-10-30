#!/usr/bin/env bash
# Acceptance on EKS: allowed and denied requests of different service identities, over the catalog
# queries, data reads, writes and management operations, and the audit trail they leave.
# Identities, fresh per run, on table <database>.orders (from the Flink flow):
#   owner    MANAGE on the database        writer   WRITE on orders
#   reader   READ on orders                columns  READ on column id of orders only
#   denied   READ on the database, DENY READ on orders
#   outsider no grant
# Each request goes from a pod in the catalog-client namespace and keeps its X-Request-Id. Commits
# and alterations carry an invalid body: authorization comes first, so they answer 400 when it
# passed and 403 when it did not, and nothing is written. Creating and dropping a scratch table is
# real (the owner's), and the writer's drop is aimed at orders itself, so a WRITE grant is shown not
# to reach DDL. Afterwards every request is looked up in the audit by its request id: it must name
# the identity, action, resource, outcome and time, failures as failures.
# The identities are disabled at the end; their grants expire after a day.
# Usage: verify-authorization.sh <database> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
db="${1:?usage: verify-authorization.sh <database> <evidence-dir>}"
evidence="${2:?usage: verify-authorization.sh <database> <evidence-dir>}"
mkdir -p "$evidence"
out="$evidence/authorization-checks.txt"
: >"$out"
ns="$ENGINE_NAMESPACE"
run="$(date -u +%Y%m%d%H%M%S)"
roles=(owner writer reader columns denied outsider)
declare -A principal
for role in "${roles[@]}"; do
  principal[$role]="authz-$role-$run"
done
# shellcheck disable=SC2317  # invoked through the EXIT trap
cleanup() {
  kubectl -n "$ns" delete secret authz-tokens --ignore-not-found >/dev/null 2>&1 || true
  for role in "${roles[@]}"; do
    admin_call POST "/colvenna/admin/v1/principals/${principal[$role]}/disable" >/dev/null 2>&1 || true
  done
  admin_close
}
trap cleanup EXIT
export GRANT_PURPOSE="authorization checks (issue #21)" GRANT_DAYS=1

admin_open
tokens="$ADMIN_DIR/authz-tokens"
mkdir -m 700 "$tokens"
cp "$ADMIN_DIR/token" "$tokens/admin"
for role in "${roles[@]}"; do
  admin_call POST /colvenna/admin/v1/principals \
    "{\"name\":\"${principal[$role]}\",\"description\":\"catalog authorization checks\"}" >/dev/null
  admin_call POST "/colvenna/admin/v1/principals/${principal[$role]}/tokens" '{"ttl":"PT2H"}' \
    | python3 -c 'import json,sys; sys.stdout.write(json.load(sys.stdin)["token"])' >"$tokens/$role"
done
ensure_grant "${principal[owner]}" DATABASE "$db" - MANAGE
ensure_grant "${principal[writer]}" TABLE "$db" orders WRITE
ensure_grant "${principal[reader]}" TABLE "$db" orders READ
ensure_grant "${principal[columns]}" TABLE "$db" orders READ ALLOW id
ensure_grant "${principal[denied]}" DATABASE "$db" - READ
ensure_grant "${principal[denied]}" TABLE "$db" orders READ DENY
kubectl -n "$ns" create secret generic authz-tokens --from-file="$tokens" --dry-run=client -o yaml \
  | kubectl apply -f - >/dev/null
rm -rf "$tokens"

url="https://colvenna-catalog.$CATALOG_NAMESPACE.svc:8181/v1/colvenna"
admin_url="https://colvenna-catalog.$CATALOG_NAMESPACE.svc:8181/colvenna/admin/v1"
probe="probe-$db"
probe="${probe//_/-}"
scratch="authz_probe_$run"
create="$(python3 -c 'import json,sys; print(json.dumps({"identifier": {"database": sys.argv[1], "object": sys.argv[2]}, "schema": {"fields": [{"id": 0, "name": "id", "type": "INT"}], "partitionKeys": [], "primaryKeys": [], "options": {}}}))' "$db" "$scratch")"
cat >"$evidence/probe.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $probe}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 100, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: curl
      image: $CURL_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      volumeMounts:
        - {name: ca, mountPath: /ca}
        - {name: tokens, mountPath: /tokens}
      command: ["sh", "-c"]
      args:
        - |
          # call <name> <role> <method> <url> [<body>]: prints "<name> <status> <request-id> <body>".
          call() {
            printf 'Authorization: Bearer %s' "\$(cat "/tokens/\$2")" >/tmp/h
            if [ -n "\$5" ]; then
              s=\$(curl -s -D /tmp/d -o /tmp/b -w '%{http_code}' --max-time 20 --cacert /ca/ca.crt -H @/tmp/h \
                -H 'Content-Type: application/json' -X "\$3" --data "\$5" "\$4")
            else
              s=\$(curl -s -D /tmp/d -o /tmp/b -w '%{http_code}' --max-time 20 --cacert /ca/ca.crt -H @/tmp/h -X "\$3" "\$4")
            fi
            id=\$(tr -d '\r' </tmp/d | sed -n 's/^[Xx]-[Rr]equest-[Ii]d: *//p' | head -1)
            echo "\$1 \$s \${id:--} \$(tr -d '\n' </tmp/b)"
          }
          t="$url/databases/$db/tables"
          for r in owner reader outsider; do call "list-databases/\$r" \$r GET "$url/databases"; done
          for r in owner writer reader denied outsider; do call "get-table/\$r" \$r GET "\$t/orders"; done
          for r in owner reader outsider; do call "list-tables/\$r" \$r GET "\$t"; done
          for r in writer reader denied outsider; do call "data-token/\$r" \$r GET "\$t/orders/token"; done
          call column-auth/allowed columns POST "\$t/orders/auth" '{"select":["id"]}'
          call column-auth/denied columns POST "\$t/orders/auth" '{"select":["id","amount"]}'
          for r in owner writer reader outsider; do call "commit/\$r" \$r POST "\$t/orders/commit" '{}'; done
          for r in owner writer; do call "alter-table/\$r" \$r POST "\$t/orders" '{}'; done
          call create-table/writer writer POST "\$t" '$create'
          call create-table/owner owner POST "\$t" '$create'
          call drop-table/owner owner DELETE "\$t/$scratch"
          for r in admin owner reader; do call "admin-grants/\$r" \$r GET "$admin_url/grants"; done
          # Last: a WRITE grant must not reach DDL on its own table.
          call drop-table/writer writer DELETE "\$t/orders"
  volumes:
    - name: ca
      configMap: {name: colvenna-catalog-ca}
    - name: tokens
      secret: {secretName: authz-tokens}
YAML
run_pod "$ns" "$probe" "$evidence/probe.yaml" >"$evidence/responses.txt"

# check <name> <expected status> [<python expression on the parsed body `b`>]
check() {
  python3 - "$evidence/responses.txt" "$1" "$2" "${3:-True}" "$db" <<'PY' | tee -a "$out"
import json, sys
path, name, expected, test, db = sys.argv[1:6]
line = next((l.rstrip("\n") for l in open(path) if l.startswith(name + " ")), None)
status, body = (line.split(" ", 3)[1], line.split(" ", 3)[3]) if line else ("missing", "")
try:
    b = json.loads(body) if body else None
except ValueError:
    b = body
ok = status == expected and bool(eval(test, {"b": b, "db": db}))
print(f"{name} expected={expected}{'' if test == 'True' else ' and ' + test} actual={status} "
      f"{'OK' if ok else 'FAIL'}")
PY
}
check list-databases/owner 200 'db in b["databases"]'
check list-databases/reader 200 'db in b["databases"]'
check list-databases/outsider 200 'db not in b["databases"]'
check get-table/owner 200
check get-table/writer 200
check get-table/reader 200
check get-table/denied 403
check get-table/outsider 403
check list-tables/owner 200 '"orders" in b["tables"]'
check list-tables/reader 200 'b["tables"] == ["orders"]'
check list-tables/outsider 403
check data-token/writer 200
check data-token/reader 200
check data-token/denied 403
check data-token/outsider 403
check column-auth/allowed 200
check column-auth/denied 403 '"amount" in b["message"]'
check commit/owner 400
check commit/writer 400
check commit/reader 403
check commit/outsider 403
check alter-table/owner 400
check alter-table/writer 403
check create-table/writer 403
check create-table/owner 200
check drop-table/owner 200
check admin-grants/admin 200
check admin-grants/owner 403
check admin-grants/reader 403
check drop-table/writer 403

# Every request in the audit, found by the request id the client received.
for role in "${roles[@]}"; do
  admin_call GET "/colvenna/admin/v1/audit?principal=${principal[$role]}&limit=1000" \
    >"$evidence/audit-$role.json"
done
python3 - "$evidence" "$db" "$scratch" <<'PY' | tee -a "$out"
import json, sys
d, db, scratch = sys.argv[1:4]
roles = ["owner", "writer", "reader", "columns", "denied", "outsider"]
events = {e["requestId"]: e for r in roles for e in json.load(open(f"{d}/audit-{r}.json"))["events"]}
calls = {}
for line in open(f"{d}/responses.txt"):
    parts = line.rstrip("\n").split(" ", 3)
    if len(parts) >= 3:
        calls[parts[0]] = (int(parts[1]) if parts[1].isdigit() else 0, parts[2])
# call -> (action, resource); the outcome follows from the status the client saw.
expected = {
    "get-table/owner": ("GET_TABLE", f"{db}.orders"),
    "get-table/denied": ("GET_TABLE", f"{db}.orders"),
    "get-table/outsider": ("GET_TABLE", f"{db}.orders"),
    "list-tables/outsider": ("LIST_TABLES", db),
    "data-token/reader": ("ISSUE_DATA_TOKEN", f"{db}.orders"),
    "data-token/denied": ("ISSUE_DATA_TOKEN", f"{db}.orders"),
    "data-token/outsider": ("ISSUE_DATA_TOKEN", f"{db}.orders"),
    "column-auth/allowed": ("AUTH_TABLE_QUERY", f"{db}.orders"),
    "column-auth/denied": ("AUTH_TABLE_QUERY", f"{db}.orders"),
    "commit/owner": ("COMMIT", f"{db}.orders"),
    "commit/writer": ("COMMIT", f"{db}.orders"),
    "commit/reader": ("COMMIT", f"{db}.orders"),
    "commit/outsider": ("COMMIT", f"{db}.orders"),
    "alter-table/owner": ("ALTER_TABLE", f"{db}.orders"),
    "alter-table/writer": ("ALTER_TABLE", f"{db}.orders"),
    # Refused on the database, before the request names its table.
    "create-table/writer": ("CREATE_TABLE", db),
    "create-table/owner": ("CREATE_TABLE", f"{db}.{scratch}"),
    "drop-table/owner": ("DROP_TABLE", f"{db}.{scratch}"),
    "drop-table/writer": ("DROP_TABLE", f"{db}.orders"),
    "admin-grants/owner": ("LIST_GRANTS", None),
    "admin-grants/reader": ("LIST_GRANTS", None),
}
failed = False
for call, (action, resource) in expected.items():
    status, request_id = calls.get(call, (0, "-"))
    e = events.get(request_id)
    outcome = "SUCCEEDED" if status < 400 else "DENIED" if status == 403 else "FAILED"
    ok = (e is not None and e["action"] == action and e["outcome"] == outcome
          and e["httpStatus"] == status and bool(e.get("time"))
          and (resource is None or e.get("resource") == resource))
    failed |= not ok
    got = None if e is None else (e["principal"], e["action"], e.get("resource"), e["outcome"], e["httpStatus"])
    print(f"audit {call} request {request_id} expected={action}/{resource}/{outcome}/{status} "
          f"actual={got} {'OK' if ok else 'FAIL'}")
wrong = [e for e in events.values() if e["outcome"] == "SUCCEEDED" and e["httpStatus"] >= 400]
failed |= bool(wrong)
print(f"audit failures-recorded-as-success expected=0 actual={len(wrong)} {'OK' if not wrong else 'FAIL'}")
sys.exit(1 if failed else 0)
PY
! grep -q ' FAIL$' "$out" || { echo "Authorization checks failed; see $out" >&2; exit 1; }
echo "Authorization checks PASS: $(grep -c ' OK$' "$out") checks"
