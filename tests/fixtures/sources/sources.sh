#!/usr/bin/env bash
# The test MySQL and PostgreSQL sources, as the acceptance runs use them.
#   mysql <sql-file>               runs a SQL file as the MySQL admin; rows tab-separated on stdout
#   mysql-table <db> <table>       every row of a table, tab-separated with a header line
#   mysql-query <sql-file>         a query's rows, tab-separated with a header line
#   postgres <database> <sql-file> the same against PostgreSQL
#   postgres-table <db> <table>    every row of a table, tab-separated with a header line
#   users                          ensures the CDC, inspector and heartbeat users of the
#                                  acceptance fixtures and writes their credentials as Secrets in
#                                  the job namespace
#   prepare                        a new environment's sources made ready for CDC: the CDC
#                                  logins (MySQL colvenna_cdc with replication, binlog kept 24 hours;
#                                  PostgreSQL colvenna_pgcdc with rds_replication), then users
# Admin passwords reach the client pod as a short-lived Secret, never as an argument; the server
# certificate is verified against the RDS CA bundle (ConfigMap rds-ca, install-operator.sh). Rows
# are copied from a file in the pod (run_pod_out): a whole table can be more than a container's
# log keeps.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../../acceptance/common.sh
source "$(dirname "$0")/../../acceptance/common.sh"
assert_account
ensure_kubeconfig
usage="usage: sources.sh mysql <sql-file> | mysql-table <db> <table> | mysql-query <sql-file> | postgres <database> <sql-file> | postgres-table <db> <table> | users | prepare"
work="$(mktemp -d)"
chmod 700 "$work"
pod="source-$RANDOM"
# shellcheck disable=SC2317  # invoked through the EXIT trap
cleanup() {
  kubectl -n "$JOB_NAMESPACE" delete secret "$pod-admin" --ignore-not-found >/dev/null 2>&1 || true
  kubectl -n "$JOB_NAMESPACE" delete secret "$pod-sql" --ignore-not-found >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# admin_secret <stack> <output>: the source admin password as the pod's Secret.
admin_secret() {
  secret_field "$(stack_output "$1" "$2")" password >"$work/password"
  kubectl -n "$JOB_NAMESPACE" create secret generic "$pod-admin" --from-file=password="$work/password" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  rm -f "$work/password"
}

# A Secret, not a ConfigMap: the SQL may set passwords.
sql_secret() {
  kubectl -n "$JOB_NAMESPACE" create secret generic "$pod-sql" --from-file=run.sql="$1" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
}

# mysql_file <sql-file> [<client-flags>]; postgres_file <database> <sql-file> [<psql-flags>]
mysql_file() {
  local flags="${2:---skip-column-names}"
  admin_secret "$TEST_MYSQL_STACK" MySqlAdminSecretArn
  sql_secret "$1"
  cat >"$work/pod.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $pod}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 999, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: mysql
      image: $MYSQL_CLIENT_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["sh", "-ec"]
      args:
        - |
          export MYSQL_PWD="\$(cat /admin/password)"
          mysql --ssl-mode=VERIFY_IDENTITY --ssl-ca=/ca/ca.pem \
            -h "$(stack_output "$TEST_MYSQL_STACK" MySqlEndpoint)" \
            -u colvenna_admin --batch $flags < /sql/run.sql >/out/out
          $POD_OUT_DONE
      volumeMounts:
        - {name: admin, mountPath: /admin, readOnly: true}
        - {name: sql, mountPath: /sql, readOnly: true}
        - {name: ca, mountPath: /ca, readOnly: true}
        - {name: out, mountPath: /out}
  volumes:
    - {name: admin, secret: {secretName: $pod-admin}}
    - {name: sql, secret: {secretName: $pod-sql}}
    - {name: ca, configMap: {name: rds-ca}}
    - {name: out, emptyDir: {}}
YAML
  run_pod_out "$JOB_NAMESPACE" "$pod" "$work/pod.yaml"
}

postgres_file() {
  local flags="${3:--At}"
  admin_secret "$TEST_POSTGRES_STACK" PostgresAdminSecretArn
  sql_secret "$2"
  cat >"$work/pod.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $pod}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 70, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: psql
      image: $POSTGRES_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["sh", "-ec"]
      args:
        - |
          export PGPASSWORD="\$(cat /admin/password)"
          psql "host=$(stack_output "$TEST_POSTGRES_STACK" PostgresEndpoint) dbname=$1 user=colvenna_admin sslmode=verify-full sslrootcert=/ca/ca.pem" \
            -v ON_ERROR_STOP=1 $flags -F '	' -f /sql/run.sql >/out/out
          $POD_OUT_DONE
      volumeMounts:
        - {name: admin, mountPath: /admin, readOnly: true}
        - {name: sql, mountPath: /sql, readOnly: true}
        - {name: ca, mountPath: /ca, readOnly: true}
        - {name: out, mountPath: /out}
  volumes:
    - {name: admin, secret: {secretName: $pod-admin}}
    - {name: sql, secret: {secretName: $pod-sql}}
    - {name: ca, configMap: {name: rds-ca}}
    - {name: out, emptyDir: {}}
YAML
  run_pod_out "$JOB_NAMESPACE" "$pod" "$work/pod.yaml"
}

# login_secret <name> <user> <password-file>: a source login as the LakeSource declarations use it.
login_secret() {
  kubectl -n "$JOB_NAMESPACE" create secret generic "$1" --from-literal=username="$2" \
    --from-file=password="$3" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
}

users() {
  # The CDC users prepare creates, with their Secrets Manager passwords.
  secret_field "$(stack_output "$TEST_MYSQL_STACK" MySqlCdcSecretArn)" password >"$work/mysql-cdc"
  login_secret mysql-cdc colvenna_cdc "$work/mysql-cdc"
  secret_field "$(stack_output "$TEST_POSTGRES_STACK" PostgresCdcSecretArn)" password >"$work/pg-cdc"
  login_secret postgres-cdc colvenna_pgcdc "$work/pg-cdc"
  # A MySQL user that may read trigger definitions (TRIGGER), for admission checks only. Its
  # password stays in the Kubernetes Secret; a rerun keeps it.
  if ! kubectl -n "$JOB_NAMESPACE" get secret mysql-inspect >/dev/null 2>&1; then
    printf "%s" "$(openssl rand -hex 24)" >"$work/inspect"
    login_secret mysql-inspect colvenna_inspect "$work/inspect"
  fi
  kubectl -n "$JOB_NAMESPACE" get secret mysql-inspect -o jsonpath='{.data.password}' \
    | base64 -d >"$work/inspect"
  # The heartbeat's writer (LLD "监控与数据质量"): each fixture grants it its colvenna_heartbeat table only.
  if ! kubectl -n "$JOB_NAMESPACE" get secret mysql-beat >/dev/null 2>&1; then
    printf "%s" "$(openssl rand -hex 24)" >"$work/beat"
    login_secret mysql-beat colvenna_beat "$work/beat"
  fi
  kubectl -n "$JOB_NAMESPACE" get secret mysql-beat -o jsonpath='{.data.password}' \
    | base64 -d >"$work/beat"
  cat >"$work/users.sql" <<SQL
CREATE USER IF NOT EXISTS 'colvenna_inspect'@'%' IDENTIFIED WITH caching_sha2_password BY '$(cat "$work/inspect")' REQUIRE SSL;
ALTER USER 'colvenna_inspect'@'%' IDENTIFIED WITH caching_sha2_password BY '$(cat "$work/inspect")';
CREATE USER IF NOT EXISTS 'colvenna_beat'@'%' IDENTIFIED WITH caching_sha2_password BY '$(cat "$work/beat")' REQUIRE SSL;
ALTER USER 'colvenna_beat'@'%' IDENTIFIED WITH caching_sha2_password BY '$(cat "$work/beat")';
SQL
  rm -f "$work/mysql-cdc" "$work/pg-cdc" "$work/inspect" "$work/beat"
  mysql_file "$work/users.sql" >/dev/null
  rm -f "$work/users.sql"
  echo "Secrets mysql-cdc, mysql-inspect, mysql-beat, postgres-cdc in $JOB_NAMESPACE"
}

# The CDC logins on the sources, with the passwords the test source stacks keep in Secrets Manager.
# Each fixture grants them its own databases and tables.
prepare() {
  aws secretsmanager get-secret-value --secret-id "$(stack_output "$TEST_MYSQL_STACK" MySqlCdcSecretArn)" \
    --query SecretString --output text >"$work/mysql-cdc.json"
  aws secretsmanager get-secret-value \
    --secret-id "$(stack_output "$TEST_POSTGRES_STACK" PostgresCdcSecretArn)" \
    --query SecretString --output text >"$work/pg-cdc.json"
  python3 - "$work" <<'PY'
import json, re, sys
work = sys.argv[1]
mysql = json.load(open(f"{work}/mysql-cdc.json"))
pg = json.load(open(f"{work}/pg-cdc.json"))
# The generated passwords hold no quote or backslash; anything else is refused, not quoted.
if mysql.get("username") != "colvenna_cdc" or not re.fullmatch(r"[A-Za-z0-9 ]{24,128}", mysql["password"]):
    raise SystemExit("unexpected MySQL CDC secret")
if pg.get("username") != "colvenna_pgcdc" or not re.fullmatch(r"[A-Za-z0-9]{24,128}", pg["password"]):
    raise SystemExit("unexpected PostgreSQL CDC secret")
open(f"{work}/mysql-cdc.sql", "w").write(f"""CREATE USER IF NOT EXISTS 'colvenna_cdc'@'%' IDENTIFIED WITH caching_sha2_password BY '{mysql["password"]}' REQUIRE SSL;
ALTER USER 'colvenna_cdc'@'%' IDENTIFIED WITH caching_sha2_password BY '{mysql["password"]}' REQUIRE SSL;
GRANT SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'colvenna_cdc'@'%';
CALL mysql.rds_set_configuration('binlog retention hours', 24);
""")
open(f"{work}/pg-cdc.sql", "w").write(f"""DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'colvenna_pgcdc') THEN CREATE ROLE colvenna_pgcdc LOGIN; END IF;
END $$;
ALTER ROLE colvenna_pgcdc LOGIN PASSWORD '{pg["password"]}';
GRANT rds_replication TO colvenna_pgcdc;
""")
PY
  rm -f "$work/mysql-cdc.json" "$work/pg-cdc.json"
  mysql_file "$work/mysql-cdc.sql" >/dev/null
  postgres_file source_db "$work/pg-cdc.sql" >/dev/null
  rm -f "$work/mysql-cdc.sql" "$work/pg-cdc.sql"
  users
}

case "${1:-}" in
  mysql) mysql_file "${2:?$usage}" ;;
  mysql-table)
    [[ "${2:?$usage}" =~ ^[a-z0-9_]+$ && "${3:?$usage}" =~ ^[a-z0-9_]+$ ]] || { echo "$usage" >&2; exit 1; }
    echo "SELECT * FROM \`$2\`.\`$3\`;" >"$work/table.sql"
    mysql_file "$work/table.sql" --column-names
    ;;
  mysql-query) mysql_file "${2:?$usage}" --column-names ;;
  postgres) postgres_file "${2:?$usage}" "${3:?$usage}" ;;
  postgres-table)
    [[ "${2:?$usage}" =~ ^[a-z0-9_]+$ && "${3:?$usage}" =~ ^[a-z0-9_]+$ ]] || { echo "$usage" >&2; exit 1; }
    echo "COPY (SELECT * FROM public.$3) TO STDOUT WITH (FORMAT text, HEADER true);" >"$work/table.sql"
    postgres_file "$2" "$work/table.sql" -q
    ;;
  users) users ;;
  prepare) prepare ;;
  *) echo "$usage" >&2; exit 1 ;;
esac
