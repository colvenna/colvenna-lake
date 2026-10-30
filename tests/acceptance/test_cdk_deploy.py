"""Offline tests of cdk_deploy (deploy/scripts/lib/cdk.sh, #233): a `cdk deploy` that failed because
the CDK CLI resolved no AWS account from the credentials is run again, a bounded number of times;
credentials of another account, and every other failure, are returned at once; the CLI never gets a
CDK_DEFAULT_ACCOUNT of the caller's. A stub npx answers each run as the scenario says and logs it."""
import json
import os
import re
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
HELPER = os.path.join(REPO, "deploy", "scripts", "lib", "cdk.sh")
ENV_TS = os.path.join(REPO, "deploy", "aws", "src", "env.ts")


def unresolved_account():
    """deploy/aws/src/env.ts's UNRESOLVED_ACCOUNT, what its synthesisAccount says then."""
    with open(ENV_TS) as f:
        return re.search(r"export const UNRESOLVED_ACCOUNT = '([^']+)';", f.read()).group(1)


# What a run of the CLI says on its standard error, and its exit status, as each scenario has it.
ANSWERS = {
    "ok": ("ColvennaLakeUatOpenLayer: deploying... [1/1]\n ✅  ColvennaLakeUatOpenLayer", 0),
    "unresolved": (f"Error: The CDK CLI {unresolved_account()} (no CDK_DEFAULT_ACCOUNT), so they cannot be checked"
                   " against -c account=111122223333: STS or the credentials did not answer in time; run it again\n"
                   "    at synthesisAccount (deploy/aws/src/env.ts:62:11)\n"
                   "npx ts-node src/app.ts: Subprocess exited with error 1", 1),
    "mismatch": ("Error: The credentials are of account 444455556666, not -c account=111122223333\n"
                 "npx ts-node src/app.ts: Subprocess exited with error 1", 1),
    "failed": (" ❌  ColvennaLakeUatOpenLayer failed: The stack is in UPDATE_ROLLBACK_COMPLETE", 7),
}

STUB = r'''#!/usr/bin/env python3
import json, os, sys
path = os.environ["CDK_STUB_STATE"]
state = json.load(open(path))
run = len(state["calls"]) + 1
state["calls"].append({"args": sys.argv[1:], "account": os.environ.get("CDK_DEFAULT_ACCOUNT"),
                       "region": os.environ.get("CDK_DEFAULT_REGION"), "aws_region": os.environ.get("AWS_REGION")})
json.dump(state, open(path, "w"))
answer = state["answers"][min(run, len(state["answers"])) - 1]
print(f"stdout of run {run}")
print(answer[0], file=sys.stderr)
sys.exit(answer[1])
'''

ARGS = ["ColvennaLakeUatOpenLayer", "--require-approval", "never", "-c", "account=111122223333", "-c", "component=open-layer"]


class CdkDeployTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        bin_dir = os.path.join(self.tmp.name, "bin")
        os.makedirs(bin_dir)
        with open(os.path.join(bin_dir, "npx"), "w") as f:
            f.write(STUB)
        os.chmod(os.path.join(bin_dir, "npx"), 0o700)
        self.state = os.path.join(self.tmp.name, "state.json")
        env = {k: v for k, v in os.environ.items() if not k.startswith("CDK_")}
        self.env = {**env, "PATH": bin_dir + os.pathsep + os.environ["PATH"], "CDK_STUB_STATE": self.state,
                    "CDK_DEPLOY_RETRY_WAIT": "0", "AWS_REGION": "us-west-2"}

    def tearDown(self):
        self.tmp.cleanup()

    def deploy(self, *answers, **env):
        """cdk_deploy ARGS as a deploy script calls it (errexit, pipefail), the CLI's runs answering so."""
        with open(self.state, "w") as f:
            json.dump({"answers": [ANSWERS[a] for a in answers], "calls": []}, f)
        result = subprocess.run(
            ["bash", "-c", 'set -euo pipefail; source "$0"; cdk_deploy "$@"; echo after-deploy', HELPER, *ARGS],
            env={**self.env, **env}, capture_output=True, text=True, timeout=60)
        with open(self.state) as f:
            return result, json.load(f)["calls"]

    def test_a_deploy_that_succeeds_runs_once_its_output_passed_on(self):
        result, calls = self.deploy("ok")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([c["args"] for c in calls], [["cdk", "deploy", *ARGS]])
        self.assertEqual(result.stdout, "stdout of run 1\nafter-deploy\n")
        self.assertIn("✅  ColvennaLakeUatOpenLayer", result.stderr)
        self.assertNotIn("cdk_deploy", result.stderr)

    def test_a_run_whose_cli_resolved_no_account_is_run_again(self):
        result, calls = self.deploy("unresolved", "ok")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0]["args"], calls[1]["args"])
        self.assertIn(f"cdk_deploy ColvennaLakeUatOpenLayer: the CDK CLI {unresolved_account()} (run 1 of 3), nothing "
                      "deployed; running it again in 0s (issue #233)", result.stderr)
        self.assertEqual(result.stdout, "stdout of run 1\nstdout of run 2\nafter-deploy\n")

    def test_the_runs_are_bounded(self):
        result, calls = self.deploy("unresolved")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(calls), 3)
        self.assertIn(f"the CDK CLI {unresolved_account()} in 3 runs; check that they answer "
                      "(aws sts get-caller-identity), then run the step again", result.stderr)
        self.assertNotIn("after-deploy", result.stdout)
        result, calls = self.deploy("unresolved", "unresolved", "ok", CDK_DEPLOY_ATTEMPTS="2")
        self.assertEqual((result.returncode, len(calls)), (1, 2))
        result, calls = self.deploy("unresolved", "ok", CDK_DEPLOY_ATTEMPTS="1")
        self.assertEqual((result.returncode, len(calls)), (1, 1))

    def test_credentials_of_another_account_are_refused_at_once(self):
        result, calls = self.deploy("mismatch", "ok")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(calls), 1)
        self.assertIn("The credentials are of account 444455556666, not -c account=111122223333", result.stderr)
        self.assertNotIn("running it again", result.stderr)
        # Also after a run that resolved none: the next run's answer is checked afresh.
        result, calls = self.deploy("unresolved", "mismatch", "ok")
        self.assertEqual((result.returncode, len(calls)), (1, 2))

    def test_any_other_failure_is_returned_at_once_with_the_cli_s_status(self):
        result, calls = self.deploy("failed", "ok")
        self.assertEqual(result.returncode, 7)
        self.assertEqual(len(calls), 1)
        self.assertNotIn("after-deploy", result.stdout)

    def test_the_cli_gets_no_default_account_or_region_of_the_caller_s(self):
        # The CLI passes a CDK_DEFAULT_ACCOUNT of its environment on to the app when it resolves none:
        # the app would then check -c account against the caller's word, not the credentials.
        result, calls = self.deploy("unresolved", "ok", CDK_DEFAULT_ACCOUNT="111122223333",
                                    CDK_DEFAULT_REGION="us-west-2")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([(c["account"], c["region"], c["aws_region"]) for c in calls],
                         [(None, None, "us-west-2")] * 2)

    def test_settings_that_are_not_counts_are_refused(self):
        for env in ({"CDK_DEPLOY_ATTEMPTS": "0"}, {"CDK_DEPLOY_ATTEMPTS": "three"}, {"CDK_DEPLOY_RETRY_WAIT": "-1"}):
            result, calls = self.deploy("ok", **env)
            self.assertEqual((result.returncode, calls), (1, []), env)
            self.assertIn("CDK_DEPLOY_ATTEMPTS is a count from 1", result.stderr)


class WiringTest(unittest.TestCase):
    def test_the_helper_retries_on_what_the_app_says_and_on_nothing_else(self):
        with open(HELPER) as f:
            marker = re.search(r"^CDK_UNRESOLVED_ACCOUNT='([^']+)'$", f.read(), re.M).group(1)
        self.assertEqual(marker, unresolved_account())
        with open(ENV_TS) as f:
            source = f.read()
        mismatch = re.search(r"throw new Error\(`(The credentials are of account [^`]+)`\)", source).group(1)
        self.assertNotIn(marker, mismatch)

    def test_every_cdk_deploy_goes_through_cdk_deploy(self):
        out = subprocess.run(["git", "-C", REPO, "ls-files", "-z", "--", "*.sh"],
                             check=True, capture_output=True, text=True).stdout
        direct, callers = [], []
        for path in filter(None, out.split("\0")):
            if path == "deploy/scripts/lib/cdk.sh":
                continue
            with open(os.path.join(REPO, path)) as f:
                text = f.read()
            code = [line for line in text.splitlines() if not line.lstrip().startswith("#")]
            # deploy, and any other run of the app (synth, diff, destroy): each resolves the account.
            direct += [f"{path}: {line.strip()}" for line in code
                       if re.search(r"\bcdk\s+(deploy|synth|diff|destroy)\b", line)]
            if any(re.search(r"^\s*cdk_deploy\s", line) for line in code):
                callers.append(path)
                self.assertTrue(re.search(r"(lib/common\.sh|lib/cdk\.sh|acceptance/common\.sh)\"$", text, re.M),
                                f"{path} calls cdk_deploy without sourcing it")
        self.assertEqual([], direct, "cdk deploy run around cdk_deploy:\n" + "\n".join(direct))
        self.assertIn("deploy/scripts/open-layer/deploy-stack.sh", callers)
        self.assertEqual(len(callers), 7, callers)


if __name__ == "__main__":
    unittest.main()
