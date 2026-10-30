"""The installer and the product are not tied to the maintainers' AWS account or DNS (#212): no
code, test, workflow or asset names the account, its hosted zone, the zone's domain (or any name under
it), or a fixed AWS profile, in any quoting or split into concatenated pieces on a line. Each
environment's account, zone and entry domain come from its settings (deploy/env.sh).

The guard holds none of those values itself (#248): it looks for those of the local settings and of
deploy/scripts/redaction/known-values.sha256, as the repository-wide check redaction.py there does for
every file. What the guard adds is the split and quoted literals of code and the forced profile. The
documentation is the redaction check's alone: it may say which profile the maintainers' runs used.
"""
import os
import re
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(REPO, "deploy", "scripts", "redaction"))
import redaction  # noqa: E402

SCOPES = ("deploy", "tests", ".github", "assets", "colvenna-bi", "colvenna-cli", "colvenna-console", "colvenna-jobs",
          "colvenna-operator")
# The AWS profile every script once forced: a name with "profile" in it (a variable, an option, an SDK
# call or field), then only quotes, brackets and the like, then work. ${AWS_PROFILE:-} and
# --profile workshop are not it.
PROFILE = re.compile(r"""[\w.]*profile[\w.]*["'\]\[(){}:=,\s-]{1,12}work(?![\w-])""", re.IGNORECASE)


def maintainers():
    """The maintainers' values to look for: the stored hashes and this clone's local settings."""
    return redaction.Values().load_known().load_local()


def tied(line, values):
    """The ties a line holds: each value found (by what it is), and profile."""
    found = values.find(line, joined=True)
    if PROFILE.search(line):
        found.append("profile")
    return found


def tracked():
    out = subprocess.run(["git", "-C", REPO, "ls-files", "-z", "--", *SCOPES],
                         check=True, capture_output=True).stdout.decode()
    this = os.path.relpath(os.path.abspath(__file__), REPO)
    return [p for p in out.split("\0") if p and p != this]


class PortabilityTest(unittest.TestCase):
    def test_no_code_names_the_maintainers_account_zone_or_profile(self):
        values, found = maintainers(), []
        for path in tracked():
            full = os.path.join(REPO, path)
            if not os.path.isfile(full):
                continue
            with open(full, "rb") as f:
                data = f.read()
            if b"\0" in data[:4096]:
                continue  # an image or another binary
            for number, line in enumerate(data.decode("utf-8", errors="replace").splitlines(), 1):
                found += [f"{path}:{number}: {kind}" for kind in tied(line, values)]
        self.assertEqual([], found, "tied to the maintainers' account or DNS:\n" + "\n".join(found))

    def test_the_scan_sees_the_installer_the_docs_inside_code_folders_and_the_assets(self):
        paths = tracked()
        for expected in ("deploy/install.sh", "deploy/env.sh", "deploy/aws/src/app.ts",
                         "deploy/images/doris/README.md", "assets/architecture/render_architecture.go"):
            self.assertIn(expected, paths)
        self.assertFalse([p for p in paths if p.startswith(("docs/", "release/")) or p == "README.md"])

    def test_the_guard_knows_the_values_without_naming_them(self):
        values = maintainers()
        self.assertTrue(values.hashes["account"] and values.hashes["text"], "no stored hashes to look for")
        self.assertEqual([], redaction.check(os.path.abspath(__file__), values, joined=True))


class TiedTest(unittest.TestCase):
    """Each way of writing a tie is caught; the repository's own GitHub path and settings are not. The
    maintainers here are synthetic: account 210987654321, zone Z0123456789ABCDEFGHIJ named
    aws.owner.example, whose owner is also the repository's GitHub owner (owner/colvenna-lake)."""

    def setUp(self):
        self.values = redaction.Values()
        for value in ("210987654321", "Z0123456789ABCDEFGHIJ", "owner.example"):
            self.values.add_value(value, value)

    def tied(self, line):
        return tied(line, self.values)

    def test_the_account_in_any_quoting_or_split(self):
        for line in ("ACCOUNT=210987654321", "'210987654321'", '"arn:aws:iam::210987654321:role/x"',
                     "account = '2109' + '87654321'", '"210987""654321"', "f'{2109}87654321'",
                     "210987654321.dkr.ecr.us-west-2.amazonaws.com", "colvenna-lake-uat-210987654321.auth",
                     "['210987654321', 7]"):
            self.assertIn("210987654321", self.tied(line), line)

    def test_the_zone_id_and_the_zone_domain(self):
        for line in ("--hosted-zone-id Z0123456789ABCDEFGHIJ", "'Z0123456789' + 'ABCDEFGHIJ'",
                     "domain: aws.owner.example", "'aws.' + 'owner' + '.example'",
                     "https://console.colvenna-uat.aws.owner.example/", '"aws.owner" ".example"',
                     "https://owner.example", "curl -s https://OWNER.example/x", "mail ops@owner.example"):
            self.assertTrue({"Z0123456789ABCDEFGHIJ", "owner.example"} & set(self.tied(line)), line)

    def test_a_forced_profile_in_any_form(self):
        for line in ("export AWS_PROFILE=work", "AWS_PROFILE: work", 'AWS_PROFILE="work"', "AWS_PROFILE='work'",
                     "aws s3 ls --profile work", "aws --profile=work sts get-caller-identity",
                     "env:\n  AWS_PROFILE: work", "AWS_DEFAULT_PROFILE=work",
                     'export AWS_PROFILE="${AWS_PROFILE:-work}"', "${AWS_PROFILE-work}", "${AWS_PROFILE:=work}",
                     'os.environ["AWS_PROFILE"] = "work"', "os.environ.setdefault('AWS_PROFILE', 'work')",
                     'subprocess.run(cmd, env={"AWS_PROFILE": "work"})', 'os.Setenv("AWS_PROFILE", "work")',
                     "process.env.AWS_PROFILE = 'work';",
                     "boto3.Session(profile_name='work')", "boto3.setup_default_session(profile_name='work')",
                     'config.LoadDefaultConfig(ctx, config.WithSharedConfigProfile("work"))',
                     "fromIni({ profile: 'work' })", "new AWS.SharedIniFileCredentials({profile: 'work'})",
                     'ProfileCredentialsProvider.create("work")',
                     'ProfileCredentialsProvider.builder().profileName("work").build()',
                     'System.setProperty("aws.profile", "work")'):
            self.assertIn("profile", self.tied(line), line)

    def test_what_is_not_a_tie(self):
        for line in ("module github.com/owner/colvenna-lake/colvenna-cli", "https://github.com/owner/colvenna-lake/issues/212",
                     "gh pr view 226 --repo owner/colvenna-lake", "git clone git@github.com:owner/colvenna-lake.git",
                     'AWS_PROFILE="${AWS_PROFILE:-}"', "unset AWS_PROFILE AWS_SESSION_TOKEN",
                     "use credentials of $COLVENNA_ACCOUNT (AWS_PROFILE=<profile>, or the default chain)",
                     "COLVENNA_ACCOUNT=111122223333", "--profile workshop", "network: workers",
                     "COLVENNA_HOSTED_ZONE_NAME=example.com", "zone=Z0123456789ABCDEFGHIJK"):
            self.assertEqual([], self.tied(line), line)


if __name__ == "__main__":
    unittest.main()
