"""Unit tests of the MongoDB run's judge (mongo/mongo.py)."""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


class RowsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)

    def judge(self, docs, rows):
        source = os.path.join(self.dir, "docs.jsonl")
        with open(source, "w") as f:
            f.writelines(json.dumps(d) + "\n" for d in docs)
        lake = os.path.join(self.dir, "rows.jsonl")
        with open(lake, "w") as f:
            f.writelines(json.dumps({"type": "ROW", "table": "ods.orders", "row": r}) + "\n" for r in rows)
        run = subprocess.run([sys.executable, os.path.join(HERE, "mongo", "mongo.py"), "rows",
                              "--lake", lake, "--source", source, "--table", "ods.orders"],
                             capture_output=True, text=True)
        return run.returncode, json.loads(run.stdout)

    def test_values_compare_by_value_whatever_their_spelling(self):
        docs = [{"_id": {"$oid": "65f0a1"}, "amount": 12.0, "n": {"$numberLong": "7"}, "paid": True,
                 "address": {"city": "Oslo", "zip": 1}, "tags": ["a", "b"], "state": "new"}]
        rows = [{"_id": "65f0a1", "amount": "12", "n": "7", "paid": "true",
                 "address": '{"zip": {"$numberInt": "1"}, "city": "Oslo"}', "tags": '["a","b"]',
                 "state": "new", "channel": None}]
        code, verdict = self.judge(docs, rows)
        self.assertEqual(code, 0, verdict)

    def test_a_missing_document_a_differing_field_or_a_stray_value_fails(self):
        _, missing = self.judge([{"_id": "a"}, {"_id": "b"}], [{"_id": "a"}])
        self.assertEqual(missing["missing"], 1)
        _, differing = self.judge([{"_id": "a", "state": "paid"}], [{"_id": "a", "state": "new"}])
        self.assertEqual(differing["differing"], 1)
        _, stray = self.judge([{"_id": "a"}], [{"_id": "a", "channel": "web"}])
        self.assertEqual(stray["differing"], 1)


if __name__ == "__main__":
    unittest.main()
