"""A browser for the entry acceptance run: it signs in through the entry the way a person does.

The entry's load balancer sends an unauthenticated request to Cognito's classic hosted UI. The
browser submits the password form, enrols a TOTP app on first sign-in (the page shows the secret
key) or answers the MFA prompt, follows the authorization code back to the load balancer, and
keeps the cookies. Redirects are followed by hand, only to the entry's and the user pool's hosts.

usage:
  entry.py login <app-url> <state-dir> <username>     sign in; the password is <state-dir>/<user>.pw,
                                                     the TOTP secret <state-dir>/<user>.totp (made
                                                     at enrolment); prints /api/me
  entry.py attempt <app-url> <state-dir> <username> [--code CODE]
                                                     sign in without saving the session; prints
                                                     where the sign-in stopped
  entry.py get <url> [<state-dir> <username>] [--header K:V]... [--only-entry-cookies]
               [--method M] [--body-file F]          one request; prints status, location, body
                                                     (the first 2000 characters; all of it to F)
  entry.py logout <app-url> <state-dir> <username>   POST /logout and follow where it leads
  entry.py post <url> <state-dir> <username> <form.json> [--origin O] [--body-file F]
                                                     a form posted from the application's page
                                                     (its Origin, unless given); prints status,
                                                     location, body
"""

import base64
import hashlib
import hmac
import html
import http.cookiejar
import json
import os
import re
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

# The user agent: the run sets its own, so its requests can be found in the logs.
AGENT = os.environ.get("ENTRY_AGENT", "colvenna-lake-acceptance")


def totp(secret, at=None):
    """The RFC 6238 code (SHA-1, 30 s, 6 digits) of a base32 secret at a time."""
    key = base64.b32decode(secret.upper() + "=" * (-len(secret) % 8))
    counter = int((time.time() if at is None else at) // 30)
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 15
    value = struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF
    return "%06d" % (value % 1_000_000)


def form(body, name=None):
    """The action and hidden _csrf of a hosted UI form: the named one, or the first."""
    pattern = r'<form[^>]*name="%s"[^>]*action="([^"]+)"' % name if name else r'<form[^>]*action="([^"]+)"'
    action = re.search(pattern, body)
    csrf = re.search(r'name="_csrf" value="([^"]+)"', body)
    if not action or not csrf:
        raise ValueError("no %s form on the page" % (name or "sign-in"))
    # The hosted UI leaves the spaces of the scope list unencoded in its actions.
    return html.unescape(action.group(1)).replace(" ", "%20"), csrf.group(1)


def secret_key(body):
    """The TOTP secret the MFA enrolment page shows."""
    match = re.search(r'id="secretKey">\s*([A-Z2-7]{16,})', body)
    if not match:
        raise ValueError("no TOTP secret on the enrolment page")
    return match.group(1)


def page_error(body):
    """The error message a hosted UI page shows, if any."""
    match = re.search(r'id="(?:loginErrorMessage|errorMessage)"[^>]*>\s*([^<]+?)\s*<', body)
    return match.group(1) if match else ""


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Browser:
    """Cookies and requests, redirects by hand to the allowed hosts only."""

    def __init__(self, hosts, cookies=None):
        self.hosts = set(hosts)
        self.jar = http.cookiejar.MozillaCookieJar(cookies)
        if cookies and os.path.exists(cookies):
            self.jar.load(ignore_discard=True, ignore_expires=True)
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar), _NoRedirect)
        self.chain = []

    def request(self, url, data=None, headers=None, method=None):
        host = urllib.parse.urlsplit(url).hostname
        if host not in self.hosts:
            raise ValueError("refusing to follow to %s" % host)
        body = urllib.parse.urlencode(data).encode() if data is not None else None
        req = urllib.request.Request(url, data=body, method=method,
                                     headers={"User-Agent": AGENT, **(headers or {})})
        try:
            resp = self.opener.open(req, timeout=30)
            result = resp.status, dict(resp.headers), resp.read().decode(errors="replace")
        except urllib.error.HTTPError as e:
            result = e.code, dict(e.headers), e.read().decode(errors="replace")
        self.chain.append((result[0], url))
        return result

    def follow(self, url, data=None, headers=None):
        """The final URL, status, headers and body, after redirects."""
        status, head, body = self.request(url, data, headers)
        for _ in range(15):
            if status not in (301, 302, 303, 307):
                return url, status, head, body
            url = urllib.parse.urljoin(url, head["Location"]).replace(" ", "%20")
            status, head, body = self.request(url)
        raise ValueError("too many redirects")

    def save(self):
        self.jar.save(ignore_discard=True, ignore_expires=True)


def sign_in(browser, app_url, username, password, secret_file, code=None):
    """Signs in as a person would. Returns (where, body): where is "app" when the browser is back at
    the application, else the hosted UI step it stopped at ("login", "mfa", "error")."""
    url, status, _, body = browser.follow(app_url)
    if "/login" not in urllib.parse.urlsplit(url).path:
        return ("app" if urllib.parse.urlsplit(url).hostname == urllib.parse.urlsplit(app_url).hostname
                else "error"), body
    action, csrf = form(body)
    url, status, _, body = browser.follow(urllib.parse.urljoin(url, action), {
        "_csrf": csrf, "username": username, "password": password,
        "signInSubmitButton": "Sign in", "cognitoAsfData": ""})
    path = urllib.parse.urlsplit(url).path
    if path.startswith("/mfa/register"):
        secret = secret_key(body)
        with open(secret_file, "w") as f:
            f.write(secret)
        os.chmod(secret_file, 0o600)
        action, csrf = form(body, "totpMfaRegister")
        url, status, _, body = browser.follow(urllib.parse.urljoin(url, action), {
            "_csrf": csrf, "userCode": code or fresh_code(secret_file), "friendlyDeviceName": AGENT})
    elif path.startswith("/mfa"):
        action, csrf = form(body, "mfaform")
        url, status, _, body = browser.follow(urllib.parse.urljoin(url, action), {
            "_csrf": csrf, "authentication_code": code or fresh_code(secret_file),
            "cognitoAsfData": ""})
    host, path = urllib.parse.urlsplit(url).hostname, urllib.parse.urlsplit(url).path
    # Back at the application: signed in, whatever the application then makes of the user.
    if host == urllib.parse.urlsplit(app_url).hostname:
        return "app", body
    if path.startswith("/login"):
        return "login", body
    if path.startswith("/mfa"):
        return "mfa", body
    return "error", body


def fresh_code(secret_file):
    """A code no earlier sign-in of this user has used: Cognito takes each code once."""
    with open(secret_file) as f:
        secret = f.read().strip()
    used_file = secret_file + ".used"
    used = int(open(used_file).read()) if os.path.exists(used_file) else -1
    while int(time.time() // 30) <= used:
        time.sleep(1)
    window = int(time.time() // 30)
    with open(used_file, "w") as f:
        f.write(str(window))
    return totp(secret, window * 30)


def _hosts(app_url, state):
    with open(os.path.join(state, "signin-host")) as f:
        return {urllib.parse.urlsplit(app_url).hostname, f.read().strip()}


def main(argv):
    if len(argv) < 2:
        sys.exit(__doc__)
    cmd, args = argv[1], argv[2:]
    if cmd in ("login", "attempt"):
        app_url, state, user = args[:3]
        code = args[args.index("--code") + 1] if "--code" in args else None
        cookies = os.path.join(state, user + ".cookies") if cmd == "login" else None
        browser = Browser(_hosts(app_url, state), cookies)
        with open(os.path.join(state, user + ".pw")) as f:
            password = f.read()
        where, body = sign_in(browser, app_url, user, password, os.path.join(state, user + ".totp"), code)
        result = {"where": where, "error": page_error(body), "chain": browser.chain}
        if cmd == "login":
            if where != "app":
                print(json.dumps(result))
                sys.exit(1)
            browser.save()
            os.chmod(cookies, 0o600)
            status, _, me = browser.request(app_url.rstrip("/") + "/api/me")
            result["me"] = json.loads(me) if status == 200 else {"status": status, "body": me[:300]}
        print(json.dumps(result))
    elif cmd == "get":
        url = args[0]
        rest = args[1:]
        headers = {}
        while "--header" in rest:
            i = rest.index("--header")
            k, v = rest[i + 1].split(":", 1)
            headers[k] = v
            del rest[i:i + 2]
        options = {}
        for option in ("--method", "--body-file"):
            if option in rest:
                i = rest.index(option)
                options[option] = rest[i + 1]
                del rest[i:i + 2]
        only_entry = "--only-entry-cookies" in rest
        rest = [a for a in rest if a != "--only-entry-cookies"]
        hosts = {urllib.parse.urlsplit(url).hostname}
        cookies = os.path.join(rest[0], rest[1] + ".cookies") if len(rest) == 2 else None
        browser = Browser(hosts, cookies)
        if only_entry:
            # Only the load balancer's session cookies: what a copied session would carry.
            for c in list(browser.jar):
                if "-auth-" not in c.name:
                    browser.jar.clear(c.domain, c.path, c.name)
        status, head, body = browser.request(url, headers=headers, method=options.get("--method"))
        if "--body-file" in options:
            with open(options["--body-file"], "w") as f:
                f.write(body)
        print(json.dumps({"status": status, "location": head.get("Location", ""), "body": body[:2000]}))
    elif cmd == "logout":
        app_url, state, user = args
        cookies = os.path.join(state, user + ".cookies")
        browser = Browser(_hosts(app_url, state), cookies)
        # As a browser sends it from the application's own page.
        url, status, _, body = browser.follow(app_url.rstrip("/") + "/logout", data={},
                                              headers={"Origin": app_url.rstrip("/"),
                                                       "Sec-Fetch-Site": "same-origin"})
        browser.save()
        print(json.dumps({"final": url, "status": status, "chain": browser.chain,
                          "loginForm": 'name="username"' in body}))
    elif cmd == "post":
        url, state, user, form_path = args[:4]
        rest = args[4:]
        options = dict(zip(rest[::2], rest[1::2]))
        with open(form_path) as f:
            form = json.load(f)
        parts = urllib.parse.urlsplit(url)
        origin = options.get("--origin", "%s://%s" % (parts.scheme, parts.netloc))
        browser = Browser({parts.hostname}, os.path.join(state, user + ".cookies"))
        headers = {"Origin": origin} if origin else {}
        status, head, body = browser.request(url, data=form, headers=headers, method="POST")
        browser.save()
        if "--body-file" in options:
            with open(options["--body-file"], "w") as f:
                f.write(body)
        print(json.dumps({"status": status, "location": head.get("Location", ""), "body": body[:2000]}))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
