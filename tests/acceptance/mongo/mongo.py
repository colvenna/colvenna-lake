#!/usr/bin/env python3
"""Judges a MongoDB source's target against its collection, for the MongoDB run.

  mongo.py rows --lake <rows.jsonl> --source <docs.jsonl> --table <db.t>
      The latest snapshot holds exactly the collection's documents: the same _id values, and
      every top-level field of every document equal to its column. <docs.jsonl> is one relaxed
      Extended JSON document per line; the lake holds every field as a STRING (nested values as
      JSON), so both sides are compared by value: numbers as numbers, nested documents and arrays
      structurally, Extended JSON wrappers ($oid, $date, $numberLong, ...) unwrapped.

Prints a JSON verdict and exits 1 when anything differs.
"""
import argparse
import json
import sys
from decimal import Decimal, InvalidOperation

WRAPPERS = ("$oid", "$date", "$numberLong", "$numberInt", "$numberDouble", "$numberDecimal")


def unwrap(value):
    """A value without Extended JSON's type wrappers, recursively."""
    if isinstance(value, dict):
        if len(value) == 1 and next(iter(value)) in WRAPPERS:
            return unwrap(next(iter(value.values())))
        return {k: unwrap(v) for k, v in value.items()}
    if isinstance(value, list):
        return [unwrap(v) for v in value]
    return value


def scalar(value):
    """A scalar on one footing: numbers by value, booleans and null as JSON spells them."""
    if value is None:
        return None
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(Decimal(str(value)).normalize())
    try:
        return str(Decimal(value).normalize())
    except (InvalidOperation, ValueError, TypeError):
        return str(value)


def canon(value):
    value = unwrap(value)
    if isinstance(value, dict):
        return {k: canon(v) for k, v in value.items()}
    if isinstance(value, list):
        return [canon(v) for v in value]
    return scalar(value)


def lake_value(text):
    """A column's text as the value it holds: JSON for nested values, else the text itself."""
    if text is None:
        return None
    try:
        parsed = json.loads(text)
    except ValueError:
        return canon(text)
    return canon(parsed) if isinstance(parsed, (dict, list)) else canon(text)


def rows(path, table):
    with open(path) as f:
        for line in f:
            item = json.loads(line)
            if item.get("type") == "ROW" and item.get("table") == table:
                yield item["row"]


def compare_rows(args):
    source = {}
    with open(args.source) as f:
        for line in f:
            if line.strip():
                doc = json.loads(line)
                source[str(unwrap(doc["_id"]))] = doc
    lake = {r["_id"]: r for r in rows(args.lake, args.table)}
    missing = sorted(set(source) - set(lake))
    extra = sorted(set(lake) - set(source))
    differing = []
    for key in sorted(set(source) & set(lake)):
        doc, row = source[key], lake[key]
        for field, value in doc.items():
            if field == "_id":
                continue
            if canon(value) != lake_value(row.get(field)):
                differing.append({"_id": key, "field": field, "source": canon(value),
                                  "lake": row.get(field)})
                break
        else:
            # A column the document lacks must be empty in its row.
            for column, text in row.items():
                if column != "_id" and column not in doc and text is not None:
                    differing.append({"_id": key, "field": column, "source": None, "lake": text})
                    break
    verdict = {"check": "rows", "table": args.table, "sourceRows": len(source), "lakeRows": len(lake),
               "missing": len(missing), "extra": len(extra), "differing": len(differing),
               "samples": {"missing": missing[:5], "extra": extra[:5], "differing": differing[:5]}}
    verdict["pass"] = not (missing or extra or differing)
    return verdict


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    r = sub.add_parser("rows")
    r.add_argument("--lake", required=True)
    r.add_argument("--source", required=True)
    r.add_argument("--table", required=True)
    args = parser.parse_args()
    verdict = compare_rows(args)
    print(json.dumps(verdict, default=str))
    sys.exit(0 if verdict["pass"] else 1)


if __name__ == "__main__":
    main()
