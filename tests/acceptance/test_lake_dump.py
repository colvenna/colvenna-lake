"""Offline tests of lake_dump.py, the check lake.sh makes of a LakeDump output before it writes the
out-file: a whole dump passes; one cut short at the beginning (as kubectl logs returns a log the
kubelet rotated, #244), at the end or in between fails, and says so."""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
CHECKER = os.path.join(HERE, "lake_dump.py")
spec = importlib.util.spec_from_file_location("lake_dump", CHECKER)
lake_dump = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lake_dump)


def table(name):
    return {"type": "TABLE", "table": name, "fields": [{"name": "id", "type": "BIGINT NOT NULL"}],
            "primaryKeys": ["id"], "snapshot": 3, "earliestSnapshot": 1, "schemaId": 0, "tags": {},
            "options": {}}


def row(name, i):
    return {"type": "ROW", "table": name, "kind": "+I", "row": {"id": str(i), "state": "paid"}}


def change(name, i):
    return {"type": "CHANGE", "table": name, "snapshot": 1 + i // 10, "committedAt": 1000 + i,
            "kind": "+I", "row": {"id": str(i)}}


def dump(mode, tables):
    """LakeDump's lines for {table: number of ROW or CHANGE lines}, as text."""
    items, written = [], {}
    for name, n in tables.items():
        items.append(table(name))
        if mode == "rows":
            items += [row(name, i) for i in range(n)]
        elif mode == "changelog":
            items += [change(name, i) for i in range(n)]
        written[name] = n if mode != "table" else 0
    items.append({"type": "END", "mode": mode, "lines": len(items), "tables": written})
    return [json.dumps(i, separators=(",", ":")) for i in items]


class ProblemsTest(unittest.TestCase):
    def check(self, lines, mode, *tables):
        return lake_dump.problems(iter(lines), mode, list(tables))

    def test_whole_dumps_pass(self):
        self.assertEqual(self.check(dump("rows", {"dwd.orders": 1000, "ods.customers": 5}), "rows",
                                    "dwd.orders", "ods.customers"), [])
        self.assertEqual(self.check(dump("changelog", {"ods.orders": 40}), "changelog", "ods.orders"), [])
        self.assertEqual(self.check(dump("table", {"a.x": 0, "a.y": 0}), "table", "a.x", "a.y"), [])

    def test_an_empty_table_is_whole(self):
        self.assertEqual(self.check(dump("rows", {"dwd.orders": 0}), "rows", "dwd.orders"), [])

    def test_a_dump_without_its_beginning_fails(self):
        # The kubelet rotated the log at 400 lines: kubectl logs returned what came after.
        lines = dump("rows", {"dwd.orders": 1000})[400:]
        found = self.check(lines, "rows", "dwd.orders")
        self.assertIn("the first line is not the TABLE line of dwd.orders: the beginning is missing", found)
        self.assertIn("lines per table {'dwd.orders': 0}, the END line counts {'dwd.orders': 1000}", found)

    def test_a_dump_without_its_end_fails(self):
        # Cut at the end, or written by a LakeDump older than #244 (no END line at all): the two
        # look alike, and the message names both.
        for lines in (dump("rows", {"dwd.orders": 1000})[:-300], dump("rows", {"dwd.orders": 1000})[:-1]):
            found = self.check(lines, "rows", "dwd.orders")
            self.assertEqual(len(found), 1)
            self.assertIn("no END line", found[0])
            self.assertIn("older than #244", found[0])

    def test_lines_missing_in_between_fail(self):
        lines = dump("rows", {"a.x": 10, "a.y": 10})
        del lines[5:8]
        found = self.check(lines, "rows", "a.x", "a.y")
        self.assertIn("19 lines before the END line, which counts 22", found)
        self.assertIn("lines per table {'a.x': 7, 'a.y': 10}, the END line counts {'a.x': 10, 'a.y': 10}", found)

    def test_a_missing_table_fails(self):
        lines = dump("rows", {"a.x": 2})
        found = self.check(lines, "rows", "a.x", "a.y")
        self.assertIn("TABLE lines of ['a.x'], not of ['a.x', 'a.y']", found)

    def test_an_empty_output_fails(self):
        self.assertEqual(self.check([], "rows", "a.x"), ["empty: no TABLE line, no END line"])

    def test_a_line_cut_in_two_fails(self):
        lines = dump("rows", {"a.x": 3})
        lines[2] = lines[2][:30]
        self.assertEqual(self.check(lines, "rows", "a.x")[0][:24], "line 3 is not JSON: {\"ty")

    def test_another_mode_or_a_stray_line_fails(self):
        self.assertIn("the END line is of mode rows, not changelog",
                      self.check(dump("rows", {"a.x": 0}), "changelog", "a.x"))
        lines = dump("rows", {"a.x": 2})
        lines.insert(1, json.dumps(change("a.x", 0)))
        found = self.check(lines, "rows", "a.x")
        self.assertTrue(any(p.startswith("a line that is not a ROW line under its table's TABLE line")
                            for p in found), found)
        lines = dump("rows", {"a.x": 2, "a.y": 2})
        lines[1] = json.dumps(row("a.y", 9))  # a ROW of a.y before a.y's TABLE line
        self.assertTrue(self.check(lines, "rows", "a.x", "a.y"))

    def test_an_end_line_before_the_last_fails(self):
        lines = dump("rows", {"a.x": 2}) + [json.dumps(row("a.x", 5))]
        found = self.check(lines, "rows", "a.x")
        self.assertIn("line 4 is an END line, not the last", found)
        self.assertIn("the last line is not the END line: the end is missing", found)


class CommandTest(unittest.TestCase):
    def run_checker(self, lines, *args):
        with tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False) as f:
            f.write("".join(line + "\n" for line in lines))
        try:
            return subprocess.run([sys.executable, CHECKER, f.name, *args], capture_output=True,
                                  text=True, timeout=60)
        finally:
            os.unlink(f.name)

    def test_exits_0_on_a_whole_dump_and_1_with_the_problems_otherwise(self):
        whole = dump("rows", {"dwd.orders": 50})
        result = self.run_checker(whole, "rows", "dwd.orders")
        self.assertEqual((result.returncode, result.stderr), (0, ""))
        result = self.run_checker(whole[10:], "rows", "dwd.orders")
        self.assertEqual(result.returncode, 1)
        self.assertIn("the beginning is missing", result.stderr)


if __name__ == "__main__":
    unittest.main()
