#!/usr/bin/env bash
# Acceptance on AWS: the console's controlled operations, behind the entry. Writes PASS/FAIL lines
# to <evidence>/verdicts.tsv and fails if any check fails.
#
# - A MySQL source feeds ODS and a DWD table reads it. Each operation is done once with `colvenna`
#   (as this machine's IAM identity) and once in the console (as a signed-in operator or
#   administrator), on the same input: applying a declaration (and a refused one), a quality check
#   and a waiver of it, the four maintenance tasks, suspending and resuming, a backfill, deleting a
#   declaration and erasing a data subject. Both make the same declaration and the operator judges
#   them the same.
# - Without the role, nothing: a BI user, a user without a role, an operator asking for an
#   administrator's operation directly (the page does not offer it), a request from another site,
#   another namespace; and Kubernetes itself refuses the console's operator user what only its
#   administrator user may, and lets the console impersonate no one else.
# - Deleting, backfilling and erasing show what they affect first; cancelled there, nothing
#   changes.
# - The console's audit has each operation (who, when, what, on which resource, its result), the
#   cluster's audit has its requests made by the console as the person; the subject's values are
#   in neither.
#
# usage: run-operations.sh <run-id> <evidence-dir>; needs the entry stack and install-lbc.sh,
# build-console.sh then install-console.sh from this commit, and the operator's chart from this
# commit (deploy/scripts/operator/build-images.sh, install-operator.sh).
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-operations.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-operations.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
here="$REPO_DIR/tests/acceptance/operations"
entry_py="$REPO_DIR/tests/acceptance/entry/entry.py"
flows="$REPO_DIR/tests/acceptance"
cdc="$flows/cdc"
mkdir -p "$evidence"/{status,sql,http,cli,console,audit}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
image="$(entry_image console)"
running="$(kubectl -n "$CONSOLE_NAMESPACE" get deployment "$CONSOLE_RELEASE" -o jsonpath='{.spec.template.spec.containers[0].image}')"
[[ "$running" == "$image" ]] || { echo "The console runs $running, not $image of this commit" >&2; exit 1; }
grep -q 'colvenna-lake.io/person' <<<"$(kubectl get validatingadmissionpolicy "$OPERATOR_RELEASE-waiver-approver" -o yaml)" ||
  { echo "The waiver policy does not know the console's person: install-operator.sh from this commit" >&2; exit 1; }
colvenna="$(colvenna_cli)"
{
  echo "console $image"
  echo "operator $(kubectl -n "$OPERATOR_NAMESPACE" get deployment "$OPERATOR_RELEASE" -o jsonpath='{.spec.template.spec.containers[0].image}')"
  echo "cli $("$colvenna" version)"
  echo "kubernetes $(kubectl version -o json | python3 -c 'import json,sys; print(json.load(sys.stdin)["serverVersion"]["gitVersion"])')"
} | tee "$evidence/versions.txt"
failed=0
began=$SECONDS
started_ms="$(($(date +%s) * 1000))"
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
# checked <label> <command...>: PASS when the command succeeds, with what it printed.
checked() {
  local label="$1" out
  shift
  if out="$("$@" 2>&1)"; then verdict "$label" PASS "$out"; else verdict "$label" FAIL "$out"; fi
}

pool="$(entry_output UserPoolId)"
url="$(entry_output consoleUrl)"
signin="$(entry_output SignInDomain)"
audit_group="$(entry_output ConsoleAuditLogGroup)"
ns="$JOB_NAMESPACE"
db="ops_$run" ods="ods_ops_$run" dwd="dwd_ops_$run"
source_name="os-$run" dwd_name="owd-$run" quality="oq-$run"
small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
prefix="ops-$run"
export ENTRY_AGENT="$prefix-browser"
state="$(mktemp -d)"
chmod 700 "$state"
python3 -c 'import sys,urllib.parse; print(urllib.parse.urlsplit(sys.argv[1]).hostname)' "$signin" >"$state/signin-host"
declare -A groups=([admin]=colvenna-admin [op]=colvenna-operator [bi]=colvenna-bi [none]="")
# shellcheck disable=SC2317  # run by the trap
cleanup() {
  local u kind
  for kind in lakewaiver lakeerasure laketask lakequality; do
    kubectl -n "$ns" get "$kind" -o name 2>/dev/null | grep -- "-$run\$" | xargs -r kubectl -n "$ns" delete --wait=false >/dev/null 2>&1 || true
  done
  kubectl -n "$ns" delete laketable "$dwd_name" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kubectl -n "$ns" delete lakesource "$source_name" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  for u in "${!groups[@]}"; do
    aws cognito-idp admin-delete-user --user-pool-id "$pool" --username "$prefix-$u" 2>/dev/null || true
  done
  rm -rf "$state"
}
trap cleanup EXIT
entry() { python3 -I "$entry_py" "$@"; }
judge() { python3 -I "$here/judge.py" "$@"; }
c() { "$colvenna" -n "$ns" "$@"; }
field() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))' "$1" "$2"; }
# get <kind> <name> <file>: the object, or {} when there is none (a check then fails, the run goes on).
get() { kubectl -n "$ns" get "$1" "$2" -o json >"$3" 2>/dev/null || { echo '{}' >"$3"; return 1; }; }
gone() { ! kubectl -n "$ns" get "$1" "$2" >/dev/null 2>&1; }

# form <file> <key=value>...: a form as JSON (the namespace is the run's).
form() {
  local file="$1"
  shift
  python3 - "$ns" "$@" >"$file" <<'PY'
import json, sys
form = {"namespace": sys.argv[1]}
for kv in sys.argv[2:]:
    k, v = kv.split("=", 1)
    form[k] = v.replace("\\n", "\n")
print(json.dumps(form))
PY
}
# operate <user> <op> <form-file> <label>: posts the form; the answer's status, the page to the evidence.
# The pages go to the evidence, but where pages="$state": an erasure's carry its subject.
operate() {
  local dir="${pages:-$evidence/http}"
  entry post "$url/operations/$2" "$state" "$prefix-$1" "$3" --body-file "$dir/$4.html" >"$dir/$4.json"
  field "$dir/$4.json" 'd["status"]'
}
# confirmed <user> <op> <form-file> <label>: the preview, then what its confirmation page sends
# (its hidden fields: the form, what the preview bound, the confirmation), as a browser does.
confirmed() {
  local code
  code="$(operate "$1" "$2" "$3" "$4-preview")"
  [[ "$code" == 200 ]] || { echo "$code"; return; }
  python3 -c '
import html, json, re, sys
page = open(sys.argv[1]).read()
print(json.dumps({html.unescape(k): html.unescape(v) for k, v in re.findall(r"<input type=\"hidden\" name=\"([^\"]+)\" value=\"([^\"]*)\">", page)}))' \
    "${pages:-$evidence/http}/$4-preview.html" >"$3.confirmed"
  operate "$1" "$2" "$3.confirmed" "$4"
}
# await <label> <timeout> <kind> <name> <python expression over st, the status>
await() {
  local label="$1" end=$((SECONDS + $2)) started=$SECONDS file="$evidence/status/$1.json"
  while ((SECONDS < end)); do
    if get "$3" "$4" "$file" 2>/dev/null && python3 - "$file" "$5" <<'PY'; then
import json, sys
st = json.load(open(sys.argv[1])).get("status") or {}
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
# ended <name> <timeout>: waits for a task's end, Succeeded or Failed (no verdict).
ended() {
  local end=$((SECONDS + $2))
  while ((SECONDS < end)); do
    [[ "$(kubectl -n "$ns" get laketask "$1" -o jsonpath='{.status.phase}' 2>/dev/null || true)" =~ ^(Succeeded|Failed)$ ]] && return 0
    sleep 10
  done
  return 1
}
# impact_of <page>: the preview's list of what it affects.
impact_of() {
  python3 -c 'import html,re,sys; print(" / ".join(html.unescape(x) for x in re.findall(r"<li>(.*?)</li>", open(sys.argv[1]).read())))' "$1"
}
# untouched <kind> <name> <generation>: the object's spec is as it was, and it is not being deleted
# (its status may have moved on).
# shellcheck disable=SC2317  # run by check
untouched() {
  [[ "$(kubectl -n "$ns" get "$1" "$2" -o jsonpath='{.metadata.generation}|{.metadata.deletionTimestamp}' 2>/dev/null || true)" == "$3|" ]]
}
# deleted <kind> <name> <timeout>: seconds until the object is gone, or "not".
deleted() {
  local end=$((SECONDS + $3)) started=$SECONDS
  while ((SECONDS < end)); do
    gone "$1" "$2" && { echo "$((SECONDS - started))"; return; }
    sleep 5
  done
  echo not
}
# settled <kind> <name> <file>: the object once its phase stays the same for 30 s (at most 10
# minutes), as it was then.
settled() {
  local end=$((SECONDS + 600)) before="" now
  while ((SECONDS < end)); do
    now="$(kubectl -n "$ns" get "$1" "$2" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    [[ -n "$now" && "$now" == "$before" ]] && break
    before="$now"
    sleep 30
  done
  get "$1" "$2" "$3" || true
}
# same <label> <cli-file> <console-file> <cli-name=console-name>...: the same, judged the same.
same() {
  local label="$1"
  shift
  checked "$label" judge same "$@"
}

step "users"
for u in "${!groups[@]}"; do
  name="$prefix-$u"
  python3 -c 'import secrets,string; a=string.ascii_letters+string.digits; print("Aa1!" + "".join(secrets.choice(a) for _ in range(24)), end="")' >"$state/$name.pw"
  aws cognito-idp admin-create-user --user-pool-id "$pool" --username "$name" --message-action SUPPRESS >/dev/null
  # The password goes in a file, not on the command line.
  python3 -c 'import json,sys; print(json.dumps({"UserPoolId": sys.argv[1], "Username": sys.argv[2], "Password": open(sys.argv[3]).read(), "Permanent": True}))' \
    "$pool" "$name" "$state/$name.pw" >"$state/$name.input.json"
  aws cognito-idp admin-set-user-password --cli-input-json "file://$state/$name.input.json"
  rm -f "$state/$name.input.json"
  for g in ${groups[$u]}; do
    aws cognito-idp admin-add-user-to-group --user-pool-id "$pool" --username "$name" --group-name "$g"
  done
  entry login "$url/" "$state" "$name" >"$evidence/http/login-$u.json" || true
done

# ------------------------------------------------------------------------------------- the lake
step "mysql fixture $db, source $source_name, $dwd_name"
{
  python3 "$cdc/mysql_contract.py" reset "$db"
  python3 "$cdc/mysql_contract.py" setup "$db"
  cat <<SQL
USE $db;
SET SESSION cte_max_recursion_depth = 100000;
INSERT INTO customers (id, name, region, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 50)
  SELECT i, CONCAT('customer-', i), ELT(1 + i % 4, 'north', 'south', 'east', 'west'), 1 FROM n;
INSERT INTO orders (id, customer_id, amount, state, note, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 500)
  SELECT i, 1 + i % 50, ROUND(i * 1.25, 2), 'created', NULL, 1 FROM n;
SQL
} >"$evidence/sql/setup.sql"
bash "$FIXTURES_DIR/sources/sources.sh" users >/dev/null
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/setup.sql" >/dev/null
ensure_rds_ca "$ns"
cat >"$evidence/status/source.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: $source_name, namespace: $ns}
spec:
  type: mysql
  connection:
    host: $(stack_output "$TEST_MYSQL_STACK" MySqlEndpoint)
    database: $db
    credentialsSecret: {name: mysql-cdc}
    inspectorSecret: {name: mysql-inspect}
    caConfigMap: rds-ca
    serverTimeZone: UTC
  tables: {include: [orders, customers]}
  target: {database: $ods}
  governance: {owner: shop-team, level: L2, retention: 7d}
  job: $small
  deletionPolicy: Delete
YAML
c apply -f "$evidence/status/source.yaml"
await source-ready 900 lakesource "$source_name" "table('orders').get('state') == 'Ready' and table('customers').get('state') == 'Ready'" || true
cat >"$evidence/status/dwd.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTable
metadata: {name: $dwd_name, namespace: $ns}
spec:
  target: {database: $dwd, table: orders}
  primaryKey: [order_id]
  upstream: [$ods.orders, $ods.customers]
  query: |
    SELECT o.id AS order_id, o.customer_id, c.region, o.amount, o.version_no
    FROM (SELECT *, PROCTIME() AS pt FROM $ods.orders) AS o
    JOIN $ods.customers FOR SYSTEM_TIME AS OF o.pt AS c ON o.customer_id = c.id
  governance: {owner: analytics, level: L2, retention: 7d}
  deletionPolicy: Delete
  job: $small
YAML
c apply -f "$evidence/status/dwd.yaml"
await dwd-running 900 laketable "$dwd_name" "st.get('phase') == 'Running'" || true

# ---------------------------------------------------------------------------------- who may operate
step "without the role, nothing"
form "$state/tag.json" table="$ods.orders" name="ot-refused-$run" tag="refused"
form "$state/delete.json" kind=LakeTable name="$dwd_name"
form "$state/forget.json" name="oe-refused-$run" values="nobody-$run"
form "$state/waive.json" check="ock-refused-$run" name="ow-refused-$run" rules=volume reason="refused"
codes="no session: $(entry post "$url/operations/tag" "$state" "$prefix-nobody" "$state/tag.json" 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])'); "
codes+="BI: $(operate bi tag "$state/tag.json" bi-tag); no role: $(operate none tag "$state/tag.json" none-tag); "
for op in delete forget waive; do
  codes+="operator $op: $(operate op "$op" "$state/$op.json" "op-$op"); "
done
codes+="another site: $(entry post "$url/operations/tag" "$state" "$prefix-admin" "$state/tag.json" --origin https://evil.example | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])'); "
codes+="no origin: $(entry post "$url/operations/tag" "$state" "$prefix-admin" "$state/tag.json" --origin "" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])'); "
python3 -c 'import json,sys; f=json.load(open(sys.argv[1])); f["namespace"]="kube-system"; print(json.dumps(f))' "$state/tag.json" >"$state/tag-elsewhere.json"
codes+="another namespace: $(operate admin tag "$state/tag-elsewhere.json" admin-elsewhere)"
check refused-without-the-role "$codes" python3 - "$codes" <<'PY'
import sys
got = dict(p.rsplit(": ", 1) for p in sys.argv[1].split("; "))
ok = got.pop("no session") in ("302", "401") and all(v == "403" for v in got.values())
sys.exit(0 if ok else 1)
PY
entry get "$url/operations" "$state" "$prefix-op" --body-file "$evidence/http/op-page.html" >/dev/null
entry get "$url/operations" "$state" "$prefix-admin" --body-file "$evidence/http/admin-page.html" >/dev/null
# offers_by_role: the operator's page has no administrator's operation; the administrator's has.
# shellcheck disable=SC2317  # run by check
offers_by_role() {
  ! grep -Eq 'operations/(delete|forget|waive)"' "$evidence/http/op-page.html" &&
    grep -q 'operations/forget"' "$evidence/http/admin-page.html"
}
check the-page-offers-each-role-its-own "operator: $(grep -o 'action="/operations/[a-z]*"' "$evidence/http/op-page.html" | sed 's/.*operations\///; s/"//' | tr '\n' ' ')" \
  offers_by_role
leftovers="$(kubectl -n "$ns" get laketasks,lakewaivers,lakeerasures,secrets -o name 2>/dev/null | grep -c -- "-refused-$run" || true)"
check nothing-made-by-the-refused "$leftovers objects" test "$leftovers" = 0
check nothing-deleted-by-the-refused "laketable/$dwd_name $(kubectl -n "$ns" get laketable "$dwd_name" -o jsonpath='{.status.phase}')" \
  kubectl -n "$ns" get laketable "$dwd_name" -o name
sa="$(kubectl -n "$CONSOLE_NAMESPACE" get deployment "$CONSOLE_RELEASE" -o jsonpath='{.spec.template.spec.serviceAccountName}')"
who="system:serviceaccount:$CONSOLE_NAMESPACE:$sa"
can="$(for q in "impersonate users/colvenna-console:admin" "impersonate users/colvenna-console:operator" "impersonate users/system:admin" \
  "impersonate users/kubernetes-admin" "impersonate groups/system:masters" "impersonate serviceaccounts"; do
  read -r verb target <<<"$q"
  printf '%s=%s; ' "$target" "$(kubectl auth can-i "$verb" "$target" --as="$who" 2>/dev/null)"
done)"
check the-console-impersonates-only-its-users "$can" test "$can" = "users/colvenna-console:admin=yes; users/colvenna-console:operator=yes; users/system:admin=no; users/kubernetes-admin=no; groups/system:masters=no; serviceaccounts=no; "
rbac="$(for q in "colvenna-console:operator delete laketables" "colvenna-console:operator create lakeerasures" "colvenna-console:operator create lakewaivers" \
  "colvenna-console:operator create secrets" "colvenna-console:operator patch laketables" "colvenna-console:admin delete laketables" "colvenna-console:admin create secrets" \
  "colvenna-console:admin get secrets" "colvenna-console:admin create pods"; do
  read -r as verb resource <<<"$q"
  printf '%s %s %s=%s; ' "${as#colvenna-console:}" "$verb" "$resource" "$(kubectl auth can-i "$verb" "$resource" --as="$as" -n "$ns" 2>/dev/null)"
done)"
check kubernetes-holds-the-roles "$rbac" test "$rbac" = "operator delete laketables=no; operator create lakeerasures=no; operator create lakewaivers=no; operator create secrets=no; operator patch laketables=yes; admin delete laketables=yes; admin create secrets=yes; admin get secrets=no; admin create pods=no; "


# -------------------------------------------------------------------------- apply, check, waive
step "apply a LakeQuality: with colvenna, then (once deleted) in the console"
cat >"$evidence/status/quality.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeQuality
metadata: {name: $quality, namespace: $ns}
spec:
  table: {database: $ods, table: orders}
  rules:
    - {name: customer-present, notNull: {column: customer_id}}
    - {name: volume, rowCount: {min: 1000000}}
YAML
c apply -f "$evidence/status/quality.yaml" >"$evidence/cli/apply.out" 2>&1 || true
settled lakequality "$quality" "$evidence/cli/quality.json"
c delete lakequality "$quality" --wait=false >"$evidence/cli/delete.out" 2>&1 || true
cli_deleted="$(deleted lakequality "$quality" 300)"
form "$state/apply.json" manifest="$(cat "$evidence/status/quality.yaml")"
code="$(operate op apply "$state/apply.json" apply)"
settled lakequality "$quality" "$evidence/console/quality.json"
same apply-is-the-same "$evidence/cli/quality.json" "$evidence/console/quality.json"
# A declaration the API server refuses, and a kind neither applies.
cat >"$state/refused.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeQuality
metadata: {name: oq-bad-$run, namespace: $ns}
spec:
  table: {database: $ods, table: orders}
  rules: "not a list"
---
apiVersion: v1
kind: ConfigMap
metadata: {name: oq-cm-$run}
YAML
cli_refused="$(c apply -f "$state/refused.yaml" 2>&1 | tail -1 || true)"
form "$state/apply-refused.json" manifest="$(cat "$state/refused.yaml")"
code="$(operate op apply "$state/apply-refused.json" apply-refused)"
console_refused="$(python3 -c 'import html,re,sys; m=re.search(r"<dt>原因</dt><dd>(.*?)</dd>", open(sys.argv[1]).read(), re.S); print(html.unescape(m.group(1)) if m else "")' "$evidence/http/apply-refused.html")"
check apply-refused-the-same "colvenna: $cli_refused | console $code: $console_refused" python3 - "$cli_refused" "$console_refused" <<'PY'
import sys
cli, console = sys.argv[1], sys.argv[2]
# The same refusal from the API server (the CLI prefixes "error: ").
sys.exit(0 if cli.removeprefix("error: ") == console and "rules" in console else 1)
PY

# What has an operation of its own, or another namespace, is refused by the console's apply.
cat >"$state/backfill-task.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: obfa-$run, namespace: $ns}
spec: {type: backfill, table: {database: $dwd, table: orders}}
YAML
form "$state/apply-backfill.json" manifest="$(cat "$state/backfill-task.yaml")"
codes="backfill $(operate op apply "$state/apply-backfill.json" apply-backfill); "
cat >"$state/expire-task.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: task
metadata: {name: oexa-$run, namespace: $ns}
spec: {type: expireSnapshots, table: {database: $ods, table: orders}}
YAML
form "$state/apply-expire.json" manifest="$(cat "$state/expire-task.yaml")"
codes+="expire, by an alias $(operate op apply "$state/apply-expire.json" apply-expire); "
cat >"$state/elsewhere-task.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: ona-$run, namespace: $OPERATOR_NAMESPACE}
spec: {type: compact, table: {database: $ods, table: orders}}
YAML
form "$state/apply-elsewhere.json" manifest="$(cat "$state/elsewhere-task.yaml")"
codes+="another namespace $(operate op apply "$state/apply-elsewhere.json" apply-elsewhere)"
# shellcheck disable=SC2317  # run by check
nothing_applied() { gone laketask "obfa-$run" && gone laketask "oexa-$run" && ! kubectl -n "$OPERATOR_NAMESPACE" get laketask "ona-$run" >/dev/null 2>&1; }
check apply-leaves-confirmed-and-other-namespaces-out "$codes" test "$codes" = "backfill 400; expire, by an alias 400; another namespace 400"
check nothing-applied-around-the-rules "obfa-$run oexa-$run ona-$run" nothing_applied

step "a quality check that fails, and its waiver"
# Each way in turn: a newer check supersedes the waivers of the one before.
# It exits 3 when rules failed, as here.
cli_check=0
c check "$ods.orders" --name "ockl-$run" --wait --wait-timeout 15m >"$evidence/cli/check.out" 2>&1 || cli_check=$?
c waive "ockl-$run" --name "owl-$run" --rule volume --reason "acceptance run" >"$evidence/cli/waive.out" 2>&1 || true
get laketask "ockl-$run" "$evidence/cli/check.json" || true
settled lakewaiver "owl-$run" "$evidence/cli/waiver.json"
form "$state/check.json" table="$ods.orders" name="ockc-$run"
code="$(operate op check "$state/check.json" check)"
ended "ockc-$run" 900 || true
get laketask "ockc-$run" "$evidence/console/check.json" || true
form "$state/waive.json" check="ockc-$run" name="owc-$run" rules=volume reason="acceptance run"
code+=" $(operate admin waive "$state/waive.json" waive)"
settled lakewaiver "owc-$run" "$evidence/console/waiver.json"
same check-is-the-same "$evidence/cli/check.json" "$evidence/console/check.json" "ockl-$run=ockc-$run"
check check-failed-the-rule "colvenna exit $cli_check (3: rules failed); console $code" test "$cli_check|$code" = "3|200 200"
same waive-is-the-same "$evidence/cli/waiver.json" "$evidence/console/waiver.json" "ockl-$run=ockc-$run"
approver="$(field "$evidence/console/waiver.json" '(d.get("metadata", {}).get("annotations") or {}).get("colvenna-lake.io/approved-by")')"
check the-waivers-approver-is-the-person "$approver; colvenna's: $(field "$evidence/cli/waiver.json" '(d.get("metadata", {}).get("annotations") or {}).get("colvenna-lake.io/approved-by")')" \
  test "$approver" = "colvenna-console:admin/$prefix-admin"

# ------------------------------------------------------------------------------- maintenance
step "the maintenance tasks"
c maintain tag "$ods.customers" --name "otgl-$run" --tag "l-$run" >/dev/null 2>&1 || true
# The same tag name twice would be refused; the console's is its own.
form "$state/tag.json" table="$ods.customers" name="otgc-$run" tag="c-$run"
codes="tag $(operate op tag "$state/tag.json" tag); "
c maintain compact "$ods.customers" --name "ocpl-$run" >/dev/null 2>&1 || true
form "$state/compact.json" table="$ods.customers" name="ocpc-$run"
codes+="compact $(operate op compact "$state/compact.json" compact); "
c maintain expire "$ods.orders" --name "oexl-$run" --older-than 1h >/dev/null 2>&1 || true
form "$state/expire.json" table="$ods.orders" name="oexc-$run" olderThan=1h
codes+="expire $(confirmed op expire "$state/expire.json" expire); "
c maintain orphans "$ods.orders" --name "oorl-$run" >/dev/null 2>&1 || true
form "$state/orphans.json" table="$ods.orders" name="oorc-$run"
codes+="orphans $(confirmed op orphans "$state/orphans.json" orphans)"
verdict maintenance-submitted "$([[ "$codes" == "tag 200; compact 200; expire 200; orphans 200" ]] && echo PASS || echo FAIL)" "$codes"
for t in otg ocp oex oor; do
  ended "${t}l-$run" 1200 || true
  ended "${t}c-$run" 1200 || true
  get laketask "${t}l-$run" "$evidence/cli/${t}.json" || true
  get laketask "${t}c-$run" "$evidence/console/${t}.json" || true
done
same tag-is-the-same "$evidence/cli/otg.json" "$evidence/console/otg.json" "l-$run=c-$run"
same compact-is-the-same "$evidence/cli/ocp.json" "$evidence/console/ocp.json"
same expire-is-the-same "$evidence/cli/oex.json" "$evidence/console/oex.json"
same orphans-is-the-same "$evidence/cli/oor.json" "$evidence/console/oor.json"
expire_impact="$(impact_of "$evidence/http/expire-preview.html")" orphans_impact="$(impact_of "$evidence/http/orphans-preview.html")"
# shellcheck disable=SC2317  # run by check
shown_before() { [[ "$expire_impact" == *"time travel"* && "$orphans_impact" == *"不能恢复"* ]]; }
check expire-and-orphans-showed-their-effect "$expire_impact | $orphans_impact" shown_before

step "suspend and resume, each way"
form "$state/suspend.json" kind=LakeTable name="$dwd_name"
code="$(operate op suspend "$state/suspend.json" suspend)"
await suspended-in-the-console 300 laketable "$dwd_name" "st.get('phase') == 'Suspended'" || true
c resume laketable "$dwd_name" >/dev/null 2>&1 || true
await resumed-with-colvenna 600 laketable "$dwd_name" "st.get('phase') == 'Running'" || true
c suspend laketable "$dwd_name" >/dev/null 2>&1 || true
await suspended-with-colvenna 300 laketable "$dwd_name" "st.get('phase') == 'Suspended'" || true
code+=" $(operate op resume "$state/suspend.json" resume)"
await resumed-in-the-console 600 laketable "$dwd_name" "st.get('phase') == 'Running'" || true
check suspend-and-resume-answered "$code" test "$code" = "200 200"

# ----------------------------------------------------------------- what needs a confirmation
step "a backfill: cancelled, then confirmed; and with colvenna"
form "$state/backfill-x.json" table="$dwd.orders" name="obfx-$run"
code="$(operate op backfill "$state/backfill-x.json" backfill-cancelled)"
impact="$(impact_of "$evidence/http/backfill-cancelled.html")"
# shellcheck disable=SC2317  # run by check
shown_and_not_done() { [[ "$code" == 200 && "$impact" == *"$dwd_name"* ]] && gone laketask "obfx-$run"; }
check backfill-shows-its-effect-and-a-cancel-changes-nothing "$code: $impact" shown_and_not_done
form "$state/backfill.json" table="$dwd.orders" name="obfc-$run"
code="$(confirmed op backfill "$state/backfill.json" backfill)"
ended "obfc-$run" 1800 || true
get laketask "obfc-$run" "$evidence/console/backfill.json" || true
c backfill "$dwd.orders" --name "obfl-$run" --wait --wait-timeout 30m >"$evidence/cli/backfill.out" 2>&1 || true
get laketask "obfl-$run" "$evidence/cli/backfill.json" || true
same backfill-is-the-same "$evidence/cli/backfill.json" "$evidence/console/backfill.json"

step "deleting a declaration: cancelled, then confirmed"
form "$state/delete-quality.json" kind=LakeQuality name="$quality"
version="$(kubectl -n "$ns" get lakequality "$quality" -o jsonpath='{.metadata.generation}')"
code="$(operate admin delete "$state/delete-quality.json" delete-cancelled)"
impact="$(impact_of "$evidence/http/delete-cancelled.html")"
sleep 5
# shellcheck disable=SC2317  # run by check
still_there() { [[ "$code" == 200 && "$impact" == *"$ods.orders"* ]] && untouched lakequality "$quality" "$version"; }
check delete-shows-its-effect-and-a-cancel-changes-nothing "$code: $impact" still_there
code="$(confirmed admin delete "$state/delete-quality.json" delete)"
console_deleted="$(deleted lakequality "$quality" 300)"
# shellcheck disable=SC2317  # run by check
both_gone() { [[ "$cli_deleted" =~ ^[0-9]+$ && "$console_deleted" =~ ^[0-9]+$ ]]; }
check delete-is-the-same "the same declaration: with colvenna gone in ${cli_deleted}s ($(tail -1 "$evidence/cli/delete.out")), in the console (answer $code) in ${console_deleted}s" both_gone

step "erasing a data subject: cancelled, then confirmed; and with colvenna"
subject="nobody-$(openssl rand -hex 6)"
printf '%s\n' "$subject" >"$state/subject"
form "$state/forget-x.json" name="oefx-$run" values="$subject"
# Its pages carry the subject: they stay out of the evidence.
pages="$state"
code="$(operate admin forget "$state/forget-x.json" forget-cancelled)"
impact="$(impact_of "$state/forget-cancelled.html")"
# shellcheck disable=SC2317  # run by check
nothing_kept() { [[ "$code" == 200 && "$impact" == *"1 个键值"* && "$impact" == *"$ods.orders"* ]] && gone lakeerasure "oefx-$run" && gone secret "oefx-$run"; }
check forget-shows-its-effect-and-a-cancel-changes-nothing "$code: ${impact//$subject/<subject>}" nothing_kept
# Each way in turn, each judged once it settles.
c forget "oefl-$run" --subject-file "$state/subject" >"$evidence/cli/forget.out" 2>&1 || true
settled lakeerasure "oefl-$run" "$evidence/cli/erasure.json"
form "$state/forget.json" name="oefc-$run" values="$subject"
code="$(confirmed admin forget "$state/forget.json" forget)"
pages=""
settled lakeerasure "oefc-$run" "$evidence/console/erasure.json"
for e in oefl oefc; do
  kubectl -n "$ns" get secret "$e-$run" -o json >"$state/$e-secret.json" 2>/dev/null || echo '{}' >"$state/$e-secret.json"
done
same forget-is-the-same "$evidence/cli/erasure.json" "$evidence/console/erasure.json" "oefl-$run=oefc-$run"
checked the-erasure-owns-its-subject judge owned "$state/oefc-secret.json" "$evidence/console/erasure.json"
# shellcheck disable=SC2317  # run by check
same_subject() {
  cmp -s <(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["values"])' "$state/oefl-secret.json") \
    <(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["values"])' "$state/oefc-secret.json")
}
check the-subject-is-the-same "console $code" same_subject

# ------------------------------------------------------------------------------------- audit
step "the console's audit and the cluster's"
for _ in $(seq 1 20); do
  aws logs filter-log-events --log-group-name "$audit_group" --start-time "$started_ms" \
    --filter-pattern "\"$prefix\"" --query 'events[].message' --output json >"$evidence/audit/console.json"
  python3 -c 'import json,sys; e=[json.loads(m) for m in json.load(open(sys.argv[1]))]; sys.exit(0 if any(x.get("action") == "forget" and x.get("status") for x in e) else 1)' "$evidence/audit/console.json" && break
  sleep 30
done
a="$evidence/audit/console.json"
for expect in "op apply $ns/LakeQuality/$quality ok" "op check $ns/LakeTask/ockc-$run ok" "admin waive $ns/LakeWaiver/owc-$run ok" \
  "op tag $ns/LakeTask/otgc-$run ok" "op compact $ns/LakeTask/ocpc-$run ok" "op expire $ns/LakeTask/oexc-$run ok" \
  "op orphans $ns/LakeTask/oorc-$run ok" "op suspend $ns/LakeTable/$dwd_name ok" "op resume $ns/LakeTable/$dwd_name ok" \
  "op backfill $ns/LakeTask/obfx-$run previewed" "op backfill $ns/LakeTask/obfc-$run ok" \
  "admin delete $ns/LakeQuality/$quality ok" "admin forget $ns/LakeErasure/oefx-$run previewed" "admin forget $ns/LakeErasure/oefc-$run ok" \
  "op delete $ns/LakeTable/$dwd_name refused" "op forget $ns/LakeErasure/oe-refused-$run refused" "op waive $ns/LakeWaiver/ow-refused-$run refused" \
  "op apply $ns/LakeQuality/oq-bad-$run,ConfigMap/oq-cm-$run refused" "op apply $ns/LakeTask/obfa-$run refused" \
  "op apply $ns/task/oexa-$run refused" "op apply $ns/LakeTask/ona-$run refused"; do
  read -r user action resource outcome <<<"$expect"
  # One verdict per expectation, named after the object (without the run id).
  object="${resource##*/}"
  checked "audit-$action-$outcome-${object%-"$run"}" judge audit "$a" "$prefix-$user" "$action" "$resource" "$outcome"
done
# shellcheck disable=SC2317  # run by check
no_subject() {
  ! grep -q -- "$subject" "$a" && ! grep -rq -- "$subject" "$evidence" &&
    [[ "$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))))' "$evidence/audit/cluster-subject.json")" == 0 ]]
}
cluster="$(stack_output "$FOUNDATION_STACK" ClusterName)"
for _ in $(seq 1 20); do
  aws logs filter-log-events --log-group-name "/aws/eks/$cluster/cluster" --log-stream-name-prefix kube-apiserver-audit \
    --start-time "$started_ms" --filter-pattern "\"owc-$run\"" --query 'events[].message' --output json >"$evidence/audit/cluster.json"
  judge cluster-audit "$evidence/audit/cluster.json" colvenna-console:admin "$prefix-admin" patch lakewaivers "owc-$run" >/dev/null && break
  sleep 30
done
checked cluster-audit-names-the-person judge cluster-audit "$evidence/audit/cluster.json" colvenna-console:admin "$prefix-admin" patch lakewaivers "owc-$run"
# Neither audit, nor anything this run keeps, has the subject's values (as is or base64, as a
# Secret's data is): looked for once the cluster's audit has the console's Secret of the subject.
for _ in $(seq 1 20); do
  aws logs filter-log-events --log-group-name "/aws/eks/$cluster/cluster" --log-stream-name-prefix kube-apiserver-audit \
    --start-time "$started_ms" --filter-pattern "\"oefc-$run\"" --query 'events[].message' --output json >"$state/cluster-erasure.json"
  python3 -c 'import json,sys; e=[json.loads(m) for m in json.load(open(sys.argv[1]))]; sys.exit(0 if any((x.get("objectRef") or {}).get("resource") == "secrets" for x in e) else 1)' "$state/cluster-erasure.json" && break
  sleep 30
done
encoded="$(printf '%s\n' "$subject" | base64 -w0)"
aws logs filter-log-events --log-group-name "/aws/eks/$cluster/cluster" --log-stream-name-prefix kube-apiserver-audit \
  --start-time "$started_ms" --filter-pattern "?\"$subject\" ?\"$encoded\"" --query 'events[].message' --output json >"$evidence/audit/cluster-subject.json"
check the-subject-is-in-no-audit "console $(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))))' "$a") events; cluster: $(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))))' "$state/cluster-erasure.json") events of the erasure, $(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))))' "$evidence/audit/cluster-subject.json") with the subject" no_subject

# --------------------------------------------------------------------------------------- end
step "delete what the run made"
for kind in lakewaiver lakeerasure laketask lakequality; do
  kubectl -n "$ns" get "$kind" -o name | grep -- "-$run\$" | xargs -r kubectl -n "$ns" delete --timeout=10m >/dev/null || true
done
# The last two each way: the DWD table in the console (confirmed), the source with colvenna; each goes
# with its job and, by its deletionPolicy, its tables.
form "$state/delete-dwd.json" kind=LakeTable name="$dwd_name"
code="$(confirmed admin delete "$state/delete-dwd.json" delete-dwd)"
console_gone="$(deleted laketable "$dwd_name" 600)"
c delete lakesource "$source_name" --wait=false >/dev/null 2>&1 || true
cli_gone="$(deleted lakesource "$source_name" 600)"
jobs_left="$(kubectl -n "$ns" get flinkdeployment -o name | grep -c -- "-$run-" || true)"
dwd_impact="$(impact_of "$evidence/http/delete-dwd-preview.html")"
# shellcheck disable=SC2317  # run by check
both_went_with_their_jobs() { [[ "$console_gone" =~ ^[0-9]+$ && "$cli_gone" =~ ^[0-9]+$ && "$jobs_left" == 0 && "$dwd_impact" == *"deletionPolicy Delete"* ]]; }
check deleting-a-pipeline-declaration-each-way "console: answer $code, gone in ${console_gone}s ($dwd_impact); colvenna: gone in ${cli_gone}s; jobs left $jobs_left" both_went_with_their_jobs
python3 "$cdc/mysql_contract.py" reset "$db" >"$evidence/sql/reset.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/reset.sql" >/dev/null
echo "took $((SECONDS - began))s"
echo "verdicts: $(grep -c $'\tPASS\t' "$evidence/verdicts.tsv") PASS, $(grep -c $'\tFAIL\t' "$evidence/verdicts.tsv") FAIL"
exit "$failed"
