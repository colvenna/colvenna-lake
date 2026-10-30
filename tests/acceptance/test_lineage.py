import io
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.parse
from contextlib import redirect_stdout
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "lineage"))
import lineage  # noqa: E402

PAIMON = "paimon://colvenna-catalog.colvenna-catalog.svc:8181"
URL = "https://kubernetes.default.svc/apis/flink.apache.org/v1beta1/namespaces/colvenna-data/flinkdeployments/"


class Marquez(BaseHTTPRequestHandler):
    """Marquez's API as the checks read it: jobs, their runs and facets, datasets, the graph."""

    routes = {}

    def do_GET(self):
        path = urllib.parse.unquote(self.path.split("?")[0])
        body = self.routes.get(path)
        self.send_response(200 if body is not None else 404)
        self.end_headers()
        self.wfile.write(json.dumps(body if body is not None else {}).encode())

    def log_message(self, *args):
        pass


def ds(name, namespace=PAIMON):
    return {"namespace": namespace, "name": name}


class LineageTest(unittest.TestCase):
    def setUp(self):
        self.server = HTTPServer(("127.0.0.1", 0), Marquez)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"
        self.dir = tempfile.mkdtemp()
        Marquez.routes = {
            "/api/v1/namespaces/colvenna-lake.colvenna-data/jobs/src-cdc": {
                "inputs": [ds("shop.orders", "mysql://db:3306")],
                "outputs": [ds("colvenna.ods.orders")],
                "location": URL + "src-cdc?version=h1",
                "latestRun": {"id": "r1", "state": "RUNNING"},
            },
            "/api/v1/namespaces/colvenna-lake.colvenna-data/jobs/dwd-table": {
                "inputs": [ds("colvenna.ods.orders")],
                "outputs": [ds("colvenna.dwd.orders")],
                "location": URL + "dwd-table?version=h2",
                "latestRun": {"id": "r2", "state": "RUNNING"},
            },
            "/api/v1/jobs/runs/r1/facets": {"facets": {
                "colvennaLake": {"specHash": "h1", "flinkJobId": "j1"},
                "externalQuery": {"externalQueryId": "j1"}}},
            "/api/v1/jobs/runs/r2/facets": {"facets": {
                "colvennaLake": {"specHash": "h2", "flinkJobId": "j-old"},
                "externalQuery": {"externalQueryId": "j-old"}}},
            "/api/v1/lineage": {"graph": [
                {"id": "dataset:mysql://db:3306:shop.orders"},
                {"id": "job:colvenna-lake.colvenna-data:src-cdc"},
                {"id": f"dataset:{PAIMON}:colvenna.ods.orders"},
                {"id": "job:colvenna-lake.colvenna-data:dwd-table"},
                {"id": f"dataset:{PAIMON}:colvenna.dwd.orders"}]},
            f"/api/v1/namespaces/{PAIMON}/datasets/colvenna.ods.orders": {"fields": [
                {"name": "id", "type": "BIGINT NOT NULL"},
                {"name": "tags", "type": '{"type":"ARRAY","element":"STRING"}'}]},
        }

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def write(self, name, value):
        path = os.path.join(self.dir, name)
        with open(path, "w") as f:
            json.dump(value, f)
        return path

    def check(self, *args):
        out = io.StringIO()
        with redirect_stdout(out):
            ok = getattr(lineage, args[0])(self.base, *args[1:])
        return ok, out.getvalue()

    def test_the_path_is_each_hop_and_reaches_the_end(self):
        hops = self.write("hops.json", [
            ["mysql://db:3306 shop.orders", "src-cdc", f"{PAIMON} colvenna.ods.orders"],
            [f"{PAIMON} colvenna.ods.orders", "dwd-table", f"{PAIMON} colvenna.dwd.orders"]])
        ok, _ = self.check("hops", "colvenna-lake.colvenna-data", hops)
        self.assertTrue(ok)
        wrong = self.write("wrong.json", [
            [f"{PAIMON} colvenna.ods.segments", "dwd-table", f"{PAIMON} colvenna.dwd.orders"]])
        ok, out = self.check("hops", "colvenna-lake.colvenna-data", wrong)
        self.assertFalse(ok)
        self.assertIn("dwd-table: ['" + PAIMON + " colvenna.ods.orders']", out)

    def test_a_version_is_the_deployments_hash_and_its_run_its_flink_job(self):
        def fd(name, spec_hash, job):
            return {"metadata": {"name": name, "annotations": {"colvenna-lake.io/spec-hash": spec_hash}},
                    "status": {"jobStatus": {"jobId": job}}}
        ok, _ = self.check("versions", "colvenna-lake.colvenna-data",
                           self.write("fd.json", [fd("src-cdc", "h1", "j1")]))
        self.assertTrue(ok)
        # The deployment runs another Flink job than the run Marquez shows.
        ok, out = self.check("versions", "colvenna-lake.colvenna-data",
                             self.write("fd2.json", [fd("dwd-table", "h2", "j-new")]))
        self.assertFalse(ok)
        self.assertIn("Flink job j-old (deployment j-new)", out)

    def test_the_fields_are_the_catalogs_columns_in_order(self):
        table = {"schema": {"fields": [
            {"id": 0, "name": "id", "type": "BIGINT NOT NULL"},
            {"id": 1, "name": "tags", "type": {"type": "ARRAY", "element": "STRING"}}]}}
        ok, _ = self.check("schema", PAIMON, "colvenna.ods.orders", self.write("t.json", table))
        self.assertTrue(ok)
        table["schema"]["fields"].append({"id": 2, "name": "channel", "type": "STRING"})
        ok, _ = self.check("schema", PAIMON, "colvenna.ods.orders", self.write("t2.json", table))
        self.assertFalse(ok)
        # Marquez answers the fields in no fixed order: the same columns in another order match.
        table["schema"]["fields"] = list(reversed(table["schema"]["fields"][:2]))
        ok, _ = self.check("schema", PAIMON, "colvenna.ods.orders", self.write("t3.json", table))
        self.assertTrue(ok)
        # Another type does not.
        table["schema"]["fields"][0]["type"] = "STRING"
        ok, _ = self.check("schema", PAIMON, "colvenna.ods.orders", self.write("t4.json", table))
        self.assertFalse(ok)

    def test_the_nodes_of_the_graph_from_a_dataset(self):
        ok, out = self.check("nodes", "mysql://db:3306", "shop.orders")
        self.assertTrue(ok)
        self.assertEqual(out.splitlines()[0], "dataset:mysql://db:3306:shop.orders")
        self.assertEqual(len(out.splitlines()), 5)


if __name__ == "__main__":
    unittest.main()
