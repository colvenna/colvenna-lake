#!/usr/bin/env bash
# Acceptance on EKS: a declared MongoDB source, from declaration to deletion, under continuous
# writes. The development MongoDB (dev-mongo.sh: one TLS member of a replica set, access control
# on) stands in for the user's. Writes PASS/FAIL lines to <evidence>/verdicts.tsv and fails if any
# check fails.
#
# - The source's scope is a list and a pattern (prod.*); it admits orders (ObjectId keys) and
#   customers (string keys) and refuses ledger (integer keys). The oplog's reach is in the
#   status, with a Warning when it is shorter than declared.
# - Under writes: a new field appears in orders (a new column); a new collection, products, is
#   created with documents already in it: the running job does not take it (AddedAfterStart); a
#   second source reads that one collection; the member is deleted and comes back primary; a
#   TaskManager is lost; the job is upgraded.
# - customers is dropped, and catalog is replaced by a collection renamed over it: only those
#   tables leave the job (TableGone, SourceReplaced), the others keep running.
# - products, and at the end, with the writer stopped, orders equal their collections document by
#   document, and their changelogs replay with every before image the row the table held.
# - Deletion: the jobs gone, the tables and databases dropped, the token refused.
#
# usage: run-mongo.sh <run-id> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-mongo.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-mongo.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
flows="$REPO_DIR/tests/acceptance"
mongo="$flows/mongo"
cdc="$flows/cdc"
mkdir -p "$evidence"/{status,dumps}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
for image in operator mongodb; do
  echo "$image $(image_ref "$image")" | tee -a "$evidence/images.txt"
done
echo "mongo $MONGO_IMAGE" | tee -a "$evidence/images.txt"
failed=0
step() { echo "$(date -u +%FT%TZ)	$*" | tee -a "$evidence/timeline.tsv"; }
verdict() {
  # One line per verdict: a detail's newlines and tabs become spaces.
  local detail="${3:-}"
  detail="${detail//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${detail//$'\t'/ }" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS ]] || failed=1
}

mname="mg-$run" db="shop_$run" ods="ods_mongo_$run" source_name="lms-$run" writer="mw-$run"
one_name="lmo-$run" one_ods="ods_mongo1_$run"
small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
work="$(mktemp -d)"
chmod 700 "$work"
trap 'admin_close; rm -rf "$work"' EXIT

# A run again under the same id: the verifier's read grants on its databases, left by the run
# before, would make them readable beyond their source (TargetNotIsolated).
admin_open
for id in $(admin_call GET "/colvenna/admin/v1/grants?principal=verifier" \
  | python3 -c 'import json,sys; print(" ".join(str(g["id"]) for g in json.load(sys.stdin)["grants"] if g.get("database") in sys.argv[1:]))' "$ods" "$one_ods"); do
  admin_call DELETE "/colvenna/admin/v1/grants/$id" >/dev/null
done
admin_close

# capture <file>: the source and its deployments, for status.py.
capture() {
  {
    printf '{"source":'
    kubectl -n "$JOB_NAMESPACE" get lakesource "$source_name" -o json
    printf ',"deployments":'
    kubectl -n "$JOB_NAMESPACE" get flinkdeployment -l "colvenna-lake.io/source=$source_name" -o json \
      | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["items"]))'
    printf '}'
  } >"$1"
}

# await <label> <timeout-seconds> <expression>
await() {
  local label="$1" end=$((SECONDS + $2)) started=$SECONDS file="$evidence/status/$1.json"
  while ((SECONDS < end)); do
    if capture "$file" 2>/dev/null && python3 "$flows/status.py" check "$file" "$3"; then
      verdict "$label" PASS "$((SECONDS - started))s"
      return 0
    fi
    sleep 10
  done
  # Before the run cleans up: what the readiness depended on (#245).
  source_diagnostics "$source_name" "$evidence/diagnostics/$label"
  verdict "$label" FAIL "not within $2s: $3"
  python3 "$flows/status.py" tables "$file" 2>/dev/null || true
  return 1
}

# warning_event <reason>
warning_event() {
  grep -qx "$1" <<<"$(kubectl -n "$JOB_NAMESPACE" get events --field-selector "involvedObject.name=$source_name,type=Warning" \
    -o jsonpath='{range .items[*]}{.reason}{"\n"}{end}')"
}

# shell: stdin as a mongosh script against the source database, as root.
shell() {
  bash "$FIXTURES_DIR/mongo/dev-mongo.sh" shell "$mname"
}

# export <collection> <file>: the collection's documents, one relaxed Extended JSON per line.
export_collection() {
  shell <<JS | grep '^{' >"$2"
db.getSiblingDB("$db").$1.find().forEach(d => print(EJSON.stringify(d, {relaxed: true})));
JS
}

# compare <collection> [<target-database>]: rows against the collection, then the changelog's
# replay.
compare() {
  local table="${2:-$ods}.$1" attempt rows="$evidence/dumps/$1.rows.jsonl"
  for attempt in $(seq 1 15); do
    export_collection "$1" "$evidence/dumps/$1.source.jsonl"
    bash "$flows/lake.sh" rows "$rows" "$table" >/dev/null
    if python3 "$mongo/mongo.py" rows --lake "$rows" --source "$evidence/dumps/$1.source.jsonl" \
      --table "$table" >"$evidence/dumps/$1.rows-verdict.json"; then
      verdict "rows-$1" PASS "$(cat "$evidence/dumps/$1.rows-verdict.json")"
      bash "$flows/lake.sh" changelog "$evidence/dumps/$1.changelog.jsonl" "$table" >/dev/null
      if python3 "$cdc/compare.py" changelog --lake "$evidence/dumps/$1.changelog.jsonl" \
        --rows "$rows" --table "$table" --key _id --version "" >"$evidence/dumps/$1.changelog-verdict.json"; then
        verdict "changelog-$1" PASS "$(cat "$evidence/dumps/$1.changelog-verdict.json")"
      else
        verdict "changelog-$1" FAIL "$(cat "$evidence/dumps/$1.changelog-verdict.json")"
      fi
      return 0
    fi
    echo "attempt $attempt: $(cat "$evidence/dumps/$1.rows-verdict.json")"
    sleep 20
  done
  verdict "rows-$1" FAIL "$(cat "$evidence/dumps/$1.rows-verdict.json")"
}

token_status() {
  printf 'header = "Authorization: Bearer %s"\n' "$(cat "$1")" >"$ADMIN_DIR/probe.curlrc"
  curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/probe.curlrc" \
    "https://localhost:$ADMIN_PORT/v1/config?warehouse=colvenna"
}

# ------------------------------------------------------------------------ the source
step "development MongoDB $mname: replica set rs0, TLS, access control"
bash "$FIXTURES_DIR/mongo/dev-mongo.sh" install "$mname" "$evidence/mongo"
step "fixture $db: orders 5000 (ObjectId keys, nested and array fields), customers 200, ledger 20"
shell <<JS | tee "$evidence/dumps/fixture.txt"
const d = db.getSiblingDB("$db");
d.orders.drop(); d.customers.drop(); d.ledger.drop(); d.products.drop(); d.catalog.drop();
const orders = [];
for (let i = 1; i <= 5000; i++) {
  orders.push({customer: i % 200 + 1, amount: Math.round(i * 125) / 100, state: "created",
               tags: ["fixture", "batch-" + (i % 3)], address: {city: "Oslo", zip: i % 50}, round: 0});
}
d.orders.insertMany(orders);
const customers = [];
for (let i = 1; i <= 200; i++) { customers.push({_id: "c-" + i, name: "customer-" + i, tier: "basic", round: 0}); }
d.customers.insertMany(customers);
const ledger = [];
for (let i = 1; i <= 20; i++) { ledger.push({_id: i, amount: i}); }
d.ledger.insertMany(ledger);
const catalog = [];
for (let i = 1; i <= 100; i++) { catalog.push({name: "item-" + i, price: i}); }
d.catalog.insertMany(catalog);
const events = [];
for (let i = 1; i <= 500; i++) { events.push({_id: "e-" + i, kind: "view", value: i}); }
d.events.drop(); d.events.insertMany(events);
print("orders " + d.orders.countDocuments() + " customers " + d.customers.countDocuments());
JS

cat >"$evidence/source.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: $source_name, namespace: $JOB_NAMESPACE}
spec:
  type: mongodb
  connection:
    host: $mname-0.$mname.$JOB_NAMESPACE.svc
    database: $db
    credentialsSecret: {name: $mname-cdc}
    caConfigMap: $mname-ca
    authSource: admin
  tables: {include: [orders, customers, ledger, catalog, "prod.*"]}
  target: {database: $ods, bucket: 2}
  job: $small
  deletionPolicy: Delete
YAML
# One collection's source: products, which appears after the first source's job started.
cat >"$evidence/one-source.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: $one_name, namespace: $JOB_NAMESPACE}
spec:
  type: mongodb
  connection:
    host: $mname-0.$mname.$JOB_NAMESPACE.svc
    database: $db
    credentialsSecret: {name: $mname-cdc}
    caConfigMap: $mname-ca
  tables: {include: [products]}
  target: {database: $one_ods}
  job: $small
  deletionPolicy: Delete
YAML
step "writer start; apply $source_name"
bash "$mongo/writer.sh" start "$mname" "$db" "$writer"
kubectl apply -f "$evidence/source.yaml"
await source-ready 900 "state('orders') == 'Ready' and state('customers') == 'Ready' \
  and state('catalog') == 'Ready' and live_all()" || true
await ledger-refused 60 "state('ledger') == 'Rejected' and reason('ledger') == 'UnsupportedKeyType'" || true
if await oplog-window-reported 60 "condition('RetentionWindow') == 'False'" && warning_event RetentionWindowShort; then
  verdict oplog-window-alerted PASS "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["source"]["status"]["retention"])' "$evidence/status/oplog-window-reported.json")"
else
  verdict oplog-window-alerted FAIL "no RetentionWindow condition or Warning"
fi

# ------------------------------------------------------------------------- changes
step "a new collection, products, with 300 documents before the job reads it"
shell <<JS
const d = db.getSiblingDB("$db");
const p = [];
for (let i = 1; i <= 300; i++) { p.push({sku: "sku-" + i, price: i + 0.5, stock: {warehouse: "w1", count: i}}); }
d.products.insertMany(p);
print("products " + d.products.countDocuments());
JS
# A collection does not join a running job (Flink CDC can lose its changes after a failover,
# FLINK-40741): refused, and told.
if await products-refused 300 "state('products') == 'Rejected' and reason('products') == 'AddedAfterStart' and live_all()" \
  && grep -q '^products: .*AddedAfterStart' <<<"$(kubectl -n "$JOB_NAMESPACE" get events \
    --field-selector "involvedObject.name=$source_name,type=Warning,reason=TableRejected" \
    -o jsonpath='{range .items[*]}{.message}{"\n"}{end}')"; then
  verdict products-refused-alerted PASS "AddedAfterStart, Warning TableRejected"
else
  verdict products-refused-alerted FAIL "no AddedAfterStart refusal with a Warning"
fi

step "one collection's source: $one_name reads products"
kubectl apply -f "$evidence/one-source.yaml"
saved="$source_name"
source_name="$one_name"
await one-collection-ready 900 "state('products') == 'Ready' and live_all()" || true
shell <<JS
const d = db.getSiblingDB("$db");
d.products.insertMany([{name: "late-1", price: 50}, {name: "late-2", price: 60}]);
d.products.updateMany({}, {\$inc: {"stock.count": 1}});
d.products.deleteMany({price: {\$lt: 20}});
print("products changed");
JS
sleep 90
compare products "$one_ods"
kubectl -n "$JOB_NAMESPACE" get secret "$one_name-catalog-token" -o jsonpath='{.data.token}' | base64 -d >"$work/one-token"
kubectl -n "$JOB_NAMESPACE" delete lakesource "$one_name" --timeout=10m
admin_open
one_database="$(curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/curlrc" \
  "https://localhost:$ADMIN_PORT/v1/colvenna/databases/$one_ods")"
one_token="$(token_status "$work/one-token")"
admin_close
if [[ "$one_database" == 404 && "$one_token" == 401 ]]; then
  verdict one-collection-deleted PASS "database dropped (404), token refused (401)"
else
  verdict one-collection-deleted FAIL "database=$one_database token=$one_token"
fi
source_name="$saved"

# ledger is refused by design, so the source stays Degraded: recovery is its job running again,
# its tables Ready (seen in run m2).
step "the member is deleted and comes back"
uid_before="$(kubectl -n "$JOB_NAMESPACE" get pod "$mname-0" -o jsonpath='{.metadata.uid}')"
kubectl -n "$JOB_NAMESPACE" delete pod "$mname-0" --wait=true
kubectl -n "$JOB_NAMESPACE" rollout status "statefulset/$mname" --timeout=10m
# A new pod, on the same volume, primary again.
primary=false
for _ in $(seq 1 60); do
  if [[ "$(kubectl -n "$JOB_NAMESPACE" get pod "$mname-0" -o jsonpath='{.metadata.uid}' 2>/dev/null)" != "$uid_before" ]] \
    && grep -qx true <<<"$(shell <<<'print(db.hello().isWritablePrimary)' 2>/dev/null)"; then
    primary=true
    break
  fi
  sleep 5
done
if [[ "$primary" == true ]]; then
  verdict member-back-primary PASS "a new pod, primary again"
else
  verdict member-back-primary FAIL "no new primary member within 300s"
fi
await recovered-from-member-loss 900 \
  "live_all() and condition('JobRunning') == 'True' and state('orders') == 'Ready'" || true

step "a TaskManager is lost"
tm=""
for _ in $(seq 1 30); do
  tm="$(kubectl -n "$JOB_NAMESPACE" get pod -l "app=$source_name-cdc,component=taskmanager" -o name | sed -n 1p)"
  [[ -n "$tm" ]] && break
  sleep 10
done
if [[ -n "$tm" ]]; then
  kubectl -n "$JOB_NAMESPACE" delete "$tm" --wait=true
  sleep 20
  await recovered-from-taskmanager-loss 900 \
    "live_all() and condition('JobRunning') == 'True' and state('orders') == 'Ready'" || true
else
  verdict recovered-from-taskmanager-loss FAIL "no TaskManager to lose within 300s"
fi

step "upgrade: TaskManager memory"
kubectl -n "$JOB_NAMESPACE" patch lakesource "$source_name" --type merge \
  -p '{"spec":{"job":{"taskManager":{"memory":"2560m"}}}}'
sleep 20
await upgraded 900 "live_all() and condition('SpecApplied') == 'True' and 'chk-' in str(restored('$source_name-cdc'))" || true

step "catalog is replaced: a collection renamed over it"
shell <<JS
const d = db.getSiblingDB("$db");
d.catalog_new.drop();
d.catalog_new.insertMany([{name: "replacement-1", price: 1}, {name: "replacement-2", price: 2}]);
d.catalog_new.renameCollection("catalog", true);
print("catalog replaced");
JS
await catalog-replaced 600 "state('catalog') == 'Failed' and reason('catalog') == 'SourceReplaced' \
  and state('orders') == 'Ready' and live_all()" || true

step "customers is dropped: only its table leaves the job"
shell <<JS
db.getSiblingDB("$db").customers.drop();
print("customers dropped");
JS
if await customers-isolated 900 \
  "state('customers') is None and state('orders') == 'Ready' and live_all()" \
  && warning_event TableGone; then
  verdict customers-isolated-alerted PASS "$(kubectl -n "$JOB_NAMESPACE" get events --field-selector "involvedObject.name=$source_name,type=Warning" -o jsonpath='{range .items[*]}{.reason}: {.message}{"\n"}{end}' | grep -m1 '^TableGone: customers')"
else
  verdict customers-isolated-alerted FAIL "no TableGone for customers"
fi

# --------------------------------------------------------------------------- the end
step "writer stop: $(bash "$mongo/writer.sh" stop "$writer")"
sleep 120
compare orders
bash "$flows/lake.sh" rows "$evidence/dumps/orders-header.jsonl" "$ods.orders" >/dev/null
if grep -q '"name": *"channel"' <<<"$(head -1 "$evidence/dumps/orders-header.jsonl")"; then
  verdict new-field-column PASS "orders gained channel"
else
  verdict new-field-column FAIL "no channel column"
fi

step "delete $source_name (Delete)"
kubectl -n "$JOB_NAMESPACE" get secret "$source_name-catalog-token" -o jsonpath='{.data.token}' | base64 -d >"$work/token"
kubectl -n "$JOB_NAMESPACE" delete lakesource "$source_name" --timeout=10m
admin_open
orders_table="$(curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/curlrc" \
  "https://localhost:$ADMIN_PORT/v1/colvenna/databases/$ods/tables/orders")"
# customers left the source earlier: its target goes with the source too, and so the database.
database="$(curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/curlrc" \
  "https://localhost:$ADMIN_PORT/v1/colvenna/databases/$ods")"
token="$(token_status "$work/token")"
admin_close
if [[ "$orders_table" == 404 && "$database" == 404 && "$token" == 401 ]] \
  && [[ -z "$(kubectl -n "$JOB_NAMESPACE" get flinkdeployment -l "colvenna-lake.io/source=$source_name" -o name)" ]]; then
  verdict mongo-deleted PASS "job gone, tables and the database dropped (404), token refused (401)"
else
  verdict mongo-deleted FAIL "orders=$orders_table database=$database token=$token"
fi
bash "$FIXTURES_DIR/mongo/dev-mongo.sh" purge "$mname"

kubectl -n "$JOB_NAMESPACE" get events -o json >"$evidence/events.json"
echo "verdicts:"
cat "$evidence/verdicts.tsv"
exit "$failed"
