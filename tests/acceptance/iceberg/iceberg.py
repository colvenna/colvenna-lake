#!/usr/bin/env python3
"""Judges the Iceberg runs' spark-sql output (spark-sql.sh: "@@ <n>" before each statement's rows).

  iceberg.py same <spark-out> <n> <m>
      Statements n and m answered the same rows (as sets of lines), neither failed.
  iceberg.py rows <spark-out> <n> <count>
      Statement n answered that many rows.
  iceberg.py failed <spark-out> <n> <text>
      Statement n failed, its error containing the text.
  iceberg.py has <spark-out> <n> <text>
      Statement n succeeded and one of its rows contains the text.

Prints a JSON verdict and exits 1 when it does not hold.
"""
import json
import sys


def statements(path):
    out, current = {}, None
    for line in open(path).read().splitlines():
        if line.startswith("@@# "):
            continue  # a failed statement's error output
        if line.startswith("@@ "):
            parts = line.split(" ", 2)
            current = parts[1]
            out[current] = {"error": parts[2][len("ERROR "):] if len(parts) > 2 else None,
                            "rows": []}
        elif current is not None:
            out[current]["rows"].append(line)
    return out


def main(argv):
    command, path = argv[0], argv[1]
    s = statements(path)
    key = lambda n: "%03d" % int(n)
    if command == "same":
        a, b = s.get(key(argv[2])), s.get(key(argv[3]))
        ok = bool(a and b and a["error"] is None and b["error"] is None
                  and sorted(a["rows"]) == sorted(b["rows"]))
        verdict = {"ok": ok, "rows": len(a["rows"]) if a else None,
                   "only_first": sorted(set(a["rows"]) - set(b["rows"]))[:5] if a and b else None,
                   "only_second": sorted(set(b["rows"]) - set(a["rows"]))[:5] if a and b else None,
                   "errors": [a and a["error"], b and b["error"]]}
    elif command == "rows":
        a = s.get(key(argv[2]))
        ok = bool(a and a["error"] is None and len(a["rows"]) == int(argv[3]))
        verdict = {"ok": ok, "rows": len(a["rows"]) if a else None, "error": a and a["error"]}
    elif command == "failed":
        a = s.get(key(argv[2]))
        ok = bool(a and a["error"] is not None and argv[3] in a["error"])
        verdict = {"ok": ok, "error": a and a["error"]}
    elif command == "has":
        a = s.get(key(argv[2]))
        ok = bool(a and a["error"] is None and any(argv[3] in r for r in a["rows"]))
        verdict = {"ok": ok, "rows": a and a["rows"][:5], "error": a and a["error"]}
    else:
        sys.exit(__doc__)
    print(json.dumps(verdict))
    return 0 if verdict["ok"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
