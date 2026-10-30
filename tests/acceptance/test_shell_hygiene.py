"""Offline checks of the repository's shell scripts (#227): scripts the docs run directly are
executable, and no judgement reads `<command> | grep -q` under pipefail, where grep exits on its
match, the command writing on dies of SIGPIPE (141), and the judgement reads as no match."""
import os
import re
import subprocess
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
# Sourced, never run: no executable bit needed.
LIBRARIES = re.compile(r"^deploy/(env\.sh|scripts/lib/.*)$")
# Lines that run in a pod's own shell, which sets no pipefail.
IN_A_POD = {("tests/fixtures/kafka/dev-kafka.sh", "kafka-topics.sh --bootstrap-server")}


def tracked(*patterns):
    out = subprocess.run(["git", "-C", ROOT, "ls-files", "-s", "--", *patterns],
                         capture_output=True, text=True, check=True).stdout
    return [line.split(None, 3) for line in out.splitlines()]


class ShellHygieneTest(unittest.TestCase):
    def test_deploy_scripts_are_executable(self):
        loose = [path for mode, _, _, path in tracked("deploy/*.sh", "deploy/scripts/*/*.sh")
                 if mode != "100755" and not LIBRARIES.match(path)]
        self.assertEqual(loose, [], "chmod +x these, as the docs run them directly")

    def test_no_grep_q_reads_a_pipe(self):
        found = []
        for _, _, _, path in tracked("deploy/*.sh", "deploy/scripts/*/*.sh", "tests/*.sh",
                                     "tests/*/*.sh", "tests/*/*/*.sh"):
            with open(os.path.join(ROOT, path), encoding="utf-8") as f:
                for n, line in enumerate(f, 1):
                    if re.search(r"\|\s*grep\s+-[a-zA-Z]*q", line) and not any(
                            path == p and marker in line for p, marker in IN_A_POD):
                        found.append(f"{path}:{n}")
        self.assertEqual(found, [], "use grep -q … <<<\"$(command)\" or the command's own exit status")


if __name__ == "__main__":
    unittest.main()
