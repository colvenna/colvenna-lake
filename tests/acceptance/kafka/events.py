#!/usr/bin/env python3
"""Synthetic events for the Kafka events run, and the judge of where they landed.

  events.py batch <name> <count> --expected <expected.json> > <batch.txt>
      key|value lines for kafka-console-producer, and <expected.json>: the event ids
      that must land in the events table and the reason each other record must be rejected for.
      Every batch has `count` valid events (some with an undeclared field) and one record per
      rejection reason the declaration can meet (UNKNOWN_FIELD needs unknownFields: reject).
  events.py late <name> --expected <expected.json> > <late.txt>
      one event ten minutes behind the others: sent once the job has read a batch, the
      watermark has passed it (LATE).
  events.py judge --rows <events.jsonl> --rejections <rejections.jsonl> --ends <ends.txt>
                  --table <db.t> --rejections-table <db.t> <expected.json>...
      The events table holds exactly the expected events, field by field; the rejections table
      holds the expected reasons, and no payload; and every offset of every partition up to its
      end is in exactly one of the two tables (nothing skipped, nothing twice).

Prints a JSON verdict and exits 1 when anything differs.
"""
import argparse
import collections
import datetime
import json
import random
import sys


def now():
    return datetime.datetime.now(datetime.timezone.utc)


def batch(args):
    t = now()
    lines, expected = [], {"events": {}, "rejections": collections.Counter()}
    for i in range(args.count):
        eid = f"{args.name}-{i}"
        event = {"event_id": eid, "event_type": random.choice(["click", "view", "purchase"]),
                 "amount": f"{random.randint(0, 99999)}.{random.randint(0, 99):02d}",
                 "user_id": random.randint(1, 500),
                 "event_time": (t - datetime.timedelta(seconds=random.randint(0, 20))).isoformat()}
        if i % 10 == 0:
            event["campaign"] = "autumn"
        lines.append(f"{eid}|{json.dumps(event)}")
        expected["events"][eid] = {k: event[k] for k in ("event_id", "event_type", "amount", "user_id")}
        expected["events"][eid]["campaign"] = event.get("campaign")
    ok = {"event_id": f"{args.name}-x", "event_type": "view", "event_time": t.isoformat()}
    broken = {
        "NOT_JSON_OBJECT": "this is not json",
        "MISSING_FIELD": {k: v for k, v in ok.items() if k != "event_id"},
        "WRONG_TYPE": {**ok, "user_id": "seven"},
        "TOO_LONG": {**ok, "event_id": "x" * 65},
        "NOT_ALLOWED_VALUE": {**ok, "event_type": "refund"},
        "OUT_OF_RANGE": {**ok, "amount": -5},
        "BAD_EVENT_TIME": {**ok, "event_time": "yesterday"},
        "TOO_OLD": {**ok, "event_time": (t - datetime.timedelta(days=40)).isoformat()},
        "TOO_NEW": {**ok, "event_time": (t + datetime.timedelta(days=3)).isoformat()},
    }
    for reason, value in broken.items():
        text = value if isinstance(value, str) else json.dumps(value)
        lines.append(f"{args.name}-{reason}|{text}")
        expected["rejections"][reason] += 1
    # An empty value (the console producer sends one for a key with nothing after it).
    lines.append(f"{args.name}-INVALID_SIZE|")
    expected["rejections"]["INVALID_SIZE"] += 1
    print("\n".join(lines))
    with open(args.expected, "w") as f:
        json.dump(expected, f)


def late(args):
    t = now()
    event = {"event_id": f"{args.name}-late", "event_type": "view",
             "event_time": (t - datetime.timedelta(minutes=10)).isoformat()}
    print(f"{args.name}-late|{json.dumps(event)}")
    with open(args.expected, "w") as f:
        json.dump({"events": {}, "rejections": {"LATE": 1}}, f)


def rows(path, table):
    with open(path) as f:
        for line in f:
            item = json.loads(line)
            if item.get("type") == "ROW" and item.get("table") == table:
                yield item["row"]


def judge(args):
    want_events, want_reasons = {}, collections.Counter()
    for path in args.expected:
        with open(path) as f:
            e = json.load(f)
        want_events.update(e["events"])
        want_reasons.update(e["rejections"])
    got = {}
    positions = collections.Counter()
    problems = []
    for row in rows(args.rows, args.table):
        positions[(row["_topic"], int(row["_partition"]), int(row["_offset"]))] += 1
        if row["event_id"] in got:
            problems.append({"problem": "event twice", "event_id": row["event_id"]})
        got[row["event_id"]] = row
    reasons = collections.Counter()
    for row in rows(args.rejections, args.rejections_table):
        positions[(row["_topic"], int(row["_partition"]), int(row["_offset"]))] += 1
        reasons[row["reason"]] += 1
        more = set(row) - {"dt", "reason", "field", "detected_at_ms", "_topic", "_partition", "_offset"}
        if more:
            # The column names only: their values may be a payload.
            problems.append({"problem": "rejection carries more than its position",
                             "columns": sorted(more)})
    missing = sorted(set(want_events) - set(got))
    extra = sorted(set(got) - set(want_events))
    for eid in sorted(set(want_events) & set(got)):
        w, g = want_events[eid], got[eid]
        for k, v in w.items():
            gv = g.get(k)
            if k == "campaign" and v is not None and gv is None:
                # Before the field was declared it lands in extra_json.
                gv = json.loads(g.get("extra_json") or "{}").get("campaign")
            same = (gv is None) if v is None else (
                str(gv) == str(v) or (k == "amount" and gv is not None and float(gv) == float(v)))
            if not same:
                problems.append({"problem": "value differs", "event_id": eid, "field": k,
                                 "want": v, "got": gv})
                break
    with open(args.ends) as f:
        ends = {}
        for line in f:
            topic, partition, end = line.strip().split(":")
            ends[(topic, int(partition))] = int(end)
    gaps, twice = [], [p for p, n in positions.items() if n > 1]
    for (topic, partition), end in ends.items():
        for offset in range(end):
            if positions[(topic, partition, offset)] == 0:
                gaps.append((topic, partition, offset))
    verdict = {"events": len(got), "expectedEvents": len(want_events), "missing": len(missing),
               "extra": len(extra), "rejections": dict(reasons), "expectedRejections": dict(want_reasons),
               "offsetsRead": sum(positions.values()), "endOffsets": sum(ends.values()),
               "gaps": len(gaps), "twice": len(twice), "problems": len(problems),
               "samples": {"missing": missing[:5], "extra": extra[:5], "gaps": gaps[:5],
                           "twice": twice[:5], "problems": problems[:5]}}
    verdict["pass"] = (not missing and not extra and not gaps and not twice and not problems
                       and reasons == want_reasons)
    print(json.dumps(verdict, default=str))
    sys.exit(0 if verdict["pass"] else 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    b = sub.add_parser("batch")
    b.add_argument("name")
    b.add_argument("count", type=int)
    b.add_argument("--expected", required=True)
    lt = sub.add_parser("late")
    lt.add_argument("name")
    lt.add_argument("--expected", required=True)
    j = sub.add_parser("judge")
    j.add_argument("--rows", required=True)
    j.add_argument("--rejections", required=True)
    j.add_argument("--ends", required=True)
    j.add_argument("--table", required=True)
    j.add_argument("--rejections-table", required=True)
    j.add_argument("expected", nargs="+")
    args = parser.parse_args()
    {"batch": batch, "late": late, "judge": judge}[args.command](args)


if __name__ == "__main__":
    main()
