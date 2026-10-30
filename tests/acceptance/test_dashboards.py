"""Unit tests of the dashboards run's judging of Doris measurements (dashboards/dashboards.py)."""
import importlib.util
import json
import os
import shutil
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("dashboards", os.path.join(HERE, "dashboards", "dashboards.py"))
dashboards = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dashboards)

AT = 1792058400000  # 2026-10-15T10:00:00Z in milliseconds


class Files(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)
        self.n = 0

    def write(self, lines, suffix=".log"):
        self.n += 1
        path = os.path.join(self.dir, f"{self.n}{suffix}")
        with open(path, "w") as f:
            f.write("\n".join(lines) + "\n")
        return path

    def out(self):
        self.n += 1
        return os.path.join(self.dir, f"{self.n}.json")

    @staticmethod
    def read(path):
        with open(path) as f:
            return json.load(f)


def q(kind, due, sent, end, status="OK"):
    """A load pod's line: sent and end are ms, written as offsets from due."""
    return f"{kind} {due} {sent - due} {end - due} {status}"


def done(lines):
    """The load pod's last line, counting the lines of each kind before it."""
    kinds = [line.split()[0] for line in lines]
    return f"load done totals={kinds.count('totals')} hot={kinds.count('hot')} lookup={kinds.count('lookup')}"


class LoadTest(Files):
    def write_load(self, lines, last=True):
        return self.write(lines + ([done(lines)] if last else []))

    def test_times_from_when_each_query_was_due_and_during_the_burst(self):
        lines = [
            q("totals", AT, AT + 2, AT + 20),
            q("totals", AT + 5000, AT + 5001, AT + 7000),
            q("hot", AT, AT + 3, AT + 30),
            q("lookup", AT, AT + 2, AT + 900),
        ]
        out = self.out()
        code = dashboards.main(["load", self.write(lines + ["junk line", done(lines)]), out,
                                "--burst", str(AT + 1000), str(AT + 6000)])
        result = self.read(out)
        self.assertEqual(2, result["dashboard"]["samples"])
        self.assertEqual(2.0, result["dashboard"]["p99"])
        self.assertEqual(2.0, result["dashboard_during_burst"]["p95"])
        self.assertEqual(0.03, result["internal"]["p95"])
        self.assertEqual({"sent": 2, "late": 0, "max_late_ms": 2, "kept": True},
                         result["schedule"]["groups"]["dashboard"])
        self.assertEqual(1, result["schedule"]["groups"]["dashboard_during_burst"]["sent"])
        self.assertTrue(result["schedule"]["kept"])
        self.assertTrue(result["schedule"]["log"]["whole"])
        # Within the targets, but fewer than the acceptance contract's 10,000: observed, and no pass.
        self.assertTrue(result["dashboard"]["within"])
        self.assertFalse(result["dashboard"]["judged"])
        self.assertEqual(1, code)

    def test_enough_samples_within_the_targets_pass_and_an_error_fails(self):
        many = [q("totals", AT + i, AT + i + 1, AT + i + 50) for i in range(10_000)]
        many += [q("hot", AT + i, AT + i + 1, AT + i + 20) for i in range(10_000)]
        out = self.out()
        self.assertEqual(0, dashboards.main(["load", self.write_load(many), out]))
        skipped = many + [q("totals", AT, AT, AT, "SKIP")]
        self.assertEqual(1, dashboards.main(["load", self.write_load(skipped), out]))
        self.assertEqual({"totals SKIP": 1}, self.read(out)["errors"])
        self.assertEqual(10_001, self.read(out)["dashboard"]["samples"])
        slow = many + [q("totals", AT + i, AT + i, AT + i + 12000) for i in range(200)]
        self.assertEqual(1, dashboards.main(["load", self.write_load(slow), out]))

    def test_a_query_that_failed_or_was_not_sent_never_answered(self):
        # 2 % failed (the query timed out, the connection was refused): the dashboard's p99 is
        # too late, not computed over the ones that answered.
        log = [q("totals", AT + i, AT + i + 1, AT + i + (60_000 if i % 50 == 0 else 50),
                 "ERR timed_out" if i % 50 == 0 else "OK") for i in range(10_000)]
        log += [q("hot", AT + i, AT + i + 1, AT + i + 20) for i in range(10_000)]
        out = self.out()
        self.assertEqual(1, dashboards.main(["load", self.write_load(log), out]))
        result = self.read(out)
        self.assertEqual(float("inf"), result["dashboard"]["p99"])
        self.assertFalse(result["dashboard"]["within"])
        self.assertEqual({"totals ERR": 200}, result["errors"])

    def test_a_log_that_lost_lines_judges_nothing(self):
        # The kubelet kept only the end of the pod's output: the last line counts more than there
        # are, or there is no last line at all.
        many = [q("totals", AT + i, AT + i + 1, AT + i + 50) for i in range(10_000)]
        many += [q("hot", AT + i, AT + i + 1, AT + i + 20) for i in range(10_000)]
        cases = [([*many[5000:], done(many)], {"totals": 5000, "hot": 10_000, "lookup": 0},
                  {"totals": 10_000, "hot": 10_000, "lookup": 0}),
                 (many, {"totals": 10_000, "hot": 10_000, "lookup": 0}, None),
                 # The last line of a pod older than the counts.
                 ([*many, "load done"], {"totals": 10_000, "hot": 10_000, "lookup": 0}, {})]
        for log, recorded, written in cases:
            out = self.out()
            self.assertEqual(1, dashboards.main(["load", self.write(log), out]))
            result = self.read(out)
            self.assertEqual((recorded, written, False), (result["schedule"]["log"]["recorded"],
                             result["schedule"]["log"]["written"], result["schedule"]["log"]["whole"]))
            self.assertFalse(result["schedule"]["kept"])
            self.assertFalse(result["dashboard"]["judged"])

    def test_a_send_a_millisecond_early_is_a_line_like_any(self):
        # The pause rounds to the millisecond: a query can start a millisecond before it was due.
        lines = [q("totals", AT, AT - 1, AT + 30), q("hot", AT, AT, AT + 20)]
        out = self.out()
        dashboards.main(["load", self.write(lines + [done(lines)]), out])
        result = self.read(out)
        self.assertEqual(-1, result["schedule"]["groups"]["dashboard"]["max_late_ms"])
        self.assertTrue(result["schedule"]["log"]["whole"])

    def test_a_loop_behind_its_schedule_judges_no_query_time(self):
        def run(late, burst=(0, 0)):
            log = [q("totals", AT + i, AT + i + (1500 if late(i) else 1), AT + i + (1600 if late(i) else 50))
                   for i in range(10_000)]
            log += [q("hot", AT + i, AT + i + 1, AT + i + 20) for i in range(10_000)]
            out = self.out()
            code = dashboards.main(["load", self.write_load(log), out, "--burst", *map(str, burst)])
            return code, self.read(out)

        # Half a percent of the dashboard queries sent more than a second late: kept.
        code, kept = run(lambda i: i % 200 == 0)
        self.assertEqual(0, code)
        self.assertTrue(kept["schedule"]["kept"])
        self.assertEqual(50, kept["schedule"]["groups"]["dashboard"]["late"])
        # 4 %: the times carry the loop's own lag, and say nothing of Doris.
        code, behind = run(lambda i: i % 25 == 0)
        self.assertEqual(1, code)
        self.assertFalse(behind["schedule"]["kept"])
        self.assertEqual(1500, behind["schedule"]["groups"]["dashboard"]["max_late_ms"])
        for key in ("dashboard", "internal", "dashboard_during_burst"):
            self.assertFalse(behind[key]["judged"], key)
            self.assertFalse(behind[key]["pass"], key)
            self.assertEqual("schedule", behind[key]["unjudged_by"], key)
        # The times are still reported, from when each query was due.
        self.assertTrue(behind["dashboard"]["within"])
        self.assertEqual(1.6, behind["dashboard"]["max"])
        # Each group on its own: 0.2 % late over the run, all of it in the burst, 2 % of the burst's.
        code, burst = run(lambda i: 2000 <= i < 3000 and i % 50 == 0, burst=(AT + 2000, AT + 3000))
        self.assertTrue(burst["schedule"]["groups"]["dashboard"]["kept"])
        self.assertFalse(burst["schedule"]["groups"]["dashboard_during_burst"]["kept"])
        self.assertFalse(burst["schedule"]["kept"])
        self.assertFalse(burst["dashboard"]["judged"])


class FreshTest(Files):
    def test_a_marker_is_seen_when_a_poll_holds_it_and_all_before_it(self):
        base = 10_000_000
        log = self.write([
            f"mark {base} {AT} {AT + 3}",
            f"mark {base + 1} {AT + 100} {AT + 103}",
            f"mark {base + 2} {AT + 200} {AT + 203}",
            # The view holds the first at 60 s; at 90 s a gap (the third without the second),
            # which does not count; at 120 s all three.
            f"live {AT + 59000} {AT + 60000} OK 1 {base}",
            f"live {AT + 89000} {AT + 90000} OK 2 {base + 2}",
            f"live {AT + 119000} {AT + 120000} OK 3 {base + 2}",
            f"mv {AT + 179000} {AT + 180000} OK 3 {base + 2}",
            f"hotm {AT + 179000} {AT + 180000} OK 3 {base + 2}",
            f"dws {AT + 299000} {AT + 300000} OK 2 {base + 1}",
        ])
        changelog = self.write([json.dumps({"type": "CHANGE", "kind": "+I", "committedAt": AT + 30000 + i,
                                            "row": {"id": str(base + i)}}) for i in range(3)], ".jsonl")
        out = self.out()
        dashboards.main(["fresh", log, changelog, out, "--window", str(AT), str(AT + 1000),
                         "--paimon-skew-ms", "40"])
        r = self.read(out)
        self.assertEqual(3, r["markers"])
        self.assertEqual(1, r["gaps"]["live"])
        # 60 s for the first; the others with the poll that held them all, at 120 s.
        self.assertEqual(119.8, r["source_to_external"]["p50"])
        self.assertEqual(119.9, r["source_to_external"]["p99"])
        self.assertEqual(89.998, r["paimon_to_external"]["p50"])
        self.assertEqual(149.999, r["paimon_to_internal"]["p50"])
        # The third marker never reached DWS: too late, whatever the target.
        self.assertEqual(float("inf"), r["source_to_dws_dashboard"]["max"])
        self.assertTrue(r["clock"]["paimon_valid"])

    def test_a_clock_off_makes_no_sample_and_a_growing_backlog_is_no_steady_state(self):
        log = self.write([f"mark 10000000 {AT} {AT + 5000}", f"live {AT} {AT + 10} OK 1 10000000"])
        changelog = self.write([], ".jsonl")
        out = self.out()
        self.assertEqual(1, dashboards.main(["fresh", log, changelog, out, "--window", str(AT), str(AT + 1)]))
        self.assertEqual(0, self.read(out)["source_to_external"]["samples"])
        # Clocks in step, but the Flink pods' not measured: the Paimon paths have no samples.
        log = self.write([f"mark 10000000 {AT} {AT + 5}", f"live {AT} {AT + 10} OK 1 10000000"])
        dashboards.main(["fresh", log, changelog, out, "--window", str(AT), str(AT + 1)])
        self.assertFalse(self.read(out)["clock"]["paimon_valid"])
        # Latency growing from 10 s to 5 minutes over the window: the backlog grew.
        growing = []
        for i in range(20):
            at, mid = AT + i * 60_000, 10_000_000 + i
            growing += [f"mark {mid} {at} {at + 5}", f"live {at} {at + 10_000 + i * 15_000} OK {i + 1} {mid}"]
        log = self.write(growing)
        dashboards.main(["fresh", log, changelog, out, "--window", str(AT), str(AT + 20 * 60_000)])
        self.assertFalse(self.read(out)["steady"]["steady"])


class BurstTest(Files):
    def test_how_many_ran_at_once_in_each_group(self):
        audit = self.write([
            # Two ran at once in the dashboard's group; one waited 4 s first; one was turned away.
            f"{AT}\tcolvenna-data.adhoc.explorer\twg_a\tEOF\t5000\t0\t",
            f"{AT + 100}\tcolvenna-data.adhoc.scout\twg_a\tEOF\t5000\t0\t",
            f"{AT + 200}\tcolvenna-data.adhoc.scout\twg_a\tEOF\t9000\t4900\t",
            f"{AT + 300}\tcolvenna-data.adhoc.explorer\twg_a\tERR\t14\t0\tquery waiting queue is full",
            f"{AT}\tcolvenna-data.adhoc.escapee\tnormal\tEOF\t3000\t0\t",
            f"{AT + 50}\tcolvenna-data.adhoc.escapee\tnormal\tERR\t10000\t10000\tquery queue timeout",
        ])
        out = self.out()
        self.assertEqual(0, dashboards.main(["burst", audit, out, "wg_a=2", "normal=2"]))
        r = self.read(out)
        self.assertEqual(2, r["wg_a"]["at_once"])
        self.assertEqual((1, 1), (r["wg_a"]["queued"], r["wg_a"]["turned_away"]))
        self.assertEqual(1, dashboards.main(["burst", audit, out, "wg_a=1", "normal=2"]))
        # Asked to be reached, a limit nothing came near fails: the burst did not test it.
        calm = self.write([f"{AT}\tcolvenna-data.adhoc.escapee\tnormal\tEOF\t3000\t0\t"])
        self.assertEqual(0, dashboards.main(["burst", calm, out, "normal<=2"]))
        self.assertEqual(1, dashboards.main(["burst", calm, out, "normal=2"]))

    def test_the_fes_count_judges_what_ran_at_once(self):
        # Four in flight at once by the log: their times include the FE's planning, before the
        # queue; the FE counted two running and two waiting, a sample every half second.
        audit = self.write([f"{AT + i * 10}\tcolvenna-data.adhoc.explorer\twg_a\tEOF\t2000\t0\t" for i in range(4)])
        window = [str(AT), str(AT + 10_000)]

        def counts(running, waiting, every=500, until=10_000):
            return self.write([f"{AT + t} wg_a {running} {waiting}" for t in range(0, until + 1, every)]
                              + ["noise", f"{AT} normal 0 0"])

        out = self.out()
        self.assertEqual(1, dashboards.main(["burst", audit, out, "wg_a=2"]))
        ok = ["burst", audit, out, "wg_a=2", "--window", *window, "--sampled"]
        self.assertEqual(0, dashboards.main(ok + [counts(2, 2)]))
        r = self.read(out)["wg_a"]
        self.assertEqual((2, 4, 2), (r["at_once"], r["logged_overlap"], r["fe"]["waiting"]))
        # More running than the limit; the limit never reached, or nothing waiting, for "=";
        # a count that stops early or samples too seldom; no count of the group at all.
        self.assertEqual(1, dashboards.main(ok + [counts(3, 0)]))
        self.assertEqual(1, dashboards.main(ok + [counts(1, 2)]))
        self.assertEqual(1, dashboards.main(ok + [counts(2, 0)]))
        self.assertEqual(0, dashboards.main(["burst", audit, out, "wg_a<=2", "--window", *window,
                                             "--sampled", counts(1, 0)]))
        self.assertEqual(1, dashboards.main(ok + [counts(2, 2, until=4000)]))
        self.assertEqual(1, dashboards.main(ok + [counts(2, 2, every=2000)]))
        self.assertEqual(1, dashboards.main(["burst", audit, out, "wg_a<=2", "--window", *window,
                                             "--sampled", self.write([f"{AT} other 1 0"])]))


class BurstWindowTest(unittest.TestCase):
    def test_the_window_opens_once_the_sampler_counts(self):
        # The FE's counts must cover the burst's window from its start (#293): the sampler starts,
        # its first count is waited for, then the window opens and the burst starts.
        with open(os.path.join(HERE, "dashboards", "run-dashboards.sh")) as f:
            script = f.read()
        order = [script.index(step) for step in ('groups_sampler "groups-$run"', 'sampling "groups-$run"',
                                                 'burst_from="$(date +%s%3N)"', 'bi_pod "burst-$run"')]
        self.assertEqual(sorted(order), order)


class AuditTest(Files):
    def test_every_user_and_path_asked_for_was_logged(self):
        audit = self.write(["a\tEOF\tregion_totals\t12", "a\tERR\tcustomers_hot\t1", "o\tEOF\tcustomers_hot\t3"])
        self.assertEqual(0, dashboards.main(["audit", audit, "a:region_totals:EOF", "a:customers_hot:ERR",
                                             "o:customers_hot:EOF"]))
        self.assertEqual(1, dashboards.main(["audit", audit, "o:region_totals:ERR"]))


class RecoveredTest(Files):
    def polls(self, lag_ms):
        lines = [f"mark 7 {AT - 1000} {AT - 990}", f"mark 9 {AT + 61000 - lag_ms} {AT + 61010 - lag_ms}"]
        for kind in ("totals", "hot", "live", "mv", "dws", "hotm"):
            lines += [f"{kind} {AT + 1000} {AT + 1100} ERR x",
                      f"{kind} {AT + 60000} {AT + 60100} OK 5 7",
                      f"{kind} {AT + 61000} {AT + 61100} OK 5 9",
                      f"{kind} {AT + 62000} {AT + 62100} OK 5 9"]
        return self.write(lines)

    def test_back_for_good_fresh_and_within_its_lag(self):
        self.assertEqual(0, dashboards.main(["recovered", self.polls(10_000), str(AT), "8"]))
        # A path still behind the marker committed at the injection has not recovered.
        self.assertEqual(1, dashboards.main(["recovered", self.polls(10_000), str(AT), "10"]))
        # Nor one whose answer is newer than the injection but still lags past its p95.
        self.assertEqual(1, dashboards.main(["recovered", self.polls(400_000), str(AT), "8"]))


class RowsTest(Files):
    def test_rows_by_key_on_the_columns_named(self):
        doris = self.write(["region\torders\tamount", "north\t2\t10.50", "south\t1\t3"], ".tsv")
        lake = self.write([json.dumps({"type": "SCHEMA"}),
                           json.dumps({"type": "ROW", "row": {"region": "north", "orders": 2, "amount": "10.5"}}),
                           json.dumps({"type": "ROW", "row": {"region": "south", "orders": 1, "amount": 3.0}})],
                          ".jsonl")
        self.assertEqual(0, dashboards.main(["rows", doris, lake, "region", "orders,amount"]))
        doris = self.write(["region\torders\tamount", "north\t3\t10.50"], ".tsv")
        self.assertEqual(1, dashboards.main(["rows", doris, lake, "region", "orders,amount"]))


if __name__ == "__main__":
    unittest.main()
