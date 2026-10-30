#!/usr/bin/env python3
"""Judges a CDC target against its source, from the dumps lake.sh and sources.sh write.

  compare.py rows --lake <rows.jsonl> --source <table.tsv> --table <db.t> --key <c1,c2>
      The latest snapshot holds exactly the source's rows: same keys, every source column equal.
      Target columns the source no longer has (dropped columns stay in Paimon) are ignored.

  compare.py changelog --lake <changelog.jsonl> --table <db.t> --key <c1,c2> --version <col>
                       [--rows <rows.jsonl>]
      Replays the table's full changelog. Each delete and update-before must carry the row the
      table held (its before image); an insert must not land on a present key; versions must
      rise. With an empty --version (an aggregate has none), before images match column by
      column. With --rows, the replayed state must equal the latest snapshot's rows.

Prints a JSON verdict and exits 1 when anything differs.
"""
import argparse
import json
import re
import sys
from datetime import datetime
from decimal import Decimal, InvalidOperation

DATETIME = re.compile(r"^\d{4}-\d{2}-\d{2}([ T]\d{2}:\d{2}(:\d{2}(\.\d+)?)?)?$")


def norm(value, char=False):
    """Source and lake values on one footing: NULL, numbers and date-times by value, CHAR without
    its padding, everything else as text."""
    if value is None or value in ("NULL", "\\N"):
        return None
    if char:
        return value.rstrip(" ")
    if DATETIME.match(value):
        return datetime.fromisoformat(value.replace(" ", "T"))
    try:
        return Decimal(value).normalize()
    except (InvalidOperation, ValueError):
        return value


def char_columns(path, table):
    """CHAR(n) columns of the table, from the dump's TABLE line: values padded by either side."""
    for item in lake_lines(path, table, "TABLE"):
        return {f["name"] for f in item["fields"] if f["type"].startswith("CHAR(")}
    return set()


def lake_lines(path, table, kind):
    with open(path) as f:
        for line in f:
            item = json.loads(line)
            if item.get("table") == table and item["type"] == kind:
                yield item


def canon(value):
    v = norm(value)
    return format(v, "f") if isinstance(v, Decimal) else str(v)


def key_of(row, keys):
    return tuple(canon(row[k]) for k in keys)


def compare_rows(args):
    keys = args.key.split(",")
    lake = {}
    for item in lake_lines(args.lake, args.table, "ROW"):
        lake[key_of(item["row"], keys)] = item["row"]
    with open(args.source) as f:
        header = f.readline().rstrip("\n").split("\t")
        source = {}
        for line in f:
            row = dict(zip(header, line.rstrip("\n").split("\t")))
            source[key_of(row, keys)] = row
    missing = sorted(set(source) - set(lake))
    extra = sorted(set(lake) - set(source))
    chars = char_columns(args.lake, args.table)
    differing = []
    for k in sorted(set(source) & set(lake)):
        for column, value in source[k].items():
            if column not in lake[k]:
                differing.append({"key": k, "column": column, "problem": "column missing in lake"})
                break
            if norm(value, column in chars) != norm(lake[k][column], column in chars):
                differing.append({"key": k, "column": column, "source": value,
                                  "lake": lake[k][column]})
                break
    verdict = {
        "check": "rows", "table": args.table, "sourceRows": len(source), "lakeRows": len(lake),
        "missing": len(missing), "extra": len(extra), "differing": len(differing),
        "samples": {"missing": missing[:5], "extra": extra[:5], "differing": differing[:5]},
    }
    verdict["pass"] = not missing and not extra and not differing
    return verdict


def same_row(current, before, version):
    """A before image matches by version, or, for tables without one, column by column."""
    if version:
        return norm(current[version]) == norm(before[version])
    return all(norm(current.get(c)) == norm(v) for c, v in before.items())


def compare_changelog(args):
    keys = args.key.split(",")
    state = {}
    versions = {}
    problems = []
    counts = {}
    for item in lake_lines(args.lake, args.table, "CHANGE"):
        kind, row = item["kind"], item["row"]
        counts[kind] = counts.get(kind, 0) + 1
        k = key_of(row, keys)
        current = state.get(k)
        if kind == "+I":
            if current is not None:
                problems.append({"key": k, "snapshot": item.get("snapshot"),
                                 "problem": "insert on a present key"})
            state[k] = row
        elif kind in ("-D", "-U"):
            if current is None:
                problems.append({"key": k, "snapshot": item.get("snapshot"),
                                 "problem": kind + " on an absent key"})
            elif not same_row(current, row, args.version):
                problems.append({"key": k, "snapshot": item.get("snapshot"),
                                 "problem": kind + " before image is not the current row",
                                 "current": current, "before": row})
            state.pop(k, None)
        elif kind == "+U":
            if current is not None:
                problems.append({"key": k, "snapshot": item.get("snapshot"),
                                 "problem": "update-after without its before image"})
            state[k] = row
        if kind in ("+I", "+U") and args.version and args.version in row:
            last = versions.get(k)
            version = norm(row[args.version])
            if last is not None and version <= last:
                problems.append({"key": k, "snapshot": item.get("snapshot"),
                                 "problem": "version did not rise", "version": row[args.version]})
            versions[k] = version
    live = state
    verdict = {"check": "changelog", "table": args.table, "changes": counts,
               "replayedRows": len(live), "problems": len(problems), "samples": problems[:10]}
    if args.rows:
        latest = {key_of(i["row"], keys): i["row"] for i in lake_lines(args.rows, args.table, "ROW")}
        mismatched = [k for k in set(latest) | set(live)
                      if k not in latest or k not in live
                      or any(norm(latest[k].get(c)) != norm(live[k].get(c)) for c in latest[k])]
        verdict["latestRows"] = len(latest)
        verdict["replayMismatches"] = len(mismatched)
        verdict["samples"] += [{"key": k, "problem": "replay differs from latest"} for k in sorted(mismatched)[:5]]
        problems += mismatched
    verdict["pass"] = not problems
    return verdict


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mode", choices=["rows", "changelog"])
    parser.add_argument("--lake", required=True)
    parser.add_argument("--source")
    parser.add_argument("--rows")
    parser.add_argument("--table", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--version", default="version_no")
    args = parser.parse_args()
    verdict = compare_rows(args) if args.mode == "rows" else compare_changelog(args)
    print(json.dumps(verdict, default=str))
    sys.exit(0 if verdict["pass"] else 1)


if __name__ == "__main__":
    main()
