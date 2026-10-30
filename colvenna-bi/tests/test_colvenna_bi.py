"""colvenna_bi's decisions, without a Superset database: who a request is, which Doris user a
connection is, which roles a person gets, which databases the dashboards need."""

import base64
import json
import os
import tempfile
import time
import unittest
from types import SimpleNamespace

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from sqlalchemy.engine.url import make_url

from colvenna_bi import credentials as creds
from colvenna_bi import identity, mutator, security, sync
from colvenna_bi.settings import Settings

ARN = "arn:aws:elasticloadbalancing:us-west-2:111122223333:loadbalancer/app/entry/0123456789abcdef"
POOL = "us-west-2_TEST"
ISSUER = "https://cognito-idp.us-west-2.amazonaws.com/" + POOL
KID = "6a1b2c3d-0000-4000-8000-000000000001"
NOW = 1_800_000_000


def settings(**changes):
    values = dict(region="us-west-2", load_balancer_arn=ARN, user_pool_id=POOL, client_id="client123",
                  role_groups={"colvenna-admin": "admin", "colvenna-operator": "operator", "colvenna-bi": "bi"},
                  public_url="https://bi.example", sign_in_url="https://pool.example",
                  session_cookie="colvenna-bi-auth", doris_host="fe.doris.svc", doris_port=9030,
                  doris_users_dir="/nowhere")
    values.update(changes)
    return Settings(**values)


class FakeResponse:
    def __init__(self, status, content=b"", body=None):
        self.status_code, self.content, self.body = status, content, body

    def json(self):
        return self.body


class Keys:
    def __init__(self):
        self.alb = ec.generate_private_key(ec.SECP256R1())
        self.pool = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.calls = []

    def get(self, url, timeout=None):
        self.calls.append(url)
        if url.endswith("/" + KID):
            return FakeResponse(200, self.alb.public_key().public_bytes(
                serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
        if url.endswith("jwks.json"):
            jwk = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(self.pool.public_key()))
            jwk.update(kid="pool-1", use="sig", alg="RS256")
            return FakeResponse(200, body={"keys": [jwk]})
        return FakeResponse(403)

    def verifier(self, now=NOW):
        clock = lambda: now  # noqa: E731
        return identity.Verifier(settings(), identity.AlbKeys("https://keys", self.get, clock),
                                 identity.PoolKeys(ISSUER + "/.well-known/jwks.json", self.get, clock),
                                 now=clock)

    def data(self, sub="user-1", key=None, **header):
        h = {"alg": "ES256", "kid": KID, "signer": ARN, "iss": ISSUER, "client": "client123",
             "exp": NOW + 60}
        h.update(header)
        # The load balancer pads its segments and signs them as padded.
        pad = lambda b: base64.urlsafe_b64encode(b).decode()  # noqa: E731
        signed = pad(json.dumps(h).encode()) + "." + pad(json.dumps({"sub": sub, "email": "a@example.com"}).encode())
        algorithm = jwt.algorithms.ECAlgorithm(jwt.algorithms.ECAlgorithm.SHA256)
        return signed + "." + pad(algorithm.sign(signed.encode(), key or self.alb))

    def access(self, key=None, **claims):
        c = {"sub": "user-1", "username": "alice", "iss": ISSUER, "client_id": "client123",
             "token_use": "access", "exp": NOW + 300, "auth_time": NOW - 60,
             "cognito:groups": ["colvenna-bi", "colvenna-operator", "other"]}
        c.update(claims)
        return jwt.encode(c, key or self.pool, algorithm="RS256", headers={"kid": "pool-1"})


class IdentityTest(unittest.TestCase):
    def setUp(self):
        self.k = Keys()

    def headers(self, data=None, access=None):
        return {identity.DATA_HEADER: data if data is not None else self.k.data(),
                identity.ACCESS_TOKEN_HEADER: access if access is not None else self.k.access()}

    def test_the_entrys_tokens_are_a_user_with_roles_of_their_groups(self):
        user = self.k.verifier().verify(self.headers())
        self.assertEqual((user.sub, user.username, user.email), ("user-1", "alice", "a@example.com"))
        self.assertEqual(user.roles, ("operator", "bi"))
        self.assertEqual(user.signed_in, NOW - 60)

    def test_anything_else_is_refused(self):
        other = Keys()
        none = jwt.encode({"sub": "user-1"}, None, algorithm="none",
                          headers={"kid": KID, "signer": ARN, "iss": ISSUER, "client": "client123", "exp": NOW + 60})
        cases = {
            "another load balancer": self.headers(data=self.k.data(signer="arn:aws:elasticloadbalancing:x")),
            "another client": self.headers(data=self.k.data(client="other")),
            "expired claims": self.headers(data=self.k.data(exp=NOW - 3600)),
            "claims signed by another key": self.headers(data=self.k.data(key=other.alb)),
            "unsigned claims": self.headers(data=none),
            "another user's token": self.headers(access=self.k.access(sub="user-2")),
            "token of another pool key": self.headers(access=self.k.access(key=other.pool)),
            "token of another client": self.headers(access=self.k.access(client_id="other")),
            "an ID token": self.headers(access=self.k.access(token_use="id")),
            "expired token": self.headers(access=self.k.access(exp=NOW - 3600)),
            "token without sign-in time": self.headers(access=self.k.access(auth_time=0)),
            "claims without a token": {identity.DATA_HEADER: self.k.data()},
        }
        for name, headers in cases.items():
            with self.subTest(name):
                with self.assertRaises(identity.Refused):
                    self.k.verifier().verify(headers)
        with self.assertRaises(identity.NoIdentity):
            self.k.verifier().verify({})

    def test_an_unknown_key_id_is_asked_for_once_a_minute_and_other_failures_retried(self):
        clock = [NOW]
        keys = identity.AlbKeys("https://keys", self.k.get, lambda: clock[0])
        unknown = "6a1b2c3d-0000-4000-8000-0000000000ff"
        for _ in range(3):
            with self.assertRaises(identity.Refused):
                keys.key(unknown)
        self.assertEqual(sum(u.endswith(unknown) for u in self.k.calls), 1)
        clock[0] += 120
        with self.assertRaises(identity.Refused):
            keys.key(unknown)
        self.assertEqual(sum(u.endswith(unknown) for u in self.k.calls), 2)
        failing = identity.AlbKeys("https://keys", lambda url, timeout=None: FakeResponse(503), lambda: NOW)
        for _ in range(2):
            with self.assertRaises(identity.Refused):
                failing.key(KID)
        self.assertNotIn(KID, failing.missing)
        with self.assertRaises(identity.Refused):
            keys.key("../../etc/passwd")


def entry(sso, user, database, dashboard):
    return {"sso": sso, "dorisUser": user, "password": "pw-" + user, "database": database,
            "dashboard": dashboard}


class CredentialsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def write(self, version, entries):
        """As a Secret volume: a timestamped directory, ..data pointing at it, links to its files."""
        target = os.path.join(self.dir, "..%d" % version)
        os.mkdir(target)
        for e in entries:
            with open(os.path.join(target, e["dorisUser"]), "w") as f:
                json.dump(e, f)
        link = os.path.join(self.dir, "..data_tmp")
        os.symlink(os.path.basename(target), link)
        os.replace(link, os.path.join(self.dir, "..data"))
        for name in os.listdir(self.dir):
            if not name.startswith(".."):
                os.remove(os.path.join(self.dir, name))
        for e in entries:
            os.symlink(os.path.join("..data", e["dorisUser"]), os.path.join(self.dir, e["dorisUser"]))

    def test_a_person_is_found_on_their_dashboards_and_changes_are_read_again(self):
        clock = [0]
        c = creds.Credentials(self.dir, now=lambda: clock[0], every=5)
        self.write(1, [entry("Alice", "ns.sales.a", "dash_s", "ns/sales"),
                       entry("alice", "ns.ops.a", "dash_o", "ns/ops")])
        self.assertEqual(c.lookup("dash_s", "ALICE").doris_user, "ns.sales.a")
        self.assertEqual(c.dashboards("alice"), ["ns/ops", "ns/sales"])
        self.assertIsNone(c.lookup("dash_s", "bob"))
        self.write(2, [entry("alice", "ns.ops.a", "dash_o", "ns/ops")])
        # Within the interval the old version stands; after it, the new one.
        self.assertIsNotNone(c.lookup("dash_s", "alice"))
        clock[0] = 10
        self.assertIsNone(c.lookup("dash_s", "alice"))
        self.assertEqual(c.dashboards("alice"), ["ns/ops"])


class FakeCredentials:
    def __init__(self, *identities):
        self.identities = identities

    def lookup(self, database, username):
        for i in self.identities:
            if i.database == database and i.sso.lower() == username.lower():
                return i
        return None

    def dashboards(self, username):
        return sorted({i.dashboard for i in self.identities if i.sso.lower() == username.lower()})

    def all(self):
        return list(self.identities)


ALICE = creds.Identity("alice", "ns.sales.a", "pw-a", "dash_s", "ns/sales")


class MutatorTest(unittest.TestCase):
    def connect(self, uri="doris://sso@fe.doris.svc:9030/internal.dash_s", username="alice",
                user=SimpleNamespace(username="alice"), guest=False):
        return mutator.credential_for(make_url(uri), username, user, guest, FakeCredentials(ALICE),
                                      "fe.doris.svc", 9030)

    def test_the_person_connects_as_their_own_doris_user(self):
        uri = self.connect()
        self.assertEqual((uri.username, uri.password, uri.host), ("ns.sales.a", "pw-a", "fe.doris.svc"))
        # Whatever the database row says, its user and password are replaced.
        uri = self.connect("doris://root:root@fe.doris.svc:9030/dash_s")
        self.assertEqual((uri.username, uri.password), ("ns.sales.a", "pw-a"))

    def test_every_other_connection_is_refused(self):
        cases = {
            "a guest": dict(guest=True),
            "no one signed in": dict(user=None),
            "another user's connection": dict(username="bob", user=SimpleNamespace(username="alice")),
            "a person on no dashboard": dict(username="bob", user=SimpleNamespace(username="bob")),
            "another host": dict(uri="doris://sso@evil.example:9030/internal.dash_s"),
            "another port": dict(uri="doris://sso@fe.doris.svc:9031/internal.dash_s"),
            "another engine": dict(uri="mysql://sso@fe.doris.svc:9030/dash_s"),
            "another catalog": dict(uri="doris://sso@fe.doris.svc:9030/lake.dash_s"),
            "another dashboard's database": dict(uri="doris://sso@fe.doris.svc:9030/internal.dash_o"),
        }
        for name, kw in cases.items():
            with self.subTest(name):
                with self.assertRaises(mutator.NotAllowed):
                    self.connect(**kw)


class RolesTest(unittest.TestCase):
    def test_roles_follow_the_groups_and_bi_users_get_their_dashboards(self):
        c = FakeCredentials(ALICE, creds.Identity("alice", "ns.ops.a", "pw", "dash_o", "ns/ops"))
        user = lambda roles: identity.User("s", "alice", "", (), roles, NOW)  # noqa: E731
        self.assertEqual(security.superset_roles(user(("bi",)), c),
                         ["ColvennaBI", "Dashboard ns/ops", "Dashboard ns/sales"])
        self.assertEqual(security.superset_roles(user(("admin",)), c), ["Admin"])
        self.assertEqual(security.superset_roles(user(("operator",)), c), [])


class SyncTest(unittest.TestCase):
    def test_one_passwordless_read_only_database_per_dashboard(self):
        c = FakeCredentials(ALICE, creds.Identity("bob", "ns.sales.b", "pw", "dash_s", "ns/sales"))
        self.assertEqual(sync.wanted(c.all()), {"ns/sales": "dash_s"})
        s = sync.database_settings("ns/sales", "dash_s", "fe.doris.svc", 9030)
        url = make_url(s["sqlalchemy_uri"])
        self.assertEqual((url.username, url.password, url.database), ("sso", None, "internal.dash_s"))
        self.assertFalse(any(s[k] for k in ("allow_dml", "allow_ctas", "allow_cvas", "allow_run_async",
                                            "allow_file_upload", "impersonate_user")))
        self.assertTrue(json.loads(s["extra"])[sync.MANAGED])


class AnnotateTest(unittest.TestCase):
    def test_queries_carry_the_person_outside_a_request_nothing(self):
        self.assertEqual(mutator.annotate("SELECT 1"), "SELECT 1")
        from flask import Flask, g

        with Flask("t").app_context():
            g.user = SimpleNamespace(username="alice*/", email="sub-1" + mutator.SUB_DOMAIN)
            self.assertEqual(mutator.annotate("SELECT 1"), "/* colvenna-bi user=alice__ sub=sub-1 */\nSELECT 1")
            g.user = SimpleNamespace(username="bob", email="bob@example.com")
            self.assertTrue(mutator.annotate("SELECT 1").startswith("/* colvenna-bi user=bob sub=- */"))


class SettingsTest(unittest.TestCase):
    def test_an_unknown_role_is_refused(self):
        env = {"AWS_REGION": "us-west-2", "COLVENNA_LOAD_BALANCER_ARN": ARN, "COLVENNA_USER_POOL_ID": POOL,
               "COLVENNA_CLIENT_ID": "c", "COLVENNA_PUBLIC_URL": "https://bi/", "COLVENNA_SIGN_IN_URL": "https://p",
               "COLVENNA_SESSION_COOKIE": "colvenna-bi-auth", "COLVENNA_DORIS_HOST": "fe",
               "COLVENNA_ROLE_GROUPS": json.dumps({"colvenna-bi": "bi"})}
        self.assertEqual(Settings.from_environment(env).public_url, "https://bi")
        env["COLVENNA_ROLE_GROUPS"] = json.dumps({"colvenna-bi": "root"})
        with self.assertRaises(ValueError):
            Settings.from_environment(env)



class MoreMutatorTest(unittest.TestCase):
    def test_options_that_log_in_or_run_on_connecting_are_refused(self):
        base = "doris://sso@fe.doris.svc:9030/internal.dash_s"
        for uri, params in ((base + "?user=root&passwd=x", {}), (base, {"connect_args": {"passwd": "x"}}),
                            (base, {"connect_args": {"init_command": "SET x=1"}}),
                            (base, {"connect_args": {"read_default_file": "/etc/my.cnf"}}),
                            (base, {"connect_args": {"sql_mode": "x'; SET PASSWORD = 'y'; -- "}}),
                            (base, {"connect_args": {"collation": "x"}}),
                            (base, {"connect_args": {"local_infile": 1}}),
                            (base, {"connect_args": {"client_flag": 65536}}),
                            (base, {"connect_args": {"ssl": {}}})):
            with self.subTest(uri=uri, params=params):
                with self.assertRaises(mutator.NotAllowed):
                    mutator.credential_for(make_url(uri), "alice", SimpleNamespace(username="alice"), False,
                                           FakeCredentials(ALICE), "fe.doris.svc", 9030, params)
        uri = mutator.credential_for(make_url(base), "alice", SimpleNamespace(username="alice"), False,
                                     FakeCredentials(ALICE), "fe.doris.svc", 9030, {"connect_args": {"connect_timeout": 10}})
        self.assertEqual(uri.username, "ns.sales.a")

    def test_the_comment_cannot_be_ended_by_a_name(self):
        from flask import Flask, g

        with Flask("t").app_context():
            g.user = SimpleNamespace(username="a**//b*/", email="s" + mutator.SUB_DOMAIN)
            self.assertTrue(mutator.annotate("SELECT 1").startswith("/* colvenna-bi user=a____b__ sub=s */"))


class AnnotateEveryStatementTest(unittest.TestCase):
    def test_a_stored_query_run_again_names_who_runs_it_once(self):
        from flask import Flask, g

        class Database:
            def mutate_sql_based_on_config(self, sql_, is_split=False):
                return sql_

        mutator.annotate_every_statement(Database)
        mutator.annotate_every_statement(Database)
        with Flask("t").app_context():
            g.user = SimpleNamespace(username="bob", email="sub-b" + mutator.SUB_DOMAIN)
            stored = "/* colvenna-bi user=alice sub=sub-a */\nSELECT 1"
            for split in (False, True):
                self.assertEqual(Database().mutate_sql_based_on_config(stored, is_split=split),
                                 "/* colvenna-bi user=bob sub=sub-b */\nSELECT 1")
            twice = mutator.annotate(mutator.annotate("SELECT 2"))
            self.assertEqual(twice.count("colvenna-bi user="), 1)


class DashboardRoleTest(unittest.TestCase):
    def test_role_names_fit_supersets_64_characters_and_stay_distinct(self):
        self.assertEqual(security.dashboard_role("ns/sales"), "Dashboard ns/sales")
        a, b = security.dashboard_role("n" * 40 + "/" + "a" * 60), security.dashboard_role("n" * 40 + "/" + "b" * 60)
        self.assertTrue(len(a) <= 64 and len(b) <= 64 and a != b)


class DialectTest(unittest.TestCase):
    def test_has_table_reads_show_not_information_schema(self):
        from unittest import mock

        from sqlalchemy.engine import Connection

        from colvenna_bi.dialect import ColvennaDorisDialect

        conn = mock.create_autospec(Connection, instance=True)
        conn.exec_driver_sql.return_value = [("live", "VIEW"), ("by_state", "BASE TABLE")]
        dialect = ColvennaDorisDialect()
        self.assertTrue(dialect.has_table(conn, "live", schema="dash_s"))
        self.assertFalse(dialect.has_table(conn, "liv", schema="dash_s"))
        sql = conn.exec_driver_sql.call_args[0][0]
        self.assertEqual(sql, "SHOW FULL TABLES FROM `dash_s`")
        self.assertEqual(dialect.get_table_comment(conn, "live", schema="dash_s"), {"text": None})


class RequestTest(unittest.TestCase):
    """The request hook in a Flask app with a stand-in for Superset's security manager."""

    def setUp(self):
        from unittest import mock

        from flask import Flask
        from flask_login import LoginManager, UserMixin

        self.accounts = {}
        known = {"ColvennaBI", "Admin", "Dashboard ns/sales"}

        class Account(UserMixin):
            def __init__(self, i, username, email, roles):
                self.id, self.username, self.email, self.roles, self.active = i, username, email, roles, True

        test = self

        class SM:
            def find_role(self, name):
                return SimpleNamespace(name=name) if name in known else None

            def find_user(self, username):
                return next((a for a in test.accounts.values() if a.username == username), None)

            def add_user(self, username, first, last, email, roles, password):
                a = Account(str(len(test.accounts) + 1), username, email, roles)
                test.accounts[a.id] = a
                return a

            def update_user(self, account):
                pass

        app = Flask("t")
        app.secret_key = "x"
        LoginManager(app).user_loader(lambda i: self.accounts.get(i))
        app.appbuilder = SimpleNamespace(sm=SM())
        app.add_url_rule("/", "home", lambda: "home")
        app.add_url_rule("/logout/", "logout", lambda: "not reached")
        with mock.patch.object(security, "annotate_superset", lambda: None):
            security.install(app)
        self.client = app.test_client()

        class Verifier:
            def verify(self, headers):
                who = headers.get("X-Test")
                if who == "alice":
                    return identity.User("sub-a", "alice", "", ("colvenna-bi",), ("bi",), NOW)
                if who == "erin":
                    return identity.User("sub-e", "erin", "", ("colvenna-operator",), ("operator",), NOW)
                raise identity.NoIdentity("no identity headers")

        self.signouts = []
        boto3 = SimpleNamespace(client=lambda *a, **k: SimpleNamespace(
            admin_user_global_sign_out=lambda **kw: self.signouts.append(kw["Username"])))
        from colvenna_bi import runtime

        for patch in (mock.patch.object(runtime, "verifier", lambda: Verifier()),
                      mock.patch.object(runtime, "credentials", lambda: FakeCredentials(ALICE)),
                      mock.patch.object(runtime, "settings", lambda: settings()),
                      mock.patch.dict("sys.modules", {"boto3": boto3})):
            patch.start()
            self.addCleanup(patch.stop)

    def test_only_a_verified_user_with_a_role_gets_in_as_their_account(self):
        self.assertEqual(self.client.get("/").status_code, 401)
        self.assertEqual(self.client.get("/", headers={"X-Test": "erin"}).status_code, 403)
        r = self.client.get("/", headers={"X-Test": "alice"})
        self.assertEqual((r.status_code, r.data), (200, b"home"))
        account = self.accounts["1"]
        self.assertEqual((account.username, account.email), ("alice", "sub-a" + mutator.SUB_DOMAIN))
        self.assertEqual(sorted(r.name for r in account.roles), ["ColvennaBI", "Dashboard ns/sales"])
        # The session alone is nobody.
        self.assertEqual(self.client.get("/").status_code, 401)

    def test_other_ways_in_are_refused(self):
        for path in ("/login/", "/api/v1/security/login", "/api/v1/security/refresh", "/api/v1/security/guest_token/"):
            self.assertEqual(self.client.post(path, headers={"X-Test": "alice"}).status_code, 404, path)
        r = self.client.get("/", headers={"X-Test": "alice", "Authorization": "Bearer x"})
        self.assertEqual(r.status_code, 401)

    def test_a_reused_user_name_is_not_the_old_account(self):
        self.client.get("/", headers={"X-Test": "alice"})
        self.accounts["1"].email = "someone-else" + mutator.SUB_DOMAIN
        self.assertEqual(self.client.get("/", headers={"X-Test": "alice"}).status_code, 403)

    def test_logout_from_its_own_pages_then_the_sign_out_page_drops_the_cookies(self):
        for site in ("cross-site", None):
            headers = {"X-Test": "alice"}
            if site:
                headers["Sec-Fetch-Site"] = site
            self.assertEqual(self.client.get("/logout/", headers=headers).status_code, 403)
        self.assertEqual(self.signouts, [])
        r = self.client.get("/logout/", headers={"X-Test": "alice", "Sec-Fetch-Site": "same-origin"})
        self.assertEqual((r.status_code, r.headers["Location"]), (303, "/signed-out"))
        self.assertEqual(self.signouts, ["alice"])
        self.assertFalse(any("colvenna-bi-auth" in c for c in r.headers.getlist("Set-Cookie")))
        # Signed in like every other path; a user without a role may still sign out.
        self.assertEqual(self.client.get("/signed-out").status_code, 401)
        self.assertEqual(self.client.get("/logout/", headers={"X-Test": "erin", "Sec-Fetch-Site": "same-origin"}).status_code, 303)
        r = self.client.get("/signed-out", headers={"X-Test": "alice"})
        self.assertEqual(r.status_code, 303)
        self.assertTrue(r.headers["Location"].startswith("https://pool.example/logout?client_id=client123"))
        dropped = [c.split("=")[0] for c in r.headers.getlist("Set-Cookie") if "Expires=Thu, 01 Jan 1970" in c]
        self.assertEqual(sorted(dropped), ["colvenna-bi-auth-0", "colvenna-bi-auth-1", "colvenna-bi-auth-2", "colvenna-bi-auth-3", "session"])


if __name__ == "__main__":
    unittest.main()
