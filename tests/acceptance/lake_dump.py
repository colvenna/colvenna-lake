#!/usr/bin/env python3
"""Whether a LakeDump output (colvenna-jobs tools/LakeDump, as lake.sh fetches it) is whole.

  lake_dump.py <dump.jsonl> table|rows|changelog <db.table>...
      Exits 0 when the file is the whole dump of those tables in that mode, else prints what is
      wrong and exits 1.

A whole dump starts with the first table's TABLE line and ends with the one END line LakeDump
writes last, {"type": "END", "mode": ..., "lines": <lines before it>, "tables": {<db.table>:
<its ROW or CHANGE lines>}}; in between, each table's TABLE line in the order given, then its ROW
(rows) or CHANGE (changelog) lines, and nothing else. An output cut short (#244: kubectl logs
returns only the kubelet's current log file, which drops the beginning of a large one) misses its
first lines, its END line or lines in between, and must not pass for data.
"""
import json
import sys

LINE_TYPE = {"table": None, "rows": "ROW", "changelog": "CHANGE"}


def problems(lines, mode, tables):
    """What makes these lines (an iterable of text, one by one: a large dump is not held in
    memory) not the whole dump; empty when whole."""
    if mode not in LINE_TYPE:
        return [f"not a LakeDump mode: {mode}"]
    kind = LINE_TYPE[mode]
    found, headers, counts = [], [], {t: 0 for t in tables}
    first = end = stray = None
    body = 0
    for n, line in enumerate(lines, 1):
        try:
            item = json.loads(line)
        except ValueError:
            return [f"line {n} is not JSON: {line[:80]}"]
        if not isinstance(item, dict):
            return [f"line {n} is not a JSON object: {line[:80]}"]
        if first is None:
            first = item
        if end is not None:
            found.append(f"line {n - 1} is an END line, not the last")
            end = None
        if item.get("type") == "END":
            end = item
            continue
        body += 1
        if item.get("type") == "TABLE":
            headers.append(item.get("table"))
        elif kind is not None and item.get("type") == kind and headers \
                and item.get("table") == headers[-1]:
            counts[headers[-1]] = counts.get(headers[-1], 0) + 1
        elif stray is None:
            stray = item
    if first is None:
        return ["empty: no TABLE line, no END line"]
    if first.get("type") != "TABLE" or first.get("table") != tables[0]:
        found.append(f"the first line is not the TABLE line of {tables[0]}: the beginning is missing")
    if headers != list(tables):
        found.append(f"TABLE lines of {headers}, not of {list(tables)}")
    if stray is not None:
        found.append(f"a line that is not a {kind or 'TABLE'} line under its table's TABLE line: "
                     f"{json.dumps(stray)[:120]}")
    if end is None and not found and headers == list(tables):
        # Whole in every other respect: a LakeDump from before #244 writes no END line.
        found.append("no END line, and nothing else is wrong: the image's LakeDump is likely older"
                     " than #244 (prepare the environment at this commit), or the end was cut")
    elif end is None:
        found.append("the last line is not the END line: the end is missing")
    else:
        if end.get("mode") != mode:
            found.append(f"the END line is of mode {end.get('mode')}, not {mode}")
        if end.get("lines") != body:
            found.append(f"{body} lines before the END line, which counts {end.get('lines')}")
        if end.get("tables") != counts:
            found.append(f"lines per table {counts}, the END line counts {end.get('tables')}")
    return found


def main(argv):
    if len(argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    path, mode, tables = argv[0], argv[1], argv[2:]
    with open(path, encoding="utf-8") as f:
        found = problems((line.rstrip("\n") for line in f), mode, tables)
    for p in found:
        print(p, file=sys.stderr)
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
