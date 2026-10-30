#!/usr/bin/env bash
# Manages the entry's users in the ColvennaLakeEntry user pool.
#   users.sh add <username> <email> <group>   Cognito emails a temporary password; the user sets a
#                                             new one and enrols a TOTP app at first sign-in.
#   users.sh groups <username> <group>...     Sets the user's groups (colvenna-admin, colvenna-operator,
#                                             colvenna-bi); none removes every role.
#   users.sh disable <username>               Disables the user and ends their sessions.
#   users.sh remove <username>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
assert_account
pool="$(entry_output UserPoolId)"
usage() { sed -n '2,9p' "$0" >&2; exit 1; }
set_groups() {
  local user="$1" group
  shift
  for group in $(aws cognito-idp admin-list-groups-for-user --user-pool-id "$pool" --username "$user" \
    --query 'Groups[].GroupName' --output text); do
    aws cognito-idp admin-remove-user-from-group --user-pool-id "$pool" --username "$user" --group-name "$group"
  done
  for group in "$@"; do
    aws cognito-idp admin-add-user-to-group --user-pool-id "$pool" --username "$user" --group-name "$group"
  done
}
case "${1:-}" in
  add)
    [[ $# -eq 4 ]] || usage
    aws cognito-idp admin-create-user --user-pool-id "$pool" --username "$2" \
      --user-attributes Name=email,Value="$3" Name=email_verified,Value=true \
      --desired-delivery-mediums EMAIL >/dev/null
    set_groups "$2" "$4"
    ;;
  groups)
    [[ $# -ge 2 ]] || usage
    set_groups "${@:2}"
    ;;
  disable)
    [[ $# -eq 2 ]] || usage
    aws cognito-idp admin-disable-user --user-pool-id "$pool" --username "$2"
    aws cognito-idp admin-user-global-sign-out --user-pool-id "$pool" --username "$2"
    ;;
  remove)
    [[ $# -eq 2 ]] || usage
    aws cognito-idp admin-delete-user --user-pool-id "$pool" --username "$2"
    ;;
  *) usage ;;
esac
