"""Unit tests of the Kafka run's judge (kafka/events.py)."""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


class JudgeTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)

    def write(self, name, lines):
        path = os.path.join(self.dir, name)
        with open(path, "w") as f:
            for line in lines:
                f.write((line if isinstance(line, str) else json.dumps(line)) + "\n")
        return path

    def run_judge(self, events_rows, rejection_rows, ends):
        expected = self.write("expected.json", [{"events": {
            "e1": {"event_id": "e1", "event_type": "view", "amount": "1.50", "user_id": 1, "campaign": "autumn"},
            "e2": {"event_id": "e2", "event_type": "click", "amount": None, "user_id": None, "campaign": None}},
            "rejections": {"WRONG_TYPE": 1}}])
        rows = self.write("rows.jsonl", [{"type": "ROW", "table": "ods.events", "row": r} for r in events_rows])
        rej = self.write("rej.jsonl", [{"type": "ROW", "table": "ods.events_rejections", "row": r}
                                       for r in rejection_rows])
        ends_file = self.write("ends.txt", ends)
        return subprocess.run([sys.executable, os.path.join(HERE, "kafka", "events.py"), "judge",
                               "--rows", rows, "--rejections", rej, "--ends", ends_file,
                               "--table", "ods.events", "--rejections-table", "ods.events_rejections",
                               expected], capture_output=True, text=True)

    def event(self, eid, offset, **extra):
        row = {"event_id": eid, "event_type": "view" if eid == "e1" else "click",
               "amount": "1.5" if eid == "e1" else None, "user_id": "1" if eid == "e1" else None,
               "_topic": "t", "_partition": "0", "_offset": str(offset)}
        row.update(extra)
        return row

    def rejection(self, offset):
        return {"dt": "2026-10-15", "reason": "WRONG_TYPE", "field": "user_id", "detected_at_ms": "1",
                "_topic": "t", "_partition": "0", "_offset": str(offset)}

    def test_every_offset_once_and_every_event_as_sent(self):
        ok = self.run_judge([self.event("e1", 0, extra_json='{"campaign":"autumn"}'), self.event("e2", 2)],
                            [self.rejection(1)], ["t:0:3"])
        self.assertEqual(ok.returncode, 0, ok.stdout)

    def test_a_gap_or_a_record_read_twice_fails(self):
        gap = self.run_judge([self.event("e1", 0, campaign="autumn"), self.event("e2", 3)],
                             [self.rejection(1)], ["t:0:4"])
        self.assertEqual(json.loads(gap.stdout)["gaps"], 1)
        twice = self.run_judge([self.event("e1", 0, campaign="autumn"), self.event("e2", 1)],
                               [self.rejection(1)], ["t:0:2"])
        self.assertEqual(json.loads(twice.stdout)["twice"], 1)
        self.assertNotEqual(twice.returncode, 0)

    def test_a_value_sent_as_null_must_land_as_null(self):
        filled = self.run_judge([self.event("e1", 0, campaign="autumn"), self.event("e2", 2, user_id="7")],
                                [self.rejection(1)], ["t:0:3"])
        self.assertNotEqual(filled.returncode, 0)
        self.assertEqual(json.loads(filled.stdout)["samples"]["problems"][0]["field"], "user_id")

    def test_a_rejection_with_more_columns_fails_without_showing_them(self):
        leaked = self.run_judge([self.event("e1", 0, campaign="autumn"), self.event("e2", 2)],
                                [{**self.rejection(1), "payload": "secret"}], ["t:0:3"])
        self.assertNotEqual(leaked.returncode, 0)
        self.assertNotIn("secret", leaked.stdout)
        self.assertIn("payload", leaked.stdout)


if __name__ == "__main__":
    unittest.main()
