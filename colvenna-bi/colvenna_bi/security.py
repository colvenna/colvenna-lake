"""Signing in to Superset is signing in at the entry: every request is verified afresh.

Superset's own session never decides who a request is. Before anything else runs, the
load balancer's claims and the user's access token are verified (colvenna_bi.identity), the Superset
user of that name is made or found, its roles set from the user pool's groups and the
dashboards the person is a user of, and the request runs as that user. A request that does not
verify gets 401, one whose user has no role here 403. Superset's API sign-in, its sign-in pages
and API tokens are refused: there is no other way in. Signing out ends the person's sessions at
the user pool, then the sign-out landing page drops the load balancer's cookie and returns
through the user pool's sign-out.
"""

import hashlib
import json
import secrets
import sys
import time
import urllib.parse

from flask import Response, current_app, redirect, request, session
from flask_login import current_user, login_user, logout_user

from colvenna_bi import runtime
from colvenna_bi.identity import Refused
from colvenna_bi.settings import DASHBOARD_ROLE_PREFIX, SIGNED_OUT_PATH, SUB_DOMAIN, SUPERSET_ROLES

# Paths that answer without a user: the kubelet's and the load balancer's health checks.
OPEN = ("/health",)
# Every other way to sign in, and the API's tokens: refused, whatever the request carries.
REFUSED_PREFIXES = (
    "/api/v1/security/login",
    "/api/v1/security/refresh",
    "/api/v1/security/guest_token",
    "/login",
    "/register",
    "/oauth-authorized",
)


def superset_roles(user, credentials):
    """The Superset roles of an entry user: their role's, and, for a BI user, one per dashboard
    they are a user of (access to that dashboard's database)."""
    roles = []
    for role in user.roles:
        roles.extend(SUPERSET_ROLES.get(role, []))
    if "bi" in user.roles:
        roles.extend(dashboard_role(d) for d in credentials.dashboards(user.username))
    return roles


def dashboard_role(dashboard):
    """A dashboard's role name: Superset keeps at most 64 characters, so a long one is shortened
    with a digest of the whole."""
    name = DASHBOARD_ROLE_PREFIX + dashboard
    if len(name) <= 64:
        return name
    return name[:47] + "~" + hashlib.sha256(dashboard.encode()).hexdigest()[:16]


def audit(kind, user=None, status=None, reason=None):
    """One line on the pod's log: who did what here."""
    event = {"time": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "kind": kind,
             "path": request.path, "method": request.method, "status": status}
    if user is not None:
        event.update(sub=user.sub, username=user.username, roles=list(user.roles),
                     signedIn=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(user.signed_in)))
    if reason:
        event["reason"] = reason
    event["sourceIp"] = request.headers.get("X-Forwarded-For", "").split(",")[-1].strip()
    print(json.dumps(event), file=sys.stdout, flush=True)


def authenticate():
    """The first before_request hook."""
    if request.path in OPEN:
        return None
    if request.path.startswith(REFUSED_PREFIXES):
        audit("denied", status=404, reason="another way to sign in")
        return Response("Not found", 404)
    if request.headers.get("Authorization"):
        audit("denied", status=401, reason="an API token")
        return Response("API tokens are not accepted: sign in at the entry.", 401)
    try:
        user = runtime.verifier().verify(request.headers)
    except Refused as e:
        audit("denied", status=401, reason=str(e))
        return Response("没有通过登录。", 401)
    # Signing out needs no role.
    if request.path.rstrip("/") == "/logout":
        return logout(user)
    if request.path == SIGNED_OUT_PATH:
        return signed_out()
    roles = superset_roles(user, runtime.credentials())
    if not roles:
        audit("denied", user, 403, "no role")
        return Response("你的账号没有 BI 查询界面的角色，请联系平台管理员。", 403)
    sm = current_app.appbuilder.sm
    found = [r for r in (sm.find_role(name) for name in roles) if r is not None]
    account = sm.find_user(username=user.username)
    if account is None:
        # No password anyone knows: Superset's own sign-in is refused above anyway. The e-mail
        # carries the sub (mutator.annotate reads it back).
        account = sm.add_user(user.username, user.username, "-", user.sub + SUB_DOMAIN,
                              found, password=secrets.token_urlsafe(32))
        audit("signin", user, reason="first sign-in")
    elif account.email != user.sub + SUB_DOMAIN:
        # A user name the user pool gave again to someone else: not the account of before.
        audit("denied", user, 403, "the Superset account is another person's (%s)" % account.email)
        return Response("这个用户名以前属于另一个人，请联系平台管理员处理旧账号。", 403)
    elif sorted(r.name for r in account.roles) != sorted(r.name for r in found) or not account.active:
        account.roles, account.active = found, True
        sm.update_user(account)
    if current_user.is_anonymous or current_user.get_id() != str(account.id):
        login_user(account, remember=False)
        audit("signin", user)
    return None


def logout(user):
    """Superset's sign-out (GET /logout/, its menu's link), from its own pages only: ends the
    person's sessions at the user pool, then goes to the sign-out page. When the access token has
    expired, the load balancer refreshes the session on this request and sets the cookie again in
    this very response, after these headers; on the next request the token is fresh, nothing is
    refreshed, and the cookie dropped there stays dropped."""
    if request.headers.get("Sec-Fetch-Site") not in ("same-origin", "none"):
        audit("denied", user, 403, "logout from another site")
        return Response("请从 BI 查询界面退出登录。", 403)
    s = runtime.settings()
    reason = "signed out at the user pool"
    try:
        import boto3

        boto3.client("cognito-idp", region_name=s.region).admin_user_global_sign_out(
            UserPoolId=s.user_pool_id, Username=user.username)
    except Exception as e:  # noqa: BLE001 - the sign-out goes on regardless
        reason = "user pool sign-out failed: %s" % e
    audit("logout", user, 303, reason)
    logout_user()
    session.clear()
    return redirect(SIGNED_OUT_PATH, code=303)


def signed_out():
    """The sign-out page, signed in like every other: drops the load balancer's and Superset's
    cookies and sends the browser to the user pool's sign-out, which returns here and so to
    sign-in."""
    s = runtime.settings()
    target = "%s/logout?%s" % (s.sign_in_url, urllib.parse.urlencode(
        {"client_id": s.client_id, "logout_uri": s.public_url + "/"}))
    response = redirect(target, code=303)
    for i in range(4):
        response.delete_cookie("%s-%d" % (s.session_cookie, i), path="/", secure=True, httponly=True)
    response.delete_cookie("session", path="/", secure=True, httponly=True)
    return response


def install(app):
    """FLASK_APP_MUTATOR: the check runs before every other hook, and every statement sent to
    Doris carries the person (mutator.annotate_every_statement)."""
    app.before_request_funcs.setdefault(None, []).insert(0, authenticate)
    annotate_superset()
    return app


def annotate_superset():
    from superset.models.core import Database

    from colvenna_bi.mutator import annotate_every_statement

    annotate_every_statement(Database)
