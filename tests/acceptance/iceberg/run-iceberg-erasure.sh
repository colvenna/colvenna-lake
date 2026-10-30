#!/usr/bin/env bash
# Acceptance on EKS: a subject's erasure from a table whose writers keep Iceberg metadata beside it
# (LLD "开放层", the erasure's compaction). Writes PASS/FAIL lines to <evidence>/verdicts.tsv and
# fails if any check fails.
#
# - A MySQL source writes orders, personal data keyed by customer_id, with Iceberg metadata
#   (metadata.iceberg.storage: table-location). Personal data is never published: the metadata is
#   in the table's directory only, its manifests naming the files' minimum and maximum values.
# - Customer 42's erasure: the erasure counts the subject in the Iceberg manifests too, its full
#   compaction rewrites Paimon's manifests and has the writers' callback write the Iceberg metadata
#   anew, and the files are reported clean only once no Iceberg manifest names the subject.
# - Read independently from S3: every Iceberg manifest left in the table's directory was written by
#   that compaction or after it, and the table still has its Iceberg metadata.
#
# usage: run-iceberg-erasure.sh <run-id> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-iceberg-erasure.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-iceberg-erasure.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
cdc="$REPO_DIR/tests/acceptance/cdc"
mkdir -p "$evidence"/{status,sql,erasure,s3}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
for image in operator mysql sql; do
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
db="icee_$run" ods="ods_icee_$run" source_name="ie-$run" erasure="erase-$run"
small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
work="$(mktemp -d)"
chmod 700 "$work"
trap 'admin_close; rm -rf "$work"' EXIT

# status_of <kind> <name> <python expression over s, its status>: the value, empty when none.
status_of() {
  kubectl -n "$JOB_NAMESPACE" get "$1" "$2" -o json 2>/dev/null \
    | python3 -c 'import json,sys; s=json.load(sys.stdin).get("status") or {}
try: print(eval(sys.argv[1]))
except (KeyError, IndexError, TypeError, StopIteration): print("")' "$3" || true
}

# files_copy <python expression over f, the erasure's Files copy of the orders>
files_copy() {
  status_of lakeerasure "$erasure" \
    "(lambda f: $1)(next(c for c in s.get('copies', []) if c['kind'] == 'Files' and c['name'] == '$ods.orders'))"
}

# manifests <out>: the Iceberg manifests (and manifest lists) in the table's metadata directory, as
# S3 lists them: "<LastModified>\t<key>".
manifests() {
  local query="Contents[?ends_with(Key, \`.avro\`)].[LastModified, Key]"
  aws s3api list-objects-v2 --bucket "$bucket" --prefix "$prefix" --query "$query" --output text >"$1"
}

# ------------------------------------------------------------------------------- the source
step "mysql fixture $db: 40 orders, three of them customer 42's"
{
  python3 "$cdc/mysql_contract.py" reset "$db"
  python3 "$cdc/mysql_contract.py" setup "$db"
  cat <<SQL
USE $db;
INSERT INTO orders (id, customer_id, amount, state, note, version_no)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 40)
  SELECT i, IF(i IN (5, 15, 25), 42, 100 + i), ROUND(i * 1.25, 2), 'created', NULL, 1 FROM n;
SQL
} >"$evidence/sql/setup.sql"
bash "$REPO_DIR/tests/fixtures/sources/sources.sh" users >/dev/null
bash "$REPO_DIR/tests/fixtures/sources/sources.sh" mysql "$evidence/sql/setup.sql" >/dev/null
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
  target:
    database: $ods
    tableOptions: {metadata.iceberg.storage: table-location}
  governance:
    owner: shop-team
    level: L3
    categories: [personal]
    subjectKeys: {orders: [customer_id]}
    retention: 24h
  job: $small
  deletionPolicy: Delete
YAML
kubectl apply -f "$evidence/source.yaml"
end=$((SECONDS + 900)) started=$SECONDS ready=""
while ((SECONDS < end)); do
  ready="$(status_of lakesource "$source_name" "[t.get('state') for t in s.get('tables', []) if t.get('name') == 'orders']")"
  [[ "$ready" == "['Ready']" ]] && break
  sleep 15
done
if [[ "$ready" == "['Ready']" ]]; then
  verdict source-ready PASS "$((SECONDS - started))s"
else
  verdict source-ready FAIL "orders: $ready"
fi

step "the writers keep Iceberg metadata beside the table"
admin_open
path="$(admin_call GET "/v1/colvenna/databases/$ods/tables/orders" | python3 -c 'import json,sys; print(json.load(sys.stdin)["path"])')"
admin_close
bucket="$(cut -d/ -f3 <<<"$path")"
prefix="$(cut -d/ -f4- <<<"$path")/metadata/"
manifests "$evidence/s3/before.tsv"
aws s3api list-objects-v2 --bucket "$bucket" --prefix "$prefix" --query 'Contents[].Key' --output text \
  | tr '\t' '\n' | grep -c '\.metadata\.json$' >"$work/versions" || true
if [[ -s "$evidence/s3/before.tsv" && "$(cat "$work/versions")" -gt 0 ]]; then
  verdict iceberg-metadata-written PASS "$(wc -l <"$evidence/s3/before.tsv") manifests and lists, $(cat "$work/versions") metadata files under $prefix"
else
  verdict iceberg-metadata-written FAIL "nothing under s3://$bucket/$prefix"
fi

# ------------------------------------------------------------------------------- the erasure
step "customer 42's erasure; the source's owner deletes it"
printf '42\n' >"$work/subject"
kubectl -n "$JOB_NAMESPACE" create secret generic "$erasure" --from-file=values="$work/subject" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
rm -f "$work/subject"
cat >"$evidence/erasure/erasure.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeErasure
metadata: {name: $erasure, namespace: $JOB_NAMESPACE}
spec:
  subjectSecret: {name: $erasure}
YAML
kubectl apply -f "$evidence/erasure/erasure.yaml"
cat >"$evidence/sql/delete-subject.sql" <<SQL
DELETE FROM $db.orders WHERE customer_id = 42;
SQL
bash "$REPO_DIR/tests/fixtures/sources/sources.sh" mysql "$evidence/sql/delete-subject.sql" >/dev/null

# The files' progress, every 20 s: whether the erasure saw the subject in the Iceberg metadata, and
# when they are clean.
end=$((SECONDS + 3600)) started=$SECONDS seen="" clean=""
: >"$evidence/erasure/files.log"
while ((SECONDS < end)); do
  message="$(files_copy "f.get('message') or ''")"
  echo "$(date -u +%FT%TZ)	$message" >>"$evidence/erasure/files.log"
  [[ "$message" == *"in its Iceberg metadata"* ]] && seen="$message"
  compacted="$(files_copy "f.get('compactedSnapshot') or ''")"
  if [[ -n "$compacted" && "$message" != *compacting* ]]; then
    clean="snapshot $compacted: $message"
    break
  fi
  sleep 20
done
kubectl -n "$JOB_NAMESPACE" get lakeerasure "$erasure" -o json >"$evidence/erasure/latest.json"
if [[ -n "$seen" ]]; then
  verdict erasure-counts-the-iceberg-manifests PASS "$(cut -c1-240 <<<"$seen")"
else
  verdict erasure-counts-the-iceberg-manifests FAIL "the files' messages never named the Iceberg metadata (files.log)"
fi
if [[ -n "$clean" ]]; then
  verdict files-clean-iceberg-metadata-included PASS "$((SECONDS - started))s, $(cut -c1-220 <<<"$clean")"
else
  verdict files-clean-iceberg-metadata-included FAIL "not within 3600s: $(files_copy "f.get('message')")"
fi

# The erasure's compaction of orders: manifests rewritten, the Iceberg metadata written anew.
kubectl -n "$JOB_NAMESPACE" get laketask -l "colvenna-lake.io/erasure=$erasure" -o json >"$evidence/erasure/tasks.json"
python3 - "$evidence/erasure/tasks.json" "$ods" >"$work/compaction.txt" <<'PY' || true
import json, sys
tasks = [t for t in json.load(open(sys.argv[1]))["items"]
         if t["spec"]["type"] == "compact" and t["spec"]["table"]["database"] == sys.argv[2]
         and (t.get("status") or {}).get("phase") == "Succeeded"]
tasks.sort(key=lambda t: t["status"].get("startedAt") or "")
if tasks:
    t = tasks[-1]
    print(t["status"].get("startedAt") or "", (t["status"].get("result") or {}).get("detail") or "", sep="\t")
PY
started_at="$(cut -f1 "$work/compaction.txt")"
detail="$(cut -f2 "$work/compaction.txt")"
if [[ "$detail" == *"manifests compacted; Iceberg metadata written anew at snapshot"* ]]; then
  verdict erasure-compaction-rewrites-the-iceberg-metadata PASS "$detail"
else
  verdict erasure-compaction-rewrites-the-iceberg-metadata FAIL "last succeeded compaction: ${detail:-none}"
fi

# Read from S3 by the run's own identity: no manifest from before that compaction is left (theirs
# named the subject's values), and the table still has its Iceberg metadata.
manifests "$evidence/s3/after.tsv"
if python3 - "$evidence/s3/after.tsv" "$started_at" >"$work/after.txt" <<'PY'; then
import datetime, sys
rows = [l.rstrip("\n").split("\t") for l in open(sys.argv[1]) if l.strip()]
start = datetime.datetime.fromisoformat(sys.argv[2].replace("Z", "+00:00"))
older = [k for m, k in rows if datetime.datetime.fromisoformat(m.replace("Z", "+00:00")) < start]
print(f"{len(rows)} manifests and lists, {len(older)} written before the compaction at {sys.argv[2]}")
sys.exit(0 if rows and not older else 1)
PY
  verdict no-iceberg-manifest-from-before-the-erasure PASS "$(cat "$work/after.txt")"
else
  verdict no-iceberg-manifest-from-before-the-erasure FAIL "$(cat "$work/after.txt" 2>/dev/null)"
fi

end=$((SECONDS + 1800)) phase=""
while ((SECONDS < end)); do
  phase="$(status_of lakeerasure "$erasure" "s.get('phase')")"
  [[ "$phase" == LogicallyErased || "$phase" == PhysicallyErased || "$phase" == SlaBreach || "$phase" == Blocked ]] && break
  sleep 20
done
kubectl -n "$JOB_NAMESPACE" get lakeerasure "$erasure" -o json >"$evidence/erasure/logical.json"
if [[ "$phase" == LogicallyErased ]]; then
  verdict erasure-logical PASS "$(status_of lakeerasure "$erasure" "'accepted ' + s['acceptedAt'] + ', logically erased ' + s['logicallyErasedAt']")"
else
  verdict erasure-logical FAIL "phase $phase: $(status_of lakeerasure "$erasure" "s.get('message')")"
fi
python3 - "$evidence/erasure/logical.json" >"$evidence/erasure/copies.txt" <<'PY' || true
import json, sys
for c in json.load(open(sys.argv[1]))["status"]["copies"]:
    print(f"{c['kind']:<15} {c['name']:<40} {c['state']:<16} {c.get('evidence') or c.get('message')}")
PY
cat "$evidence/erasure/copies.txt"

# --------------------------------------------------------------------------------- clean up
step "clean up"
kubectl -n "$JOB_NAMESPACE" delete lakeerasure "$erasure" --wait=true --timeout=10m >/dev/null || true
kubectl -n "$JOB_NAMESPACE" delete secret "$erasure" --ignore-not-found >/dev/null
kubectl -n "$JOB_NAMESPACE" delete lakesource "$source_name" --wait=true --timeout=10m >/dev/null || true
python3 "$cdc/mysql_contract.py" reset "$db" >"$evidence/sql/reset.sql"
bash "$REPO_DIR/tests/fixtures/sources/sources.sh" mysql "$evidence/sql/reset.sql" >/dev/null || true

echo
cut -f3 "$evidence/verdicts.tsv" | sort | uniq -c
exit "$failed"
