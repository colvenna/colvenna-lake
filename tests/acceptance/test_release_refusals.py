"""Offline tests of tests/acceptance/deploy/run-release-refusals.sh's scratch commits (#18): its
functions edit and commit run on their own, in a repository whose pre-commit hook refuses every
commit, as a clone with the repository's pre-commit hooks installed can for these deliberately broken
trees. The commits are made all the same, and the edited manifest ends with a newline."""
import json
import os
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "deploy", "run-release-refusals.sh")


def function(name):
    with open(SCRIPT) as f:
        text = f.read()
    start = text.index("\n%s() {" % name) + 1
    return text[start:text.index("\n}\n", start) + 3]


class ScratchCommitTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = os.path.join(self.tmp.name, "repo")
        os.makedirs(os.path.join(self.repo, "release", "v0.0.0-dev.1"))
        self.manifest = "release/v0.0.0-dev.1/manifest.json"
        with open(os.path.join(self.repo, self.manifest), "w") as f:
            json.dump({"images": {"bi": {"digest": "sha256:" + "1" * 64}}}, f, indent=2)
            f.write("\n")
        git = ["git", "-C", self.repo, "-c", "user.name=t", "-c", "user.email=t@example.invalid"]
        subprocess.run(["git", "init", "-q", self.repo], check=True)
        subprocess.run(git + ["add", "-A"], check=True)
        subprocess.run(git + ["commit", "-q", "-m", "base"], check=True)
        hook = os.path.join(self.repo, ".git", "hooks", "pre-commit")
        with open(hook, "w") as f:
            f.write("#!/bin/sh\necho 'a hook that refuses everything' >&2\nexit 1\n")
        os.chmod(hook, 0o755)

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        return subprocess.run(["git", "-C", self.repo, *args], capture_output=True, text=True, check=True).stdout

    def test_the_hooks_do_not_stop_a_scratch_commit(self):
        script = "set -euo pipefail\nscratch=%s manifest=%s\n%s\n%s\nedit %s\n" % (
            self.repo, self.manifest, function("commit"), function("edit"),
            json.dumps('pin("bi", "sha256:" + "0" * 64)'))
        run = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=60)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertNotIn("refuses everything", run.stderr)
        self.assertEqual(self.git("log", "-1", "--format=%s").strip(), "change the manifest")
        self.assertEqual(self.git("status", "--porcelain"), "")
        with open(os.path.join(self.repo, self.manifest)) as f:
            text = f.read()
        self.assertTrue(text.endswith("}\n"), repr(text[-5:]))
        self.assertEqual(json.loads(text)["images"]["bi"]["digest"], "sha256:" + "0" * 64)


if __name__ == "__main__":
    unittest.main()
