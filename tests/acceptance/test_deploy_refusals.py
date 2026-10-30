"""Offline tests of what the installer refuses before anything is made, recorded or deleted (#227):
deploy/install.sh lists its steps without the settings, and stops in a region that is not
CDK-bootstrapped; deploy/scripts/lib/preflight.sh tells a stack that does not exist from one it
cannot read; deploy/uninstall.sh checks the hosted zone and entry domain the local state does not
record yet before it deletes anything; deploy/scripts/doris/copy-images.sh checks DORIS_IMAGE_SOURCE's
form first, says when both images are there already under their tags, without logging in anywhere, and
gives an image there under other tags only its tag, logging in to the environment's registry alone.
Resumed once its stacks are gone, deploy/uninstall.sh deletes and looks for what CloudFormation named
after them cut short (#282).

The scripts run from a copy of deploy/ (a git repository of its own, as install.sh requires a
committed tree), against a stub AWS CLI and crane that answer from rules and log every call; a call
no rule answers fails, as a denied one would."""
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
# What the scripts under test read: themselves, the libraries (deploy/scripts/lib, whole), the release
# manifest's reader, the foundation's spec, the third-party images' list and the catalog release's pin.
FILES = ("deploy/env.sh", "deploy/install.sh", "deploy/uninstall.sh", "deploy/scripts/doris/copy-images.sh",
         "deploy/scripts/release/manifest.py", "deploy/aws/src/foundation-spec.json", "deploy/images/third-party.tsv",
         "deploy/images/colvenna-catalog.json",
         *(f"deploy/scripts/lib/{name}" for name in sorted(os.listdir(os.path.join(REPO, "deploy", "scripts", "lib")))))
ACCOUNT = "123456789012"
REGION = "us-west-2"
ZONE_ID = "Z0EXAMPLE0ZONE"
ZONE = "example.com"
SETTINGS = {"COLVENNA_REGION": REGION, "COLVENNA_ACCOUNT": ACCOUNT, "COLVENNA_HOSTED_ZONE_ID": ZONE_ID,
            "COLVENNA_HOSTED_ZONE_NAME": ZONE}
# Every file of the settings in deploy/.state/<env>/ (deploy/env.sh's COLVENNA_SETTINGS).
SETTING_FILES = ("region", "account", "hosted-zone-id", "hosted-zone-name", "entry-domain")
with open(os.path.join(REPO, "deploy", "scripts", "lib", "common.sh")) as f:
    COMMON = f.read()
FE = re.search(r"^DORIS_FE_DIGEST=(\S+)$", COMMON, re.M).group(1)
BE = re.search(r"^DORIS_BE_DIGEST=(\S+)$", COMMON, re.M).group(1)
# Their tags in the environment's repository, each carrying its digest (lib/common.sh's doris_tag).
DORIS_TAG = re.search(r"^DORIS_TAG=(\S+)$", COMMON, re.M).group(1)
FE_TAG, BE_TAG = f"{DORIS_TAG}-fe-{FE[7:19]}", f"{DORIS_TAG}-be-{BE[7:19]}"

STUB = r'''#!/usr/bin/env python3
import json, os, sys
name, args = os.path.basename(sys.argv[0]), sys.argv[1:]
if "--password-stdin" in args:
    sys.stdin.read()
with open(os.environ["STUB_LOG"], "a") as f:
    f.write(json.dumps([name, *args]) + "\n")
for rule in json.load(open(os.environ["STUB_RULES"])):
    if rule["cli"] == name and all(token in args for token in rule["when"]):
        sys.stdout.write(rule["out"])
        sys.stderr.write(rule["err"])
        sys.exit(rule["code"])
sys.stderr.write("\nAn error occurred (AccessDenied) when calling the stub: no rule for " + " ".join([name, *args]) + "\n")
sys.exit(254)
'''


def rule(*when, out="", err="", code=0, cli="aws"):
    """An answer to every call of <cli> whose arguments include all of <when>; the first that matches."""
    return {"cli": cli, "when": list(when), "out": out if isinstance(out, str) else json.dumps(out),
            "err": err, "code": code}


def error(code, operation, message):
    return f"\nAn error occurred ({code}) when calling the {operation} operation: {message}\n"


IDENTITY = rule("sts", "get-caller-identity", out=ACCOUNT + "\n")


def zone(zone_id=ZONE_ID, name=ZONE, private=False):
    return rule("route53", "get-hosted-zone", zone_id, out={"HostedZone": {
        "Id": f"/hostedzone/{zone_id}", "Name": name + ".", "Config": {"PrivateZone": private}}})


def no_zone(zone_id):
    return rule("route53", "get-hosted-zone", zone_id, code=254,
                err=error("NoSuchHostedZone", "GetHostedZone", f"No hosted zone found with ID: {zone_id}"))


def no_stack(stack):
    return rule("cloudformation", "describe-stacks", stack, code=254,
                err=error("ValidationError", "DescribeStacks", f"Stack with id {stack} does not exist"))


def throttled(stack):
    return rule("cloudformation", "describe-stacks", stack, code=254,
                err=error("Throttling", "DescribeStacks (reached max retries: 2)", "Rate exceeded"))


def outputs(stack, **values):
    """describe-stacks of <stack> as lib/preflight.sh's stack_outputs asks it: the outputs, as JSON."""
    return rule("cloudformation", "describe-stacks", stack, "Stacks[0].Outputs",
                out=[{"OutputKey": k, "OutputValue": v} for k, v in values.items()])


def bootstrap(version):
    return rule("ssm", "get-parameter", "/cdk-bootstrap/hnb659fds/version",
                out={"Parameter": {"Name": "/cdk-bootstrap/hnb659fds/version", "Type": "String", "Value": version}})


NOT_BOOTSTRAPPED = rule("ssm", "get-parameter", code=254, err=error("ParameterNotFound", "GetParameter", ""))


def after(call, flag):
    """The argument of a logged call that follows <flag>."""
    return call[call.index(flag) + 1]


def kept(query, name):
    """Whether <query>, of the form <list>[?<test> || <test>...].<field>, keeps <name>: its tests are
    starts_with(<field>, '<prefix>') and contains(<field>, '<part>') alone, the ones uninstall.sh writes."""
    tests = [re.fullmatch(r"\s*(starts_with|contains)\(\w+, '([^']*)'\)\s*", t)
             for t in re.fullmatch(r"\w+\[\?(.*)\]\.\w+", query, re.S).group(1).split("||")]
    if not all(tests):
        raise ValueError(f"a test other than starts_with or contains in {query}")
    return any(name.startswith(v) if f == "starts_with" else v in name for f, v in (t.groups() for t in tests))


class Deploy(unittest.TestCase):
    """A copy of deploy/, committed, with the stub CLIs first on PATH and no credentials at all, so a
    call the stub missed could reach nothing."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = os.path.join(self.tmp.name, "repo")
        for path in FILES:
            os.makedirs(os.path.dirname(os.path.join(self.repo, path)), exist_ok=True)
            shutil.copy2(os.path.join(REPO, path), os.path.join(self.repo, path))
        with open(os.path.join(self.repo, ".gitignore"), "w") as f:
            f.write("deploy/.state/\n")
        git = ["git", "-C", self.repo, "-c", "user.name=t", "-c", "user.email=t@example.com",
               "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null"]
        subprocess.run([*git, "init", "-q"], check=True)
        subprocess.run([*git, "add", "-A"], check=True)
        subprocess.run([*git, "commit", "-qm", "copy"], check=True)
        bin_dir = os.path.join(self.tmp.name, "bin")
        os.makedirs(bin_dir)
        for cli in ("aws", "crane"):
            with open(os.path.join(bin_dir, cli), "w") as f:
                f.write(STUB)
            os.chmod(os.path.join(bin_dir, cli), 0o700)
        self.log = os.path.join(self.tmp.name, "calls.jsonl")
        self.rules = os.path.join(self.tmp.name, "rules.json")
        self.answer()
        self.env = {k: v for k, v in os.environ.items()
                    if not k.startswith(("AWS_", "COLVENNA_", "DORIS_", "INSTALL_", "UNINSTALL_"))}
        self.env.update(PATH=bin_dir + os.pathsep + os.environ["PATH"], STUB_LOG=self.log, STUB_RULES=self.rules,
                        AWS_CONFIG_FILE=os.devnull, AWS_SHARED_CREDENTIALS_FILE=os.devnull,
                        AWS_EC2_METADATA_DISABLED="true")

    def tearDown(self):
        self.tmp.cleanup()

    def answer(self, *rules):
        with open(self.rules, "w") as f:
            json.dump(list(rules), f)

    def state(self, env, **files):
        """deploy/.state/<env>/: write <file>=<content> (underscores for hyphens); returns the directory."""
        directory = os.path.join(self.repo, "deploy", ".state", env)
        os.makedirs(directory, exist_ok=True)
        for name, content in files.items():
            with open(os.path.join(directory, name.replace("_", "-")), "w") as f:
                f.write(content + "\n")
        return directory

    def recorded(self, env):
        directory = os.path.join(self.repo, "deploy", ".state", env)
        return {f for f in SETTING_FILES if os.path.exists(os.path.join(directory, f))}

    def run_script(self, script, *args, env=None):
        result = subprocess.run(["bash", os.path.join(self.repo, script), *args], env={**self.env, **(env or {})},
                                stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=120)
        return result.returncode, result.stdout + result.stderr

    def source(self, body, env=None):
        """<body> run with deploy/env.sh and lib/preflight.sh sourced, as install.sh has them."""
        script = (f'set -euo pipefail; source "{self.repo}/deploy/env.sh"; '
                  f'source "{self.repo}/deploy/scripts/lib/preflight.sh"; {body}')
        result = subprocess.run(["bash", "-c", script], env={**self.env, **(env or {})},
                                stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=60)
        return result.returncode, result.stdout + result.stderr

    def calls(self):
        if not os.path.exists(self.log):
            return []
        with open(self.log) as f:
            return [json.loads(line) for line in f]

    def called(self, *tokens):
        return [c for c in self.calls() if all(t in c for t in tokens)]

    def changes(self):
        """The calls that would change something: no stub answers them, the tests check none is made."""
        verbs = ("delete-", "put-", "update-", "change-", "create-", "copy", "auth")
        return [c for c in self.calls() if len(c) > 2 and c[2].startswith(verbs) or c[0] == "crane"]


class ListTest(Deploy):
    def test_the_steps_are_listed_with_the_name_alone(self):
        status, out = self.run_script("deploy/install.sh", "--list", env={"COLVENNA_ENV": "fresh"})
        self.assertEqual(status, 0, out)
        self.assertRegex(out, r"(?m)^foundation +ColvennaLakeFreshFoundation: VPC, EKS")
        self.assertRegex(out, r"(?m)^entry-stack +ColvennaLakeFreshEntry: ")
        self.assertRegex(out, r"(?m)^sources +.*\(--fixtures\)$")
        self.assertEqual(self.calls(), [])
        self.assertEqual(self.recorded("fresh"), set())

    def test_settings_recorded_or_given_otherwise_do_not_matter_to_it(self):
        self.state("old", region="us-east-2")
        status, out = self.run_script("deploy/install.sh", "--list", env={"COLVENNA_ENV": "old", "COLVENNA_REGION": REGION})
        self.assertEqual(status, 0, out)
        self.assertIn("ColvennaLakeOldFoundation", out)

    def test_the_name_is_still_checked_and_a_run_still_needs_the_settings(self):
        status, out = self.run_script("deploy/install.sh", "--list", env={"COLVENNA_ENV": "Fresh"})
        self.assertEqual(status, 1)
        self.assertIn("COLVENNA_ENV=<env>", out)
        status, out = self.run_script("deploy/install.sh", "--only", "verify", env={"COLVENNA_ENV": "fresh"})
        self.assertEqual(status, 1)
        self.assertIn("COLVENNA_REGION=<region>", out)
        self.assertEqual(self.calls(), [])


class EntryDomainTest(Deploy):
    """An environment installed before its settings were recorded: its entry domain is compared with
    the one its entry stack serves (assert_entry_domain false)."""

    ENV = {"COLVENNA_ENV": "old", **SETTINGS}

    def test_an_entry_stack_it_cannot_read_stops_it(self):
        self.answer(throttled("ColvennaLakeOldEntry"))
        status, out = self.source("assert_entry_domain false", self.ENV)
        self.assertNotEqual(status, 0)
        self.assertIn("Cannot read ColvennaLakeOldEntry in us-west-2", out)
        self.assertIn("Rate exceeded", out)

    def test_no_entry_stack_leaves_nothing_to_compare(self):
        self.answer(no_stack("ColvennaLakeOldEntry"))
        self.assertEqual(self.source("assert_entry_domain false", self.ENV)[0], 0)

    def test_the_entry_stack_s_domain_must_be_the_one_given(self):
        self.answer(outputs("ColvennaLakeOldEntry", consoleUrl="https://console.colvenna-old.example.com"))
        self.assertEqual(self.source("assert_entry_domain false", self.ENV)[0], 0)
        status, out = self.source("assert_entry_domain false", {**self.ENV, "COLVENNA_ENTRY_DOMAIN": "portal.example.com"})
        self.assertNotEqual(status, 0)
        self.assertIn("serves console.colvenna-old.example.com, not console.portal.example.com", out)
        self.assertIn("give COLVENNA_ENTRY_DOMAIN=colvenna-old.example.com", out)

    def test_a_recorded_entry_domain_is_not_compared_again(self):
        self.state("old", entry_domain="colvenna-old.example.com")
        self.assertEqual(self.source("assert_entry_domain false", self.ENV)[0], 0)
        self.assertEqual(self.called("describe-stacks"), [])

    def test_the_foundation_too_is_new_only_when_it_does_not_exist(self):
        self.answer(throttled("ColvennaLakeOldFoundation"))
        status, out = self.source("foundation_outputs", self.ENV)
        self.assertNotEqual(status, 0)
        self.assertIn("Cannot read ColvennaLakeOldFoundation in us-west-2", out)
        self.answer(no_stack("ColvennaLakeOldFoundation"))
        self.assertEqual(self.source("foundation_outputs", self.ENV), (0, "\n"))


class BootstrapTest(Deploy):
    ENV = {"COLVENNA_ENV": "fresh", **SETTINGS}
    FIX = "(cd deploy/aws && npm ci && npx cdk bootstrap aws://123456789012/us-west-2 --app '')"

    def test_a_bootstrapped_region_passes(self):
        self.answer(bootstrap("32"))
        self.assertEqual(self.source("assert_cdk_bootstrap", self.ENV), (0, ""))

    def test_a_region_not_bootstrapped_is_refused_with_the_fix(self):
        self.answer(NOT_BOOTSTRAPPED)
        status, out = self.source("assert_cdk_bootstrap", self.ENV)
        self.assertNotEqual(status, 0)
        self.assertIn("Account 123456789012 has not CDK-bootstrapped us-west-2", out)
        self.assertIn(self.FIX, out)

    def test_an_old_bootstrap_is_refused_and_a_read_error_said(self):
        self.answer(bootstrap("5"))
        status, out = self.source("assert_cdk_bootstrap", self.ENV)
        self.assertNotEqual(status, 0)
        self.assertIn("version 5; the stacks need 6 or newer", out)
        self.answer(rule("ssm", "get-parameter", code=254, err=error("AccessDeniedException", "GetParameter", "denied")))
        status, out = self.source("assert_cdk_bootstrap", self.ENV)
        self.assertNotEqual(status, 0)
        self.assertIn("Cannot read the CDK bootstrap version of us-west-2", out)

    def new_environment(self, *more):
        self.answer(IDENTITY, zone(), no_stack("ColvennaLakeFreshFoundation"), rule("iam", "list-roles", out="\n"), *more)

    def test_install_stops_in_a_region_not_bootstrapped_before_it_records_anything(self):
        self.new_environment(NOT_BOOTSTRAPPED)
        status, out = self.run_script("deploy/install.sh", env=self.ENV)
        self.assertEqual(status, 1, out)
        self.assertIn("has not CDK-bootstrapped us-west-2", out)
        self.assertEqual(self.recorded("fresh"), set())
        self.assertFalse(os.path.exists(os.path.join(self.repo, "deploy", ".state", "fresh", "install-mode")))
        # The other checks of the region come after it.
        self.assertEqual(self.called("eks"), [])
        self.assertEqual(self.changes(), [])

    def test_install_goes_on_to_the_next_check_in_a_bootstrapped_region(self):
        self.new_environment(bootstrap("32"))
        status, out = self.run_script("deploy/install.sh", env=self.ENV)
        self.assertNotEqual(status, 0)
        self.assertTrue(self.called("eks", "describe-cluster-versions"), out)
        self.assertEqual(self.recorded("fresh"), set())

    def test_a_run_that_deploys_no_stack_does_not_ask(self):
        self.new_environment()
        status, out = self.run_script("deploy/install.sh", "--only", "verify", env=self.ENV)
        self.assertNotEqual(status, 0)
        self.assertEqual(self.called("ssm"), [])
        self.assertTrue(self.called("eks", "describe-cluster-versions"), out)


class UninstallTest(Deploy):
    """Environment old, installed before the settings were recorded: only its region is."""

    LIVE = rule("cloudformation", "list-stacks", out="ColvennaLakeOldEntry\tColvennaLakeOldFoundation\n")
    SERVES = outputs("ColvennaLakeOldEntry", consoleUrl="https://console.colvenna-old.example.com")

    def setUp(self):
        super().setUp()
        self.state("old", region=REGION)

    def uninstall(self, **settings):
        return self.run_script("deploy/uninstall.sh", "--yes", "old", env={"COLVENNA_ENV": "old", **SETTINGS, **settings})

    def assert_nothing_done(self, status, out):
        self.assertEqual(status, 1, out)
        self.assertEqual(self.recorded("old"), {"region"})
        self.assertFalse(os.path.exists(os.path.join(self.repo, "deploy", ".state", "old", "uninstall.noted")))
        self.assertEqual(self.changes(), [])

    def test_a_hosted_zone_not_of_the_account_is_refused_before_anything_is_deleted(self):
        self.answer(IDENTITY, self.LIVE, no_zone("Z0WRONG0ZONE"), self.SERVES)
        status, out = self.uninstall(COLVENNA_HOSTED_ZONE_ID="Z0WRONG0ZONE")
        self.assert_nothing_done(status, out)
        self.assertIn("Cannot read hosted zone Z0WRONG0ZONE in account 123456789012", out)

    def test_a_hosted_zone_of_another_name_is_refused(self):
        self.answer(IDENTITY, self.LIVE, zone(), self.SERVES)
        status, out = self.uninstall(COLVENNA_HOSTED_ZONE_NAME="example.org")
        self.assert_nothing_done(status, out)
        self.assertIn(f"Hosted zone {ZONE_ID} is example.com, not COLVENNA_HOSTED_ZONE_NAME example.org", out)

    def test_an_entry_domain_other_than_the_entry_stack_s_is_refused(self):
        self.answer(IDENTITY, self.LIVE, zone(), self.SERVES)
        status, out = self.uninstall(COLVENNA_ENTRY_DOMAIN="portal.example.com")
        self.assert_nothing_done(status, out)
        self.assertIn("serves console.colvenna-old.example.com, not console.portal.example.com", out)

    def test_an_entry_stack_it_cannot_read_stops_it(self):
        self.answer(IDENTITY, self.LIVE, zone(), throttled("ColvennaLakeOldEntry"))
        status, out = self.uninstall()
        self.assert_nothing_done(status, out)
        self.assertIn("Cannot read ColvennaLakeOldEntry", out)

    def test_the_right_settings_are_recorded_and_the_uninstall_goes_on(self):
        # It goes on to note what the stacks hold; the stub answers no more, so it stops there.
        self.answer(IDENTITY, self.LIVE, zone(), self.SERVES)
        status, out = self.uninstall()
        self.assertNotEqual(status, 0)
        self.assertEqual(self.recorded("old"), set(SETTING_FILES), out)
        with open(os.path.join(self.repo, "deploy", ".state", "old", "entry-domain")) as f:
            self.assertEqual(f.read(), "colvenna-old.example.com\n")
        self.assertTrue(os.path.exists(os.path.join(self.repo, "deploy", ".state", "old", "uninstall.noted")))
        self.assertEqual(self.changes(), [])

    def test_recorded_settings_are_not_checked_again(self):
        self.state("old", account=ACCOUNT, hosted_zone_id=ZONE_ID, hosted_zone_name=ZONE,
                   entry_domain="colvenna-old.example.com")
        self.answer(IDENTITY, self.LIVE)
        status, out = self.run_script("deploy/uninstall.sh", "--yes", "old", env={"COLVENNA_ENV": "old"})
        self.assertNotEqual(status, 0)
        self.assertEqual(self.called("route53", "get-hosted-zone"), [])
        self.assertEqual(self.called("describe-stacks", "Stacks[0].Outputs"), [])
        self.assertTrue(os.path.exists(os.path.join(self.repo, "deploy", ".state", "old", "uninstall.noted")), out)

class CutNamesUninstallTest(Deploy):
    """An uninstall resumed once its stacks are gone. CloudFormation cut uate2's stack names short in
    the names it generated (ColvennaLakeUate2Foundati-..., colvennalakeuate2foundatio-...): the log
    groups of its Lambda functions, which no stack holds, are deleted by those names, and the check of
    the account looks for every kind by them; uat's and uate2b's, whose stack names start the same, are
    neither, nor is a cut of 22 characters, which two environments' stack names can share."""

    OURS = ["/aws/lambda/ColvennaLakeUate2Foundati-AWSCDKCfnUtilsProviderCu-q5EXyVy90rMb",
            "/aws/lambda/ColvennaLakeUate2Foundation-a-OnEventHandler42BEBAE0-AAAA",
            "/aws/lambda/ColvennaLakeUate2TestMysq-LogRetentionaae0aa3c5b4d-BBBB",
            "/aws/lambda/ColvennaLakeUate2TestPost-LogRetentionaae0aa3c5b4d-CCCC",
            "/aws/lambda/ColvennaLakeUate2Entry-Handler-DDDD"]
    THEIRS = ["/aws/lambda/ColvennaLakeUatFoundation-AWSCDKCfnUtilsProviderCu-EEEE",
              "/aws/lambda/ColvennaLakeUatTestPostgr-LogRetentionaae0aa3c5b4d-FFFF",
              "/aws/lambda/ColvennaLakeUate2bFoundati-Handler-GGGG"]

    def uninstall(self, env, lambda_logs=()):
        """<env>'s uninstall, its settings recorded and its stacks gone; the sweep finds <lambda_logs>."""
        directory = self.state(env, region=REGION, account=ACCOUNT, hosted_zone_id=ZONE_ID,
                               hosted_zone_name=ZONE, entry_domain=f"colvenna-{env}.example.com")
        with open(os.path.join(directory, "uninstall.noted"), "w") as f:
            f.write("vpc=vpc-0example\n")
        self.answer(IDENTITY, rule("cloudformation", "describe-stacks", code=254,
                                   err=error("ValidationError", "DescribeStacks", "Stack does not exist")),
                    rule("logs", "describe-log-groups", "--log-group-name-prefix", out="\t".join(lambda_logs) + "\n"),
                    *(rule(*call) for call in (
                        ("cloudformation", "list-stacks"), ("rds", "describe-db-instances"),
                        ("rds", "describe-db-snapshots"), ("logs", "delete-log-group"), ("logs", "describe-log-groups"),
                        ("secretsmanager", "list-secrets"), ("iam", "list-roles"), ("ecr", "describe-repositories"),
                        ("s3api", "list-buckets"), ("cognito-idp", "list-user-pools"),
                        ("resourcegroupstaggingapi", "get-resources"))),
                    rule("route53", "list-resource-record-sets", out={"ResourceRecordSets": []}))
        status, out = self.run_script("deploy/uninstall.sh", "--yes", env, env={"COLVENNA_ENV": env})
        self.assertEqual(status, 0, out)
        self.assertIn(f"nothing of {env} is left", out)

    def check_query(self, *tokens):
        """The query of the check's one call with all of <tokens>: not the sweep's, which lists by a
        prefix, nor the lookup of the restores."""
        queries = [after(c, "--query") for c in self.called(*tokens) if "--log-group-name-prefix" not in c]
        queries = [q for q in queries if "-restore-'" not in q]
        self.assertEqual(len(queries), 1, tokens)
        return queries[0]

    def assert_kept(self, query, ours, theirs):
        self.assertEqual([n for n in ours + theirs if kept(query, n)], ours, query)

    def test_the_lambda_log_groups_are_deleted_by_the_cut_names(self):
        self.uninstall("uate2", self.OURS)
        sweep = self.called("describe-log-groups", "--log-group-name-prefix")
        self.assertEqual([after(c, "--log-group-name-prefix") for c in sweep], ["/aws/lambda/ColvennaLakeUate2"])
        self.assert_kept(after(sweep[0], "--query"), self.OURS, self.THEIRS)
        self.assertEqual([after(c, "--log-group-name") for c in self.called("delete-log-group")], self.OURS)

    def test_the_check_looks_for_every_kind_by_the_cut_names(self):
        self.uninstall("uate2")
        self.assert_kept(self.check_query("describe-log-groups"),
                         self.OURS + ["/aws/rds/instance/colvennalakeuate2foundatio-catalogdatabase1-aaaa/postgresql",
                                      "/colvenna-lake-uate2/console-audit"],
                         self.THEIRS + ["/aws/rds/instance/colvennalakeuatfoundation-catalogdatabase1-cccc/postgresql"])
        for tokens, mine, others in (
                (("iam", "list-roles"), ["ColvennaLakeUate2Foundati-ClusterCreationRole-AAAA", "colvenna-lake-uate2-catalog"],
                 ["ColvennaLakeUatFoundation-ClusterCreationRole-BBBB", "ColvennaLakeUate2bFoundati-Role-CCCC"]),
                (("rds", "describe-db-instances"), ["colvennalakeuate2foundatio-catalogdatabase1-aaaa",
                                                    "colvennalakeuate2testpostgr-postgressource1-bbbb"],
                 ["colvennalakeuatfoundation-catalogdatabase1-cccc", "colvennalakeuate2bfoundati-catalogdb-dddd"]),
                (("rds", "describe-db-snapshots"), ["colvennalakeuate2foundatio-catalogdatabase1-aaaa"],
                 ["colvennalakeuatfoundation-catalogdatabase1-cccc"]),
                (("s3api", "list-buckets"), ["colvennalakeuate2foundatio-warehouse1-eeee"],
                 ["colvennalakeuatfoundation-warehouse1-ffff"])):
            self.assert_kept(self.check_query(*tokens), mine, others)

    def test_a_cut_of_22_characters_is_not_taken_for_the_environment_s(self):
        # Lowercased, abcdef's ColvennaLakeAbcdefOpenLayer and abcdefop's ColvennaLakeAbcdefopEntry
        # share their first 22 characters, colvennalakeabcdefopen: the most two environments' can.
        self.uninstall("abcdef")
        self.assert_kept(self.check_query("s3api", "list-buckets"),
                         ["colvennalakeabcdefopenl-bucket-aaaa"], ["colvennalakeabcdefopen-bucket-bbbb"])


class CopyImagesTest(Deploy):
    ENV = {"COLVENNA_ENV": "old", **SETTINGS}
    # The query engine's repository: a modified build based on Apache Doris goes by a name of its
    # own (release/trademarks.md), and so does the foundation's output naming it.
    OWN = f"{ACCOUNT}.dkr.ecr.{REGION}.amazonaws.com/colvenna-lake-old/query-engine"
    SOURCE = "111122223333.dkr.ecr.us-east-2.amazonaws.com/colvenna-lake-release/query-engine"
    OUTPUT = "Stacks[0].Outputs[?OutputKey=='QueryEngineImageRepositoryUri'].OutputValue | [0]"

    def answers(self, held, *more):
        self.answer(IDENTITY, rule("cloudformation", "describe-stacks", "ColvennaLakeOldFoundation", self.OUTPUT,
                                   out=self.OWN + "\n"),
                    rule("ecr", "batch-get-image", "colvenna-lake-old/query-engine", out=f"{held}\n"), *more)

    def test_a_source_of_the_wrong_form_is_refused_even_with_both_images_there(self):
        self.answers(2)
        for source in ("docker.io/apache/doris", "111122223333.dkr.ecr.us-gov-west-1.amazonaws.com/doris"):
            status, out = self.run_script("deploy/scripts/doris/copy-images.sh", env={**self.ENV, "DORIS_IMAGE_SOURCE": source})
            self.assertEqual(status, 1, out)
            self.assertIn(f"DORIS_IMAGE_SOURCE must be an ECR repository in a standard region,"
                          f" <account>.dkr.ecr.<region>.amazonaws.com/<repository>: {source}", out)
        self.assertEqual(self.called("batch-get-image"), [])
        self.assertEqual(self.called("get-login-password"), [])
        self.assertEqual(self.changes(), [])

    def tagged(self, *components):
        """describe-images of the components' tags, each holding its digest."""
        return [rule("ecr", "describe-images", f"imageTag={tag}", out=digest + "\n")
                for component, tag, digest in (("fe", FE_TAG, FE), ("be", BE_TAG, BE)) if component in components]

    def test_both_images_there_already_are_said_without_a_login(self):
        self.answers(2, *self.tagged("fe", "be"))
        for env in ({"DORIS_IMAGE_SOURCE": self.SOURCE}, {}):
            status, out = self.run_script("deploy/scripts/doris/copy-images.sh", env={**self.ENV, **env})
            self.assertEqual(status, 0, out)
            self.assertIn(f"fe: {self.OWN}:{FE_TAG}@{FE} (already there)\n"
                          f"be: {self.OWN}:{BE_TAG}@{BE} (already there)\n", out)
        self.assertEqual(self.called("--registry-id"), [])
        self.assertEqual(self.called("get-login-password"), [])
        self.assertEqual(self.called("crane"), [])
        self.assertEqual(self.changes(), [])

    def test_an_image_there_under_other_tags_only_is_tagged_without_the_source(self):
        # The BE under its tag, the FE under build-images.sh's build-<sha>-fe only (#278): the FE is
        # tagged by digest in the environment's registry, the one logged in to; nothing is copied.
        self.answers(2, *self.tagged("be"),
                     rule("ecr", "describe-images", f"imageTag={FE_TAG}", code=254,
                          err=error("ImageNotFoundException", "DescribeImages", f"The image with tag {FE_TAG} does not exist")),
                     rule("ecr", "describe-images", f"imageDigest={FE}", out=FE + "\n"),
                     rule("ecr", "get-login-password", out="token\n"), rule("auth", cli="crane"),
                     rule("tag", f"{self.OWN}@{FE}", FE_TAG, cli="crane"),
                     rule("digest", f"{self.OWN}:{FE_TAG}", out=FE + "\n", cli="crane"))
        status, out = self.run_script("deploy/scripts/doris/copy-images.sh", env={**self.ENV, "DORIS_IMAGE_SOURCE": self.SOURCE})
        self.assertEqual(status, 0, out)
        self.assertIn(f"fe: {self.OWN}:{FE_TAG}@{FE} (there already, tagged now)\n"
                      f"be: {self.OWN}:{BE_TAG}@{BE} (already there)\n", out)
        self.assertTrue(self.called("crane", "tag", f"{self.OWN}@{FE}", FE_TAG), self.calls())
        self.assertEqual(self.called("crane", "copy"), [])
        self.assertEqual(self.called("get-login-password", "--region"), [])
        self.assertTrue(self.called("crane", "auth", "login", f"{ACCOUNT}.dkr.ecr.{REGION}.amazonaws.com"))
        self.assertEqual(self.called("crane", "auth", "login", "111122223333.dkr.ecr.us-east-2.amazonaws.com"), [])

    def test_a_tag_holding_another_digest_stops_it_before_any_login(self):
        # The repository's tags cannot be rewritten: the FE's tag holding another digest is said, and
        # nothing is tagged, copied or logged in to.
        other = "sha256:" + "7" * 64
        self.answers(2, rule("ecr", "describe-images", f"imageTag={FE_TAG}", out=other + "\n"), *self.tagged("be"))
        status, out = self.run_script("deploy/scripts/doris/copy-images.sh", env={**self.ENV, "DORIS_IMAGE_SOURCE": self.SOURCE})
        self.assertEqual(status, 1, out)
        self.assertIn(f"fe: colvenna-lake-old/query-engine:{FE_TAG} is already {other}, not {FE}", out)
        self.assertEqual(self.called("get-login-password"), [])
        self.assertEqual(self.changes(), [])

    def test_a_copy_logs_in_to_the_source_s_region(self):
        held = {"images": [{"imageId": {"imageDigest": d}} for d in (FE, BE)]}
        self.answers(0, rule("ecr", "batch-get-image", "--registry-id", "111122223333", out=held),
                     rule("ecr", "get-login-password", out="token\n"),
                     rule("auth", cli="crane"), rule("copy", cli="crane"),
                     rule("digest", f"{self.OWN}:{FE_TAG}", out=FE + "\n", cli="crane"),
                     rule("digest", f"{self.OWN}:{BE_TAG}", out=BE + "\n", cli="crane"))
        status, out = self.run_script("deploy/scripts/doris/copy-images.sh", env={**self.ENV, "DORIS_IMAGE_SOURCE": self.SOURCE})
        self.assertEqual(status, 0, out)
        self.assertTrue(self.called("get-login-password", "--region", "us-east-2"), self.calls())
        self.assertTrue(self.called("crane", "copy", f"{self.SOURCE}@{FE}", f"{self.OWN}:{FE_TAG}"))
        self.assertIn(f"fe: {self.OWN}:{FE_TAG}@{FE} (from {self.SOURCE})\n", out)
        self.assertTrue(self.called("crane", "auth", "login", "111122223333.dkr.ecr.us-east-2.amazonaws.com"))
        self.assertTrue(self.called("describe-images", "--repository-name", "colvenna-lake-old/query-engine"))

    def test_the_tagged_manifests_are_no_source(self):
        # The tree's v1.0.0-rc.1 manifest pins the same digests under its old keys (schema 1), and
        # v1.0.0-rc.2's in a repository of the product's former names (#271): neither is a source, so
        # the check asks for one before anything is made, and reads no release repository.
        for candidate in ("v1.0.0-rc.1", "v1.0.0-rc.2"):
            tagged = os.path.join(REPO, "release", candidate, "manifest.json")
            if not os.path.exists(tagged):
                self.skipTest(f"no {candidate} manifest in this tree")
            os.makedirs(os.path.join(self.repo, "release", candidate))
            shutil.copy2(tagged, os.path.join(self.repo, "release", candidate, "manifest.json"))
        self.answer(IDENTITY, rule("ecr", "batch-get-image", "colvenna-lake-old/query-engine", code=254,
                                   err=error("RepositoryNotFoundException", "BatchGetImage",
                                             "The repository with name 'colvenna-lake-old/query-engine' does not exist")))
        status, out = self.source("assert_doris_source",
                                  env={**self.ENV, "COLVENNA_RELEASE_REGISTRY": "111122223333.dkr.ecr.us-east-2.amazonaws.com"})
        self.assertEqual(status, 1, out)
        self.assertIn("No source of the query engine's images (based on Apache Doris 4.1.4)", out)
        self.assertTrue(self.called("batch-get-image", "colvenna-lake-old/query-engine"), self.calls())
        self.assertEqual([c for c in self.calls() if any("release/" in t for t in c)], [])
        self.assertEqual(self.changes(), [])

    def test_a_foundation_without_the_query_engine_s_repository_is_refused(self):
        # A foundation without the output (deployed from another commit): nothing is copied until the
        # foundation is deployed again.
        self.answer(IDENTITY, rule("cloudformation", "describe-stacks", "ColvennaLakeOldFoundation", self.OUTPUT,
                                   out="None\n"))
        status, out = self.run_script("deploy/scripts/doris/copy-images.sh", env={**self.ENV, "DORIS_IMAGE_SOURCE": self.SOURCE})
        self.assertEqual(status, 1, out)
        self.assertIn(f"The foundation stack has no query engine repository {self.OWN}; deploy it first", out)
        self.assertEqual(self.called("batch-get-image"), [])
        self.assertEqual(self.called("get-login-password"), [])
        self.assertEqual(self.changes(), [])


if __name__ == "__main__":
    unittest.main()
