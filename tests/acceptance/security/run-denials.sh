#!/usr/bin/env bash
# The denial evidence of the release check (#208): for every resource of the environment that the
# inventory (inventory.sh, same evidence directory) found reachable from the internet, requests that
# must be refused in three classes, each check a line of <evidence>/verdicts.tsv:
#   - unauthenticated: no session, a forged session cookie or identity headers, no token or a forged
#     one, anonymous S3 and registry requests, connections to the NAT and spare addresses (on the
#     ports a positive control shows this host can connect on);
#   - unauthorized: signed in, but without the application's role (and forging the role's headers
#     with that session); a Kubernetes identity without RBAC on the public EKS endpoint; a wrong
#     password; the console's service account, whose role has no S3 or ECR permission, listing each
#     bucket of the environment and an image repository;
#   - around the entry: the load balancer's own name, another host name, plain HTTP, TLS 1.1, the
#     EKS endpoint from an address it does not admit, S3 over plain HTTP and its website endpoint,
#     Cognito's sign-up page, and its API with the client's secret but without the hosted sign-in
#     (sign-up, the password flow). The applications' pod addresses are tried from this host for the
#     log only: the UAT's in-cluster checks are their evidence.
# Then exposure.py matrix pairs each resource and class with its evidence: these checks, the
# inventory's, and the UAT's own negative checks (UAT-ENT-01, UAT-BI-01, UAT-CAT-05) when the UAT's
# evidence root of the same environment and candidate is given; a class without evidence fails.
# exposure.py section writes the release check's section of the environment, <evidence>/section.md;
# last, exposure.py scrub and exact-scan check that no credential reached any file of the evidence.
#
# usage: COLVENNA_ENV=<env> [DENIALS_REQUESTS_ONLY=1] tests/acceptance/security/run-denials.sh <run-id> <evidence-dir> [<uat-evidence-root>]
# The evidence directory is the one inventory.sh just wrote, from the same commit: a directory with
# an earlier denial run, or verdict rows of another run, is refused (exposure.py preflight).
# Makes, and removes on exit: Cognito users sec-<run>-none (no group) and sec-<run>-op
# (colvenna-operator); namespace colvenna-sec-<run> with a service account and a probe pod; the pod
# sec-<run>-underprivileged in the console's namespace. The exit also deletes, whatever the mode,
# what a wrongly accepted refusal would have left: the user sec-<run>-signup and the anonymous object
# security-probe/<run> of each bucket. Passwords, TOTP secrets, cookies, the client secret and the
# service account's token stay in a private temporary directory, never in the evidence.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-denials.sh <run-id> <evidence-dir> [<uat-evidence-root>]}"
evidence="${2:?usage: run-denials.sh <run-id> <evidence-dir> [<uat-evidence-root>]}"
uat_root="${3:-}"
# DENIALS_REQUESTS_ONLY=1: only requests, nothing made on the environment (no users, no namespace,
# no pod); the checks that need them are not run, and their classes stay MISSING in the matrix.
requests_only=false
if [[ "${DENIALS_REQUESTS_ONLY:-}" == 1 ]]; then
  requests_only=true
fi
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
here="$(cd "$(dirname "$0")" && pwd)"
entry_dir="$REPO_DIR/tests/acceptance/entry"
scripts_sha="$(committed_sha)"
# This environment's inventory, of these scripts' commit, and no other run's evidence.
python3 -I "$here/exposure.py" preflight "$evidence" "$COLVENNA_ENV" "$scripts_sha"
if [[ -n "$uat_root" && ! -s "$uat_root/summary.tsv" ]]; then
  echo "No summary.tsv in $uat_root" >&2
  exit 1
fi
mkdir -p "$evidence/denials"
exec > >(tee -a "$evidence/denials.log") 2>&1
echo "$(date -u +%FT%TZ) denials $run of $COLVENNA_ENV in $COLVENNA_REGION at $scripts_sha by" \
  "$(aws sts get-caller-identity --query Arn --output text); UAT evidence: ${uat_root:-none}"
failed=0
# verdict <check> <PASS|FAIL|N/A> <detail>: a row of this run. N/A (with its reason) fails nothing.
verdict() {
  local detail="${3:-}"
  detail="${detail//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${detail//$'\t'/ }	$run" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS || "$2" == N/A ]] || failed=1
}
check() {
  local label="$1" detail="$2"
  shift 2
  if "$@"; then verdict "$label" PASS "$detail"; else verdict "$label" FAIL "$detail"; fi
}
# record <file>: the "<check>\t<result>\t<detail>" lines exposure.py printed, as verdicts.
record() {
  local label result detail
  while IFS=$'\t' read -r label result detail; do verdict "$label" "$result" "$detail"; done <"$1"
}
# The types of credential this run made or read, for the exact scan (exposure.py SECRET_TYPES).
made=()
# env <key>: a value of the inventory's raw/env.json.
env_of() { python3 -c 'import json,sys; v=json.load(open(sys.argv[1]))[sys.argv[2]]; print(" ".join(v) if isinstance(v, list) else v)' "$evidence/raw/env.json" "$1"; }
# rows <kind>: the ids of the inventory's rows of one kind that the environment owns.
rows() { awk -F'\t' -v k="$1" '$1 == k && $3 == "env" {print $2}' "$evidence/inventory.tsv"; }
# http <probe> <curl arguments...>: "<status> <redirect>" of one request; a row of denials/http.tsv
# (the URL without its query, never a header).
http() {
  local probe="$1" out
  shift
  out="$(curl -s -o /dev/null -m 20 -A "colvenna-sec-$run" -w '%{http_code} %{redirect_url}' "$@" || true)"
  printf '%s\t%s\t%s\n' "$probe" "$(sed -E 's/\?.*//' <<<"${*: -1}")" "${out:0:160}" >>"$evidence/denials/http.tsv"
  echo "$out"
}
# no_connection <host> <port>: true when nothing accepts a TCP connection within 5 s.
no_connection() { ! timeout 5 bash -c "exec 3<>/dev/tcp/$1/$2" 2>/dev/null; }

state="$(mktemp -d)"
chmod 700 "$state"
namespace="colvenna-sec-$run"
pool="$(env_of pool)"
signin="$(env_of signin)"
client="$(env_of client)"
prefix="sec-$run"
put_key="security-probe/$run"
probe_namespace="$CONSOLE_NAMESPACE"
probe_pod="$prefix-underprivileged"
# Every mode: deleting a name of this run that does not exist is harmless, and a refusal wrongly
# accepted even with DENIALS_REQUESTS_ONLY=1 (the sign-up user, the anonymous object) is removed.
# shellcheck disable=SC2317  # run by the trap
cleanup() {
  for u in none op signup; do
    aws cognito-idp admin-delete-user --user-pool-id "$pool" --username "$prefix-$u" 2>/dev/null || true
  done
  kubectl -n "$probe_namespace" delete pod "$probe_pod" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kubectl delete namespace "$namespace" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  for b in $(rows s3-bucket); do
    aws s3api delete-object --bucket "$b" --key "$put_key" >/dev/null 2>&1 || true
  done
  rm -rf "$state"
}
trap cleanup EXIT
export ENTRY_AGENT="colvenna-sec-$run"
entry() { python3 -I "$entry_dir/entry.py" "$@"; }
field() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))' "$1" "$2"; }
python3 -c 'import sys,urllib.parse; print(urllib.parse.urlsplit(sys.argv[1]).hostname)' "$signin" >"$state/signin-host"
apps="$(env_of apps)"
first_app="${apps%% *}"
domain="$(env_of entry_domain)"
alb_dns="$(env_of alb_dns)"
alb_ip="$(getent ahostsv4 "$alb_dns" | awk '{print $1; exit}')"
# A forged identity, as the load balancer would pass it: unsigned (alg none), of an administrator.
forged="eyJhbGciOiJub25lIn0.$(printf '{"sub":"forged","username":"%s-admin","cognito:groups":["colvenna-admin","colvenna-bi"]}' "$prefix" | base64 -w0 | tr '+/' '-_' | tr -d '=')."
forged_headers=(-H "X-Amzn-Oidc-Data: $forged" -H "X-Amzn-Oidc-Accesstoken: $forged" -H "X-Amzn-Oidc-Identity: forged")
# to_sign_in <"status redirect">...: every answer a 302 to this environment's Cognito sign-in.
# shellcheck disable=SC2317  # run through check
to_sign_in() {
  local a
  for a in "$@"; do [[ "$a" == "302 $signin/oauth2/authorize?"* ]] || return 1; done
}

# --- Unauthenticated -------------------------------------------------------------------------------
echo "$(date -u +%FT%TZ) unauthenticated"
declare -A api_path=([console]=/api/me [bi]=/api/v1/database/)
for app in $apps; do
  url="$(entry_output "${app}Url")"
  cookie="$(entry_output "${app}SessionCookie")"
  a="$(http "$app-root" "$url/")" b="$(http "$app-api" "$url${api_path[$app]}")"
  check "deny-$app-no-session" "/: ${a:0:60}; ${api_path[$app]}: ${b:0:60}" to_sign_in "$a" "$b"
  a="$(http "$app-forged-cookie" -H "Cookie: $cookie-0=$(openssl rand -base64 48 | tr -d '\n=')" "$url${api_path[$app]}")"
  check "deny-$app-forged-session-cookie" "${a:0:60}" to_sign_in "$a"
  a="$(http "$app-forged-headers" "${forged_headers[@]}" "$url${api_path[$app]}")"
  check "deny-$app-forged-identity-headers" "unsigned administrator headers: ${a:0:60}" to_sign_in "$a"
done
# refused_with <code> <body file> <what the body must say>: a 400 or 401 that says it.
# shellcheck disable=SC2317  # run through check
refused_with() { [[ "$1" == 400 || "$1" == 401 ]] && grep -q "$3" "$2"; }
code="$(curl -s -m 20 -o "$state/userinfo" -w '%{http_code}' "$signin/oauth2/userInfo" || true)"
check deny-cognito-userinfo-without-token "$code $(head -c 100 "$state/userinfo")" \
  refused_with "$code" "$state/userinfo" '"error"'
code="$(curl -s -m 20 -o "$state/token" -w '%{http_code}' -X POST \
  -d "grant_type=client_credentials&client_id=$client" "$signin/oauth2/token" || true)"
# Only the error's name: an answer that is no JSON object records none.
python3 -c '
import json, sys
text = open(sys.argv[1]).read().strip()
print(json.dumps({"error": json.loads(text).get("error") if text.startswith("{") else None}))' \
  "$state/token" >"$evidence/denials/cognito-token-without-secret.json"
check deny-cognito-token-without-secret "$code $(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])))' "$evidence/denials/cognito-token-without-secret.json")" \
  refused_with "$code" "$evidence/denials/cognito-token-without-secret.json" invalid_client

cluster="$(env_of cluster)"
endpoint="$(aws eks describe-cluster --name "$cluster" --query cluster.endpoint --output text)"
codes=""
for path in /version /api /api/v1/namespaces/kube-system/secrets; do
  codes+="$path $(curl -sk -m 20 -o /dev/null -w '%{http_code}' "$endpoint$path" || true); "
done
check deny-eks-anonymous "$codes" test "$(grep -o ' 401;' <<<"$codes" | wc -l)" = 3
fake="k8s-aws-v1.$(printf 'https://sts.%s.amazonaws.com/?Action=GetCallerIdentity&Version=2011-06-15&X-Amz-Signature=forged' "$COLVENNA_REGION" | base64 -w0 | tr '+/' '-_' | tr -d '=')"
printf 'Authorization: Bearer %s\n' "$fake" >"$state/forged-eks"
code="$(curl -sk -m 20 -o /dev/null -w '%{http_code}' -H @"$state/forged-eks" "$endpoint/api/v1/namespaces" || true)"
check deny-eks-forged-token "a token with a forged signature: $code" test "$code" = 401

for b in $(rows s3-bucket); do
  a="$(http "s3-$b-list" "https://$b.s3.$COLVENNA_REGION.amazonaws.com/?list-type=2&max-keys=1")"
  check "deny-s3-$b-anonymous-list" "${a:0:40}" test "${a%% *}" = 403
  a="$(http "s3-$b-put" -X PUT --data-binary probe "https://$b.s3.$COLVENNA_REGION.amazonaws.com/$put_key")"
  check "deny-s3-$b-anonymous-put" "${a:0:40}" test "${a%% *}" = 403
done

registry="${ECR_REGISTRY}"
repo="$(rows ecr-repository | head -1)"
a="$(http ecr-v2 "https://$registry/v2/")" b="$(http ecr-tags "https://$registry/v2/$repo/tags/list")"
# A forged registry password: ECR answers 400 DENIED "Your Authorization Token is invalid."
c="$(curl -s -m 20 -o "$state/ecr-forged" -w '%{http_code}' -u "AWS:forged" "https://$registry/v2/$repo/tags/list" || true)"
check deny-ecr-anonymous "/v2/: ${a%% *}; $repo tags: ${b%% *}; forged password: $c $(head -c 90 "$state/ecr-forged")" \
  test "${a%% *} ${b%% *} $c $(grep -c '"DENIED"' "$state/ecr-forged")" = "401 401 400 1"

# The NAT gateway's and the spare Elastic IP's addresses (exposure.is_nat_or_spare's notes) accept no
# connection. Only ports with a positive control are probed: the control shows this host's own
# connections on each of them get out, so a refusal is the address's.
probe_ports=(80 443)
control_host=checkip.amazonaws.com
unreached=""
for port in "${probe_ports[@]}"; do
  if no_connection "$control_host" "$port"; then unreached+="$port "; fi
done
check deny-address-positive-control \
  "this host connects to $control_host on ${probe_ports[*]}${unreached:+, except on $unreached}" test -z "$unreached"
while IFS=$'\t' read -r ip note; do
  ports=""
  for port in "${probe_ports[@]}"; do
    no_connection "$ip" "$port" || ports+="$port "
  done
  check "deny-address-$ip-no-inbound" "$note; accepted on: ${ports:-none of ${probe_ports[*]}} (control: deny-address-positive-control)" \
    test -z "$ports"
done < <(awk -F'\t' '$1 == "address" && $3 == "env" && $7 ~ /^(NAT|unassociated)/ {print $2 "\t" $7}' "$evidence/inventory.tsv")

# --- Unauthorized ----------------------------------------------------------------------------------
# Signs in, so makes identities: not with DENIALS_REQUESTS_ONLY=1.
if ! $requests_only; then
  echo "$(date -u +%FT%TZ) unauthorized"
  declare -A groups=([none]="" [op]=colvenna-operator)
  for u in none op; do
    name="$prefix-$u"
    python3 -c 'import secrets,string; a=string.ascii_letters+string.digits; print("Aa1!" + "".join(secrets.choice(a) for _ in range(24)), end="")' >"$state/$name.pw"
    aws cognito-idp admin-create-user --user-pool-id "$pool" --username "$name" --message-action SUPPRESS >/dev/null
    python3 -c 'import json,sys; print(json.dumps({"UserPoolId": sys.argv[1], "Username": sys.argv[2], "Password": open(sys.argv[3]).read(), "Permanent": True}))' \
      "$pool" "$name" "$state/$name.pw" >"$state/$name.input.json"
    aws cognito-idp admin-set-user-password --cli-input-json "file://$state/$name.input.json"
    rm -f "$state/$name.input.json"
    for g in ${groups[$u]}; do
      aws cognito-idp admin-add-user-to-group --user-pool-id "$pool" --username "$name" --group-name "$g"
    done
  done
  made+=(password)
  # A wrong password, before the user ever signed in.
  cp "$state/$prefix-op.pw" "$state/$prefix-op.right"
  printf 'Wrong-password-1!' >"$state/$prefix-op.pw"
  entry attempt "$(entry_output "${first_app}Url")/" "$state" "$prefix-op" >"$evidence/denials/wrong-password.json" || true
  mv "$state/$prefix-op.right" "$state/$prefix-op.pw"
  check deny-cognito-wrong-password "$(field "$evidence/denials/wrong-password.json" '(d["where"], d["error"])')" \
    test "$(field "$evidence/denials/wrong-password.json" 'd["where"]')" = login
  # The user pool's own unauthorized class has no probe beyond these: the matrix carries its N/A and
  # the reason (exposure.COGNITO_UNAUTHORIZED_NA).

  # status_of <file>: the status entry.py get recorded.
  status_of() { field "$1" 'd["status"]'; }
  # The same unsigned administrator headers, as entry.py get takes them.
  forged_args=(--header "X-Amzn-Oidc-Data:$forged" --header "X-Amzn-Oidc-Accesstoken:$forged" --header "X-Amzn-Oidc-Identity:forged")
  for app in $apps; do
    url="$(entry_output "${app}Url")"
    # The console's refusal is for a user without any role; the BI interface's also for one whose role
    # is another application's.
    users=none
    [[ "$app" == bi ]] && users="none op"
    plain="" forged_seen="" ok=1 forged_ok=1
    for u in $users; do
      if ! entry login "$url/" "$state" "$prefix-$u" >"$state/login.json"; then
        plain+="$u could not sign in: $(head -c 200 "$state/login.json"); "
        ok=0 forged_ok=0
        continue
      fi
      # The sign-in enrolled (or answered) TOTP and saved the session's cookies.
      made+=(totp cookie)
      # Only where the sign-in ended: the login's /api/me answer may name the user.
      python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(json.dumps({"where": d["where"], "chain": [s for s, _ in d["chain"]]}))' \
        "$state/login.json" >"$evidence/denials/login-$app-$u.json"
      entry get "$url${api_path[$app]}" "$state" "$prefix-$u" >"$state/get.json"
      entry get "$url${api_path[$app]}" "$state" "$prefix-$u" "${forged_args[@]}" >"$state/forged.json"
      got="$(status_of "$state/get.json")" with_forged="$(status_of "$state/forged.json")"
      printf '%s\t%s\t%s\n' "$app-$u" "$url${api_path[$app]}" "$got" "$app-$u-forged" "$url${api_path[$app]}" "$with_forged" \
        >>"$evidence/denials/http.tsv"
      plain+="$u: $got; " forged_seen+="$u: $with_forged; "
      [[ "$got" == 403 ]] || ok=0
      [[ "$with_forged" == 403 ]] || forged_ok=0
    done
    check "deny-$app-without-the-role" "${api_path[$app]} signed in ${plain}" test "$ok" = 1
    check "deny-$app-forged-headers-with-a-session" "the same sessions with unsigned administrator headers: $forged_seen" \
      test "$forged_ok" = 1
  done

  kubectl create namespace "$namespace" >/dev/null
  kubectl label namespace "$namespace" pod-security.kubernetes.io/enforce=restricted >/dev/null
  kubectl -n "$namespace" create serviceaccount probe >/dev/null
  kubectl -n "$namespace" create token probe --duration=10m >"$state/sa-token"
  made+=(sa-token)
  printf 'Authorization: Bearer %s\n' "$(cat "$state/sa-token")" >"$state/sa-header"
  codes=""
  for path in /api/v1/namespaces /api/v1/namespaces/kube-system/secrets "/apis/apps/v1/namespaces/$namespace/deployments"; do
    codes+="$path $(curl -sk -m 20 -o /dev/null -w '%{http_code}' -H @"$state/sa-header" "$endpoint$path" || true); "
  done
  code="$(curl -sk -m 20 -o /dev/null -w '%{http_code}' -H @"$state/sa-header" "$endpoint/version" || true)"
  check deny-eks-without-rbac "a service account without RBAC, authenticated (/version $code): $codes" \
    test "$code $(grep -o ' 403;' <<<"$codes" | wc -l)" = "200 3"

  # An identity of the environment without the permission: the console's service account, whose role
  # may only write its own audit log group and sign users out (deploy/aws/src/entry-stack.ts), lists
  # each bucket of the environment (the inventory's, from the stacks' saved resources) and an image
  # repository's images. Each request prints its own line with its own exit status; exposure.py
  # underprivileged reads each check from its line only.
  probe_sa=colvenna-console
  buckets="$(rows s3-bucket | tr '\n' ' ')"
  repo="$(rows ecr-repository | head -1)"
  probe_checks=()
  for b in $buckets; do probe_checks+=("deny-s3-$b-underprivileged"); done
  if [[ -n "$repo" ]]; then
    probe_checks+=(deny-ecr-underprivileged)
  else
    verdict deny-ecr-underprivileged FAIL "no image repository of the environment in the inventory"
  fi
  role="$(kubectl -n "$probe_namespace" get serviceaccount "$probe_sa" \
    -o jsonpath='{.metadata.annotations.eks\.amazonaws\.com/role-arn}' 2>/dev/null || true)"
  if [[ -z "$role" ]]; then
    for c in "${probe_checks[@]}"; do
      verdict "$c" FAIL "no probe identity: service account $probe_namespace/$probe_sa with an IAM role not found"
    done
  else
    cat >"$state/underprivileged.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $probe_pod, namespace: $probe_namespace}
spec:
  restartPolicy: Never
  serviceAccountName: $probe_sa
  securityContext: {runAsNonRoot: true, runAsUser: 1000, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: aws
      image: $AWS_CLI_IMAGE
      env:
        - {name: HOME, value: /tmp}
        - {name: BUCKETS, value: "$buckets"}
        - {name: REPO, value: "$repo"}
        - {name: REGION, value: "$COLVENNA_REGION"}
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: [ALL]}, readOnlyRootFilesystem: true}
      command: ["sh", "-c"]
      args:
        - |
          probe() {
            name="\$1"; shift
            out="\$("\$@" 2>&1)"; status=\$?
            printf '%s\t%s\t%s\n' "\$name" "\$status" "\$(printf '%s' "\$out" | tr '\t\n' '  ' | head -c 600)"
          }
          for b in \$BUCKETS; do
            probe "deny-s3-\$b-underprivileged" aws s3api list-objects-v2 --bucket "\$b" --max-keys 1
          done
          [ -z "\$REPO" ] || probe deny-ecr-underprivileged aws ecr describe-images --region "\$REGION" --repository-name "\$REPO" --max-items 1
      resources: {requests: {cpu: 10m, memory: 64Mi}, limits: {memory: 128Mi}}
      volumeMounts: [{name: tmp, mountPath: /tmp}]
  volumes: [{name: tmp, emptyDir: {}}]
YAML
    kubectl apply -f "$state/underprivileged.yaml" >/dev/null
    kubectl -n "$probe_namespace" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/$probe_pod" --timeout=3m >/dev/null || true
    kubectl -n "$probe_namespace" logs "$probe_pod" >"$evidence/denials/underprivileged.txt" 2>&1 || true
    kubectl -n "$probe_namespace" delete pod "$probe_pod" --ignore-not-found >/dev/null 2>&1 || true
    python3 -I "$here/exposure.py" underprivileged "$evidence/denials/underprivileged.txt" "${role##*/}" \
      "${probe_checks[@]}" >"$state/underprivileged.txt"
    record "$state/underprivileged.txt"
  fi
fi

# --- Around the entry ------------------------------------------------------------------------------
echo "$(date -u +%FT%TZ) bypass"
direct="$(curl -sk -m 20 -A "colvenna-sec-$run" -w ' %{http_code}' "https://$alb_dns/" || true)"
check deny-entry-load-balancer-name "${direct:0:60}" test "$direct" = "Not found 404"
other="$(curl -s -m 20 -A "colvenna-sec-$run" --resolve "other.$domain:443:$alb_ip" -w ' %{http_code}' "https://other.$domain/" || true)"
check deny-entry-unknown-host "${other:0:60}" test "$other" = "Not found 404"
first_url="$(entry_output "${first_app}Url")"
host="${first_url#https://}"
a="$(http plain-http "http://$host${api_path[$first_app]}")"
check deny-entry-plain-http "${a:0:80}" test "$a" = "301 https://$host:443${api_path[$first_app]}"
legacy="$(echo | timeout 20 openssl s_client -connect "$host:443" -servername "$host" -tls1_1 \
  -cipher 'DEFAULT:@SECLEVEL=0' 2>&1 || true)"
check deny-entry-legacy-tls "$(grep -o 'alert protocol version' <<<"$legacy" | head -1)" \
  test -n "$(grep -o 'alert protocol version' <<<"$legacy")"
# Informational: pod addresses from the host. Private addresses in another VPC are unroutable, so
# "cannot reach" is expected but not proof of NetworkPolicy. The UAT's in-cluster checks cover it:
# pods-unreachable-in-cluster and pod-refuses-forged-identity (matrix references them).
for app in $apps; do
  targets="" reached=""
  while read -r ip port; do
    [[ -n "$ip" ]] || continue
    targets+="$ip:$port "
    no_connection "$ip" "$port" || reached+="$ip:$port "
  done < <(aws elbv2 describe-target-health --target-group-arn "$(entry_output "${app}TargetGroupArn")" \
    --query 'TargetHealthDescriptions[].[Target.Id,Target.Port]' --output text)
  # stdout is denials.log already.
  echo "$(date -u +%FT%TZ) info: $app pod addresses tried from the host: ${targets:-none}; reached: ${reached:-none}"
done

# The public endpoint from an address it does not admit: a pod's traffic leaves through the NAT.
# Runs a pod: not with DENIALS_REQUESTS_ONLY=1.
if ! $requests_only; then
  eks_host="${endpoint#https://}"
  mapfile -t eks_ips < <(getent ahostsv4 "$eks_host" | awk '{print $1}' | sort -u)
  cat >"$state/probe.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: probe, namespace: $namespace}
spec:
  restartPolicy: Never
  serviceAccountName: probe
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 100, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: curl
      image: $CURL_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: [ALL]}}
      command: ["sh", "-c"]
      args:
        - |
          echo "egress \$(curl -s -m 10 https://checkip.amazonaws.com || echo unknown)"
          for ip in ${eks_ips[*]}; do
            code="\$(curl -sk -o /dev/null -m 15 --resolve $eks_host:443:\$ip -w '%{http_code}' https://$eks_host/version)"
            echo "\$ip \$code \$?"
          done
      resources: {requests: {cpu: 10m, memory: 32Mi}, limits: {memory: 64Mi}}
YAML
  kubectl apply -f "$state/probe.yaml" >/dev/null
  kubectl -n "$namespace" wait --for=jsonpath='{.status.phase}'=Succeeded pod/probe --timeout=4m >/dev/null || true
  kubectl -n "$namespace" logs probe >"$evidence/denials/eks-from-the-nat.txt" 2>&1 || true
  check deny-eks-from-another-address "$(tr '\n' ';' <"$evidence/denials/eks-from-the-nat.txt")" python3 - \
    "$evidence/denials/eks-from-the-nat.txt" "${#eks_ips[@]}" <<'PY'
import re, sys
text = open(sys.argv[1]).read().splitlines()
egress = [l for l in text if l.startswith("egress ")]
lines = [l.split() for l in text if l.strip() and not l.startswith("egress")]
# The pod reaches the internet (so the refusal is the endpoint's), yet from each public address of
# the endpoint gets no HTTP answer (000): curl's connect timeout or failure.
ok = (egress and re.match(r"^egress \d+\.\d+\.\d+\.\d+$", egress[0])
      and len(lines) == int(sys.argv[2]) > 0 and all(l[1] == "000" and l[2] in ("7", "28") for l in lines))
sys.exit(0 if ok else 1)
PY
fi

for b in $(rows s3-bucket); do
  aws s3api list-objects-v2 --bucket "$b" --max-keys 1 --endpoint-url "http://s3.$COLVENNA_REGION.amazonaws.com" \
    >/dev/null 2>"$state/plain-http" && plain=allowed || plain="$(grep -o 'explicit deny in a resource-based policy' "$state/plain-http" | head -1)"
  check "deny-s3-$b-plain-http" "this run's own identity over plain HTTP: ${plain:-$(head -c 120 "$state/plain-http")}" \
    test "$plain" = "explicit deny in a resource-based policy"
  curl -s -m 20 -o "$evidence/denials/s3-$b-website.txt" "http://$b.s3-website.$COLVENNA_REGION.amazonaws.com/" || true
  check "deny-s3-$b-website" "$(head -c 100 "$evidence/denials/s3-$b-website.txt")" \
    grep -q NoSuchWebsiteConfiguration "$evidence/denials/s3-$b-website.txt"
done

a="$(http sign-up-page "$signin/signup?client_id=$client&response_type=code&scope=openid&redirect_uri=$first_url/oauth2/idpresponse")"
check deny-cognito-sign-up-page "${a:0:80}" grep -q "^302 $signin/error" <<<"$a"
# The user pool's API, past the hosted sign-in, with what a client holding the secret would send:
# no sign-up, and no password flow (so no sign-in without TOTP).
# The secret stays in $state until the exact scan, last, has looked for it; the exit removes it.
aws cognito-idp describe-user-pool-client --user-pool-id "$pool" --client-id "$client" \
  --query UserPoolClient.ClientSecret --output text >"$state/client-secret"
made+=(client-secret password)
who="$(python3 - "$state" "$client" "$prefix" <<'PY'
import base64, hashlib, hmac, json, os, sys
state, client, prefix = sys.argv[1:4]
secret = open(os.path.join(state, "client-secret")).read().strip().encode()
def secret_hash(user):
    return base64.b64encode(hmac.new(secret, (user + client).encode(), hashlib.sha256).digest()).decode()
def new_password(user):
    # Kept as <user>.pw, as the other users' are, for the exact scan.
    password = "Aa1!" + os.urandom(12).hex()
    with open(os.path.join(state, user + ".pw"), "w") as f:
        f.write(password)
    return password
user = prefix + "-signup"
json.dump({"ClientId": client, "Username": user, "Password": new_password(user),
           "SecretHash": secret_hash(user)}, open(os.path.join(state, "sign-up.json"), "w"))
# A real user's right password when the run made one (not with DENIALS_REQUESTS_ONLY=1), else nobody's.
op = prefix + "-op"
known = os.path.exists(os.path.join(state, op + ".pw"))
if not known:
    op = prefix + "-nobody"
password = open(os.path.join(state, op + ".pw")).read() if known else new_password(op)
json.dump({"ClientId": client, "AuthFlow": "USER_PASSWORD_AUTH",
           "AuthParameters": {"USERNAME": op, "PASSWORD": password, "SECRET_HASH": secret_hash(op)}},
          open(os.path.join(state, "password-flow.json"), "w"))
print("the right password of " + op if known else "an unknown user " + op)
PY
)"
aws cognito-idp sign-up --no-sign-request --cli-input-json "file://$state/sign-up.json" >/dev/null 2>"$state/sign-up.err" \
  && signed=yes || signed="$(head -c 200 "$state/sign-up.err")"
check deny-cognito-api-sign-up "${signed}" grep -q 'SignUp is not permitted' <<<"$signed"
aws cognito-idp initiate-auth --no-sign-request --cli-input-json "file://$state/password-flow.json" >/dev/null \
  2>"$state/password-flow.err" && flow=yes || flow="$(head -c 200 "$state/password-flow.err")"
check deny-cognito-api-password-flow "$who, with the secret: ${flow}" \
  grep -q 'USER_PASSWORD_AUTH flow not enabled' <<<"$flow"

# --- The matrix, the section, and no credential in the evidence ------------------------------------
python3 -I "$here/exposure.py" matrix "$evidence" "$run" "$uat_root" >"$state/matrix.txt"
record "$state/matrix.txt"
write_section() { python3 -I "$here/exposure.py" section "$evidence" "$run" >"$evidence/section.md"; }
# scan: every file of the evidence, section.md included, for anything credential-like (scrub) and for
# exact copies of the credentials this run made or read, by type; they are still in $state.
scan() {
  python3 -I "$here/exposure.py" scrub "$evidence"
  python3 -I "$here/exposure.py" exact-scan "$evidence" "$state" "${made[*]}"
}
write_section
scan >"$state/scan.txt"
record "$state/scan.txt"
write_section
# The scans' verdict rows and the section with them came after the scan: scan once more, last. A clean
# pass records nothing new; a finding is recorded and the section rewritten to show it.
scan >"$state/scan.txt"
if grep -q $'\tFAIL\t' "$state/scan.txt"; then
  record "$state/scan.txt"
  write_section
fi
echo "verdicts: $(grep -c $'\tPASS\t' "$evidence/verdicts.tsv") PASS, $(grep -c $'\tN/A\t' "$evidence/verdicts.tsv" || true) N/A," \
  "$(grep -c $'\tFAIL\t' "$evidence/verdicts.tsv" || true) FAIL (inventory and denials); the release check's section: $evidence/section.md"
exit "$failed"
