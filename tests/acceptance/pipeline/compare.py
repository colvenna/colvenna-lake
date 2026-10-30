#!/usr/bin/env python3
"""Compares the console's pipeline (GET /api/pipeline) with what kubectl, Prometheus and Marquez
say, for the pipeline acceptance run. Each check prints what it saw and exits 0 when it holds.

  compare.py path <console.json> <edges.json>
      every expected edge [from, to] is in the console's flow
  compare.py state <console.json> <kubectl.json> <namespace>
      each declaration's phase, its jobs and their Flink state, a table job's lag behind each
      upstream, a dashboard's queries and each task's phase, as kubectl reads them (kubectl.json:
      {"sources": [...], "tables": [...], "dashboards": [...], "tasks": [...], "deployments": [...]})
  compare.py measures <console.json> <prometheus-url> <node-id>...
      each measure of those nodes is what Prometheus answers at the console's moment
  compare.py alerts <console.json> <alerts.json> <node-id>...
      the alerts on those nodes are Prometheus's (alerts.json: GET /api/v1/alerts)
  compare.py lineage <console.json> <marquez-url> <marquez-namespace> <lake-namespace> <lineage.json>
      the flow is Marquez's (lineage.json: {"<node id>": [<Marquez job>...]}); the lake's tables are
      the datasets of <lake-namespace> (paimon://<colvenna-catalog>)
  compare.py located <console.json> <problem-id> <alertname> <downstream-id,...> <upstream-id,...>
      the problem is there, for that alert; downstream is affected by it (whatever else it has);
      upstream is neither a problem nor affected
  compare.py cleared <console.json> <node-id> <alertname>
      the node no longer gives that alert as why; with no other firing alert and a sound phase it
      is no problem, and nothing is affected by it
  compare.py secrets <values-file> <page>...
      no value (one line each: base64 of the value, a tab, where it is from; at least 8
      characters) is in any page, as is or escaped; a match is named by where it is from
"""
import base64
import html
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

# The console's measures (colvenna-console/internal/pipeline/model.go): name, PromQL and the node each
# series belongs to.
MEASURES = [
    ("sourceLatencySeconds", "colvenna_source_latency_seconds", "source"),
    ("sourceFreshnessSeconds", "colvenna_source_freshness_seconds", "source"),
    ("kafkaLag", "sum by (namespace, source) (colvenna_source_kafka_lag)", "source"),
    ("upstreamLagSeconds", "colvenna_table_upstream_lag_seconds", "job"),
    ("layerLatencySeconds", "colvenna_table_layer_latency_seconds", "job"),
    ("quarantinedRows", "sum by (namespace, laketable) (colvenna_table_quarantined_rows)", "job"),
    ("secondsSinceCheckpoint", "time() - colvenna_job_last_checkpoint_timestamp_seconds", "job-or-source"),
    ("freshnessSeconds", "colvenna_table_freshness_seconds", "table"),
    ("level0Files", "colvenna_table_level0_files", "table"),
    ("smallFiles", "colvenna_table_small_files", "table"),
    ("snapshots", "colvenna_table_snapshots", "table"),
    ("copyAgeSeconds", "time() - colvenna_dashboard_refreshed_timestamp_seconds", "dashboard"),
]
EXPRESSIONS = {name: expr for name, expr, _ in MEASURES}


def load(path):
    with open(path) as f:
        return json.load(f)


def get(url):
    try:
        with urllib.request.urlopen(url, timeout=20) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def nodes(console):
    return {n["id"]: n for n in console["nodes"]}


def node_of(by, labels):
    """The node a series belongs to: by its measure's kind of node (source, job, job-or-source,
    table, dashboard), as model.go's measures place them."""
    ns = labels.get("namespace", "")
    if by == "source" and labels.get("source"):
        return f"source:{ns}/{labels['source']}"
    if by == "job" and labels.get("laketable"):
        return f"job:{ns}/{labels['laketable']}"
    if by == "job-or-source":
        return node_of("job", labels) or node_of("source", labels)
    if by == "table" and labels.get("table"):
        return f"table:{labels['table']}"
    if by == "dashboard" and labels.get("lakedashboard"):
        return f"dashboard:{ns}/{labels['lakedashboard']}"
    return None


def alert_node(labels):
    """The node an alert belongs to, as model.go's owner() places it."""
    for by in ("job", "source", "dashboard", "table"):
        n = node_of(by, labels)
        if n:
            return n
    return None


def path(console_path, edges_path):
    have = {(e["from"], e["to"]) for e in load(console_path)["edges"]}
    missing = [e for e in load(edges_path) if tuple(e) not in have]
    print(f"{len(have)} edges; missing: {missing}")
    return not missing


def state(console_path, kubectl_path, namespace):
    shown, k = nodes(load(console_path)), load(kubectl_path)
    flink = {d["metadata"]["name"]: d.get("status") or {} for d in k["deployments"]}
    seen, bad = [], []

    def same(nid, what, console_value, kubectl_value):
        if console_value != kubectl_value:
            bad.append(f"{nid} {what}: console {console_value}, kubectl {kubectl_value}")
            return False
        return True

    for kind, items in (("source", k["sources"]), ("job", k["tables"]), ("dashboard", k["dashboards"])):
        for o in items:
            nid = f"{kind}:{namespace}/{o['metadata']['name']}"
            st = o.get("status") or {}
            n = shown.get(nid) or {}
            ok = same(nid, "phase", n.get("phase", ""), st.get("phase", ""))
            # Its jobs: the status's, each with its deployment's state.
            jobs = st.get("jobs") or ([st["job"]] if st.get("job") else [])
            deployed = [flink.get(j.get("deployment"), {}) for j in jobs]
            want = [((d.get("jobStatus") or {}).get("state", ""), d.get("lifecycleState", ""), j.get("deployment"))
                    for j, d in zip(jobs, deployed)]
            got = [(j.get("state", ""), j.get("lifecycle", ""), j.get("deployment")) for j in n.get("jobs") or []]
            ok = same(nid, "jobs", got, want) and ok
            if kind == "job":
                want = [(u.get("table"), u.get("lag"), u.get("lagSeconds")) for u in st.get("upstream") or []]
                got = [(u.get("table"), u.get("lagSnapshots"), u.get("lagSeconds")) for u in n.get("upstream") or []]
                ok = same(nid, "upstream", got, want) and ok
            if kind == "dashboard":
                want = [(q.get("name"), q.get("serve"), q.get("refreshedAt")) for q in st.get("queries") or []]
                got = [(q.get("name"), q.get("serve"), q.get("refreshedAt")) for q in n.get("queries") or []]
                ok = same(nid, "queries", got, want) and ok
            if ok:
                seen.append(f"{nid} {st.get('phase', '')}")
    for t in k.get("tasks", []):
        st, spec = t.get("status") or {}, t["spec"]
        table = st.get("table") or f"{spec['table']['database']}.{spec['table']['table']}"
        tasks = {x["name"]: x for x in (shown.get("table:" + table) or {}).get("tasks") or []}
        name = f"{t['metadata']['namespace']}/{t['metadata']['name']}"
        if same("table:" + table, "task " + name, (tasks.get(name) or {}).get("phase"), st.get("phase")):
            seen.append(f"table:{table} task {name} {st.get('phase')}")
    print("; ".join(bad + seen))
    return bool(seen) and not bad


def prometheus(base, expr, at):
    q = urllib.parse.urlencode({"query": expr, "time": at})
    body = get(f"{base}/api/v1/query?{q}")
    return [(r["metric"], float(r["value"][1])) for r in body["data"]["result"]]


def measures(console_path, base, *ids):
    console = load(console_path)
    shown = nodes(console)
    want = {i: {} for i in ids}
    for name, expr, by in MEASURES:
        for labels, value in prometheus(base, expr, console["at"]):
            n = node_of(by, labels)
            if n in want:
                want[n][name] = max(value, want[n].get(name, value))
    bad, count = [], 0
    for i in ids:
        got = (shown.get(i) or {}).get("measures") or {}
        for name in sorted(set(got) | set(want[i])):
            a, b = got.get(name), want[i].get(name)
            # time() is evaluated when each query is made: within a second of the console's.
            slack = 2.0 if "time()" in EXPRESSIONS.get(name, "") else 1e-6 * max(1.0, abs(b or 0))
            count += 1
            if a is None or b is None or abs(a - b) > slack:
                bad.append(f"{i} {name}: console {a}, Prometheus {b}")
    print(f"{count} measures of {len(ids)} nodes at {console['at']}; differ: {bad}")
    return count > 0 and not bad


def alerts(console_path, alerts_path, *ids):
    shown = nodes(load(console_path))
    want = set()
    for a in load(alerts_path)["data"]["alerts"]:
        n = alert_node(a["labels"])
        if n in ids:
            want.add((n, a["labels"]["alertname"], a["state"]))
    got = {(i, a["name"], a["state"]) for i in ids for a in (shown.get(i) or {}).get("alerts") or []}
    print(f"console {sorted(got)}; Prometheus {sorted(want)}")
    return got == want


def lineage(console_path, base, namespace, lake, mapping_path):
    edges = load(console_path)["edges"]
    into, out = {}, {}
    for e in edges:
        into.setdefault(e["to"], set()).add(e["from"])
        out.setdefault(e["from"], set()).add(e["to"])
    seen, bad = [], []
    for node, jobs in load(mapping_path).items():
        ins, outs, external = set(), set(), set()
        for name in jobs:
            j = get(f"{base}/api/v1/namespaces/{urllib.parse.quote(namespace, safe='')}/jobs/{urllib.parse.quote(name, safe='')}")
            if j is None:
                bad.append(f"{name}: no such job in Marquez")
                continue
            for d, into_set in [(d, ins) for d in j["inputs"]] + [(d, outs) for d in j["outputs"]]:
                if d["namespace"] == lake:
                    into_set.add("table:" + d["name"].split(".", 1)[1])
                else:
                    external.add(d["namespace"].split(":", 1)[0] + " " + d["name"])
        console_in = into.get(node, set())
        console_out = {o for o in out.get(node, set()) if o.startswith("table:")}
        if node.startswith("source:"):
            # Marquez has the source's tables as datasets of the source system; the console, the
            # source itself: the tables it writes are the same names.
            source_tables = {x.rsplit(".", 1)[-1] for x in external}
            written = {o.rsplit(".", 1)[-1] for o in console_out}
            ok = outs == console_out and source_tables == written
        else:
            ok = ins == console_in and outs == console_out
        (seen if ok else bad).append(
            f"{node}: Marquez {sorted(ins)} -> {sorted(outs)} {sorted(external)}; console {sorted(console_in)} -> {sorted(console_out)}")
    print("; ".join(bad + seen))
    return bool(seen) and not bad


def located(console_path, problem, alertname, downstream, upstream):
    shown = nodes(load(console_path))
    p = shown.get(problem) or {}
    bad = []
    if not p.get("problem") or f"alert {alertname}" not in (p.get("why") or []):
        bad.append(f"{problem}: problem {p.get('problem')}, why {p.get('why')}")
    for i in filter(None, downstream.split(",")):
        n = shown.get(i) or {}
        if problem not in (n.get("affectedBy") or []) or not (n.get("affected") or n.get("problem")):
            bad.append(f"{i} downstream: affected by {n.get('affectedBy')}")
    for i in filter(None, upstream.split(",")):
        n = shown.get(i) or {}
        if n.get("affected") or n.get("problem"):
            bad.append(f"{i} upstream: affected {n.get('affectedBy')}, problem {n.get('problem')} {n.get('why')}")
    marked = sorted(f"{i}{' (problem)' if n['problem'] else ''}" for i, n in shown.items() if n["problem"] or n["affected"])
    print(f"{problem}: {p.get('why')}; marked {marked}; wrong: {bad}")
    return not bad


def cleared(console_path, node, alertname):
    shown = nodes(load(console_path))
    n = shown.get(node) or {}
    why = n.get("why") or []
    firing = [a["name"] for a in n.get("alerts") or [] if a["state"] == "firing"]
    sound = n.get("phase") not in ("Degraded", "Failed")
    by_it = sorted(i for i, m in shown.items() if node in (m.get("affectedBy") or []))
    ok = not any(w.startswith(f"alert {alertname}") for w in why)
    if not firing and sound:
        ok = ok and not n.get("problem") and not by_it
    print(f"{node}: problem {n.get('problem')}, why {why}, firing {firing}, affecting {by_it}")
    return ok


def escaped(value):
    """The forms a value takes in the console's pages: as is, in JSON (Go escapes <, > and &) and
    in HTML."""
    as_json = json.dumps(value)[1:-1]
    go = as_json.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    return {value, as_json, go, html.escape(value), html.escape(value, quote=False)}


def secrets(values_path, *pages):
    values = {}
    with open(values_path) as f:
        for line in f:
            if line.strip():
                raw, _, where = line.rstrip("\n").partition("\t")
                v = base64.b64decode(raw).decode("utf-8", "replace")
                if len(v) >= 8:
                    values.setdefault(v, where)
    texts = []
    for p in pages:
        with open(p, encoding="utf-8", errors="replace") as f:
            texts.append(f.read())
    found = sorted({where for v, where in values.items() if any(form in t for form in escaped(v) for t in texts)})
    # Only where a value is from: the values themselves are not printed.
    print(f"{len(values)} values, {len(pages)} pages, {sum(map(len, texts))} characters; found: {found}")
    return bool(values) and bool(pages) and not found


def main():
    command, *args = sys.argv[1:]
    checks = {"path": path, "state": state, "measures": measures, "alerts": alerts,
              "lineage": lineage, "located": located, "cleared": cleared, "secrets": secrets}
    sys.exit(0 if checks[command](*args) else 1)


if __name__ == "__main__":
    main()
