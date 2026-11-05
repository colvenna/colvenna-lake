"""Offline tests of the ggshield hooks, ggshield_hook.py (#44): it runs ggshield only at the version
requirements.lock pins, here a stand-in ggshield that reports a version and the arguments it was given."""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import ggshield_pin  # noqa: E402


class HookTest(unittest.TestCase):
    """The pre-commit hooks run ggshield only at the version requirements.lock pins."""

    def run_hook(self, version, *args):
        with tempfile.TemporaryDirectory() as work:
            fake = Path(work, "ggshield")
            fake.write_text("#!/bin/sh\n"
                            f'if [ "$1" = --version ]; then echo "ggshield, version {version}"; exit 0; fi\n'
                            'echo "ran: $*"; exit 3\n')
            fake.chmod(0o755)
            return subprocess.run([sys.executable, str(HERE / "ggshield_hook.py"), *args], capture_output=True,
                                  text=True, env={**os.environ, "PATH": f"{work}:/usr/bin:/bin"})

    def test_the_pinned_version_scans_the_staged_changes_or_the_pushed_commits(self):
        pinned = ggshield_pin.pinned_version()

        for mode in ("pre-commit", "pre-push"):
            done = self.run_hook(pinned, mode)

            self.assertEqual((done.returncode, done.stdout), (3, f"ran: secret scan {mode}\n"))

    def test_another_version_is_refused_with_the_reason(self):
        done = self.run_hook("0.0.1", "pre-commit")

        self.assertEqual(done.returncode, 1)
        self.assertIn("needs ggshield", done.stderr)
        self.assertIn("found: 0.0.1", done.stderr)
        self.assertIn("SKIP=ggshield,ggshield-push", done.stderr)


if __name__ == "__main__":
    unittest.main()
