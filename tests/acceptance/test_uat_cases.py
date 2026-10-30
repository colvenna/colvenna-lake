"""Offline checks that the UAT's case map (tests/acceptance/uat-cases.tsv) matches the case
document (docs/uat/cases.md) and the runner (tests/acceptance/run-uat.sh): every case heading has a
row and every row a heading, every required case has a row, and every run label is one the runner
runs: the case's first, deciding label in the case's phase, the others (the runs it depends on) in
any phase."""
import csv
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
CASES = os.path.join(REPO, "docs", "uat", "cases.md")
RUNNER = os.path.join(HERE, "run-uat.sh")
MAP = os.path.join(HERE, "uat-cases.tsv")
CASE_ID = re.compile(r"UAT-[A-Z]+-\d\d")


def read(path):
    with open(path) as f:
        return f.read()


def rows():
    with open(MAP, newline="") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def headings():
    return [m.group(1) for m in re.finditer(r"^#### (UAT-[A-Z]+-\d\d) ", read(CASES), re.M)]


def required():
    text = read(CASES)
    start, end = text.index("<!-- required-cases:start -->"), text.index("<!-- required-cases:end -->")
    ids = []
    for line in text[start:end].splitlines():
        m = re.match(r"^\| (UAT-[A-Z]+-\d\d) \|", line)
        if m:
            ids.append(m.group(1))
    return ids


def runner_labels():
    """{phase: {label}}: the labels each phase_<name> function passes to run."""
    out, phase = {}, None
    for line in read(RUNNER).splitlines():
        m = re.match(r"^phase_([a-z]+)\(\) \{", line)
        if m:
            phase = m.group(1)
            out[phase] = set()
        elif line.startswith("}"):
            phase = None
        elif phase:
            out[phase].update(re.findall(r"\brun ([a-z0-9-]+) ", line))
    return out


class UatCasesTest(unittest.TestCase):
    def test_the_map_has_its_columns(self):
        for row in rows():
            self.assertEqual(list(row), ["case", "phase", "labels", "configs", "evidence"])
            self.assertRegex(row["case"], CASE_ID)
            self.assertIn(row["configs"], ("platform", "fixtures", "both"), row["case"])
            self.assertTrue(row["labels"] and row["evidence"], row["case"])

    def test_every_case_heading_has_one_row_and_every_row_a_heading(self):
        cases = [r["case"] for r in rows()]
        self.assertEqual(len(cases), len(set(cases)), "a case twice in the map")
        self.assertEqual(sorted(headings()), sorted(cases))

    def test_the_map_s_configurations_are_the_document_s(self):
        # A case's 适用配置: "只装平台或 `--fixtures`" is both, "`--fixtures`" alone fixtures, "只装平台" alone platform.
        text = read(CASES)
        documented = {}
        for m in re.finditer(r"^#### (UAT-[A-Z]+-\d\d) .*?^\*\*适用配置\*\*：([^\n]*)", text, re.M | re.S):
            line = m.group(2)
            both = "只装平台或 `--fixtures`" in line or "`--fixtures` 或只装平台" in line
            documented[m.group(1)] = "both" if both else "fixtures" if "`--fixtures`" in line.split("（")[0] else "platform"
        self.assertEqual(sorted(documented), sorted(headings()), "a case without 适用配置")
        for row in rows():
            self.assertEqual(row["configs"], documented[row["case"]], row["case"])

    def test_every_required_case_is_mapped(self):
        ids = required()
        self.assertTrue(ids, "no required cases between the markers")
        self.assertEqual(len(ids), len(set(ids)), "a required case twice")
        self.assertEqual(sorted(set(ids) - {r["case"] for r in rows()}), [])
        self.assertEqual(sorted(set(ids) - set(headings())), [])

    def test_every_runner_label_is_one_the_runner_runs(self):
        phases = runner_labels()
        anywhere = set().union(*phases.values())
        for row in rows():
            if row["phase"] == "operator":
                continue
            self.assertIn(row["phase"], phases, row["case"])
            deciding, *depended = row["labels"].split(",")
            self.assertIn(deciding, phases[row["phase"]], f"{row['case']}: {deciding} is not run in {row['phase']}")
            for label in depended:
                self.assertIn(label, anywhere, f"{row['case']}: the runner runs no {label}")

    def test_the_runner_reads_this_map_s_phases_in_order(self):
        order = re.search(r"phases=\(([a-z ]+)\)", read(RUNNER)).group(1).split()
        mapped = {r["phase"] for r in rows()} - {"operator"}
        self.assertEqual(sorted(mapped - set(order)), [])


if __name__ == "__main__":
    unittest.main()
