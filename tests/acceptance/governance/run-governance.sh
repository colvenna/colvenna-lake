#!/usr/bin/env bash
# Acceptance on EKS: tables declared with their governance are published only while governed, and
# a data subject is erased across them with logical and physical states and their evidence.
# Writes PASS/FAIL lines to <evidence>/verdicts.tsv and fails if any check fails.
#
# A LakeSource ingests orders and customers, personal data (L3) with their subject keys, and is
# published to a reader principal; a LakeTable keeps DWD (orders with their customer, a lookup
# join), personal too and published; another reads orders at L2, below its upstream. Then:
# - the governed tables are readable by the reader, the one below its upstream is not, and
#   lowering DWD's level, or dropping its retention or its classification, revokes the reader's
#   grant until it is declared again;
# - personal tables carry bounded retention (24 h, no snapshot kept for its count) and DWD, whose
#   writer compacts, periodic full compactions; ODS, whose writer is write-only, is expired by the
#   operator each hour; a tag kept two days on them is refused;
# - with the writer stopped, customer 42 is deleted at the source and a LakeErasure accepted; the
#   operator restarts right after. It must be logically erased by its deadline (the source checked,
#   no row of the subject in ODS or DWD, the files compacted, DWD's behind its fence, older
#   snapshots and the changes holding the deletes expired, tags and readers past it), with the rows
#   gone in an independent read, and a backfill accounted for where DWD's stream did not carry the
#   deletion; the jobs' Flink state must hold nothing from before the delete.
#   Physically it cannot be done within the run (orphan files are only cleaned a day after the
#   compaction), so past its shortened physical deadline it must report SlaBreach with the copies
#   left and why, keep the breach on record, and claim no physical completion.
#
# usage: run-governance.sh <run-id> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-governance.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-governance.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
flows="$REPO_DIR/tests/acceptance"
cdc="$flows/cdc"
mkdir -p "$evidence"/{status,dumps,sql,tables,erasure}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
for image in operator mysql sql; do
  echo "$image $(image_ref "$image")" | tee -a "$evidence/images.txt"
done
failed=0

step() { echo "$(date -u +%FT%TZ)	$*" | tee -a "$evidence/timeline.tsv"; }
verdict() {
  # One line per verdict: a detail's newlines and tabs become spaces.
  local detail="${3:-}"
  detail="${detail//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${detail//$'\t'/ }" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS ]] || failed=1
}

# capture <kind> <name> <file>: the declaration and its deployments, for status.py.
capture() {
  local key=source label=colvenna-lake.io/source
  if [[ "$1" == laketable ]]; then
    key=table label=colvenna-lake.io/table
  fi
  {
    printf '{"%s":' "$key"
    kubectl -n "$JOB_NAMESPACE" get "$1" "$2" -o json
    printf ',"deployments":'
    kubectl -n "$JOB_NAMESPACE" get flinkdeployment -l "$label=$2" -o json \
      | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["items"]))'
    printf '}'
  } >"$3"
}

# await <kind> <name> <label> <timeout-seconds> <expression>: polls until status.py says it holds.
await() {
  local kind="$1" name="$2" label="$3" end=$((SECONDS + $4)) started=$SECONDS
  local file="$evidence/status/$3.json"
  while ((SECONDS < end)); do
    if capture "$kind" "$name" "$file" 2>/dev/null && python3 "$flows/status.py" check "$file" "$5"; then
      verdict "$label" PASS "$((SECONDS - started))s"
      return 0
    fi
    sleep 10
  done
  # Before the run cleans up: what the readiness depended on (#245).
  [[ "$kind" != lakesource ]] || source_diagnostics "$name" "$evidence/diagnostics/$label"
  verdict "$label" FAIL "not within $4s: $5"
  return 1
}

# condition <kind> <name> <type>: the condition's "status reason: message".
condition() {
  kubectl -n "$JOB_NAMESPACE" get "$1" "$2" -o json | python3 -c '
import json, sys
c = [c for c in json.load(sys.stdin).get("status", {}).get("conditions", []) if c["type"] == sys.argv[1]]
print(" ".join([c[0]["status"], c[0]["reason"] + ":", str(c[0].get("message"))]) if c else "absent")' "$3"
}

# await_condition <kind> <name> <label> <timeout-seconds> <type> <status reason prefix>
await_condition() {
  local end=$((SECONDS + $4)) started=$SECONDS got=""
  while ((SECONDS < end)); do
    got="$(condition "$1" "$2" "$5" 2>/dev/null || true)"
    if [[ "$got" == "$6"* ]]; then
      verdict "$3" PASS "$((SECONDS - started))s: $got"
      return 0
    fi
    sleep 10
  done
  verdict "$3" FAIL "$5 is '$got', not '$6'"
  return 1
}

# erasure_field <python expression over s, the LakeErasure's status>
erasure_field() {
  kubectl -n "$JOB_NAMESPACE" get lakeerasure "erase-$run" -o json \
    | tee "$evidence/erasure/latest.json" \
    | python3 -c 'import json,sys; s=json.load(sys.stdin).get("status",{}); print(eval(sys.argv[1]))' "$1"
}

# laketable <name> <db> <table> <primary-key> <upstream,...> <query> [<extra spec yaml>]
laketable() {
  local file="$evidence/tables/$1.yaml" upstream="" u
  for u in ${5//,/ }; do upstream+="\"$u\", "; done
  cat >"$file" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTable
metadata: {name: $1, namespace: $JOB_NAMESPACE}
spec:
  target: {database: $2, table: $3}
  primaryKey: [$4]
  upstream: [${upstream%, }]
  query: |
    ${6//$'\n'/$'\n'    }
${7:-}
YAML
  kubectl apply -f "$file"
}

# readable <db> <table>: the HTTP status the reader's token gets for the table. Needs admin_open.
readable() {
  printf 'header = "Authorization: Bearer %s"\n' "$(cat "$work/reader.token")" >"$work/reader.curlrc"
  curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$work/reader.curlrc" \
    "https://localhost:$ADMIN_PORT/v1/colvenna/databases/$1/tables/$2"
}

small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
work="$(mktemp -d)"
chmod 700 "$work"
trap 'admin_close; rm -rf "$work"' EXIT
ensure_rds_ca "$JOB_NAMESPACE"

# ------------------------------------------------------------------------- the reader
reader="reader-$run"
step "reader principal $reader"
admin_open
ensure_principal_token "$reader" "$JOB_NAMESPACE" "$reader-token" P1D
admin_close
kubectl -n "$JOB_NAMESPACE" get secret "$reader-token" -o jsonpath='{.data.token}' | base64 -d >"$work/reader.token"

# --------------------------------------------------------------------------------- the source
db="gov_$run" ods="ods_gov_$run" dwd="dwd_gov_$run" low="low_gov_$run"
source_name="gov-$run" dwd_name="gov-dwd-$run" low_name="gov-low-$run"
step "mysql fixture $db"
{
  python3 "$cdc/mysql_contract.py" reset "$db"
  python3 "$cdc/mysql_contract.py" setup "$db"
  cat <<SQL
USE $db;
SET SESSION cte_max_recursion_depth = 100000;
INSERT INTO customers (id, name, region, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 200)
  SELECT i, CONCAT('customer-', i), ELT(1 + i % 4, 'north', 'south', 'east', 'west'), 1 FROM n;
INSERT INTO orders (id, customer_id, amount, state, note, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 5000)
  SELECT i, 1 + i % 200, ROUND(i * 1.25, 2), 'created', NULL, 1 FROM n;
SQL
} >"$evidence/sql/setup.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/setup.sql" >/dev/null

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
  tables: {include: [orders, customers]}
  target: {database: $ods, bucket: 2}
  governance:
    owner: shop-team
    level: L3
    categories: [personal]
    retention: 24h
    subjectKeys: {orders: [customer_id], customers: [id]}
  readers: [$reader]
  job: $small
  deletionPolicy: Delete
YAML
step "writer start"
bash "$cdc/writer.sh" start mysql "$db" "gw-$run"
kubectl apply -f "$evidence/source.yaml"
await lakesource "$source_name" source-ready 900 \
  "state('orders') == 'Ready' and state('customers') == 'Ready' and live_all()" || true
await_condition lakesource "$source_name" source-governed 300 Governed "True Governed" || true
await_condition lakesource "$source_name" source-published 300 Published "True Published" || true

dwd_query="SELECT o.id AS order_id, o.customer_id, c.region, o.amount, o.version_no
FROM (SELECT *, PROCTIME() AS pt FROM $ods.orders) AS o
JOIN $ods.customers FOR SYSTEM_TIME AS OF o.pt AS c ON o.customer_id = c.id"
step "apply $dwd_name (personal, L3) and $low_name (L2, below its upstream)"
dwd_governance='{"owner": "analytics", "level": "L3", "categories": ["personal"], "retention": "24h", "subjectKey": ["customer_id"]}'
laketable "$dwd_name" "$dwd" orders order_id "$ods.orders,$ods.customers" "$dwd_query" "  governance: $dwd_governance
  readers: [$reader]
  deletionPolicy: Delete
  job: $small"
laketable "$low_name" "$low" orders order_id "$ods.orders" "SELECT id AS order_id, amount FROM $ods.orders" "  governance: {owner: analytics, level: L2, retention: 7d}
  readers: [$reader]
  deletionPolicy: Delete
  job: $small"
await laketable "$dwd_name" dwd-running 900 "phase == 'Running' and live_all()" || true
await_condition laketable "$dwd_name" dwd-published 300 Published "True Published" || true
await_condition laketable "$low_name" low-not-governed 600 Governed "False BelowUpstream" || true
await_condition laketable "$low_name" low-not-published 300 Published "False NotGoverned" || true

step "what the reader may read"
admin_open
codes="ods.orders=$(readable "$ods" orders) ods.customers=$(readable "$ods" customers) dwd=$(readable "$dwd" orders) low=$(readable "$low" orders)"
admin_close
if [[ "$codes" == "ods.orders=200 ods.customers=200 dwd=200 low=403" ]]; then
  verdict published-only-governed PASS "$codes"
else
  verdict published-only-governed FAIL "$codes"
fi

step "lower $dwd_name to L2: below its upstream, unpublished; then restore it"
kubectl -n "$JOB_NAMESPACE" patch laketable "$dwd_name" --type merge -p '{"spec":{"governance":{"level":"L2","categories":[]}}}'
await_condition laketable "$dwd_name" dwd-lowered-unpublished 300 Published "False NotGoverned" || true
admin_open
lowered="$(readable "$dwd" orders)"
admin_close
kubectl -n "$JOB_NAMESPACE" patch laketable "$dwd_name" --type merge -p '{"spec":{"governance":{"level":"L3","categories":["personal"]}}}'
await_condition laketable "$dwd_name" dwd-republished 300 Published "True Published" || true
admin_open
restored="$(readable "$dwd" orders)"
admin_close
if [[ "$lowered" == 403 && "$restored" == 200 ]]; then
  verdict grant-follows-governance PASS "lowered: $lowered, restored: $restored"
else
  verdict grant-follows-governance FAIL "lowered: $lowered, restored: $restored"
fi

step "drop $dwd_name's retention, then its classification: unpublished; then declare it again"
kubectl -n "$JOB_NAMESPACE" patch laketable "$dwd_name" --type merge -p '{"spec":{"governance":{"retention":null}}}'
await_condition laketable "$dwd_name" dwd-retention-missing 300 Governed "False RetentionMissing" || true
admin_open
no_retention="$(readable "$dwd" orders)"
admin_close
kubectl -n "$JOB_NAMESPACE" patch laketable "$dwd_name" --type merge -p '{"spec":{"governance":null}}'
await_condition laketable "$dwd_name" dwd-unclassified 300 Governed "False Unclassified" || true
admin_open
unclassified="$(readable "$dwd" orders)"
admin_close
kubectl -n "$JOB_NAMESPACE" patch laketable "$dwd_name" --type merge -p "{\"spec\":{\"governance\":$dwd_governance}}"
await_condition laketable "$dwd_name" dwd-declared-republished 300 Published "True Published" || true
admin_open
declared="$(readable "$dwd" orders)"
admin_close
if [[ "$no_retention" == 403 && "$unclassified" == 403 && "$declared" == 200 ]]; then
  verdict undeclared-unpublished PASS "no retention: $no_retention, unclassified: $unclassified, declared again: $declared"
else
  verdict undeclared-unpublished FAIL "no retention: $no_retention, unclassified: $unclassified, declared again: $declared"
fi

step "bounded retention on the personal tables"
bash "$flows/lake.sh" table "$evidence/dumps/tables.jsonl" "$ods.orders" "$dwd.orders" >/dev/null
if python3 - "$evidence/dumps/tables.jsonl" "$ods.orders" "$dwd.orders" >"$work/options.txt" <<'PY'
import json, sys
tables = {d["table"]: d["options"] for d in map(json.loads, open(sys.argv[1])) if d.get("type") == "TABLE"}
ods, dwd = tables[sys.argv[2]], tables[sys.argv[3]]
want = {"snapshot.time-retained": "24 h", "consumer.expiration-time": "24 h", "snapshot.num-retained.min": "1"}
ok = all(ods.get(k) == v and dwd.get(k) == v for k, v in want.items())
ok = ok and dwd.get("compaction.optimization-interval") == "1 h"
print({k: (ods.get(k), dwd.get(k)) for k in list(want) + ["compaction.optimization-interval"]})
sys.exit(0 if ok else 1)
PY
then
  verdict bounded-retention PASS "$(cat "$work/options.txt")"
else
  verdict bounded-retention FAIL "$(cat "$work/options.txt")"
fi
cat >"$evidence/tables/tag-$run.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: gov-tag-$run, namespace: $JOB_NAMESPACE}
spec:
  type: createTag
  table: {database: $ods, table: orders}
  createTag: {name: long-$run, timeRetained: 2d}
YAML
kubectl apply -f "$evidence/tables/tag-$run.yaml"
await_condition laketask "gov-tag-$run" long-tag-refused 300 Finished "False RetentionTooLong" || true
# The ODS writer is write-only and expires nothing: the operator runs the expiry each hour.
end=$((SECONDS + 900))
retained=""
while ((SECONDS < end)); do
  retained="$(kubectl -n "$JOB_NAMESPACE" get laketask -l "colvenna-lake.io/retention=$source_name" \
    -o jsonpath='{range .items[*]}{.spec.table.table}={.status.phase}{" "}{end}' 2>/dev/null || true)"
  [[ "$retained" == *"orders=Succeeded"* && "$retained" == *"customers=Succeeded"* ]] && break
  sleep 20
done
if [[ "$retained" == *"orders=Succeeded"* && "$retained" == *"customers=Succeeded"* ]]; then
  verdict retention-enforced PASS "$retained"
else
  verdict retention-enforced FAIL "expiries: $retained"
fi

# ------------------------------------------------------------------------------- erasure
# A table below its upstream would hold the erasure Blocked: it served its purpose here.
kubectl -n "$JOB_NAMESPACE" delete laketable "$low_name" --timeout=10m
step "writer stop: $(bash "$cdc/writer.sh" stop "gw-$run")"
await laketable "$dwd_name" dwd-caught-up 600 "lag('$ods.orders') == 0 and live_all()" || true
step "the erasure of customer 42 is requested; the source still holds the subject's rows"
printf '42\n' >"$work/subject"
kubectl -n "$JOB_NAMESPACE" create secret generic "erase-$run" --from-file=values="$work/subject" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
rm -f "$work/subject"
cat >"$evidence/erasure/erasure.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeErasure
metadata: {name: erase-$run, namespace: $JOB_NAMESPACE}
spec:
  subjectSecret: {name: erase-$run}
  deadlines: {logical: 60m, physical: 75m}
YAML
kubectl apply -f "$evidence/erasure/erasure.yaml"
# Before the source deletes: the source and the lake both count the subject's rows, the check that
# the subject key matches the values each holds (a later 0 is then proof).
end=$((SECONDS + 600))
seen=""
while ((SECONDS < end)); do
  seen="$(erasure_field '" ".join(c["kind"] + ":" + str(c.get("seen") or 0) for c in s.get("copies", []) if c["kind"] in ("SourceDatabase", "Rows") and c.get("state") != "Blocked")' 2>/dev/null || true)"
  [[ -n "$seen" && "$seen" != *":0"* ]] && break
  sleep 20
done
if [[ -n "$seen" && "$seen" != *":0"* ]]; then
  verdict erasure-counted-before PASS "$seen"
else
  verdict erasure-counted-before FAIL "${seen:-no copies yet}"
fi
step "the source's owner deletes customer 42"
cat >"$evidence/sql/delete-subject.sql" <<SQL
DELETE FROM $db.orders WHERE customer_id = 42;
DELETE FROM $db.customers WHERE id = 42;
SQL
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/delete-subject.sql" >/dev/null
started=$SECONDS
# The erasure is accepted, then the operator stops and starts again: it takes up what it began.
until [[ "$(erasure_field 's.get("acceptedAt") is not None' 2>/dev/null)" == True ]]; do
  ((SECONDS - started < 300)) || break
  sleep 5
done
step "operator restart after acceptance"
kubectl -n "$OPERATOR_NAMESPACE" rollout restart "deployment/$OPERATOR_RELEASE"
kubectl -n "$OPERATOR_NAMESPACE" rollout status "deployment/$OPERATOR_RELEASE" --timeout=5m
end=$((SECONDS + 3600))
phase=""
while ((SECONDS < end)); do
  phase="$(erasure_field 's.get("phase")' 2>/dev/null || true)"
  [[ "$phase" == LogicallyErased || "$phase" == PhysicallyErased || "$phase" == SlaBreach || "$phase" == Blocked ]] && break
  sleep 20
done
cp "$evidence/erasure/latest.json" "$evidence/erasure/logical.json"
if [[ "$phase" == LogicallyErased ]]; then
  verdict erasure-logical PASS "$(erasure_field '"accepted " + s["acceptedAt"] + ", logically erased " + s["logicallyErasedAt"]')"
else
  verdict erasure-logical FAIL "phase $phase: $(erasure_field 's.get("message")')"
fi
python3 - "$evidence/erasure/logical.json" >"$evidence/erasure/copies.txt" <<'PY'
import json, sys
for c in json.load(open(sys.argv[1]))["status"]["copies"]:
    print(f"{c['kind']:<15} {c['name']:<40} {c['layer']:<9} {c['state']:<16} {c.get('evidence') or c.get('message')}")
PY
cat "$evidence/erasure/copies.txt"
# An independent read: no row of the subject left in the current tables, nor any snapshot before
# each table's clean one.
bash "$flows/lake.sh" rows "$evidence/dumps/after.rows.jsonl" "$ods.orders" "$ods.customers" "$dwd.orders" >/dev/null
bash "$flows/lake.sh" table "$evidence/dumps/after.tables.jsonl" "$ods.orders" "$ods.customers" "$dwd.orders" >/dev/null
if python3 - "$evidence/dumps/after.rows.jsonl" "$evidence/dumps/after.tables.jsonl" "$evidence/erasure/logical.json" \
  >"$work/independent.txt" <<'PY'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
left = [r for r in rows if r.get("type") == "ROW"
        and (r["row"].get("customer_id") == "42" or (r["table"].endswith(".customers") and r["row"].get("id") == "42"))]
tables = {d["table"]: d for d in map(json.loads, open(sys.argv[2])) if d.get("type") == "TABLE"}
clean = {c["name"]: c["cleanSnapshot"] for c in json.load(open(sys.argv[3]))["status"]["copies"]
         if c["kind"] == "Rows" and c.get("cleanSnapshot") is not None}
early = {t: (tables[t]["earliestSnapshot"], s) for t, s in clean.items() if tables[t]["earliestSnapshot"] < s}
print(f"rows of the subject: {len(left)}; earliest before clean: {early}; clean: {clean}")
sys.exit(0 if not left and not early and len(clean) == 3 else 1)
PY
then
  verdict erasure-independent-read PASS "$(cat "$work/independent.txt")"
else
  verdict erasure-independent-read FAIL "$(cat "$work/independent.txt")"
fi
# DWD's writer compacts: the erasure's compaction of it ran behind its fence, which was lifted.
kubectl -n "$JOB_NAMESPACE" get laketask -l "colvenna-lake.io/erasure=erase-$run" -o json >"$evidence/erasure/tasks.json"
if python3 - "$evidence/erasure/tasks.json" "$dwd" >"$work/fenced.txt" <<'PY'
import json, sys
tasks = [t for t in json.load(open(sys.argv[1]))["items"]
         if t["spec"]["type"] == "compact" and t["spec"]["table"]["database"] == sys.argv[2]]
ok = [t for t in tasks if t.get("status", {}).get("phase") == "Succeeded"
      and (t["status"].get("fence") or {}).get("releasedAt")]
print([(t["metadata"]["name"], t.get("status", {}).get("phase"), t.get("status", {}).get("fence")) for t in tasks])
sys.exit(0 if ok else 1)
PY
then
  verdict dwd-compacted-behind-fence PASS "$(cat "$work/fenced.txt")"
else
  verdict dwd-compacted-behind-fence FAIL "$(cat "$work/fenced.txt")"
fi
# Where DWD's stream did not carry the deletion (its lookup no longer found the customer), the
# erasure backfilled DWD, said so in an event, and DWD's rows name the backfill.
python3 - "$evidence/erasure/tasks.json" "$dwd" >"$work/backfills.txt" <<'PY'
import json, sys
print(" ".join(t["metadata"]["name"] + "=" + str(t.get("status", {}).get("phase"))
               for t in json.load(open(sys.argv[1]))["items"]
               if t["spec"]["type"] == "backfill" and t["spec"]["table"]["database"] == sys.argv[2]))
PY
backfills="$(cat "$work/backfills.txt")"
rows_evidence="$(erasure_field "[c.get('evidence') or '' for c in s['copies'] if c['kind'] == 'Rows' and c['name'] == '$dwd.orders'][0]")"
announced="$(kubectl -n "$JOB_NAMESPACE" get events --field-selector "involvedObject.name=erase-$run,reason=ErasureBackfill" \
  -o jsonpath='{range .items[*]}{.message}{"\n"}{end}')"
if [[ -z "$backfills" && "$rows_evidence" != *backfill* && -z "$announced" ]]; then
  verdict dwd-backfill-accounted PASS "the stream carried the deletion, no backfill: $rows_evidence"
elif [[ "$backfills" == *=Succeeded* && "$rows_evidence" == *"after backfill"* && -n "$announced" ]]; then
  verdict dwd-backfill-accounted PASS "$backfills; event: $announced; rows: $rows_evidence"
else
  verdict dwd-backfill-accounted FAIL "backfills: [$backfills]; event: [$announced]; rows: $rows_evidence"
fi
step "the jobs' Flink state"
end=$((SECONDS + 900))
state=""
while ((SECONDS < end)); do
  # The two jobs' state, and the state of jobs no longer declared: every one of them done.
  state="$(erasure_field '[c["state"] for c in s["copies"] if c["kind"] == "FlinkState"]' 2>/dev/null || true)"
  [[ "$state" =~ ^\[\'Done\'(,\ \'Done\')+\]$ ]] && break
  sleep 30
done
detail="$(erasure_field '"; ".join(c["name"] + ": " + str(c.get("evidence") or c.get("message")) for c in s["copies"] if c["kind"] == "FlinkState")')"
if [[ "$state" =~ ^\[\'Done\'(,\ \'Done\')+\]$ ]]; then
  verdict flink-state-checked PASS "$detail"
else
  verdict flink-state-checked FAIL "$state: $detail"
fi
step "past the physical deadline"
# Until the deadline and 10 minutes more, however long the logical erasure took.
deadline="$(erasure_field 's.get("physicalDeadline")')"
left="$(python3 -c '
import datetime, re, sys
d = datetime.datetime.fromisoformat(re.sub(r"(\.\d{6})\d*", r"\1", sys.argv[1]).replace("Z", "+00:00"))
print(max(0, int((d - datetime.datetime.now(datetime.timezone.utc)).total_seconds())))' "$deadline")"
end=$((SECONDS + left + 600))
while ((SECONDS < end)); do
  phase="$(erasure_field 's.get("phase")' 2>/dev/null || true)"
  [[ "$phase" == SlaBreach || "$phase" == PhysicallyErased ]] && break
  sleep 30
done
cp "$evidence/erasure/latest.json" "$evidence/erasure/physical.json"
message="$(erasure_field 's.get("message")')"
breaches="$(erasure_field 'len(s.get("breaches", []))')"
# No physical completion claimed: PhysicallyErased stays False, WithinDeadlines turns False.
claims="$(erasure_field '" ".join(c["type"] + "=" + c["status"] for c in s.get("conditions", []) if c["type"] in ("PhysicallyErased", "WithinDeadlines")) + " physicallyErasedAt=" + str(s.get("physicallyErasedAt"))')"
if [[ "$phase" == SlaBreach && "$message" == *"Files"* && "$breaches" == 1 \
  && "$claims" == "PhysicallyErased=False WithinDeadlines=False physicallyErasedAt=None" ]] \
  && grep -qx ErasureOverdue <<<"$(kubectl -n "$JOB_NAMESPACE" get events --field-selector "involvedObject.name=erase-$run,type=Warning" \
    -o jsonpath='{range .items[*]}{.reason}{"\n"}{end}')"; then
  verdict erasure-overdue-reported PASS "$claims; $message"
else
  verdict erasure-overdue-reported FAIL "phase $phase, $claims: $message"
fi
storage="$(erasure_field '[c["state"] + ": " + str(c.get("evidence")) for c in s["copies"] if c["kind"] == "Storage"][0]')"
if [[ "$storage" == "Done: bucket "* ]]; then
  verdict storage-evidence PASS "$storage"
else
  verdict storage-evidence FAIL "$storage"
fi

# ------------------------------------------------------------------------------- cleanup
step "delete the erasure, the tables and the source; the reader's grants go with them"
kubectl -n "$JOB_NAMESPACE" get events -o json >"$evidence/events.json"
kubectl -n "$JOB_NAMESPACE" delete lakeerasure "erase-$run" --timeout=5m
kubectl -n "$JOB_NAMESPACE" delete laketask "gov-tag-$run" --ignore-not-found --timeout=5m
kubectl -n "$JOB_NAMESPACE" delete laketable "$dwd_name" --timeout=10m
kubectl -n "$JOB_NAMESPACE" delete lakesource "$source_name" --timeout=10m
admin_open
grants="$(admin_call GET "/colvenna/admin/v1/grants?principal=$reader" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["grants"]))')"
revoke_principal "$reader"
admin_call POST "/colvenna/admin/v1/principals/$reader/disable" >/dev/null || true
admin_close
if [[ "$grants" == 0 ]]; then
  verdict unpublished-on-delete PASS "the reader holds no grant"
else
  verdict unpublished-on-delete FAIL "the reader still holds $grants grants"
fi
kubectl -n "$JOB_NAMESPACE" delete secret "erase-$run" "$reader-token" --ignore-not-found >/dev/null
python3 "$cdc/mysql_contract.py" reset "$db" >"$evidence/sql/reset.sql"
bash "$FIXTURES_DIR/sources/sources.sh" mysql "$evidence/sql/reset.sql" >/dev/null
echo "verdicts:"
cat "$evidence/verdicts.tsv"
exit "$failed"
