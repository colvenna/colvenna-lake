import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "entry"))
import entry  # noqa: E402

SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"


class TotpTest(unittest.TestCase):
    def test_rfc6238_vector(self):
        # RFC 6238 appendix B, SHA-1, T = 59: 94287082, of which the last six digits.
        secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        self.assertEqual(entry.totp(secret, 59), "287082")
        self.assertEqual(entry.totp(secret, 1111111109), "081804")


LOGIN = """<form action="/login?client_id=c&amp;scope=openid email&amp;state=s" method="post">
<input type="hidden" name="_csrf" value="tok-1"/><input name="username"></form>"""
REGISTER = """<p>Secret key</p><span id="secretKey">%s</span>
<form name="totpMfaRegister" action="/mfa/register/verifyTOTP?client_id=c&amp;scope=openid email">
<input type="hidden" name="_csrf" value="tok-2"/></form>""" % SECRET
MFA = """<form name="mfaform" action="/mfa?client_id=c"><input type="hidden" name="_csrf" value="tok-3"/>
<input name="authentication_code"></form>"""


class PageTest(unittest.TestCase):
    def test_forms_and_secret(self):
        self.assertEqual(entry.form(LOGIN), ("/login?client_id=c&scope=openid%20email&state=s", "tok-1"))
        self.assertEqual(entry.form(REGISTER, "totpMfaRegister")[1], "tok-2")
        self.assertEqual(entry.form(MFA, "mfaform"), ("/mfa?client_id=c", "tok-3"))
        self.assertEqual(entry.secret_key(REGISTER), SECRET)
        with self.assertRaises(ValueError):
            entry.form("<p>nothing</p>")
        self.assertEqual(entry.page_error('<p id="loginErrorMessage" class="x">User is disabled.</p>'),
                         "User is disabled.")


class Fake(BaseHTTPRequestHandler):
    """The entry (on 127.0.0.1) and the hosted UI (on localhost), as the browser meets them."""

    enrolled = set()
    pending = ""
    app = signin = ""
    # What the application answers a signed-in user (403: a user without a role).
    status = 200

    def log_message(self, *args):
        pass

    def send(self, status, body="", location=None, cookie=None):
        self.send_response(status)
        if location:
            self.send_header("Location", location)
        if cookie:
            self.send_header("Set-Cookie", cookie)
        self.end_headers()
        self.wfile.write(body.encode())

    def form_data(self):
        n = int(self.headers.get("Content-Length") or 0)
        return dict(urllib.parse.parse_qsl(self.rfile.read(n).decode()))

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        if self.headers["Host"].startswith("127.0.0.1"):
            signed = "app-auth-0=ok" in (self.headers.get("Cookie") or "")
            if url.path == "/oauth2/idpresponse":
                return self.send(302, location="/", cookie="app-auth-0=ok; Path=/")
            if not signed:
                return self.send(302, location=self.signin + "/oauth2/authorize?client_id=c")
            if url.path == "/api/me":
                return self.send(200, json.dumps({"username": "u", "roles": ["bi"]}))
            return self.send(Fake.status, "<h1>console</h1>")
        if url.path == "/oauth2/authorize":
            return self.send(302, location="/login?client_id=c&scope=openid%20email&state=s")
        pages = {"/login": LOGIN, "/mfa/register": REGISTER, "/mfa": MFA}
        return self.send(200, pages[url.path])

    def do_POST(self):
        url = urllib.parse.urlsplit(self.path)
        data = self.form_data()
        back = self.app + "/oauth2/idpresponse?code=ok"
        if url.path == "/login":
            if data.get("password") != "pw" or data.get("_csrf") != "tok-1":
                return self.send(200, '<p id="loginErrorMessage">Incorrect username or password.</p>' + LOGIN)
            Fake.pending = data["username"]
            if data["username"] in Fake.enrolled:
                return self.send(302, location="/mfa?client_id=c")
            return self.send(302, location="/mfa/register?client_id=c")
        code = data.get("userCode") or data.get("authentication_code")
        if code != entry.totp(SECRET):
            return self.send(200, MFA)
        Fake.enrolled.add(Fake.pending)
        return self.send(302, location=back)


class SignInTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), Fake)
        port = cls.server.server_address[1]
        Fake.app = "http://127.0.0.1:%d" % port
        Fake.signin = "http://localhost:%d" % port
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def browser(self):
        return entry.Browser({"127.0.0.1", "localhost"})

    def test_enrols_then_answers_the_mfa_prompt(self):
        with tempfile.TemporaryDirectory() as state:
            secret = os.path.join(state, "alice.totp")
            b = self.browser()
            self.assertEqual(entry.sign_in(b, Fake.app + "/", "alice", "pw", secret)[0], "app")
            with open(secret) as f:
                self.assertEqual(f.read(), SECRET)
            self.assertEqual(b.request(Fake.app + "/api/me")[0], 200)
            # Again in a fresh browser: the MFA prompt, with the code of the current window.
            where, _ = entry.sign_in(self.browser(), Fake.app + "/", "alice", "pw", secret,
                                     code=entry.totp(SECRET))
            self.assertEqual(where, "app")

    def test_stops_at_the_step_that_refused(self):
        with tempfile.TemporaryDirectory() as state:
            secret = os.path.join(state, "bob.totp")
            where, body = entry.sign_in(self.browser(), Fake.app + "/", "bob", "wrong", secret)
            self.assertEqual((where, entry.page_error(body)), ("login", "Incorrect username or password."))
            Fake.enrolled.add("carol")
            with open(secret, "w") as f:
                f.write(SECRET)
            where, _ = entry.sign_in(self.browser(), Fake.app + "/", "carol", "pw", secret, code="000000"
                                     if entry.totp(SECRET) != "000000" else "111111")
            self.assertEqual(where, "mfa")

    def test_a_user_the_application_refuses_is_still_signed_in(self):
        with tempfile.TemporaryDirectory() as state:
            Fake.status = 403
            try:
                where, _ = entry.sign_in(self.browser(), Fake.app + "/", "dave", "pw",
                                         os.path.join(state, "dave.totp"))
            finally:
                Fake.status = 200
            self.assertEqual(where, "app")

    def test_refuses_to_follow_elsewhere(self):
        with self.assertRaises(ValueError):
            entry.Browser({"127.0.0.1"}).request("http://example.com/")


if __name__ == "__main__":
    unittest.main()
