#!/usr/bin/env python3
"""Reads the lineage Marquez shows back, for the lineage acceptance; each check prints what it saw
and exits 0 when it holds.

  lineage.py hops <marquez> <job-namespace> <hops.json>
      each hop [input, job, output] (datasets as "<namespace> <name>") is a current input and
      output of that job, and the graph from the first hop's input reaches the last's output
  lineage.py versions <marquez> <job-namespace> <deployments.json>
      each FlinkDeployment's job: its current version is at the deployment's spec hash, its latest
      run is RUNNING and is the deployment's Flink job
  lineage.py schema <marquez> <dataset-namespace> <name> <catalog-table.json>
      the dataset's fields are colvenna-catalog's columns, names and types (Marquez keeps no column
      order: it answers them in no fixed order)
  lineage.py nodes <marquez> <dataset-namespace> <name>
      prints the lineage graph's nodes from that dataset, sorted
  lineage.py runs <marquez> <job-namespace> <job>
      prints the job's runs (state and spec hash), newest first; exits 1 if it has none
  lineage.py jobs <marquez> <job-namespace>
      prints the namespace's job names
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request


def load(path):
    with open(path) as f:
        return json.load(f)


def get(base, path):
    try:
        with urllib.request.urlopen(base + path, timeout=20) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def encode(value):
    return urllib.parse.quote(value, safe="")


def job(base, ns, name):
    return get(base, f"/api/v1/namespaces/{encode(ns)}/jobs/{encode(name)}")


def dataset_id(d):
    return f"{d['namespace']} {d['name']}"


def hops(base, ns, path):
    chain = load(path)
    ok = True
    seen = []
    for source, name, target in chain:
        j = job(base, ns, name)
        if j is None:
            seen.append(f"{name}: no such job")
            ok = False
            continue
        inputs = {dataset_id(d) for d in j["inputs"]}
        outputs = {dataset_id(d) for d in j["outputs"]}
        if source not in inputs or target not in outputs:
            ok = False
            seen.append(f"{name}: {sorted(inputs)} -> {sorted(outputs)}")
        else:
            seen.append(f"{source} -> {name} -> {target}")
    start = chain[0][0].split(" ", 1)
    end = "dataset:" + chain[-1][2].replace(" ", ":", 1)
    graph = get(base, "/api/v1/lineage?depth=20&nodeId=" + encode(f"dataset:{start[0]}:{start[1]}"))
    nodes = {n["id"] for n in (graph or {}).get("graph", [])}
    if end not in nodes:
        ok = False
        seen.append(f"the graph from {chain[0][0]} does not reach {chain[-1][2]}")
    print("; ".join(seen))
    return ok


def run_facets(base, run_id):
    return get(base, f"/api/v1/jobs/runs/{encode(run_id)}/facets?type=run") or {}


def versions(base, ns, path):
    ok = True
    seen = []
    for fd in load(path):
        name = fd["metadata"]["name"]
        spec_hash = fd["metadata"]["annotations"]["colvenna-lake.io/spec-hash"]
        flink_job = ((fd.get("status") or {}).get("jobStatus") or {}).get("jobId")
        j = job(base, ns, name)
        if j is None:
            ok = False
            seen.append(f"{name}: no such job")
            continue
        run = j.get("latestRun") or {}
        facets = run_facets(base, run["id"]).get("facets", {}) if run.get("id") else {}
        colvenna = facets.get("colvennaLake", {})
        good = (
            (j.get("location") or "").endswith("?version=" + spec_hash)
            and run.get("state") == "RUNNING"
            and colvenna.get("specHash") == spec_hash
            and colvenna.get("flinkJobId") == flink_job
            and facets.get("externalQuery", {}).get("externalQueryId") == flink_job
        )
        ok = ok and good
        seen.append(
            f"{name}: version {j.get('location', '').rsplit('=', 1)[-1]} (deployment {spec_hash}),"
            f" run {run.get('state')} of Flink job {colvenna.get('flinkJobId')} (deployment {flink_job})"
        )
    print("; ".join(seen))
    return ok


def schema(base, namespace, name, path):
    table = load(path)
    expected = []
    for f in table["schema"]["fields"]:
        t = f["type"]
        expected.append([f["name"], t if isinstance(t, str) else json.dumps(t, separators=(",", ":"))])
    d = get(base, f"/api/v1/namespaces/{encode(namespace)}/datasets/{encode(name)}")
    if d is None:
        print(f"{name}: no such dataset")
        return False
    shown = [[f["name"], f["type"]] for f in d["fields"]]
    print(json.dumps({"marquez": shown, "catalog": expected}))
    return sorted(shown) == sorted(expected)


def nodes(base, namespace, name):
    graph = get(base, "/api/v1/lineage?depth=20&nodeId=" + encode(f"dataset:{namespace}:{name}"))
    print("\n".join(sorted(n["id"] for n in (graph or {}).get("graph", []))))
    return True


def runs(base, ns, name):
    r = get(base, f"/api/v1/namespaces/{encode(ns)}/jobs/{encode(name)}/runs?limit=100")
    out = []
    for run in (r or {}).get("runs", []):
        facets = run_facets(base, run["id"]).get("facets", {})
        out.append(f"{run['state']} {facets.get('colvennaLake', {}).get('specHash')}")
    print(", ".join(out))
    return bool(out)


def jobs(base, ns):
    r = get(base, f"/api/v1/namespaces/{encode(ns)}/jobs?limit=200")
    print("\n".join(sorted(j["name"] for j in (r or {}).get("jobs", []))))
    return True


def main():
    command, base, *args = sys.argv[1:]
    checks = {"hops": hops, "versions": versions, "schema": schema, "runs": runs, "jobs": jobs,
              "nodes": nodes}
    sys.exit(0 if checks[command](base, *args) else 1)


if __name__ == "__main__":
    main()
