#!/usr/bin/env bash
# Drops flow databases through the catalog: every view and table (managed table directories are
# purged by the catalog), then the database itself. Refuses names outside the catalog flows'
# prefixes.
# Usage: drop-database.sh <database>...
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
if [[ $# -eq 0 ]]; then
  echo "usage: drop-database.sh <database>..." >&2
  exit 1
fi
for database in "$@"; do
  if [[ ! "$database" =~ ^(flow|renew)[a-z0-9]*_[a-z0-9_]+$ ]]; then
    echo "Refusing to drop $database: not a catalog flow database" >&2
    exit 1
  fi
done
list="$(mktemp)"
trap 'rm -f "$list"; admin_close' EXIT
admin_open

# names <path> <field>: every name on all pages of a list endpoint, one per line.
names() {
  local path="$1" field="$2" token="" page
  while :; do
    page="$(admin_call GET "$path?maxResults=1000${token:+&pageToken=$token}")"
    python3 -c 'import json,sys; print("\n".join(json.loads(sys.argv[1]).get(sys.argv[2]) or []))' \
      "$page" "$field"
    token="$(python3 -c '
import json, sys, urllib.parse
print(urllib.parse.quote(json.loads(sys.argv[1]).get("nextPageToken") or "", safe=""))' "$page")"
    [[ -n "$token" ]] || break
  done
}

# status <path>: the HTTP status of a GET, without failing on 4xx.
status() {
  curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/curlrc" \
    "https://localhost:$ADMIN_PORT$1"
}

for database in "$@"; do
  case "$(status "/v1/colvenna/databases/$database")" in
    200) ;;
    404)
      echo "$database: absent"
      continue
      ;;
    *)
      echo "$database: cannot read it from the catalog" >&2
      exit 1
      ;;
  esac
  names "/v1/colvenna/databases/$database/views" views >"$list"
  while read -r view; do
    [[ -n "$view" ]] && admin_call DELETE "/v1/colvenna/databases/$database/views/$view" >/dev/null
  done <"$list"
  names "/v1/colvenna/databases/$database/tables" tables >"$list"
  while read -r table; do
    [[ -n "$table" ]] && admin_call DELETE "/v1/colvenna/databases/$database/tables/$table" >/dev/null
  done <"$list"
  admin_call DELETE "/v1/colvenna/databases/$database" >/dev/null
  echo "$database: dropped"
done
