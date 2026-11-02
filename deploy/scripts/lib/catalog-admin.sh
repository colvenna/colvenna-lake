#!/usr/bin/env bash
# Admin API helpers, sourced by flow scripts. Reaches colvenna-catalog through kubectl port-forward
# (IAM-authenticated, RBAC-checked) and keeps every token out of logs and process arguments.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

# A free local port unless the caller picks one, so concurrent scripts never share a forward.
ADMIN_PORT="${ADMIN_PORT:-$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')}"

# Starts a port-forward to the catalog Service and leaves CA, admin token and URL in $ADMIN_DIR.
admin_open() {
  ADMIN_DIR="$(mktemp -d)"
  chmod 700 "$ADMIN_DIR"
  kubectl -n "$CATALOG_NAMESPACE" get secret colvenna-catalog-ca-key -o jsonpath='{.data.ca\.crt}' \
    | base64 -d >"$ADMIN_DIR/ca.crt"
  kubectl -n "$CATALOG_NAMESPACE" get secret colvenna-catalog-admin-token -o jsonpath='{.data.token}' \
    | base64 -d >"$ADMIN_DIR/token"
  printf 'header = "Authorization: Bearer %s"\n' "$(cat "$ADMIN_DIR/token")" >"$ADMIN_DIR/curlrc"
  kubectl -n "$CATALOG_NAMESPACE" port-forward svc/colvenna-catalog "$ADMIN_PORT:8181" \
    >"$ADMIN_DIR/port-forward.log" 2>&1 &
  ADMIN_FORWARD_PID=$!
  for _ in $(seq 1 30); do
    # Our own forward must be the one answering: another process could hold the port.
    if ! kill -0 "$ADMIN_FORWARD_PID" 2>/dev/null; then
      echo "port-forward to colvenna-catalog exited: $(cat "$ADMIN_DIR/port-forward.log")" >&2
      return 1
    fi
    if curl -s -o /dev/null --cacert "$ADMIN_DIR/ca.crt" "https://localhost:$ADMIN_PORT/"; then
      return 0
    fi
    sleep 1
  done
  echo "port-forward to colvenna-catalog did not come up" >&2
  return 1
}

admin_close() {
  if [[ -n "${ADMIN_FORWARD_PID:-}" ]]; then
    kill "$ADMIN_FORWARD_PID" 2>/dev/null || true
    wait "$ADMIN_FORWARD_PID" 2>/dev/null || true
  fi
  rm -rf "${ADMIN_DIR:-/nonexistent}"
}

# admin_call <method> <path> [json-body]; prints the response body, fails on HTTP >= 400.
admin_call() {
  local method="$1" path="$2" body="${3:-}"
  local args=(-sS --fail-with-body --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/curlrc" -X "$method")
  if [[ -n "$body" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$body")
  fi
  curl "${args[@]}" "https://localhost:$ADMIN_PORT$path"
}

# token_state <token-file> <principal> <id> <description>...: what an install does with the token in
# the file, one of <principal>'s (an admin): one line, which starts with
#   invalid  when the catalog refuses it (401: expired, revoked, its principal disabled, or unknown,
#            as after a restore of the metadata database),
#   renew    when it works but expires in fewer than TOKEN_RENEW_DAYS days (default 30), or
#   keep     when it works with at least that many days left,
# and says why. It is checked with an admin call made with it: the principal's token list, which
# gives each token's id and expiry but not which one the file's is (the catalog keeps only hashes).
# So the expiry is that of the token <id> names, the id the install recorded when it issued the
# token (renew when that is not among the principal's unrevoked tokens). Without one (<id> empty or
# "-": a token issued before ids were recorded), it is the latest of the principal's unrevoked tokens
# with one of the descriptions, those the install gives the token; with none of those, renew. Any
# other answer (a 503, no answer at all) fails it. Needs admin_open.
token_state() {
  local file="$1" principal="$2" status
  shift 2
  printf 'header = "Authorization: Bearer %s"\n' "$(cat "$file")" >"$ADMIN_DIR/check.curlrc"
  # curl prints 000 when nothing answers (the port-forward gone): reported below, as any status.
  status="$(curl -s -o "$ADMIN_DIR/check.json" -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" \
    -K "$ADMIN_DIR/check.curlrc" "https://localhost:$ADMIN_PORT/colvenna/admin/v1/principals/$principal/tokens")" \
    || true
  rm -f "$ADMIN_DIR/check.curlrc"
  case "$status" in
    200) ;;
    401) echo "invalid (the catalog refuses it: $(cat "$ADMIN_DIR/check.json"))"; return 0 ;;
    *)
      echo "Checking the token of $principal failed with $status: $(cat "$ADMIN_DIR/check.json" 2>/dev/null)" >&2
      return 1
      ;;
  esac
  python3 - "$ADMIN_DIR/check.json" "${TOKEN_RENEW_DAYS:-30}" "$principal" "$@" <<'PY'
import datetime, json, re, sys
listed, margin, principal, token_id, described = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], set(sys.argv[5:])
if not re.fullmatch(r"[0-9]+", margin):
    sys.exit(f"TOKEN_RENEW_DAYS is a whole number of days, not {margin!r}")
def epoch(value):
    # The token list gives epoch seconds with a fraction; an ISO-8601 instant is read too.
    if isinstance(value, (int, float)):
        return float(value)
    return datetime.datetime.strptime(re.sub(r"\.[0-9]+", "", value), "%Y-%m-%dT%H:%M:%SZ").replace(
        tzinfo=datetime.timezone.utc).timestamp()
unrevoked = [t for t in json.load(open(listed))["tokens"] if not t.get("revokedAt")]
if token_id not in ("", "-"):
    expiries = [epoch(t["expiresAt"]) for t in unrevoked if t["id"] == token_id]
    found = f"token {token_id}, the recorded id"
    if not expiries:
        print(f"renew (the recorded id {token_id} is not among {principal}'s unrevoked tokens)")
        sys.exit()
else:
    expiries = [epoch(t["expiresAt"]) for t in unrevoked if t.get("description") in described]
    found = "no id recorded: the latest token described as " + " or ".join(sorted(described))
    if not expiries:
        print("renew (no id recorded, no unrevoked token described as " + " or ".join(sorted(described)) + ")")
        sys.exit()
latest = max(expiries)
left = (latest - datetime.datetime.now(datetime.timezone.utc).timestamp()) / 86400
when = datetime.datetime.fromtimestamp(latest, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
print(f"{'keep' if left >= int(margin) else 'renew'} (expires {when}, in {left:.1f} days; {found})")
PY
}

# secret_token_state <namespace> <secret> <principal> <description>...: token_state of the token in
# the Secret (its key "token"), with the id store_secret_token recorded beside it (key "id"), if any.
# Needs admin_open.
secret_token_state() {
  local namespace="$1" secret="$2" held="$ADMIN_DIR/held.token" id state
  shift 2
  # Explicit returns: a caller's command substitution runs this without errexit.
  kubectl -n "$namespace" get secret "$secret" -o jsonpath='{.data.token}' | base64 -d >"$held" \
    || { rm -f "$held"; return 1; }
  id="$(kubectl -n "$namespace" get secret "$secret" -o jsonpath='{.data.id}' | base64 -d)" \
    || { rm -f "$held"; return 1; }
  state="$(token_state "$held" "$1" "$id" "${@:2}")" || { rm -f "$held"; return 1; }
  rm -f "$held"
  echo "$state"
}

# store_secret_token <namespace> <secret> <token-file> <id>: the Secret, with the token (key
# "token") and its id (key "id"; "-" when not known), by which token_state finds its expiry. Never
# an empty token.
store_secret_token() {
  local namespace="$1" secret="$2" file="$3" id="${4:--}"
  if [[ ! -s "$file" ]]; then
    echo "No token to store in $secret" >&2
    return 1
  fi
  kubectl -n "$namespace" create secret generic "$secret" --from-file=token="$file" \
    --from-literal=id="$id" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
}

# issue_secret_token <principal> <namespace> <secret> <ttl>: a new token for the principal, issued
# with the admin token and described as <secret>, into the Secret with its id (store_secret_token).
# Needs admin_open.
issue_secret_token() {
  local principal="$1" namespace="$2" secret="$3" ttl="$4" token="$ADMIN_DIR/$3.token" id="$ADMIN_DIR/$3.id"
  admin_call POST "/colvenna/admin/v1/principals/$principal/tokens" \
    "{\"ttl\":\"$ttl\",\"description\":\"$secret\"}" \
    | python3 -c 'import json, sys
issued = json.load(sys.stdin)
open(sys.argv[1], "w").write(issued["id"])
sys.stdout.write(issued["token"])' "$id" >"$token"
  store_secret_token "$namespace" "$secret" "$token" "$(cat "$id")"
  rm -f "$token" "$id"
}

# published_snapshot <database> <table>: the id of the snapshot colvenna-catalog serves, 0 if none.
published_snapshot() {
  admin_call GET "/v1/colvenna/databases/$1/tables/$2/snapshot" 2>/dev/null \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot"]["snapshot"]["id"])' \
      2>/dev/null || echo 0
}

# await_published <database> <table> <n>: waits up to ten minutes until colvenna-catalog serves
# snapshot n of the table or a later one. Needs admin_open.
await_published() {
  local _
  for _ in $(seq 1 60); do
    (($(published_snapshot "$1" "$2") >= $3)) && return 0
    sleep 10
  done
  echo "$1.$2 did not reach snapshot $3 in ten minutes" >&2
  return 1
}

# ensure_principal_token <principal> <namespace> <secret> <ttl>
# Creates the principal if missing, issues a token and stores it as <secret>/token in <namespace>.
ensure_principal_token() {
  local principal="$1" namespace="$2" secret="$3" ttl="$4" token
  admin_call POST /colvenna/admin/v1/principals \
    "{\"name\":\"$principal\",\"description\":\"flow identity\"}" >/dev/null 2>&1 || true
  token="$ADMIN_DIR/$secret.token"
  admin_call POST "/colvenna/admin/v1/principals/$principal/tokens" \
    "{\"ttl\":\"$ttl\",\"description\":\"$secret\"}" \
    | python3 -c 'import json,sys; sys.stdout.write(json.load(sys.stdin)["token"])' >"$token"
  # Never replace a working Secret with an empty one.
  if [[ ! -s "$token" ]]; then
    echo "Could not issue a token for $principal" >&2
    return 1
  fi
  kubectl -n "$namespace" create secret generic "$secret" --from-file=token="$token" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  rm -f "$token"
}

# admin_status <method> <path> [json-body]: like admin_call, for callers that act on the answer: prints
# the HTTP status and leaves the response body in $ADMIN_DIR/status.out.
admin_status() {
  local method="$1" path="$2" body="${3:-}"
  local args=(-s -o "$ADMIN_DIR/status.out" -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt"
    -K "$ADMIN_DIR/curlrc" -X "$method")
  if [[ -n "$body" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$body")
  fi
  curl "${args[@]}" "https://localhost:$ADMIN_PORT$path"
}

# grant_verdict <principal> <json>: whether the principal's grant identical to the one asked for (the
# grant's JSON, as ensure_grant posts it) fits its terms, by ensure_grant's rule. Prints keep, missing,
# or renew <id> <why>.
grant_verdict() {
  admin_call GET "/colvenna/admin/v1/grants?principal=$1" >"$ADMIN_DIR/grants.json" || return 1
  python3 - "$2" "$ADMIN_DIR/grants.json" <<'PY'
import datetime, json, os, sys
want = json.loads(sys.argv[1])
with open(sys.argv[2]) as f:
    grants = json.load(f)["grants"]
key = ("principal", "scope", "database", "object", "privilege", "effect")
same = [g for g in grants if all(g.get(k) == want.get(k) for k in key)]
if not same:
    print("missing")
    sys.exit()
grant = same[0]
utc = datetime.timezone.utc


def instant(text):
    # As the catalog writes it (Java's Instant): seconds, then 0, 3, 6 or 9 decimals, then Z.
    whole, _, fraction = text.rstrip("Z").partition(".")
    return (datetime.datetime.strptime(whole, "%Y-%m-%dT%H:%M:%S").replace(tzinfo=utc)
            + datetime.timedelta(microseconds=int((fraction + "000000")[:6])))


def columns(g):
    return ",".join(sorted(g["columns"])) if g.get("columns") is not None else "all"


days = int(os.environ.get("GRANT_DAYS", "7"))
margin = float(os.environ.get("GRANT_RENEW_DAYS") or days / 2)
now = datetime.datetime.now(utc)
expires = instant(grant["expiresAt"])
if expires <= now:
    why = f"expired {grant['expiresAt']}"
elif expires < now + datetime.timedelta(days=margin):
    why = f"expires {grant['expiresAt']}, within the {margin:g}-day renewal margin"
elif expires > now + datetime.timedelta(days=days + 1):
    why = f"expires {grant['expiresAt']}, later than a {days}-day term allows"
elif columns(grant) != columns(want):
    why = f"names columns {columns(grant)}, not {columns(want)}"
else:
    print("keep")
    sys.exit()
print("renew", grant["id"], why)
PY
}

# ensure_grant <principal> <scope> <database|-> <object|-> <privilege> [ALLOW|DENY] [<columns,csv>]
# Grants under the acceptance terms, expiring GRANT_DAYS (default 7) days from now; GRANT_PURPOSE,
# GRANT_APPROVER, GRANT_APPROVAL and GRANT_DAYS override the terms. The catalog keeps one grant per
# principal, scope, database, object, privilege and effect, expired or not, and answers 409 to
# another one (#22). So an identical grant is kept only while it fits the terms; otherwise it is
# deleted and granted anew under them. It no longer fits once it has expired, expires within
# GRANT_RENEW_DAYS (default half the term: a 1-day test grant is kept for its first 12 hours, not
# renewed on every call), expires more than a day later than the term allows, or names other
# columns. Another run renewing the same grant at the same time may delete it first (the DELETE
# answers 404) or grant it anew first (the POST answers 409): what it granted is kept if it fits.
ensure_grant() {
  local body status verdict id reason renewed gone=""
  body="$(python3 - "$@" <<'PY'
import datetime, json, os, sys
principal, scope, database, obj, privilege = sys.argv[1:6]
effect = sys.argv[6] if len(sys.argv) > 6 else "ALLOW"
columns = sys.argv[7].split(",") if len(sys.argv) > 7 and sys.argv[7] else None
days = int(os.environ.get("GRANT_DAYS", "7"))
expires = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=days)
grant = {"principal": principal, "scope": scope, "privilege": privilege, "effect": effect,
         "purpose": os.environ.get("GRANT_PURPOSE", "acceptance flows"),
         "approvedBy": os.environ.get("GRANT_APPROVER", "requester"),
         "approval": os.environ.get("GRANT_APPROVAL", "issue-21-acceptance"),
         "expiresAt": expires.strftime("%Y-%m-%dT%H:%M:%SZ")}
if database != "-":
    grant["database"] = database
if obj != "-":
    grant["object"] = obj
if columns:
    grant["columns"] = columns
print(json.dumps(grant))
PY
)"
  status="$(admin_status POST /colvenna/admin/v1/grants "$body")"
  if [[ "$status" == 200 ]]; then
    return 0
  fi
  if [[ "$status" != 409 ]]; then
    echo "Grant $* failed with $status: $(cat "$ADMIN_DIR/status.out")" >&2
    return 1
  fi
  if ! verdict="$(grant_verdict "$1" "$body")"; then
    echo "Grant $* answered 409, and the grants of $1 could not be checked" >&2
    return 1
  fi
  case "$verdict" in
    keep) return 0 ;;
    missing)
      echo "Grant $* answered 409, but $1 has no identical grant" >&2
      return 1
      ;;
  esac
  read -r _ id reason <<<"$verdict"
  status="$(admin_status DELETE "/colvenna/admin/v1/grants/$id")"
  case "$status" in
    200) ;;
    404) gone=", already deleted" ;;
    *)
      echo "Grant $*: grant $id $reason, and deleting it failed with $status: $(cat "$ADMIN_DIR/status.out")" >&2
      return 1
      ;;
  esac
  status="$(admin_status POST /colvenna/admin/v1/grants "$body")"
  if [[ "$status" == 409 ]]; then
    # Granted anew in between, by another run: kept if it fits the terms. Read once, no more.
    verdict="$(grant_verdict "$1" "$body")" || verdict="unchecked"
    if [[ "$verdict" == keep ]]; then
      echo "Grant $*: grant $id $reason$gone; another run granted it anew under the terms, kept" >&2
      return 0
    fi
    echo "Grant $* answered 409 again after grant $id ($reason$gone) was deleted, and what is there" \
      "is not kept: ${verdict/#renew /grant }" >&2
    return 1
  fi
  if [[ "$status" != 200 ]]; then
    echo "Grant $* failed with $status after grant $id ($reason$gone) was deleted: $(cat "$ADMIN_DIR/status.out")" >&2
    return 1
  fi
  # The new grant's id, as the catalog answered, and its expiry, as granted.
  renewed="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"], json.loads(sys.argv[2])["expiresAt"])' \
    "$ADMIN_DIR/status.out" "$body" 2>/dev/null)" || renewed="? ?"
  echo "Grant $*: grant $id $reason$gone; replaced by grant ${renewed% *}, expiring ${renewed#* }" >&2
}

# revoke_principal <principal>: deletes every grant of the principal and revokes its tokens, so an
# identity whose job is over keeps no access.
revoke_principal() {
  local principal="$1" id
  for id in $(admin_call GET "/colvenna/admin/v1/grants?principal=$principal" \
    | python3 -c 'import json,sys; print(" ".join(str(g["id"]) for g in json.load(sys.stdin)["grants"]))'); do
    admin_call DELETE "/colvenna/admin/v1/grants/$id" >/dev/null
  done
  for id in $(admin_call GET "/colvenna/admin/v1/principals/$principal/tokens" \
    | python3 -c 'import json,sys; print(" ".join(t["id"] for t in json.load(sys.stdin)["tokens"] if not t.get("revokedAt")))'); do
    admin_call DELETE "/colvenna/admin/v1/tokens/$id" >/dev/null
  done
}

# ensure_flow_grants <principal> <default>: a flow run's grants. FLOW_GRANTS, if set, replaces the
# default: entries of ensure_grant arguments after the principal, separated by ';'.
ensure_flow_grants() {
  local principal="$1" entry
  local -a entries fields
  IFS=';' read -r -a entries <<<"${FLOW_GRANTS:-$2}"
  for entry in "${entries[@]}"; do
    read -r -a fields <<<"$entry"
    ensure_grant "$principal" "${fields[@]}"
  done
}
