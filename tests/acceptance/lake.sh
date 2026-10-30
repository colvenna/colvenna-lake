#!/usr/bin/env bash
# Reads lake tables for evidence, as an identity of its own (verifier, READ on the catalog),
# from a pod running the colvenna-jobs image of this SHA.
#   table <out-file> <db.table>...       LakeDump table: schema, earliest and latest snapshot, tags
#   rows <out-file> <db.table>...        LakeDump rows: the latest snapshot's rows, JSON lines
#   changelog <out-file> <db.table>...   LakeDump changelog: every change from the earliest snapshot
#                                        (each of the three ends with LakeDump's END line, checked
#                                        here: an incomplete dump fails, its out-file left empty)
#   consumers <out-file> <db.table>      the table's Paimon consumers, JSON lines
#   drop-consumer <db.table> <id>        deletes a consumer, as expiry would (WRITE on that table,
#                                        granted for the call and revoked after it)
#   query <out-file> <sql-file> <db.table>...
#                                        one SELECT (Flink batch, colvenna-jobs SqlQuery) reading these
#                                        tables, a ROW line per row
# shellcheck source-path=SCRIPTDIR
# shellcheck source=common.sh
source "$(dirname "$0")/common.sh"
# shellcheck source=../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
usage="usage: lake.sh table|rows|changelog <out-file> <db.table>... | consumers <out-file> <db.table> | drop-consumer <db.table> <id> | query <out-file> <sql-file> <db.table>..."
mode="${1:?$usage}"
case "$mode" in
  table | rows | changelog | consumers)
    out="${2:?$usage}"
    shift 2
    (($# > 0)) || { echo "$usage" >&2; exit 1; }
    ;;
  query)
    out="${2:?$usage}"
    sql="${3:?$usage}"
    shift 3
    (($# > 0)) || { echo "$usage" >&2; exit 1; }
    ;;
  drop-consumer)
    out=/dev/stdout
    consumer="${3:?$usage}"
    [[ "$consumer" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "Not a consumer id: $consumer" >&2; exit 1; }
    set -- "${2:?$usage}"
    ;;
  *) echo "$usage" >&2; exit 1 ;;
esac
# The out-file stays empty until the output is in and whole: a failed run leaves no stale or partial
# dump behind.
if [[ "$mode" != drop-consumer ]]; then
  : >"$out"
  rm -f "$out.incomplete"
fi
image="$(image_ref mysql)"
# A query runs Flink's planner: the sql image.
[[ "$mode" != query ]] || image="$(image_ref sql)"
pod="lake-$RANDOM"
work="$(mktemp -d)"
chmod 700 "$work"
# revoke_write <db> <table>: drops verifier's WRITE grant on that table. Needs admin_open.
revoke_write() {
  local id
  for id in $(admin_call GET "/colvenna/admin/v1/grants?principal=verifier" \
    | python3 -c 'import json,sys; print(" ".join(str(g["id"]) for g in json.load(sys.stdin)["grants"] if g["scope"] == "TABLE" and g.get("database") == sys.argv[1] and g.get("object") == sys.argv[2] and g["privilege"] == "WRITE"))' "$1" "$2"); do
    admin_call DELETE "/colvenna/admin/v1/grants/$id" >/dev/null
  done
}
# shellcheck disable=SC2317  # invoked through the EXIT trap
cleanup() {
  # The WRITE grant of drop-consumer goes whether or not the pod succeeded.
  if [[ "$mode" == drop-consumer && -n "${ADMIN_FORWARD_PID:-}" ]]; then
    revoke_write "${1%%.*}" "${1#*.}" || echo "Revoke verifier's WRITE on $1 by hand" >&2
  fi
  admin_close
  kubectl -n "$JOB_NAMESPACE" delete pod "$pod" --ignore-not-found >/dev/null 2>&1 || true
  kubectl -n "$JOB_NAMESPACE" delete configmap "$pod" --ignore-not-found >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap 'cleanup "$@"' EXIT

tables=""
for table in "$@"; do
  [[ "$table" =~ ^[a-z0-9_]+\.[a-z0-9_]+$ ]] || { echo "Not a table: $table" >&2; exit 1; }
  tables+=" $table"
done
# Read access per database, not catalog-wide: a catalog grant would make every source's ingest
# database readable beyond its source, which stops new tables from being enrolled there.
admin_open
created="$(kubectl -n "$JOB_NAMESPACE" get secret verifier-token \
  -o jsonpath='{.metadata.creationTimestamp}' 2>/dev/null || true)"
if [[ -z "$created" ]] || (( $(date +%s) - $(date -d "$created" +%s) > 20 * 86400 )); then
  ensure_principal_token verifier "$JOB_NAMESPACE" verifier-token P30D
fi
for id in $(admin_call GET "/colvenna/admin/v1/grants?principal=verifier" \
  | python3 -c 'import json,sys; print(" ".join(str(g["id"]) for g in json.load(sys.stdin)["grants"] if g["scope"] == "CATALOG"))'); do
  admin_call DELETE "/colvenna/admin/v1/grants/$id" >/dev/null
done
for database in $(printf '%s\n' "$@" | cut -d. -f1 | sort -u); do
  GRANT_PURPOSE="acceptance evidence reads" GRANT_APPROVER=requester GRANT_APPROVAL=issue-24 \
    GRANT_DAYS=30 ensure_grant verifier DATABASE "$database" - READ
done
case "$mode" in
  table | rows | changelog) main="com.colvenna.lake.jobs.tools.LakeDump $mode$tables" ;;
  consumers) main="com.colvenna.lake.jobs.tools.Consumers list$tables" ;;
  query)
    kubectl -n "$JOB_NAMESPACE" create configmap "$pod" --from-file=query.sql="$sql" \
      --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    main="com.colvenna.lake.jobs.tools.SqlQuery /query/query.sql"
    ;;
  drop-consumer)
    GRANT_PURPOSE="acceptance evidence: an expired consumer" GRANT_APPROVER=requester \
      GRANT_APPROVAL=issue-26 GRANT_DAYS=1 ensure_grant verifier TABLE "${1%%.*}" "${1#*.}" WRITE
    main="com.colvenna.lake.jobs.tools.Consumers drop$tables $consumer"
    ;;
esac
# drop-consumer keeps the forward open: cleanup revokes the WRITE grant through it.
[[ "$mode" == drop-consumer ]] || admin_close

cat >"$work/pod.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $pod}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 9999, runAsGroup: 9999, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: dump
      image: $image
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      env:
        - {name: COLVENNA_CATALOG_URI, value: "https://colvenna-catalog.$CATALOG_NAMESPACE.svc:8181"}
        - {name: COLVENNA_CATALOG_TOKEN_FILE, value: /token/token}
      command: ["sh", "-ec"]
      args:
        - |
          cp "\$JAVA_HOME/lib/security/cacerts" /tmp/cacerts && chmod 600 /tmp/cacerts
          keytool -importcert -noprompt -alias colvenna-catalog -file /ca/ca.crt -keystore /tmp/cacerts -storepass changeit >/dev/null
          java -Xmx2g -Djavax.net.ssl.trustStore=/tmp/cacerts -Djavax.net.ssl.trustStorePassword=changeit \
            -Dlog4j2.level=WARN -cp "/opt/flink/lib/*:/opt/flink/usrlib/*" $main >/out/out 2>/tmp/stderr || { tail -50 /tmp/stderr >&2; exit 1; }
          $POD_OUT_DONE
      resources: {requests: {cpu: 500m, memory: 3Gi}, limits: {memory: 3Gi}}
      volumeMounts:
        - {name: token, mountPath: /token, readOnly: true}
        - {name: ca, mountPath: /ca, readOnly: true}
        - {name: query, mountPath: /query, readOnly: true}
        - {name: out, mountPath: /out}
  volumes:
    - {name: token, secret: {secretName: verifier-token}}
    - {name: ca, configMap: {name: colvenna-catalog-ca}}
    - {name: query, configMap: {name: $pod, optional: true}}
    - {name: out, emptyDir: {}}
YAML
# Copied from a file in the pod, not read from its log (#244). No lines is an answer too: a table
# without consumers. A failed pod still fails the pipeline.
run_pod_out "$JOB_NAMESPACE" "$pod" "$work/pod.yaml" | { grep '^{' || true; } >"$work/out"
case "$mode" in
  table | rows | changelog)
    # LakeDump's first line is the first table's TABLE line and its last the END line counting
    # what it wrote: anything else is a dump cut short, not data.
    if ! python3 "$ACCEPTANCE_DIR/lake_dump.py" "$work/out" "$mode" "$@" 2>"$work/problems"; then
      cp "$work/out" "$out.incomplete"
      echo "Incomplete LakeDump $mode output for$tables: $(tr '\n' ';' <"$work/problems")" >&2
      echo "$out stays empty; the $(wc -l <"$work/out") lines that came back are in $out.incomplete" >&2
      exit 1
    fi
    ;;
esac
cat "$work/out" >"$out"
[[ "$mode" == drop-consumer ]] || echo "$(wc -l <"$out") lines in $out"
