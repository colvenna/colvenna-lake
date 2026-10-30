#!/usr/bin/env python3
"""Evaluates a condition on a LakeSource or LakeTable and its FlinkDeployments, for the acceptance
runs.

  status.py check <state.json> '<expression>'    exit 0 when the expression holds
  status.py tables <state.json>                  one line per table: name state reason rows

<state.json> is {"source": <LakeSource>, "deployments": [<FlinkDeployment>...]}. Expressions
see: phase, state(t), reason(t), rows(t) -> (source, target), live(d) (the deployment runs the
spec it was last given), live_all(), condition(type) -> "True"/"False", jobs (deployment names),
restored(d) -> the checkpoint the deployment's running job restored from, tables (names), and
pending_slots (replication slots not dropped yet). For a kafka source also: kafka_reason (null
when the cluster answered), lag (records not consumed, all topics), kafka_topics (how many it
read), accepted, rejected_by(reason); for a Kafka transport, kafka_reason and lag too, and
transport (its job's counters: unparsable, stale, unverified).

For a LakeTable, <state.json> is {"table": <LakeTable>, "deployments": [...]} and expressions see:
phase, condition(type), why(type) -> the condition's reason, live_all(), restored() -> what the
job restored from, validation -> the validation status, lag(upstream) -> snapshots not yet read,
rolled_back (the rolled-back change or None), upgrading (an upgrade in flight), upgrade (what the
upgrade in flight recorded, or {}), checkpointed (the running job has completed a checkpoint of its
own, as last observed), and lifecycle.
"""
import json
import sys


def load(path):
    with open(path) as f:
        return json.load(f)


def scope(doc):
    source = doc["source"]
    status = source.get("status") or {}
    tables = {t["name"]: t for t in status.get("tables", [])}
    deployments = {d["metadata"]["name"]: d for d in doc.get("deployments", [])}

    def live(name):
        d = deployments.get(name)
        if not d or "status" not in d:
            return False
        s = d["status"]
        job = (s.get("jobStatus") or {}).get("state")
        return (job == "RUNNING"
                and s.get("observedGeneration") == d["metadata"].get("generation")
                and s.get("lifecycleState") in ("STABLE", "DEPLOYED"))

    return {
        "phase": status.get("phase"),
        "state": lambda t: tables.get(t, {}).get("state"),
        "reason": lambda t: tables.get(t, {}).get("reason"),
        "rechecking": lambda t: tables.get(t, {}).get("recheckSince") is not None,
        "rows": lambda t: (tables.get(t, {}).get("sourceRows"), tables.get(t, {}).get("targetRows")),
        "live": live,
        "live_all": lambda: bool(deployments) and all(live(n) for n in deployments),
        "condition": lambda c: next((x["status"] for x in status.get("conditions", []) if x["type"] == c), None),
        "jobs": sorted(deployments),
        "restored": lambda d: next((j.get("restoredFrom") for j in status.get("jobs", [])
                                    if j.get("deployment") == d), None),
        "tables": sorted(tables),
        "pending_slots": status.get("pendingSlots", []),
        "kafka_reason": (status.get("kafka") or {}).get("reason"),
        "lag": sum(t.get("lag") or 0 for t in (status.get("kafka") or {}).get("topics", [])),
        "kafka_topics": len((status.get("kafka") or {}).get("topics", [])),
        "accepted": (status.get("events") or {}).get("accepted") or 0,
        "rejected_by": lambda reason: ((status.get("events") or {}).get("rejectedBy") or {}).get(reason, 0),
        "transport": status.get("transport") or {},
    }


def table_scope(doc):
    table = doc["table"]
    status = table.get("status") or {}
    conditions = {c["type"]: c for c in status.get("conditions", [])}
    deployments = doc.get("deployments", [])
    job = status.get("job") or {}

    def live():
        if len(deployments) != 1 or "status" not in deployments[0]:
            return False
        d = deployments[0]
        s = d["status"]
        return ((s.get("jobStatus") or {}).get("state") == "RUNNING"
                and s.get("observedGeneration") == d["metadata"].get("generation")
                and s.get("lifecycleState") in ("STABLE", "DEPLOYED"))

    def lag(upstream):
        return next((u.get("lag") for u in status.get("upstream", []) if u["table"] == upstream), None)

    return {
        "phase": status.get("phase"),
        "condition": lambda c: conditions.get(c, {}).get("status"),
        "why": lambda c: conditions.get(c, {}).get("reason"),
        "live_all": live,
        "restored": lambda: job.get("restoredFrom"),
        "validation": status.get("validation") or {},
        "lag": lag,
        "rolled_back": status.get("rolledBack"),
        "upgrading": status.get("upgrade") is not None,
        "upgrade": status.get("upgrade") or {},
        "checkpointed": job.get("lastCheckpointAt") is not None,
        "lifecycle": job.get("lifecycle"),
        "quarantined": lambda rule: ((status.get("quality") or {}).get("byRule") or {}).get(rule, 0),
    }


def main():
    mode, path = sys.argv[1], sys.argv[2]
    doc = load(path)
    if mode == "check":
        names = scope(doc) if "source" in doc else table_scope(doc)
        sys.exit(0 if eval(sys.argv[3], {"__builtins__": {"all": all, "any": any, "len": len, "str": str, "sum": sum}}, names) else 1)
    elif mode == "tables":
        for t in (doc["source"].get("status") or {}).get("tables", []):
            print(t["name"], t.get("state"), t.get("reason"), f'{t.get("sourceRows")}/{t.get("targetRows")}', sep="\t")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
