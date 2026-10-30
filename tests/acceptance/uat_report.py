#!/usr/bin/env python3
"""The per-case results of one region's UAT, for the release's UAT report (docs/uat/reports/), and
the summary.tsv of an evidence root (run-uat.sh's summary phase).

  uat_report.py report --region <region> <evidence-root>... [--cases <tsv>] [--doc <md>]
                [--overrides <tsv>]
  uat_report.py summary <evidence-root> [--cases <tsv>]

`report` judges every case of the release's required set (docs/uat/cases.md, between the
required-cases markers) that is in scope in <region> (the set's 区域 column), from what run-uat.sh,
rerun-uat.sh and the operator's checkers left in the evidence roots (runs.tsv, each run's
verdicts.tsv, the evidence files uat-cases.tsv names, its log), against the case's 期望结果:

- The deciding run is the last run, in any root, of the case's first label in uat-cases.tsv or of
  a rerun of it (<label>-2, <label>-3 ...). When the case map's evidence says `<label>/kind (<k>)`,
  only runs of that kind count (a reinstall that was a repeat is no upgrade).
- PASS: exit 0; every check the 期望结果 table names (STEP, CHECK or 检查 tables) is among the
  run's results and none failed (a check said to show only on failure may be absent, one judged
  only once another passed is required when that one passed); as many results as the sentence
  announces, and as many with a prefix as it announces; the last output line, or the line of an
  evidence file, it announces. A table that names checks with no result anywhere is a FAIL.
- FAIL: anything of that not met, or a run of a case that needs a kind that wrote none (it died
  early). BLOCKED: a supporting run (the other labels) failed before the deciding run, or the
  deciding run never ran and one failed. NOT_RUN: nothing of the case ran. A required case in scope
  never leaves the count; cases out of scope in the region are listed with their 区域 cell. Any
  other run (of no case, or of a case not judged in the region) is listed as an other run.
- Each evidence root is judged on its own runs (a supporting run belongs to its environment); a
  FAIL in any root wins, then a PASS, then BLOCKED, then NOT_RUN.
- --overrides (case, result, reason) may only make a result worse: PASS or NOT_RUN to BLOCKED or
  FAIL, BLOCKED to FAIL; never a PASS, never over a FAIL, never for a case not judged here.

`summary` prints one row per check of every run in runs.tsv (label, check, outcome, detail), from
the same reading of results, and one `(exit status)` row for a run without a result row, or with a
non-zero exit and no FAIL row.
"""
import argparse
import csv
import os
import re
import sys
from collections import namedtuple
from dataclasses import dataclass, field

HERE = os.path.dirname(os.path.abspath(__file__))
OUTCOMES = ("PASS", "FAIL", "BLOCKED", "NOT_RUN")
START, END = "<!-- required-cases:start -->", "<!-- required-cases:end -->"
CASE_ID = re.compile(r"UAT-[A-Z]+-\d+")
REGION = re.compile(r"[a-z]{2}-[a-z]+-\d")
TIMESTAMP = re.compile(r"^\d{4}-\d\d-\d\dT[\d:.]+Z\s+")
# Result lines: "STEP <name> OK ...", "CHECK <name> expected=... actual=... OK",
# "<name> expected=<anything> actual=<value> [OK|FAIL]" and, in evidence files, "<name> OK <detail>".
STEP_LINE = re.compile(r"^(?:STEP|CHECK|check)\s+(\S+)\s+(?:expected=.*\sactual=\S*\s+)?(OK|PASS|FAIL)\b(.*)$")
EXPECTED_LINE = re.compile(r"^(?:CHECK\s+)?(.+?) expected=(.*?) actual=(.*?)(?:\s+(OK|PASS|FAIL))?\s*$")
STATUS_LINE = re.compile(r"^([A-Za-z0-9<][^\s:,]*) (OK|PASS|FAIL)(?:\s+(.*))?$")
# The 期望结果 tables whose first column names checks; others describe lines of a file.
CHECK_TABLES = ("STEP", "CHECK", "检查")

Run = namedtuple("Run", "root label status start seconds")
Required = namedtuple("Required", "case cell regions")
Finding = namedtuple("Finding", "name outcome detail")


@dataclass
class Check:
    name: str
    pattern: re.Pattern
    optional: bool = False
    prereq: str = None


@dataclass
class Expected:
    count: int = 0
    checks: list = field(default_factory=list)
    prefixes: list = field(default_factory=list)  # (prefix, how many, prerequisite or None)
    conditions: dict = field(default_factory=dict)  # check -> the check it is judged after
    last_output: str = None
    last_line: tuple = None  # (file in the run's directory, token)
    has_lines: list = field(default_factory=list)  # (file, token)


@dataclass
class CaseRow:
    case: str
    labels: list
    files: list
    kind: str = None


@dataclass
class Result:
    case: str
    result: str = "NOT_RUN"
    start: str = ""
    seconds: int = 0
    runs: list = field(default_factory=list)
    evidence: str = ""
    checks_text: str = ""
    note: str = ""
    root: str = ""


@dataclass
class Report:
    cases: list
    out_of_scope: list
    other_runs: list


def text_lines(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.readlines()


def pattern(text):
    """A name or line as the document writes it: <...> placeholders, a lone N and a parenthesized
    run id (reruns take another id) stand for any text."""
    text = re.sub(r"\([^)]*<[^)]*>[^)]*\)", "(<x>)", text)
    parts = re.split(r"<[^<>]+>|\bN\b", text)
    return re.compile("[^\t]*?".join(re.escape(p) for p in parts) + r"\Z")


def read_required(doc):
    """The release's required set, in the document's order, each case with the regions its 区域
    cell names (None: every region)."""
    text = "".join(text_lines(doc))
    if START not in text or END not in text:
        sys.exit(f"{doc}: no {START} ... {END} section")
    rows, region_column = [], None
    for line in text.split(START, 1)[1].split(END, 1)[0].splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if "区域" in cells:
            region_column = cells.index("区域")
        if not CASE_ID.fullmatch(cells[0].strip("`")):
            continue
        cell = cells[region_column] if region_column is not None and len(cells) > region_column else ""
        rows.append(Required(cells[0].strip("`"), cell, cell_regions(doc, cells[0], cell)))
    if not rows:
        sys.exit(f"{doc}: the required-cases section lists no case")
    return rows


def cell_regions(doc, case, cell):
    """The regions a 区域 cell holds a case to: None for every region ("两个区域各一次..."), else
    the ones named in the parentheses of "至少一个区域（本次 ...）" or "升级的那套环境所在的区域（...）".
    Any other wording stops: a cell is never guessed at."""
    if cell.startswith("两个区域各一次"):
        return None
    m = re.fullmatch(r"(?:至少一个区域|升级的那套环境所在的区域)（([^（）]*)）", cell)
    named = REGION.findall(m.group(1)) if m else []
    if not named or re.search(r"以外|之外|除", cell):
        sys.exit(f"{doc}: {case}'s 区域 cell is worded in a way this script does not read: {cell!r}")
    return named


def regions(required):
    """The regions the required set's 区域 cells name: what a report may be for."""
    return sorted({r for item in required for r in item.regions or ()})


def read_expected(doc):
    """Per case, what its 期望结果 announce: the checks its STEP/CHECK/检查 tables name (optional
    when they show only on failure, with a prerequisite when judged only once another passed), how
    many results, how many with a prefix, and the last output or evidence-file line."""
    expected, case, in_expected, header = {}, None, False, None
    for line in text_lines(doc):
        heading = re.match(r"^#### (UAT-[A-Z]+-\d+)\b", line)
        if heading:
            case, in_expected, header = heading.group(1), False, None
            expected[case] = Expected()
            continue
        if case is None:
            continue
        if line.startswith("**期望结果**"):
            in_expected = True
            read_sentence(expected[case], line)
            continue
        if line.startswith("**") and in_expected:
            in_expected = False
        if not in_expected or not line.startswith("| "):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if cells[0].startswith("---"):
            continue
        if not cells[0].startswith("`"):
            header = cells[0]
            continue
        if header in CHECK_TABLES:
            rest = " ".join(cells[1:])
            # The names before any prose in the cell ("`audit <调用>`：上面除 `x` 之外的 ...").
            for name in re.findall(r"`([^`]+)`", cells[0].split("：", 1)[0]):
                expected[case].checks.append(Check(name, pattern(name), optional="只在失败时" in rest))
    for exp in expected.values():
        for check in exp.checks:
            check.prereq = exp.conditions.get(check.name)
    return expected


def read_sentence(exp, sentence):
    count = re.search(r"(\d+) (?:行|项)", sentence)
    exp.count = int(count.group(1)) if count else 0
    for names, prereq in re.findall(r"((?:`[^`]+`\s*(?:与|和|、)\s*)*`[^`]+`)\s*仅在\s*`([^`]+)`\s*通过后", sentence):
        for name in re.findall(r"`([^`]+)`", names):
            exp.conditions[name] = prereq
    for prefix, many, prereq in re.findall(r"`([^`]+)` 开头的(?:另外)?\s*(\d+)\s*项(?:仅在\s*`([^`]+)`\s*通过后)?", sentence):
        exp.prefixes.append((prefix, int(many), prereq or None))
    output = re.search(r"最后输出 `([^`]+)`", sentence)
    exp.last_output = output.group(1) if output else None
    exp.has_lines = re.findall(r"`([^`/]+\.(?:txt|log))` 有 `([^`]+)`", sentence)
    last = re.search(r"(?:`([^`/]+\.(?:txt|log))` )?(?:有 `[^`]+`，)?最后一行为 `([^`]+)`", sentence)
    if last:
        exp.last_line = (last.group(1) or (exp.has_lines[0][0] if exp.has_lines else None), last.group(2))


def read_cases(path):
    """The case map: each case's labels (the first decides), the evidence files it names under a
    label's directory, and the kind its deciding run must be (`<label>/kind (<kind>)`)."""
    with open(path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f, delimiter="\t"))
    cases = {}
    for r in rows:
        kind = re.search(r"\S+/kind \((\w+)\)", r["evidence"])
        files = [f for f in re.findall(r"\b([a-z0-9-]+/[\w.-]+\.(?:txt|tsv|log))\b", r["evidence"])
                 if not f.endswith("verdicts.tsv")]
        cases[r["case"]] = CaseRow(r["case"], [l for l in r["labels"].split(",") if l], files,
                                   kind.group(1) if kind else None)
    return cases


def read_overrides(path):
    if not path:
        return {}
    with open(path, encoding="utf-8") as f:
        return {r["case"]: (r["result"], r["reason"]) for r in csv.DictReader(f, delimiter="\t")}


def read_runs(roots):
    runs = []
    for root in roots:
        path = os.path.join(root, "runs.tsv")
        for row in csv.reader(text_lines(path), delimiter="\t") if os.path.exists(path) else []:
            if row:
                runs.append(Run(root, row[0], int(row[1]), row[2], int(row[3])))
    return sorted(runs, key=lambda r: r.start)


def of_label(run, label):
    """Whether a run is of the label or a rerun of it (<label>-2, <label>-3 ...)."""
    return run.label == label or re.fullmatch(re.escape(label) + r"-\d+", run.label) is not None


def findings(run, files=()):
    """The run's check results: its verdicts.tsv; else the evidence files the case map names (under
    the run's own directory); else its log's STEP, CHECK and expected=/actual= lines."""
    directory = os.path.join(run.root, run.label)
    path = os.path.join(directory, "verdicts.tsv")
    if os.path.exists(path):
        rows = [l.rstrip("\n").split("\t") for l in text_lines(path)]
        return [Finding(p[1], "PASS" if p[2] == "PASS" else "FAIL", p[3] if len(p) > 3 else "")
                for p in rows if len(p) >= 3]
    found = []
    for name in files:
        path = os.path.join(directory, name.split("/", 1)[1])
        for line in text_lines(path) if os.path.exists(path) else []:
            found += parse_line(line, in_evidence=True)
    if found:
        return found
    log = os.path.join(run.root, run.label + ".log")
    for line in text_lines(log) if os.path.exists(log) else []:
        found += parse_line(line, in_evidence=False)
    return found


def parse_line(line, in_evidence):
    line = TIMESTAMP.sub("", line.strip())
    m = STEP_LINE.match(line)
    if m:
        return [Finding(m.group(1), "PASS" if m.group(2) in ("OK", "PASS") else "FAIL", m.group(3).strip())]
    m = EXPECTED_LINE.match(line)
    if m:
        outcome = m.group(4) or ("OK" if m.group(2) == m.group(3) else "FAIL")
        return [Finding(m.group(1), "PASS" if outcome in ("OK", "PASS") else "FAIL",
                        f"expected={m.group(2)} actual={m.group(3)}")]
    m = STATUS_LINE.match(line) if in_evidence else None
    if m:
        return [Finding(m.group(1), "PASS" if m.group(2) in ("OK", "PASS") else "FAIL", (m.group(3) or "").strip())]
    return []


def last_line(path):
    lines = [TIMESTAMP.sub("", l.strip()) for l in text_lines(path) if l.strip()] if os.path.exists(path) else []
    return lines[-1] if lines else None


def kind_of(run):
    path = os.path.join(run.root, run.label, "kind")
    return text_lines(path)[0].strip() if os.path.exists(path) else None


def flat(text):
    """One TSV field: no tab or line break inside."""
    return re.sub(r"[\t\r\n]+", " ", text)


def join(*parts):
    return "；".join(p for p in parts if p)


def judge(roots, cases, expected, required, region, overrides):
    """Each case judged in each evidence root on that root's runs alone (a supporting run belongs
    to the environment it ran on), then combined: a FAIL in any root is a FAIL; else a PASS in any
    root is a PASS; else BLOCKED in any root; else NOT_RUN."""
    runs = read_runs(roots)
    judged, out_of_scope = [], []
    for item in required:
        if item.case not in cases:
            sys.exit(f"{item.case} is required but has no row in the case map")
        if item.regions is not None and region not in item.regions:
            out_of_scope.append((item.case, item.cell))
            continue
        exp = expected.get(item.case, Expected())
        per_root = [judge_case(cases[item.case], exp, [run for run in runs if run.root == root]) for root in roots]
        for root, r in zip(roots, per_root):
            r.root = os.path.basename(root)
        judged.append(combine(item.case, per_root))
    names = {r.case for r in judged}
    for case, (outcome, reason) in overrides.items():
        if case not in names:
            sys.exit(f"override of {case}: not a required case judged in {region}")
        override(next(r for r in judged if r.case == case), outcome, reason)
    # The runs of cases judged here; any other run (of no case, or of one out of scope in the
    # region) is listed, never lost.
    claimed = {label for case in names for label in cases[case].labels}
    others = [run for run in runs if not any(of_label(run, label) for label in claimed)]
    return Report(judged, out_of_scope, others)


def combine(case, per_root):
    ran = [r for r in per_root if r.runs]
    if not ran:
        return per_root[0] if per_root else Result(case)
    for outcome in ("FAIL", "PASS", "BLOCKED", "NOT_RUN"):
        chosen = [r for r in ran if r.result == outcome]
        if chosen:
            break
    r = chosen[-1]
    r.runs = [run for each in ran for run in each.runs]
    if len(ran) > 1:
        r.note = join(*(f"{each.root}：{each.result}" + (f"，{each.note}" if each.note else "") for each in ran))
    return r


def judge_case(row, exp, runs):
    deciding_label, supporting = row.labels[0], row.labels[1:]
    mine = [run for run in runs if any(of_label(run, label) for label in row.labels)]
    r = Result(row.case, runs=[f"{os.path.basename(run.root)}/{run.label} (exit {run.status})" for run in mine])
    deciding_runs = [run for run in mine if of_label(run, deciding_label)]
    if row.kind:
        # Another kind does not count (a reinstall that was a repeat); no kind at all, the run died early.
        r.note = join(*(f"{run.label} 是 {kind_of(run)}，不计入" for run in deciding_runs
                        if kind_of(run) not in (row.kind, None)))
        deciding_runs = [run for run in deciding_runs if kind_of(run) in (row.kind, None)]
    prep = [run for run in mine if not of_label(run, deciding_label)]
    if not deciding_runs:
        failed = [run.label for run in prep if run.status != 0]
        if failed:
            r.result = "BLOCKED"
            r.note = join(r.note, f"准备运行 {failed[-1]} 失败，判定运行没有运行")
        return r
    run = deciding_runs[-1]
    r.start, r.seconds = run.start, run.seconds
    tail = "/" if os.path.isdir(os.path.join(run.root, run.label)) else ".log"
    r.evidence = f"{os.path.basename(run.root)}/{run.label}{tail}"
    problems = check_run(run, row, exp, r)
    if row.kind and kind_of(run) is None:
        problems.insert(0, f"没有记下 kind（{run.label}/kind）：运行提前结束")
    latest_before = {label: [s for s in prep if of_label(s, label) and s.start < run.start][-1:] for label in supporting}
    blocking = [label for label, last in latest_before.items() if last and last[0].status != 0]
    after = [s.label for s in prep if s.start > run.start and s.status != 0]
    if blocking:
        r.result = "BLOCKED"
        r.note = join(r.note, f"准备运行 {'、'.join(blocking)} 在判定运行之前失败，判定运行不可信")
    else:
        r.result = "FAIL" if problems else "PASS"
    r.note = join(r.note, "；".join(problems), after and f"之后的准备运行 {'、'.join(after)} 失败")
    return r


def check_run(run, row, exp, r):
    """What keeps the run from passing, in words; sets the result's checks text."""
    problems = [] if run.status == 0 else [f"退出 {run.status}"]
    found = findings(run, row.files)
    passed = {f.name for f in found if f.outcome == "PASS"}
    failed = [f.name for f in found if f.outcome != "PASS"]
    names = passed | set(failed)
    r.checks_text = (f"{len(passed)} PASS" + (f"，{len(failed)} FAIL：{', '.join(failed[:5])}" if failed else "")
                     if found else f"退出 {run.status}")
    if failed:
        problems.append(f"{len(failed)} 项 FAIL")
    if exp.checks and not found:
        problems.append("没有检查结果")
    elif exp.checks:
        def due(check):
            return not check.optional and (check.prereq is None or any(pattern(check.prereq).match(n) for n in passed))
        missing = [c.name for c in exp.checks if due(c) and not any(c.pattern.match(n) for n in names)]
        if missing:
            problems.append("缺少：" + ", ".join(missing[:5]))
    for prefix, many, prereq in exp.prefixes:
        if prereq and prereq not in passed:
            continue
        have = len({n for n in names if n.startswith(prefix)} - {prereq})
        if have < many:
            problems.append(f"`{prefix}` 开头的检查期望 {many} 项，出现 {have} 项")
    if exp.count and len(names) < exp.count:
        problems.append(f"期望 {exp.count} 项，出现 {len(names)} 项")
    if exp.last_output:
        last = last_line(os.path.join(run.root, run.label + ".log"))
        if last is None or not pattern(exp.last_output).match(last):
            problems.append(f"最后输出不是 `{exp.last_output}`：{last}")
    if exp.last_line and exp.last_line[0]:
        last = last_line(os.path.join(run.root, run.label, exp.last_line[0]))
        if last is None or not pattern(exp.last_line[1]).match(last):
            problems.append(f"{exp.last_line[0]} 最后一行不是 `{exp.last_line[1]}`：{last}")
    for name, token in exp.has_lines:
        path = os.path.join(run.root, run.label, name)
        lines = [TIMESTAMP.sub("", l.strip()) for l in text_lines(path)] if os.path.exists(path) else []
        if not any(pattern(token).match(l) for l in lines):
            problems.append(f"{name} 没有 `{token}`")
    return problems


def override(r, outcome, reason):
    worse = {"PASS": ("BLOCKED", "FAIL"), "NOT_RUN": ("BLOCKED", "FAIL"), "BLOCKED": ("FAIL",), "FAIL": ()}
    if outcome not in worse[r.result]:
        sys.exit(f"{r.case}: an override may only make {r.result} worse, not {outcome}")
    r.result, r.note = outcome, join(r.note, f"人工判定：{reason}")


def tally(cases):
    return {o: sum(1 for r in cases if r.result == o) for o in OUTCOMES}


def markdown(report, region):
    lines = [f"**{region}**", "", "| 结果 | 用例数 |", "| --- | --- |"]
    lines += [f"| {o} | {n} |" for o, n in tally(report.cases).items()]
    lines += [f"| 合计（本区域必需） | {len(report.cases)} |", "",
              "| 用例 | 结果 | 检查 | 开始（UTC） | 用时 | 运行 | 证据 | 说明 |",
              "| --- | --- | --- | --- | --- | --- | --- | --- |"]
    for r in report.cases:
        took = f"{r.seconds // 60} 分 {r.seconds % 60} 秒" if r.seconds else "—"
        evidence = f"`{r.evidence}`" if r.evidence else "—"
        lines.append(f"| {r.case} | {r.result} | {r.checks_text or '—'} | {r.start or '—'} | {took} | "
                     f"{'；'.join(r.runs) or '—'} | {evidence} | {r.note or '—'} |")
    # A blank line before the list, as Markdown (markdownlint MD032) wants it.
    lines += ["", "本区域范围外的必需用例（按用例文档第 7 节的“区域”一列，在另一个区域执行）：", ""]
    lines += [f"- {case}：{cell}" for case, cell in report.out_of_scope] or ["- 无"]
    if report.other_runs:
        lines += ["", "其他运行（不判定本区域的任何必需用例）：" + "；".join(
            f"{os.path.basename(r.root)}/{r.label} (exit {r.status})" for r in report.other_runs)]
    lines += ["", "不在必需集合里的内容与理由见用例文档第 7 节。"]
    return "\n".join(lines) + "\n"


def summary(root, cases):
    first = {row.labels[0]: row.files for row in cases.values()}
    for run in read_runs([root]):
        files = next((f for label, f in first.items() if of_label(run, label)), ())
        rows = findings(run, files)
        for f in rows:
            print(f"{run.label}\t{flat(f.name)}\t{f.outcome}\t{flat(f.detail)[:300]}")
        if run.status != 0 and not any(f.outcome == "FAIL" for f in rows):
            print(f"{run.label}\t(exit status)\tFAIL\texit {run.status}; see {run.label}.log")
        elif run.status == 0 and not rows:
            print(f"{run.label}\t(exit status)\tPASS\texit 0")


def main():
    doc_default = os.path.join(HERE, "..", "..", "docs", "uat", "cases.md")
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    rep = commands.add_parser("report", help="one region's per-case results, as Markdown",
                              description="Judges every required case in scope in the region from the "
                                          "evidence roots and prints the counts and one row per case.")
    rep.add_argument("roots", nargs="+", metavar="evidence-root",
                     help="the UAT evidence directories of the region's environments (e.g. uat and plat)")
    rep.add_argument("--region", required=True,
                     help="the region reported: one the required set's 区域 column names")
    rep.add_argument("--overrides", metavar="TSV",
                     help="case, result, reason: a person's judgement that makes a result worse")
    summ = commands.add_parser("summary", help="summary.tsv of one evidence root (run-uat.sh's summary phase)",
                               description="Prints one row per check of every run in runs.tsv.")
    summ.add_argument("root", metavar="evidence-root", help="the UAT evidence directory")
    for sub in (rep, summ):
        sub.add_argument("--cases", default=os.path.join(HERE, "uat-cases.tsv"), metavar="TSV",
                         help="the case map (default: tests/acceptance/uat-cases.tsv)")
    rep.add_argument("--doc", default=doc_default, metavar="MD",
                     help="the UAT cases document (default: docs/uat/cases.md)")
    args = parser.parse_args()
    cases = read_cases(args.cases)
    if args.command == "summary":
        summary(args.root, cases)
        return
    required = read_required(args.doc)
    if args.region not in regions(required):
        rep.error(f"--region {args.region}: not one of the regions the required set names: "
                  f"{', '.join(regions(required))}")
    report = judge(args.roots, cases, read_expected(args.doc), required, args.region,
                   read_overrides(args.overrides))
    sys.stdout.write(markdown(report, args.region))


if __name__ == "__main__":
    main()
