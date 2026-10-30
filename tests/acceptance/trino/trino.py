#!/usr/bin/env python3
"""Judges the Trino run's Trino answers (trino.sh trino_sql: CSV rows, or "ERROR <message>").

  trino.py same <trino-csv> <lake-rows> <column>...
      Trino's rows are the lake's (LakeDump rows, JSON lines), compared on these columns.
  trino.py failed <trino-csv> <text>...
      The statement failed, its error (after "Query <id> failed:") containing one of the texts
      (case-insensitive).
  trino.py equal <trino-csv> <value>
      The statement answered one row, that value.

Prints a JSON verdict and exits 1 when it does not hold.
"""
import csv
import json
import re
import sys


def answer(path):
    """Trino's rows, or its error without the query id (whose digits could match a status)."""
    with open(path) as f:
        text = f.read()
    if text.startswith("ERROR "):
        return None, re.sub(r"^Query \S+ failed: ", "", text[len("ERROR "):].strip())
    return [row for row in csv.reader(text.splitlines()) if row], None


def lake_rows(path, columns):
    rows = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line.startswith("{"):
                continue
            r = json.loads(line)
            if r.get("type") == "ROW":
                rows.append(["" if r["row"].get(c) is None else str(r["row"][c]) for c in columns])
    return rows


def main(argv):
    command, path = argv[0], argv[1]
    rows, error = answer(path)
    if command == "same":
        lake = lake_rows(argv[2], argv[3:])
        ok = error is None and sorted(rows) == sorted(lake)
        verdict = {"ok": ok, "trino": None if rows is None else len(rows), "lake": len(lake),
                   "error": error}
        if not ok and rows is not None:
            in_trino, in_lake = set(map(tuple, rows)), set(map(tuple, lake))
            verdict["only_trino"] = sorted(in_trino - in_lake)[:3]
            verdict["only_lake"] = sorted(in_lake - in_trino)[:3]
    elif command == "failed":
        texts = [t.lower() for t in argv[2:]]
        ok = error is not None and any(t in error.lower() for t in texts)
        verdict = {"ok": ok, "error": error, "rows": None if rows is None else len(rows)}
    elif command == "equal":
        ok = error is None and rows == [[argv[2]]]
        verdict = {"ok": ok, "rows": rows, "error": error}
    else:
        sys.exit(__doc__)
    print(json.dumps(verdict))
    return 0 if verdict["ok"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
