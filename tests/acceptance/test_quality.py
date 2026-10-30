import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "quality"))
import quality  # noqa: E402


def dump(path, table, rows):
    with open(path, "w") as f:
        f.write(json.dumps({"type": "TABLE", "table": table, "fields": []}) + "\n")
        for r in rows:
            f.write(json.dumps({"type": "ROW", "table": table, "kind": "+I", "row": r}) + "\n")


def result(uid, rule, passed, measured="1.0", snapshot="9", locate="SELECT 1"):
    return {"task_uid": uid, "rule": rule, "task": "check-" + uid, "checked_table": "ods.clicks",
            "partitions": "dt=2026-10-16", "snapshot_id": snapshot, "as_of": "2026-10-16T08:00",
            "rule_version": "abc", "definition": "{}", "metric": "m", "measured": measured,
            "threshold": "t", "passed": passed, "failing_rows": "0", "checked_rows": "10",
            "locate": locate, "checked_at": "2026-10-16T08:01"}


class QualityTest(unittest.TestCase):
    def run_main(self, *argv):
        out = io.StringIO()
        with redirect_stdout(out):
            code = quality.main(list(argv))
        return code, json.loads(out.getvalue().splitlines()[-1]) if out.getvalue() else None

    def test_events_have_their_bad_kinds(self):
        with tempfile.TemporaryDirectory() as d:
            exp = os.path.join(d, "e.json")
            out = io.StringIO()
            with redirect_stdout(out):
                quality.main(["events", "b", "2026-10-15", "4", "--nulls", "2", "--dups", "1",
                              "--expected", exp])
            lines = out.getvalue().strip().splitlines()
            self.assertEqual(7, len(lines))
            e = json.load(open(exp))
            self.assertEqual(["b-null-0", "b-null-1"], e["nulls"])
            self.assertEqual(["b-0"], e["dups"])
            first = json.loads(lines[0].split("|", 1)[1])
            self.assertTrue(first["event_time"].startswith("2026-10-15T12:00"))
            self.assertNotIn("user_id", json.loads(lines[4].split("|", 1)[1]))

    def test_results_and_same(self):
        with tempfile.TemporaryDirectory() as d:
            f = os.path.join(d, "r.jsonl")
            dump(f, "quality_x.results",
                 [result("a", "volume", "true"), result("a", "fresh", "false"),
                  result("b", "volume", "true"), result("b", "fresh", "false"),
                  result("c", "volume", "true", measured="2.0"), result("c", "fresh", "false")])
            self.assertEqual(0, self.run_main("results", f, "a", "volume=true", "fresh=false")[0])
            self.assertEqual(1, self.run_main("results", f, "a", "volume=false", "fresh=false")[0])
            self.assertEqual(0, self.run_main("same", f, "a", "b")[0])
            code, v = self.run_main("same", f, "a", "c")
            self.assertEqual(1, code)
            self.assertIn("volume.measured", v["problems"][0])

    def test_locate_lists_exactly_the_bad_events(self):
        with tempfile.TemporaryDirectory() as d:
            exp = os.path.join(d, "e.json")
            json.dump({"nulls": ["x-null-0"], "dups": ["x-0", "x-1"]}, open(exp, "w"))
            nulls = os.path.join(d, "n.jsonl")
            with open(nulls, "w") as f:
                f.write(json.dumps({"ROW": ["x-null-0", None, "1"]}) + "\n")
            self.assertEqual(0, self.run_main("locate", nulls, exp, "nulls")[0])
            dups = os.path.join(d, "d.jsonl")
            with open(dups, "w") as f:
                f.write(json.dumps({"ROW": ["x-0", "2"]}) + "\n")
            self.assertEqual(1, self.run_main("locate", dups, exp, "dups")[0])
            with open(dups, "a") as f:
                f.write(json.dumps({"ROW": ["x-1", "2"]}) + "\n")
            self.assertEqual(0, self.run_main("locate", dups, exp, "dups")[0])

    def test_decisions_in_order_and_naming_their_checks(self):
        with tempfile.TemporaryDirectory() as d:
            f = os.path.join(d, "d.jsonl")
            rows = [{"checked_table": "ods.clicks", "decided_at": f"2026-10-16T0{i}:00",
                     "cause": c, "check_uid": None if c == "NoCheck" else "u"}
                    for i, c in enumerate(["NoCheck", "Passed", "Failed", "Waived"])]
            dump(f, "quality_x.decisions", rows)
            self.assertEqual(0, self.run_main("decisions", f, "ods.clicks", "NoCheck", "Failed",
                                              "Waived")[0])
            self.assertEqual(1, self.run_main("decisions", f, "ods.clicks", "Waived",
                                              "Failed")[0])


if __name__ == "__main__":
    unittest.main()
