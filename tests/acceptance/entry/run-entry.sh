#!/usr/bin/env bash
# Acceptance on AWS: the authenticated public entry. Writes PASS/FAIL lines to
# <evidence>/verdicts.tsv and fails if any check fails.
#
# - Without a session nothing is served: every path redirects to Cognito's sign-in, HTTP redirects
#   to HTTPS, TLS below 1.2 is refused, a host the entry does not serve gets a 404, a forged session
#   cookie or authorization code gets nothing, and no one can sign up.
# - Signing in takes the password and a TOTP code; each user gets the roles of their groups, a user
#   without a role is refused, and identity headers a client sends are not trusted.
# - Nothing reaches the console but the load balancer: pods in the cluster cannot connect, and the
#   pod itself refuses forged identity headers (sent through an authenticated port-forward).
# - Sessions end: signing out returns to sign-in and a copied session cookie stops working once
#   its access token expires; so does the session of a user disabled at the user pool, who then
#   cannot sign in; a group change applies within an access token's life; a session ends at the
#   load balancer's session timeout.
# - Sign-ins (and failed ones), requests, refusals and sign-outs are in the audit, by user: the
#   user pool's activity log, the console's audit log group and the load balancer's access logs.
# - The entry is the only thing exposed: one internet-facing load balancer, every rule signing in
#   first; no Service or Ingress in the cluster opens anything else.
#
# usage: run-entry.sh <run-id> <evidence-dir>; needs the entry stack deployed with
# SESSION_TIMEOUT_SECONDS=600 (so a session ends within the run), then install-lbc.sh,
# build-console.sh and install-console.sh from the same commit.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-entry.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-entry.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
here="$REPO_DIR/tests/acceptance/entry"
mkdir -p "$evidence"/{me,http,audit}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
failed=0
step() { echo "$(date -u +%FT%TZ)	$*" | tee -a "$evidence/timeline.tsv"; }
verdict() {
  # One line per verdict: a detail's newlines and tabs become spaces.
  local detail="${3:-}"
  detail="${detail//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${detail//$'\t'/ }" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS ]] || failed=1
}
# check <label> <detail> <command...>: PASS when the command succeeds.
check() {
  local label="$1" detail="$2"
  shift 2
  if "$@"; then verdict "$label" PASS "$detail"; else verdict "$label" FAIL "$detail"; fi
}

pool="$(entry_output UserPoolId)"
client="$(entry_output UserPoolClientId)"
url="$(entry_output consoleUrl)"
signin="$(entry_output SignInDomain)"
timeout="$(entry_output SessionTimeoutSeconds)"
cookie="$(entry_output consoleSessionCookie)"
alb="$(entry_output LoadBalancerArn)"
alb_dns="$(entry_output LoadBalancerDnsName)"
audit_group="$(entry_output ConsoleAuditLogGroup)"
activity_group="$(entry_output UserActivityLogGroup)"
log_bucket="$(entry_output AccessLogBucket)"
if ((timeout > 900)); then
  echo "The session timeout is ${timeout}s; deploy with SESSION_TIMEOUT_SECONDS=600 for the run" >&2
  exit 1
fi
image="$(entry_image console)"
running="$(kubectl -n "$CONSOLE_NAMESPACE" get deployment "$CONSOLE_RELEASE" -o jsonpath='{.spec.template.spec.containers[0].image}')"
[[ "$running" == "$image" ]] || { echo "The console runs $running, not $image of this commit" >&2; exit 1; }
echo "console $image; stack from $(entry_output SourceSha); session timeout ${timeout}s" | tee "$evidence/versions.txt"
started_ms="$(($(date +%s) * 1000))"

state="$(mktemp -d)"
chmod 700 "$state"
urlhost() { python3 -c 'import sys,urllib.parse; print(urllib.parse.urlsplit(sys.argv[1]).hostname)' "$1"; }
urlhost "$signin" >"$state/signin-host"
forward_pid=""
prefix="entry-$run"
export ENTRY_AGENT="$prefix-browser"
declare -A groups=(
  [admin]="colvenna-admin" [op]="colvenna-operator" [bi]="colvenna-bi" [multi]="colvenna-admin colvenna-bi" [none]=""
  [out]="colvenna-operator" [off]="colvenna-bi" [idle]="colvenna-bi" [promo]="colvenna-bi" [late]="colvenna-operator"
)
declare -A expect=(
  [admin]="admin" [op]="operator" [bi]="bi" [multi]="admin,bi" [none]=""
  [out]="operator" [off]="bi" [idle]="bi" [promo]="bi" [late]="operator"
)
# shellcheck disable=SC2317  # run by the trap
cleanup() {
  [[ -n "$forward_pid" ]] && kill "$forward_pid" 2>/dev/null
  for u in "${!groups[@]}"; do
    aws cognito-idp admin-delete-user --user-pool-id "$pool" --username "$prefix-$u" 2>/dev/null || true
  done
  rm -rf "$state"
}
trap cleanup EXIT
entry() { python3 -I "$here/entry.py" "$@"; }
# field <file> <python expression over d, the file's JSON>
field() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))' "$1" "$2"; }

step "users"
for u in "${!groups[@]}"; do
  name="$prefix-$u"
  python3 -c 'import secrets,string; a=string.ascii_letters+string.digits; print("Aa1!" + "".join(secrets.choice(a) for _ in range(24)), end="")' >"$state/$name.pw"
  aws cognito-idp admin-create-user --user-pool-id "$pool" --username "$name" --message-action SUPPRESS >/dev/null
  # The password goes in a file, not on the command line.
  python3 -c 'import json,sys; print(json.dumps({"UserPoolId": sys.argv[1], "Username": sys.argv[2], "Password": open(sys.argv[3]).read(), "Permanent": True}))' \
    "$pool" "$name" "$state/$name.pw" >"$state/$name.input.json"
  aws cognito-idp admin-set-user-password --cli-input-json "file://$state/$name.input.json"
  rm -f "$state/$name.input.json"
  for g in ${groups[$u]}; do
    aws cognito-idp admin-add-user-to-group --user-pool-id "$pool" --username "$name" --group-name "$g"
  done
done

# --- Without a session ---------------------------------------------------------------------------
step "unauthenticated"
# refused_to_sign_in <file>: a redirect to the user pool's authorize endpoint and none of the console.
refused_to_sign_in() {
  python3 - "$1" "$signin" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
ok = d["status"] == 302 and d["location"].startswith(sys.argv[2] + "/oauth2/authorize") and "控制台" not in d["body"]
sys.exit(0 if ok else 1)
PY
}
for path in / /api/me /healthz /static/console.css /logout /signed-out /nowhere; do
  file="$evidence/http/no-session$(tr '/' '_' <<<"$path").json"
  entry get "$url$path" >"$file"
  check "no-session$(tr '/' '-' <<<"${path%/}")" "$(field "$file" '(d["status"], d["location"][:70])')" refused_to_sign_in "$file"
done
code="$(curl -s -o /dev/null -X POST -A "$prefix" -w '%{http_code} %{redirect_url}' "$url/logout")"
check no-session-post-logout "$code" grep -q "^302 $signin/oauth2/authorize" <<<"$code"
code="$(curl -s -o /dev/null -A "$prefix" -w '%{http_code} %{redirect_url}' "http://$(urlhost "$url")/api/me")"
check http-redirects-to-https "$code" test "$code" = "301 $url:443/api/me"
# A TLS 1.1 client (OpenSSL allowed to offer it: SECLEVEL 0) gets the server's protocol_version
# alert, and the listener's policy allows nothing below TLS 1.2.
tls_host="$(urlhost "$url")"
legacy="$(echo | timeout 20 openssl s_client -connect "$tls_host:443" -servername "$tls_host" -tls1_1 \
  -cipher 'DEFAULT:@SECLEVEL=0' 2>&1 || true)"
policy="$(aws elbv2 describe-listeners --load-balancer-arn "$(entry_output LoadBalancerArn)" \
  --query "Listeners[?Port==\`443\`].SslPolicy | [0]" --output text)"
protocols="$(aws elbv2 describe-ssl-policies --names "$policy" --query 'SslPolicies[0].SslProtocols' \
  --output text | tr '\t' ' ')"
check legacy-tls-refused "TLS 1.1 client: $(grep -o 'alert protocol version' <<<"$legacy" | head -1); $policy: $protocols" \
  test -n "$(grep -o 'alert protocol version' <<<"$legacy")" -a "$protocols" = "TLSv1.2 TLSv1.3"
alb_ip="$(getent hosts "$alb_dns" | awk '{print $1; exit}')"
other="$(curl -s -A "$prefix" --resolve "other.$ENTRY_DOMAIN:443:$alb_ip" -w ' %{http_code}' "https://other.$ENTRY_DOMAIN/")"
direct="$(curl -sk -A "$prefix" -w ' %{http_code}' "https://$alb_dns/")"
check unknown-host-gets-nothing "other host: $other; load balancer name: $direct" \
  test "$other|$direct" = "Not found 404|Not found 404"
file="$evidence/http/forged-cookie.json"
entry get "$url/api/me" --header "Cookie:$cookie-0=$(openssl rand -base64 48 | tr -d '\n')" >"$file"
check forged-session-cookie "$(field "$file" 'd["status"]')" refused_to_sign_in "$file"
code="$(curl -s -o /dev/null -A "$prefix" -w '%{http_code}' "$url/oauth2/idpresponse?code=$(uuidgen)&state=forged")"
check forged-authorization-code "$code" test "$code" = 401
code="$(curl -s -o /dev/null -A "$prefix" -w '%{http_code} %{redirect_url}' \
  "$signin/signup?client_id=$client&response_type=code&scope=openid&redirect_uri=$url/oauth2/idpresponse")"
check no-self-sign-up "${code:0:90}" grep -q "^302 $signin/error" <<<"$code"

# --- Signing in -----------------------------------------------------------------------------------
step "sign-in"
cp "$state/$prefix-bi.pw" "$state/$prefix-bi.right"
printf 'Wrong-password-1!' >"$state/$prefix-bi.pw"
entry attempt "$url/" "$state" "$prefix-bi" >"$evidence/http/wrong-password.json"
check wrong-password-refused "$(field "$evidence/http/wrong-password.json" '(d["where"], d["error"])')" \
  test "$(field "$evidence/http/wrong-password.json" 'd["where"]')" = login
mv "$state/$prefix-bi.right" "$state/$prefix-bi.pw"
declare -A signed_in_at=()
for u in admin op bi multi none out off idle promo late; do
  name="$prefix-$u"
  file="$evidence/me/$u.json"
  began="$(date +%s)"
  if entry login "$url/" "$state" "$name" >"$file"; then
    echo "$began $(date +%s)" >"$evidence/me/$u.window"
    signed_in_at[$u]=$SECONDS
    if [[ "$u" == none ]]; then
      # Signed in at the user pool; the console refuses a user without a role.
      check "roles-$u" "no groups -> $(field "$file" 'd["me"].get("status")')" \
        test "$(field "$file" 'd["me"].get("status")')" = 403
    else
      roles="$(field "$file" '",".join(d["me"]["roles"])')"
      check "roles-$u" "groups ${groups[$u]:-none} -> roles $roles" test "$roles" = "${expect[$u]}"
    fi
  else
    verdict "signed-in-$u" FAIL "$(head -c 600 "$file")"
  fi
done
# Password and TOTP set up, a wrong code does not let the user in.
wrong="000000"
[[ "$(python3 -I -c 'import sys; sys.path.insert(0, sys.argv[1]); import entry; print(entry.totp(open(sys.argv[2]).read()))' "$here" "$state/$prefix-admin.totp")" == "$wrong" ]] && wrong="111111"
entry attempt "$url/" "$state" "$prefix-admin" --code "$wrong" >"$evidence/http/wrong-code.json"
check wrong-mfa-code-refused "$(field "$evidence/http/wrong-code.json" '(d["where"], d["error"])')" \
  test "$(field "$evidence/http/wrong-code.json" 'd["where"]')" = mfa
file="$evidence/http/no-role.json"
entry get "$url/" "$state" "$prefix-none" >"$file"
check no-role-refused "$(field "$file" 'd["status"]')" \
  python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d["status"] == 403 and "没有分配角色" in d["body"] else 1)' "$file"
# Identity headers a signed-in client sends itself are replaced by the load balancer's.
forged_claims="$(printf '{"sub":"forged","username":"%s-admin","cognito:groups":["colvenna-admin"]}' "$prefix" | base64 -w0)"
file="$evidence/http/client-headers.json"
entry get "$url/api/me" "$state" "$prefix-op" --header "X-Amzn-Oidc-Data:eyJhbGciOiJub25lIn0.$forged_claims." \
  --header "X-Amzn-Oidc-Accesstoken:eyJhbGciOiJub25lIn0.$forged_claims." --header "X-Amzn-Oidc-Identity:forged" >"$file"
check client-identity-headers-not-trusted "$(field "$file" '(d["status"], d["body"][:120])')" \
  python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); ok=d["status"]==401 or (d["status"]==200 and json.loads(d["body"])["username"]==sys.argv[2]); sys.exit(0 if ok else 1)' "$file" "$prefix-op"

# --- Nothing but the load balancer reaches the console --------------------------------------------
step "bypass"
pod="$(kubectl -n "$CONSOLE_NAMESPACE" get pods -l app.kubernetes.io/name=colvenna-console -o jsonpath='{.items[0].metadata.name}')"
pod_ip="$(kubectl -n "$CONSOLE_NAMESPACE" get pod "$pod" -o jsonpath='{.status.podIP}')"
cat >"$state/probe.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: entry-probe-$run, namespace: $JOB_NAMESPACE}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 100, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: curl
      image: $CURL_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: [ALL]}}
      command: ["sh", "-c"]
      args:
        - |
          for target in https://$pod_ip:8443/healthz https://$CONSOLE_RELEASE.$CONSOLE_NAMESPACE.svc:8443/healthz \\
                        https://colvenna-catalog.$CATALOG_NAMESPACE.svc:8181/v1/config; do
            out="\$(curl -sk -o /dev/null -m 10 -w '%{http_code} %{time_connect}' "\$target")"
            echo "\$target \$out \$?"
          done
      resources: {requests: {cpu: 10m, memory: 32Mi}, limits: {memory: 64Mi}}
YAML
kubectl apply -f "$state/probe.yaml" >/dev/null
kubectl -n "$JOB_NAMESPACE" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/entry-probe-$run" --timeout=3m >/dev/null || true
kubectl -n "$JOB_NAMESPACE" logs "entry-probe-$run" >"$evidence/http/in-cluster.txt" 2>&1 || true
kubectl -n "$JOB_NAMESPACE" delete pod "entry-probe-$run" --ignore-not-found >/dev/null
check pods-unreachable-in-cluster "$(tr '\n' ';' <"$evidence/http/in-cluster.txt")" python3 - "$evidence/http/in-cluster.txt" <<'PY'
import sys
lines = [l.split() for l in open(sys.argv[1]) if l.strip()]
# The console, by pod and by service: no HTTP status, no connection made, curl timed out (28).
# The control, colvenna-catalog, which this namespace may reach: it answers (401, no token).
ok = (len(lines) == 3 and all(l[1:] == ["000", "0.000000", "28"] for l in lines[:2])
      and lines[2][1] == "401" and lines[2][3] == "0")
sys.exit(0 if ok else 1)
PY
# Through the API server (IAM, RBAC), past the network policy: the pod still wants the tokens.
forward_log="$(mktemp)"
kubectl -n "$CONSOLE_NAMESPACE" port-forward "pod/$pod" :8443 >"$forward_log" 2>&1 &
forward_pid=$!
pod_port="$(forwarded_port "$forward_log")"
none_alg="$(printf '{"alg":"none","kid":"%s","signer":"%s","iss":"https://cognito-idp.%s.amazonaws.com/%s","client":"%s","exp":%d}' \
  "$(uuidgen)" "$alb" "$AWS_REGION" "$pool" "$client" "$(($(date +%s) + 300))" | base64 -w0 | tr '+/' '-_')"
fake_es="$(printf '{"alg":"ES256","kid":"%s","signer":"%s","iss":"https://cognito-idp.%s.amazonaws.com/%s","client":"%s","exp":%d}' \
  "$(uuidgen)" "$alb" "$AWS_REGION" "$pool" "$client" "$(($(date +%s) + 300))" | base64 -w0 | tr '+/' '-_')"
claims="$(printf '{"sub":"forged","username":"%s-admin"}' "$prefix" | base64 -w0 | tr '+/' '-_')"
signature="$(openssl rand 64 | base64 -w0 | tr '+/' '-_')"
results=""
for headers in "" "$none_alg.$claims." "$fake_es.$claims.$signature"; do
  code="$(curl -sk -o /dev/null -A "$prefix-forged" -w '%{http_code}' \
    ${headers:+-H "X-Amzn-Oidc-Data: $headers" -H "X-Amzn-Oidc-Accesstoken: $headers"} "https://127.0.0.1:$pod_port/api/me")"
  results+="$code "
done
health="$(curl -sk -o /dev/null -w '%{http_code}' "https://127.0.0.1:$pod_port/healthz")"
kill "$forward_pid" 2>/dev/null
forward_pid=""
check pod-refuses-forged-identity "none, alg none, unknown key: $results; health $health" test "$results|$health" = "401 401 401 |200"

# --- Sessions end ---------------------------------------------------------------------------------
step "sessions"
# signed_out_ok <logout-file>: back at the sign-in page, through the sign-out page and the user
# pool's sign-out.
# shellcheck disable=SC2317  # run by check
signed_out_ok() {
  python3 - "$1" "$signin" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
chain = [u for _, u in d["chain"]]
ok = (d["loginForm"] and d["final"].startswith(sys.argv[2] + "/login") and any(u.endswith("/signed-out") for u in chain)
      and any(u.startswith(sys.argv[2] + "/logout") for u in chain))
sys.exit(0 if ok else 1)
PY
}
cp "$state/$prefix-out.cookies" "$state/$prefix-copy.cookies"
entry logout "$url/" "$state" "$prefix-out" >"$evidence/http/logout.json"
check logout-returns-to-sign-in "$(field "$evidence/http/logout.json" '(d["final"][:60], d["loginForm"])')" \
  signed_out_ok "$evidence/http/logout.json"
file="$evidence/http/after-logout.json"
entry get "$url/api/me" "$state" "$prefix-out" >"$file"
check logout-drops-the-session "$(field "$file" 'd["status"]')" refused_to_sign_in "$file"
ended=$SECONDS
aws cognito-idp admin-disable-user --user-pool-id "$pool" --username "$prefix-off"
aws cognito-idp admin-add-user-to-group --user-pool-id "$pool" --username "$prefix-promo" --group-name colvenna-operator
step "logged out $prefix-out, disabled $prefix-off, made $prefix-promo an operator"
# The load balancer refreshes the access token (five minutes) with the refresh token, which the
# sign-out, the disabling and the group change all act on: each shows within one token's life.
# A token issued before the change expires within 300 s of it; one poll (20 s) and the request
# itself come on top.
token_life=300
limit=$((token_life + 30))
# Every 20 s until each of five probes is done: a copied cookie of the signed-out user, the
# disabled user's session and the promoted user's roles (each in seconds after the change); the
# late user's sign-out once its access token has expired, and the idle user's session at the
# session timeout (each in seconds after its sign-in).
declare -A done_at=()
probes=5
deadline=$((SECONDS + timeout + 300))
while ((${#done_at[@]} < probes && SECONDS < deadline)); do
  if [[ -z "${done_at[copy]:-}" ]]; then
    entry get "$url/api/me" "$state" "$prefix-copy" --only-entry-cookies >"$state/copy.json"
    refused_to_sign_in "$state/copy.json" && done_at[copy]=$((SECONDS - ended))
  fi
  if [[ -z "${done_at[off]:-}" ]]; then
    entry get "$url/api/me" "$state" "$prefix-off" >"$state/off.json"
    refused_to_sign_in "$state/off.json" && done_at[off]=$((SECONDS - ended))
  fi
  if [[ -z "${done_at[promo]:-}" ]]; then
    entry get "$url/api/me" "$state" "$prefix-promo" >"$state/promo.json"
    [[ "$(field "$state/promo.json" 'json.loads(d["body"]).get("roles") if d["status"] == 200 else None')" == "['operator', 'bi']" ]] \
      && done_at[promo]=$((SECONDS - ended))
  fi
  # Signing out once the access token has expired (and before the session's own timeout): the
  # load balancer refreshes the session on the sign-out request itself, and the session ends.
  if [[ -z "${signed_in_at[late]:-}" ]]; then
    done_at[late]="no sign-in"
  elif [[ -z "${done_at[late]:-}" ]] && ((SECONDS - signed_in_at[late] > limit)); then
    done_at[late]=$((SECONDS - signed_in_at[late]))
    entry logout "$url/" "$state" "$prefix-late" >"$evidence/http/logout-late.json" || true
    entry get "$url/api/me" "$state" "$prefix-late" >"$evidence/http/after-logout-late.json"
  fi
  if [[ -z "${done_at[idle]:-}" ]]; then
    entry get "$url/api/me" "$state" "$prefix-idle" >"$state/idle.json"
    if refused_to_sign_in "$state/idle.json"; then
      done_at[idle]=$((SECONDS - signed_in_at[idle]))
    elif [[ "$(field "$state/idle.json" 'd["status"]')" != 200 ]]; then
      done_at[idle]="error $(field "$state/idle.json" 'd["status"]')"
    fi
  fi
  ((${#done_at[@]} < probes)) && sleep 20
done
# shellcheck disable=SC2317  # run by check
within() { [[ "${1:-}" =~ ^[0-9]+$ ]] && (($1 >= $2 && $1 <= $3)); }
check copied-session-ends-after-logout "${done_at[copy]:-never}s after sign-out (limit ${limit}s)" within "${done_at[copy]:-}" 0 "$limit"
check disabled-user-session-ends "${done_at[off]:-never}s after disabling (limit ${limit}s)" within "${done_at[off]:-}" 0 "$limit"
check group-change-applies "${done_at[promo]:-never}s after the change (limit ${limit}s)" within "${done_at[promo]:-}" 0 "$limit"
idle="${done_at[idle]:-never}"
check session-times-out "${idle}s after sign-in, timeout ${timeout}s" \
  within "$idle" $((timeout - 60)) $((timeout + 120))
attempted_disabled="$(date -u +%FT%TZ)"
entry attempt "$url/" "$state" "$prefix-off" >"$evidence/http/disabled-sign-in.json" || true
late_age="${done_at[late]:-never}"
# shellcheck disable=SC2317  # run by check
late_logout_ok() { within "$late_age" $((token_life + 1)) $((timeout - 1)) && signed_out_ok "$evidence/http/logout-late.json"; }
check logout-after-the-token-expired "${late_age}s after sign-in, back at the sign-in page" late_logout_ok
check logout-after-the-token-expired-drops-the-session "$(field "$evidence/http/after-logout-late.json" 'd["status"]' 2>/dev/null || echo never)" \
  refused_to_sign_in "$evidence/http/after-logout-late.json"
check disabled-user-cannot-sign-in "$(field "$evidence/http/disabled-sign-in.json" '(d["where"], d["error"])')" \
  python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d["where"] == "login" and "disabled" in d["error"].lower() else 1)' \
  "$evidence/http/disabled-sign-in.json"

# --- Audit ----------------------------------------------------------------------------------------
step "audit"
# logs_of <group> <file>: the group's events naming the run's users or agent, since the run began.
logs_of() {
  aws logs filter-log-events --log-group-name "$1" --start-time "$started_ms" \
    --filter-pattern "\"$prefix\"" --query 'events[].message' --output json >"$2"
}
# trail_of <file>: the user pool's CloudTrail events from the disabled user's sign-in attempt on.
trail_of() {
  aws cloudtrail lookup-events --lookup-attributes AttributeKey=EventSource,AttributeValue=cognito-idp.amazonaws.com \
    --start-time "$attempted_disabled" --query 'Events[].CloudTrailEvent' --output json >"$1"
}
# Delivery is minutes behind: up to 15 for the user pool's activity log and CloudTrail.
for _ in $(seq 1 30); do
  logs_of "$audit_group" "$evidence/audit/console.json"
  logs_of "$activity_group" "$evidence/audit/user-activity.json"
  trail_of "$evidence/audit/cloudtrail.json"
  if python3 - "$evidence/audit/console.json" "$evidence/audit/user-activity.json" "$evidence/audit/cloudtrail.json" "$prefix" <<'PY'
import json, sys
console = [json.loads(m) for m in json.load(open(sys.argv[1]))]
activity = [json.loads(m)["message"] for m in json.load(open(sys.argv[2]))]
trail = [json.loads(e) for e in json.load(open(sys.argv[3]))]
signed = {m["userName"] for m in activity if m.get("eventType") == "SignIn"}
refused = any(e["eventName"] == "Login_Error_POST" for e in trail)
# The disabled user's attempt reaches the activity log too (its second sign-in event).
attempts = [m for m in activity if m.get("eventType") == "SignIn" and m.get("userName") == sys.argv[4] + "-off"]
attempt_logged = refused and len(attempts) >= 2
sys.exit(0 if any(e["kind"] == "logout" for e in console) and len(signed) >= 10 and attempt_logged else 1)
PY
  then break; fi
  sleep 30
done
python3 - "$evidence/audit" "$prefix" "$evidence/me" "$attempted_disabled" >"$evidence/audit/verdicts.txt" <<'PY'
import datetime, glob, json, os, sys
audit, prefix, me_dir, attempted = sys.argv[1:5]
console = [json.loads(m) for m in json.load(open(os.path.join(audit, "console.json")))]
activity = [json.loads(m)["message"] for m in json.load(open(os.path.join(audit, "user-activity.json")))]
trail = [json.loads(e) for e in json.load(open(os.path.join(audit, "cloudtrail.json")))]
users = ("admin", "op", "bi", "multi", "none", "out", "off", "idle", "promo", "late")
def say(label, ok, detail):
    print("%s\t%s\t%s" % (label, "PASS" if ok else "FAIL", detail))
def epoch(text):
    return datetime.datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()
me, window = {}, {}
for u in users:
    d = json.load(open(os.path.join(me_dir, u + ".json")))
    if isinstance(d.get("me"), dict) and "sub" in d["me"]:
        me[u] = d["me"]
    w = os.path.join(me_dir, u + ".window")
    if os.path.exists(w):
        window[u] = [int(x) for x in open(w).read().split()]
# Each user's sign-in: a signin event of that user whose sign-in time falls in the sign-in's window.
signed = [u for u in users if u in window and any(
    e["kind"] == "signin" and e.get("username") == prefix + "-" + u and e.get("signedIn")
    and window[u][0] - 5 <= epoch(e["signedIn"]) <= window[u][1] + 5 for e in console)]
say("audit-sign-ins-by-user", len(signed) == len(users), "%d of %d users" % (len(signed), len(users)))
# Each user with a role: requests under their sub and sign-in time.
with_role = [u for u in me if me[u]["roles"]]
seen = [u for u in with_role if any(e["kind"] == "request" and e.get("sub") == me[u]["sub"]
                                    and e.get("signedIn") == me[u]["signedIn"].replace(".000", "") for e in console)]
say("audit-requests-by-user", len(seen) == len(with_role), "%d of %d users" % (len(seen), len(with_role)))
denied = [e for e in console if e["kind"] == "denied" and e.get("username") == prefix + "-none" and e.get("reason") == "no role"]
say("audit-no-role-refusal", bool(denied), "%d refusals of %s-none" % (len(denied), prefix))
forged = [e for e in console if e["kind"] == "denied" and e.get("userAgent") == prefix + "-forged" and e["status"] == 401]
say("audit-forged-refusals", len(forged) == 3, "; ".join(e["reason"][:60] for e in forged))
out = [e for e in console if e["kind"] == "logout" and e.get("username") == prefix + "-out"]
say("audit-logout", any(e["reason"] == "signed out at the user pool" for e in out), "%d logouts" % len(out))
# The user pool logs a sign-in when the password step ends: InProgress with Password:Success while
# the MFA step follows, Fail with the step that failed.
def steps(user, response):
    return [m for m in activity if m.get("eventType") == "SignIn"
            and m.get("userName") == prefix + "-" + user and m.get("eventResponse") == response]
passed = [u for u in users if any("Password:Success" in m["challenges"] for m in steps(u, "InProgress"))]
say("user-activity-sign-ins", len(passed) == len(users), "%d of %d users" % (len(passed), len(users)))
wrong_password = any("Password:Failure" in m["challenges"] for m in steps("bi", "Fail"))
wrong_code = any("Mfa:Failure" in m["challenges"] for m in steps("admin", "Fail"))
say("user-activity-failed-sign-ins", wrong_password and wrong_code,
    "bi: %s; admin: %s" % ([m["challenges"] for m in steps("bi", "Fail")], [m["challenges"] for m in steps("admin", "Fail")]))
# A disabled user's attempt: the user pool logs the password step (InProgress) and CloudTrail the
# refusal ("User is disabled.", no user name): the two meet by time and address.
t0 = epoch(attempted)
mine = [m for m in steps("off", "InProgress") if t0 - 5 <= datetime.datetime.strptime(
    m["creationDate"], "%a %b %d %H:%M:%S UTC %Y").replace(tzinfo=datetime.timezone.utc).timestamp() <= t0 + 60]
refusals = [e for e in trail if e["eventName"] == "Login_Error_POST" and e.get("errorMessage") == "User is disabled."
            and t0 - 5 <= epoch(e["eventTime"]) <= t0 + 60]
same = [(m, e) for m in mine for e in refusals if m.get("ipAddress") == e.get("sourceIPAddress")
        and abs(epoch(e["eventTime"]) - datetime.datetime.strptime(m["creationDate"], "%a %b %d %H:%M:%S UTC %Y")
                .replace(tzinfo=datetime.timezone.utc).timestamp()) <= 10]
say("disabled-sign-in-traced", bool(same), "%d password steps of %s-off, %d refusals, %d matched by time and address"
    % (len(mine), prefix, len(refusals), len(same)))
PY
while IFS=$'\t' read -r label result detail; do verdict "$label" "$result" "$detail"; done <"$evidence/audit/verdicts.txt"
# The load balancer's access logs (every five minutes): the requests it refused, by the run's agent.
# The run may cross midnight (UTC): the logs of the day it began and of today.
for day in $(printf '%s\n' "$(date -u -d "@$((started_ms / 1000))" +%Y/%m/%d)" "$(date -u +%Y/%m/%d)" | sort -u); do
  aws s3 cp --recursive --quiet "s3://$log_bucket/entry/AWSLogs/$(aws sts get-caller-identity --query Account --output text)/elasticloadbalancing/$AWS_REGION/$day/" "$state/alb/"
done
zcat "$state"/alb/*.gz 2>/dev/null | grep -F "$prefix" >"$evidence/audit/alb.log" || true
check alb-logs-refusals "$(wc -l <"$evidence/audit/alb.log") lines of the run's agent" python3 - "$evidence/audit/alb.log" <<'PY'
import shlex, sys
rows = [shlex.split(l) for l in open(sys.argv[1]) if l.strip()]
# Fields: type time elb client target request_processing target_processing response_processing
# elb_status target_status ... "request" "user_agent" ... actions_executed ... error_reason ...
codes = {(r[8], r[4]) for r in rows}
redirected = any(r[8] == "302" and r[4] == "-" for r in rows)
unauthorized = any(r[8] == "401" and r[4] == "-" for r in rows)
sys.exit(0 if redirected and unauthorized else 1)
PY

# --- Exposure -------------------------------------------------------------------------------------
step "exposure"
vpc="$(stack_output "$FOUNDATION_STACK" VpcId)"
aws elbv2 describe-load-balancers --query "LoadBalancers[?VpcId=='$vpc'].[LoadBalancerArn,Scheme,Type]" \
  --output json >"$evidence/exposure-lbs.json"
aws elbv2 describe-listeners --load-balancer-arn "$alb" --output json >"$evidence/exposure-listeners.json"
https_listener="$(field "$evidence/exposure-listeners.json" '[l["ListenerArn"] for l in d["Listeners"] if l["Port"] == 443][0]')"
aws elbv2 describe-rules --listener-arn "$https_listener" --output json >"$evidence/exposure-rules.json"
kubectl get services -A -o json | python3 -c 'import json,sys; print(json.dumps([s["metadata"]["namespace"]+"/"+s["metadata"]["name"]+" "+s["spec"]["type"] for s in json.load(sys.stdin)["items"] if s["spec"]["type"] in ("LoadBalancer","NodePort")]))' >"$evidence/exposure-services.json"
kubectl get ingress -A -o json | python3 -c 'import json,sys; print(json.dumps([i["metadata"]["namespace"]+"/"+i["metadata"]["name"] for i in json.load(sys.stdin)["items"]]))' >"$evidence/exposure-ingresses.json"
check exposure-only-the-entry "$(tr -d '\n' <"$evidence/exposure-services.json") $(tr -d '\n' <"$evidence/exposure-ingresses.json")" \
  python3 - "$evidence" "$alb" <<'PY'
import json, os, sys
e, alb = sys.argv[1], sys.argv[2]
lbs = json.load(open(os.path.join(e, "exposure-lbs.json")))
public = [a for a, scheme, _ in lbs if scheme == "internet-facing"]
listeners = {l["Port"]: l for l in json.load(open(os.path.join(e, "exposure-listeners.json")))["Listeners"]}
rules = json.load(open(os.path.join(e, "exposure-rules.json")))["Rules"]
def kinds(actions):
    return [a["Type"] for a in sorted(actions, key=lambda a: a.get("Order", 0))]
ok = (public == [alb] and sorted(listeners) == [80, 443]
      and kinds(listeners[80]["DefaultActions"]) == ["redirect"]
      and all(kinds(r["Actions"]) == (["fixed-response"] if r["IsDefault"] else ["authenticate-cognito", "forward"]) for r in rules)
      and json.load(open(os.path.join(e, "exposure-services.json"))) == []
      and json.load(open(os.path.join(e, "exposure-ingresses.json"))) == [])
sys.exit(0 if ok else 1)
PY

# The table's other rows (HLD 12.1): the EKS endpoint open only to the operators' address, no
# database in the VPC public, every bucket of the platform blocking public access.
cluster="$(stack_output "$FOUNDATION_STACK" ClusterName)"
aws eks describe-cluster --name "$cluster" --query 'cluster.resourcesVpcConfig.{public:endpointPublicAccess,cidrs:publicAccessCidrs}' \
  --output json >"$evidence/exposure-eks.json"
aws rds describe-db-instances --query "DBInstances[?DBSubnetGroup.VpcId=='$vpc'].[DBInstanceIdentifier,PubliclyAccessible]" \
  --output json >"$evidence/exposure-rds.json"
account="$(aws sts get-caller-identity --query Account --output text)"
aws s3control get-public-access-block --account-id "$account" --query PublicAccessBlockConfiguration \
  --output json >"$state/bpa-account.json"
aws s3api get-public-access-block --bucket "$(stack_output "$FOUNDATION_STACK" WarehouseBucket)" \
  --query PublicAccessBlockConfiguration --output json >"$state/bpa-warehouse.json"
aws s3api get-public-access-block --bucket "$log_bucket" --query PublicAccessBlockConfiguration \
  --output json >"$state/bpa-access-logs.json"
python3 -c 'import json,sys; print(json.dumps({f.split("bpa-")[1][:-5]: json.load(open(f)) for f in sys.argv[1:]}))' \
  "$state"/bpa-*.json >"$evidence/exposure-s3.json"
check exposure-other-rows "eks $(tr -d ' \n' <"$evidence/exposure-eks.json"); rds $(tr -d ' \n' <"$evidence/exposure-rds.json")" \
  python3 - "$evidence" <<'PY'
import json, os, sys
e = sys.argv[1]
eks = json.load(open(os.path.join(e, "exposure-eks.json")))
rds = json.load(open(os.path.join(e, "exposure-rds.json")))
s3 = json.load(open(os.path.join(e, "exposure-s3.json")))
ok = ("0.0.0.0/0" not in eks["cidrs"] and all(c.endswith("/32") for c in eks["cidrs"])
      and rds and not any(public for _, public in rds)
      and len(s3) == 3 and all(all(b.values()) and len(b) == 4 for b in s3.values()))
sys.exit(0 if ok else 1)
PY

step "done"
echo "verdicts: $(grep -c PASS "$evidence/verdicts.tsv") PASS, $(grep -c FAIL "$evidence/verdicts.tsv") FAIL"
exit "$failed"
