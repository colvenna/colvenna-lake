"""Offline tests of redaction.py: what it finds of the values it is given (from local settings or as
stored hashes), the value-independent findings, and the check of files as pre-commit runs it. Every
value here is synthetic; the addresses and real-looking ARNs are put together at run time, so that the
check of this file finds nothing."""
import contextlib
import fnmatch
import io
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import redaction  # noqa: E402

# A made-up deployment, as a maintainer's local settings would hold it.
ACCOUNT = "210987654321"
ZONE_ID = "Z0123456789ABCDEFGHIJ"
ZONE = "aws.owner.example"
ENTRY = "colvenna-uat.aws.owner.example"
# A 12-digit account that is neither synthetic nor AWS's, and internal addresses (redaction.py's
# value-independent findings).
OTHER = "98765" + "4321098"
PRIVATE = ".".join(("10", "84", "8", "23"))
# Public addresses (#300), and a version that reads as one.
PUBLIC_IP = ".".join(("100", "50", "7", "9"))
OTHER_PUBLIC = ".".join(("52", "1", "2", "3"))
VERSION = ".".join(("4", "1", "4", "1"))


def values(*pairs):
    v = redaction.Values()
    for value in pairs:
        v.add_value(value, value)
    return v


class FindTest(unittest.TestCase):
    def test_an_account_is_twelve_digits_with_no_digit_next_to_them(self):
        v = values(ACCOUNT)
        for line in (ACCOUNT, f"arn:aws:iam::{ACCOUNT}:role/x", f"{ACCOUNT}.dkr.ecr.us-west-2.amazonaws.com",
                     f"https://colvenna-lake-uat-{ACCOUNT}.auth.us-west-2.amazoncognito.com", f"账号 `{ACCOUNT}`、区域",
                     f"sha {ACCOUNT}abc"):
            self.assertEqual([ACCOUNT], v.find(line), line)
        for line in ("1" + ACCOUNT, ACCOUNT + "0", ACCOUNT[:6] + " " + ACCOUNT[6:], "111122223333"):
            self.assertEqual([], v.find(line), line)

    def test_split_or_quoted_accounts_only_when_joined(self):
        v = values(ACCOUNT)
        for line in (f"'{ACCOUNT[:4]}' + '{ACCOUNT[4:]}'", f"f'{{{ACCOUNT[:4]}}}{ACCOUNT[4:]}'", f'"{ACCOUNT[:6]}""{ACCOUNT[6:]}"'):
            self.assertEqual([], v.find(line), line)
            self.assertEqual([ACCOUNT], v.find(line, joined=True), line)
        # Joined, a number's digits run on into the next one's.
        self.assertEqual([ACCOUNT], v.find(f"['{ACCOUNT}', 7]", joined=True))

    def test_a_domain_is_found_in_every_name_under_it_whatever_the_case(self):
        v = values("owner.example")
        for line in ("https://owner.example", f"https://console.{ENTRY}/x", f"`*.colvenna.{ZONE}`", "ops@owner.example",
                     "Console.Colvenna-Uat.AWS.Owner.Example.", f"{ZONE}，入口"):
            self.assertEqual(["owner.example"], v.find(line), line)
        for line in ("gh --repo owner/colvenna-lake", "https://github.com/owner/colvenna-lake", "owner.examples.com",
                     "myowner.example", "owner-example", "owner.example_x"):
            self.assertEqual([], v.find(line), line)

    def test_names_and_ids_as_words_or_parts_of_one(self):
        v = values("deployer_EC2_role", "i-0123456789abcdef0", ZONE_ID, "exec-role")
        self.assertEqual(["deployer_EC2_role", "i-0123456789abcdef0"],
                         v.find("arn:aws:sts::123456789012:assumed-role/deployer_EC2_role/i-0123456789abcdef0"))
        self.assertEqual([ZONE_ID], v.find(f"--hosted-zone-id {ZONE_ID.lower()}"))
        self.assertEqual(["exec-role"], v.find("role colvenna-exec-role-2"))
        for line in ("deployer_EC2_role_2", "i-0123456789abcdef01", ZONE_ID + "K", "execrole"):
            self.assertEqual([], v.find(line), line)

    def test_a_value_must_be_one_word(self):
        for value in ("arn:aws:iam::1:role/x", "two words", "https://owner.example", ""):
            with self.assertRaises(ValueError, msg=value):
                redaction.Values().add_value(value, "x")


class KnownTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)

    def known(self, text):
        path = os.path.join(self.dir, "known")
        with open(path, "w") as f:
            f.write(text)
        return redaction.Values().load_known(path)

    def test_stored_prefixes_find_the_values(self):
        lines = redaction.hash_lines([ACCOUNT, "owner.example", ""])
        self.assertEqual(["account", "text"], [line.split()[0] for line in lines])
        self.assertEqual([6, 16], [len(line.split()[1]) for line in lines])
        v = self.known("# a comment\n\n%s\n" % "\n".join(line.replace("<what it is>", "stored %d" % i)
                                                         for i, line in enumerate(lines)))
        self.assertEqual(["stored 0", "stored 1"], v.find(f"arn:aws:iam::{ACCOUNT}:root on https://owner.example"))
        self.assertEqual([], v.find("arn:aws:iam::111122223333:root on https://example.com"))

    def test_a_line_not_of_the_format_is_refused(self):
        for text in ("account\n", "domain 0123456789abcdef x\n", "text 0123 too short\n", "text not-hex x\n"):
            with self.assertRaises(ValueError, msg=text):
                self.known(text)

    def test_the_repositorys_hashes_load_and_name_no_value(self):
        v = redaction.Values().load_known()
        self.assertTrue(v.hashes["account"] and v.hashes["text"])
        self.assertEqual([], redaction.check(redaction.KNOWN, v))
        self.assertEqual([], redaction.check(os.path.join(HERE, "redaction.py"), v))


class LocalTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root)
        self.state = os.path.join(self.root, "deploy", ".state")

    def write(self, path, text):
        path = os.path.join(self.state, path)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(text)

    def test_the_settings_of_every_environment_and_the_extra_values(self):
        self.write("uat/account", ACCOUNT + "\n")
        self.write("uat/hosted-zone-id", ZONE_ID)
        self.write("uat/hosted-zone-name", ZONE)
        self.write("uat2/entry-domain", "colvenna-uat2.other.example")
        self.write("uat/region", "us-west-2")
        self.write("uat/install-mode", "0" * 40 + " source")
        self.write(redaction.EXTRA, "# the executor\ndeployer_EC2_role\n\nowner.example\n")
        v = redaction.Values().load_local([self.root, os.path.join(self.root, "missing")])
        found = v.find(f"{ACCOUNT} {ZONE_ID} https://x.colvenna-uat2.other.example deployer_EC2_role")
        self.assertEqual(["the account of local environment uat", "the hosted-zone-id of local environment uat",
                          "the entry-domain of local environment uat2", "line 2 of deploy/.state/redaction-values"],
                         found)
        self.assertEqual(["line 4 of deploy/.state/redaction-values"], v.find("https://owner.example"))
        self.assertEqual([], v.find("us-west-2 source"))

    def test_the_worktrees_of_this_clone(self):
        roots = redaction.worktrees()
        self.assertIn(redaction.REPO, [os.path.realpath(r) for r in roots] + roots)


class PatternTest(unittest.TestCase):
    def test_arns_and_registries_of_real_accounts(self):
        for line in (f"arn:aws:iam::{OTHER}:role/x", f"arn:aws:sts::{OTHER}:assumed-role/r/i-0",
                     f"arn:aws-cn:s3:::b vs arn:aws:secretsmanager:us-west-2:{OTHER}:secret:x",
                     f"{OTHER}.dkr.ecr.us-west-2.amazonaws.com/colvenna-lake-uat/query-engine"):
            self.assertTrue(redaction.patterns(line), line)
        for line in ("arn:aws:iam::123456789012:role/x", "arn:aws:iam::111122223333:root", "arn:aws:iam::aws:policy/x",
                     "arn:aws:iam::000000000000:role/x", "111111111111.dkr.ecr.us-west-2.amazonaws.com",
                     "602401143452.dkr.ecr.us-west-2.amazonaws.com/amazon-k8s-cni:v1", "arn:aws:s3:::bucket/key",
                     "arn:aws:iam::${account}:role/x", "${COLVENNA_ACCOUNT}.dkr.ecr.${COLVENNA_REGION}.amazonaws.com"):
            self.assertEqual([], redaction.patterns(line), line)

    def test_internal_addresses_but_not_networks(self):
        for line in (PRIVATE, f"pod {PRIVATE}:9030", ".".join(("172", "31", "23", "138")) + ".",
                     ".".join(("192", "168", "0", "7")), "node ip-" + "-".join(("10", "0", "1", "23")) + ".ec2.internal",
                     "ip-" + "-".join(("172", "31", "23", "138"))):
            self.assertTrue(redaction.patterns(line), line)
        # Next to the private ranges (public: the address check of PublicTest finds them).
        near = [".".join(parts) for parts in (("172", "15", "0", "1"), ("172", "32", "0", "1"), ("1", "10", "0", "1"))]
        for line in ["10.84.0.0/16", "{10.84.0.0/24}", "203.0.113.7", "0.0.0.0/0", "10.1.2.300", "version 10.1.2.3.4",
                     "ip-203-0-113-7"] + near:
            self.assertEqual([], redaction.patterns(line), line)
        self.assertEqual(near, [a for line in near for a in redaction.public_addresses(line)])

    def test_a_line_may_allow_the_patterns_never_the_values(self):
        line = f"{PRIVATE} and arn:aws:iam::{OTHER}:root  # redaction: allow (synthetic)"
        self.assertEqual([], redaction.patterns(line))
        self.assertEqual([ACCOUNT], values(ACCOUNT).find(f"{ACCOUNT}  # redaction: allow"))


class PublicTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)

    def test_public_addresses_with_or_without_a_prefix_length(self):
        for line in (PUBLIC_IP, f"`{PUBLIC_IP}/32`", f"publicAccessCidrs: [{PUBLIC_IP}/32]", f"ip={PUBLIC_IP}:443",
                     f"deny-address-{PUBLIC_IP}-no-inbound", f"只对{PUBLIC_IP}/32", f"执行机{PUBLIC_IP}的出口",
                     VERSION, f"tag/{VERSION}"):
            self.assertEqual([PUBLIC_IP if PUBLIC_IP in line else VERSION], redaction.public_addresses(line), line)
        self.assertEqual([PUBLIC_IP, OTHER_PUBLIC], redaction.public_addresses(f"{PUBLIC_IP} and {OTHER_PUBLIC}"))

    def test_documentation_private_and_special_addresses_are_not_public(self):
        for line in ("192.0.2.7", "198.51.100.9/32", "203.0.113.7", PRIVATE, "10.84.0.0/16",
                     ".".join(("172", "31", "0", "1")),
                     # 0.0.0.0 is test data of the address check (bandit B104): nothing binds to it.
                     "100.64.0.1", "127.0.0.1", "169.254.169.254", "0.0.0.0/0", "0.0.0.0", "255.255.255.255",  # nosec B104
                     "224.0.0.1", "10.1.2.300", f"v{VERSION}", f"{VERSION}.2", f"fe{VERSION}", f"{VERSION}x"):
            self.assertEqual([], redaction.public_addresses(line), line)

    def tsv(self, text):
        path = os.path.join(self.dir, "public.tsv")
        with open(path, "w") as f:
            f.write(text)
        return path

    def test_the_exceptions_are_a_file_glob_one_public_address_and_a_reason(self):
        allowed = redaction.load_public(self.tsv(f"# a comment\n\ndocs/*.md\t{VERSION}\tApache Doris's version\n"))
        self.assertEqual([("docs/*.md", VERSION, "Apache Doris's version")], allowed)
        for line in (f"docs/a.md\t{VERSION}\n", f"docs/a.md\t{VERSION}\t\n", f"docs/a.md\t{PRIVATE}\tx\n",
                     f"docs/a.md\t{VERSION}/32\tx\n", f"docs/a.md\t{VERSION} {PUBLIC_IP}\tx\n",
                     f"docs/a.md\t{VERSION}\tx\ty\n"):
            with self.assertRaises(ValueError, msg=line):
                redaction.load_public(self.tsv(line))

    def test_only_the_file_and_address_registered_pass_and_the_marker_spares_none(self):
        path = os.path.join(self.dir, "a.md")
        with open(path, "w") as f:
            f.write(f"Doris {VERSION}\nendpoint {PUBLIC_IP}/32  # redaction: allow\n")
        name = redaction.repo_path(path)
        self.assertEqual([f"{path}:1", f"{path}:2"], [f.split(": ", 1)[0] for f in redaction.check(path, values())])
        found = redaction.check(path, values(), allowed=[(name, VERSION, "a version")])
        self.assertEqual([f"{path}:2"], [f.split(": ", 1)[0] for f in found])
        self.assertIn("a public IPv4 address", found[0])
        self.assertNotIn(PUBLIC_IP, "".join(found))
        self.assertEqual(2, len(redaction.check(path, values(), allowed=[("other/*.md", VERSION, "x")])))

    def test_the_repositorys_exceptions_load_and_each_one_is_used(self):
        allowed = redaction.load_public()
        self.assertTrue(allowed)
        try:
            tracked = subprocess.run(["git", "-C", redaction.REPO, "ls-files"], check=True, capture_output=True,
                                     text=True).stdout.splitlines()
        except (OSError, subprocess.CalledProcessError):
            self.skipTest("not a git checkout")
        for glob, address, reason in allowed:
            files = [p for p in tracked if fnmatch.fnmatchcase(p, glob)]
            self.assertTrue(files, f"{glob}: no tracked file ({reason})")
            self.assertTrue(any(address in redaction.public_addresses(line) for p in files
                                for line in redaction.read(os.path.join(redaction.REPO, p)).splitlines()),
                            f"{glob} {address}: unused ({reason})")
        self.assertEqual([], redaction.check(redaction.PUBLIC, values(), allowed=allowed))


class CheckTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)
        os.makedirs(os.path.join(self.dir, "deploy", ".state", "uat"))
        with open(os.path.join(self.dir, "deploy", ".state", "uat", "hosted-zone-name"), "w") as f:
            f.write(ZONE)
        self.known = os.path.join(self.dir, "known")
        with open(self.known, "w") as f:
            f.write("\n".join(redaction.hash_lines([ACCOUNT])).replace("<what it is>", "the account") + "\n")

    def file(self, name, data):
        path = os.path.join(self.dir, name)
        with open(path, "wb") as f:
            f.write(data)
        return path

    def run_main(self, *paths, public=redaction.PUBLIC):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = redaction.main(["redaction.py", *paths], roots=[self.dir], known=self.known, public=public)
        return code, out.getvalue()

    def test_the_registered_public_addresses_pass(self):
        allowed = os.path.join(self.dir, "public.tsv")
        planted = self.file("history.md", f"Doris {VERSION}\n".encode())
        with open(allowed, "w") as f:
            f.write(f"{redaction.repo_path(planted)}\t{VERSION}\tApache Doris's version\n")
        self.assertEqual(1, self.run_main(planted)[0])
        self.assertEqual((0, ""), self.run_main(planted, public=allowed))
        with open(allowed, "w") as f:
            f.write(f"{redaction.repo_path(planted)}\t{PRIVATE}\tnot public\n")
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            self.assertEqual(2, self.run_main(planted, public=allowed)[0])
        self.assertIn("public.tsv:1", err.getvalue())

    def test_clean_files_pass(self):
        clean = self.file("clean.md", "账号 `<ACCOUNT_ID>`，入口 `colvenna-uat.<COLVENNA_HOSTED_ZONE_NAME>`\n".encode())
        binary = self.file("image.png", b"\x89PNG\0\0" + ACCOUNT.encode())
        self.assertEqual((0, ""), self.run_main(clean, binary, os.path.join(self.dir, "deleted")))

    def test_a_planted_value_fails_without_showing_it(self):
        clean = self.file("clean.md", b"nothing here\n")
        planted = self.file("planted.md", f"one\n账号 `{ACCOUNT}`\nhttps://console.colvenna-uat.{ZONE}/\n{PRIVATE}\n"
                                          f"EKS 公网端点只对 `{PUBLIC_IP}/32`\n".encode())
        code, out = self.run_main(clean, planted)
        self.assertEqual(1, code)
        self.assertIn(f"{planted}:2: the account\n", out)
        self.assertIn(f"{planted}:3: the hosted-zone-name of local environment uat\n", out)
        self.assertIn(f"{planted}:4: an internal IPv4 address", out)
        self.assertIn(f"{planted}:5: a public IPv4 address", out)
        self.assertNotIn("clean.md", out)
        for value in (ACCOUNT, ZONE, PRIVATE, PUBLIC_IP):
            self.assertNotIn(value, out)

    def test_a_setting_it_cannot_look_for_is_named_not_shown(self):
        with open(os.path.join(self.dir, "deploy", ".state", "uat", "entry-domain"), "w") as f:
            f.write("https://" + ENTRY)
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            self.assertEqual((2, ""), self.run_main(self.file("clean.md", b"x\n")))
        self.assertIn("the entry-domain of local environment uat", err.getvalue())
        self.assertNotIn(ENTRY, err.getvalue())

    def test_the_hash_of_a_value_given_on_stdin(self):
        out, stdin = io.StringIO(), sys.stdin
        try:
            sys.stdin = io.StringIO(ACCOUNT + "\n")
            with contextlib.redirect_stdout(out):
                self.assertEqual(0, redaction.main(["redaction.py", "--hash"]))
        finally:
            sys.stdin = stdin
        self.assertEqual(redaction.hash_lines([ACCOUNT])[0] + "\n", out.getvalue())
        self.assertNotIn(ACCOUNT, out.getvalue())


if __name__ == "__main__":
    unittest.main()
