#!/usr/bin/env bash
# Continuous source writes for the CDC runs, from a pod, until stopped:
#   writer.sh start mysql|postgres <database> <name>   starts pod <name>
#   writer.sh stop <name>                               stops it and prints how many batches it ran
# Each batch is a transaction that keeps the source contract: an insert under a new key, an
# update raising a version, an occasional delete (keys are never reused), on two tables; the MySQL
# writer also changes invoices, the PostgreSQL one refunds, once those tables exist.
# WRITER_AVOID_NODE=<node> keeps the pod off that node (a node-loss test takes it away).
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
assert_account
ensure_kubeconfig
usage="usage: writer.sh start mysql|postgres <database> <name> | stop <name>"
command="${1:?$usage}"

# The loops run in the client images: bash in the MySQL one, ash in the Alpine PostgreSQL one.
# Both read SOURCE_HOST and SOURCE_DB from the environment and the password from /admin.
mysql_loop() {
  cat <<'SH'
export MYSQL_PWD="$(cat /admin/password)"
n=0
# After the keys of earlier writers on this database: a key taken would fail every batch.
next="$(mysql --ssl-mode=VERIFY_IDENTITY --ssl-ca=/ca/ca.pem -h "$SOURCE_HOST" -u colvenna_admin "$SOURCE_DB" -N \
  -e "SELECT GREATEST(COALESCE(MAX(id) + 1, 0), 1000000) FROM orders")"
while true; do
  a=$((RANDOM % 5000 + 1)); b=$((RANDOM % 5000 + 1)); c=$((RANDOM % 200 + 1))
  if mysql --ssl-mode=VERIFY_IDENTITY --ssl-ca=/ca/ca.pem -h "$SOURCE_HOST" -u colvenna_admin "$SOURCE_DB" -e "
      START TRANSACTION;
      INSERT INTO orders (id, customer_id, amount, state, version_no) VALUES ($next, $c, $n.25, 'created', 1);
      UPDATE orders SET amount = amount + 1, state = 'paid', version_no = version_no + 1 WHERE id = $a;
      DELETE FROM orders WHERE id = $b AND MOD(id, 7) = 0;
      UPDATE customers SET region = 'north', version_no = version_no + 1 WHERE id = $c;
      COMMIT;"; then
    n=$((n + 1))
  fi
  # invoices exists only once the run creates it; until then this batch fails harmlessly.
  d=$((RANDOM % 3000 + 1)); e=$((RANDOM % 3000 + 1))
  mysql --ssl-mode=VERIFY_IDENTITY --ssl-ca=/ca/ca.pem -h "$SOURCE_HOST" -u colvenna_admin "$SOURCE_DB" -e "
      START TRANSACTION;
      INSERT INTO invoices (id, label, amount, version_no) VALUES ($((next + 1000000)), 'writer', 1, 1);
      UPDATE invoices SET amount = amount + 1, version_no = version_no + 1 WHERE id = $d;
      DELETE FROM invoices WHERE id = $e AND MOD(id, 5) = 0;
      COMMIT;" 2>/dev/null || true
  next=$((next + 1))
  echo "batches=$n"
  sleep 0.5
done
SH
}

postgres_loop() {
  cat <<'SH'
export PGPASSWORD="$(cat /admin/password)"
n=0
# After the keys of earlier writers on this database: a key taken would fail every batch.
next="$(psql -qtA "host=$SOURCE_HOST dbname=$SOURCE_DB user=colvenna_admin sslmode=verify-full sslrootcert=/ca/ca.pem" \
  -c "SELECT GREATEST(COALESCE(MAX(id) + 1, 0), 1000000) FROM accounts")"
while true; do
  a=$((RANDOM % 2000 + 1)); b=$((RANDOM % 2000 + 1)); p=$((RANDOM % 1500 + 1))
  region=west
  if [ $((next % 2)) -eq 0 ]; then region=east; fi
  if psql -q "host=$SOURCE_HOST dbname=$SOURCE_DB user=colvenna_admin sslmode=verify-full sslrootcert=/ca/ca.pem" -v ON_ERROR_STOP=1 -c "
      BEGIN;
      INSERT INTO accounts (id, owner, balance, version_no) VALUES ($next, 'writer', $n, 1);
      UPDATE accounts SET balance = balance + 1, version_no = version_no + 1 WHERE id = $a;
      DELETE FROM accounts WHERE id = $b AND id % 7 = 0;
      UPDATE payments SET amount = amount + 1, version_no = version_no + 1 WHERE id = $p;
      INSERT INTO payments (id, region, amount, version_no) VALUES ($next, '$region', 1, 1);
      COMMIT;"; then
    n=$((n + 1))
  fi
  # refunds exists only once the run creates it; until then this batch fails harmlessly.
  d=$((RANDOM % 1000 + 1)); e=$((RANDOM % 1000 + 1))
  psql -q "host=$SOURCE_HOST dbname=$SOURCE_DB user=colvenna_admin sslmode=verify-full sslrootcert=/ca/ca.pem" -v ON_ERROR_STOP=1 -c "
      BEGIN;
      INSERT INTO refunds (id, label, amount, version_no) VALUES ($((next + 1000000)), 'writer', 1, 1);
      UPDATE refunds SET amount = amount + 1, version_no = version_no + 1 WHERE id = $d;
      DELETE FROM refunds WHERE id = $e AND id % 5 = 0;
      COMMIT;" >/dev/null 2>&1 || true
  next=$((next + 1))
  echo "batches=$n"
  sleep 0.5
done
SH
}

# avoid_node: the pod's affinity away from WRITER_AVOID_NODE, or nothing.
avoid_node() {
  [[ -n "${WRITER_AVOID_NODE:-}" ]] || return 0
  printf '  affinity: {nodeAffinity: {requiredDuringSchedulingIgnoredDuringExecution: {nodeSelectorTerms: [{matchExpressions: [{key: kubernetes.io/hostname, operator: NotIn, values: [%s]}]}]}}}\n' \
    "$WRITER_AVOID_NODE"
}

start() {
  local kind="$1" database="$2" name="$3" work image user host shell
  [[ "$database" =~ ^[a-z0-9_]+$ && "$name" =~ ^[a-z0-9-]+$ ]] || { echo "$usage" >&2; exit 1; }
  work="$(mktemp -d)"
  chmod 700 "$work"
  if [[ "$kind" == mysql ]]; then
    secret_field "$(stack_output "$TEST_MYSQL_STACK" MySqlAdminSecretArn)" password >"$work/password"
    image="$MYSQL_CLIENT_IMAGE" user=999 shell=bash
    host="$(stack_output "$TEST_MYSQL_STACK" MySqlEndpoint)"
    mysql_loop >"$work/loop.sh"
  else
    secret_field "$(stack_output "$TEST_POSTGRES_STACK" PostgresAdminSecretArn)" password >"$work/password"
    image="$POSTGRES_IMAGE" user=70 shell=sh
    host="$(stack_output "$TEST_POSTGRES_STACK" PostgresEndpoint)"
    postgres_loop >"$work/loop.sh"
  fi
  kubectl -n "$JOB_NAMESPACE" create secret generic "$name-admin" --from-file=password="$work/password" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl -n "$JOB_NAMESPACE" create configmap "$name-loop" --from-file=loop.sh="$work/loop.sh" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  rm -rf "$work"
  kubectl -n "$JOB_NAMESPACE" delete pod "$name" --ignore-not-found --wait=true >/dev/null
  kubectl -n "$JOB_NAMESPACE" apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $name, labels: {colvenna-lake.io/role: writer}}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  terminationGracePeriodSeconds: 5
  securityContext: {runAsNonRoot: true, runAsUser: $user, seccompProfile: {type: RuntimeDefault}}
$(avoid_node)
  containers:
    - name: writer
      image: $image
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      env:
        - {name: SOURCE_HOST, value: "$host"}
        - {name: SOURCE_DB, value: "$database"}
      command: ["$shell", "/loop/loop.sh"]
      volumeMounts:
        - {name: admin, mountPath: /admin, readOnly: true}
        - {name: loop, mountPath: /loop, readOnly: true}
        - {name: ca, mountPath: /ca, readOnly: true}
  volumes:
    - {name: admin, secret: {secretName: $name-admin}}
    - {name: loop, configMap: {name: $name-loop}}
    - {name: ca, configMap: {name: rds-ca}}
YAML
  kubectl -n "$JOB_NAMESPACE" wait --for=condition=Ready "pod/$name" --timeout=180s >/dev/null
}

stop() {
  local name="$1" batches
  batches="$(kubectl -n "$JOB_NAMESPACE" logs "$name" --tail=1 2>/dev/null || true)"
  kubectl -n "$JOB_NAMESPACE" delete pod "$name" --ignore-not-found --wait=true >/dev/null
  kubectl -n "$JOB_NAMESPACE" delete secret "$name-admin" --ignore-not-found >/dev/null
  kubectl -n "$JOB_NAMESPACE" delete configmap "$name-loop" --ignore-not-found >/dev/null
  echo "${batches:-batches=0}"
}

case "$command" in
  start) start "${2:?$usage}" "${3:?$usage}" "${4:?$usage}" ;;
  stop) stop "${2:?$usage}" ;;
  *) echo "$usage" >&2; exit 1 ;;
esac
