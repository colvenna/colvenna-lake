#!/usr/bin/env bash
# cdk_deploy, the one way the deploy scripts run `cdk deploy` (deploy/scripts/lib/common.sh sources
# this file; deploy/scripts/release/deploy-registry.sh, which is of no environment, too).
#
# cdk_deploy <cdk deploy arguments>...
#   runs `npx cdk deploy <arguments>` (from deploy/aws, where the callers are), and runs it again
#   when, and only when, it failed because the CDK CLI resolved no AWS account from the
#   credentials: CDK_DEPLOY_ATTEMPTS runs in all at most (default 3), CDK_DEPLOY_RETRY_WAIT seconds
#   apart (default 30). Any other failure is returned at once, credentials of another account among
#   them.
#
#   The CLI asks STS for the credentials' account once, as it starts, and gives it to the app as
#   CDK_DEFAULT_ACCOUNT; deploy/aws/src/app.ts synthesizes only for that account (synthesisAccount
#   in env.ts). When no answer comes in time (the credentials from the instance metadata service, or
#   STS, on a loaded host: issue #233), the account stays unresolved for the whole run and app.ts
#   refuses, saying UNRESOLVED_ACCOUNT, before anything is deployed. A new run asks again, and app.ts
#   checks its answer afresh. Handing the app an account the installer checked (COLVENNA_ACCOUNT) would
#   not do: the CLI's deployment in that run has no account either and stops ("Need to perform AWS
#   calls for account ..., but no credentials have been configured"), and app.ts would be checking
#   the account against itself. For the same reason the CLI is not given the caller's own
#   CDK_DEFAULT_ACCOUNT or CDK_DEFAULT_REGION: it passes them on to the app when it resolves none.
#
#   The run's standard output is passed on as it is, its standard error as it comes, kept to read
#   why it failed.

# What deploy/aws/src/env.ts's synthesisAccount says when the CLI resolved no account (its
# UNRESOLVED_ACCOUNT; tests/acceptance/test_cdk_deploy.py checks they are the same).
CDK_UNRESOLVED_ACCOUNT='resolved no AWS account from the credentials'

cdk_deploy() {
  local attempts="${CDK_DEPLOY_ATTEMPTS:-3}" wait="${CDK_DEPLOY_RETRY_WAIT:-30}" attempt=1 status err
  if [[ ! "$attempts" =~ ^[1-9][0-9]*$ || ! "$wait" =~ ^[0-9]+$ ]]; then
    echo "cdk_deploy: CDK_DEPLOY_ATTEMPTS is a count from 1, CDK_DEPLOY_RETRY_WAIT seconds" >&2
    return 1
  fi
  err="$(mktemp)"
  while true; do
    status=0
    # Within an || list errexit is off: a failed run is counted here, not the caller's end. Its
    # status is the CLI's, not tee's.
    {
      (unset CDK_DEFAULT_ACCOUNT CDK_DEFAULT_REGION; exec npx cdk deploy "$@") 2>&1 >&3 3>&- \
        | tee "$err" >&2 3>&-
      status="${PIPESTATUS[0]}"
    } 3>&1 || true
    if ((status == 0)) || ! grep -qF "$CDK_UNRESOLVED_ACCOUNT" "$err"; then
      break
    fi
    if ((attempt >= attempts)); then
      echo "cdk_deploy $1: the CDK CLI $CDK_UNRESOLVED_ACCOUNT in $attempts runs; check that they" \
        "answer (aws sts get-caller-identity), then run the step again" >&2
      break
    fi
    echo "cdk_deploy $1: the CDK CLI $CDK_UNRESOLVED_ACCOUNT (run $attempt of $attempts), nothing" \
      "deployed; running it again in ${wait}s (issue #233)" >&2
    sleep "$wait"
    attempt=$((attempt + 1))
  done
  rm -f "$err"
  return "$status"
}
