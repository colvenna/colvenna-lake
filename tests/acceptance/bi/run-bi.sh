#!/usr/bin/env bash
# Acceptance on AWS: the BI interface (Superset behind the entry), each person querying Doris as
# their own Doris user. Writes PASS/FAIL lines to <evidence>/verdicts.tsv and fails if any check
# fails.
#
# - A MySQL source declares orders L3 personal and labels their columns as the governance
#   enforcement run does; a dashboard serves them to three users cleared for L2: alice (the
#   created orders), bob (the shipped ones) and carol (ids up to 10, who also connects to Doris
#   directly). Each has a sign-in name; dave signs in but is no dashboard user, erin is an
#   operator, adm an admin.
# - Without a session nothing is served; Superset's own sign-in, API tokens and guest tokens are
#   refused; pods in the cluster cannot connect and the pod refuses forged identity headers.
# - Each person gets the roles of their groups and their dashboards, sees the dashboard's
#   database and nothing else, and reads in SQL Lab exactly what their Doris user reads: masked
#   columns, their own rows, carol's the same as directly. The lake table, information_schema and
#   a query the user is not given are refused by Doris; browsing schemas, tables and columns works.
#   Dave reaches no database.
# - A dataset alice makes is a chart that shows each viewer their own rows, its CSV export too;
#   there is no results backend to read another's results from.
# - Doris's audit names every query from the BI interface by the person's own Doris user, with the
#   person in a comment, and has none from any other user.
# - Taking bob's sign-in name off the dashboard ends his access; signing out returns to sign-in;
#   deleting the dashboard withdraws all of it.
#
# usage: run-bi.sh <run-id> <evidence-dir>; needs the entry stack, install-lbc.sh, build-bi.sh and
# install-bi.sh, then deploy/scripts/operator/build-images.sh and install-operator.sh from this commit.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-bi.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-bi.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
here="$REPO_DIR/tests/acceptance/bi"
entry_py="$REPO_DIR/tests/acceptance/entry/entry.py"
cdc="$ACCEPTANCE_DIR/cdc"
mkdir -p "$evidence"/{status,sql,doris,http}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
for image in operator mysql; do
  echo "$image $(image_ref "$image")" | tee -a "$evidence/images.txt"
done
echo "bi $(entry_image bi)" | tee -a "$evidence/images.txt"
running="$(kubectl -n "$BI_NAMESPACE" get deployment "$BI_RELEASE" -o jsonpath='{.spec.template.spec.containers[0].image}')"
[[ "$running" == "$(entry_image bi)" ]] || { echo "The BI interface runs $running, not this commit's" >&2; exit 1; }
failed=0
began=$SECONDS
step() { echo "$(date -u +%FT%TZ)	$*" | tee -a "$evidence/timeline.tsv"; }
verdict() {
  local why="${3:-}"
  why="${why//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${why//$'\t'/ }" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS ]] || failed=1
}
# check <label> <detail> <command...>: PASS when the command succeeds.
check() {
  local label="$1" detail="$2"
  shift 2
  if "$@"; then verdict "$label" PASS "$detail"; else verdict "$label" FAIL "$detail"; fi
}

pool="$(entry_output UserPoolId)"
url="$(entry_output biUrl)"
signin="$(entry_output SignInDomain)"
db="bi_$run" ods="ods_bi_$run" source_name="bs-$run" dashboard="bd-$run"
fe="$DORIS_CLUSTER-fe-service.$DORIS_NAMESPACE.svc"
small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
prefix="bi-$run"
export ENTRY_AGENT="$prefix-browser"
state="$(mktemp -d)"
chmod 700 "$state"
python3 -c 'import sys,urllib.parse; print(urllib.parse.urlsplit(sys.argv[1]).hostname)' "$signin" >"$state/signin-host"
declare -A groups=([alice]=colvenna-bi [bob]=colvenna-bi [carol]=colvenna-bi [dave]=colvenna-bi [erin]=colvenna-operator [adm]=colvenna-admin)
forward_pid=""
# shellcheck disable=SC2317  # run by the trap
cleanup() {
  [[ -n "$forward_pid" ]] && kill "$forward_pid" 2>/dev/null
  # A run that stopped early leaves no job running (the last steps delete them in order).
  kubectl -n "$JOB_NAMESPACE" delete lakedashboard "$dashboard" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kubectl -n "$JOB_NAMESPACE" delete lakesource "$source_name" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  for u in "${!groups[@]}"; do
    aws cognito-idp admin-delete-user --user-pool-id "$pool" --username "$prefix-$u" 2>/dev/null || true
  done
  rm -rf "$state"
}
trap cleanup EXIT
signin() { python3 -I "$entry_py" "$@"; }
bi() { python3 -I "$here/bi.py" "$@"; }
field() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))' "$1" "$2"; }
get() { kubectl -n "$JOB_NAMESPACE" get "$1" "$2" -o json >"$3"; }

# await <label> <timeout-seconds> <kind> <name> <python expression over st, cond()>
await() {
  local label="$1" end=$((SECONDS + $2)) started=$SECONDS file="$evidence/status/$1.json"
  while ((SECONDS < end)); do
    if get "$3" "$4" "$file" 2>/dev/null && python3 - "$file" "$5" <<'PY'; then
import json, sys
st = json.load(open(sys.argv[1])).get("status") or {}
def cond(t):
    return next((c.get("status") for c in st.get("conditions", []) if c.get("type") == t), None)
def table(name):
    return next((t for t in st.get("tables", []) if t.get("name") == name), {})
sys.exit(0 if eval(sys.argv[2]) else 1)
PY
      verdict "$label" PASS "$((SECONDS - started))s"
      return 0
    fi
    sleep 10
  done
  verdict "$label" FAIL "not within $2s: $5"
  return 1
}

# until_ok <label> <timeout-seconds> <command...>: PASS once the command succeeds.
until_ok() {
  local label="$1" end=$((SECONDS + $2)) started=$SECONDS
  shift 2
  while ((SECONDS < end)); do
    if "$@"; then
      verdict "$label" PASS "$((SECONDS - started))s"
      return 0
    fi
    sleep 15
  done
  verdict "$label" FAIL "not within the time allowed"
  return 1
}

# doris_as <user> <password-file> <sql-file> <out>: each line run as that user, as
# run-enforcement.sh does.
doris_as() {
  local user="$1" pod="bd-as-$RANDOM" ns=colvenna-bi-client
  kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl label namespace "$ns" --overwrite pod-security.kubernetes.io/enforce=restricted \
    colvenna-lake.io/doris-client=true >/dev/null
  kubectl -n "$ns" create secret generic "$pod" --from-file=password="$2" --from-file=run.sql="$3" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  cat >"$state/$pod.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $pod, namespace: $ns}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 999, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: mysql
      image: $MYSQL_CLIENT_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["bash", "-c"]
      args:
        - |
          export MYSQL_PWD="\$(cat /in/password)"
          while IFS= read -r q; do
            [[ -z "\$q" ]] && continue
            echo "-- \$q"
            mysql -h $fe -P 9030 -u '$user' --connect-timeout=10 --batch --skip-column-names -e "\$q" 2>&1
          done < /in/run.sql
      resources: {requests: {cpu: 10m, memory: 64Mi}, limits: {memory: 128Mi}}
      volumeMounts: [{name: in, mountPath: /in, readOnly: true}]
  volumes: [{name: in, secret: {secretName: $pod}}]
YAML
  run_pod "$ns" "$pod" "$state/$pod.yaml" >"$4" || true
  kubectl -n "$ns" delete secret "$pod" --ignore-not-found >/dev/null
}

# ------------------------------------------------------------------------------------ the users
step "users"
for u in "${!groups[@]}"; do
  name="$prefix-$u"
  python3 -c 'import secrets,string; a=string.ascii_letters+string.digits; print("Aa1!" + "".join(secrets.choice(a) for _ in range(24)), end="")' >"$state/$name.pw"
  aws cognito-idp admin-create-user --user-pool-id "$pool" --username "$name" --message-action SUPPRESS >/dev/null
  python3 -c 'import json,sys; print(json.dumps({"UserPoolId": sys.argv[1], "Username": sys.argv[2], "Password": open(sys.argv[3]).read(), "Permanent": True}))' \
    "$pool" "$name" "$state/$name.pw" >"$state/$name.input.json"
  aws cognito-idp admin-set-user-password --cli-input-json "file://$state/$name.input.json"
  rm -f "$state/$name.input.json"
  aws cognito-idp admin-add-user-to-group --user-pool-id "$pool" --username "$name" --group-name "${groups[$u]}"
done

# ----------------------------------------------------------------------------------- the lake
step "mysql fixture $db; source $source_name, its orders labelled"
{
  python3 "$cdc/mysql_contract.py" reset "$db"
  python3 "$cdc/mysql_contract.py" setup "$db"
  cat <<SQL
USE $db;
INSERT INTO orders (id, customer_id, amount, state, note, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 30)
  SELECT i, 1000 + i, ROUND(i * 1.5, 2), IF(i % 2 = 1, 'created', 'shipped'),
         CONCAT('call 555-01', LPAD(i, 2, '0')), 1 FROM n;
SQL
} >"$evidence/sql/setup.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/setup.sql" >/dev/null
ensure_rds_ca "$JOB_NAMESPACE"
cat >"$evidence/source.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: $source_name, namespace: $JOB_NAMESPACE}
spec:
  type: mysql
  connection:
    host: $(stack_output "$TEST_MYSQL_STACK" MySqlEndpoint)
    database: $db
    credentialsSecret: {name: mysql-cdc}
    inspectorSecret: {name: mysql-inspect}
    caConfigMap: rds-ca
    serverTimeZone: UTC
  tables: {include: [orders]}
  target: {database: $ods}
  governance:
    owner: shop-team
    level: L3
    categories: [personal]
    subjectKeys: {orders: [customer_id]}
    retention: 24h
    columns:
      orders.id: {level: L1}
      orders.amount: {level: L2}
      orders.state: {level: L2}
      orders.customer_id: {categories: [personal]}
      orders.note: {level: L3, categories: [personal], mask: mask}
  job: $small
  deletionPolicy: Delete
YAML
kubectl apply -f "$evidence/source.yaml"
await source-ready 900 lakesource "$source_name" \
  "table('orders').get('state') == 'Ready' and (table('orders').get('governance') or {}).get('governed') is True" || true

step "dashboard $dashboard: alice, bob and carol sign in as its users"
openssl rand -hex 16 | tr -d '\n' >"$state/carol-doris"
kubectl -n "$JOB_NAMESPACE" create secret generic "$dashboard-carol" --from-file=password="$state/carol-doris" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
for u in analyst viewer; do
  openssl rand -hex 16 | tr -d '\n' >"$state/$u-doris"
  kubectl -n "$JOB_NAMESPACE" create secret generic "$dashboard-$u" --from-file=password="$state/$u-doris" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
done
# dashboard_yaml [<bob's sso>]: the declaration; bob signs in unless "" is given.
dashboard_yaml() {
  local default=", sso: {username: $prefix-bob}"
  local bob_sso="${1-$default}"
  cat <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeDashboard
metadata: {name: $dashboard, namespace: $JOB_NAMESPACE}
spec:
  tables: [$ods.orders]
  governance: {owner: bi-team, level: L3, categories: [personal], retention: 24h}
  access: {level: L2}
  queries:
    - {name: live, serve: external, sql: "SELECT id, customer_id, amount, state, note, version_no FROM $ods.orders"}
    - {name: by_state, serve: materialized, refresh: 1m, sql: "SELECT state, COUNT(*) AS n, SUM(amount) AS total FROM $ods.orders GROUP BY state"}
  users:
    - {name: analyst, passwordSecret: {name: $dashboard-analyst}, queries: [live, by_state], rows: "state = 'created'", sso: {username: $prefix-alice}}
    - {name: viewer, passwordSecret: {name: $dashboard-viewer}, queries: [live], rows: "state = 'shipped'"$bob_sso}
    - {name: carol, passwordSecret: {name: $dashboard-carol}, queries: [live], rows: "id <= 10", sso: {username: $prefix-carol}}
YAML
}
dashboard_yaml >"$evidence/dashboard.yaml"
kubectl apply -f "$evidence/dashboard.yaml"
await dashboard-ready 600 lakedashboard "$dashboard" \
  "st.get('phase') == 'Ready' and cond('BiPublished') == 'True' and len(st.get('exposure') or []) == 1" || true
get lakedashboard "$dashboard" "$evidence/status/dashboard.json"
database="$(field "$evidence/status/dashboard.json" 'd["status"]["database"]')"
catalog="$(field "$evidence/status/dashboard.json" 'd["status"]["catalog"]')"
name="$JOB_NAMESPACE/$dashboard"
analyst="$JOB_NAMESPACE.$dashboard.analyst" viewer="$JOB_NAMESPACE.$dashboard.viewer" carol="$JOB_NAMESPACE.$dashboard.carol"
kubectl -n "$BI_NAMESPACE" get secret "$BI_DORIS_USERS_SECRET" -o json \
  | python3 -c 'import base64,json,sys; d=json.load(sys.stdin).get("data") or {}; print(json.dumps({k: {f: v for f, v in json.loads(base64.b64decode(x)).items() if f != "password"} for k, x in d.items()}, indent=1))' \
  >"$evidence/status/published.json"
check published-to-the-bi-interface "$(tr -d '\n ' <"$evidence/status/published.json" | cut -c1-300)" python3 - "$evidence/status/published.json" "$prefix" "$analyst" "$viewer" "$carol" <<'PY'
import json, sys
d, prefix = json.load(open(sys.argv[1])), sys.argv[2]
want = {sys.argv[3]: prefix + "-alice", sys.argv[4]: prefix + "-bob", sys.argv[5]: prefix + "-carol"}
sys.exit(0 if all(d.get(k, {}).get("sso") == v for k, v in want.items()) else 1)
PY

# ----------------------------------------------------------------------------- without a user
step "without a session"
for path in / /sqllab/ /api/v1/database/ /api/v1/security/csrf_token/ /health /signed-out; do
  file="$evidence/http/no-session$(tr '/' '_' <<<"$path").json"
  signin get "$url$path" >"$file"
  check "no-session$(tr '/' '-' <<<"${path%/}")" "$(field "$file" '(d["status"], d["location"][:60])')" \
    python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d["status"] == 302 and d["location"].startswith(sys.argv[2] + "/oauth2/authorize") else 1)' "$file" "$signin"
done

step "sign-in"
# One sign-in for both: carol signs in to the console, then opens the BI interface.
signin login "$(entry_output consoleUrl)/" "$state" "$prefix-carol" >"$evidence/http/login-carol-console.json" || true
for u in alice bob carol dave erin adm; do
  if signin login "$url/" "$state" "$prefix-$u" >"$evidence/http/login-$u.json"; then
    bi api "$url" "$state" "$prefix-$u" GET /api/v1/me/roles/ >"$evidence/http/roles-$u.json"
  else
    verdict "signed-in-$u" FAIL "$(head -c 500 "$evidence/http/login-$u.json")"
  fi
done
check one-sign-in-for-both "$(field "$evidence/http/login-carol.json" '[s for s, _ in d["chain"]]')" python3 - "$evidence/http/login-carol-console.json" "$evidence/http/login-carol.json" <<'PY'
import json, sys
console, bi = json.load(open(sys.argv[1])), json.load(open(sys.argv[2]))
# Signed in at the console with a password and a code; at the BI interface, no sign-in page.
asked = any("/login" in u for _, u in bi["chain"])
sys.exit(0 if console["where"] == "app" and bi["where"] == "app" and not asked else 1)
PY
# roles_are <user> <expected roles, comma-separated, sorted>
roles_are() {
  python3 - "$evidence/http/roles-$1.json" "$2" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
got = sorted((d.get("body") or {}).get("result", {}).get("roles", {}).keys()) if d["status"] == 200 else ["status %s" % d["status"]]
print(",".join(got))
sys.exit(0 if ",".join(got) == sys.argv[2] else 1)
PY
}
check roles-dave "$(roles_are dave ColvennaBI || true)" roles_are dave ColvennaBI
check roles-adm "$(roles_are adm Admin || true)" roles_are adm Admin
check roles-erin-refused "$(field "$evidence/http/roles-erin.json" 'd["status"]')" test "$(field "$evidence/http/roles-erin.json" 'd["status"]')" = 403
# databases_of <user>: the Superset databases the user sees, comma-separated.
databases_of() {
  bi api "$url" "$state" "$prefix-$1" GET /api/v1/database/ \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(",".join(sorted(r["database_name"] for r in (d["body"] or {}).get("result", []))) if d["status"] == 200 else "status %s" % d["status"])'
}
# databases_are <user> <expected>: what databases_of says is that.
# shellcheck disable=SC2317  # run by until_ok
databases_are() { [[ "$(databases_of "$1")" == "$2" ]]; }
until_ok databases-appear-for-alice 240 databases_are alice "$name" || true
for u in bob carol; do
  got="$(databases_of "$u")"
  check "databases-$u" "$got" test "$got" = "$name"
done
# The dashboard's role, once the sync made it: the next request gets it.
for u in alice bob carol; do
  bi api "$url" "$state" "$prefix-$u" GET /api/v1/me/roles/ >"$evidence/http/roles-$u.json"
  check "roles-$u" "$(roles_are "$u" "ColvennaBI,Dashboard $name" || true)" roles_are "$u" "ColvennaBI,Dashboard $name"
done
got="$(databases_of dave)"
check databases-dave-none "${got:-none}" test -z "$got"

step "other ways in"
for path in /api/v1/security/login /api/v1/security/refresh /login/ /api/v1/security/guest_token/; do
  bi api "$url" "$state" "$prefix-alice" POST "$path" '{"username":"x","password":"y","provider":"db"}' >"$evidence/http/other$(tr '/' '_' <<<"$path").json"
  check "refused$(tr '/' '-' <<<"${path%/}")" "$(field "$evidence/http/other$(tr '/' '_' <<<"$path").json" 'd["status"]')" \
    test "$(field "$evidence/http/other$(tr '/' '_' <<<"$path").json" 'd["status"]')" = 404
done
signin get "$url/api/v1/database/" "$state" "$prefix-alice" --header "Authorization:Bearer forged" >"$evidence/http/bearer.json"
check api-token-refused "$(field "$evidence/http/bearer.json" 'd["status"]')" test "$(field "$evidence/http/bearer.json" 'd["status"]')" = 401

# ------------------------------------------------------------------------------ what each reads
step "what each person reads"
q="SELECT id, customer_id, amount, state, note, version_no FROM live ORDER BY id"
for u in alice bob carol; do
  bi sql "$url" "$state" "$prefix-$u" "$name" "$database" "$q" >"$evidence/http/sql-$u.json"
done
# rows_are <user> <python predicate over i (the id) for the rows they may see> <count>
rows_are() {
  python3 - "$evidence/http/sql-$1.json" "$2" "$3" <<'PY'
import json, re, sys
d = json.load(open(sys.argv[1]))
rows = d.get("rows")
if rows is None:
    print("error:", d.get("error"))
    sys.exit(1)
keep = lambda i: eval(sys.argv[2])  # noqa: E731
ok = len(rows) == int(sys.argv[3])
for r in rows:
    i = int(r[0])
    ok &= keep(i) and r[1] is None and float(r[2]) == round(i * 1.5, 2) and r[5] is None
    ok &= re.fullmatch(r"x+ nnn-nnnn", r[4] or "") is not None
print(len(rows), rows[:2])
sys.exit(0 if ok else 1)
PY
}
check alice-reads-her-rows-masked "$(rows_are alice 'i % 2 == 1' 15 || true)" rows_are alice 'i % 2 == 1' 15
check bob-reads-his-rows-masked "$(rows_are bob 'i % 2 == 0' 15 || true)" rows_are bob 'i % 2 == 0' 15
printf '%s\n' "SELECT id, customer_id, amount, state, note, version_no FROM \`$database\`.live ORDER BY id" >"$state/carol.sql"
doris_as "$carol" "$state/carol-doris" "$state/carol.sql" "$evidence/doris/carol.txt"
check carol-reads-as-directly "superset vs mysql client" python3 - "$evidence/http/sql-carol.json" "$evidence/doris/carol.txt" <<'PY'
import json, sys
sup = json.load(open(sys.argv[1])).get("rows") or []
direct = [l.split("\t") for l in open(sys.argv[2]).read().splitlines() if l and not l.startswith("-- ")]
norm = lambda v: "NULL" if v is None else ("%.2f" % v if isinstance(v, float) else str(v))  # noqa: E731
a = [[norm(v) for v in r] for r in sup]
print(len(a), len(direct), a[:1], direct[:1])
sys.exit(0 if a and a == direct and len(a) == 10 else 1)
PY
# refused_sql <label> <user> <sql> <pattern>: refused in SQL Lab, the error matching the pattern
# (Python regex, case-insensitive): the boundary that refused it, not any error.
refused_sql() {
  bi sql "$url" "$state" "$prefix-$2" "$name" "$database" "$3" >"$evidence/http/$1.json"
  check "$1" "$(field "$evidence/http/$1.json" '(d["status"], (d.get("error") or "")[:240])')" \
    python3 -c 'import json,re,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d.get("rows") is None and re.search(sys.argv[2], d.get("error") or "", re.I) else 1)' \
    "$evidence/http/$1.json" "$4"
}
refused_sql lake-table-refused alice "SELECT * FROM \`$catalog\`.\`$ods\`.orders" "denied|unknown catalog"
refused_sql information-schema-refused alice "SELECT table_name FROM information_schema.tables" "denied"
refused_sql query-not-given-refused bob "SELECT * FROM by_state" "denied"
refused_sql writes-refused alice "INSERT INTO live VALUES (99, 1, 1, 'x', 'y', 1)" "DML|only SELECT|denied"
bi sql "$url" "$state" "$prefix-alice" "$name" "$database" "SELECT state, n FROM by_state" >"$evidence/http/sql-alice-by-state.json"
check alice-copy-holds-her-rows "$(field "$evidence/http/sql-alice-by-state.json" 'd.get("rows")')" \
  test "$(field "$evidence/http/sql-alice-by-state.json" 'd.get("rows")')" = "[['created', 15]]"

step "browsing"
db_id="$(bi api "$url" "$state" "$prefix-alice" GET /api/v1/database/ | python3 -c 'import json,sys; print(json.load(sys.stdin)["body"]["result"][0]["id"])')"
bi api "$url" "$state" "$prefix-alice" GET "/api/v1/database/$db_id/schemas/" >"$evidence/http/schemas.json"
check browse-schemas "$(field "$evidence/http/schemas.json" 'd["body"]')" python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d["status"] == 200 and sys.argv[2] in d["body"]["result"] else 1)' "$evidence/http/schemas.json" "$database"
bi api "$url" "$state" "$prefix-alice" GET "/api/v1/database/$db_id/tables/?q=(schema_name:'$database')" >"$evidence/http/tables.json"
check browse-tables "$(field "$evidence/http/tables.json" '[t.get("value") for t in (d["body"] or {}).get("result", [])]')" python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); names={t["value"] for t in d["body"]["result"]}; sys.exit(0 if {"live","by_state"} <= names else 1)' "$evidence/http/tables.json"
bi api "$url" "$state" "$prefix-alice" GET "/api/v1/database/$db_id/table_metadata/?name=live&schema=$database" >"$evidence/http/columns.json"
check browse-columns "$(field "$evidence/http/columns.json" '[c.get("name") for c in (d["body"] or {}).get("columns", [])]')" python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); names=[c["name"] for c in d["body"]["columns"]]; sys.exit(0 if {"id","note","state"} <= set(names) else 1)' "$evidence/http/columns.json"
# Dave sees no database; asked for alice's by its id, Superset refuses (no access to it: it
# never connects). The connection mutator's own refusal shows in chart-refuses-dave.
bi sql-id "$url" "$state" "$prefix-dave" "$db_id" "$database" "$q" >"$evidence/http/sql-dave.json"
check dave-reaches-nothing "$(field "$evidence/http/sql-dave.json" '(d["status"], (d.get("error") or "")[:200])')" \
  python3 -c 'import json,re,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d.get("rows") is None and re.search("no user|access|denied|permission|not found", d.get("error") or "", re.I) else 1)' "$evidence/http/sql-dave.json"

step "a shared chart and its export"
bi dataset "$url" "$state" "$prefix-alice" "$name" "$database" live >"$evidence/http/dataset.json"
dataset="$(field "$evidence/http/dataset.json" 'd.get("id")')"
for u in alice bob; do
  bi chart "$url" "$state" "$prefix-$u" "$dataset" id,state >"$evidence/http/chart-$u.json"
done
check chart-shows-each-viewer-their-rows "$(field "$evidence/http/chart-alice.json" 'len(d.get("rows") or [])') / $(field "$evidence/http/chart-bob.json" 'len(d.get("rows") or [])')" python3 - "$evidence/http" <<'PY'
import json, os, sys
a = json.load(open(os.path.join(sys.argv[1], "chart-alice.json"))).get("rows") or []
b = json.load(open(os.path.join(sys.argv[1], "chart-bob.json"))).get("rows") or []
ok = len(a) == 15 and len(b) == 15 and {r[1] for r in a} == {"created"} and {r[1] for r in b} == {"shipped"}
sys.exit(0 if ok else 1)
PY
bi chart "$url" "$state" "$prefix-bob" "$dataset" id,state csv >"$evidence/http/export-bob.json"
check export-is-the-viewers-rows "$(field "$evidence/http/export-bob.json" '(d.get("csv") or "")[:80]')" python3 -c 'import json,sys; c=json.load(open(sys.argv[1])).get("csv") or ""; sys.exit(0 if "shipped" in c and "created" not in c else 1)' "$evidence/http/export-bob.json"
bi chart "$url" "$state" "$prefix-dave" "$dataset" id,state >"$evidence/http/chart-dave.json"
check chart-refuses-dave "$(field "$evidence/http/chart-dave.json" 'd["status"]')" python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if not d.get("rows") else 1)' "$evidence/http/chart-dave.json"
# A table chart alice saves on a published dashboard: each viewer, as the dashboard asks for it
# (not forced past a cache), gets their own rows; dave none.
bi save "$url" "$state" "$prefix-alice" "$dataset" id,state "orders $run" >"$evidence/http/saved.json"
board="$(field "$evidence/http/saved.json" 'd.get("dashboard")')"
for u in alice bob alice bob dave; do
  bi saved "$url" "$state" "$prefix-$u" "$board" >>"$evidence/http/saved-views.jsonl"
done
check saved-dashboard-shows-each-viewer-their-rows "$(python3 -c 'import json,sys; print([[(c["status"], len(c["rows"] or [])) for c in json.loads(l).get("charts", [])] or json.loads(l).get("status") for l in open(sys.argv[1])])' "$evidence/http/saved-views.jsonl")" python3 - "$evidence/http/saved-views.jsonl" <<'PY'
import json, sys
views = [json.loads(l) for l in open(sys.argv[1])]
def states(v):
    charts = v.get("charts") or []
    return [{r["state"] for r in (c["rows"] or [])} for c in charts], [len(c["rows"] or []) for c in charts]
alice1, bob1, alice2, bob2, dave = views
ok = all(states(v) == ([{"created"}], [15]) for v in (alice1, alice2))
ok &= all(states(v) == ([{"shipped"}], [15]) for v in (bob1, bob2))
ok &= not any(c.get("rows") for c in dave.get("charts") or [])
sys.exit(0 if ok else 1)
PY
# SQL Lab's CSV export: alice's own query is her rows; bob exporting alice's query is not.
alice_query="$(field "$evidence/http/sql-alice.json" 'd["client_id"]')"
bi export "$url" "$state" "$prefix-alice" "$alice_query" >"$evidence/http/export-alice.json"
bi export "$url" "$state" "$prefix-bob" "$alice_query" >"$evidence/http/export-bob-of-alice.json"
check sqllab-export-is-the-exporters "$(field "$evidence/http/export-alice.json" '((d.get("csv") or "")[:60], d["status"])') / $(field "$evidence/http/export-bob-of-alice.json" '((d.get("csv") or d.get("error") or "")[:80], d["status"])')" python3 - "$evidence/http" <<'PY'
import json, os, sys
a = json.load(open(os.path.join(sys.argv[1], "export-alice.json")))
b = json.load(open(os.path.join(sys.argv[1], "export-bob-of-alice.json")))
ok = a["status"] == 200 and "created" in (a.get("csv") or "") and "shipped" not in (a.get("csv") or "")
ok &= "created" not in (b.get("csv") or "")
sys.exit(0 if ok else 1)
PY
bi api "$url" "$state" "$prefix-bob" GET "/api/v1/sqllab/results/?q=(key:'$(uuidgen)')" >"$evidence/http/results.json"
check no-results-backend "$(field "$evidence/http/results.json" '(d["status"], str(d["body"])[:120])')" python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d["status"] != 200 and "RESULTS_BACKEND_NOT_CONFIGURED_ERROR" in json.dumps(d["body"]) else 1)' "$evidence/http/results.json"
bi api "$url" "$state" "$prefix-adm" GET /api/v1/report/ >"$evidence/http/reports.json"
check reports-off "$(field "$evidence/http/reports.json" 'd["status"]')" python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d["status"] in (403, 404) else 1)' "$evidence/http/reports.json"

# ------------------------------------------------------------------------------- bypass
step "bypass"
pod="$(kubectl -n "$BI_NAMESPACE" get pods -l app.kubernetes.io/name=colvenna-bi -o jsonpath='{.items[0].metadata.name}')"
pod_ip="$(kubectl -n "$BI_NAMESPACE" get pod "$pod" -o jsonpath='{.status.podIP}')"
cat >"$state/probe.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: bi-probe-$run, namespace: $JOB_NAMESPACE}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 100, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: curl
      image: $CURL_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: [ALL]}}
      command: ["sh", "-c"]
      args:
        - |
          for target in https://$pod_ip:8443/health https://$BI_RELEASE.$BI_NAMESPACE.svc:8443/health \\
                        https://colvenna-catalog.$CATALOG_NAMESPACE.svc:8181/v1/config; do
            out="\$(curl -sk -o /dev/null -m 10 -w '%{http_code} %{time_connect}' "\$target")"
            echo "\$target \$out \$?"
          done
      resources: {requests: {cpu: 10m, memory: 32Mi}, limits: {memory: 64Mi}}
YAML
kubectl apply -f "$state/probe.yaml" >/dev/null
kubectl -n "$JOB_NAMESPACE" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/bi-probe-$run" --timeout=3m >/dev/null || true
kubectl -n "$JOB_NAMESPACE" logs "bi-probe-$run" >"$evidence/http/in-cluster.txt" 2>&1 || true
kubectl -n "$JOB_NAMESPACE" delete pod "bi-probe-$run" --ignore-not-found >/dev/null
check pod-unreachable-in-cluster "$(tr '\n' ';' <"$evidence/http/in-cluster.txt")" python3 - "$evidence/http/in-cluster.txt" <<'PY'
import sys
lines = [l.split() for l in open(sys.argv[1]) if l.strip()]
ok = (len(lines) == 3 and all(l[1:] == ["000", "0.000000", "28"] for l in lines[:2])
      and lines[2][1] == "401" and lines[2][3] == "0")
sys.exit(0 if ok else 1)
PY
forward_log="$(mktemp)"
kubectl -n "$BI_NAMESPACE" port-forward "pod/$pod" :8443 >"$forward_log" 2>&1 &
forward_pid=$!
pod_port="$(forwarded_port "$forward_log")"
forged="$(printf '{"alg":"ES256","kid":"%s","signer":"%s"}' "$(uuidgen)" "$(entry_output LoadBalancerArn)" | base64 -w0 | tr '+/' '-_').e30.c2ln"
results=""
for headers in "" "$forged"; do
  code="$(curl -sk -o /dev/null -w '%{http_code}' ${headers:+-H "X-Amzn-Oidc-Data: $headers" -H "X-Amzn-Oidc-Accesstoken: $headers"} "https://127.0.0.1:$pod_port/api/v1/database/")"
  results+="$code "
done
health="$(curl -sk -o /dev/null -w '%{http_code}' "https://127.0.0.1:$pod_port/health")"
kill "$forward_pid" 2>/dev/null
forward_pid=""
check pod-refuses-forged-identity "none, forged: $results; health $health" test "$results|$health" = "401 401 |200"

# ------------------------------------------------------------------------------- Doris's audit
step "doris audit"
# Doris writes its audit log in batches; give it a minute.
sleep 60
cat >"$state/audit.sql" <<SQL
SELECT \`user\`, client_ip, LEFT(stmt, 160) FROM __internal_schema.audit_log
WHERE \`time\` > NOW() - INTERVAL $((SECONDS - began + 120)) SECOND AND (stmt LIKE '%colvenna-bi user=$prefix-%' OR \`user\` IN ('$analyst', '$viewer', '$carol'))
ORDER BY \`time\`;
SQL
doris_sql "$state/audit.sql" >"$evidence/doris/audit.tsv" || true
superset_ip="$pod_ip"
check doris-audit-names-each-person "$(wc -l <"$evidence/doris/audit.tsv") rows" python3 - "$evidence/doris/audit.tsv" "$prefix" "$analyst" "$viewer" "$carol" "$superset_ip" <<'PY'
import re, sys
rows = [l.rstrip("\n").split("\t", 2) for l in open(sys.argv[1]) if l.strip()]
prefix, mapping, ip = sys.argv[2], {"alice": sys.argv[3], "bob": sys.argv[4], "carol": sys.argv[5]}, sys.argv[6]
# What the driver and Superset send on their own: the connection's settings and browsing.
own = re.compile(r"\s*(SET |ROLLBACK|SHOW |SELECT (@@|DATABASE\(\)|CONNECTION_ID\(\)|VERSION\(\)))", re.I)
seen, bad = set(), []
for user, client, stmt in rows:
    m = re.search(r"colvenna-bi user=%s-(\w+) " % re.escape(prefix), stmt)
    if m:
        person = m.group(1)
        if mapping.get(person) != user:
            bad.append((person, user))
        seen.add(person)
    elif client.split(":")[0] == ip and (user not in mapping.values() or not own.match(stmt)):
        bad.append(("unannotated", user, stmt[:60]))
print(sorted(seen), bad[:3])
sys.exit(0 if seen >= {"alice", "bob", "carol"} and not bad else 1)
PY
cat >"$state/audit-others.sql" <<SQL
SELECT DISTINCT \`user\` FROM __internal_schema.audit_log
WHERE \`time\` > NOW() - INTERVAL $((SECONDS - began + 120)) SECOND AND client_ip LIKE '$superset_ip:%';
SQL
doris_sql "$state/audit-others.sql" >"$evidence/doris/audit-users.tsv" || true
check no-other-doris-user-from-the-bi-interface "$(tr '\n' ',' <"$evidence/doris/audit-users.tsv")" python3 - "$evidence/doris/audit-users.tsv" "$analyst" "$viewer" "$carol" <<'PY'
import sys
users = {l.strip() for l in open(sys.argv[1]) if l.strip()}
sys.exit(0 if users and users <= set(sys.argv[2:]) else 1)
PY

# ------------------------------------------------------------------------- access that ends
step "bob's sign-in name taken off the dashboard"
dashboard_yaml "" >"$evidence/dashboard-without-bob.yaml"
kubectl apply -f "$evidence/dashboard-without-bob.yaml"
until_ok bob-loses-the-database 300 databases_are bob "" || true
bi sql-id "$url" "$state" "$prefix-bob" "$db_id" "$database" "$q" >"$evidence/http/sql-bob-after.json"
check bob-queries-refused-after "$(field "$evidence/http/sql-bob-after.json" '(d["status"], (d.get("error") or "")[:200])')" \
  python3 -c 'import json,re,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d.get("rows") is None and re.search("no user|access|denied|permission|not found", d.get("error") or "", re.I) else 1)' "$evidence/http/sql-bob-after.json"
bi saved "$url" "$state" "$prefix-bob" "$board" >"$evidence/http/saved-bob-after.json"
check bob-sees-no-rows-of-the-dashboard-after "$(field "$evidence/http/saved-bob-after.json" 'd.get("status")')" \
  python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if not any(c.get("rows") for c in d.get("charts") or []) else 1)' "$evidence/http/saved-bob-after.json"

step "sign-out"
signin logout "$url/" "$state" "$prefix-carol" >"$evidence/http/logout.json" || true
check logout-returns-to-sign-in "$(head -c 300 "$evidence/http/logout.json")" python3 - "$evidence/http/logout.json" "$signin" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
chain = [u for _, u in d["chain"]]
landing = any(u.endswith("/signed-out") for u in chain)
sys.exit(0 if d["loginForm"] and landing and any(u.startswith(sys.argv[2] + "/logout") for u in chain) else 1)
PY

step "the dashboard deleted"
kubectl -n "$JOB_NAMESPACE" delete lakedashboard "$dashboard" --wait=true --timeout=5m
entries="$(kubectl -n "$BI_NAMESPACE" get secret "$BI_DORIS_USERS_SECRET" -o json | python3 -c 'import base64,json,sys; d=json.load(sys.stdin).get("data") or {}; print(sum(1 for k in d if k.startswith(sys.argv[1] + ".")))' "$JOB_NAMESPACE.$dashboard")"
check credentials-withdrawn "$entries entries left" test "$entries" = 0
until_ok alice-loses-the-database 300 databases_are alice "" || true

step "clean up"
kubectl -n "$JOB_NAMESPACE" delete lakesource "$source_name" --wait=true --timeout=10m || true
kubectl -n "$JOB_NAMESPACE" delete secret "$dashboard-analyst" "$dashboard-viewer" "$dashboard-carol" --ignore-not-found >/dev/null
if python3 "$cdc/mysql_contract.py" reset "$db" >"$state/drop.sql"; then
  bash "$FIXTURES_DIR/sources/sources.sh" mysql "$state/drop.sql" >/dev/null || true
fi

step "done"
echo "verdicts: $(grep -c PASS "$evidence/verdicts.tsv") PASS, $(grep -c FAIL "$evidence/verdicts.tsv") FAIL"
exit "$failed"
