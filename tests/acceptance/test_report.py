"""Unit tests of the UAT report generator (uat_report.py)."""
import importlib.util
import io
import os
import shutil
import tempfile
import unittest
from contextlib import redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("uat_report", os.path.join(HERE, "uat_report.py"))
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)
DOC = os.path.join(HERE, "..", "..", "docs", "uat", "cases.md")
CASE_MAP = os.path.join(HERE, "uat-cases.tsv")

CASES_MD = """# UAT

<!-- required-cases:start -->

| 用例 | 名称 | 配置 | 区域 | 执行 |
| --- | --- | --- | --- | --- |
| UAT-DEP-01 | 从零安装 | `--fixtures` | 至少一个区域（本次 `us-east-2` 的 `uate2`） | 运维 |
| UAT-DEP-06 | 原地升级 | `--fixtures` | 升级的那套环境所在的区域（`us-west-2` 的 `uat`） | `prep` |
| UAT-CAT-01 | Flink | `--fixtures` | 两个区域各一次 | `catalog` |
| UAT-CAT-06 | 授权 | `--fixtures` | 两个区域各一次 | `catalog` |
| UAT-CAT-10 | 提交故障 | `--fixtures` | 两个区域各一次 | `faults` |
| UAT-CAT-12 | 续期 | `--fixtures` | 两个区域各一次 | `renewal` |
| UAT-SRC-01 | CDC | `--fixtures` | 两个区域各一次 | `ingest` |
| UAT-TSK-01 | 任务 | `--fixtures` | 两个区域各一次 | `ingest` |
| UAT-ENT-01 | 入口 | `--fixtures` | 两个区域各一次 | `entry` |
| UAT-CAT-04 | session | `--fixtures` | 两个区域各一次 | `catalog` |

<!-- required-cases:end -->

#### UAT-DEP-01 从零安装

**期望结果**：`verdicts.tsv` 的 1 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `every-step-done` | 每步 ok |

#### UAT-DEP-06 原地升级

**期望结果**：退出 0，`<root>/reinstall/kind` 为 `upgrade`，`verdicts.tsv` 的 1 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `images-changed` | 换了镜像 |

#### UAT-CAT-01 Flink 经 colvenna-catalog 读写

**期望结果**：退出 0，2 行 `STEP` 都为 `OK`，最后输出 `Flink flow PASS (flow_cf<tag>)`。

| STEP | 期望 |
| --- | --- |
| `create-database` | 建库 |
| `batch-insert` | 写入 |

#### UAT-CAT-06 目录授权与审计

**期望结果**：退出 0，最后输出 `Authorization checks PASS: N checks`；每项一行 `<检查> expected=… actual=… OK`。

| 检查 | 期望 |
| --- | --- |
| `list-databases/owner` | 看得到 |
| `audit <调用>`：上面除 `list-databases/*` 之外的调用各一行 | 审计事件对 |

#### UAT-CAT-10 故障下的原子提交

**期望结果**：退出 0，`faults.txt` 最后一行为 `PASS`。下列任何一条不满足即失败。

| 检查（`faults.txt` 中的行） | 期望 |
| --- | --- |
| `the writers did not start` | 不出现 |

#### UAT-CAT-12 数据凭证续期

**期望结果**：退出 0，`run.txt` 最后一行为 `Renewal PASS`。

#### UAT-SRC-01 MySQL 与 PostgreSQL 的声明式 CDC

**期望结果**：退出 0，`<root>/cdc/verdicts.tsv` 的 4 项都为 PASS（`b-later` 仅在 `b` 通过后判定）。

| 检查 | 期望 |
| --- | --- |
| `a`、`b` | 两项 |
| `b-later` | 条件 |
| `rows-ods_cdc_<run>.orders` | 逐行相同 |
| `probe target exists: <键>` | 只在失败时出现：探测目标不存在 |

#### UAT-TSK-01 维护任务

**期望结果**：退出 0，`<root>/tasks/verdicts.tsv` 的 5 项都为 PASS（`x` 与 `y` 仅在 `z` 通过后，`notag-` 开头的另外 2 项仅在 `notag-failed` 通过后）。

| 检查 | 期望 |
| --- | --- |
| `z`、`x`、`y` | 三项 |
| `notag-failed` | 没有快照 |

#### UAT-ENT-01 Web 入口

**期望结果**：退出 0，`verdicts.tsv` 的 1 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `no-session` | 跳转登录 |

#### UAT-CAT-04 Flink session 集群

**期望结果**：退出 0，最后输出 `Flink session run PASS (flow_cx<tag>)`。`STEP` 与 UAT-CAT-01 的 2 项相同，都为 `OK`。

#### UAT-X-01 范围外

**期望结果**：退出 0。
"""

CASES_TSV = (
    "case\tphase\tlabels\tconfigs\tevidence\n"
    "UAT-DEP-01\toperator\tinstall\tfixtures\tinstall/verdicts.tsv\n"
    "UAT-DEP-06\tprep\treinstall\tboth\treinstall/kind (upgrade), reinstall/verdicts.tsv\n"
    "UAT-CAT-01\tcatalog\tcatalog-flink\tboth\tcatalog-flink/steps.txt\n"
    "UAT-CAT-06\tcatalog\tcatalog-authorization\tboth\tcatalog-authorization/authorization-checks.txt\n"
    "UAT-CAT-10\tfaults\tcatalog-commit-faults\tboth\tcatalog-commit-faults/faults.txt\n"
    "UAT-CAT-12\trenewal\trenewal,renewal-catalog,renewal-catalog-back\tboth\trenewal/run.txt\n"
    "UAT-SRC-01\tingest\tcdc\tfixtures\tcdc/verdicts.tsv\n"
    "UAT-TSK-01\tingest\ttasks\tfixtures\ttasks/verdicts.tsv\n"
    "UAT-ENT-01\tentry\tentry,entry-stack\tboth\tentry/verdicts.tsv\n"
    "UAT-CAT-04\tcatalog\tcatalog-session\tboth\tcatalog-session/steps.txt\n"
    "UAT-X-01\tsolo\tx\tfixtures\tx/verdicts.tsv\n"
)


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def verdicts(*rows):
    return "".join(f"2026-10-23T00:00:00Z\t{name}\t{outcome}\t\n" for name, outcome in rows)


class Fixture(unittest.TestCase):
    """An evidence root with no runs yet; each test adds the runs it needs."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)
        self.root = os.path.join(self.dir, "uat")
        self.doc = os.path.join(self.dir, "cases.md")
        self.map = os.path.join(self.dir, "cases.tsv")
        write(self.doc, CASES_MD)
        write(self.map, CASES_TSV)
        self.runs = []

    def run_of(self, label, status=0, start="2026-10-23T01:00:00Z", seconds=60, files=None, root=None):
        root = root or self.root
        self.runs.append((root, label, status, start, seconds))
        for name, content in (files or {}).items():
            write(os.path.join(root, name), content)

    def results(self, region="us-west-2", overrides=None, roots=None):
        by_root = {}
        for root, label, status, start, seconds in self.runs:
            by_root.setdefault(root, []).append(f"{label}\t{status}\t{start}\t{seconds}\n")
        for root, lines in by_root.items():
            write(os.path.join(root, "runs.tsv"), "".join(lines))
        os.makedirs(self.root, exist_ok=True)
        return report.judge(roots or [self.root], report.read_cases(self.map), report.read_expected(self.doc),
                            report.read_required(self.doc), region, overrides or {})

    def case(self, case_id, **kw):
        return next(r for r in self.results(**kw).cases if r.case == case_id)


class ReadDocTest(Fixture):
    def test_the_required_set_and_its_regions_are_read_between_the_markers(self):
        required = report.read_required(self.doc)
        self.assertEqual("UAT-DEP-01", required[0].case)
        self.assertEqual(["us-east-2"], required[0].regions)
        self.assertIsNone(required[2].regions)  # 两个区域各一次

    def test_no_markers_is_an_error(self):
        path = os.path.join(self.dir, "none.md")
        write(path, "# nothing\n")
        with self.assertRaises(SystemExit):
            report.read_required(path)

    def test_a_region_cell_naming_no_region_is_an_error(self):
        write(self.doc, CASES_MD.replace("至少一个区域（本次 `us-east-2` 的 `uate2`）", "看情况"))
        with self.assertRaises(SystemExit):
            report.read_required(self.doc)

    def test_a_region_cell_worded_otherwise_is_an_error_never_a_region(self):
        write(self.doc, CASES_MD.replace("至少一个区域（本次 `us-east-2` 的 `uate2`）", "`us-west-2` 以外的区域"))
        with self.assertRaises(SystemExit):
            report.read_required(self.doc)

    def test_the_regions_a_report_may_name_are_the_tables(self):
        self.assertEqual(["us-east-2", "us-west-2"], report.regions(report.read_required(self.doc)))

    def test_expected_results_are_read_with_counts_conditions_and_last_lines(self):
        expected = report.read_expected(self.doc)
        src = expected["UAT-SRC-01"]
        self.assertEqual(4, src.count)
        self.assertEqual({"probe target exists: <键>"}, {c.name for c in src.checks if c.optional})
        self.assertEqual({"b-later": "b"}, {c.name: c.prereq for c in src.checks if c.prereq})
        tsk = expected["UAT-TSK-01"]
        self.assertEqual({"x": "z", "y": "z"}, {c.name: c.prereq for c in tsk.checks if c.prereq})
        self.assertEqual([("notag-", 2, "notag-failed")], tsk.prefixes)
        self.assertEqual("Flink flow PASS (flow_cf<tag>)", expected["UAT-CAT-01"].last_output)
        self.assertEqual(("faults.txt", "PASS"), expected["UAT-CAT-10"].last_line)
        self.assertEqual([], expected["UAT-CAT-10"].checks)  # a table of lines, not of checks


class JudgeTest(Fixture):
    def test_the_last_rerun_of_the_deciding_label_decides(self):
        self.run_of("cdc", 1, "2026-10-23T02:00:00Z", files={"cdc/verdicts.tsv": verdicts(("a", "PASS"), ("b", "FAIL"))})
        self.run_of("cdc-2", 0, "2026-10-23T04:00:00Z", 3700, files={"cdc-2/verdicts.tsv": verdicts(
            ("a", "PASS"), ("b", "PASS"), ("b-later", "PASS"), ("rows-ods_cdc_cdcb1023.orders", "PASS"))})
        src = self.case("UAT-SRC-01")
        self.assertEqual("PASS", src.result, src.note)
        self.assertEqual(["uat/cdc (exit 1)", "uat/cdc-2 (exit 0)"], src.runs)
        self.assertEqual(("2026-10-23T04:00:00Z", 3700), (src.start, src.seconds))
        self.assertEqual("uat/cdc-2/", src.evidence)

    def test_a_missing_check_fails_and_a_conditional_one_is_required_once_its_prerequisite_passed(self):
        self.run_of("cdc", files={"cdc/verdicts.tsv": verdicts(("a", "PASS"), ("b", "PASS"),
                                                               ("rows-ods_cdc_x.orders", "PASS"))})
        src = self.case("UAT-SRC-01")
        self.assertEqual("FAIL", src.result)
        self.assertIn("b-later", src.note)

    def test_a_conditional_check_whose_prerequisite_failed_may_be_absent(self):
        self.run_of("cdc", 1, files={"cdc/verdicts.tsv": verdicts(("a", "PASS"), ("b", "FAIL"),
                                                                  ("rows-ods_cdc_x.orders", "PASS"))})
        src = self.case("UAT-SRC-01")
        self.assertEqual("FAIL", src.result)
        self.assertNotIn("b-later", src.note)

    def test_prefixed_checks_must_be_as_many_as_announced(self):
        rows = [("z", "PASS"), ("x", "PASS"), ("y", "PASS"), ("notag-failed", "PASS"), ("notag-alert", "PASS")]
        self.run_of("tasks", files={"tasks/verdicts.tsv": verdicts(*rows)})
        tsk = self.case("UAT-TSK-01")
        self.assertEqual("FAIL", tsk.result)
        self.assertIn("notag-", tsk.note)

    def test_fewer_checks_than_announced_fail(self):
        write(self.doc, CASES_MD.replace("`verdicts.tsv` 的 1 项都为 PASS。\n\n| 检查 | 期望 |\n| --- | --- |\n"
                                         "| `no-session` | 跳转登录 |", "`verdicts.tsv` 的 2 项都为 PASS。"))
        self.run_of("entry", files={"entry/verdicts.tsv": verdicts(("no-session", "PASS"))})
        ent = self.case("UAT-ENT-01")
        self.assertEqual("FAIL", ent.result)
        self.assertIn("期望 2 项", ent.note)

    def test_named_checks_are_read_from_the_case_evidence_file_when_there_are_no_verdicts(self):
        self.run_of("catalog-flink", files={
            "catalog-flink/steps.txt": "STEP create-database OK 52ms\nSTEP batch-insert OK 16546ms 100 rows\n",
            "catalog-flink.log": "...\nFlink flow PASS (flow_cfa1023)\n"})
        cat = self.case("UAT-CAT-01")
        self.assertEqual(("PASS", "2 PASS"), (cat.result, cat.checks_text), cat.note)

    def test_named_checks_with_no_result_anywhere_fail_even_on_exit_zero(self):
        self.run_of("catalog-flink", files={"catalog-flink.log": "Flink flow PASS (flow_cfa1023)\n"})
        cat = self.case("UAT-CAT-01")
        self.assertEqual("FAIL", cat.result)
        self.assertIn("没有检查结果", cat.note)

    def test_the_announced_last_output_must_be_there(self):
        self.run_of("catalog-flink", files={
            "catalog-flink/steps.txt": "STEP create-database OK\nSTEP batch-insert OK\n",
            "catalog-flink.log": "Flink flow FAIL\n"})
        self.assertEqual("FAIL", self.case("UAT-CAT-01").result)

    def test_expected_text_with_spaces_does_not_become_the_check_name(self):
        self.run_of("catalog-authorization", files={
            "catalog-authorization/authorization-checks.txt":
                'list-databases/owner expected=200 and db in b["databases"] actual=200 OK\n'
                "audit get-table/owner request 1f expected=GET_TABLE/t/SUCCEEDED/200 actual=('a', 'GET_TABLE') OK\n",
            "catalog-authorization.log": "Authorization checks PASS: 2 checks\n"})
        cat = self.case("UAT-CAT-06")
        self.assertEqual(("PASS", "2 PASS"), (cat.result, cat.checks_text), cat.note)
        names = [c.name for c in report.read_expected(self.doc)["UAT-CAT-06"].checks]
        self.assertEqual(["list-databases/owner", "audit <调用>"], names)  # not the prose's names

    def test_a_last_line_case_reads_its_file_after_the_timestamp(self):
        self.run_of("catalog-commit-faults", files={
            "catalog-commit-faults/faults.txt": "2026-10-23T01:00:00Z start\n2026-10-23T01:20:00Z PASS\n"})
        self.assertEqual("PASS", self.case("UAT-CAT-10").result)
        self.runs.clear()
        self.run_of("catalog-commit-faults-2", files={
            "catalog-commit-faults-2/faults.txt": "2026-10-23T02:00:00Z FAIL: the writers did not start\n"})
        self.assertEqual("FAIL", self.case("UAT-CAT-10").result)

    def test_a_supporting_run_that_failed_before_the_deciding_run_blocks_it(self):
        self.run_of("renewal-catalog", 1, "2026-10-23T01:00:00Z")
        self.run_of("renewal", 0, "2026-10-23T02:00:00Z", files={"renewal/run.txt": "Renewal PASS\n"})
        renewal = self.case("UAT-CAT-12")
        self.assertEqual("BLOCKED", renewal.result)
        self.assertIn("renewal-catalog", renewal.note)

    def test_a_supporting_run_that_failed_after_the_deciding_run_is_only_noted(self):
        self.run_of("renewal-catalog", 0, "2026-10-23T01:00:00Z")
        self.run_of("renewal", 0, "2026-10-23T02:00:00Z", files={"renewal/run.txt": "Renewal PASS\n"})
        self.run_of("renewal-catalog-back", 1, "2026-10-23T04:00:00Z")
        renewal = self.case("UAT-CAT-12")
        self.assertEqual("PASS", renewal.result)
        self.assertIn("renewal-catalog-back", renewal.note)

    def test_only_a_failed_supporting_run_and_no_deciding_run_is_blocked(self):
        self.run_of("entry-stack", 1)
        self.assertEqual("BLOCKED", self.case("UAT-ENT-01").result)

    def test_a_reinstall_that_was_a_repeat_does_not_count_for_the_upgrade(self):
        self.run_of("reinstall", files={"reinstall/kind": "repeat\n",
                                        "reinstall/verdicts.tsv": verdicts(("images-changed", "PASS"))})
        dep = self.case("UAT-DEP-06")
        self.assertEqual("NOT_RUN", dep.result)
        self.assertIn("repeat", dep.note)
        self.runs.clear()
        self.run_of("reinstall-2", files={"reinstall-2/kind": "upgrade\n",
                                          "reinstall-2/verdicts.tsv": verdicts(("images-changed", "PASS"))})
        self.assertEqual("PASS", self.case("UAT-DEP-06").result)

    def test_an_announced_count_with_no_result_at_all_fails(self):
        self.run_of("catalog-session", files={"catalog-session.log": "Flink session run PASS (flow_cya1023)\n"})
        cat = self.case("UAT-CAT-04")
        self.assertEqual("FAIL", cat.result)
        self.assertIn("期望 2 项", cat.note)
        self.runs.clear()
        self.run_of("catalog-session-2", files={
            "catalog-session-2/steps.txt": "STEP create-database OK\nSTEP batch-insert OK\n",
            "catalog-session-2.log": "Flink session run PASS (flow_cza1023)\n"})
        self.assertEqual("PASS", self.case("UAT-CAT-04").result)

    def test_prose_lines_of_an_evidence_file_are_not_checks(self):
        found = report.parse_line("2026-10-23T01:00:00Z A: PASS, writers' checks passed and t1 is consistent", True)
        self.assertEqual([], found)
        self.assertEqual(["catalog-spread"], [f.name for f in report.parse_line("catalog-spread OK replicas on 2 nodes", True)])

    def test_a_reinstall_that_wrote_no_kind_died_early_and_fails(self):
        self.run_of("reinstall", 1)
        dep = self.case("UAT-DEP-06")
        self.assertEqual("FAIL", dep.result)
        self.assertIn("kind", dep.note)

    def test_a_case_with_no_run_is_not_run_and_stays_in_the_count(self):
        results = self.results()
        self.assertEqual("NOT_RUN", next(r for r in results.cases if r.case == "UAT-ENT-01").result)
        self.assertEqual(9, len(results.cases))  # DEP-01 is out of scope in us-west-2

    def test_cases_out_of_scope_in_the_region_leave_its_denominator_with_their_reason(self):
        west = self.results("us-west-2")
        self.assertNotIn("UAT-DEP-01", {r.case for r in west.cases})
        self.assertEqual("UAT-DEP-01", west.out_of_scope[0][0])
        self.assertIn("us-east-2", west.out_of_scope[0][1])
        east = self.results("us-east-2")
        self.assertIn("UAT-DEP-01", {r.case for r in east.cases})
        self.assertNotIn("UAT-DEP-06", {r.case for r in east.cases})

    def test_runs_of_several_roots_are_judged_together(self):
        plat = os.path.join(self.dir, "plat")
        self.run_of("install", root=plat, files={"install/verdicts.tsv": verdicts(("every-step-done", "PASS"))})
        dep = self.case("UAT-DEP-01", region="us-east-2", roots=[self.root, plat])
        self.assertEqual(("PASS", "plat/install/", ["plat/install (exit 0)"]), (dep.result, dep.evidence, dep.runs))

    def test_each_root_is_judged_alone_and_a_failure_in_any_wins(self):
        plat = os.path.join(self.dir, "plat")
        # The supporting run failed in uat only; plat's run of the case is its own.
        self.run_of("entry-stack", 1, "2026-10-23T01:00:00Z")
        self.run_of("entry", 0, "2026-10-23T02:00:00Z", root=plat,
                    files={"entry/verdicts.tsv": verdicts(("no-session", "PASS"))})
        roots = [self.root, plat]
        ent = self.case("UAT-ENT-01", roots=roots)
        self.assertEqual("PASS", ent.result)
        self.assertIn("uat：BLOCKED", ent.note)
        self.runs = [run for run in self.runs if run[1] != "entry-stack"]
        os.remove(os.path.join(self.root, "runs.tsv"))
        self.run_of("entry", 1, "2026-10-23T03:00:00Z", files={"entry/verdicts.tsv": verdicts(("no-session", "FAIL"))})
        ent = self.case("UAT-ENT-01", roots=roots)
        self.assertEqual(("FAIL", "uat/entry/"), (ent.result, ent.evidence))

    def test_a_label_that_merely_starts_like_a_case_label_is_another_run(self):
        self.run_of("entry-stack-back-final", 0)
        self.run_of("entry-2", 0, files={"entry-2/verdicts.tsv": verdicts(("no-session", "PASS"))})
        results = self.results()
        self.assertEqual("PASS", next(r for r in results.cases if r.case == "UAT-ENT-01").result)
        self.assertEqual(["entry-stack-back-final"], [r.label for r in results.other_runs])

    def test_a_run_of_a_case_out_of_scope_is_listed_as_another_run(self):
        # UAT-DEP-01 is required in us-east-2 only: its run in us-west-2 judges nothing here, but
        # the report does not lose it.
        self.run_of("install", 1)
        results = self.results(region="us-west-2")
        self.assertNotIn("UAT-DEP-01", {r.case for r in results.cases})
        self.assertEqual(["install"], [r.label for r in results.other_runs])
        self.assertIn("uat/install (exit 1)", report.markdown(results, "us-west-2"))

    def test_out_of_scope_cases_are_not_judged(self):
        self.assertNotIn("UAT-X-01", {r.case for r in self.results().cases})


class OverrideTest(Fixture):
    def test_an_override_makes_a_result_worse_and_keeps_the_original_note(self):
        rows = self.results(overrides={"UAT-ENT-01": ("BLOCKED", "the entry stack broke: #999")}).cases
        ent = next(r for r in rows if r.case == "UAT-ENT-01")
        self.assertEqual("BLOCKED", ent.result)
        self.assertIn("#999", ent.note)

    def test_an_override_of_a_case_not_judged_in_the_region_is_an_error(self):
        for case in ("UAT-NONE-01", "UAT-DEP-01"):  # unknown; out of scope in us-west-2
            with self.assertRaises(SystemExit):
                self.results(overrides={case: ("FAIL", "?")})

    def test_an_override_never_gives_a_pass_or_hides_a_failure(self):
        self.run_of("cdc", 1, files={"cdc/verdicts.tsv": verdicts(("a", "FAIL"))})
        for outcome in ("PASS", "NOT_RUN", "BLOCKED"):
            with self.assertRaises(SystemExit):
                self.results(overrides={"UAT-SRC-01": (outcome, "trust me")})


class SummaryTest(Fixture):
    def test_the_summary_rows_come_from_the_same_parser(self):
        self.run_of("catalog-authorization", files={
            "catalog-authorization/authorization-checks.txt":
                'list-databases/owner expected=200 and db in b["databases"] actual=200 OK\n'})
        self.run_of("catalog-flink", files={"catalog-flink/steps.txt": "STEP create-database OK 52ms\tdb\thost\n"})
        self.run_of("spark-image", 1)
        self.results()
        out = io.StringIO()
        with redirect_stdout(out):
            report.summary(self.root, report.read_cases(self.map))
        lines = out.getvalue().splitlines()
        self.assertIn("catalog-authorization\tlist-databases/owner\tPASS\t", "\n".join(lines))
        self.assertTrue(all(len(line.split("\t")) == 4 for line in lines), lines)
        self.assertIn("spark-image\t(exit status)\tFAIL\texit 1; see spark-image.log", lines)


class MarkdownTest(Fixture):
    def test_the_report_lists_every_case_the_counts_and_the_scope(self):
        text = report.markdown(self.results(), "us-west-2")
        for case in ("UAT-DEP-06", "UAT-CAT-01", "UAT-ENT-01"):
            self.assertIn(f"| {case} |", text)
        self.assertIn("| NOT_RUN | 9 |", text)
        self.assertIn("| 合计（本区域必需） | 9 |", text)
        self.assertNotIn("``", text)  # a case that did not run has "—", not an empty evidence cell
        self.assertIn("本区域范围外", text)
        self.assertIn("第 7 节", text)
        # Pasted into a report, the list passes markdownlint: a blank line before it.
        self.assertRegex(text, r"在另一个区域执行）：\n\n- ")


class RealDocumentTest(unittest.TestCase):
    """The repository's own cases.md and uat-cases.tsv: every required case can be judged."""

    def test_every_required_case_has_a_region_rule_a_case_map_row_and_something_to_judge(self):
        required = report.read_required(DOC)
        expected = report.read_expected(DOC)
        cases = report.read_cases(CASE_MAP)
        for item in required:
            with self.subTest(case=item.case):
                self.assertIn(item.case, cases)
                exp = expected[item.case]
                self.assertTrue(exp.checks or exp.count or exp.last_output or exp.last_line,
                                f"{item.case}: no check, count or last line to judge")

    def test_the_upgrade_case_requires_an_upgrade_run(self):
        self.assertEqual("upgrade", report.read_cases(CASE_MAP)["UAT-DEP-06"].kind)


if __name__ == "__main__":
    unittest.main()
