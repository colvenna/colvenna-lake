"""Superset's API as a signed-in person uses it in the BI acceptance run.

The person's browser (entry.py's, with the cookies its sign-in left) calls the API the pages call:
a CSRF token first, then JSON, with the BI interface as referrer.

usage:
  bi.py api <app-url> <state-dir> <username> <method> <path> [<json-body>]
                                     one call; prints {status, body}
  bi.py sql <app-url> <state-dir> <username> <database-name> <schema> <sql>
                                     SQL Lab; prints {status, rows, error}
  bi.py dataset <app-url> <state-dir> <username> <database-name> <schema> <table>
                                     a dataset on the table (the existing one if there is); its id
  bi.py sql-id <app-url> <state-dir> <username> <database-id> <schema> <sql>
                                     SQL Lab on a database by id, listed for the person or not
  bi.py export <app-url> <state-dir> <username> <client-id>
                                     SQL Lab's CSV export of a query (another's too)
  bi.py chart <app-url> <state-dir> <username> <dataset-id> <column,...> [csv]
                                     the dataset's rows as a chart (or its CSV export) gets them
  bi.py save <app-url> <state-dir> <username> <dataset-id> <column,...> <title>
                                     a saved table chart on a published dashboard; their ids
  bi.py saved <app-url> <state-dir> <username> <dashboard-id>
                                     each chart of the dashboard and the rows it shows the person
"""

import json
import os
import sys
import urllib.parse

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "entry"))
import entry  # noqa: E402


class Superset:
    def __init__(self, app_url, state, username):
        self.base = app_url.rstrip("/")
        self.browser = entry.Browser({urllib.parse.urlsplit(app_url).hostname},
                                     os.path.join(state, username + ".cookies"))
        self.token = None

    def call(self, method, path, body=None):
        headers = {"Accept": "application/json", "Referer": self.base + "/sqllab/"}
        data = None
        if method != "GET":
            if self.token is None:
                status, _, text = self.browser.request(self.base + "/api/v1/security/csrf_token/",
                                                       headers=headers)
                self.token = json.loads(text)["result"] if status == 200 else ""
            headers.update({"Content-Type": "application/json", "X-CSRFToken": self.token})
            data = json.dumps(body if body is not None else {}).encode()
        status, _, text = self._send(method, path, data, headers)
        try:
            return status, json.loads(text)
        except ValueError:
            return status, text[:2000]

    def _send(self, method, path, data, headers):
        import urllib.request

        req = urllib.request.Request(self.base + path, data=data, method=method,
                                     headers={"User-Agent": entry.AGENT, **headers})
        try:
            resp = self.browser.opener.open(req, timeout=120)
            return resp.status, dict(resp.headers), resp.read().decode(errors="replace")
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read().decode(errors="replace")

    def database_id(self, name):
        q = urllib.parse.quote("(filters:!((col:database_name,opr:eq,value:'%s')))" % name)
        status, body = self.call("GET", "/api/v1/database/?q=" + q)
        if status != 200 or not body.get("ids"):
            return None
        return body["ids"][0]

    def sql(self, database, schema, sql):
        db = self.database_id(database)
        if db is None:
            return {"status": 404, "rows": None, "error": "no database %s" % database}
        return self.sql_on(db, schema, sql)

    def sql_on(self, db, schema, sql):
        client_id = os.urandom(5).hex()
        status, body = self.call("POST", "/api/v1/sqllab/execute/", {
            "database_id": int(db), "schema": schema, "sql": sql, "runAsync": False,
            "queryLimit": 1000, "client_id": client_id, "tab": "acceptance",
            "select_as_cta": False, "ctas_method": "TABLE", "templateParams": "{}",
            "expand_data": False})
        if status == 200 and isinstance(body, dict) and "data" in body:
            columns = [c["column_name"] for c in body.get("columns", [])]
            return {"status": status, "columns": columns, "client_id": client_id,
                    "rows": [[r.get(c) for c in columns] for r in body["data"]], "error": None}
        return {"status": status, "rows": None, "client_id": client_id, "error": json.dumps(body)[:1500]}

    def export(self, client_id):
        status, body = self.call("GET", "/api/v1/sqllab/export/%s/" % client_id)
        return {"status": status, "csv": body if isinstance(body, str) else None,
                "error": None if isinstance(body, str) else json.dumps(body)[:1500]}

    def dataset(self, database, schema, table):
        db = self.database_id(database)
        q = urllib.parse.quote("(filters:!((col:table_name,opr:eq,value:'%s'),(col:schema,opr:eq,value:'%s')))"
                               % (table, schema))
        status, body = self.call("GET", "/api/v1/dataset/?q=" + q)
        if status == 200 and body.get("ids"):
            return {"status": status, "id": body["ids"][0]}
        status, body = self.call("POST", "/api/v1/dataset/", {"database": db, "schema": schema,
                                                             "table_name": table})
        return {"status": status, "id": body.get("id") if isinstance(body, dict) else None,
                "error": None if status in (200, 201) else json.dumps(body)[:1500]}

    def chart(self, dataset, columns, csv=False):
        status, body = self.call("POST", "/api/v1/chart/data", {
            "datasource": {"id": int(dataset), "type": "table"}, "force": True,
            "queries": [{"columns": columns, "metrics": [], "orderby": [[columns[0], True]],
                         "row_limit": 1000}],
            "result_format": "csv" if csv else "json", "result_type": "full"})
        if status == 200 and csv:
            return {"status": status, "csv": body if isinstance(body, str) else json.dumps(body)}
        if status == 200 and isinstance(body, dict):
            data = body["result"][0]["data"]
            return {"status": status, "rows": [[r.get(c) for c in columns] for r in data]}
        return {"status": status, "error": json.dumps(body)[:1500] if not isinstance(body, str) else body[:1500]}


    def save(self, dataset, columns, title):
        params = {"viz_type": "table", "datasource": "%s__table" % dataset, "query_mode": "raw",
                  "all_columns": columns, "row_limit": 1000}
        context = {"datasource": {"id": int(dataset), "type": "table"}, "force": False,
                   "queries": [{"columns": columns, "metrics": [], "orderby": [[columns[0], True]],
                                "row_limit": 1000}],
                   "form_data": params, "result_format": "json", "result_type": "full"}
        status, dash = self.call("POST", "/api/v1/dashboard/", {"dashboard_title": title, "published": True})
        if status not in (200, 201):
            return {"status": status, "error": json.dumps(dash)[:1500]}
        status, chart = self.call("POST", "/api/v1/chart/", {
            "slice_name": title, "viz_type": "table", "datasource_id": int(dataset),
            "datasource_type": "table", "params": json.dumps(params), "query_context": json.dumps(context),
            "dashboards": [dash["id"]]})
        if status not in (200, 201):
            return {"status": status, "error": json.dumps(chart)[:1500]}
        return {"status": status, "dashboard": dash["id"], "chart": chart["id"]}

    def saved(self, dashboard):
        status, body = self.call("GET", "/api/v1/dashboard/%s/charts" % dashboard)
        if status != 200:
            return {"status": status, "error": json.dumps(body)[:1500]}
        charts = []
        for c in body["result"]:
            # As the dashboard asks for it: the chart's saved query, not forced past a cache.
            st, data = self.call("GET", "/api/v1/chart/%s/data/" % c["id"])
            rows = data["result"][0]["data"] if st == 200 and isinstance(data, dict) else None
            charts.append({"id": c["id"], "status": st, "rows": rows,
                           "error": None if st == 200 else json.dumps(data)[:600]})
        return {"status": status, "charts": charts}


def main(argv):
    if len(argv) < 5:
        sys.exit(__doc__)
    cmd, url, state, user, rest = argv[1], argv[2], argv[3], argv[4], argv[5:]
    s = Superset(url, state, user)
    if cmd == "api":
        status, body = s.call(rest[0], rest[1], json.loads(rest[2]) if len(rest) > 2 else None)
        print(json.dumps({"status": status, "body": body}))
    elif cmd == "sql":
        print(json.dumps(s.sql(*rest[:3])))
    elif cmd == "dataset":
        print(json.dumps(s.dataset(*rest[:3])))
    elif cmd == "sql-id":
        print(json.dumps(s.sql_on(*rest[:3])))
    elif cmd == "export":
        print(json.dumps(s.export(rest[0])))
    elif cmd == "chart":
        print(json.dumps(s.chart(rest[0], rest[1].split(","), csv=len(rest) > 2 and rest[2] == "csv")))
    elif cmd == "save":
        print(json.dumps(s.save(rest[0], rest[1].split(","), rest[2])))
    elif cmd == "saved":
        print(json.dumps(s.saved(rest[0])))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
