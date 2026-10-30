import contextlib
import io
import json
import math
import os
import tempfile
import unittest

import perf


def write(directory, name, lines):
    path = os.path.join(directory, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        for line in lines:
            f.write((json.dumps(line) if isinstance(line, dict) else line) + "\n")
    return path


def change(table, snapshot, at, kind, **row):
    return {"type": "CHANGE", "table": table, "snapshot": snapshot, "committedAt": at,
            "observedAt": at + 100, "kind": kind,
            "row": {k: None if v is None else str(v) for k, v in row.items()}}


def run(argv):
    with contextlib.redirect_stdout(io.StringIO()):
        return perf.main(argv)


def checksum(key, version):
    return (key * 7919 + version * 104729) % 9999991


def amount(c):
    return f"{c // 100}.{c % 100:02d}"


class Scenario:
    """A synthetic run of the version markers: one key a second, updated a minute later, deleted
    two minutes after its insert; ODS 20 s after the source, DWD 30 s after ODS, DWS 30 s after
    DWD, Doris's paths after them."""

    T0 = 1_000_000_000
    MINUTES = 20

    def __init__(self, d, dws_lag=None, bad_dwd_amount=False, skew=4, dws_regress=False):
        ticks = (self.MINUTES + 6) * 60
        ops, seq = [], 0
        for j in range(ticks):
            for i, (kind, key, version) in enumerate((("I", j, 1), ("U", j - 60, 2),
                                                       ("D", j - 120, 2))):
                if key < 0:
                    continue
                seq += 1
                ops.append((seq, kind, 5_000_000 + key, version, self.T0 + j * 1000 + i * 100))
        self.ops = ops
        rel = os.path.join(d, "release")
        write(d, "release/tables", ["ods ods.o", "dwd dwd.o", "dws dws.s"])
        write(d, "release/versions.log", [
            f"op {s} {k} {key} {v} {checksum(key, v)} {at} {at - 2} {at + 3}"
            for s, k, key, v, at in ops])
        lines, rows, live, versions, cents = [], {}, 0, 0, 0
        polls = []
        for s, k, key, v, at in ops:
            c = checksum(key, v)
            if k == "I":
                lines.append(change("ods.o", s, at + 20_000, "+I", id=key, version_no=v,
                                    amount=amount(c), note=f"v {s}"))
                lines.append(change("dwd.o", s, at + 50_000, "+I", order_id=key, version_no=v,
                                    amount=amount(c)))
                rows[key] = (v, c)
                live, versions, cents = live + 1, versions + v, cents + c
            elif k == "U":
                old_v, old_c = rows[key]
                lines.append(change("ods.o", s, at + 20_000, "-U", id=key, version_no=old_v,
                                    amount=amount(old_c), note=None))
                lines.append(change("ods.o", s, at + 20_000, "+U", id=key, version_no=v,
                                    amount=amount(c), note=f"v {s}"))
                dwd_c = c + 1 if bad_dwd_amount and s == 500 else c
                lines.append(change("dwd.o", s, at + 50_000, "-U", order_id=key,
                                    version_no=old_v, amount=amount(old_c)))
                lines.append(change("dwd.o", s, at + 50_000, "+U", order_id=key, version_no=v,
                                    amount=amount(dwd_c)))
                rows[key] = (v, c)
                versions, cents = versions + v - old_v, cents + c - old_c
            else:
                old_v, old_c = rows.pop(key)
                lines.append(change("ods.o", s, at + 20_000, "-D", id=key, version_no=old_v,
                                    amount=amount(old_c), note=None))
                lines.append(change("dwd.o", s, at + 50_000, "-D", order_id=key,
                                    version_no=old_v, amount=amount(old_c)))
                live, versions, cents = live - 1, versions - old_v, cents - old_c
            lag = dws_lag(at) if dws_lag else 80_000
            lines.append(change("dws.s", s, at + lag, "+U", region="r", rel_live=live,
                                rel_versions=versions, rel_amount=amount(cents)))
            polls.append((at, live, versions, cents))
        if dws_regress:
            # An old state read once more, after a newer one.
            _, live0, versions0, cents0 = polls[10]
            lines.append(change("dws.s", 99_999, polls[-1][0] + 90_000, "+U", region="r",
                                rel_live=live0, rel_versions=versions0, rel_amount=amount(cents0)))
        for t in ("ods.o", "dwd.o", "dws.s"):
            lines.append({"type": "END", "table": t, "at": 0})
        write(d, "release/observed.jsonl", sorted(
            lines, key=lambda x: (x["table"], x.get("committedAt", math.inf))))
        doris = []
        for at, live_, versions_, cents_ in polls:
            for path, lag in (("live", 100_000), ("mv", 110_000), ("dws_dashboard", 150_000)):
                doris.append(f"{path} {at + lag - 50} {at + lag} OK {live_} {versions_} "
                             f"{amount(cents_)}")
        write(d, "release/doris.log", doris)
        clocks = []
        for t in range(-120_000, (self.MINUTES + 10) * 60_000, 60_000):
            for pod in ("colvenna-data/versions-r1", "colvenna-bi-client/prober-r1",
                        "colvenna-data/dash-r1-cdc-taskmanager-1-1",
                        "colvenna-data/dash-dwd-r1-table-taskmanager-1-1",
                        "colvenna-data/dash-dws-r1-table-taskmanager-1-1"):
                error = skew if "dws" in pod else 4
                clocks.append(f"{self.T0 + t} {pod} node-1 {error} 1")
        write(d, "release/clocks.tsv", clocks)
        write(d, "release/cdc-load.log", [
            f"load {self.T0 + s * 1000} {s * 18} {s * 184} 3 0" for s in range(-5, ticks)])
        self.marks = write(d, "marks.log", [
            f"mark {10_000_000 + i} {self.T0 + i * 100} {self.T0 + i * 100 + 4}"
            for i in range(ticks * 10)])
        self.release = rel
        # After the warm-up: every kind once a second.
        self.window = (self.T0 + 180_000, self.T0 + 180_000 + self.MINUTES * 60_000)

    def judge(self, d, min_samples=10, target=197):
        old = perf.dashboards.MIN_SAMPLES
        perf.dashboards.MIN_SAMPLES = min_samples
        try:
            code = run(["layers", self.release, self.marks, os.path.join(d, "layers.json"),
                        "--window", str(self.window[0]), str(self.window[1]), "--run", "r1",
                        "--cdc-target", str(target)])
        finally:
            perf.dashboards.MIN_SAMPLES = old
        with open(os.path.join(d, "layers.json")) as f:
            return code, json.load(f)


class LayersTest(unittest.TestCase):
    def test_every_kind_on_every_path(self):
        with tempfile.TemporaryDirectory() as d:
            code, r = Scenario(d).judge(d)
            self.assertEqual(0, code, json.dumps(r["findings"]))
            expected = {"source_to_ods": 20, "ods_to_dwd": 30, "dwd_to_dws": 30,
                        "source_to_external": 100, "paimon_to_external": 80,
                        "paimon_to_materialized": 90, "source_to_dws_dashboard": 150}
            for path, seconds in expected.items():
                for kind in ("insert", "update", "delete"):
                    p = r["paths"][path][kind]
                    self.assertEqual(1200, p["samples"], (path, kind))
                    self.assertEqual(seconds, p["p99"], (path, kind))
            self.assertTrue(all(s["steady"] for s in r["steady"].values()), r["steady"])
            self.assertEqual(0, sum(sum(f.values()) for f in r["findings"].values()))
            self.assertTrue(r["rate"]["kept"], r["rate"])
            self.assertEqual(184.0, r["rate"]["load_per_second"])
            self.assertEqual(3.0, r["rate"]["versions_per_second"])

    def test_too_few_samples_are_not_judged(self):
        with tempfile.TemporaryDirectory() as d:
            code, r = Scenario(d).judge(d, min_samples=10_000)
            self.assertEqual(1, code)
            p = r["paths"]["source_to_ods"]["delete"]
            self.assertFalse(p["judged"])
            self.assertTrue(p["within"])

    def test_a_growing_backlog_is_not_steady(self):
        with tempfile.TemporaryDirectory() as d:
            # 3 minutes an hour: about 0.67 minutes more from the window's first third to its last.
            s = Scenario(d, dws_lag=lambda at: 80_000 + (at - Scenario.T0) // 20)
            code, r = s.judge(d)
            self.assertEqual(1, code)
            self.assertFalse(r["steady"]["dws"]["steady"])
            self.assertTrue(r["steady"]["dwd"]["steady"])
            # Within its targets all the same: the steady state fails it, in the report.
            self.assertTrue(r["paths"]["dwd_to_dws"]["insert"]["within"])

    def test_a_wrong_checksum_and_an_older_state_are_findings(self):
        with tempfile.TemporaryDirectory() as d:
            code, r = Scenario(d, bad_dwd_amount=True, dws_regress=True).judge(d)
            self.assertEqual(1, code)
            self.assertEqual({"checksum_mismatch": 1}, r["findings"]["dwd"])
            self.assertEqual(1, r["findings"]["dws"]["older_than_before"])
            self.assertEqual({}, r["findings"]["ods"])

    def test_a_clock_too_far_voids_its_samples(self):
        with tempfile.TemporaryDirectory() as d:
            code, r = Scenario(d, skew=997).judge(d)
            self.assertEqual(1, code)
            # dws's 997 ms with dwd's 4: over a second together.
            self.assertEqual(0, r["paths"]["dwd_to_dws"]["update"]["samples"])
            self.assertEqual(1200, r["paths"]["dwd_to_dws"]["update"]["voided_by_clock"])
            self.assertEqual(1200, r["paths"]["ods_to_dwd"]["update"]["samples"])

    def test_the_rate_is_the_whole_window(self):
        with tempfile.TemporaryDirectory() as d:
            code, r = Scenario(d).judge(d, target=200)
            self.assertEqual(1, code)
            self.assertFalse(r["rate"]["kept"])
            self.assertEqual(197.0, r["rate"]["per_second"])


class PrefixTest(unittest.TestCase):
    def test_totals_name_the_prefix_and_older_or_other_states_are_findings(self):
        ops = [perf.Op(1, "I", 1, 1, 100), perf.Op(2, "I", 2, 1, 200), perf.Op(3, "U", 1, 2, 150),
               perf.Op(4, "D", 2, 1, 200)]
        index = perf.prefixes(ops)
        self.assertEqual([3], index[(2, 3, 350)])
        states = [(10, (1, 1, 100)), (20, (9, 9, 9)), (30, (2, 3, 350)), (40, (2, 2, 300)),
                  (50, (1, 2, 150))]
        seen, findings = perf.prefix_seen(states, index, len(ops))
        self.assertEqual([None, 10, 30, 30, 50], seen)
        self.assertEqual({"not_a_prefix": 1, "older_than_before": 1}, findings)

    def test_a_key_read_after_its_delete_or_at_an_older_version(self):
        ops = [perf.Op(1, "I", 5_000_001, 1, 100), perf.Op(2, "U", 5_000_001, 2, 150),
               perf.Op(3, "D", 5_000_001, 2, 150)]
        lines = [change("t.o", 1, 10, "+I", id=5_000_001, version_no=1, amount="1.00"),
                 change("t.o", 2, 20, "+U", id=5_000_001, version_no=2, amount="1.50"),
                 change("t.o", 3, 30, "+U", id=5_000_001, version_no=1, amount="1.00"),
                 change("t.o", 4, 40, "-D", id=5_000_001, version_no=2, amount="1.50"),
                 change("t.o", 5, 50, "+I", id=5_000_001, version_no=2, amount="1.50")]
        seen, findings = perf.key_changes(lines, "t.o", "id", ops)
        self.assertEqual({1: 10, 2: 20, 3: 40}, seen)
        # And it stays there: its final state is not the deleted one.
        self.assertEqual({"older_version_after_newer": 1, "read_after_delete": 1,
                          "final_state_mismatch": 1}, findings)

    def test_an_update_and_a_delete_in_one_commit_show_as_one_retraction(self):
        # DWD's lookup changelog: the commit that took both shows the key's -D of version 1.
        ops = [perf.Op(1, "I", 5_000_001, 1, 100), perf.Op(2, "U", 5_000_001, 2, 150),
               perf.Op(3, "D", 5_000_001, 2, 150), perf.Op(4, "I", 5_000_002, 1, 200)]
        lines = [change("t.o", 1, 10, "+I", id=5_000_001, version_no=1, amount="1.00"),
                 change("t.o", 2, 30, "-D", id=5_000_001, version_no=1, amount="1.00"),
                 change("t.o", 3, 40, "+I", id=5_000_002, version_no=1, amount="2.00")]
        seen, findings = perf.key_changes(lines, "t.o", "id", ops)
        self.assertEqual({1: 10, 2: 30, 3: 30, 4: 40}, seen)
        self.assertEqual({}, findings)

    def test_a_lost_op_shows_in_the_final_state(self):
        # The update of key 1 never reaches the table, though a later op does.
        ops = [perf.Op(1, "I", 5_000_001, 1, 100), perf.Op(2, "U", 5_000_001, 2, 150),
               perf.Op(3, "I", 5_000_002, 1, 200)]
        lines = [change("t.o", 1, 10, "+I", id=5_000_001, version_no=1, amount="1.00"),
                 change("t.o", 2, 30, "+I", id=5_000_002, version_no=1, amount="2.00")]
        seen, findings = perf.key_changes(lines, "t.o", "id", ops)
        self.assertEqual({"final_state_mismatch": 1}, findings)

    def test_a_lagging_kind_cannot_hide_behind_other_keys(self):
        # Key 1's update never shows, though later ops of other keys do: it is not readable.
        ops = [perf.Op(1, "I", 5_000_001, 1, 100), perf.Op(2, "I", 5_000_002, 1, 200),
               perf.Op(3, "U", 5_000_001, 2, 150), perf.Op(4, "I", 5_000_003, 1, 300)]
        lines = [change("t.o", 1, 10, "+I", id=5_000_001, version_no=1, amount="1.00"),
                 change("t.o", 2, 20, "+I", id=5_000_002, version_no=1, amount="2.00"),
                 change("t.o", 3, 30, "+I", id=5_000_003, version_no=1, amount="3.00")]
        seen, findings = perf.key_changes(lines, "t.o", "id", ops)
        self.assertEqual({1: 10, 2: 20, 4: 30}, seen)
        self.assertEqual({"final_state_mismatch": 1}, findings)

    def test_an_update_written_as_a_delete_and_an_insert_is_no_delete(self):
        # ODS's writer (Paimon's CDC sink) writes an update as -D of the old row and +I of the new.
        ops = [perf.Op(1, "I", 5_000_001, 1, 100), perf.Op(2, "U", 5_000_001, 2, 150),
               perf.Op(3, "D", 5_000_001, 2, 150)]
        lines = [change("t.o", 1, 10, "+I", id=5_000_001, version_no=1, amount="1.00"),
                 change("t.o", 2, 20, "-D", id=5_000_001, version_no=1, amount="1.00"),
                 change("t.o", 2, 20, "+I", id=5_000_001, version_no=2, amount="1.50"),
                 change("t.o", 3, 30, "-D", id=5_000_001, version_no=2, amount="1.50")]
        seen, findings = perf.key_changes(lines, "t.o", "id", ops)
        self.assertEqual({1: 10, 2: 20, 3: 30}, seen)
        self.assertEqual({}, findings)


class ClockTest(unittest.TestCase):
    def test_bounds_sum_to_a_second_at_most(self):
        with tempfile.TemporaryDirectory() as d:
            path = write(d, "clocks.tsv", [
                "0 ns/a-1 n1 5 1", "60000 ns/a-1 n1 5 1", "120000 ns/a-1 n1 5 0",
                "0 ns/b-1 n2 4 1", "60000 ns/b-1 n2 1200 1", "120000 ns/b-1 n2 4 1",
                "0 ns/versions-r n3 ? 0", "bad line"])
            c = perf.Clocks(path, {"a": lambda p: "/a-" in p, "b": lambda p: "/b-" in p,
                                   "versions": lambda p: "/versions-" in p})
            self.assertTrue(c.valid("a", -50_000, "b", -50_000))
            # b's maximum error near 60 s is 1.2 s.
            self.assertFalse(c.valid("a", 70_000, "b", 70_000))
            # a was not synchronized at 120 s; nothing measured near 300 s.
            self.assertFalse(c.valid("a", 200_000, "b", -50_000))
            self.assertFalse(c.valid("a", 300_000, "b", 300_000))
            # MySQL's bound comes with the version markers' pod, whose node was not read.
            c.add_mysql([perf.Op(1, "I", 1, 1, 1, 0, 3)])
            self.assertEqual(math.inf, c.source(0))

    def test_mysql_ahead_of_the_pod_is_bounded_by_the_read_before_the_call(self):
        # MySQL read its clock somewhere between the pod's 0 and 400: it is 200 to 600 ahead.
        op = perf.Op(1, "I", 1, 1, 1, source_ms=600, pod_ms=400, pod_before_ms=0)
        self.assertEqual(600, op.mysql_offset_bound())
        # A log of the earlier form has the read after the call only.
        self.assertEqual(200, perf.Op(1, "I", 1, 1, 1, 600, 400).mysql_offset_bound())
        with tempfile.TemporaryDirectory() as d:
            path = write(d, "versions.log", ["op 1 I 5000000 1 77 600 0 400",
                                             "op 2 U 5000000 2 88 1600 1500"])
            ops = perf.version_ops(path)
            self.assertEqual([0, None], [o.pod_before_ms for o in ops])
            self.assertEqual([400, 1500], [o.pod_ms for o in ops])

    def test_mysql_against_the_markers_pod_within_90_seconds(self):
        with tempfile.TemporaryDirectory() as d:
            path = write(d, "clocks.tsv", ["0 ns/versions-r n1 4 1", "60000 ns/versions-r n1 4 1"])
            c = perf.Clocks(path, {"versions": lambda p: "/versions-" in p})
            c.add_mysql([perf.Op(1, "I", 1, 1, 1, 0, 3), perf.Op(2, "I", 2, 1, 1, 50_000, 50_400)])
            self.assertEqual(404, c.source(10_000))
            self.assertEqual(7, c.source(-80_000))


MINUTE = 60_000
HOUR = 60 * MINUTE


def every_second(until, lag):
    """A change every second from 0 to until (ms), seen lag(source) ms later (None: never)."""
    out = []
    for t in range(0, until, 1000):
        wait = lag(t)
        out.append((t, None if wait is None else t + wait))
    return out


def cycles(maxima, period, base=None):
    """A layer that shows changes in cycles, its backlog's maximum per 10-minute window given
    (minutes): a change every second; a catch-up every period (ms), half a period after each
    period's start, just before which the backlog is its window's maximum (with base, only the
    catch-up in the middle of each window: the others at base). So each catch-up shows the changes
    before the one the next catch-up's backlog started from. The windows' maxima come out as given
    while neighbouring ones differ by less than half a period."""
    span = len(maxima) * perf.BACKLOG_WINDOW_MS
    catch_ups = list(range(period // 2, span + period, period))
    middle = perf.BACKLOG_WINDOW_MS // 2 // period

    def height(c):
        m = maxima[min(c // perf.BACKLOG_WINDOW_MS, len(maxima) - 1)]
        return m if base is None or c % perf.BACKLOG_WINDOW_MS // period == middle else base

    peak = [round(height(c) * MINUTE) for c in catch_ups]
    # Just before catch-up j + 1, the oldest waiting was confirmed at catch_ups[j + 1] - peak[j + 1].
    oldest = [c - h for c, h in zip(catch_ups[1:], peak[1:])]
    samples, j = [], 0
    for t in sorted(set(range(0, span + 3 * period, 1000)) | set(oldest)):
        while j < len(oldest) and t >= oldest[j]:
            j += 1
        samples.append((t, catch_ups[j]))
    return samples, (0, span)


class BacklogTest(unittest.TestCase):
    def test_a_constant_lag_is_steady_and_a_change_never_seen_is_not(self):
        window = (0, 30 * MINUTE)
        samples = every_second(40 * MINUTE, lambda t: 20_000)
        b = perf.backlog(samples, window)
        self.assertTrue(b["steady"], b)
        # Each change waits 20 s, the one after it 19 s when it shows: the backlog runs from 19 to
        # 20 s, evenly, and is no sample's.
        self.assertAlmostEqual(20 / 60, b["max_backlog_min"], places=4)
        self.assertAlmostEqual(19.95 / 60, b["last_third_p95_min"], places=4)
        samples[600] = (samples[600][0], None)
        self.assertFalse(perf.backlog(samples, window)["steady"])

    def test_a_lag_growing_a_minute_an_hour_is_not_steady(self):
        window = (0, 2 * HOUR)
        b = perf.backlog(every_second(3 * HOUR, lambda t: 20_000 + t // 60), window)
        self.assertFalse(b["steady"], b)
        self.assertGreater(b["last_third_p95_min"] - b["first_third_p95_min"], 1.2)

    def test_a_layer_that_stops_showing_is_not_steady(self):
        # Nothing confirmed after 100 minutes shows: the backlog grows without bound.
        window = (0, 2 * HOUR)
        b = perf.backlog(every_second(3 * HOUR, lambda t: 20_000 if t < 100 * MINUTE else None),
                         window)
        self.assertFalse(b["steady"], b)
        self.assertAlmostEqual(20.0, b["max_backlog_min"], places=2)

    def test_a_late_step_that_persists_and_grows_fails_only_above_the_margin(self):
        # 30 s throughout; from 85 minutes (in the last third) a step up, growing 0.2 minutes more
        # by the end.
        window = (0, 2 * HOUR)

        def step(up_min):
            def lag(t):
                if t < 85 * MINUTE:
                    return 30_000
                return 30_000 + up_min * MINUTE + 0.2 * MINUTE * (t - 85 * MINUTE) / (35 * MINUTE)
            return perf.backlog(every_second(3 * HOUR, lag), window)

        below = step(0.2)  # at most 0.4 minutes above the start
        self.assertTrue(below["steady"], below)
        self.assertAlmostEqual(0.38, below["last_third_p95_min"] - below["first_third_p95_min"],
                               delta=0.01)
        # The rule up to v1.0.0-rc.2 failed it on its slope.
        self.assertGreater(below["grid_slope_min_per_hour"], 0.05)
        above = step(0.4)  # 0.6 minutes above
        self.assertFalse(above["steady"], above)
        self.assertGreater(above["last_third_p95_min"] - above["first_third_p95_min"], 0.5)

    @staticmethod
    def slow_cycle(at_min, late_ms):
        """30 s cycles, each shown 5 s after it closes; the one closing at at_min, late_ms later."""
        def lag(t):
            close = (t // 30_000 + 1) * 30_000
            return close + 5_000 + (late_ms if close == at_min * MINUTE else 0) - t
        return every_second(3 * HOUR, lag)

    def test_a_single_slow_commit_passes_wherever_it_falls(self):
        # 31 s late: the backlog's maximum 0.52 minutes above the rest, early or late in the
        # window. Over 1% of a third's time, it leaves the p95 where it was.
        for at in (20, 110):
            with self.subTest(minute=at):
                b = perf.backlog(self.slow_cycle(at, 31_000), (0, 2 * HOUR), 5)
                self.assertTrue(b["steady"], b)
                self.assertAlmostEqual(66 / 60, b["max_backlog_min"], places=3)
                self.assertLess(abs(b["last_third_p95_min"] - b["first_third_p95_min"]), 0.01)

    def test_a_backlog_over_its_bound_fails_wherever_it_falls(self):
        # One cycle 6 minutes late: 6.6 minutes, over a 5-minute bound.
        for at in (20, 110):
            with self.subTest(minute=at):
                b = perf.backlog(self.slow_cycle(at, 6 * MINUTE), (0, 2 * HOUR), 5)
                self.assertFalse(b["steady"], b)
                self.assertAlmostEqual(6.583, b["max_backlog_min"], places=3)
        # Without a bound, early in the window it is no growth: the bound is what fails it.
        self.assertTrue(perf.backlog(self.slow_cycle(20, 6 * MINUTE), (0, 2 * HOUR))["steady"])

    def test_the_bound_is_the_p99_target_of_the_path_from_the_source(self):
        self.assertEqual({"ods": 5, "external": 10, "dws_dashboard": 30, "kafka_ods": 5,
                          "dwd": None, "dws": None, "materialized": None},
                         {layer: perf.backlog_bound(layer) for layer in (
                             "ods", "external", "dws_dashboard", "kafka_ods", "dwd", "dws",
                             "materialized")})

    def test_a_catch_up_either_side_of_a_10_s_step_is_no_growth(self):
        # #268: 30 s cycles, each shown 9.5 s after it closes; from the fourth window on, one cycle
        # in each is shown at 10.5 s instead. A sample every 10 s, 10 s after each close, sees the
        # late ones a step (10 s) higher: their windows' maxima 0.667 against 0.5 minutes, the slope
        # 0.094 minutes an hour. The backlog itself is 1 s higher, in the first third as in the last.
        def lag(t):
            close = (t // 30_000 + 1) * 30_000
            late = close % perf.BACKLOG_WINDOW_MS == 300_000 and close >= 3 * perf.BACKLOG_WINDOW_MS
            return close + (10_500 if late else 9_500) - t

        window = (0, 2 * HOUR)
        b = perf.backlog(every_second(3 * HOUR, lag), window)
        self.assertTrue(b["steady"], b)
        self.assertEqual([0.658] * 3 + [0.675] * 9, b["windows"])
        self.assertAlmostEqual(0.0944, b["grid_slope_min_per_hour"], places=4)


class ReconstructedRc2qWindowMaximaTest(unittest.TestCase):
    """v1.0.0-rc.2's run rc2q: the two layers its rule failed on their slope (#268), on a backlog
    reconstructed from their 10-minute maxima (cycles above: each window's peak in its middle, the
    other catch-ups at the lowest maximum). The maxima as layers.json recorded them on the 10 s
    grid, and as the backlog computed exactly from the run's evidence has them."""

    FIXTURES = {
        # (maxima in minutes, the layer's cycle in ms)
        "external, on the grid": ([0.505, 0.505, 0.505, 0.663, 0.671, 0.663, 0.671, 0.663, 0.505,
                                   0.663, 0.663, 0.671], 30_000),
        "dws_dashboard, on the grid": ([1.838, 1.838, 1.838, 1.996, 1.996, 1.996, 1.996, 1.838,
                                        1.838, 1.996, 2.005, 1.996], 60_000),
        "external": ([0.663, 0.654, 0.648, 0.688, 0.682, 0.682, 0.695, 0.682, 0.658, 0.678, 0.686,
                      0.683], 30_000),
        "dws_dashboard": ([2.002, 1.999, 1.997, 1.997, 2.002, 2.003, 2.003, 2.002, 2.002, 2.005,
                           2.007, 2.005], 60_000),
    }

    def test_steady_though_the_grid_s_slope_was_over_its_limit(self):
        for name, (maxima, period) in self.FIXTURES.items():
            with self.subTest(name):
                samples, window = cycles(maxima, period, base=min(maxima))
                b = perf.backlog(samples, window, 30)
                self.assertEqual(maxima, b["windows"])
                self.assertTrue(b["steady"], b)
                self.assertLess(abs(b["last_third_p95_min"] - b["first_third_p95_min"]), 0.05)
                if name.endswith("on the grid"):
                    # The rule up to rc.2 fails the reconstruction too.
                    self.assertGreater(b["grid_slope_min_per_hour"], 0.05)
        # Their slopes, the rule up to rc.2's: 0.0746 and 0.0647 minutes an hour, over 0.05.
        for name, expected in (("external, on the grid", 0.0746),
                               ("dws_dashboard, on the grid", 0.0647)):
            maxima = self.FIXTURES[name][0]
            points = [((k + 0.5) / 6, m) for k, m in enumerate(maxima)]
            self.assertAlmostEqual(expected, perf.slope(points), places=3)


class ReconstructedRc1qWindowMaximaTest(unittest.TestCase):
    def test_the_dashboard_view_s_half_minute_peaks_pass(self):
        # v1.0.0-rc.1's run rc1q, dws_dashboard, its exact maxima: 1.93 minutes, or 2.41-2.43 in
        # the windows where the view missed a DWS commit cycle (0.5 minutes). Reconstructed with a
        # refresh a minute, each window's peak in its middle and the rest at the lowest maximum.
        maxima = [1.934, 2.42, 2.415, 2.413, 1.932, 2.409, 2.412, 1.926, 2.434, 2.426, 1.934, 2.409]
        samples, window = cycles(maxima, 60_000, base=1.926)
        b = perf.backlog(samples, window, perf.backlog_bound("dws_dashboard"))
        self.assertEqual(maxima, b["windows"])
        self.assertTrue(b["steady"], b)
        self.assertLess(abs(b["last_third_p95_min"] - b["first_third_p95_min"]), 0.05)


class SteadyWindowsTest(unittest.TestCase):
    def test_a_short_last_window_is_no_maximum(self):
        # 30 minutes and 15 s: a 1-minute backlog throughout, 6 s in the last 15 s alone. Only
        # whole 10-minute windows have a maximum: that short one, at its nominal midpoint, would
        # pull the observed slope far down.
        window = (0, 30 * MINUTE + 15_000)
        samples = every_second(40 * MINUTE, lambda t: 60_000 if t < 29 * MINUTE else 6_000)
        b = perf.backlog(samples, window)
        self.assertEqual([1.0, 1.0, 1.0], b["windows"])
        self.assertEqual(0.0, b["grid_slope_min_per_hour"])
        self.assertTrue(b["steady"], b)


class CoverageTest(unittest.TestCase):
    def test_the_prefix_moves_only_when_every_offset_before_came(self):
        c = perf.Coverage()
        c.add(0, 9)
        c.add(20, 29)
        self.assertEqual(10, c.next)
        self.assertEqual(10, c.missing_below(30))
        c.add(10, 19)
        self.assertEqual(30, c.next)
        self.assertEqual(0, c.repeated)

    def test_repeats_are_counted_below_and_beyond_the_prefix(self):
        c = perf.Coverage()
        c.add(0, 9)
        c.add(5, 12)
        self.assertEqual(5, c.repeated)
        c.add(20, 25)
        c.add(22, 30)
        self.assertEqual(9, c.repeated)
        self.assertEqual(7, c.missing_below(31))


class EventsTest(unittest.TestCase):
    def make(self, d, ranges, end_line, clocks=True, acked_per_second=20, error=False):
        start = 1_000_000
        producer = [f"stat {start + s * 1000} {s * 20} {s * acked_per_second} 0 2"
                    for s in range(-1, 8)]
        if error:
            producer.append("err 7 org.apache.kafka.common.errors.TimeoutException")
        producer += [f"ack 0 0 0 {start + 10} {start + 15}", f"ack 10 1 5 {start + 500} {start + 505}",
                     f"ack 20 0 9 {start + 1000} {start + 1005}", end_line]
        write(d, "events/producer.log", producer)
        write(d, "events/observed.jsonl", [
            {"type": "OFFSETS", "table": "ods.e", "snapshot": i + 1, "committedAt": at,
             "ranges": r} for i, (at, r) in enumerate(ranges)] + [
                 {"type": "END", "table": "ods.e", "at": 0}])
        write(d, "events/clocks.tsv", [
            f"{start + t} {pod} node-1 3 1" for t in range(-60_000, 180_000, 60_000)
            for pod in ("colvenna-lake-kafka/colvenna-dev-colvenna-dev-node-0",
                        "colvenna-data/pev-r2-events-taskmanager-1-1")] if clocks else [])
        old = perf.dashboards.MIN_SAMPLES
        perf.dashboards.MIN_SAMPLES = 1
        try:
            code = run(["events", os.path.join(d, "events"), os.path.join(d, "events.json"),
                        "--window", str(start), str(start + 6000), "--run", "r2",
                        "--broker-namespace", "colvenna-lake-kafka", "--rate", "20"])
        finally:
            perf.dashboards.MIN_SAMPLES = old
        with open(os.path.join(d, "events.json")) as f:
            return code, json.load(f)

    def test_an_event_is_readable_once_its_partition_is_complete_up_to_it(self):
        with tempfile.TemporaryDirectory() as d:
            start = 1_000_000
            code, r = self.make(d, [
                (start + 30_000, {"0": [[0, 4]], "1": [[0, 2], [4, 9]]}),
                (start + 60_000, {"0": [[5, 69]], "1": [[3, 3], [10, 69]]})],
                "end 140 140 0 0:69:70 1:69:70")
            p = r["paths"]["kafka_to_ods"]["insert"]
            self.assertEqual(3, p["samples"])
            self.assertEqual(59.5, p["max"])
            self.assertEqual({"repeated": 0, "missing": 0},
                             {k: r["exactly_once"][k] for k in ("repeated", "missing")})
            self.assertTrue(r["rate"]["kept"], r["rate"])
            self.assertEqual(20.0, r["rate"]["per_second"])

    def test_the_rate_is_what_the_broker_acknowledged_and_any_refusal_fails(self):
        ranges = [(1_060_000, {"0": [[0, 69]], "1": [[0, 69]]})]
        with tempfile.TemporaryDirectory() as d:
            code, r = self.make(d, ranges, "end 140 70 0 0:69:70 1:69:70", acked_per_second=10)
            self.assertEqual(1, code)
            self.assertFalse(r["rate"]["kept"])
            self.assertEqual(10.0, r["rate"]["per_second"])
        with tempfile.TemporaryDirectory() as d:
            code, r = self.make(d, ranges, "end 140 139 1 0:69:70 1:69:70", error=True)
            self.assertEqual(1, code)
            self.assertFalse(r["rate"]["kept"])
            self.assertEqual(1, r["rate"]["errors"])

    def test_offsets_never_brought_or_brought_twice_fail(self):
        with tempfile.TemporaryDirectory() as d:
            code, r = self.make(d, [(1_030_000, {"0": [[0, 9], [9, 9], [20, 29]]})],
                                "end 30 30 0 0:29:30", clocks=False)
            self.assertEqual(1, code)
            self.assertEqual(1, r["exactly_once"]["repeated"])
            self.assertEqual(10, r["exactly_once"]["missing"])
            self.assertFalse(r["paths"]["kafka_to_ods"]["insert"]["judged"])


class ReportTest(unittest.TestCase):
    def files(self, d, judged=True, fail=False, statuses=None, internal_slow=False,
              schedule_kept=True, errors=None, log_whole=True):
        def p(ok=True):
            return {"samples": 20_000, "p50": 5.0, "p95": 10.0, "p99": 10.0, "max": 10.0,
                    "targets": [120, 300], "judged": judged, "within": ok,
                    "pass": judged and ok}

        steady = {"steady": True, "first_third_p95_min": 0.5, "last_third_p95_min": 0.5,
                  "max_backlog_min": 0.6, "grid_slope_min_per_hour": 0.0,
                  "limits": {"thirds_growth_min": 0.5, "max_backlog_min": 5.0}}
        kinds = lambda ok=True: {k: p(ok) for k in ("insert", "update", "delete")}  # noqa: E731
        write(d, "layers.json", [{
            "paths": {k: kinds(not (fail and k == "dwd_to_dws")) for k in (
                "source_to_ods", "ods_to_dwd", "dwd_to_dws", "paimon_to_external",
                "paimon_to_materialized", "source_to_external", "source_to_dws_dashboard")},
            "steady": {k: steady for k in ("ods", "dwd", "dws", "external", "materialized",
                                           "dws_dashboard")},
            "findings": {"ods": {}, "dwd": {}, "dws": {"not_a_prefix": 0}},
            "rate": {"kept": True}, "observer": {"ended": ["a"], "missing_end": [], "errors": {}},
            "clock": {"groups": {"ods": {"measurements": 3, "max_error_ms": 4, "unsynchronized": 0}}}}])
        write(d, "events.json", [{
            "paths": {"kafka_to_ods": {"insert": p()}}, "steady": {"kafka_ods": steady},
            "rate": {"kept": True}, "exactly_once": {"repeated": 0, "missing": 0},
            "observer": {"ended": ["e"], "missing_end": [], "errors": {}},
            "clock": {"groups": {"broker": {"measurements": 3, "max_error_ms": 3,
                                            "unsynchronized": 0}}}}])
        write(d, "dashboards/doris/load.json", [{
            "dashboard": p(), "dashboard_during_burst": p(),
            "internal": p(not internal_slow), "lookup": p(),
            "errors": errors or {},
            "schedule": {"late_after_ms": 1000, "on_time_share": 0.99, "kept": schedule_kept,
                         "groups": {"dashboard": {"sent": 1000, "late": 0 if schedule_kept else 50,
                                                  "max_late_ms": 3, "kept": schedule_kept}},
                         "log": {"recorded": {"totals": 1000},
                                 "written": {"totals": 1000 if log_whole else 72_000}, "whole": log_whole}}}])
        write(d, "dashboards/verdicts.tsv", ["t\tsource-ready\tPASS\t", "t\tload-within-targets\tFAIL\t",
                                             "t\tfresh-paimon-to-external\tPASS\t"])
        write(d, "statuses.json", [statuses or {"events_run": 0, "release_stop_and_cleanup": 0,
                                                "dashboards_teardown": 0, "left_behind": 0}])

    def outcome(self, d):
        code = run(["report", d, os.path.join(d, "release.md")])
        with open(os.path.join(d, "release.json")) as f:
            return code, json.load(f)["outcome"]

    def test_pass_only_when_every_judged_row_is_within_and_every_check_holds(self):
        with tempfile.TemporaryDirectory() as d:
            # The internal table's lookups are observed only: slow ones do not fail the run.
            self.files(d, internal_slow=True)
            self.assertEqual((0, "PASS"), self.outcome(d))
            with open(os.path.join(d, "release.md")) as f:
                text = f.read()
            self.assertIn("OBSERVED", text)
            self.assertIn("MySQL → ODS（删除）", text)
        with tempfile.TemporaryDirectory() as d:
            self.files(d, judged=False)
            self.assertEqual((1, "NOT_JUDGED"), self.outcome(d))
        with tempfile.TemporaryDirectory() as d:
            self.files(d, fail=True)
            self.assertEqual((1, "FAIL"), self.outcome(d))

    def test_a_dashboard_load_behind_its_schedule_or_a_failed_query_fails(self):
        with tempfile.TemporaryDirectory() as d:
            self.files(d, schedule_kept=False)
            self.assertEqual((1, "FAIL"), self.outcome(d))
            with open(os.path.join(d, "release.json")) as f:
                checks = {c["check"]: c for c in json.load(f)["checks"]}
            self.assertFalse(checks["看板负载按时发出"]["ok"])
            self.assertIn("固定看板查询发出 1000 个，晚于应发出 1000 ms 以上的 50 个",
                          checks["看板负载按时发出"]["value"])
            self.assertIn("每组至多 1%", checks["看板负载按时发出"]["value"])
            self.assertIn("日志完整", checks["看板负载按时发出"]["value"])
        with tempfile.TemporaryDirectory() as d:
            self.files(d, errors={"totals ERR": 3})
            self.assertEqual((1, "FAIL"), self.outcome(d))
        with tempfile.TemporaryDirectory() as d:
            self.files(d, schedule_kept=False, log_whole=False)
            self.assertEqual((1, "FAIL"), self.outcome(d))
            with open(os.path.join(d, "release.json")) as f:
                value = {c["check"]: c for c in json.load(f)["checks"]}["看板负载按时发出"]["value"]
            self.assertIn('日志不完整：收回 {"totals": 1000}，负载 Pod 写出 {"totals": 72000}', value)

    def test_a_missing_result_or_a_dirty_end_fails(self):
        with tempfile.TemporaryDirectory() as d:
            self.files(d)
            os.remove(os.path.join(d, "events.json"))
            self.assertEqual((1, "FAIL"), self.outcome(d))
        with tempfile.TemporaryDirectory() as d:
            self.files(d, statuses={"events_run": 0, "release_stop_and_cleanup": 0,
                                    "dashboards_teardown": 0, "left_behind": 2})
            self.assertEqual((1, "FAIL"), self.outcome(d))
        with tempfile.TemporaryDirectory() as d:
            self.files(d, statuses={"events_run": 0, "release_stop_and_cleanup": 0,
                                    "dashboards_teardown": 1, "left_behind": 0})
            self.assertEqual((1, "FAIL"), self.outcome(d))


if __name__ == "__main__":
    unittest.main()
