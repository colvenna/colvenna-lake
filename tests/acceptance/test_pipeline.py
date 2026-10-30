import base64
import contextlib
import io
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "pipeline"))
import compare  # noqa: E402


def node(i, **kw):
    return {"id": i, "problem": False, "affected": False, "why": [], "alerts": [], "measures": {}, **kw}


class CompareTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def write(self, name, value):
        path = os.path.join(self.dir, name)
        with open(path, "w") as f:
            f.write(value if isinstance(value, str) else json.dumps(value))
        return path

    def quiet(self, check, *args):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            ok = check(*args)
        return ok, out.getvalue()

    def test_located_needs_the_problem_there_downstream_affected_by_it_and_upstream_clean(self):
        console = {"nodes": [node("source:n/s"), node("job:n/d", problem=True, why=["alert ColvennaLayerLagP99"]),
                             node("table:d.t", affected=True, affectedBy=["job:n/d"]),
                             node("job:n/e", problem=True, why=["alert ColvennaRowsQuarantined"], affectedBy=["job:n/d"]),
                             node("table:e.t", affected=True, affectedBy=["job:n/e"])], "edges": []}
        path = self.write("c.json", console)
        self.assertTrue(self.quiet(compare.located, path, "job:n/d", "ColvennaLayerLagP99", "table:d.t,job:n/e", "source:n/s")[0])
        # Another alert's problem, a downstream affected by something else, a marked upstream.
        self.assertFalse(self.quiet(compare.located, path, "job:n/d", "ColvennaSourceStale", "table:d.t", "")[0])
        self.assertFalse(self.quiet(compare.located, path, "job:n/d", "ColvennaLayerLagP99", "table:e.t", "")[0])
        self.assertFalse(self.quiet(compare.located, path, "job:n/d", "ColvennaLayerLagP99", "source:n/s", "")[0])
        self.assertFalse(self.quiet(compare.located, path, "table:d.t", "ColvennaLayerLagP99", "", "job:n/d")[0])

    def test_cleared_is_no_longer_that_alert_and_no_mark_without_a_cause(self):
        firing = [{"name": "ColvennaRowsQuarantined", "state": "firing"}]
        still = {"nodes": [node("job:n/d", problem=True, why=["alert ColvennaRowsQuarantined"], alerts=firing)]}
        self.assertTrue(self.quiet(compare.cleared, self.write("c.json", still), "job:n/d", "ColvennaLayerLagP99")[0])
        self.assertFalse(self.quiet(compare.cleared, self.write("c.json", still), "job:n/d", "ColvennaRowsQuarantined")[0])
        # Nothing firing any more, yet still a problem, or still affecting what is downstream.
        stuck = {"nodes": [node("job:n/d", problem=True, why=[])]}
        self.assertFalse(self.quiet(compare.cleared, self.write("c.json", stuck), "job:n/d", "ColvennaLayerLagP99")[0])
        lingering = {"nodes": [node("job:n/d"), node("table:d.t", affected=True, affectedBy=["job:n/d"])]}
        self.assertFalse(self.quiet(compare.cleared, self.write("c.json", lingering), "job:n/d", "ColvennaLayerLagP99")[0])
        gone = {"nodes": [node("job:n/d"), node("table:d.t")]}
        self.assertTrue(self.quiet(compare.cleared, self.write("c.json", gone), "job:n/d", "ColvennaLayerLagP99")[0])

    def test_state_is_the_status_down_to_lag_queries_and_tasks(self):
        console = {"nodes": [
            node("job:n/d", phase="Running", jobs=[{"deployment": "d-table", "state": "RUNNING", "lifecycle": "STABLE"}],
                 upstream=[{"table": "o.t", "lagSnapshots": 3.0, "lagSeconds": 40.0}]),
            node("table:d.t", tasks=[{"name": "n/tag-1", "phase": "Succeeded"}])]}
        kubectl = {"sources": [], "dashboards": [],
                   "tables": [{"metadata": {"name": "d"}, "status": {"phase": "Running", "job": {"deployment": "d-table"},
                                                                     "upstream": [{"table": "o.t", "lag": 3, "lagSeconds": 40}]}}],
                   "tasks": [{"metadata": {"namespace": "n", "name": "tag-1"}, "spec": {"table": {"database": "d", "table": "t"}},
                              "status": {"phase": "Succeeded"}}],
                   "deployments": [{"metadata": {"name": "d-table"}, "status": {"lifecycleState": "STABLE", "jobStatus": {"state": "RUNNING"}}}]}
        ok, _ = self.quiet(compare.state, self.write("c.json", console), self.write("k.json", kubectl), "n")
        self.assertTrue(ok)
        kubectl["tables"][0]["status"]["upstream"][0]["lag"] = 4
        ok, out = self.quiet(compare.state, self.write("c.json", console), self.write("k.json", kubectl), "n")
        self.assertFalse(ok)
        self.assertIn("upstream", out)

    def test_alerts_go_to_the_nodes_the_console_gives_them(self):
        console = {"nodes": [node("job:n/d", alerts=[{"name": "ColvennaLayerLagP99", "state": "firing"}]), node("table:d.t")]}
        alerts = {"data": {"alerts": [
            {"labels": {"alertname": "ColvennaLayerLagP99", "namespace": "n", "laketable": "d", "table": "d.t"}, "state": "firing"},
            {"labels": {"alertname": "ColvennaSnapshotsPiling", "table": "x.y"}, "state": "pending"}]}}
        ok, _ = self.quiet(compare.alerts, self.write("c.json", console), self.write("a.json", alerts), "job:n/d", "table:d.t")
        self.assertTrue(ok)
        alerts["data"]["alerts"][0]["state"] = "pending"
        ok, _ = self.quiet(compare.alerts, self.write("c.json", console), self.write("a.json", alerts), "job:n/d", "table:d.t")
        self.assertFalse(ok)

    def test_secrets_name_where_a_match_is_from_never_the_value(self):
        value = "s3cr<et>&-value"
        values = self.write("v", "".join(base64.b64encode(v.encode()).decode() + "\t" + w + "\n"
                                         for v, w in [(value, "ns/db/password"), ("short", "ns/x/k")]))
        clean = self.write("clean.html", "<p>nothing here</p>")
        # Go's JSON encoder escapes <, > and &.
        leaked = self.write("leaked.json", json.dumps({"m": "x"}) + "s3cr\\u003cet\\u003e\\u0026-value")
        self.assertTrue(self.quiet(compare.secrets, values, clean)[0])
        ok, out = self.quiet(compare.secrets, values, clean, leaked)
        self.assertFalse(ok)
        self.assertIn("ns/db/password", out)
        self.assertNotIn(value, out)


if __name__ == "__main__":
    unittest.main()
