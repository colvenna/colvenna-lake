"""Offline tests of install-catalog.sh's private CA (deploy/scripts/catalog/install-catalog.sh, #24): its
functions ensure_ca and reissue_ca run on their own with this host's openssl, and kubectl replaced by a
stand-in that keeps Secrets in files. A CA certificate valid for long is kept as it is; one that expires
within CA_RENEW_DAYS (default 90), or has expired, is issued anew with the same key, subject and key
identifier, and stored beside the same key. A server certificate signed after that, as install-catalog.sh
and install-polaris.sh sign theirs, verifies against the old CA certificate and the new one alike, and
one signed before verifies against the new one: a client still holding the old certificate is not cut
off while it is valid. The platform's copies of the CA are made from the Secret on every install, after
the catalog step."""
import base64
import datetime
import json
import os
import re
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
DEPLOY = os.path.join(os.path.dirname(os.path.dirname(HERE)), "deploy")
INSTALL_CATALOG = os.path.join(DEPLOY, "scripts", "catalog", "install-catalog.sh")
INSTALL_POLARIS = os.path.join(DEPLOY, "scripts", "open-layer", "install-polaris.sh")
NAMESPACE = "colvenna-catalog"
SECRET = "colvenna-catalog-ca-key"
SUBJECT = "/CN=colvenna-catalog private CA"

# The stand-in for kubectl: Secrets as files under <root>/<namespace>/<name>/<key>, and a log of the
# calls. It answers get secret (with -o jsonpath='{.data.<key>}'), create secret generic --from-file
# (refused when the Secret exists, unless --dry-run=client -o yaml, which prints the manifest) and
# apply -f - of such a manifest.
KUBECTL = r'''
import base64, json, os, sys
root, log, *args = sys.argv[1:]
with open(log, "a") as f:
    f.write(" ".join(a for a in args if not a.startswith("--from-file")) + "\n")
namespace = "default"
if "-n" in args:
    i = args.index("-n")
    namespace = args[i + 1]
    del args[i:i + 2]
verb = args[0]
if verb == "get" and args[1] == "secret":
    path = os.path.join(root, namespace, args[2])
    if not os.path.isdir(path):
        sys.exit(f'Error from server (NotFound): secrets "{args[2]}" not found')
    if "-o" in args:
        field = args[args.index("-o") + 1]
        key = field[len("jsonpath={.data."):-1].replace("\\.", ".")
        with open(os.path.join(path, key), "rb") as f:
            sys.stdout.write(base64.b64encode(f.read()).decode())
    sys.exit()
if verb == "create" and args[1:3] == ["secret", "generic"]:
    name = args[3]
    data = {}
    for a in args[4:]:
        if a.startswith("--from-file="):
            key, _, source = a[len("--from-file="):].partition("=")
            with open(source, "rb") as f:
                data[key] = base64.b64encode(f.read()).decode()
    if "--dry-run=client" in args:
        print(json.dumps({"apiVersion": "v1", "kind": "Secret",
                          "metadata": {"name": name, "namespace": namespace}, "data": data}))
        sys.exit()
    path = os.path.join(root, namespace, name)
    if os.path.isdir(path):
        sys.exit(f'error: failed to create secret secrets "{name}" already exists')
    manifest = {"metadata": {"name": name, "namespace": namespace}, "data": data}
elif verb == "apply" and args[1:3] == ["-f", "-"]:
    manifest = json.load(sys.stdin)
else:
    sys.exit(f"the stand-in does not answer kubectl {' '.join(args)}")
path = os.path.join(root, manifest["metadata"]["namespace"], manifest["metadata"]["name"])
os.makedirs(path, exist_ok=True)
for key in os.listdir(path):
    os.remove(os.path.join(path, key))
for key, value in manifest["data"].items():
    with open(os.path.join(path, key), "wb") as f:
        f.write(base64.b64decode(value))
'''


def function(path, name):
    with open(path) as f:
        text = f.read()
    start = text.index("\n%s() {" % name) + 1
    return text[start:text.index("\n}\n", start) + 3]


def between(path, first, last):
    """The script's lines from the one starting with `first` to the one starting with `last`, both in."""
    with open(path) as f:
        text = f.read()
    start = text.index("\n" + first) + 1
    end = text.index("\n", text.index("\n" + last, start) + 1) + 1
    return text[start:end]


def openssl(*args, check=True):
    return subprocess.run(["openssl", *args], capture_output=True, text=True, check=check)


def field(cert, *option):
    return openssl("x509", "-in", cert, "-noout", *option).stdout


def dn(cert, which):
    """The certificate's subject or issuer, attribute by attribute, with each one's string type."""
    return field(cert, "-" + which, "-nameopt", "multiline,show_type").split("\n", 1)[1]


def key_id(cert, extension):
    return field(cert, "-ext", extension).splitlines()[1].strip().removeprefix("keyid:")


def not_after(cert):
    text = field(cert, "-enddate").strip().split("=", 1)[1]
    return datetime.datetime.strptime(text, "%b %d %H:%M:%S %Y %Z").replace(tzinfo=datetime.timezone.utc)


def stamp(t):
    return t.strftime("%Y%m%d%H%M%SZ")


class CaTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name
        self.secrets = os.path.join(self.dir, "secrets")
        self.log = os.path.join(self.dir, "kubectl.txt")
        self.kubectl = os.path.join(self.dir, "kubectl.py")
        self.work = os.path.join(self.dir, "work")
        os.makedirs(self.work)
        with open(self.kubectl, "w") as f:
            f.write(KUBECTL)
        open(self.log, "w").close()

    def tearDown(self):
        self.tmp.cleanup()

    def path(self, name):
        return os.path.join(self.dir, name)

    def make_ca(self, start_days, end_days, name="old"):
        """A CA made as install-catalog.sh first makes it (P-256, its subject, the CA extensions), valid
        from start_days to end_days from now: the key and certificate's paths."""
        key, csr, cert = self.path(f"{name}.key"), self.path(f"{name}.csr"), self.path(f"{name}.crt")
        ca = self.path(f"{name}-ca")
        os.makedirs(ca)
        open(os.path.join(ca, "index.txt"), "w").close()
        config = os.path.join(ca, "openssl.cnf")
        with open(config, "w") as f:
            f.write(f"""[ca]
default_ca = d
[d]
database = {ca}/index.txt
new_certs_dir = {ca}
serial = {ca}/serial
default_md = sha256
policy = p
x509_extensions = v3_ca
unique_subject = no
[p]
commonName = supplied
[v3_ca]
basicConstraints = critical, CA:TRUE
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
""")
        with open(os.path.join(ca, "serial"), "w") as f:
            f.write("1000\n")
        now = datetime.datetime.now(datetime.timezone.utc)
        openssl("req", "-new", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
                "-subj", SUBJECT, "-keyout", key, "-out", csr)
        openssl("ca", "-batch", "-config", config, "-selfsign", "-keyfile", key, "-in", csr, "-notext",
                "-startdate", stamp(now + datetime.timedelta(days=start_days)),
                "-enddate", stamp(now + datetime.timedelta(days=end_days)), "-out", cert)
        return key, cert

    def store(self, key, cert):
        secret = os.path.join(self.secrets, NAMESPACE, SECRET)
        os.makedirs(secret)
        for name, source in (("ca.key", key), ("ca.crt", cert)):
            with open(source, "rb") as f, open(os.path.join(secret, name), "wb") as out:
                out.write(f.read())

    def stored(self, name):
        with open(os.path.join(self.secrets, NAMESPACE, SECRET, name), "rb") as f:
            return f.read()

    def bash(self, body, env=None):
        script = """set -euo pipefail
CATALOG_NAMESPACE=%s work=%s
kubectl() { python3 %s %s %s "$@"; }
%s%s%s""" % (NAMESPACE, self.work, self.kubectl, self.secrets, self.log,
             function(INSTALL_CATALOG, "reissue_ca"), function(INSTALL_CATALOG, "ensure_ca"), body)
        clean = {k: v for k, v in os.environ.items() if k != "CA_RENEW_DAYS"}
        return subprocess.run(["bash", "-c", script], env={**clean, **(env or {})}, capture_output=True,
                              text=True, timeout=120)

    def ensure(self, env=None):
        run = self.bash('ensure_ca "$work"\n', env)
        self.assertEqual(run.returncode, 0, run.stderr)
        return run.stdout

    def sign_server(self):
        """The catalog's server certificate, signed as install-catalog.sh signs it, from $work/ca.*."""
        block = between(INSTALL_CATALOG, "service=colvenna-catalog", "  -extfile <(printf 'subjectAltName")
        run = self.bash(block)
        self.assertEqual(run.returncode, 0, run.stderr)
        return os.path.join(self.work, "server.crt")

    def sign_polaris(self):
        """Polaris's certificate, signed as install-polaris.sh signs it, from the CA in the Secret."""
        block = between(INSTALL_POLARIS, "kubectl -n \"$CATALOG_NAMESPACE\" get secret colvenna-catalog-ca-key",
                        "  -extfile <(printf 'subjectAltName")
        polaris = os.path.join(self.dir, "polaris")
        os.makedirs(polaris, exist_ok=True)
        run = self.bash("work=%s POLARIS_RELEASE=polaris POLARIS_NAMESPACE=polaris\n%s" % (polaris, block))
        self.assertEqual(run.returncode, 0, run.stderr)
        return os.path.join(polaris, "tls.crt")

    def verifies(self, ca, leaf, *extra):
        return openssl("verify", "-CAfile", ca, *extra, leaf, check=False)

    def assertReissued(self, old_key, old, out):
        new = os.path.join(self.work, "ca.crt")
        with open(old_key, "rb") as f:
            self.assertEqual(self.stored("ca.key"), f.read(), "the key is kept")
        with open(new, "rb") as f:
            self.assertEqual(self.stored("ca.crt"), f.read(), "the new certificate is stored")
        with open(old, "rb") as f:
            self.assertNotEqual(self.stored("ca.crt"), f.read())
        self.assertEqual(dn(new, "subject"), dn(old, "subject"))
        self.assertEqual(dn(new, "issuer"), dn(old, "subject"))
        self.assertEqual(field(new, "-pubkey"), field(old, "-pubkey"))
        self.assertEqual(key_id(new, "subjectKeyIdentifier"), key_id(old, "subjectKeyIdentifier"))
        self.assertEqual(key_id(new, "authorityKeyIdentifier"), key_id(old, "subjectKeyIdentifier"))
        self.assertNotEqual(field(new, "-serial"), field(old, "-serial"))
        expected = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=825)
        self.assertLess(abs((not_after(new) - expected).total_seconds()), 300)
        text = field(new, "-text")
        self.assertRegex(text, r"Basic Constraints: critical\s+CA:TRUE")
        self.assertRegex(text, r"Key Usage: critical\s+Certificate Sign, CRL Sign")
        self.assertRegex(out, r"^colvenna-catalog CA: issued anew with the same key and subject, notAfter=.+ "
                              r"before, now notAfter=.+\n$")
        return new

    def assertServerChainHolds(self, old, new, before=None, old_valid=True):
        """A server certificate signed now verifies against both CA certificates (the old one, when it
        has expired, only with its validity set aside), with the new one sent beside it as servers
        send it; one signed before the renewal verifies against the new one."""
        time = () if old_valid else ("-no_check_time",)
        ski = key_id(new, "subjectKeyIdentifier")
        for leaf in (self.sign_server(), self.sign_polaris()):
            with self.subTest(leaf=os.path.basename(leaf)):
                self.assertEqual(key_id(leaf, "authorityKeyIdentifier"), ski)
                self.assertEqual(dn(leaf, "issuer"), dn(old, "subject"))
                for ca, extra in ((new, ()), (old, time), (old, time + ("-untrusted", new))):
                    run = self.verifies(ca, leaf, *extra)
                    self.assertEqual(run.returncode, 0, (ca, extra, run.stdout, run.stderr))
                if not old_valid:
                    run = self.verifies(old, leaf)
                    self.assertNotEqual(run.returncode, 0, "an expired CA certificate verifies nothing")
                    self.assertIn("expired", run.stdout + run.stderr)
        if before:
            run = self.verifies(new, before)
            self.assertEqual(run.returncode, 0, (run.stdout, run.stderr))

    def test_a_ca_valid_for_long_is_kept_as_it_is(self):
        key, cert = self.make_ca(-30, 795)
        self.store(key, cert)
        out = self.ensure()
        with open(cert, "rb") as f:
            self.assertEqual(self.stored("ca.crt"), f.read())
        with open(os.path.join(self.work, "ca.crt"), "rb") as f, open(cert, "rb") as g:
            self.assertEqual(f.read(), g.read())
        with open(key, "rb") as f:
            self.assertEqual(self.stored("ca.key"), f.read())
        with open(self.log) as f:
            calls = f.read()
        self.assertNotIn("create", calls)
        self.assertNotIn("apply", calls)
        self.assertRegex(out, r"^colvenna-catalog CA: kept, notAfter=")

    def test_a_ca_expiring_within_the_margin_is_issued_anew(self):
        key, old = self.make_ca(-795, 30)
        self.store(key, old)
        # A server certificate the old CA certificate signed, as a client may still be served.
        with open(key, "rb") as f, open(os.path.join(self.work, "ca.key"), "wb") as out:
            out.write(f.read())
        with open(old, "rb") as f, open(os.path.join(self.work, "ca.crt"), "wb") as out:
            out.write(f.read())
        before = self.path("before.crt")
        os.rename(self.sign_server(), before)
        out = self.ensure()
        new = self.assertReissued(key, old, out)
        self.assertServerChainHolds(old, new, before=before)

    def test_an_expired_ca_is_issued_anew(self):
        key, old = self.make_ca(-826, -1)
        self.store(key, old)
        new = self.assertReissued(key, old, self.ensure())
        self.assertServerChainHolds(old, new, old_valid=False)

    def test_the_margin_can_be_set(self):
        key, cert = self.make_ca(-725, 100)
        self.store(key, cert)
        self.assertRegex(self.ensure(), r"^colvenna-catalog CA: kept, ")
        self.assertRegex(self.ensure({"CA_RENEW_DAYS": "120"}), r"^colvenna-catalog CA: issued anew ")

    def test_the_margin_is_a_whole_number_of_days(self):
        key, cert = self.make_ca(-30, 795)
        self.store(key, cert)
        marker = self.path("evaluated")
        for value in ("-5", "1.5", "30d", "x[$(touch %s)]" % marker):
            with self.subTest(value=value):
                run = self.bash('ensure_ca "$work"\n', {"CA_RENEW_DAYS": value})
                self.assertNotEqual(run.returncode, 0)
                self.assertIn("CA_RENEW_DAYS is a whole number of days", run.stderr)
                self.assertFalse(os.path.exists(marker), "the value is never evaluated")
        self.assertRegex(self.ensure({"CA_RENEW_DAYS": "090"}), r"^colvenna-catalog CA: kept, ")
        with open(cert, "rb") as f:
            self.assertEqual(self.stored("ca.crt"), f.read())

    def test_a_reissued_ca_is_then_kept(self):
        key, old = self.make_ca(-826, -1)
        self.store(key, old)
        self.ensure()
        stored = self.stored("ca.crt")
        self.assertRegex(self.ensure(), r"^colvenna-catalog CA: kept, ")
        self.assertEqual(self.stored("ca.crt"), stored)

    def test_the_first_install_makes_the_ca(self):
        out = self.ensure()
        self.assertRegex(out, r"^colvenna-catalog CA: made, notAfter=")
        cert = os.path.join(self.work, "ca.crt")
        expected = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=825)
        self.assertLess(abs((not_after(cert) - expected).total_seconds()), 300)
        self.assertEqual(field(cert, "-subject").strip(), "subject=CN = colvenna-catalog private CA")
        with open(cert, "rb") as f:
            self.assertEqual(self.stored("ca.crt"), f.read())

    def test_a_key_that_is_not_the_certificates_is_refused(self):
        _, old = self.make_ca(-826, -1)
        other, _ = self.make_ca(0, 10, name="other")
        self.store(other, old)
        run = self.bash('ensure_ca "$work"\n')
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("ca.key is not the key of ca.crt", run.stderr)
        with open(old, "rb") as f:
            self.assertEqual(self.stored("ca.crt"), f.read(), "nothing stored")


class CaCopiesTest(unittest.TestCase):
    """Where the CA goes after the catalog step: each copy is made anew from the Secret on every run."""

    def read(self, *path):
        with open(os.path.join(DEPLOY, *path)) as f:
            return f.read()

    def test_the_catalog_step_issues_the_ca_before_signing_and_the_copies_follow_it(self):
        text = self.read("scripts", "catalog", "install-catalog.sh")
        self.assertLess(text.index('\nensure_ca "$work"'), text.index('-CA "$work/ca.crt"'))
        # The pods' template carries the fingerprint of the CA certificate this run has: they roll when
        # it is issued anew, and serve it.
        self.assertLess(text.index('\nensure_ca "$work"'), text.index('\nCA_SHA256="$(openssl x509 -in "$work/ca.crt"'))
        self.assertLess(text.index('\nCA_SHA256='), text.index("\nmapfile -t values < <(catalog_values)\n"))
        self.assertIn('"podAnnotations.colvenna-lake/ca-sha256=$CA_SHA256"',
                      function(INSTALL_CATALOG, "catalog_values"))
        steps = re.findall(r'^  "([a-z-]+)\|', self.read("install.sh"), re.M)
        for later in ("doris", "operator", "polaris", "operator-again", "verify"):
            self.assertLess(steps.index("catalog"), steps.index(later), later)

    def test_the_copies_are_made_from_the_secret_on_every_run(self):
        doris = self.read("scripts", "doris", "install-doris.sh").replace("\\\n", " ")
        self.assertRegex(doris, r"get secret colvenna-catalog-ca-key -o jsonpath='\{\.data\.ca\\\.crt\}'")
        self.assertRegex(doris, r"create secret generic colvenna-catalog-truststore .*--dry-run=client -o yaml"
                                r"\s*\|\s*kubectl apply -f -")
        polaris = self.read("scripts", "open-layer", "install-polaris.sh")
        self.assertIn("get secret colvenna-catalog-ca-key -o jsonpath='{.data.ca\\.crt}'", polaris)
        operator = self.read("scripts", "operator", "install-operator.sh")
        self.assertIn('ca="$ADMIN_DIR/ca.crt"', operator)
        self.assertIn('--set-file catalog.caCert="$ca"', operator)
        # The operator restarts when the CA it is given changes; the managed namespaces get it too.
        self.assertIn("checksum/catalog-ca: {{ .Values.catalog.caCert | sha256sum }}",
                      self.read("helm", "colvenna-operator", "templates", "deployment.yaml"))
        self.assertIn("$.Values.catalog.caCert", self.read("helm", "colvenna-operator", "templates",
                                                           "managed-namespaces.yaml"))
        admin = self.read("scripts", "lib", "catalog-admin.sh")
        self.assertIn("get secret colvenna-catalog-ca-key -o jsonpath='{.data.ca\\.crt}'", admin)


if __name__ == "__main__":
    unittest.main()
