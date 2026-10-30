"""Offline tests of run_pod_out (deploy/scripts/lib/pod-out.sh), which copies a pod's output from a
file in the pod rather than reading the pod's log (#244): it prints the output once the pod records
its size and a copy comes back with that size, copies again when one comes back short, and fails,
printing nothing, when the pod fails or ends first, when it records nothing in time, or when no
copy comes back whole. A stub kubectl plays the pod and logs every call."""
import json
import os
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
HELPER = os.path.join(os.path.dirname(os.path.dirname(HERE)), "deploy", "scripts", "lib", "pod-out.sh")

STUB = r'''#!/usr/bin/env python3
import json, os, sys
path = os.environ["KUBECTL_STUB_STATE"]
state = json.load(open(path))
args = sys.argv[1:]
if args[:1] == ["-n"]:
    args = args[2:]
code = 0
out = state["output"].encode()
if args[0] == "get":
    state["gets"] += 1
    phases = state["phases"]
    print(phases[min(state["gets"], len(phases)) - 1])
elif args[0] == "exec":
    command = args[args.index("--") + 1:]
    if command == ["cat", "/out/done"]:
        if state["done_at"] is not None and state["gets"] >= state["done_at"]:
            print(len(out))
        else:
            print("cat: /out/done: No such file or directory", file=sys.stderr)
            code = 1
    elif command == ["cat", "/out/out"]:
        state["copies"] += 1
        if state["copies"] <= state["failed_copies"]:
            sys.stdout.buffer.write(out[: len(out) // 3])
            print("error: stream closed", file=sys.stderr)
            code = 1
        elif state["copies"] <= state["failed_copies"] + state["short_copies"]:
            sys.stdout.buffer.write(out[: len(out) // 2])
        else:
            sys.stdout.buffer.write(out)
    else:
        code = 2
elif args[0] == "logs":
    print(state["logs"])
state["calls"].append(" ".join(args[:2]))
with open(path + ".part", "w") as f:
    json.dump(state, f)
os.replace(path + ".part", path)
sys.exit(code)
'''

# Not ASCII, and no final newline: a copy is checked by its bytes.
OUTPUT = '{"type":"TABLE","table":"db.t"}\n{"type":"ROW","row":{"name":"Zoë"}}\n' * 200 + "end"


class RunPodOutTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        bin_dir = os.path.join(self.tmp.name, "bin")
        os.makedirs(bin_dir)
        with open(os.path.join(bin_dir, "kubectl"), "w") as f:
            f.write(STUB)
        os.chmod(os.path.join(bin_dir, "kubectl"), 0o700)
        self.state = os.path.join(self.tmp.name, "state.json")
        self.env = {**os.environ, "PATH": bin_dir + os.pathsep + os.environ["PATH"],
                    "KUBECTL_STUB_STATE": self.state}

    def tearDown(self):
        self.tmp.cleanup()

    def run_pod_out(self, phases, done_at=1, short_copies=0, failed_copies=0, seconds=""):
        with open(self.state, "w") as f:
            json.dump({"phases": phases, "done_at": done_at, "short_copies": short_copies,
                       "failed_copies": failed_copies, "output": OUTPUT, "logs": "the pod's log",
                       "gets": 0, "copies": 0, "calls": []}, f)
        result = subprocess.run(
            ["bash", "-c", f'set -euo pipefail; source "{HELPER}"; run_pod_out ns dump pod.yaml {seconds}'],
            env=self.env, capture_output=True, timeout=60)
        with open(self.state) as f:
            state = json.load(f)
        return result, state["calls"], state["copies"]

    def test_the_output_comes_from_the_file_once_its_size_is_recorded(self):
        result, calls, copies = self.run_pod_out(["Pending", "Running", "Running"], done_at=3)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, OUTPUT.encode())
        self.assertEqual(calls, ["delete pod", "apply -f", "get pod", "get pod", "exec dump",
                                 "get pod", "exec dump", "exec dump", "delete pod"])
        self.assertEqual(copies, 1)

    def test_a_short_or_failed_copy_is_copied_again(self):
        result, calls, copies = self.run_pod_out(["Running"], short_copies=1, failed_copies=1)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, OUTPUT.encode())
        self.assertEqual(copies, 3)
        self.assertIn(b"a copy of its output failed", result.stderr)
        self.assertIn(f"came back with {len(OUTPUT.encode()) // 2} of {len(OUTPUT.encode())} bytes".encode(),
                      result.stderr)

    def test_no_whole_copy_in_three_fails_and_prints_nothing(self):
        result, calls, copies = self.run_pod_out(["Running"], short_copies=5)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, b"")
        self.assertEqual(copies, 3)
        self.assertIn(b"Pod dump: its output did not come back whole", result.stderr)
        self.assertEqual(calls[-1], "delete pod")

    def test_a_failed_pod_fails_with_its_log(self):
        result, calls, copies = self.run_pod_out(["Pending", "Failed"], done_at=None)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, b"")
        self.assertEqual(copies, 0)
        self.assertIn(b"the pod's log", result.stderr)
        self.assertIn(b"Pod dump did not succeed (Failed): no output recorded in it", result.stderr)
        self.assertEqual(calls[-2:], ["logs dump", "delete pod"])

    def test_a_pod_that_ended_before_the_copy_fails(self):
        result, _, copies = self.run_pod_out(["Succeeded"], done_at=None)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(copies, 0)
        self.assertIn(b"did not succeed (Succeeded)", result.stderr)

    def test_a_pod_that_records_nothing_in_time_fails(self):
        result, calls, copies = self.run_pod_out(["Running"], done_at=None, seconds="1")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, b"")
        self.assertEqual(copies, 0)
        self.assertIn(b"did not succeed (Running)", result.stderr)
        self.assertEqual(calls[-1], "delete pod")


if __name__ == "__main__":
    unittest.main()
