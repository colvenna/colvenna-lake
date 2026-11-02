"""Offline tests of tests/acceptance/deploy/run-release-refusals.sh's scratch commits (#18): its
functions edit and commit run on their own, in a linked worktree of a repository whose pre-commit hook
refuses every commit, as a clone with the repository's pre-commit hooks installed can for these
deliberately broken trees. The commits are made all the same, and the edited manifest ends with a
newline. And each case's change to the manifest applies to one of the current schema (#20)."""
import copy
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "deploy", "run-release-refusals.sh")
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(HERE)), "deploy", "scripts", "release"))
import manifest  # noqa: E402
import test_manifest  # noqa: E402  (its inputs(): what make-manifest.sh gathers, synthetic)


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
        # The repository's own hooks, whatever the caller's global configuration says; and the scratch
        # worktree a linked one, sharing them, as the script makes it.
        subprocess.run(git + ["config", "core.hooksPath", os.path.join(self.repo, ".git", "hooks")], check=True)
        self.scratch = os.path.join(self.tmp.name, "scratch")
        subprocess.run(git + ["worktree", "add", "-q", "--detach", self.scratch, "HEAD"], check=True)
        refused = subprocess.run(["git", "-C", self.scratch, "-c", "user.name=t", "-c", "user.email=t@example.invalid",
                                  "commit", "-q", "--allow-empty", "-m", "plain"], capture_output=True, text=True)
        self.assertNotEqual(refused.returncode, 0, "the hook refuses a plain commit")

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        return subprocess.run(["git", "-C", self.scratch, *args], capture_output=True, text=True, check=True).stdout

    def test_the_hooks_do_not_stop_a_scratch_commit(self):
        script = "set -euo pipefail\nscratch=%s manifest=%s\n%s\n%s\nedit %s\n" % (
            self.scratch, self.manifest, function("commit"), function("edit"),
            json.dumps('pin("bi", "sha256:" + "0" * 64)'))
        run = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=60)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertNotIn("refuses everything", run.stderr)
        self.assertEqual(self.git("log", "-1", "--format=%s").strip(), "change the manifest")
        self.assertEqual(self.git("status", "--porcelain"), "")
        with open(os.path.join(self.scratch, self.manifest)) as f:
            text = f.read()
        self.assertTrue(text.endswith("}\n"), repr(text[-5:]))
        self.assertEqual(json.loads(text)["images"]["bi"]["digest"], "sha256:" + "0" * 64)


class ManifestEditsTest(unittest.TestCase):
    """Each case's change to the manifest (the script's edit '<statement>' lines) runs on a manifest of
    the schema this tree's tools write (#20: schema 3 has the catalog's release apart, no images.catalog),
    and changes it."""

    def test_every_edit_applies_to_a_manifest_of_the_current_schema(self):
        current = manifest.build(test_manifest.inputs())
        self.assertEqual(manifest.problems(current), [])
        # A digest of its own for each product image, so that a re-pin or a swap shows.
        for n, image in enumerate(current["images"].values(), 1):
            image["digest"] = "sha256:%064x" % n
        with open(SCRIPT) as f:
            statements = re.findall(r"^edit '(.*)'$", f.read(), re.M)
        self.assertGreaterEqual(len(statements), 5)
        for statement in statements:
            with self.subTest(statement=statement):
                m = copy.deepcopy(current)

                def pin(key, digest, m=m):
                    m["images"][key]["digest"] = digest
                exec(statement, {"m": m, "pin": pin})
                self.assertNotEqual(m, current)


if __name__ == "__main__":
    unittest.main()
