#!/usr/bin/env bash
# Acceptance on EKS: column labels, masks and row filters as Doris enforces them for a dashboard's
# users, and what nobody reaches. Writes PASS/FAIL lines to <evidence>/verdicts.tsv and fails if
# any check fails.
#
# - A MySQL source declares orders L3 personal and labels their columns: id L1, amount and state
#   L2, customer_id and note personal (redacted, masked); version_no has no label, so it is
#   labelled as the table. A dashboard copies them (L3 personal) for users cleared for L2: they read
#   through the view the operator makes, which redacts customer_id and version_no and masks note.
# - The analyst may see the created orders only, the viewer has no condition and so sees no rows;
#   a user not declared does not log in.
# - Denied to the users: the lake table, its system tables and the masking view itself; DDL, a
#   row policy of their own, writes; the warehouse by Doris's table functions (s3() with the
#   default chain, the node's role or another role; local(); http() to the instance metadata or
#   colvenna-catalog) or INTO OUTFILE; a wrong or empty password. A pod with no AWS identity of its
#   own reads no table file. An error a user's predicate raises tells no hidden row; EXPLAIN shows
#   the plan, not the rows.
# - A column added at the source is redacted until labelled, in the view and for the analyst; a
#   label above its table's level stops the table's publication and the dashboard (its users
#   withdrawn), until it is fixed.
#
# usage: run-enforcement.sh <run-id> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-enforcement.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-enforcement.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
cdc="$ACCEPTANCE_DIR/cdc"
mkdir -p "$evidence"/{status,sql,doris}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
for image in operator mysql; do
  echo "$image $(image_ref "$image")" | tee -a "$evidence/images.txt"
done
failed=0
step() { echo "$(date -u +%FT%TZ)	$*" | tee -a "$evidence/timeline.tsv"; }
verdict() {
  local why="${3:-}"
  why="${why//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${why//$'\t'/ }" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS ]] || failed=1
}
db="enf_$run" ods="ods_enf_$run" source_name="ge-$run" dashboard="gd-$run"
bi="colvenna-bi-client" fe="$DORIS_CLUSTER-fe-service.$DORIS_NAMESPACE.svc"
small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
work="$(mktemp -d)"
chmod 700 "$work"
trap 'admin_close; rm -rf "$work"' EXIT

get() { kubectl -n "$JOB_NAMESPACE" get "$1" "$2" -o json >"$3"; }

# await <label> <timeout-seconds> <kind> <name> <python expression over st, cond(), reason()>
await() {
  local label="$1" end=$((SECONDS + $2)) started=$SECONDS file="$evidence/status/$1.json"
  while ((SECONDS < end)); do
    if get "$3" "$4" "$file" 2>/dev/null && python3 - "$file" "$5" <<'PY'; then
import json, sys
st = json.load(open(sys.argv[1])).get("status") or {}
def cond(t):
    return next((c.get("status") for c in st.get("conditions", []) if c.get("type") == t), None)
def reason(t):
    return next((c.get("reason") for c in st.get("conditions", []) if c.get("type") == t), None)
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
  python3 -c 'import json,sys; s=json.load(open(sys.argv[1])).get("status",{}); print(s.get("phase"), s.get("message"))' "$file" || true
  return 1
}

# doris_as <user> <password-file> <sql-file> <out>: each line of the file run as that user, each
# answer after a "-- <statement>" line, an error as "ERROR <message>".
doris_as() {
  local user="$1" pod="gd-as-$RANDOM"
  kubectl -n "$bi" create secret generic "$pod" --from-file=password="$2" --from-file=run.sql="$3" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  cat >"$work/$pod.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $pod, namespace: $bi}
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
            mysql -h $fe -P 9030 -u '$user' --connect-timeout=10 --batch --skip-column-names -e "\$q" 2>&1 \
              | sed 's/^ERROR \([0-9]*\) ([A-Z0-9]*)[^:]*: /ERROR \1 /'
          done < /in/run.sql
      resources: {requests: {cpu: 10m, memory: 64Mi}, limits: {memory: 128Mi}}
      volumeMounts: [{name: in, mountPath: /in, readOnly: true}]
  volumes: [{name: in, secret: {secretName: $pod}}]
YAML
  run_pod "$bi" "$pod" "$work/$pod.yaml" >"$4" || true
  kubectl -n "$bi" delete secret "$pod" --ignore-not-found >/dev/null
}

# answer <out> <statement>: the statement's lines in a doris_as output (ERROR... when it failed).
answer() {
  python3 - "$1" "$2" <<'PY'
import sys
lines = open(sys.argv[1]).read().splitlines()
want, out, inside = "-- " + sys.argv[2], [], False
for line in lines:
    if line.startswith("-- "):
        if inside:
            break
        inside = line == want
    elif inside:
        out.append(line)
print("\n".join(out))
PY
}

# refused <label> <out> <statement>: the statement failed.
# refused <label> <out> <statement> <pattern>: the statement failed, its error matching the
# pattern (grep -E, case-insensitive): the boundary that refused it, not any error. A login
# failure (ERROR 1045) counts only where the pattern asks for one.
refused() {
  local got
  got="$(answer "$2" "$3")"
  if [[ "$got" == "ERROR 1045 "* && "$4" != *"access denied for user"* ]]; then
    verdict "$1" FAIL "could not log in: $(head -1 <<<"$got" | cut -c1-200)"
  elif [[ "$got" == ERROR* ]] && grep -qiE "$4" <<<"$got"; then
    verdict "$1" PASS "$(head -1 <<<"$got" | cut -c1-240)"
  else
    verdict "$1" FAIL "answered: $(head -3 <<<"$got" | tr '\n' ' ' | cut -c1-240)"
  fi
}

# field_of <json-file> <python expression over s>: one value of a status, empty when it has none.
field_of() {
  python3 -c 'import json,sys; s=json.load(open(sys.argv[1])).get("status") or {}
try: print(eval(sys.argv[2]))
except (KeyError, IndexError, TypeError): print("")' "$1" "$2" || true
}

# --------------------------------------------------------------------------------- the source
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
labels() {
  cat <<YAML
    columns:
      orders.id: {level: L1}
      orders.amount: {level: L2}
      orders.state: {level: L2}
      orders.customer_id: {categories: [personal]}
      orders.note: {level: $1, categories: [personal], mask: mask}
YAML
}
source_yaml() {
  cat <<YAML
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
$(labels "$1")
  job: $small
  deletionPolicy: Delete
YAML
}
source_yaml L3 >"$evidence/source.yaml"
kubectl apply -f "$evidence/source.yaml"
await source-ready 900 lakesource "$source_name" \
  "table('orders').get('state') == 'Ready' and (table('orders').get('governance') or {}).get('governed') is True" || true

# ------------------------------------------------------------------------------ the dashboard
step "dashboard $dashboard: copies L3 personal, users cleared for L2; the analyst's rows"
kubectl create namespace "$bi" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label namespace "$bi" --overwrite pod-security.kubernetes.io/enforce=restricted \
  colvenna-lake.io/doris-client=true >/dev/null
for user in analyst viewer; do
  openssl rand -hex 16 | tr -d '\n' >"$work/$user"
  kubectl -n "$JOB_NAMESPACE" create secret generic "$dashboard-$user" --from-file=password="$work/$user" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
done
cat >"$evidence/dashboard.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeDashboard
metadata: {name: $dashboard, namespace: $JOB_NAMESPACE}
spec:
  tables: [$ods.orders]
  governance: {owner: bi-team, level: L3, categories: [personal], retention: 24h}
  access: {level: L2}
  queries:
    - {name: live, serve: external, sql: "SELECT id, customer_id, amount, state, note, version_no FROM $ods.orders"}
    - {name: everything, serve: external, sql: "SELECT * FROM $ods.orders"}
    - {name: by_state, serve: materialized, refresh: 1m, sql: "SELECT state, COUNT(*) AS n, SUM(amount) AS total FROM $ods.orders GROUP BY state"}
  users:
    - {name: analyst, passwordSecret: {name: $dashboard-analyst}, queries: [live, by_state, everything], rows: "state = 'created'"}
    - {name: viewer, passwordSecret: {name: $dashboard-viewer}, queries: [live]}
YAML
kubectl apply -f "$evidence/dashboard.yaml"
await dashboard-ready 600 lakedashboard "$dashboard" \
  "st.get('phase') == 'Ready' and len(st.get('rowPolicies') or {}) == 4 and len(st.get('exposure') or []) == 1" || true
get lakedashboard "$dashboard" "$evidence/status/dashboard.json"
database="$(field_of "$evidence/status/dashboard.json" 's["database"]')"
catalog="$(field_of "$evidence/status/dashboard.json" 's["catalog"]')"
view="$(field_of "$evidence/status/dashboard.json" 's["exposure"][0]["view"]')"
masked="$(field_of "$evidence/status/dashboard.json" '",".join(s["exposure"][0]["masked"])')"
if [[ "$masked" == "customer_id:redact,note:mask,version_no:redact" ]]; then
  verdict masks-as-labelled PASS "$view masks $masked"
else
  verdict masks-as-labelled FAIL "masked: $masked"
fi
analyst="$JOB_NAMESPACE.$dashboard.analyst" viewer="$JOB_NAMESPACE.$dashboard.viewer"

step "what the users see: masked columns, their rows"
cat >"$work/allowed.sql" <<SQL
SELECT id, customer_id, amount, state, note, version_no FROM \`$database\`.live ORDER BY id
SELECT state, n FROM \`$database\`.by_state ORDER BY state
SQL
# The copy refreshes every minute; the first refresh may not have run yet.
end=$((SECONDS + 300))
while ((SECONDS < end)); do
  doris_as "$analyst" "$work/analyst" "$work/allowed.sql" "$evidence/doris/analyst.txt"
  [[ -n "$(answer "$evidence/doris/analyst.txt" "SELECT state, n FROM \`$database\`.by_state ORDER BY state")" ]] && break
  sleep 20
done
answer "$evidence/doris/analyst.txt" "SELECT id, customer_id, amount, state, note, version_no FROM \`$database\`.live ORDER BY id" \
  >"$evidence/doris/analyst-live.tsv"
if python3 - "$evidence/doris/analyst-live.tsv" <<'PY'; then
import re, sys
rows = [l.split("\t") for l in open(sys.argv[1]).read().splitlines() if l]
ok = len(rows) == 15
for r in rows:
    i = int(r[0])
    ok &= i % 2 == 1                                           # the created orders only
    ok &= r[1] == "NULL"                                       # customer_id redacted
    ok &= r[2] == "%.2f" % (i * 1.5)                           # amount clear
    ok &= r[3] == "created"
    ok &= re.fullmatch(r"x+ nnn-nnnn", r[4]) is not None       # note masked
    ok &= r[5] == "NULL"                                       # version_no redacted
print(len(rows), rows[:2])
sys.exit(0 if ok else 1)
PY
  verdict analyst-sees-masked-columns-of-its-rows PASS "$(head -2 "$evidence/doris/analyst-live.tsv" | tr '\t\n' '| ' | cut -c1-240)"
else
  verdict analyst-sees-masked-columns-of-its-rows FAIL "$(head -3 "$evidence/doris/analyst-live.tsv" | tr '\t\n' '| ' | cut -c1-300)"
fi
got="$(answer "$evidence/doris/analyst.txt" "SELECT state, n FROM \`$database\`.by_state ORDER BY state")"
if [[ "$got" == "created	15" ]]; then
  verdict analyst-copy-holds-its-rows-only PASS "by_state: $got"
else
  verdict analyst-copy-holds-its-rows-only FAIL "by_state: $(tr '\n' ' ' <<<"$got")"
fi
cat >"$work/viewer.sql" <<SQL
SELECT COUNT(*) FROM \`$database\`.live
SQL
doris_as "$viewer" "$work/viewer" "$work/viewer.sql" "$evidence/doris/viewer.txt"
got="$(answer "$evidence/doris/viewer.txt" "SELECT COUNT(*) FROM \`$database\`.live")"
if [[ "$got" == 0 ]]; then
  verdict user-without-a-condition-sees-no-rows PASS "the viewer counts $got rows"
else
  verdict user-without-a-condition-sees-no-rows FAIL "the viewer counts $got"
fi
openssl rand -hex 16 | tr -d '\n' >"$work/nobody"
doris_as "$JOB_NAMESPACE.$dashboard.nobody" "$work/nobody" "$work/viewer.sql" "$evidence/doris/nobody.txt"
refused undeclared-user-cannot-log-in "$evidence/doris/nobody.txt" "SELECT COUNT(*) FROM \`$database\`.live" "access denied for user"
# A declared user without its password, or with none at all.
doris_as "$analyst" "$work/nobody" "$work/viewer.sql" "$evidence/doris/wrong-password.txt"
refused wrong-password-cannot-log-in "$evidence/doris/wrong-password.txt" "SELECT COUNT(*) FROM \`$database\`.live" "access denied for user"
: >"$work/empty"
doris_as "$analyst" "$work/empty" "$work/viewer.sql" "$evidence/doris/empty-password.txt"
refused empty-password-cannot-log-in "$evidence/doris/empty-password.txt" "SELECT COUNT(*) FROM \`$database\`.live" "access denied for user"

step "what the users cannot reach"
root="$(helm -n "$CATALOG_NAMESPACE" get values colvenna-catalog -o json | python3 -c 'import json,sys; print(json.load(sys.stdin)["warehouse"]["root"])')"
bucket="$(cut -d/ -f3 <<<"$root")"
printf 'SHOW BACKENDS;\n' >"$work/backends.sql"
backend="$(doris_sql "$work/backends.sql" | cut -f1 | head -1 || true)"
# A role that may read the warehouse (Polaris's), which Doris's role may not assume.
read_role="$(stack_output "$OPEN_LAYER_STACK" IcebergReadRoleArn)"
s3props="'format' = 'parquet', 's3.region' = '$AWS_REGION', 's3.endpoint' = 'https://s3.$AWS_REGION.amazonaws.com'"
denied_privilege="does not have privilege|privilege\\(s\\)|command denied"
denied_aws="not authorized to perform|accessdenied|unauthorized"
# label, the pattern its error must match, the statement: tab-separated.
cat >"$work/denied.txt" <<SQL
lake-table	$denied_privilege	SELECT * FROM \`$catalog\`.\`$ods\`.orders LIMIT 1
system-table	$denied_privilege	SELECT * FROM \`$catalog\`.\`$ods\`.\`orders\$audit_log\` LIMIT 1
masking-view	$denied_privilege	SELECT * FROM \`${view%%.*}\`.\`${view#*.}\` LIMIT 1
view-definition	command denied	SHOW CREATE VIEW \`$database\`.live
create-view	$denied_privilege	CREATE VIEW \`$database\`.mine AS SELECT 1
write	command denied	INSERT INTO \`$database\`.by_state VALUES ('x', 1, 1)
own-row-policy	$denied_privilege	CREATE ROW POLICY mine ON \`$database\`.live AS PERMISSIVE TO '$analyst'@'%' USING (1 = 1)
s3-default-chain	$denied_aws	SELECT * FROM s3('uri' = '$root/*', $s3props) LIMIT 1
s3-instance-profile	$denied_aws	SELECT * FROM s3('uri' = '$root/*', $s3props, 's3.credentials_provider_type' = 'INSTANCE_PROFILE') LIMIT 1
s3-another-role	$denied_aws	SELECT * FROM s3('uri' = '$root/*', $s3props, 's3.role_arn' = '$read_role') LIMIT 1
local-files	failed to glob .*user_files_none	SELECT * FROM local('file_path' = 'storage/*', 'backend_id' = '$backend', 'format' = 'csv') LIMIT 1
local-absolute-path	failed to glob .*user_files_none	SELECT * FROM local('file_path' = '/opt/apache-doris/be/conf/be.conf', 'backend_id' = '$backend', 'format' = 'csv') LIMIT 1
local-parent-path	can not contain	SELECT * FROM local('file_path' = '../log/be.INFO', 'backend_id' = '$backend', 'format' = 'csv') LIMIT 1
paimon-meta	$denied_privilege|can not found function	SELECT * FROM paimon_meta('table' = '$catalog.$ods.orders', 'query_type' = 'files') LIMIT 1
http-instance-metadata	401	SELECT * FROM http('uri' = 'http://169.254.169.254/latest/meta-data/', 'format' = 'csv') LIMIT 1
http-colvenna-catalog	401	SELECT * FROM http('uri' = 'https://colvenna-catalog.$CATALOG_NAMESPACE.svc:8181/v1/config', 'format' = 'json') LIMIT 1
outfile-to-the-warehouse	failed to put object|$denied_aws	SELECT * FROM \`$database\`.live INTO OUTFILE '$root/enf-outfile-$run/' FORMAT AS CSV PROPERTIES ('s3.region' = '$AWS_REGION', 's3.endpoint' = 'https://s3.$AWS_REGION.amazonaws.com')
SQL
{ echo "SELECT CURRENT_USER()"; cut -f3 "$work/denied.txt"; } >"$work/denied.sql"
doris_as "$analyst" "$work/analyst" "$work/denied.sql" "$evidence/doris/denied.txt"
got="$(answer "$evidence/doris/denied.txt" "SELECT CURRENT_USER()")"
if [[ "$got" == *"$analyst"* ]]; then
  verdict analyst-logged-in-for-the-denials PASS "$got"
else
  verdict analyst-logged-in-for-the-denials FAIL "$(cut -c1-200 <<<"$got")"
fi
while IFS=$'\t' read -r label pattern q; do
  refused "denied-$label" "$evidence/doris/denied.txt" "$q" "$pattern"
done <"$work/denied.txt"
cat >"$work/nocreds.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: gd-nocreds-$run}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 1000, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: aws
      image: $AWS_CLI_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      env: [{name: HOME, value: /tmp}, {name: AWS_REGION, value: $AWS_REGION}]
      command: ["sh", "-c", "aws s3 ls s3://$bucket/ >/tmp/out 2>&1; echo exit=\$?; head -3 /tmp/out"]
YAML
run_pod "$bi" "gd-nocreds-$run" "$work/nocreds.yaml" >"$evidence/status/nocreds.txt" 2>&1 || true
# It ran, failed, and for want of credentials: not a pod that never started.
if grep -q '^exit=[1-9]' "$evidence/status/nocreds.txt" \
  && grep -q 'Unable to locate credentials' "$evidence/status/nocreds.txt"; then
  verdict no-direct-storage PASS "$(grep -m1 'Unable to locate' "$evidence/status/nocreds.txt" | cut -c1-160)"
else
  verdict no-direct-storage FAIL "$(tr '\n' ' ' <"$evidence/status/nocreds.txt" | cut -c1-240)"
fi

# What a user learns without being granted it. Doris has no security barrier: a predicate of the
# user's that raised an error on a row its policy hides would tell that row apart. Probed on the
# view and on the copy, and in a CASE; and the plan EXPLAIN shows.
probe_view="SELECT COUNT(*) FROM \`$database\`.live WHERE assert_true(state = 'created', 'probe')"
probe_copy="SELECT COUNT(*) FROM \`$database\`.by_state WHERE assert_true(state = 'created', 'probe')"
probe_case="SELECT COUNT(*) FROM \`$database\`.live WHERE CASE WHEN state = 'shipped' THEN assert_true(false, 'probe') ELSE true END"
# The probe fires on a row the analyst sees: it would tell a hidden one.
probe_seen="SELECT COUNT(*) FROM \`$database\`.live WHERE assert_true(state <> 'created', 'probe')"
printf '%s\n' "$probe_seen" "$probe_view" "$probe_copy" "$probe_case" "EXPLAIN SELECT * FROM \`$database\`.live" >"$work/probe.sql"
doris_as "$analyst" "$work/analyst" "$work/probe.sql" "$evidence/doris/probe.txt"
got="$(answer "$evidence/doris/probe.txt" "$probe_seen")"
if [[ "$got" == ERROR*probe* ]]; then
  verdict row-filter-probe-fires-on-a-seen-row PASS "$(cut -c1-160 <<<"$got")"
else
  verdict row-filter-probe-fires-on-a-seen-row FAIL "$(cut -c1-160 <<<"$got")"
fi
for shape in view copy case; do
  q="probe_$shape"
  got="$(answer "$evidence/doris/probe.txt" "${!q}")"
  if [[ "$got" =~ ^[0-9]+$ ]]; then
    verdict "row-filter-probe-sees-no-hidden-row-$shape" PASS "no error, $got rows: the policy came first"
  else
    verdict "row-filter-probe-sees-no-hidden-row-$shape" FAIL "$(cut -c1-200 <<<"$got")"
  fi
done
plan="$(answer "$evidence/doris/probe.txt" "EXPLAIN SELECT * FROM \`$database\`.live")"
# The plan holds the masks, the policy's predicate and estimates (rows hidden included), no value:
# not the notes in clear.
if grep -qi 'mask(' <<<"$plan" && ! grep -q '555-01' <<<"$plan"; then
  verdict explain-shows-the-plan-not-the-rows PASS "$(grep -o 'cardinality=[0-9]*' <<<"$plan" | head -1); masks and the policy's predicate; no note in clear"
else
  verdict explain-shows-the-plan-not-the-rows FAIL "$(head -c 300 <<<"$plan")"
fi

cat >"$work/ports.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: gd-ports-$run}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 999, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: probe
      image: $MYSQL_CLIENT_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["bash", "-c"]
      args:
        - |
          for port in 9030 8030 8070; do
            if timeout 8 bash -c "</dev/tcp/$fe/\$port" 2>/dev/null; then echo "\$port open"; else echo "\$port closed"; fi
          done
YAML
run_pod "$bi" "gd-ports-$run" "$work/ports.yaml" >"$evidence/status/ports.txt" 2>&1 || true
if [[ "$(tr '\n' ' ' <"$evidence/status/ports.txt")" == "9030 open 8030 closed 8070 closed " ]]; then
  verdict only-the-mysql-protocol PASS "from $bi: $(tr '\n' ' ' <"$evidence/status/ports.txt")"
else
  verdict only-the-mysql-protocol FAIL "from $bi: $(tr '\n' ' ' <"$evidence/status/ports.txt")"
fi

# --------------------------------------------------------------------------- declared rules
step "a column added at the source is redacted until labelled"
cat >"$evidence/sql/add-column.sql" <<SQL
USE $db;
ALTER TABLE orders ADD COLUMN phone VARCHAR(16) NULL;
UPDATE orders SET phone = '555-0199', version_no = version_no + 1 WHERE id <= 4;
SQL
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/add-column.sql" >/dev/null
await new-column-redacted 600 lakedashboard "$dashboard" \
  "'phone:redact' in ((st.get('exposure') or [{}])[0].get('masked') or [])" || true
# In Doris itself: the masking view redacts it.
echo "SHOW CREATE VIEW \`${view%%.*}\`.\`${view#*.}\`;" >"$work/show-view.sql"
doris_sql "$work/show-view.sql" >"$evidence/doris/masking-view.txt" 2>&1 || true
# Doris writes the view's names out in full: NULLIF(`<catalog>`.`<db>`.`orders`.`phone`, ...).
redacted="NULLIF\\([^,]*\`phone\`, [^)]*\`phone\`\\) AS \`phone\`"
if grep -qE "$redacted" "$evidence/doris/masking-view.txt"; then
  verdict new-column-redacted-in-doris PASS "the masking view redacts phone with NULLIF"
else
  verdict new-column-redacted-in-doris FAIL "$(head -c 300 "$evidence/doris/masking-view.txt")"
fi
# And for the analyst: the SELECT * object, made anew with the view, has the column, redacted.
phones="^[1-9][0-9]*"$'\t'"0$"
end=$((SECONDS + 180))
while ((SECONDS < end)); do
  echo "SELECT COUNT(*), COUNT(phone) FROM \`$database\`.everything" >"$work/phone.sql"
  doris_as "$analyst" "$work/analyst" "$work/phone.sql" "$evidence/doris/analyst-phone.txt"
  got="$(answer "$evidence/doris/analyst-phone.txt" "SELECT COUNT(*), COUNT(phone) FROM \`$database\`.everything")"
  [[ "$got" =~ $phones ]] && break
  sleep 15
done
if [[ "$got" =~ $phones ]]; then
  verdict new-column-redacted-for-the-analyst PASS "rows and non-NULL phones: ${got//$'\t'/ }"
else
  verdict new-column-redacted-for-the-analyst FAIL "$(cut -c1-200 <<<"$got")"
fi

step "a label above its table's level stops the publication, until it is fixed"
source_yaml L4 >"$evidence/source-conflict.yaml"
kubectl apply -f "$evidence/source-conflict.yaml" >/dev/null
await conflict-stops-the-table 300 lakesource "$source_name" \
  "(table('orders').get('governance') or {}).get('reason') == 'LabelInvalid'" || true
await conflict-stops-the-dashboard 300 lakedashboard "$dashboard" \
  "st.get('phase') == 'Failed' and 'above its table' in (st.get('message') or '')" || true
doris_as "$analyst" "$work/analyst" "$work/viewer.sql" "$evidence/doris/analyst-during-conflict.txt"
refused conflict-withdraws-the-users "$evidence/doris/analyst-during-conflict.txt" "SELECT COUNT(*) FROM \`$database\`.live" "access denied for user"
kubectl apply -f "$evidence/source.yaml" >/dev/null
await fixed-label-serves-again 300 lakedashboard "$dashboard" "st.get('phase') == 'Ready'" || true
doris_as "$analyst" "$work/analyst" "$work/viewer.sql" "$evidence/doris/analyst-after-fix.txt"
got="$(answer "$evidence/doris/analyst-after-fix.txt" "SELECT COUNT(*) FROM \`$database\`.live")"
if [[ "$got" =~ ^[0-9]+$ ]] && ((got > 0)); then
  verdict analyst-reads-again PASS "the analyst counts $got rows"
else
  verdict analyst-reads-again FAIL "the analyst got: $(cut -c1-200 <<<"$got")"
fi

# ---------------------------------------------------------------------------------- clean up
step "clean up"
kubectl -n "$JOB_NAMESPACE" delete lakedashboard "$dashboard" --wait=true --timeout=10m >/dev/null || true
kubectl -n "$JOB_NAMESPACE" delete lakesource "$source_name" --wait=true --timeout=10m >/dev/null || true
kubectl -n "$JOB_NAMESPACE" delete secret "$dashboard-analyst" "$dashboard-viewer" --ignore-not-found >/dev/null
python3 "$cdc/mysql_contract.py" reset "$db" >"$evidence/sql/reset.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/reset.sql" >/dev/null || true

echo
awk -F'\t' '{print $3}' "$evidence/verdicts.tsv" | sort | uniq -c
exit "$failed"
