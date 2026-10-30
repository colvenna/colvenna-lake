"""Unit tests of the evidence checkers (cdc/compare.py, status.py)."""
import importlib.util
import json
import os
import tempfile
import unittest
from types import SimpleNamespace

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


compare = load("compare", os.path.join(HERE, "cdc", "compare.py"))
status = load("status", os.path.join(HERE, "status.py"))


def lines(path, items):
    with open(path, "w") as f:
        for item in items:
            f.write(json.dumps(item) + "\n")


def change(kind, id_, version, **extra):
    return {"type": "CHANGE", "table": "ods.t", "kind": kind,
            "row": {"id": str(id_), "version_no": str(version), **extra}}


class CompareTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def path(self, name):
        return os.path.join(self.dir, name)

    def test_rows_compare_values_not_spelling(self):
        lines(self.path("lake"), [
            {"type": "ROW", "table": "ods.t", "row": {"id": "1", "amount": "12.50", "note": None, "dropped": "x"}},
            {"type": "ROW", "table": "ods.t", "row": {"id": "1010", "amount": "3", "note": "a", "dropped": "y"}},
        ])
        with open(self.path("src"), "w") as f:
            f.write("id\tamount\tnote\n1\t12.5\tNULL\n1010\t3.00\ta\n")
        args = SimpleNamespace(lake=self.path("lake"), source=self.path("src"), table="ods.t", key="id")
        self.assertTrue(compare.compare_rows(args)["pass"])
        with open(self.path("src"), "w") as f:
            f.write("id\tamount\tnote\n1\t12.5\tNULL\n1010\t4\ta\n2\t1\tb\n")
        verdict = compare.compare_rows(args)
        self.assertFalse(verdict["pass"])
        self.assertEqual((verdict["missing"], verdict["differing"]), (1, 1))

    def test_dates_times_and_char_padding_compare_by_value(self):
        lines(self.path("lake"), [
            {"type": "TABLE", "table": "ods.t", "fields": [{"name": "id", "type": "BIGINT"},
                                                          {"name": "c", "type": "CHAR(4)"}]},
            {"type": "ROW", "table": "ods.t",
             "row": {"id": "1", "c": "ab  ", "d": "2026-10-15", "ts": "2026-10-15T01:02:03.450"}},
        ])
        with open(self.path("src"), "w") as f:
            f.write("id\tc\td\tts\n1\tab\t2026-10-15\t2026-10-15 01:02:03.45\n")
        args = SimpleNamespace(lake=self.path("lake"), source=self.path("src"), table="ods.t", key="id")
        self.assertTrue(compare.compare_rows(args)["pass"])
        with open(self.path("src"), "w") as f:
            f.write("id\tc\td\tts\n1\tab\t2026-10-15\t2026-10-15 01:02:04\n")
        self.assertFalse(compare.compare_rows(args)["pass"])

    def test_changelog_replay_judges_before_images(self):
        good = [change("+I", 1, 1), change("+I", 2, 1), change("-D", 1, 1), change("+I", 1, 2),
                change("-U", 2, 1), change("+U", 2, 3), change("-D", 2, 3)]
        lines(self.path("cl"), good)
        lines(self.path("rows"), [{"type": "ROW", "table": "ods.t", "row": {"id": "1", "version_no": "2"}}])
        args = SimpleNamespace(lake=self.path("cl"), rows=self.path("rows"), table="ods.t", key="id",
                               version="version_no")
        verdict = compare.compare_changelog(args)
        self.assertTrue(verdict["pass"], verdict)
        # A duplicate insert, a delete with a stale before image, and a version that went back.
        lines(self.path("cl"), [change("+I", 1, 1), change("+I", 1, 1), change("-D", 1, 0),
                                change("+I", 1, 1)])
        verdict = compare.compare_changelog(SimpleNamespace(**{**vars(args), "rows": None}))
        self.assertFalse(verdict["pass"])
        problems = {p["problem"] for p in verdict["samples"]}
        self.assertIn("insert on a present key", problems)
        self.assertIn("-D before image is not the current row", problems)
        self.assertIn("version did not rise", problems)

    def test_an_aggregate_changelog_matches_before_images_by_value(self):
        def agg(kind, count, total):
            return {"type": "CHANGE", "table": "dws.t", "kind": kind,
                    "row": {"segment": "gold", "order_count": str(count), "amount_total": total}}
        lines(self.path("cl"), [agg("+I", 1, "1.50"), agg("-U", 1, "1.5"), agg("+U", 2, "4.00")])
        args = SimpleNamespace(lake=self.path("cl"), rows=None, table="dws.t", key="segment", version="")
        self.assertTrue(compare.compare_changelog(args)["pass"])
        # A retraction of a total the table never held: the aggregate would count twice.
        lines(self.path("cl"), [agg("+I", 1, "1.50"), agg("-U", 2, "3.00"), agg("+U", 3, "4.50")])
        verdict = compare.compare_changelog(args)
        self.assertFalse(verdict["pass"])
        self.assertEqual(verdict["samples"][0]["problem"], "-U before image is not the current row")


class StatusTest(unittest.TestCase):
    def test_expressions_see_tables_conditions_and_live_jobs(self):
        doc = {
            "source": {"status": {"phase": "Running",
                                  "conditions": [{"type": "JobRunning", "status": "True"}],
                                  "tables": [{"name": "orders", "state": "Ready", "reason": "Reconciled",
                                              "sourceRows": 5, "targetRows": 5}]}},
            "deployments": [{"metadata": {"name": "d", "generation": 3},
                             "status": {"observedGeneration": 3, "lifecycleState": "STABLE",
                                        "jobStatus": {"state": "RUNNING"}}}],
        }
        scope = status.scope(doc)
        self.assertTrue(eval("state('orders') == 'Ready' and live_all() and condition('JobRunning') == 'True'",
                             {}, scope))
        doc["deployments"][0]["status"]["observedGeneration"] = 2
        self.assertFalse(status.scope(doc)["live_all"]())

    def test_table_expressions_see_conditions_upstream_lag_and_rollbacks(self):
        doc = {
            "table": {"status": {"phase": "Degraded",
                                 "conditions": [{"type": "SpecApplied", "status": "False", "reason": "RolledBack"}],
                                 "job": {"restoredFrom": "s3://b/savepoint-1", "lifecycle": "STABLE"},
                                 "upstream": [{"table": "ods.orders", "lag": 0}],
                                 "rolledBack": {"hash": "h"}}},
            "deployments": [{"metadata": {"name": "d", "generation": 2},
                             "status": {"observedGeneration": 2, "lifecycleState": "STABLE",
                                        "jobStatus": {"state": "RUNNING"}}}],
        }
        scope = status.table_scope(doc)
        self.assertTrue(eval("why('SpecApplied') == 'RolledBack' and rolled_back and live_all()"
                             " and lag('ods.orders') == 0 and 'savepoint' in restored()", {}, scope))
        # Restored from the savepoint and no checkpoint of its own yet, then one.
        self.assertFalse(scope["checkpointed"])
        doc["table"]["status"]["job"]["lastCheckpointAt"] = "2026-10-26T01:16:40Z"
        self.assertTrue(status.table_scope(doc)["checkpointed"])
        # What an upgrade in flight recorded.
        self.assertFalse(eval("upgrading", {}, scope))
        doc["table"]["status"]["upgrade"] = {"fromCheckpoint": 12, "fromCheckpointsCompleted": 0}
        self.assertTrue(eval("upgrading and upgrade.get('fromCheckpointsCompleted') == 0", {},
                             status.table_scope(doc)))


if __name__ == "__main__":
    unittest.main()
