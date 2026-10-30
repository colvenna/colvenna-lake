#!/usr/bin/env bash
# Flink CDC 3.7's YAML pipeline against colvenna-catalog (pipeline/). A principal of
# its own, MANAGE on one database (pipe_<run>) and nothing on another (pipe_other_<run>), both made
# here; MySQL 8.0 in Docker on this machine (synthetic rows, loopback only); the test JVM without
# AWS credentials of its own, so it reaches S3 only with the data tokens colvenna-catalog vends. The
# token reaches the test in its environment, never an argument, and is cut from the logs kept.
# Afterwards the databases go and the principal's grants and tokens are revoked.
#   run-pipeline.sh <run-id> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../../acceptance/common.sh
source "$(dirname "$0")/../../acceptance/common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-pipeline.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-pipeline.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"
sha="$(committed_sha)"
mkdir -p "$evidence"
principal="pipe-$run" granted="pipe_$run" refused="pipe_other_$run" name="cdc37-pipeline-$run"
umask 077
# shellcheck disable=SC2317  # invoked through the EXIT trap
cleanup() {
  docker rm -f "$name" >/dev/null 2>&1 || true
  # The test's own log may hold the token, however the run ended.
  rm -f "$here/pipeline/target/repro.log"
  if [[ -n "${ADMIN_DIR:-}" ]]; then
    for db in "$granted" "$refused"; do
      admin_call DELETE "/v1/colvenna/databases/$db/tables/orders" >/dev/null 2>&1 || true
      admin_call DELETE "/v1/colvenna/databases/$db" >/dev/null 2>&1 || true
    done
    revoke_principal "$principal" || true
    admin_call POST "/colvenna/admin/v1/principals/$principal/disable" >/dev/null 2>&1 || true
    admin_close
  fi
}
trap cleanup EXIT
admin_open
for db in "$granted" "$refused"; do
  admin_call POST /v1/colvenna/databases "{\"name\":\"$db\",\"options\":{}}" >/dev/null
done
admin_call POST /colvenna/admin/v1/principals \
  "{\"name\":\"$principal\",\"description\":\"issue 113 pipeline\"}" >/dev/null 2>&1 || true
GRANT_PURPOSE="issue 113 YAML pipeline evaluation" GRANT_APPROVAL="issue-113" GRANT_DAYS=1 \
  ensure_grant "$principal" DATABASE "$granted" - MANAGE
admin_call POST "/colvenna/admin/v1/principals/$principal/tokens" '{"ttl":"PT2H","description":"issue 113"}' \
  | python3 -c 'import json,sys; sys.stdout.write(json.load(sys.stdin)["token"])' >"$ADMIN_DIR/pipe.token"
[[ -s "$ADMIN_DIR/pipe.token" ]] || { echo "Could not issue a token for $principal" >&2; exit 1; }
# The JDK's public CAs (S3) and colvenna-catalog's.
cp "$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")/lib/security/cacerts" "$ADMIN_DIR/trust.p12"
chmod 600 "$ADMIN_DIR/trust.p12"
keytool -importcert -noprompt -alias colvenna-catalog -file "$ADMIN_DIR/ca.crt" \
  -keystore "$ADMIN_DIR/trust.p12" -storepass changeit >/dev/null 2>&1
port="$(mysql_up "$name")"
rc=0
(
  cd "$here/pipeline"
  # No AWS credentials of its own: no profile, no keys, no instance metadata.
  unset AWS_PROFILE AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
  export AWS_EC2_METADATA_DISABLED=true AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null
  # The region is no credential; without it and the instance metadata the S3 client cannot start.
  export AWS_REGION="$COLVENNA_REGION" AWS_DEFAULT_REGION="$COLVENNA_REGION"
  COLVENNA_PIPE_URI="https://localhost:$ADMIN_PORT"
  COLVENNA_PIPE_TOKEN="$(cat "$ADMIN_DIR/pipe.token")"
  export COLVENNA_PIPE_URI COLVENNA_PIPE_TOKEN
  mvn -B test "-Dmysql.port=$port" "-Drun=$run" "-Dtruststore=$ADMIN_DIR/trust.p12"
) >"$ADMIN_DIR/mvn.log" 2>&1 || rc=$?
# The evidence keeps the logs without the token, read from its file (never an argument).
for f in "$ADMIN_DIR/mvn.log" "$here/pipeline/target/repro.log" "$here/pipeline/target/pipeline-result.txt"; do
  if [[ -f "$f" ]]; then
    python3 -c 'import sys; t = open(sys.argv[1]).read().strip(); sys.stdout.write(open(sys.argv[2]).read().replace(t, "<token>"))' \
      "$ADMIN_DIR/pipe.token" "$f" >"$evidence/$(basename "$f")"
  fi
done
rm -f "$here/pipeline/target/repro.log"
# Nothing was written where the principal has no grant: the database holds no table.
admin_call GET "/v1/colvenna/databases/$refused/tables" >"$evidence/refused-tables.json" 2>/dev/null || true
echo "sha $sha" >"$evidence/source.txt"
admin_call GET "/colvenna/admin/v1/grants?principal=$principal" >"$evidence/grants.json" 2>/dev/null || true
cat "$evidence/pipeline-result.txt" 2>/dev/null || grep -E 'Tests run|FAIL|ERROR' "$evidence/mvn.log" | head -20
exit "$rc"
