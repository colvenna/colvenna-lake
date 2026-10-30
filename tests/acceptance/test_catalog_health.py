"""Offline tests of check-health.sh's metadata-database check (deploy/scripts/catalog/check-health.sh,
#237): its functions run on their own, with a stand-in for aws that answers one RDS DB instance status.
The instance passes in every status it serves connections in, backing-up (the daily automatic
backup) among them, and fails in every other one, including a status RDS adds later."""
import os
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(os.path.dirname(os.path.dirname(HERE)), "deploy", "scripts", "catalog", "check-health.sh")
ENDPOINT = "colvenna-catalog.abcdefghijkl.us-west-2.rds.example.com"
# The RDS user guide's DB instance statuses ("Viewing Amazon RDS DB instance status").
SERVING = ("available", "backing-up", "configuring-enhanced-monitoring", "configuring-iam-database-auth",
           "configuring-log-exports", "resetting-master-credentials", "storage-optimization",
           "storage-initialization")
NOT_SERVING = ("converting-to-vpc", "creating", "delete-precheck", "deleting", "failed",
               "inaccessible-encryption-credentials", "inaccessible-encryption-credentials-recoverable",
               "incompatible-create", "incompatible-network", "incompatible-option-group",
               "incompatible-parameters", "incompatible-restore", "insufficient-capacity", "maintenance",
               "modifying", "moving-to-vpc", "rebooting", "renaming", "restore-error", "starting", "stopped",
               "stopping", "storage-config-upgrade", "storage-full", "upgrading", "upgrade-failed")


def function(name):
    with open(SCRIPT) as f:
        text = f.read()
    start = text.index("\n%s() {" % name) + 1
    return text[start:text.index("\n}\n", start) + 3]


class MetadataDatabaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.tmp.cleanup()

    def health(self, answer):
        """The check's line, health.txt, the failed flag after it, and the aws call it made, when RDS
        answers the line `answer` (the instance's status, version, multi-AZ and backup days)."""
        out = os.path.join(self.tmp.name, "health.txt")
        calls = os.path.join(self.tmp.name, "aws-calls.txt")
        script = """set -euo pipefail
out=%s failed=0
: >"$out"
aws() { printf '%%s\\n' "$*" >%s; printf '%%s\\n' "$ANSWER"; }
%s
%s
metadata_database %s
echo "failed=$failed"
""" % (out, calls, function("check"), function("metadata_database"), ENDPOINT)
        run = subprocess.run(["bash", "-c", script], env={**os.environ, "ANSWER": answer},
                             capture_output=True, text=True, timeout=30)
        self.assertEqual(run.returncode, 0, run.stderr)
        line, failed = run.stdout.splitlines()
        with open(out) as f:
            written = f.read()
        with open(calls) as f:
            call = f.read()
        return line, written, failed, call

    def test_every_status_the_instance_serves_connections_in_passes_and_is_shown(self):
        for status in SERVING:
            with self.subTest(status=status):
                line, written, failed, _ = self.health(f"{status}\t16.13\tFalse\t1")
                self.assertEqual(line, f"metadata-database OK status, version, multi-AZ, backup days: "
                                       f"{status}\t16.13\tFalse\t1")
                self.assertEqual(written, line + "\n")
                self.assertEqual(failed, "failed=0")

    def test_every_other_status_fails_and_is_shown(self):
        for status in NOT_SERVING:
            with self.subTest(status=status):
                line, written, failed, _ = self.health(f"{status}\t16.13\tFalse\t1")
                self.assertEqual(line, f"metadata-database FAIL status, version, multi-AZ, backup days: "
                                       f"{status}\t16.13\tFalse\t1")
                self.assertEqual(written, line + "\n")
                self.assertEqual(failed, "failed=1")

    def test_a_status_rds_adds_later_fails(self):
        for status in ("some-new-status", "available-soon", "Available", "backing-up-and-failing"):
            with self.subTest(status=status):
                line, _, failed, _ = self.health(f"{status}\t16.13\tFalse\t1")
                self.assertTrue(line.startswith("metadata-database FAIL "), line)
                self.assertEqual(failed, "failed=1")

    def test_no_instance_at_the_endpoint_fails(self):
        for answer in ("None", ""):
            with self.subTest(answer=answer):
                line, _, failed, _ = self.health(answer)
                self.assertEqual(line, f"metadata-database FAIL status, version, multi-AZ, backup days: {answer}")
                self.assertEqual(failed, "failed=1")

    def test_the_instance_asked_for_is_the_one_at_the_endpoint(self):
        _, _, _, call = self.health("available\t16.13\tFalse\t1")
        self.assertEqual(call, f"rds describe-db-instances --query DBInstances[?Endpoint.Address=='{ENDPOINT}']"
                               ".[DBInstanceStatus,EngineVersion,MultiAZ,BackupRetentionPeriod] | [0] --output text\n")

if __name__ == "__main__":
    unittest.main()
