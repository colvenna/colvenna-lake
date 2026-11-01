#!/usr/bin/env bash
# Backs up and restores colvenna-catalog's metadata, the colvenna_catalog database on the project RDS
# instance (the foundation stack's), and brings the catalog back in line with the table files
# (docs/manual/install.md, 维护与恢复).
#
# usage: restore-metadata.sh snapshot <snapshot-id>
#        restore-metadata.sh restore (--snapshot <snapshot-id> | --time <UTC time> | --latest) [--keep-instance] --yes <env> <evidence-dir>
#        restore-metadata.sh check <evidence-dir>
#        restore-metadata.sh reconcile [--unregister-missing] <evidence-dir>
#
#   snapshot   a manual RDS snapshot of the project database, waited for until available. RDS keeps
#              automated backups too (point-in-time restore, within the instance's retention).
#   restore    RDS restores the snapshot (of this environment's project instance only), or the point
#              in time, into a new, temporary instance of the same class, subnets, security groups and
#              parameter group, <foundation stack>-restore-<time>. Its colvenna_catalog database is dumped
#              and the tables fenced there are noted; then the catalog is stopped, a manual snapshot of
#              the current instance is taken (the way back, of exactly what is overwritten), the dump
#              replaces colvenna_catalog on the project instance, and the catalog starts again. The project
#              instance stays the stack's own, endpoint and all: nothing drifts from what
#              CloudFormation manages. The temporary instance is deleted unless --keep-instance (then
#              uninstall.sh deletes it). The environment's name must be given again after --yes. The
#              restore point goes to <evidence-dir>/restore-point.txt.
#   check      every table the catalog lists against its files (the admin consistency report), and
#              every table directory in the warehouse the catalog does not list, by whether its first
#              schema file is newer than the restore point: <evidence-dir>/check.tsv. Fails if any
#              table could not be checked (kind error).
#   reconcile  check (and stop if any table could not be checked), then: every listed table behind its
#              files is reconciled (the catalog takes the files' latest snapshots and branches: commits
#              after the restore point are kept; a fence is lifted only if the table was not fenced at
#              the restore point already); every unlisted table directory made after the restore point
#              is registered again under its directory's name (its database created first if the
#              catalog lost it too); a listed table whose directory is gone (dropped after the restore
#              point) is reported, and with --unregister-missing removed from the catalog. Then check
#              again.
# What the restore cannot bring back (what only colvenna_catalog held after the restore point: grants,
# principals and tokens, renames, audit records) is listed in the install guide.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/catalog-admin.sh
source "$(dirname "$0")/../lib/catalog-admin.sh"
# A command that fails inside $(...) fails the assignment that runs it: a listing that could not be
# read must never pass for an empty one.
shopt -s inherit_errexit
assert_account
ensure_kubeconfig
usage() { sed -n '/^# usage:/,/^#        restore-metadata.sh reconcile/p' "$0" | sed 's/^# //' >&2; exit 1; }
command="${1:-}"
[[ -n "$command" ]] || usage
shift
log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" | tee -a "$evidence/timeline.txt"; }
fail() { log "FAIL: $*"; exit 1; }

# The project instance: the one at the foundation stack's CatalogEndpoint.
endpoint="$(stack_output "$FOUNDATION_STACK" CatalogEndpoint)"
instance="$(aws rds describe-db-instances --output text \
  --query "DBInstances[?Endpoint.Address=='$endpoint'].DBInstanceIdentifier | [0]")"
if [[ -z "$instance" || "$instance" == None ]]; then
  echo "No RDS instance at $FOUNDATION_STACK's CatalogEndpoint $endpoint" >&2
  exit 1
fi
# wait_for <what> <seconds> <command...>: until the command prints available (or, for deleted, fails).
wait_for() {
  local what="$1" limit="$2" state=""
  shift 2
  for _ in $(seq 1 $((limit / 20))); do
    state="$("$@" 2>/dev/null || echo gone)"
    [[ "$state" == available || ( "$what" == deleted && "$state" == gone ) ]] && return 0
    sleep 20
  done
  echo "$what: still $state after ${limit}s" >&2
  return 1
}
snapshot_state() { aws rds describe-db-snapshots --db-snapshot-identifier "$1" --query 'DBSnapshots[0].Status' --output text; }
instance_state() { aws rds describe-db-instances --db-instance-identifier "$1" --query 'DBInstances[0].DBInstanceStatus' --output text; }

# take_snapshot <id>: a manual snapshot of the project instance, available. Fails at once if it
# cannot be made (called as a condition, the function runs without errexit).
take_snapshot() {
  aws rds create-db-snapshot --db-instance-identifier "$instance" --db-snapshot-identifier "$1" \
    --tags "Key=colvenna-lake:snapshot-of,Value=$instance" >/dev/null || return 1
  wait_for "snapshot $1" 3600 snapshot_state "$1"
}

if [[ "$command" == snapshot ]]; then
  id="${1:?usage: restore-metadata.sh snapshot <snapshot-id>}"
  evidence="$(mktemp -d)"
  take_snapshot "$id"
  aws rds describe-db-snapshots --db-snapshot-identifier "$id" --output text \
    --query 'DBSnapshots[0].[DBSnapshotIdentifier,SnapshotCreateTime,DBInstanceIdentifier]'
  rm -rf "$evidence"
  exit 0
fi

# --- the table files against the catalog ----------------------------------------------------------
bucket="$(stack_output "$FOUNDATION_STACK" WarehouseBucket)"
# paged <path> <field>: every name of a listing, one per line, page by page.
paged() {
  local token="" page
  while :; do
    page="$(admin_call GET "$1?maxResults=1000${token:+&pageToken=$token}")"
    python3 -c 'import json,sys; [print(n) for n in json.loads(sys.argv[1]).get(sys.argv[2]) or []]' "$page" "$2"
    token="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("nextPageToken") or "")' "$page")"
    [[ -n "$token" ]] || break
  done
}
# listed: "<database>\t<table>\t<path>" for every table the catalog lists, the path empty for
# objects without one (views). Needs admin_open. Any request that fails fails it.
listed() {
  local d t databases tables table path
  databases="$(paged /v1/colvenna/databases databases)"
  while IFS= read -r d; do
    [[ -n "$d" ]] || continue
    tables="$(paged "/v1/colvenna/databases/$d/tables" tables)"
    while IFS= read -r t; do
      [[ -n "$t" ]] || continue
      table="$(admin_call GET "/v1/colvenna/databases/$d/tables/$t")"
      path="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("path") or "")' "$table")"
      printf '%s\t%s\t%s\n' "$d" "$t" "$path"
    done <<<"$tables"
  done <<<"$databases"
}
# prefixes_of <prefix>: the warehouse bucket's prefixes one level below it, one per line.
prefixes_of() {
  local listing
  listing="$(aws s3api list-objects-v2 --bucket "$bucket" --prefix "$1" --delimiter / \
    --query 'CommonPrefixes[].Prefix' --output json)"
  python3 -c 'import json,sys; [print(p) for p in json.loads(sys.argv[1]) or []]' "$listing"
}
# directories: "<database>\t<directory>\t<path>\t<first schema file's time>" for every table
# directory (one with a schema file) under the warehouse's <database>.db/ prefixes.
directories() {
  local db_prefixes table_prefixes db_prefix table_prefix first db_dir table_dir
  db_prefixes="$(prefixes_of warehouse/)"
  while IFS= read -r db_prefix; do
    [[ "$db_prefix" == warehouse/*.db/ ]] || continue
    table_prefixes="$(prefixes_of "$db_prefix")"
    while IFS= read -r table_prefix; do
      [[ -n "$table_prefix" ]] || continue
      # shellcheck disable=SC2016  # JMESPath's backticks
      first="$(aws s3api list-objects-v2 --bucket "$bucket" --prefix "${table_prefix}schema/schema-" \
        --query 'sort_by(Contents || `[]`, &LastModified)[0].LastModified' --output text)"
      [[ "$first" != None ]] || continue
      db_dir="${db_prefix#warehouse/}"
      table_dir="${table_prefix#"$db_prefix"}"
      printf '%s\t%s\t%s\t%s\n' "${db_dir%.db/}" "${table_dir%/}" "s3://$bucket/${table_prefix%/}" "$first"
    done <<<"$table_prefixes"
  done <<<"$db_prefixes"
}
# check_tables: <evidence>/check.tsv, one "<kind>\t<database>.<table>\t<detail>" line per table:
#   consistent    listed, in line with its files
#   behind        listed, its files have more (commits after the restore point): reconcile it
#   missing       listed, its directory gone (dropped after the restore point)
#   inconsistent  listed, otherwise out of line with its files (the report says how)
#   other         listed, without a path: a view
#   error         listed, but its consistency report could not be read: nothing is known of it
#   new           a directory the catalog does not list, made after the restore point: register it
#   unlisted      a directory the catalog does not list, made before the restore point (unregistered
#                 or left behind on purpose): left alone
# Listing the catalog or the warehouse failing fails it: a table is missing only when the bucket
# answered that its schema files are gone.
check_tables() {
  local out="$evidence/check.tsv" d t path report dir first count
  : >"$out"
  listed >"$evidence/listed.tsv"
  directories >"$evidence/directories.tsv"
  while IFS=$'\t' read -r d t path; do
    if [[ -z "$path" ]]; then
      printf 'other\t%s.%s\tno path\n' "$d" "$t" >>"$out"
      continue
    fi
    # Only a table in the warehouse bucket counts as dropped when its schema files are gone; the
    # consistency report says what else is wrong with any table.
    if [[ "$path" == "s3://$bucket/"* ]]; then
      # shellcheck disable=SC2016  # JMESPath's backticks
      count="$(aws s3api list-objects-v2 --bucket "$bucket" --prefix "${path#s3://"$bucket"/}/schema/" \
        --max-items 1 --query 'length(Contents || `[]`)' --output text)"
      if [[ "$count" == 0 ]]; then
        printf 'missing\t%s.%s\t%s\n' "$d" "$t" "$path" >>"$out"
        continue
      fi
    fi
    if ! report="$(admin_call GET "/colvenna/admin/v1/consistency/$d/$t" 2>&1)"; then
      printf 'error\t%s.%s\tconsistency report: %s\n' "$d" "$t" "$(tr '\n\t' '  ' <<<"$report" | cut -c1-300)" >>"$out"
      continue
    fi
    # Behind: every problem one that reconcile settles from the files (snapshots past the pointer,
    # a newer schema, the fence a write past them raised after the restore); anything else, a fence
    # the table had at the restore point (or any fence, without restore-fenced.txt) among them, is
    # left to the administrator.
    python3 -c '
import json, re, sys
name, report, fenced_file = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    problems = json.loads(report)["problems"] or []
except (ValueError, KeyError, TypeError):
    print("error\t%s\tconsistency report: %s" % (name, report.replace("\n", " ")[:300])); sys.exit()
try:
    fenced_before = name in open(fenced_file).read().splitlines()
except OSError:
    fenced_before = True
settled = re.compile(r"exists beyond the published pointer|^catalog serves schema \d+ but")
def settles(p):
    return bool(settled.search(p)) or (p.startswith("table is fenced") and not fenced_before)
kind = "consistent" if not problems else "behind" if all(settles(p) for p in problems) else "inconsistent"
print("%s\t%s\t%s" % (kind, name, "; ".join(problems).replace("\n", " ")))' "$d.$t" "$report" \
      "$evidence/restore-fenced.txt" >>"$out"
  done <"$evidence/listed.tsv"
  while IFS=$'\t' read -r d dir path first; do
    grep -qxF "$path" <<<"$(cut -f3 "$evidence/listed.tsv")" && continue
    if [[ -n "$point" ]] && [[ "$(date -u -d "$first" +%s)" -gt "$(date -u -d "$point" +%s)" ]]; then
      printf 'new\t%s.%s\t%s first schema %s\n' "$d" "$dir" "$path" "$first" >>"$out"
    else
      printf 'unlisted\t%s.%s\t%s first schema %s\n' "$d" "$dir" "$path" "$first" >>"$out"
    fi
  done <"$evidence/directories.tsv"
  log "check: $(cut -f1 "$out" | sort | uniq -c | awk '{printf "%s %s, ", $2, $1}' | sed 's/, $//')"
}
# unchecked: how many tables check_tables could not check.
unchecked() { grep -c '^error' "$evidence/check.tsv" || true; }

if [[ "$command" == check || "$command" == reconcile ]]; then
  unregister=false
  if [[ "${1:-}" == --unregister-missing && "$command" == reconcile ]]; then
    unregister=true
    shift
  fi
  evidence="${1:?usage: restore-metadata.sh $command [--unregister-missing] <evidence-dir>}"
  mkdir -p "$evidence"
  point="${RESTORE_POINT:-$(cat "$evidence/restore-point.txt" 2>/dev/null || true)}"
  [[ -n "$point" ]] || echo "No restore point (<evidence-dir>/restore-point.txt or RESTORE_POINT):" \
    "every unlisted directory counts as made before it" >&2
  trap admin_close EXIT
  admin_open
  check_tables
  (($(unchecked) == 0)) || fail "$(unchecked) tables could not be checked (error in check.tsv); nothing changed"
  [[ "$command" == check ]] && exit 0
  cp "$evidence/check.tsv" "$evidence/check-before-reconcile.tsv"
  failures=0
  # A failed step is logged and counted, and the next table is taken on.
  while IFS=$'\t' read -r kind name detail; do
    # Database names hold no dot (the catalog refuses them): the first dot ends it.
    d="${name%%.*}" t="${name#*.}"
    case "$kind" in
      behind)
        if ! admin_call POST "/colvenna/admin/v1/tables/$d/$t/reconcile" >"$evidence/reconcile-$d.$t.json"; then
          log "FAILED to reconcile $name: $(cat "$evidence/reconcile-$d.$t.json")"
          failures=$((failures + 1))
          continue
        fi
        log "reconciled $name: $(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("branches"))' "$evidence/reconcile-$d.$t.json")" ;;
      new)
        if [[ "$t" =~ -[0-9a-f]{8}$ ]]; then
          # A suffixed sibling path (a renamed table, or files in the way, when it was made): its
          # name is not the directory's. Left to the administrator.
          log "not registered: ${detail%% *} may belong to another name than $t; register it by hand"
          continue
        fi
        databases="$(paged /v1/colvenna/databases databases)"
        if ! grep -qxF "$d" <<<"$databases"; then
          if ! admin_call POST /v1/colvenna/databases "{\"name\":\"$d\",\"options\":{}}" >/dev/null; then
            log "FAILED to create database $d for $name"
            failures=$((failures + 1))
            continue
          fi
          log "database $d recreated (without the options it had)"
        fi
        if ! admin_call POST "/v1/colvenna/databases/$d/register" \
          "{\"identifier\":{\"database\":\"$d\",\"object\":\"$t\"},\"path\":\"${detail%% *}\"}" >/dev/null; then
          log "FAILED to register $name at ${detail%% *}"
          failures=$((failures + 1))
          continue
        fi
        log "registered $name at ${detail%% *}" ;;
      missing)
        if $unregister; then
          if ! admin_call POST "/colvenna/admin/v1/tables/$d/$t/unregister" >/dev/null; then
            log "FAILED to unregister $name"
            failures=$((failures + 1))
            continue
          fi
          log "unregistered $name: its directory $detail is gone"
        else
          log "listed, directory gone: $name ($detail); remove it with --unregister-missing"
        fi ;;
      inconsistent) log "left as it is: $name: $detail" ;;
    esac
  done <"$evidence/check-before-reconcile.tsv"
  check_tables
  ((failures == 0)) || fail "$failures steps failed; see timeline.txt"
  (($(unchecked) == 0)) || fail "$(unchecked) tables could not be checked after reconcile; see check.tsv"
  exit 0
fi

# --- restore ---------------------------------------------------------------------------------------
[[ "$command" == restore ]] || usage
source_args=() keep=false confirmed=false
while [[ $# -gt 1 ]]; do
  case "$1" in
    --snapshot) source_args=(snapshot "${2:?--snapshot <snapshot-id>}"); shift ;;
    --time) source_args=(time "${2:?--time <UTC time>}"); shift ;;
    --latest) source_args=(latest) ;;
    --keep-instance) keep=true ;;
    --yes) [[ "${2:-}" == "$COLVENNA_ENV" ]] && confirmed=true; shift ;;
    *) usage ;;
  esac
  shift
done
evidence="${1:-}"
[[ -n "$evidence" && ${#source_args[@]} -gt 0 ]] || usage
if ! $confirmed; then
  echo "restore replaces $COLVENNA_ENV's colvenna-catalog metadata: confirm with --yes $COLVENNA_ENV" >&2
  exit 1
fi
if [[ "${source_args[0]}" == snapshot ]]; then
  # Only this environment's own metadata: another instance's snapshot names other table paths.
  of="$(aws rds describe-db-snapshots --db-snapshot-identifier "${source_args[1]}" \
    --query 'DBSnapshots[0].DBInstanceIdentifier' --output text)"
  if [[ "$of" != "$instance" ]]; then
    echo "Snapshot ${source_args[1]} is of $of, not of $COLVENNA_ENV's project database $instance" >&2
    exit 1
  fi
fi
mkdir -p "$evidence"
stamp="$(date -u +%Y%m%d%H%M%S)"
temporary="$(tr '[:upper:]' '[:lower:]' <<<"$FOUNDATION_STACK")-restore-$stamp"
before="$instance-before-restore-$stamp"
tools_pod=colvenna-catalog-restore-tools
replicas="$(kubectl -n "$CATALOG_NAMESPACE" get deploy colvenna-catalog -o jsonpath='{.spec.replicas}')"
# shellcheck disable=SC2317  # invoked through the EXIT trap
cleanup() {
  # Never leave the catalog stopped; the temporary instance goes unless kept.
  kubectl -n "$CATALOG_NAMESPACE" scale deploy/colvenna-catalog --replicas="$replicas" >/dev/null 2>&1 || true
  kubectl -n "$CATALOG_NAMESPACE" delete pod "$tools_pod" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  if ! $keep && aws rds describe-db-instances --db-instance-identifier "$temporary" >/dev/null 2>&1; then
    aws rds delete-db-instance --db-instance-identifier "$temporary" --skip-final-snapshot \
      --delete-automated-backups >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

aws rds describe-db-instances --db-instance-identifier "$instance" --output json \
  --query 'DBInstances[0].{class:DBInstanceClass,subnets:DBSubnetGroup.DBSubnetGroupName,groups:VpcSecurityGroups[].VpcSecurityGroupId,parameters:DBParameterGroups[0].DBParameterGroupName,ca:CACertificateIdentifier,latest:LatestRestorableTime,retention:BackupRetentionPeriod}' \
  >"$evidence/instance.json"
field() { python3 -c 'import json,sys; v=json.load(open(sys.argv[1]))[sys.argv[2]]; print(" ".join(v) if isinstance(v, list) else v)' "$evidence/instance.json" "$1"; }
read -r -a groups <<<"$(field groups)"
like=(--db-instance-class "$(field class)" --db-subnet-group-name "$(field subnets)"
  --vpc-security-group-ids "${groups[@]}" --db-parameter-group-name "$(field parameters)"
  --ca-certificate-identifier "$(field ca)" --no-publicly-accessible --no-multi-az
  --no-deletion-protection --tags "Key=colvenna-lake:restore-of,Value=$instance")
log "restore of $instance (colvenna_catalog of $COLVENNA_ENV) from ${source_args[*]} into $temporary"
case "${source_args[0]}" in
  snapshot)
    point="$(aws rds describe-db-snapshots --db-snapshot-identifier "${source_args[1]}" \
      --query 'DBSnapshots[0].SnapshotCreateTime' --output text)"
    aws rds restore-db-instance-from-db-snapshot --db-instance-identifier "$temporary" \
      --db-snapshot-identifier "${source_args[1]}" "${like[@]}" >/dev/null ;;
  time)
    point="$(date -u -d "${source_args[1]}" +%Y-%m-%dT%H:%M:%SZ)"
    aws rds restore-db-instance-to-point-in-time --source-db-instance-identifier "$instance" \
      --target-db-instance-identifier "$temporary" --restore-time "$point" "${like[@]}" >/dev/null ;;
  latest)
    point="$(field latest)"
    aws rds restore-db-instance-to-point-in-time --source-db-instance-identifier "$instance" \
      --target-db-instance-identifier "$temporary" --use-latest-restorable-time "${like[@]}" >/dev/null ;;
esac
echo "$point" >"$evidence/restore-point.txt"
log "restore point $point; waiting for $temporary"
wait_for "instance $temporary" 5400 instance_state "$temporary" || fail "$temporary never became available"
restored="$(aws rds describe-db-instances --db-instance-identifier "$temporary" \
  --query 'DBInstances[0].Endpoint.Address' --output text)"
log "$temporary available at $restored"

cat >"$evidence/tools.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $tools_pod}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 70, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: psql
      image: $POSTGRES_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["sleep", "7200"]
      env:
        - {name: PGHOST, value: "$endpoint"}
        - {name: RESTORED_HOST, value: "$restored"}
        - {name: PGDATABASE, value: colvenna_catalog}
        - {name: PGSSLMODE, value: verify-full}
        - {name: PGSSLROOTCERT, value: /ca/ca.pem}
        - name: PGUSER
          valueFrom: {secretKeyRef: {name: colvenna-catalog-db, key: username}}
        - name: PGPASSWORD
          valueFrom: {secretKeyRef: {name: colvenna-catalog-db, key: password}}
      volumeMounts:
        - {name: ca, mountPath: /ca}
        - {name: dump, mountPath: /dump}
  volumes:
    - name: ca
      configMap: {name: colvenna-catalog-db-ca}
    - name: dump
      emptyDir: {}
YAML
kubectl -n "$CATALOG_NAMESPACE" delete pod "$tools_pod" --ignore-not-found --wait=true >/dev/null
kubectl -n "$CATALOG_NAMESPACE" apply -f "$evidence/tools.yaml" >/dev/null
kubectl -n "$CATALOG_NAMESPACE" wait "pod/$tools_pod" --for=condition=Ready --timeout=5m >/dev/null
pg() { kubectl -n "$CATALOG_NAMESPACE" exec "$tools_pod" -- "$@"; }
# The catalog's own login: colvenna_catalog owns the database, on the restored instance as at the point.
# shellcheck disable=SC2016  # expanded by the pod's shell
pg sh -c 'pg_dump -h "$RESTORED_HOST" -Fc -f /dump/restored.dump && pg_restore -l /dump/restored.dump | grep -c "TABLE DATA"' \
  >"$evidence/dump-tables.txt"
log "dumped colvenna_catalog of $temporary: $(cat "$evidence/dump-tables.txt") tables with data"
# The tables fenced at the restore point: a fence from before it (a writer outside the catalog) is
# the administrator's to lift, unlike one a write raises after the restore (check, reconcile).
pg psql -h "$restored" -At -c "SELECT database_name || '.' || name FROM catalog_tables WHERE state = 'FENCED' ORDER BY 1" \
  >"$evidence/restore-fenced.txt"
log "fenced at the restore point: $(wc -l <"$evidence/restore-fenced.txt") tables"

# The catalog down from here until it serves the restored metadata: catalog-stopped-seconds.txt.
stopped=$SECONDS
log "stop the catalog ($replicas replicas)"
kubectl -n "$CATALOG_NAMESPACE" scale deploy/colvenna-catalog --replicas=0 >/dev/null
for _ in $(seq 1 60); do
  running="$(kubectl -n "$CATALOG_NAMESPACE" get pods -l app.kubernetes.io/name=colvenna-catalog "$LIVE_PODS" -o name)"
  [[ -z "$running" ]] && break
  sleep 5
done
# No replica may still commit into what is about to be snapshotted and overwritten.
[[ -z "$running" ]] || fail "catalog pods still there after 5 minutes: ${running//$'\n'/ }; nothing restored"
# The way back, should the restored metadata be the wrong one: exactly what is overwritten.
log "snapshot $before of $instance, the catalog stopped"
take_snapshot "$before" || fail "no snapshot $before; nothing restored"
echo "$before" >"$evidence/before-snapshot.txt"
# One transaction: a failure leaves colvenna_catalog as it was.
pg pg_restore --clean --if-exists --no-owner --single-transaction -d colvenna_catalog /dump/restored.dump \
  >"$evidence/pg-restore.txt" 2>&1 || fail "pg_restore failed; colvenna_catalog is unchanged: $(tail -3 "$evidence/pg-restore.txt")"
log "colvenna_catalog of $instance replaced by the restored one"
kubectl -n "$CATALOG_NAMESPACE" scale deploy/colvenna-catalog --replicas="$replicas" >/dev/null
kubectl -n "$CATALOG_NAMESPACE" rollout status deploy/colvenna-catalog --timeout=10m >/dev/null
down=$((SECONDS - stopped))
echo "$down" >"$evidence/catalog-stopped-seconds.txt"
log "catalog serving again after $down s stopped: $(kubectl -n "$CATALOG_NAMESPACE" get pods \
  -l app.kubernetes.io/name=colvenna-catalog "$LIVE_PODS" -o jsonpath='{range .items[*]}{.metadata.name} {.spec.nodeName};{end}')"
if $keep; then
  log "$temporary kept (--keep-instance): it bills until deleted; uninstall.sh deletes it"
else
  aws rds delete-db-instance --db-instance-identifier "$temporary" --skip-final-snapshot \
    --delete-automated-backups >/dev/null
  wait_for deleted 3600 instance_state "$temporary" || fail "$temporary not deleted"
  log "$temporary deleted"
fi
log "restored; next: restore-metadata.sh check $evidence, then reconcile; delete snapshot $before once satisfied"
