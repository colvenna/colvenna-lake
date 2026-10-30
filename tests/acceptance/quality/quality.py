#!/usr/bin/env python3
"""Synthetic click events for the quality run, and the judge of its checks.

  quality.py events <name> <day> <count> [--nulls N] [--dups N] --expected <file> > <batch.txt>
      key|value lines for kafka-console-producer: `count` events of <day> (YYYY-MM-DD, at noon
      UTC, or now when the day is today), plus `nulls` without a user and `dups` repeating the
      ids of earlier events of the batch. <file> gets the ids of each kind.
  quality.py results <results.jsonl> <task-uid> <rule>=<passed>...
      The check's rows in the store's results table: one per rule, each passed as expected, all
      with the same table, snapshot, partitions, rule version and asOf. Prints them.
  quality.py same <results.jsonl> <uid-a> <uid-b>
      Two checks of the same inputs wrote the same results: measures, verdicts, failing rows,
      checked rows, snapshot, partitions and locate statements, rule by rule.
  quality.py locate <rows.jsonl> <expected.json> nulls|dups
      The rows a failed rule's locate statement lists are the bad events sent: the events without
      a user, or each repeated id with its count.
  quality.py decisions <decisions.jsonl> <table> <cause>...
      The gate's decisions for the table, in order, are these causes (a subsequence check: each
      in turn appears after the one before), each naming its check except NoCheck and Removed.

Prints a JSON verdict and exits 1 when anything differs.
"""
import argparse
import datetime
import json
import sys


def rows(path, kind="ROW"):
    out = []
    for line in open(path):
        line = line.strip()
        if line.startswith("{"):
            obj = json.loads(line)
            if kind in obj:
                out.append(obj[kind])
    return out


def events(args):
    today = datetime.datetime.now(datetime.timezone.utc)
    if args.day == today.strftime("%Y-%m-%d"):
        at = today
    else:
        at = datetime.datetime.fromisoformat(args.day + "T12:00:00+00:00")
    lines, ids, nulls, dups = [], [], [], []
    for i in range(args.count):
        eid = f"{args.name}-{i}"
        ids.append(eid)
        event = {"event_id": eid, "user_id": 1 + i % 50,
                 "event_time": (at - datetime.timedelta(seconds=i % 30)).isoformat()}
        lines.append(f"{eid}|{json.dumps(event)}")
    for i in range(args.nulls):
        eid = f"{args.name}-null-{i}"
        nulls.append(eid)
        lines.append(f"{eid}|{json.dumps({'event_id': eid, 'event_time': at.isoformat()})}")
    for i in range(args.dups):
        eid = ids[i]
        dups.append(eid)
        event = {"event_id": eid, "user_id": 99, "event_time": at.isoformat()}
        lines.append(f"{eid}|{json.dumps(event)}")
    json.dump({"day": args.day, "ids": ids, "nulls": nulls, "dups": dups}, open(args.expected, "w"))
    print("\n".join(lines))
    return 0


def table_rows(path, table=None):
    """LakeDump rows: each ROW line's values by column (as text), of one table if given."""
    out = []
    for line in open(path):
        line = line.strip()
        if not line.startswith("{"):
            continue
        obj = json.loads(line)
        if obj.get("type") == "ROW" and (table is None or obj.get("table") == table):
            out.append(obj["row"])
    return out


def results(args):
    mine = [r for r in table_rows(args.file) if r.get("task_uid") == args.uid]
    want = dict(p.split("=", 1) for p in args.rules)
    problems = []
    got = {r["rule"]: r for r in mine}
    if sorted(got) != sorted(want):
        problems.append(f"rules {sorted(got)}, expected {sorted(want)}")
    for rule, passed in want.items():
        if rule in got and str(got[rule]["passed"]).lower() != passed.lower():
            problems.append(f"{rule} passed={got[rule]['passed']}, expected {passed}")
    for key in ("checked_table", "snapshot_id", "partitions", "rule_version", "as_of", "task"):
        if len({r[key] for r in mine}) > 1:
            problems.append(f"rows differ in {key}")
    verdict = {"ok": not problems, "problems": problems,
               "rows": [{k: r[k] for k in ("rule", "passed", "metric", "measured", "threshold",
                                          "failing_rows", "checked_rows", "snapshot_id",
                                          "partitions", "rule_version")} for r in mine]}
    print(json.dumps(verdict))
    return 0 if verdict["ok"] else 1


SAME = ("metric", "measured", "threshold", "passed", "failing_rows", "checked_rows",
        "snapshot_id", "partitions", "rule_version", "as_of", "locate", "definition")


def same(args):
    all_rows = [r for r in table_rows(args.file) if "task_uid" in r]
    a = {r["rule"]: r for r in all_rows if r["task_uid"] == args.a}
    b = {r["rule"]: r for r in all_rows if r["task_uid"] == args.b}
    problems = []
    if not a or sorted(a) != sorted(b):
        problems.append(f"rules {sorted(a)} and {sorted(b)}")
    for rule in sorted(set(a) & set(b)):
        for key in SAME:
            if a[rule][key] != b[rule][key]:
                problems.append(f"{rule}.{key}: {a[rule][key]} != {b[rule][key]}")
    print(json.dumps({"ok": not problems, "problems": problems, "rules": sorted(a)}))
    return 0 if not problems else 1


def locate(args):
    expected = json.load(open(args.expected))
    got = rows(args.rows)
    if args.kind == "nulls":
        # SELECT * of the events table: event_id first, user_id second.
        listed = sorted(r[0] for r in got)
        want = sorted(expected["nulls"])
        ok = listed == want and all(r[1] is None for r in got)
    else:
        # event_id, n
        listed = sorted((r[0], int(r[1])) for r in got)
        want = sorted((e, 2) for e in expected["dups"])
        ok = listed == want
    print(json.dumps({"ok": ok, "listed": listed, "expected": want}))
    return 0 if ok else 1


def decisions(args):
    mine = [r for r in table_rows(args.file) if "decided_at" in r and r["checked_table"] == args.table]
    mine.sort(key=lambda r: r["decided_at"])
    causes = [r["cause"] for r in mine]
    problems = []
    i = 0
    for want in args.causes:
        while i < len(causes) and causes[i] != want:
            i += 1
        if i == len(causes):
            problems.append(f"no {want} after the ones before, in {causes}")
            break
        i += 1
    for r in mine:
        if r["cause"] not in ("NoCheck", "Removed", "NotReady") and not r["check_uid"]:
            problems.append(f"{r['cause']} at {r['decided_at']} names no check")
    print(json.dumps({"ok": not problems, "problems": problems, "causes": causes,
                      "decisions": mine}))
    return 0 if not problems else 1


def main(argv):
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    e = sub.add_parser("events")
    e.add_argument("name")
    e.add_argument("day")
    e.add_argument("count", type=int)
    e.add_argument("--nulls", type=int, default=0)
    e.add_argument("--dups", type=int, default=0)
    e.add_argument("--expected", required=True)
    r = sub.add_parser("results")
    r.add_argument("file")
    r.add_argument("uid")
    r.add_argument("rules", nargs="+")
    s = sub.add_parser("same")
    s.add_argument("file")
    s.add_argument("a")
    s.add_argument("b")
    loc = sub.add_parser("locate")
    loc.add_argument("rows")
    loc.add_argument("expected")
    loc.add_argument("kind", choices=["nulls", "dups"])
    d = sub.add_parser("decisions")
    d.add_argument("file")
    d.add_argument("table")
    d.add_argument("causes", nargs="+")
    args = p.parse_args(argv)
    return {"events": events, "results": results, "same": same, "locate": locate,
            "decisions": decisions}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
