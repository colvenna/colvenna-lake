"""Offline tests of deploy/scripts/lib/catalog-admin.sh's ensure_grant (#22): its functions run on their
own, with curl replaced by a stand-in for colvenna-catalog's grants API backed by a JSON file. Like the
catalog (its V2 migration), the stand-in keeps one grant per principal, scope, database, object,
privilege and effect, expired or not, and answers 409 to another one; it answers 200 to a grant and to
a deletion, 404 to the deletion of a grant it does not have, and lists a principal's grants, expired
ones included. An install whose identical grant has expired, or expires within the renewal margin,
later than the term allows, or with other columns, has it deleted and granted anew; one that fits the
terms is kept. install-catalog.sh's grant of the platform identity admin, and install-operator.sh's of
the operator's identity, are run as the scripts have them."""
import datetime
import json
import os
import re
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.join(os.path.dirname(os.path.dirname(HERE)), "deploy", "scripts")
LIBRARY = os.path.join(SCRIPTS, "lib", "catalog-admin.sh")
INSTALL_CATALOG = os.path.join(SCRIPTS, "catalog", "install-catalog.sh")
INSTALL_OPERATOR = os.path.join(SCRIPTS, "operator", "install-operator.sh")
UTC = datetime.timezone.utc
GRANTS = "/colvenna/admin/v1/grants"

# The stand-in for curl: the arguments catalog-admin.sh gives it (post_grant's -o/-w form, admin_call's
# -X/--fail-with-body form), the store, and a log of the requests. A store's "force" maps a request
# ("POST /colvenna/admin/v1/grants") to the statuses its next calls answer instead, null for the
# store's own answer.
CURL = r'''
import datetime, json, sys, urllib.parse
store_path, log_path, *args = sys.argv[1:]
out = write = method = data = url = None
fail = False
i = 0
while i < len(args):
    a = args[i]
    if a in ("-o", "-w", "-X", "--data", "--cacert", "-K", "-H"):
        value = args[i + 1]
        if a == "-o":
            out = value
        elif a == "-w":
            write = value
        elif a == "-X":
            method = value
        elif a == "--data":
            data = value
        i += 2
        continue
    if a == "--fail-with-body":
        fail = True
    elif not a.startswith("-"):
        url = a
    i += 1
method = method or ("POST" if data is not None else "GET")
parts = urllib.parse.urlsplit(url)
path, query = parts.path, urllib.parse.parse_qs(parts.query)
with open(store_path) as f:
    store = json.load(f)
with open(log_path, "a") as f:
    f.write(f"{method} {path}{'?' + parts.query if parts.query else ''}\n")


def error(code, message):
    return code, {"resourceType": "GRANT", "resourceName": None, "message": message, "code": code}


def key(g):
    return (g["principal"], g["scope"], g.get("database") or "", g.get("object") or "", g["privilege"],
            g.get("effect", "ALLOW"))


def answer():
    if method == "POST" and path == "/colvenna/admin/v1/grants":
        g = json.loads(data)
        expires = datetime.datetime.strptime(g["expiresAt"], "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=datetime.timezone.utc)
        if expires <= datetime.datetime.now(datetime.timezone.utc):
            return error(400, "A grant must expire in the future.")
        if any(key(s) == key(g) for s in store["grants"]):
            return error(409, "This grant already exists.")
        store["next"] += 1
        stored = {"id": store["next"], "principal": g["principal"], "scope": g["scope"],
                  "database": g.get("database"), "object": g.get("object"), "privilege": g["privilege"],
                  "effect": g.get("effect", "ALLOW"), "columns": g.get("columns"), "purpose": g["purpose"],
                  "environment": "test", "approvedBy": g["approvedBy"], "approval": g["approval"],
                  "expiresAt": g["expiresAt"], "createdAt": "2026-11-02T00:00:00Z", "createdBy": "admin"}
        store["grants"].append(stored)
        return 200, stored
    if method == "GET" and path == "/colvenna/admin/v1/grants":
        principal = query.get("principal", [None])[0]
        return 200, {"grants": [g for g in sorted(store["grants"], key=lambda g: g["id"])
                                if principal is None or g["principal"] == principal]}
    if method == "DELETE" and path.startswith("/colvenna/admin/v1/grants/"):
        gid = int(path.rsplit("/", 1)[1])
        if not any(g["id"] == gid for g in store["grants"]):
            return error(404, f"Grant {gid} does not exist.")
        store["grants"] = [g for g in store["grants"] if g["id"] != gid]
        return 200, None
    return error(404, "no such route")


forced = store.get("force", {}).get(f"{method} {path}", [])
status = forced.pop(0) if forced else None
if status is None:
    status, body = answer()
else:
    body = {"message": "forced", "code": status}
with open(store_path, "w") as f:
    json.dump(store, f)
text = "" if body is None else json.dumps(body)
if out:
    with open(out, "w") as f:
        f.write(text)
else:
    sys.stdout.write(text)
if write == "%{http_code}":
    sys.stdout.write(str(status))
sys.exit(22 if fail and status >= 400 else 0)
'''


def function(path, name):
    with open(path) as f:
        text = f.read()
    start = text.index("\n%s() {" % name) + 1
    return text[start:text.index("\n}\n", start) + 3]


def statement(path, call):
    """The script's statements that end in `call`, with the variables they set, over continued lines."""
    with open(path) as f:
        text = f.read()
    return re.findall(r'^(?:[A-Z_]+=(?:"[^"\n]*"|\S+) +(?:\\\n *)?)*' + re.escape(call) + r"\n", text, re.M)


def instant(text):
    return datetime.datetime.strptime(text, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=UTC)


def at(**delta):
    return (datetime.datetime.now(UTC) + datetime.timedelta(**delta)).strftime("%Y-%m-%dT%H:%M:%SZ")


def grant(gid, principal, scope, privilege, expires, database=None, obj=None, columns=None):
    return {"id": gid, "principal": principal, "scope": scope, "database": database, "object": obj,
            "privilege": privilege, "effect": "ALLOW", "columns": columns, "purpose": "earlier",
            "environment": "test", "approvedBy": "requester", "approval": "earlier", "expiresAt": expires,
            "createdAt": "2026-10-25T00:00:00Z", "createdBy": "admin"}


class EnsureGrantTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = os.path.join(self.tmp.name, "store.json")
        self.log = os.path.join(self.tmp.name, "requests.txt")
        self.curl = os.path.join(self.tmp.name, "curl.py")
        with open(self.curl, "w") as f:
            f.write(CURL)
        self.seed([])

    def tearDown(self):
        self.tmp.cleanup()

    def seed(self, grants, force=None):
        with open(self.store, "w") as f:
            json.dump({"next": max([g["id"] for g in grants], default=0), "grants": grants,
                       "force": force or {}}, f)
        open(self.log, "w").close()

    def grants(self):
        with open(self.store) as f:
            return json.load(f)["grants"]

    def requests(self):
        with open(self.log) as f:
            return f.read().splitlines()

    def run_step(self, step, env=None):
        """Runs `step` with ensure_grant and the stand-in; its exit status and stderr."""
        script = """set -euo pipefail
ADMIN_DIR=%s ADMIN_PORT=8181 OPERATOR_PRINCIPAL=colvenna-operator
curl() { python3 %s %s %s "$@"; }
%s
%s
%s
%s
%s""" % (self.tmp.name, self.curl, self.store, self.log, function(LIBRARY, "admin_call"),
         function(LIBRARY, "post_grant"), function(LIBRARY, "ensure_grant"),
         function(LIBRARY, "ensure_flow_grants"), step)
        clean = {k: v for k, v in os.environ.items() if not k.startswith(("GRANT_", "FLOW_GRANTS"))}
        run = subprocess.run(["bash", "-c", script], env={**clean, **(env or {})}, capture_output=True,
                             text=True, timeout=60)
        return run.returncode, run.stderr

    def assertFreshFor(self, g, days):
        expected = datetime.datetime.now(UTC) + datetime.timedelta(days=days)
        self.assertLess(abs((instant(g["expiresAt"]) - expected).total_seconds()), 120, g)

    def test_a_new_grant_is_one_post(self):
        status, err = self.run_step("ensure_grant admin CATALOG - - MANAGE\n")
        self.assertEqual(status, 0, err)
        self.assertEqual(self.requests(), [f"POST {GRANTS}"])
        [g] = self.grants()
        self.assertEqual((g["principal"], g["scope"], g["database"], g["object"], g["privilege"], g["effect"]),
                         ("admin", "CATALOG", None, None, "MANAGE", "ALLOW"))
        self.assertFreshFor(g, 7)
        self.assertEqual(err, "")

    def test_the_install_renews_the_platform_identitys_expired_grant(self):
        # As on uat (2026-11-02): admin's catalog-wide MANAGE expired yesterday, the install runs again.
        [step] = statement(INSTALL_CATALOG, "ensure_grant admin CATALOG - - MANAGE")
        self.seed([grant(7, "admin", "CATALOG", "MANAGE", at(days=-1)),
                   grant(8, "admin", "DATABASE", "READ", at(days=6), database="other")])
        status, err = self.run_step(step)
        self.assertEqual(status, 0, err)
        self.assertEqual(self.requests(), [f"POST {GRANTS}", f"GET {GRANTS}?principal=admin",
                                           f"DELETE {GRANTS}/7", f"POST {GRANTS}"])
        same = [g for g in self.grants() if g["scope"] == "CATALOG"]
        self.assertEqual(len(same), 1, same)
        self.assertEqual(same[0]["id"], 9)
        self.assertGreater(instant(same[0]["expiresAt"]), datetime.datetime.now(UTC))
        self.assertFreshFor(same[0], 7)
        self.assertEqual(same[0]["purpose"], "platform operations: flow databases, fixtures and cleanup")
        self.assertEqual([g["id"] for g in self.grants() if g["scope"] != "CATALOG"], [8], "others untouched")
        self.assertRegex(err, r"^Grant admin CATALOG - - MANAGE: grant 7 expired \S+Z; replaced by grant 9, "
                              r"expiring \S+Z\n$")

    def test_a_grant_valid_for_the_whole_term_is_kept(self):
        self.seed([grant(3, "admin", "CATALOG", "MANAGE", at(days=7, hours=-1))])
        before = self.grants()
        status, err = self.run_step("ensure_grant admin CATALOG - - MANAGE\n")
        self.assertEqual(status, 0, err)
        self.assertEqual(self.requests(), [f"POST {GRANTS}", f"GET {GRANTS}?principal=admin"])
        self.assertEqual(self.grants(), before)
        self.assertEqual(err, "")

    def test_a_grant_expiring_within_the_margin_is_renewed(self):
        # A 7-day term's margin is 3 days: 2 days left is renewed, 4 days left is kept.
        for left, renewed in ((dict(days=2), True), (dict(days=3, minutes=-5), True), (dict(days=4), False)):
            with self.subTest(left=left):
                self.seed([grant(5, "admin", "CATALOG", "MANAGE", at(**left))])
                status, err = self.run_step("ensure_grant admin CATALOG - - MANAGE\n")
                self.assertEqual(status, 0, err)
                [g] = self.grants()
                if renewed:
                    self.assertIn(f"DELETE {GRANTS}/5", self.requests())
                    self.assertEqual(g["id"], 6)
                    self.assertFreshFor(g, 7)
                    self.assertIn("within the 3-day renewal margin", err)
                else:
                    self.assertEqual(g["id"], 5)
                    self.assertNotIn(f"DELETE {GRANTS}/5", self.requests())

    def test_the_margin_can_be_set(self):
        self.seed([grant(5, "admin", "CATALOG", "MANAGE", at(days=5))])
        status, err = self.run_step("ensure_grant admin CATALOG - - MANAGE\n", {"GRANT_RENEW_DAYS": "6"})
        self.assertEqual(status, 0, err)
        [g] = self.grants()
        self.assertEqual(g["id"], 6)
        self.assertIn("within the 6-day renewal margin", err)

    def test_a_grant_longer_than_the_term_is_renewed(self):
        self.seed([grant(5, "admin", "CATALOG", "MANAGE", at(days=30))])
        status, err = self.run_step("ensure_grant admin CATALOG - - MANAGE\n")
        self.assertEqual(status, 0, err)
        [g] = self.grants()
        self.assertEqual(g["id"], 6)
        self.assertFreshFor(g, 7)
        self.assertIn("later than a 7-day term allows", err)

    def test_a_grant_with_other_columns_is_renewed(self):
        self.seed([grant(5, "reader", "TABLE", "READ", at(days=6), database="db", obj="orders", columns=["id"])])
        status, err = self.run_step("ensure_grant reader TABLE db orders READ ALLOW name,id\n")
        self.assertEqual(status, 0, err)
        [g] = self.grants()
        self.assertEqual((g["id"], g["columns"]), (6, ["name", "id"]))
        self.assertIn("names columns id, not id,name", err)
        # The same columns in another order are the same grant.
        open(self.log, "w").close()
        status, err = self.run_step("ensure_grant reader TABLE db orders READ ALLOW id,name\n")
        self.assertEqual(status, 0, err)
        self.assertEqual([g["id"] for g in self.grants()], [6])
        self.assertNotIn(f"DELETE {GRANTS}/6", self.requests())

    def test_a_conflict_without_an_identical_grant_fails(self):
        # Another principal's, and admin's DENY, are not the grant asked for.
        self.seed([grant(1, "other", "CATALOG", "MANAGE", at(days=-1)),
                   {**grant(2, "admin", "CATALOG", "MANAGE", at(days=-1)), "effect": "DENY"}],
                  force={f"POST {GRANTS}": [409]})
        status, err = self.run_step("ensure_grant admin CATALOG - - MANAGE\n")
        self.assertNotEqual(status, 0)
        self.assertIn("Grant admin CATALOG - - MANAGE answered 409, but admin has no identical grant", err)
        self.assertEqual([g["id"] for g in self.grants()], [1, 2])
        self.assertNotIn("DELETE", " ".join(self.requests()))

    def test_a_failed_regrant_fails(self):
        self.seed([grant(7, "admin", "CATALOG", "MANAGE", at(days=-1))], force={f"POST {GRANTS}": [None, 500]})
        status, err = self.run_step("ensure_grant admin CATALOG - - MANAGE\n")
        self.assertNotEqual(status, 0)
        self.assertIn("Grant admin CATALOG - - MANAGE failed with 500 after grant 7 (expired ", err)

    def test_other_failures_still_fail(self):
        self.seed([], force={f"POST {GRANTS}": [404]})
        status, err = self.run_step("ensure_grant nobody CATALOG - - MANAGE\n")
        self.assertNotEqual(status, 0)
        self.assertIn("Grant nobody CATALOG - - MANAGE failed with 404", err)

    def test_the_operator_install_renews_its_grant_within_its_margin(self):
        # install-operator.sh grants for 90 days: renewed with 10 days left, kept with 80.
        [step] = statement(INSTALL_OPERATOR, 'ensure_grant "$OPERATOR_PRINCIPAL" CATALOG - - MANAGE')
        for left, renewed in ((10, True), (80, False)):
            with self.subTest(left=left):
                self.seed([grant(4, "colvenna-operator", "CATALOG", "MANAGE", at(days=left))])
                status, err = self.run_step(step)
                self.assertEqual(status, 0, err)
                [g] = self.grants()
                if renewed:
                    self.assertEqual(g["id"], 5)
                    self.assertFreshFor(g, 90)
                    self.assertEqual(g["approval"], "issue-24")
                    self.assertIn("within the 45-day renewal margin", err)
                else:
                    self.assertEqual(g["id"], 4)
                    self.assertEqual(err, "")

    def test_flow_grants_still_grant(self):
        status, err = self.run_step('ensure_flow_grants test-flink "DATABASE flow_a - MANAGE"\n'
                                    'FLOW_GRANTS="TABLE flow_a orders READ ALLOW id;TABLE flow_a items WRITE" '
                                    'ensure_flow_grants test-spark "DATABASE flow_a - MANAGE"\n')
        self.assertEqual(status, 0, err)
        self.assertEqual(sorted((g["principal"], g["scope"], g["object"], g["privilege"], g["columns"])
                                for g in self.grants()),
                         [("test-flink", "DATABASE", None, "MANAGE", None),
                          ("test-spark", "TABLE", "items", "WRITE", None),
                          ("test-spark", "TABLE", "orders", "READ", ["id"])])


class InstallGrantStepsTest(unittest.TestCase):
    """The installs grant their identities through ensure_grant, once, with the library sourced."""

    def test_install_catalog_grants_admin_through_ensure_grant(self):
        found = statement(INSTALL_CATALOG, "ensure_grant admin CATALOG - - MANAGE")
        self.assertEqual(len(found), 1, found)
        with open(INSTALL_CATALOG) as f:
            text = f.read()
        self.assertIn('source "$(dirname "$0")/../lib/catalog-admin.sh"', text)
        self.assertLess(text.index("\nadmin_open\n"), text.index(found[0]))
        self.assertNotIn("ensure_grant() {", text)

    def test_install_operator_grants_the_operator_through_ensure_grant(self):
        found = statement(INSTALL_OPERATOR, 'ensure_grant "$OPERATOR_PRINCIPAL" CATALOG - - MANAGE')
        self.assertEqual(len(found), 1, found)
        self.assertIn("GRANT_DAYS=90", found[0])
        with open(INSTALL_OPERATOR) as f:
            text = f.read()
        self.assertIn('source "$(dirname "$0")/../lib/catalog-admin.sh"', text)
        self.assertLess(text.index("\nadmin_open\n"), text.index(found[0]))
        self.assertNotIn("ensure_grant() {", text)


if __name__ == "__main__":
    unittest.main()
