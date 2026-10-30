import contextlib
import io
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "operations"))
import judge  # noqa: E402


def task(name, tag, phase="Succeeded", verdict=None):
    return {"kind": "LakeTask", "metadata": {"name": name, "uid": name + "-uid"},
            "spec": {"type": "createTag", "table": {"database": "ods", "table": "t"}, "createTag": {"name": tag}},
            "status": {"phase": phase, "conditions": [{"type": "Done", "status": "True", "reason": phase}],
                       "check": {"verdict": verdict} if verdict else None}}


class CompareTest(unittest.TestCase):
    def write(self, value):
        f = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False)
        json.dump(value, f)
        f.close()
        return f.name

    def quiet(self, check, *args):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            ok = check(*args)
        return ok, out.getvalue()

    def test_same_declaration_judged_the_same_names_aside(self):
        cli, console = self.write(task("t-l", "l-1")), self.write(task("t-c", "c-1"))
        self.assertTrue(self.quiet(judge.same, cli, console, "l-1=c-1")[0])
        self.assertFalse(self.quiet(judge.same, cli, console)[0])
        other = self.write(task("t-c", "c-1", phase="Failed"))
        ok, out = self.quiet(judge.same, cli, other, "l-1=c-1")
        self.assertFalse(ok)
        self.assertIn("phase", out)

    def test_the_audit_has_the_operation_and_its_outcome(self):
        events = [json.dumps(e) for e in [
            {"kind": "operation", "username": "u", "action": "delete", "resource": "ns/LakeQuality/q", "reason": "previewed"},
            {"kind": "operation", "username": "u", "action": "delete", "resource": "ns/LakeQuality/q", "reason": "requested"},
            {"kind": "operation", "username": "u", "action": "delete", "resource": "ns/LakeQuality/q", "reason": "deleted", "status": 200},
            {"kind": "operation", "username": "u", "action": "backfill", "resource": "ns/LakeTask/x", "reason": "previewed"},
        ]]
        path = self.write(events)
        self.assertTrue(self.quiet(judge.audit, path, "u", "delete", "ns/LakeQuality/q", "ok")[0])
        self.assertTrue(self.quiet(judge.audit, path, "u", "backfill", "ns/LakeTask/x", "previewed")[0])
        # A preview that was then done is not a cancelled one; nothing by someone else counts.
        self.assertFalse(self.quiet(judge.audit, path, "u", "delete", "ns/LakeQuality/q", "previewed")[0])
        self.assertFalse(self.quiet(judge.audit, path, "v", "delete", "ns/LakeQuality/q", "ok")[0])

    def test_the_cluster_audit_names_the_person_behind_the_console(self):
        event = {"verb": "create", "objectRef": {"resource": "lakewaivers", "name": "w"},
                 "user": {"username": "system:serviceaccount:colvenna-console:colvenna-console"},
                 "impersonatedUser": {"username": "colvenna-console:admin", "extra": {"colvenna-lake.io/person": ["ada"]}}}
        path = self.write([json.dumps(event)])
        self.assertTrue(self.quiet(judge.cluster_audit, path, "colvenna-console:admin", "ada", "create", "lakewaivers", "w")[0])
        self.assertFalse(self.quiet(judge.cluster_audit, path, "colvenna-console:admin", "bob", "create", "lakewaivers", "w")[0])


if __name__ == "__main__":
    unittest.main()
