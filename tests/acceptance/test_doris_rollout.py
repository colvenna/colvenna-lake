"""Offline tests of how the doris step waits for the cluster it applied (#261): lib/common.sh's
doris_component_current, which install-doris.sh asks before it connects to the FE, and doris_sql's
error on stderr. The library runs from a scratch copy of deploy/ (test_third_party.Scratch) against a
stub kubectl that answers each get from a table."""
import json
import os
import unittest

from test_third_party import Scratch

IMAGE = "123456789012.dkr.ecr.us-west-2.amazonaws.com/colvenna-lake-t234/query-engine@sha256:" + "a" * 64
OLD = "123456789012.dkr.ecr.us-west-2.amazonaws.com/colvenna-lake-t234/doris@sha256:" + "a" * 64

# kubectl: "get <kind> <name>" answers from KUBECTL_ANSWERS (<kind>/<name> -> stdout; missing: none,
# as --ignore-not-found prints); everything else succeeds silently.
KUBECTL = r'''#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
if "get" in args:
    i = args.index("get")
    print(json.load(open(os.environ["KUBECTL_ANSWERS"])).get(f"{args[i + 1]}/{args[i + 2]}", ""), end="")
'''


def rollout(generation=2, observed=2, current="r2", update="r2", replicas=1, ready=1):
    return " ".join(str(v) for v in (generation, observed, current, update, replicas, "" if ready is None else ready))


class DorisRollout(unittest.TestCase):
    def setUp(self):
        self.scratch = Scratch(self)
        with open(os.path.join(self.scratch.bin, "kubectl"), "w") as f:
            f.write(KUBECTL)
        os.chmod(os.path.join(self.scratch.bin, "kubectl"), 0o700)
        self.answers = os.path.join(self.scratch.tmp.name, "answers.json")

    def call(self, answers, lines):
        with open(self.answers, "w") as f:
            json.dump(answers, f)
        with open(os.path.join(self.scratch.root, "deploy", "probe.sh"), "w") as f:
            f.write(f'source "$(dirname "$0")/scripts/lib/common.sh"\n{lines}\n')
        return self.scratch.run("deploy/probe.sh", KUBECTL_ANSWERS=self.answers)

    def current(self, statefulset, pod):
        answers = {"pod/colvenna-doris-fe-0": pod}
        if statefulset is not None:
            answers["statefulset/colvenna-doris-fe"] = statefulset
        result = self.call(answers, f'doris_component_current colvenna-doris colvenna-doris fe "{IMAGE}" && echo current'
                                    ' || echo not')
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def test_rolled_out_and_running_the_applied_image(self):
        self.assertEqual(self.current(rollout(), f"{IMAGE} true"), "current")

    def test_old_pods_still_ready_are_not_the_applied_cluster(self):
        # The upgrade of #261: the DorisCluster names the new repository, the pod still runs the old.
        self.assertEqual(self.current(rollout(), f"{OLD} true"), "not")

    def test_a_rollout_under_way_is_not_done(self):
        for statefulset in (rollout(observed=1), rollout(current="r1"), rollout(ready=0), rollout(ready=None)):
            with self.subTest(statefulset=statefulset):
                self.assertEqual(self.current(statefulset, f"{IMAGE} true"), "not")

    def test_the_applied_image_not_ready_yet(self):
        self.assertEqual(self.current(rollout(), f"{IMAGE} false"), "not")

    def test_no_statefulset_yet(self):
        # A new cluster before the Doris Operator made the BE (apache/doris-operator#495).
        self.assertEqual(self.current(None, ""), "not")

    def sql(self, output, then=""):
        """doris_sql on a one-line file, with run_pod_out replaced by one that prints <output>, as it
        copies a pod's: the client's rows or error, then the marker."""
        return self.call({}, f"run_pod_out() {{ cat <<'OUT'\n{output}OUT\n}}\n"
                             'echo "SELECT 1;" >"$(dirname "$0")/one.sql"\n'
                             f'doris_sql "$(dirname "$0")/one.sql" {then}')

    def test_a_failed_statement_s_error_reaches_stderr(self):
        result = self.sql("ERROR 2003 (HY000): Can't connect to MySQL server\nDORIS-SQL-FAILED\n",
                          ">/dev/null && echo succeeded || echo failed")
        self.assertEqual(result.stdout.strip(), "failed", result.stderr)
        self.assertIn("one.sql failed:", result.stderr)
        self.assertIn("ERROR 2003 (HY000): Can't connect to MySQL server", result.stderr)
        self.assertNotIn("DORIS-SQL-FAILED", result.stderr)

    def test_a_password_a_failed_statement_echoes_is_masked_on_stderr(self):
        result = self.sql("ERROR 1105 (HY000): near SET PASSWORD FOR 'admin'@'%' = PASSWORD('s3cr3t')\n"
                          "DORIS-SQL-FAILED\n", ">/dev/null || true")
        self.assertIn("PASSWORD('<masked>')", result.stderr)
        self.assertNotIn("s3cr3t", result.stderr)

    def test_a_statement_that_succeeds_says_nothing_on_stderr(self):
        result = self.sql("1\nDORIS-SQL-OK\n")
        self.assertEqual((result.returncode, result.stdout, result.stderr), (0, "1\n", ""))

if __name__ == "__main__":
    unittest.main()
