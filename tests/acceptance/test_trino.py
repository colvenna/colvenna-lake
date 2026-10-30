import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "trino"))
import trino  # noqa: E402


class TrinoTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def write(self, name, text):
        path = os.path.join(self.dir, name)
        with open(path, "w") as f:
            f.write(text)
        return path

    def judge(self, *argv):
        out = io.StringIO()
        with redirect_stdout(out):
            code = trino.main(list(argv))
        return code, json.loads(out.getvalue())

    def test_trinos_rows_are_the_lakes(self):
        lake = self.write("lake.jsonl", "\n".join([
            json.dumps({"type": "TABLE", "table": "ods.clicks", "fields": []}),
            json.dumps({"type": "ROW", "table": "ods.clicks", "kind": "+I",
                        "row": {"event_id": "b1-0", "user_id": 1, "dt": "2026-10-17"}}),
            json.dumps({"type": "ROW", "table": "ods.clicks", "kind": "+I",
                        "row": {"event_id": "b1-1", "user_id": None, "dt": "2026-10-17"}})]))
        same = self.write("same.csv", "b1-1,\nb1-0,1\n")
        self.assertEqual(self.judge("same", same, lake, "event_id", "user_id")[0], 0)
        other = self.write("other.csv", "b1-0,1\nb1-9,2\n")
        code, verdict = self.judge("same", other, lake, "event_id", "user_id")
        self.assertEqual(code, 1)
        self.assertEqual(verdict["only_trino"], [["b1-9", "2"]])
        self.assertEqual(verdict["only_lake"], [["b1-1", ""]])

    def test_a_refusal_is_a_failure_saying_why(self):
        refused = self.write("refused.csv", "ERROR Query failed: Forbidden: principal may not read\n")
        self.assertEqual(self.judge("failed", refused, "forbidden")[0], 0)
        self.assertEqual(self.judge("failed", refused, "unauthorized")[0], 1)
        # Any failure, when the text is empty; an answer is not one.
        self.assertEqual(self.judge("failed", refused, "")[0], 0)
        # The query id is not the error: its digits do not match a status.
        unrelated = self.write("unrelated.csv", "ERROR Query 20261017_114034_00401_x failed: Syntax error\n")
        self.assertEqual(self.judge("failed", unrelated, "401")[0], 1)
        answered = self.write("answered.csv", "40\n")
        self.assertEqual(self.judge("failed", answered, "")[0], 1)
        self.assertEqual(self.judge("equal", answered, "40")[0], 0)
        self.assertEqual(self.judge("equal", answered, "41")[0], 1)


if __name__ == "__main__":
    unittest.main()
