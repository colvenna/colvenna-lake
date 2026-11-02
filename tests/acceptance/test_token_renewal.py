"""Offline tests of how an install keeps the platform's two Colvenna Catalog tokens working (#23):
install-catalog.sh's ensure_admin_token (the admin token, Secret colvenna-catalog-admin-token) and
install-operator.sh's ensure_operator_token (the operator's, Secret colvenna-operator-catalog-token),
each run on its own with lib/catalog-admin.sh from a scratch copy of deploy/ (test_third_party.Scratch).
Stub kubectl and curl share a JSON state: the cluster's Secrets, a catalog pod's files, and the
catalog's principals and tokens, which the stub curl authenticates as the catalog does (an unknown,
revoked or expired token, or a disabled principal's, is refused with 401).

For each token: valid with plenty of time left (nothing issued), valid but within the margin
(renewed, the Secret updated, the old token not revoked), invalid (the admin token: bootstrap-admin in
the pod again; the operator's: issued anew with the admin token) and missing (issued as before). Every
new token is recorded in its Secret with its id, which decides its expiry on the next run (a newer
token with the same description does not hide it); a Secret without one, as installs before left
it, falls back on the description. A check that gets no verdict (a 503, no answer) or an unexpected
state stops the install before anything is issued or written."""
import base64
import json
import os
import re
import time
import unittest
import uuid

from test_third_party import REPO, Scratch

CATALOG_SCRIPT = os.path.join(REPO, "deploy", "scripts", "catalog", "install-catalog.sh")
OPERATOR_SCRIPT = os.path.join(REPO, "deploy", "scripts", "operator", "install-operator.sh")
ADMIN_SECRET = "colvenna-catalog/colvenna-catalog-admin-token"
OPERATOR_SECRET = "colvenna-system/colvenna-operator-catalog-token"
DAY = 86400

STUB = r'''#!/usr/bin/env python3
import base64, datetime, json, os, re, sys, time, uuid
path = os.environ["TOKEN_STATE"]
tool, args = os.path.basename(sys.argv[0]), sys.argv[1:]
with open(path + ".calls", "a") as log:
    log.write(" ".join([tool] + args) + "\n")
if tool == "kubectl" and "port-forward" in args:
    # Up until admin_close stops it; it answers nothing itself (curl answers for the catalog).
    time.sleep(300)
    sys.exit(0)
state = json.load(open(path))
def save():
    json.dump(state, open(path, "w"), indent=1)
def option(name):
    return args[args.index(name) + 1] if name in args else None
def issue(principal, ttl, description, by):
    days = re.fullmatch(r"P([0-9]+)D", ttl)
    hours = re.fullmatch(r"PT([0-9]+)H", ttl)
    seconds = int(days[1]) * 86400 if days else int(hours[1]) * 3600
    token = {"id": str(uuid.uuid4()), "principal": principal, "secret": "colvenna_" + uuid.uuid4().hex,
             "description": description, "createdAt": time.time(), "createdBy": by,
             "expiresAt": time.time() + seconds, "revokedAt": None}
    state["tokens"].append(token)
    state["issued"].append({k: token[k] for k in ("principal", "description", "createdBy")} | {"ttl": ttl})
    return token

if tool == "kubectl":
    namespace = option("-n")
    if args[-3:] == ["apply", "-f", "-"]:
        manifest = json.load(sys.stdin)
        name = f'{manifest["metadata"]["namespace"]}/{manifest["metadata"]["name"]}'
        state["secrets"][name] = manifest["data"]
        state["applied"].append(name)
        save()
    elif "create" in args and "secret" in args:
        name = args[args.index("generic") + 1]
        data = {}
        for a in args:
            if a.startswith("--from-file="):
                key, file = a[len("--from-file="):].split("=", 1)
                data[key] = base64.b64encode(open(file, "rb").read()).decode()
            elif a.startswith("--from-literal="):
                key, value = a[len("--from-literal="):].split("=", 1)
                data[key] = base64.b64encode(value.encode()).decode()
        print(json.dumps({"apiVersion": "v1", "kind": "Secret", "data": data,
                          "metadata": {"name": name, "namespace": namespace}}))
    elif args[2:4] == ["get", "secret"]:
        secret = state["secrets"].get(f"{namespace}/{args[4]}")
        if secret is None:
            print(f'Error from server (NotFound): secrets "{args[4]}" not found', file=sys.stderr)
            sys.exit(1)
        if option("-o"):
            key = re.fullmatch(r"jsonpath=\{\.data\.(.*)\}", option("-o"))[1].replace("\\.", ".")
            print(secret.get(key, ""), end="")
    elif args[2:4] == ["get", "pods"]:
        print("colvenna-catalog-7d9f-abcde", end="")
    elif "exec" in args:
        command = args[args.index("--") + 1:]
        files = state["pod_files"]
        if "bootstrap-admin" in command:
            name, ttl, out = command[command.index("bootstrap-admin") + 1:]
            if out in files:
                print(f"java.nio.file.FileAlreadyExistsException: {out}", file=sys.stderr)
                sys.exit(1)
            state["principals"].setdefault(name, {"admin": True, "disabled": False})
            token = issue(name, ttl, "bootstrap", "bootstrap")
            files[out] = token["secret"]
            save()
            # Main.bootstrapAdmin's log line (logback's pattern, on standard output).
            if state.get("bootstrap_logs_id", True):
                expires = datetime.datetime.fromtimestamp(token["expiresAt"], datetime.timezone.utc)
                print(f'2026-11-02T10:00:00.000Z INFO  [main] com.colvenna.catalog.Main - Issued token {token["id"]} '
                      f'for admin {name} expiring {expires.strftime("%Y-%m-%dT%H:%M:%S.%fZ")}; written to {out}')
        elif command == ["sh", "-c", "rm -f /tmp/admin-token"]:
            files.pop("/tmp/admin-token", None)
            save()
        elif command[:2] == ["sh", "-c"] and command[2].startswith("cat /tmp/admin-token;"):
            if "/tmp/admin-token" not in files:
                sys.exit(1)
            print(files.pop("/tmp/admin-token"), end="")
            save()
        else:
            sys.exit(f"unexpected exec {command}")
    else:
        sys.exit(f"unexpected kubectl {args}")
elif tool == "curl":
    url = next(a for a in args if a.startswith("https://"))
    path_ = re.sub(r"^https://localhost:[0-9]+", "", url)
    if path_ == "/":
        sys.exit(0)
    body = option("--data")
    method = option("-X") or ("POST" if body is not None else "GET")
    bearer = re.search(r'Authorization: Bearer ([^"]*)"', open(option("-K")).read())[1]
    def answer(status, reply):
        text = json.dumps(reply)
        out = option("-o")
        if out:
            open(out, "w").write(text)
        else:
            sys.stdout.write(text)
        if option("-w"):
            sys.stdout.write(str(status))
        save()
        sys.exit(22 if status >= 400 and "--fail-with-body" in args else 0)
    m = re.fullmatch(r"/colvenna/admin/v1/principals/([^/]+)/tokens", path_)
    if m and method == "GET" and state.get("listing") == "000":
        # Nothing answers: the port-forward is gone.
        sys.stdout.write("000")
        sys.exit(7)
    if m and method == "GET" and state.get("listing") == "503":
        answer(503, {"message": "Too many token lookups in flight.", "code": 503})
    token = next((t for t in state["tokens"] if bearer and t["secret"] == bearer), None)
    who = state["principals"].get(token["principal"]) if token else None
    if token is None or token["revokedAt"] or token["expiresAt"] <= time.time() or who is None \
            or who["disabled"]:
        answer(401, {"message": "Bearer token is not valid.", "code": 401})
    if not who["admin"]:
        answer(403, {"message": "Admin only.", "code": 403})
    if m and method == "GET":
        answer(200, {"tokens": [{k: t[k] for k in ("id", "principal", "description", "createdAt", "createdBy",
                                                  "expiresAt", "revokedAt")} | {"revokedBy": None}
                                for t in state["tokens"] if t["principal"] == m[1]]})
    if m and method == "POST":
        if m[1] not in state["principals"]:
            answer(404, {"message": f"Principal {m[1]} does not exist.", "code": 404})
        request = json.loads(body)
        issued = issue(m[1], request.get("ttl", "P90D"), request.get("description"), token["principal"])
        answer(200, {"id": issued["id"], "principal": m[1], "token": issued["secret"],
                     "expiresAt": datetime.datetime.fromtimestamp(issued["expiresAt"], datetime.timezone.utc)
                     .strftime("%Y-%m-%dT%H:%M:%S.%fZ")})
    if path_ == "/colvenna/admin/v1/principals" and method == "POST":
        request = json.loads(body)
        state["created"].append(request)
        if request["name"] in state["principals"]:
            answer(409, {"message": f'Principal {request["name"]} already exists.', "code": 409})
        state["principals"][request["name"]] = {"admin": bool(request.get("admin")), "disabled": False}
        answer(200, {"name": request["name"]})
    answer(200, {})
'''


def function(script, name):
    with open(script) as f:
        text = f.read()
    start = text.index("\n%s() {" % name) + 1
    return text[start:text.index("\n}\n", start) + 3]


def assignments(script, *names):
    with open(script) as f:
        text = f.read()
    return "".join(re.search(rf"^{name}=.*\n", text, re.M)[0] for name in names)


def b64(text):
    return base64.b64encode(text.encode()).decode()


class TokenRenewal(unittest.TestCase):
    def setUp(self):
        self.scratch = Scratch(self)
        for tool in ("kubectl", "curl"):
            with open(os.path.join(self.scratch.bin, tool), "w") as f:
                f.write(STUB)
            os.chmod(os.path.join(self.scratch.bin, tool), 0o700)
        self.state_file = os.path.join(self.scratch.tmp.name, "tokens.json")
        self.state = {"principals": {"admin": {"admin": True, "disabled": False}}, "tokens": [],
                      "secrets": {"colvenna-catalog/colvenna-catalog-ca-key": {"ca.crt": b64("CA")}},
                      "pod_files": {}, "issued": [], "applied": [], "created": []}

    # -- the catalog's state ------------------------------------------------------------------------

    def token(self, principal, days, description, revoked=False):
        """A token of the principal expiring in `days` days (negative: expired); its secret."""
        secret = "colvenna_" + uuid.uuid4().hex
        self.state["tokens"].append({
            "id": str(uuid.uuid4()), "principal": principal, "secret": secret, "description": description,
            "createdAt": time.time() - DAY, "createdBy": "bootstrap", "expiresAt": time.time() + days * DAY,
            "revokedAt": time.time() if revoked else None})
        return secret

    def id_of(self, secret):
        return next(t["id"] for t in self.state["tokens"] if t["secret"] == secret)

    def hold(self, name, secret, recorded=None):
        """The Secret with the token; with the id an install recorded beside it, if given (True: the
        token's own); without one, as an install before ids were recorded left it."""
        self.state["secrets"][name] = {"token": b64(secret)}
        if recorded is not None:
            self.state["secrets"][name]["id"] = b64(self.id_of(secret) if recorded is True else recorded)

    def held(self, name):
        return base64.b64decode(self.state["secrets"][name]["token"]).decode()

    def recorded(self, name):
        return base64.b64decode(self.state["secrets"][name]["id"]).decode()

    def works(self, secret):
        token = next(t for t in self.state["tokens"] if t["secret"] == secret)
        return not token["revokedAt"] and token["expiresAt"] > time.time()

    def expires_in(self, secret):
        token = next(t for t in self.state["tokens"] if t["secret"] == secret)
        return (token["expiresAt"] - time.time()) / DAY

    def calls(self):
        if not os.path.exists(self.state_file + ".calls"):
            return []
        with open(self.state_file + ".calls") as f:
            return f.read().splitlines()

    # -- runs ---------------------------------------------------------------------------------------

    def run_probe(self, body, **env):
        with open(self.state_file, "w") as f:
            json.dump(self.state, f)
        probe = os.path.join(self.scratch.root, "deploy", "probe.sh")
        with open(probe, "w") as f:
            f.write('source "$(dirname "$0")/scripts/lib/catalog-admin.sh"\n' + body)
        result = self.scratch.run("deploy/probe.sh", TOKEN_STATE=self.state_file, **env)
        with open(self.state_file) as f:
            self.state = json.load(f)
        # No token in any process's arguments.
        secrets = [t["secret"] for t in self.state["tokens"]]
        leaked = [call for call in self.calls() if any(s in call for s in secrets)]
        self.assertEqual(leaked, [])
        return result

    def admin(self, replace="", **env):
        """ensure_admin_token, after the bash in `replace` (functions it replaces)."""
        work = os.path.join(self.scratch.tmp.name, "work")
        evidence = os.path.join(self.scratch.tmp.name, "evidence")
        return self.run_probe(
            f'evidence={evidence} work={work}\nmkdir -p "$evidence" "$work"\n'
            + assignments(CATALOG_SCRIPT, "ADMIN_TOKEN_TTL", "ADMIN_TOKEN_SECRET")
            + function(CATALOG_SCRIPT, "bootstrap_admin_token") + function(CATALOG_SCRIPT, "ensure_admin_token")
            + replace + "ensure_admin_token\n", **env)

    def operator(self, replace="", **env):
        return self.run_probe(
            assignments(OPERATOR_SCRIPT, "OPERATOR_TOKEN_SECRET") + function(OPERATOR_SCRIPT, "ensure_operator_token")
            + replace + "admin_open\ntrap admin_close EXIT\nensure_operator_token\n", **env)

    def bootstrapped(self):
        return [c for c in self.calls() if "bootstrap-admin" in c]

    # -- the admin token ----------------------------------------------------------------------------

    def test_admin_token_with_plenty_of_time_is_kept(self):
        old = self.token("admin", 75, "bootstrap")
        self.hold(ADMIN_SECRET, old)
        result = self.admin()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.state["issued"], [])
        self.assertEqual(self.state["applied"], [])
        self.assertEqual(self.held(ADMIN_SECRET), old)
        self.assertRegex(result.stdout, r"^admin token: keep \(expires \S+, in 7[45]\.[0-9] days; no id recorded: "
                                        r"the latest token described as bootstrap or colvenna-catalog-admin-token\)\n$")

    def test_admin_token_within_the_margin_is_renewed_through_the_admin_api(self):
        old = self.token("admin", 10, "bootstrap")
        self.hold(ADMIN_SECRET, old)
        result = self.admin()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.state["issued"], [{"principal": "admin", "description": "colvenna-catalog-admin-token",
                                                 "createdBy": "admin", "ttl": "P90D"}])
        new = self.held(ADMIN_SECRET)
        self.assertNotEqual(new, old)
        self.assertTrue(self.works(new))
        self.assertAlmostEqual(self.expires_in(new), 90, places=1)
        # The old token expires by itself: whoever still holds it keeps working until then.
        self.assertTrue(self.works(old))
        self.assertEqual(self.bootstrapped(), [])
        self.assertIn("admin token: renew (expires ", result.stdout)
        # Recorded with its id, by which the next run finds its expiry.
        self.assertEqual(self.recorded(ADMIN_SECRET), self.id_of(new))
        self.assertRegex(result.stdout, r"admin token: new \(expires \S+, in (89\.9|90\.0) days; token \S+, "
                                        r"the recorded id\)\n$")

    def test_a_renewed_admin_token_is_found_by_its_description(self):
        # The previous install's renewal, the latest of the described tokens, decides; the older
        # bootstrap token, a revoked one and another script's longer-lived one do not.
        self.token("admin", 3, "bootstrap")
        self.token("admin", 200, "colvenna-catalog-admin-token", revoked=True)
        self.token("admin", 300, "issue 113")
        current = self.token("admin", 70, "colvenna-catalog-admin-token")
        self.hold(ADMIN_SECRET, current)
        result = self.admin()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.state["issued"], [])
        self.assertIn("admin token: keep (expires ", result.stdout)

    def test_an_admin_token_whose_expiry_is_not_listed_is_renewed(self):
        # Only another script's token is listed: the Secret's could expire any time.
        self.hold(ADMIN_SECRET, self.token("admin", 300, "issue 113"))
        result = self.admin()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([i["description"] for i in self.state["issued"]], ["colvenna-catalog-admin-token"])
        self.assertIn("admin token: renew (no id recorded, no unrevoked token described as bootstrap or "
                      "colvenna-catalog-admin-token)", result.stdout)

    def test_the_recorded_id_decides_the_expiry(self):
        # A newer token with the Secret's description (an issue whose Secret write failed, say) does
        # not hide the Secret's own, which expires in 10 days; without the id, it does.
        for name, principal, description, run in (
                (ADMIN_SECRET, "admin", "colvenna-catalog-admin-token", self.admin),
                (OPERATOR_SECRET, "colvenna-operator", "colvenna-operator-catalog-token", self.operator)):
            for recorded in (True, None):
                with self.subTest(secret=name, recorded=recorded):
                    self.setUp()
                    if principal != "admin":
                        self.with_operator()
                    current = self.token(principal, 10, description)
                    self.token(principal, 80, description)
                    self.hold(name, current, recorded)
                    result = run()
                    self.assertEqual(result.returncode, 0, result.stderr)
                    if recorded:
                        self.assertIn("token: renew (expires ", result.stdout)
                        self.assertIn(f"in 10.0 days; token {self.id_of(current)}, the recorded id)", result.stdout)
                        self.assertEqual(len(self.state["issued"]), 1)
                        self.assertEqual(self.recorded(name), self.id_of(self.held(name)))
                    else:
                        self.assertIn("token: keep (expires ", result.stdout)
                        self.assertEqual(self.state["issued"], [])

    def test_a_recorded_id_not_among_the_unrevoked_tokens_is_renewed(self):
        revoked = self.token("admin", 80, "colvenna-catalog-admin-token", revoked=True)
        self.hold(ADMIN_SECRET, self.token("admin", 80, "colvenna-catalog-admin-token"), self.id_of(revoked))
        result = self.admin()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"admin token: renew (the recorded id {self.id_of(revoked)} is not among admin's unrevoked "
                      "tokens)", result.stdout)
        self.assertEqual(self.recorded(ADMIN_SECRET), self.id_of(self.held(ADMIN_SECRET)))

    def test_the_margin_is_token_renew_days(self):
        self.hold(ADMIN_SECRET, self.token("admin", 75, "bootstrap"))
        result = self.admin(TOKEN_RENEW_DAYS="90")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.state["issued"]), 1)
        result = self.admin(TOKEN_RENEW_DAYS="soon")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("TOKEN_RENEW_DAYS is a whole number of days, not 'soon'", result.stderr)

    def test_an_invalid_admin_token_is_bootstrapped_again_in_the_pod(self):
        for case, held in (("expired", lambda: self.token("admin", -1, "bootstrap")),
                           ("revoked", lambda: self.token("admin", 60, "bootstrap", revoked=True)),
                           ("unknown after a restore", lambda: "colvenna_" + uuid.uuid4().hex)):
            with self.subTest(case=case):
                self.setUp()
                old = held()
                self.hold(ADMIN_SECRET, old)
                # A file an interrupted run left: bootstrap-admin writes only a new one.
                self.state["pod_files"]["/tmp/admin-token"] = "colvenna_stale"
                result = self.admin()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(len(self.bootstrapped()), 1)
                self.assertIn("bootstrap-admin admin P90D /tmp/admin-token", self.bootstrapped()[0])
                self.assertEqual(self.state["issued"], [{"principal": "admin", "description": "bootstrap",
                                                         "createdBy": "bootstrap", "ttl": "P90D"}])
                new = self.held(ADMIN_SECRET)
                self.assertNotEqual(new, old)
                self.assertTrue(self.works(new))
                # The id bootstrap-admin logged.
                self.assertEqual(self.recorded(ADMIN_SECRET), self.id_of(new))
                self.assertEqual(self.state["pod_files"], {})
                self.assertIn("admin token: invalid (the catalog refuses it: ", result.stdout)
                self.assertIn("admin token: new (expires ", result.stdout)

    def test_a_missing_admin_token_is_bootstrapped_as_before(self):
        del self.state["principals"]["admin"]
        result = self.admin()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.bootstrapped()), 1)
        self.assertEqual(self.state["principals"], {"admin": {"admin": True, "disabled": False}})
        self.assertTrue(self.works(self.held(ADMIN_SECRET)))
        self.assertEqual(self.state["pod_files"], {})
        self.assertIn("admin token: missing (no Secret colvenna-catalog-admin-token)", result.stdout)
        self.assertEqual(self.recorded(ADMIN_SECRET), self.id_of(self.held(ADMIN_SECRET)))
        self.assertIn("the recorded id)", result.stdout)

    def test_a_bootstrap_that_logs_no_id_falls_back_on_the_description(self):
        self.state["bootstrap_logs_id"] = False
        result = self.admin()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("bootstrap-admin logged no token id", result.stderr)
        self.assertEqual(self.recorded(ADMIN_SECRET), "-")
        self.assertTrue(self.works(self.held(ADMIN_SECRET)))
        self.assertIn("admin token: new (expires ", result.stdout)
        self.assertIn("no id recorded: the latest token described as bootstrap", result.stdout)

    def test_a_check_that_gets_no_verdict_stops_the_install(self):
        # A 503 (the catalog busy), or no answer at all (000: the port-forward gone), is no verdict on
        # the token: nothing is bootstrapped, issued or written.
        for listing in ("503", "000"):
            for name, run in ((ADMIN_SECRET, self.admin), (OPERATOR_SECRET, self.operator)):
                with self.subTest(listing=listing, secret=name):
                    self.setUp()
                    old = self.with_operator(10) if name == OPERATOR_SECRET else self.token("admin", 10, "bootstrap")
                    if name == ADMIN_SECRET:
                        self.hold(ADMIN_SECRET, old)
                    self.state["listing"] = listing
                    result = run()
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(f"failed with {listing}", result.stderr)
                    self.assertEqual(self.bootstrapped(), [])
                    self.assertEqual(self.state["issued"], [])
                    self.assertEqual(self.state["applied"], [])
                    self.assertEqual(self.held(name), old)

    def test_a_disabled_admin_fails_the_install(self):
        self.hold(ADMIN_SECRET, self.token("admin", 60, "bootstrap"))
        self.state["principals"]["admin"]["disabled"] = True
        result = self.admin()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("refuses the admin token just issued", result.stderr)

    def test_an_unexpected_state_issues_nothing(self):
        # Only missing and invalid lead to bootstrap-admin, only those and renew to a new token.
        unexpected = 'secret_token_state() { echo "forbidden (403)"; }\n'
        for name, run in ((ADMIN_SECRET, self.admin), (OPERATOR_SECRET, self.operator)):
            with self.subTest(secret=name):
                self.setUp()
                old = self.with_operator(60) if name == OPERATOR_SECRET else self.token("admin", 60, "bootstrap")
                if name == ADMIN_SECRET:
                    self.hold(ADMIN_SECRET, old)
                result = run(replace=unexpected)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("token, forbidden (403): nothing issued", result.stderr)
                self.assertEqual(self.bootstrapped(), [])
                self.assertEqual(self.state["issued"], [])
                self.assertEqual(self.held(name), old)

    # -- the operator's token -----------------------------------------------------------------------

    def with_operator(self, days=None, description="colvenna-operator-catalog-token", **kind):
        """The admin token in its Secret, the operator principal, and, given days, its token in its."""
        self.hold(ADMIN_SECRET, self.token("admin", 80, "bootstrap"))
        self.state["principals"]["colvenna-operator"] = {"admin": True, "disabled": False}
        if days is not None:
            secret = self.token("colvenna-operator", days, description, **kind)
            self.hold(OPERATOR_SECRET, secret)
            return secret
        return None

    def test_operator_token_with_plenty_of_time_is_kept(self):
        old = self.with_operator(70)
        result = self.operator()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.state["issued"], [])
        self.assertEqual(self.state["applied"], [])
        self.assertEqual(self.held(OPERATOR_SECRET), old)
        self.assertIn("operator token: keep (expires ", result.stdout)

    def test_operator_token_within_the_margin_is_renewed(self):
        old = self.with_operator(29)
        result = self.operator()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.state["issued"], [{"principal": "colvenna-operator",
                                                 "description": "colvenna-operator-catalog-token",
                                                 "createdBy": "admin", "ttl": "P90D"}])
        self.assertEqual(self.state["applied"], [OPERATOR_SECRET])
        new = self.held(OPERATOR_SECRET)
        self.assertNotEqual(new, old)
        self.assertTrue(self.works(new))
        self.assertTrue(self.works(old))
        self.assertIn("operator token: renew (expires ", result.stdout)
        self.assertIn("operator token: new (expires ", result.stdout)
        self.assertEqual(self.recorded(OPERATOR_SECRET), self.id_of(new))

    def test_operator_token_lifetime_is_operator_token_ttl(self):
        self.with_operator(5)
        result = self.operator(OPERATOR_TOKEN_TTL="P120D")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([i["ttl"] for i in self.state["issued"]], ["P120D"])
        self.assertAlmostEqual(self.expires_in(self.held(OPERATOR_SECRET)), 120, places=1)

    def test_an_invalid_operator_token_is_issued_anew_with_the_admin_token(self):
        for case, kind in (("expired", {"days": -2}), ("revoked", {"days": 60, "revoked": True})):
            with self.subTest(case=case):
                self.setUp()
                old = self.with_operator(**kind)
                result = self.operator()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual([(i["principal"], i["createdBy"]) for i in self.state["issued"]],
                                 [("colvenna-operator", "admin")])
                self.assertNotEqual(self.held(OPERATOR_SECRET), old)
                self.assertTrue(self.works(self.held(OPERATOR_SECRET)))
                self.assertEqual(self.bootstrapped(), [])
                self.assertIn("operator token: invalid (the catalog refuses it: ", result.stdout)

    def test_an_operator_lost_in_a_restore_is_made_again_an_admin(self):
        # The metadata database restored to before the operator was made: principal and token gone.
        self.with_operator()
        del self.state["principals"]["colvenna-operator"]
        self.hold(OPERATOR_SECRET, "colvenna_" + uuid.uuid4().hex)
        result = self.operator()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.state["principals"]["colvenna-operator"], {"admin": True, "disabled": False})
        self.assertTrue(self.works(self.held(OPERATOR_SECRET)))

    def test_a_missing_operator_token_is_issued_as_before(self):
        self.with_operator()
        del self.state["principals"]["colvenna-operator"]
        result = self.operator()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.state["created"][0], {"name": "colvenna-operator", "description": "colvenna-operator",
                                                    "admin": True})
        self.assertEqual(self.state["issued"], [{"principal": "colvenna-operator",
                                                 "description": "colvenna-operator-catalog-token",
                                                 "createdBy": "admin", "ttl": "P90D"}])
        self.assertEqual(self.state["applied"], [OPERATOR_SECRET])
        self.assertTrue(self.works(self.held(OPERATOR_SECRET)))
        self.assertEqual(self.recorded(OPERATOR_SECRET), self.id_of(self.held(OPERATOR_SECRET)))
        self.assertIn("operator token: missing (no Secret colvenna-operator-catalog-token)", result.stdout)

    def test_a_disabled_operator_fails_the_install(self):
        self.with_operator(60)
        self.state["principals"]["colvenna-operator"]["disabled"] = True
        result = self.operator()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("refuses the operator token just issued", result.stderr)


if __name__ == "__main__":
    unittest.main()
