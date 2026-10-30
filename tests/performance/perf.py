#!/usr/bin/env python3
"""Judges the release performance run (docs/uat/performance.md) against the acceptance contract's
2.1: per path and per kind of change (insert, update, delete), p95 and p99 of at least 10,000
valid samples; fewer are observed and not judged. A path passes only with its end layer in steady
state (the source's backlog there not growing) and no correctness finding.

  perf.py layers <release-dir> <marks.log> <out.json> --window <from-ms> <to-ms>
          --run <dashboards-run> [--cdc-target <changes/s>]
      <release-dir> is what release.sh collected: versions.log (the version markers: each key
      inserted, updated to version 2 a minute later, deleted two minutes after insert, every op
      "op <seq> I|U|D <key> <version> <cents> <source-ms> <pod-before-ms> <pod-after-ms>", its time
      by MySQL's clock right after its commit, between the pod's two reads), observed.jsonl (the snapshot observer's changes of ODS, DWD and DWS),
      doris.log (the prober's polls of the version markers' Doris paths: "<path> <start-ms>
      <end-ms> OK <count> <versions> <amount>"), clocks.tsv and cdc-load.log. <marks.log> is the
      dashboards run's (its markers count toward the source's rate). Each op committed in the window
      is a sample of its kind on every path:
        source -> ODS        the ODS snapshot whose changes first hold it (insert: the key at
                             version 1; update: version 2; delete: the key's -D)
        ODS -> DWD           the same in DWD, from ODS's
        DWD -> DWS           the first DWS snapshot whose version-marker totals (live keys,
                             versions, amounts, summed over its rows) are those of the ops up to
                             and including it, from DWD's
        source -> Doris external, Paimon -> Doris external
                             rel_live, an external view over ODS, the same way, from the source's
                             commit or ODS's
        Paimon -> Doris materialized   rel_mv, a materialized view over ODS, from ODS's commit
        source -> DWS dashboard        rel_dws, a materialized view over DWS, from the source's
      Not seen by the end of the observation counts as too late. Correctness findings: a key's
      amount not its version's checksum, a version read after a later one, a key read after its
      delete, totals that are no prefix of the ops, a prefix older than one read before.
  perf.py events <events-dir> <out.json> --window <from-ms> <to-ms> --run <events-run>
          --broker-namespace <ns> --rate <events/s>
      The producer's receipts (producer.log: "ack <n> <partition> <offset> <append-ms>
      <receipt-ms>", every sample-th event, the broker's append time) and the observer's OFFSETS
      lines of the events table: an event is readable once a snapshot has brought every offset of
      its partition up to its own (each offset exactly once: repeats and offsets never brought are
      counted). Kafka to ODS per sampled event appended in the window (events are inserts).
  perf.py report <evidence-root> <out.md>
      The paths of layers.json and events.json, the dashboard queries of the dashboards run's
      doris/load.json, and the checks, against their targets: release.json beside <out.md>. PASS
      when every judged row passed and every check held; NOT_JUDGED when none failed but some row
      was not judged; FAIL otherwise. Rows that are not the contract's (the internal table, the
      interactive lookup) are observed only and do not count.

Clocks: a sample compares two clocks, each with a bound on its error at the time it is read:
  - a node's clock (every pod reads its node's): <clocks.tsv> holds "<ms> <namespace>/<pod> <node>
    <maximum-error-ms> <synchronized>", once a minute (lib.sh clock_sampler: adjtimex; the nodes
    follow Amazon Time Sync); its bound is the largest maximum error measured within 90 s, and
    there is none (the sample is void) without a measurement there or with one not synchronized;
  - MySQL's: the version-marker pod reads its own clock, has MySQL read MySQL's right after each
    commit, and reads its own again, so MySQL's is within the larger of the two differences of
    that read (the round trip at most); its bound is the largest such within 90 s plus the pod
    node's.
A sample is valid when the two bounds sum to a second at most (the M1 baseline's rule).

Steady state (the contract's 2.1: the source's backlog no longer growing): a layer's backlog at a
moment is how long the oldest change the source confirmed and the layer does not show yet has
waited. It rises a second a second and drops each time the layer shows more, so it follows from
each change's own times (source, seen), not from samples on a grid. It is exact over the changes
it is computed from: the version markers' ops for the lake's and Doris's layers, the producer's
sampled receipts (every 100th event) for Kafka; changes confirmed before the window began are left
out, so the backlog starts the window at 0. Over a span, its maximum is the largest
min(seen, the span's end) - source of the changes waiting at some time in it; its p95 is the level
it was above for 5% of the span's time. Steady when both:
  - growth: the last third's p95 is at most 0.5 minutes (the M1 baseline's margin) above the first
    third's, the end of the window against its start;
  - a bound: the whole window's maximum is at most the p99 target of the path from the source that
    ends at the layer (BACKLOG_BOUND_PATHS). DWD, DWS and the materialized view have no such path
    (only one from the layer before, timed from its snapshot, not the source's commit), so no
    bound: their growth alone is judged.
Neither depends on where in the window a slow commit or a missed cycle falls. Reported besides:
the maximum per whole 10-minute window. From the candidate after v1.0.0-rc.2 on (#268); rc.2 and
those before it are not judged again.
  The margin leaves a blind spot: the thirds' p95 are two thirds of the window apart, so growth
slower than 0.75 / (the window in hours) minutes an hour is not caught (0.375 on 120 minutes, 1.5
on 30). It is the rule's choice, not a limit of the measure.
  No slope is judged: on 2 hours, 12 window maxima, a layer's backlog moves in steps of its own
cycles (a 30 s checkpoint, a 1-minute refresh, a commit cycle missed: 0.5 minutes in rc1q), and a
slope fitted to them depends on which windows the steps fall in (maxima 0.5 minutes higher in the
last 6 windows than in the first 6 are 0.38 minutes an hour; the other way round, -0.38).
  Up to v1.0.0-rc.2 the backlog was sampled every 10 s and the 10-minute maxima's slope was held to
0.05 minutes an hour (the M1 baseline's rule, on 24 hours); on that grid the maxima moved by a step
of about 10 s with where a catch-up fell against it, and the slope with them. That slope is still
reported, observed only, for comparison with the candidates up to rc.2.
"""
import argparse
import bisect
import collections
import decimal
import heapq
import json
import math
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "acceptance",
                                "dashboards"))
import dashboards  # noqa: E402  (the dashboards run's judge: markers, percentiles, summaries)

MAX_SKEW_MS = dashboards.MAX_SKEW_MS
CLOCK_WINDOW_MS = 90_000
KINDS = {"I": "insert", "U": "update", "D": "delete"}
# Seconds, p95 / p99 (the acceptance contract's 2.1).
TARGETS = {
    "kafka_to_ods": (120, 300),
    "source_to_ods": (120, 300),
    "ods_to_dwd": (120, 300),
    "dwd_to_dws": (120, 300),
    "paimon_to_external": (180, 360),
    "paimon_to_materialized": (300, 600),
    "source_to_external": (300, 600),
    "source_to_dws_dashboard": (900, 1800),
}
# The version markers' keys: from 5,000,000 up to the dashboards run's markers.
VERSION_FIRST, VERSION_END = 5_000_000, 10_000_000
# A load kept its rate when it sent this share of its target over the whole window, and was at
# most this far behind its schedule over the window's last minute.
RATE_SHARE = 0.99
MAX_LATE_MS = 5000
LATE_SPAN_MS = 60_000
# Steady state (#268): the window's last third against its first in p95; maxima reported per 10
# minutes.
BACKLOG_WINDOW_MS = 600_000
MAX_THIRDS_GROWTH_MIN = 0.5
BACKLOG_QUANTILE = 0.95
# Steady state's bound on a layer's largest backlog: the p99 target of the path from the source that
# ends at it (see the module's doc for the layers without one).
BACKLOG_BOUND_PATHS = {"ods": "source_to_ods", "external": "source_to_external",
                       "dws_dashboard": "source_to_dws_dashboard", "kafka_ods": "kafka_to_ods"}
# The rule up to v1.0.0-rc.2: a sample every 10 s, the slope of its maxima. Kept, observed only, for
# comparison with the candidates up to rc.2.
BACKLOG_STEP_MS = 10_000


def lines_of(path, kind=None):
    """A log's lines split on blanks; with kind, only those that start with it."""
    out = []
    if not os.path.exists(path):
        return out
    with open(path) as f:
        for line in f:
            parts = line.split()
            if parts and (kind is None or parts[0] == kind):
                out.append(parts)
    return out


def observed(path):
    """The observer's lines, as dicts, in file order (each table's in snapshot order)."""
    out = []
    if not os.path.exists(path):
        return out
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    out.append(json.loads(line))
                except ValueError:
                    pass
    return out


def observer_health(lines, tables):
    ends = {d["table"] for d in lines if d.get("type") == "END"}
    errors = collections.Counter(d["table"] for d in lines if d.get("type") == "ERROR")
    return {"ended": sorted(ends), "missing_end": sorted(set(tables) - ends),
            "errors": dict(errors)}


def cents(value):
    """A decimal string (or None, an empty SUM) as whole cents."""
    if value in (None, "NULL", "None", ""):
        return 0
    return int((decimal.Decimal(value) * 100).to_integral_value())


def integer(value):
    return 0 if value in (None, "NULL", "None", "") else int(value)


# --------------------------------------------------------------------------- clocks
class Clocks:
    """The error bounds of the clocks a sample reads (see the module's doc)."""

    def __init__(self, path, groups):
        self.series = {g: [] for g in groups}
        for parts in lines_of(path):
            if len(parts) != 5 or not parts[0].isdigit():
                continue
            error = int(parts[3]) if parts[3].isdigit() else math.inf
            for g, test in groups.items():
                if test(parts[1]):
                    self.series[g].append((int(parts[0]), error, parts[4] == "1"))
        for v in self.series.values():
            v.sort()
        self.times = {g: [m[0] for m in v] for g, v in self.series.items()}
        self.mysql, self.mysql_times = [], []  # (ms, |pod - MySQL|)

    def node(self, group, at):
        series, times = self.series[group], self.times[group]
        near = series[bisect.bisect_left(times, at - CLOCK_WINDOW_MS):
                      bisect.bisect_right(times, at + CLOCK_WINDOW_MS)]
        if not near or not all(m[2] for m in near):
            return math.inf
        return max(m[1] for m in near)

    def add_mysql(self, ops):
        self.mysql = sorted((o.source_ms, o.mysql_offset_bound()) for o in ops
                            if o.source_ms is not None)
        self.mysql_times = [m[0] for m in self.mysql]

    def source(self, at):
        near = self.mysql[bisect.bisect_left(self.mysql_times, at - CLOCK_WINDOW_MS):
                          bisect.bisect_right(self.mysql_times, at + CLOCK_WINDOW_MS)]
        if not near:
            return math.inf
        return max(m[1] for m in near) + self.node("versions", at)

    def bound(self, clock, at):
        return self.source(at) if clock == "mysql" else self.node(clock, at)

    def valid(self, a, at_a, b, at_b):
        return self.bound(a, at_a) + self.bound(b, at_b) <= MAX_SKEW_MS

    def summary(self):
        return {g: {"measurements": len(v), "max_error_ms": max((m[1] for m in v), default=None),
                    "unsynchronized": sum(1 for m in v if not m[2])}
                for g, v in self.series.items()}


# --------------------------------------------------------------------------- samples and paths
def summarize(samples, targets):
    """samples: (origin ms, end ms or None, valid). The valid ones' latencies against targets; a
    sample never seen is later than any."""
    values = [(e - o) / 1000 if e is not None else math.inf for o, e, ok in samples if ok]
    result = dashboards.summary(values, targets)
    result["voided_by_clock"] = sum(1 for _, _, ok in samples if not ok)
    result["never_seen"] = sum(1 for _, e, _ in samples if e is None)
    return result


def backlog_curve(samples, window):
    """A layer's backlog over the window, exactly. samples: (source ms, seen ms or None) of the
    changes the source confirmed. Pieces (from, to, oldest) in time order, covering the window:
    over [from, to) the oldest change waiting (confirmed, not seen yet) was confirmed at oldest, so
    the backlog there is t - oldest; None when none waits (the backlog is 0)."""
    start, end = window
    ordered = sorted((s, math.inf if e is None else e) for s, e in samples if s < end)
    # The oldest waiting changes only when a change is confirmed or seen.
    times = sorted({start, end}.union((s for s, _ in ordered if s > start),
                                      (e for _, e in ordered if start < e < end)))
    heap, i, pieces = [], 0, []
    for at, until in zip(times, times[1:]):
        while i < len(ordered) and ordered[i][0] <= at:
            heapq.heappush(heap, ordered[i])
            i += 1
        while heap and heap[0][1] <= at:
            heapq.heappop(heap)
        oldest = heap[0][0] if heap else None
        if pieces and pieces[-1][2] == oldest:
            pieces[-1] = (pieces[-1][0], until, oldest)
        else:
            pieces.append((at, until, oldest))
    return pieces


def backlog_max(pieces, a, b):
    """The backlog's largest over [a, b), in minutes: the age a change waiting then reached before
    it was seen or the span ended."""
    return max((min(to, b) - oldest for at, to, oldest in pieces
                if oldest is not None and at < b and to > a), default=0) / 60_000


def backlog_quantile(pieces, a, b, q=BACKLOG_QUANTILE):
    """The backlog's q-quantile over the time of [a, b), in minutes: the level it was above for
    1 - q of that time."""
    rises = []  # (backlog at the piece's start, at its end), ms: it rises a ms a ms between
    for at, to, oldest in pieces:
        at, to = max(at, a), min(to, b)
        if to > at and oldest is not None:
            rises.append((at - oldest, to - oldest))

    def above(x):
        return sum(v - max(u, x) for u, v in rises if v > x)

    allowed = (1 - q) * (b - a)
    lo, hi = 0.0, float(max((v for _, v in rises), default=0))
    for _ in range(50):
        mid = (lo + hi) / 2
        lo, hi = (mid, hi) if above(mid) > allowed else (lo, mid)
    return hi / 60_000


def slope(points):
    """Least squares through (hours, minutes): minutes an hour."""
    if len(points) < 2:
        return 0.0
    mx = sum(x for x, _ in points) / len(points)
    my = sum(y for _, y in points) / len(points)
    den = sum((x - mx) ** 2 for x, _ in points)
    return sum((x - mx) * (y - my) for x, y in points) / den if den else 0.0


def grid_slope(pieces, window):
    """The steady state's slope up to v1.0.0-rc.2, observed only: the backlog every
    BACKLOG_STEP_MS of the window, its maxima per whole 10-minute window at their midpoints."""
    start, end = window
    froms = [p[0] for p in pieces]
    ages = []
    for at in range(start, end, BACKLOG_STEP_MS):
        _, _, oldest = pieces[bisect.bisect_right(froms, at) - 1]
        ages.append((at, 0.0 if oldest is None else (at - oldest) / 60_000))
    maxima = []
    for w in range(start, end - BACKLOG_WINDOW_MS + 1, BACKLOG_WINDOW_MS):
        inside = [g for t, g in ages if w <= t < w + BACKLOG_WINDOW_MS]
        if inside:
            maxima.append(((w + BACKLOG_WINDOW_MS / 2 - start) / 3_600_000, max(inside)))
    return slope(maxima)


def backlog_bound(layer):
    """The bound on a layer's largest backlog, in minutes, or None (see BACKLOG_BOUND_PATHS)."""
    path = BACKLOG_BOUND_PATHS.get(layer)
    return None if path is None else TARGETS[path][1] / 60


def backlog(samples, window, bound_min=None):
    """The steady state of a layer (see the module's doc): samples are (source ms, seen ms or None)
    of every change the source confirmed since the window began. Steady when the backlog's p95 in
    the window's last third is at most MAX_THIRDS_GROWTH_MIN above the first third's, and its
    maximum over the window at most bound_min (None: no bound). The maxima per window are of whole
    10-minute windows only, as before."""
    start, end = window
    if end - start < BACKLOG_WINDOW_MS or not samples:
        return {"steady": False, "why": "no backlog samples or no whole 10-minute window"}
    pieces = backlog_curve(samples, window)
    third = (end - start) / 3
    p95 = [backlog_quantile(pieces, *span) for span in ((start, start + third), (end - third, end))]
    highest = backlog_max(pieces, start, end)
    windows = [backlog_max(pieces, w, w + BACKLOG_WINDOW_MS)
               for w in range(start, end - BACKLOG_WINDOW_MS + 1, BACKLOG_WINDOW_MS)]
    steady = (p95[1] - p95[0] <= MAX_THIRDS_GROWTH_MIN
              and (bound_min is None or highest <= bound_min))
    return {"steady": steady,
            "first_third_p95_min": round(p95[0], 4), "last_third_p95_min": round(p95[1], 4),
            "max_backlog_min": round(highest, 4),
            "windows": [round(m, 3) for m in windows],
            "grid_slope_min_per_hour": round(grid_slope(pieces, window), 4),
            "limits": {"thirds_growth_min": MAX_THIRDS_GROWTH_MIN, "max_backlog_min": bound_min}}


def late_at_end(late, window):
    """How far behind its schedule a load was over the window's last minute: (ms, late) lines."""
    start, end = window
    inside = [v for t, v in late if max(start, end - LATE_SPAN_MS) <= t < end]
    return max(inside) if inside else None


def at_time(series, t):
    """A cumulative counter's value at t: the last (ms, value) at or before t (0 before any)."""
    times = [s[0] for s in series]
    i = bisect.bisect_right(times, t)
    return series[i - 1][1] if i else 0


# --------------------------------------------------------------------------- version markers
class Op:
    __slots__ = ("seq", "kind", "key", "version", "cents", "source_ms", "pod_ms", "pod_before_ms")

    def __init__(self, seq, kind, key, version, cents_, source_ms=None, pod_ms=None,
                 pod_before_ms=None):
        self.seq, self.kind, self.key, self.version = seq, kind, key, version
        self.cents, self.source_ms, self.pod_ms = cents_, source_ms, pod_ms
        self.pod_before_ms = pod_before_ms

    def mysql_offset_bound(self):
        """How far MySQL's clock can be from the pod's: MySQL read it between the pod's two reads
        (before the call and after its answer). A log of the earlier form has the later only,
        which bounds MySQL running behind the pod but not ahead."""
        if self.pod_before_ms is None:
            return abs(self.pod_ms - self.source_ms)
        return max(abs(self.source_ms - self.pod_before_ms), abs(self.pod_ms - self.source_ms))


def version_ops(path):
    """The ops committed, in commit order; "opx" ones are committed but their time unknown, "skip"
    ones were not committed."""
    ops = []
    for parts in lines_of(path):
        if parts[0] == "op" and len(parts) == 9:
            ops.append(Op(int(parts[1]), parts[2], int(parts[3]), int(parts[4]), int(parts[5]),
                          int(parts[6]), int(parts[8]), int(parts[7])))
        elif parts[0] == "op" and len(parts) == 8:
            # The earlier form: no pod time before the call.
            ops.append(Op(int(parts[1]), parts[2], int(parts[3]), int(parts[4]), int(parts[5]),
                          int(parts[6]), int(parts[7])))
        elif parts[0] == "opx" and len(parts) == 6:
            ops.append(Op(int(parts[1]), parts[2], int(parts[3]), int(parts[4]), int(parts[5])))
    ops.sort(key=lambda o: o.seq)
    return ops


def prefixes(ops):
    """The version markers' totals after each prefix of the ops (live keys, versions, cents): an
    index from totals to the prefix lengths that have them."""
    live, versions, total = 0, 0, 0
    rows = {}
    index = collections.defaultdict(list)
    index[(0, 0, 0)].append(0)
    for p, o in enumerate(ops, 1):
        if o.kind == "I":
            live, versions, total = live + 1, versions + o.version, total + o.cents
            rows[o.key] = (o.version, o.cents)
        elif o.kind == "U":
            v, c = rows.get(o.key, (0, 0))
            versions, total = versions + o.version - v, total + o.cents - c
            rows[o.key] = (o.version, o.cents)
        else:
            v, c = rows.pop(o.key, (0, 0))
            live, versions, total = live - 1, versions - v, total - c
        index[(live, versions, total)].append(p)
    return index


def prefix_seen(states, index, n):
    """states: (ms, totals) in time order. When each prefix length (1..n) was first held, and the
    findings: totals that are no prefix, and a prefix older than one held before."""
    seen, held, not_prefix, older = [None] * (n + 1), 0, 0, 0
    for at, totals in states:
        candidates = index.get(totals)
        if not candidates:
            not_prefix += 1
            continue
        later = [p for p in candidates if p >= held]
        if not later:
            older += 1
            continue
        p = later[0]
        for q in range(held + 1, p + 1):
            seen[q] = at
        held = p
    return seen, {"not_a_prefix": not_prefix, "older_than_before": older}


def key_changes(lines, table, key, ops):
    """When each op became readable in a table, keyed by seq, and the findings.

    A table may show only the net change of what one commit brought: DWD's lookup changelog turns
    an update and a delete of a key in one commit into one -D of the old version. So an op is
    readable at the first snapshot showing its own effect or that of a later op of the same key
    (never another key's: a kind that lags cannot hide behind other keys). A key's -D followed by
    +I of it in the same snapshot is an update (ODS's writer, Paimon's CDC sink, writes one so).
    Findings: an amount not its version's checksum, a version read after a later one, a key read
    after its delete, and a key whose final state is not what its ops up to the latest one shown
    in the table leave."""
    by_key = collections.defaultdict(dict)
    ops_by_seq = {o.seq: o for o in ops}
    for o in ops:
        by_key[o.key][o.kind] = o
    findings, reached = collections.Counter(), []
    state = {}  # key -> (highest version read, deleted, present version or None)
    groups, current = [], None
    for d in lines:
        if d.get("type") != "CHANGE" or d.get("table") != table:
            continue
        k = int(d["row"][key])
        if not VERSION_FIRST <= k < VERSION_END or k not in by_key:
            continue
        if current is None or d["snapshot"] != current[0]:
            current = (d["snapshot"], d["committedAt"], collections.defaultdict(list))
            groups.append(current)
        current[2][k].append(d)
    for _, at, keys in groups:
        for k, changes in keys.items():
            mine = by_key[k]
            for i, d in enumerate(changes):
                high, deleted, present = state.get(k, (0, False, None))
                if d["kind"] == "-U":
                    continue
                if d["kind"] == "-D":
                    if any(c["kind"] in ("+I", "+U") for c in changes[i + 1:]):
                        continue  # an update's before image
                    state[k] = (high, True, None)
                    if "D" in mine:
                        reached.append((at, mine["D"].seq))
                    continue
                version = integer(d["row"].get("version_no"))
                if deleted:
                    findings["read_after_delete"] += 1
                if version < high:
                    findings["older_version_after_newer"] += 1
                expected = mine.get("I") if version == 1 else mine.get("U")
                if expected is None or expected.cents != cents(d["row"].get("amount")):
                    findings["checksum_mismatch"] += 1
                else:
                    reached.append((at, expected.seq))
                state[k] = (max(high, version), deleted, version)
    # Each op: the first time its own effect, or a later op's of the same key, showed.
    seen, firsts = {}, collections.defaultdict(dict)
    for at, seq in reached:
        firsts[ops_by_seq[seq].key][seq] = min(firsts[ops_by_seq[seq].key].get(seq, at), at)
    for k, mine in by_key.items():
        shown = firsts.get(k, {})
        for o in mine.values():
            times = [at for seq, at in shown.items() if seq >= o.seq]
            if times:
                seen[o.seq] = min(times)
    best = max((seq for _, seq in reached), default=0)
    # What the ops shown so far leave each key as, against what the table shows.
    for k, mine in by_key.items():
        done = [o for o in mine.values() if o.seq <= best]
        if not done:
            continue
        last = max(done, key=lambda o: o.seq)
        expected = None if last.kind == "D" else last.version
        if state.get(k, (0, False, None))[2] != expected:
            findings["final_state_mismatch"] += 1
    return seen, dict(findings)


def dws_states(lines, table):
    """Per DWS snapshot, its rows replayed from its changes, the version markers' totals summed over
    them: (commit ms, totals)."""
    rows, out, current, at = {}, [], None, None

    def close():
        if current is not None:
            out.append((at, (sum(integer(r.get("rel_live")) for r in rows.values()),
                             sum(integer(r.get("rel_versions")) for r in rows.values()),
                             sum(cents(r.get("rel_amount")) for r in rows.values()))))

    for d in lines:
        if d.get("type") != "CHANGE" or d.get("table") != table:
            continue
        if d["snapshot"] != current:
            close()
            current, at = d["snapshot"], d["committedAt"]
        if d["kind"] in ("+I", "+U"):
            rows[d["row"]["region"]] = d["row"]
        else:
            rows.pop(d["row"]["region"], None)
    close()
    return out


def doris_states(path, kind):
    """The prober's answers on one path: (end ms, totals)."""
    out = []
    for parts in lines_of(path, kind):
        if len(parts) >= 7 and parts[3] == "OK":
            try:
                out.append((int(parts[2]), (integer(parts[4]), integer(parts[5]), cents(parts[6]))))
            except (ValueError, decimal.InvalidOperation):
                continue
    # By time alone: answers at the same moment keep the order they were written in.
    return sorted(out, key=lambda s: s[0])


def layers(args):
    rel = args.release
    ops = version_ops(os.path.join(rel, "versions.log"))
    lines = observed(os.path.join(rel, "observed.jsonl"))
    tables = {}
    for parts in lines_of(os.path.join(rel, "tables")):
        if len(parts) == 2:
            tables[parts[0]] = parts[1]
    ods, dwd, dws = tables.get("ods"), tables.get("dwd"), tables.get("dws")
    start, end = args.window
    run = args.run
    clocks = Clocks(os.path.join(rel, "clocks.tsv"), {
        "versions": lambda p: p.endswith("/versions-" + run),
        "prober": lambda p: p.endswith("/prober-" + run),
        "ods": lambda p: ("/dash-" + run + "-") in p and "taskmanager" in p,
        "dwd": lambda p: ("/dash-dwd-" + run + "-") in p and "taskmanager" in p,
        "dws": lambda p: ("/dash-dws-" + run + "-") in p and "taskmanager" in p,
    })
    clocks.add_mysql(ops)
    index = prefixes(ops)
    position = {o.seq: p for p, o in enumerate(ops, 1)}
    ods_seen, ods_findings = key_changes(lines, ods, "id", ops)
    dwd_seen, dwd_findings = key_changes(lines, dwd, "order_id", ops)
    states = {"dws": dws_states(lines, dws)}
    for path in ("live", "mv", "dws_dashboard"):
        states[path] = doris_states(os.path.join(rel, "doris.log"), path)
    by_prefix, prefix_findings = {}, {}
    for name, series in states.items():
        by_prefix[name], prefix_findings[name] = prefix_seen(series, index, len(ops))

    def prefix_time(name, o):
        return by_prefix[name][position[o.seq]]

    source = lambda o: o.source_ms  # noqa: E731
    paths = {
        "source_to_ods": (source, "mysql", lambda o: ods_seen.get(o.seq), "ods"),
        "ods_to_dwd": (lambda o: ods_seen.get(o.seq), "ods", lambda o: dwd_seen.get(o.seq), "dwd"),
        "dwd_to_dws": (lambda o: dwd_seen.get(o.seq), "dwd", lambda o: prefix_time("dws", o), "dws"),
        "source_to_external": (source, "mysql", lambda o: prefix_time("live", o), "prober"),
        "paimon_to_external": (lambda o: ods_seen.get(o.seq), "ods",
                               lambda o: prefix_time("live", o), "prober"),
        "paimon_to_materialized": (lambda o: ods_seen.get(o.seq), "ods",
                                   lambda o: prefix_time("mv", o), "prober"),
        "source_to_dws_dashboard": (source, "mysql", lambda o: prefix_time("dws_dashboard", o),
                                    "prober"),
    }
    inside = [o for o in ops if o.source_ms is not None and start <= o.source_ms < end]
    result = {"window": [start, end], "tables": tables,
              "ops": {KINDS[k]: sum(1 for o in inside if o.kind == k) for k in KINDS},
              "paths": {}}
    for name, (origin, origin_clock, seen, end_clock) in paths.items():
        result["paths"][name] = {}
        for k, kind in KINDS.items():
            samples, no_origin = [], 0
            for o in inside:
                if o.kind != k:
                    continue
                o_at, e_at = origin(o), seen(o)
                if o_at is None:
                    # Never readable upstream: that path counts it as never seen.
                    no_origin += 1
                    continue
                ok = clocks.valid(origin_clock, o_at, end_clock, e_at if e_at is not None else o_at)
                samples.append((o_at, e_at, ok))
            summary = summarize(samples, TARGETS[name])
            summary["no_origin"] = no_origin
            result["paths"][name][kind] = summary
    # Each end layer's backlog, from the source's confirmations: every op since the window began.
    after = [o for o in ops if o.source_ms is not None and o.source_ms >= start]
    ends = {"ods": lambda o: ods_seen.get(o.seq), "dwd": lambda o: dwd_seen.get(o.seq),
            "dws": lambda o: prefix_time("dws", o), "external": lambda o: prefix_time("live", o),
            "materialized": lambda o: prefix_time("mv", o),
            "dws_dashboard": lambda o: prefix_time("dws_dashboard", o)}
    result["steady"] = {layer: backlog([(o.source_ms, f(o)) for o in after], (start, end),
                                       backlog_bound(layer))
                        for layer, f in ends.items()}
    result["findings"] = {"ods": ods_findings, "dwd": dwd_findings, **prefix_findings}
    result["observer"] = observer_health(lines, [t for t in (ods, dwd, dws) if t])
    result["clock"] = {"groups": clocks.summary(), "max_ms": MAX_SKEW_MS,
                       "mysql_max_difference_ms": max((m[1] for m in clocks.mysql), default=None)}
    result["rate"] = cdc_rate(rel, args.marks, ops, (start, end), args.cdc_target)
    ok = (all(s["pass"] for p in result["paths"].values() for s in p.values())
          and all(s["steady"] for s in result["steady"].values())
          and not any(sum(f.values()) for f in result["findings"].values())
          and not result["observer"]["missing_end"] and not result["observer"]["errors"]
          and result["rate"]["kept"])
    with open(args.out, "w") as f:
        json.dump(result, f, indent=1, default=str)
    print(json.dumps({name: {k: brief(v) for k, v in p.items()}
                      for name, p in result["paths"].items()}, default=str))
    return 0 if ok else 1


def cdc_rate(rel, marks_log, ops, window, target):
    """The rate the source took over the whole window: the change load's, the dashboards run's
    markers' and the version markers'."""
    start, end = window
    seconds = (end - start) / 1000
    load = lines_of(os.path.join(rel, "cdc-load.log"), "load")
    changes = [(int(p[1]), int(p[3])) for p in load]
    late = [(int(p[1]), int(p[4])) for p in load]
    failed = [(int(p[1]), int(p[5])) for p in load if len(p) > 5]
    loaded = at_time(changes, end) - at_time(changes, start)
    markers = sum(1 for p in lines_of(marks_log, "mark") if start <= int(p[2]) < end)
    versions = sum(1 for o in ops if o.source_ms is not None and start <= o.source_ms < end)
    total = (loaded + markers + versions) / seconds if seconds > 0 else 0
    behind = late_at_end(late, window)
    return {"kept": total >= RATE_SHARE * target and behind is not None and behind <= MAX_LATE_MS,
            "target": target, "per_second": round(total, 1),
            "load_per_second": round(loaded / seconds, 1) if seconds else 0,
            "markers_per_second": round(markers / seconds, 1) if seconds else 0,
            "versions_per_second": round(versions / seconds, 1) if seconds else 0,
            "late_at_end_ms": behind,
            "failed_transactions": at_time(failed, end) - at_time(failed, start)}


def brief(v):
    if isinstance(v, dict) and "samples" in v:
        return {x: v.get(x) for x in ("samples", "p95", "p99", "judged", "pass")}
    return v


# --------------------------------------------------------------------------- Kafka events
class Coverage:
    """One partition's offsets as snapshots bring them: the prefix every offset of which came
    (next), the ranges beyond it, and the offsets that came more than once."""

    def __init__(self):
        self.next = 0
        self.ahead = []  # sorted, disjoint [from, to]
        self.repeated = 0

    def add(self, lo, hi):
        if hi < self.next:
            self.repeated += hi - lo + 1
            return
        if lo < self.next:
            self.repeated += self.next - lo
            lo = self.next
        merged = []
        for a, b in self.ahead:
            if b < lo - 1 or a > hi + 1:
                merged.append([a, b])
                continue
            overlap = min(b, hi) - max(a, lo) + 1
            if overlap > 0:
                self.repeated += overlap
            lo, hi = min(a, lo), max(b, hi)
        merged.append([lo, hi])
        merged.sort()
        self.ahead = merged
        while self.ahead and self.ahead[0][0] == self.next:
            self.next = self.ahead.pop(0)[1] + 1

    def missing_below(self, end):
        """Offsets below end never brought."""
        have = sum(min(b, end - 1) - a + 1 for a, b in self.ahead if a < end)
        return max(0, end - self.next - have)


def events(args):
    ev = args.events
    acks = [(int(p[1]), int(p[2]), int(p[3]), int(p[4]))
            for p in lines_of(os.path.join(ev, "producer.log"), "ack")]
    lines = observed(os.path.join(ev, "observed.jsonl"))
    start, end = args.window
    run = args.run
    clocks = Clocks(os.path.join(ev, "clocks.tsv"), {
        "broker": lambda p: p.startswith(args.broker_namespace + "/"),
        "source": lambda p: ("/pev-" + run + "-") in p and "taskmanager" in p,
    })
    pending = collections.defaultdict(list)
    for n, partition, offset, append in acks:
        pending[partition].append((offset, n, append))
    for v in pending.values():
        v.sort()
    seen, coverage, table = {}, {}, None
    for d in lines:
        if d.get("type") != "OFFSETS":
            continue
        table = d["table"]
        for partition, ranges in d["ranges"].items():
            p = int(partition)
            c = coverage.setdefault(p, Coverage())
            before = c.next
            for lo, hi in ranges:
                c.add(int(lo), int(hi))
            if c.next == before:
                continue
            queue = pending.get(p, [])
            i = bisect.bisect_left(queue, (before, -1, -1))
            while i < len(queue) and queue[i][0] < c.next:
                seen[queue[i][1]] = d["committedAt"]
                i += 1
    samples = []
    for n, partition, offset, append in acks:
        if not start <= append < end:
            continue
        at = seen.get(n)
        ok = clocks.valid("broker", append, "source", at if at is not None else append)
        samples.append((append, at, ok))
    finals = lines_of(os.path.join(ev, "producer.log"), "end")
    highest = {}
    if finals:
        for item in finals[-1][4:]:
            partition, high, _ = item.split(":")
            highest[int(partition)] = int(high)
    stats = [(int(p[1]), int(p[3]), int(p[5])) for p in lines_of(os.path.join(ev, "producer.log"),
                                                                    "stat")]
    seconds = (end - start) / 1000
    # What the broker acknowledged over the window: sends it refused do not count, and any refusal
    # of the run fails it.
    acked = at_time([(t, a) for t, a, _ in stats], end) - at_time([(t, a) for t, a, _ in stats], start)
    behind = late_at_end([(t, late) for t, _, late in stats], (start, end))
    errors = len(lines_of(os.path.join(ev, "producer.log"), "err"))
    if finals:
        errors = max(errors, int(finals[-1][3]))
    rate = {"kept": (seconds > 0 and acked / seconds >= RATE_SHARE * args.rate
                     and behind is not None and behind <= MAX_LATE_MS and errors == 0),
            "target": args.rate, "per_second": round(acked / seconds, 1) if seconds else 0,
            "late_at_end_ms": behind, "errors": errors}
    exact = {
        "repeated": sum(c.repeated for c in coverage.values()),
        "missing": sum(coverage.get(p, Coverage()).missing_below(h + 1) for p, h in highest.items()),
        "partitions": {p: {"highest_sent": highest.get(p), "readable_to": coverage[p].next - 1}
                       for p in sorted(coverage)},
        "producer_end": " ".join(finals[-1]) if finals else None,
    }
    path = summarize(samples, TARGETS["kafka_to_ods"])
    result = {
        "table": table,
        "window": [start, end],
        "samples_sent": len(acks),
        "observer": observer_health(lines, [table] if table else []),
        "clock": {"groups": clocks.summary(), "max_ms": MAX_SKEW_MS},
        "exactly_once": exact,
        "rate": rate,
        "paths": {"kafka_to_ods": {"insert": path}},
        "steady": {"kafka_ods": backlog([(a, s) for a, s, _ in samples], (start, end),
                                        backlog_bound("kafka_ods"))},
    }
    ok = (path["pass"] and result["steady"]["kafka_ods"]["steady"] and rate["kept"]
          and exact["repeated"] == 0 and exact["missing"] == 0 and bool(finals)
          and not result["observer"]["missing_end"] and not result["observer"]["errors"])
    with open(args.out, "w") as f:
        json.dump(result, f, indent=1, default=str)
    print(json.dumps({"kafka_to_ods": brief(path), "rate": rate, "exactly_once": exact["repeated"]
                      == 0 and exact["missing"] == 0}, default=str))
    return 0 if ok else 1


# --------------------------------------------------------------------------- the report
# (label, origin, end, file, path key, its end layer's steady-state key)
PATHS = [
    ("Kafka 事件 → ODS", "broker 追加", "ODS 快照可流读", "events.json", "kafka_to_ods", "kafka_ods"),
    ("MySQL → ODS", "源库提交", "ODS 快照可流读", "layers.json", "source_to_ods", "ods"),
    ("ODS → DWD", "ODS 快照", "DWD 快照", "layers.json", "ods_to_dwd", "dwd"),
    ("DWD → DWS", "DWD 快照", "DWS 快照", "layers.json", "dwd_to_dws", "dws"),
    ("Paimon → Doris 外表", "ODS 快照", "外表首次查到", "layers.json", "paimon_to_external",
     "external"),
    ("Paimon → Doris 物化视图", "ODS 快照", "物化视图首次查到", "layers.json",
     "paimon_to_materialized", "materialized"),
    ("MySQL → Doris ODS 外表", "源库提交", "外表首次查到", "layers.json", "source_to_external",
     "external"),
    ("MySQL → DWS 看板", "源库提交", "看板物化视图首次查到", "layers.json",
     "source_to_dws_dashboard", "dws_dashboard"),
]
# (label, key, judged): the dashboard queries of the dashboards run (doris/load.json).
QUERIES = [
    ("固定看板查询（负载下）", "dashboard", True),
    ("固定看板查询（突发期间）", "dashboard_during_burst", True),
    ("内表点查（负载下，仅观测）", "internal", False),
    ("外表交互点查（负载下，仅观测）", "lookup", False),
]
# The groups of doris/load.json's schedule.
QUERY_GROUPS = {"dashboard": "固定看板查询", "dashboard_during_burst": "突发期间的固定看板查询",
                "internal": "内表点查", "lookup": "外表交互点查"}
KIND_LABELS = {"insert": "插入", "update": "更新", "delete": "删除"}
# The dashboards run's own performance verdicts, superseded here; the rest are its functional
# checks, which a valid run passes.
SUPERSEDED = {"load-within-targets", "load-on-schedule", "dashboard-under-burst", "steady-state"}


def fmt(v):
    if v is None:
        return "—"
    if isinstance(v, float) and math.isinf(v):
        return "∞"
    if isinstance(v, float):
        # Three significant digits, never in exponent form (2,000 events a second, not 2e+03).
        return f"{v:.0f}" if abs(v) >= 100 else f"{v:.3g}"
    return str(v)


def load_json(file):
    try:
        with open(file) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def report(args):
    root = args.root
    docs = {name: load_json(os.path.join(root, name)) for name in ("layers.json", "events.json")}
    load = load_json(os.path.join(root, "dashboards/doris/load.json")) or {}
    rows = []

    def row(label, origin, until, p, steady=None, judged_row=True):
        if p is None:
            result = "MISSING"
        elif not judged_row:
            result = "OBSERVED"
        elif not p.get("judged"):
            result = "NOT_JUDGED"
        else:
            within = p.get("within", p.get("pass"))
            result = "PASS" if within and (steady is None or steady.get("steady")) else "FAIL"
        rows.append({"path": label, "from": origin, "to": until, "result": result,
                     "steady": None if steady is None else bool(steady.get("steady")),
                     **({k: p.get(k) for k in ("samples", "p50", "p95", "p99", "max", "targets",
                                                "voided_by_clock", "never_seen")} if p else {})})

    for label, origin, until, name, key, layer in PATHS:
        doc = docs[name] or {}
        steady = (doc.get("steady") or {}).get(layer)
        kinds = ("insert",) if key == "kafka_to_ods" else tuple(KIND_LABELS)
        for kind in kinds:
            p = ((doc.get("paths") or {}).get(key) or {}).get(kind)
            row(f"{label}（{KIND_LABELS[kind]}）", origin, until, p, steady)
    for label, key, judged in QUERIES:
        row(label, "应发出", "返回", load.get(key), None, judged)
    checks = []
    layers_doc, events_doc = docs["layers.json"] or {}, docs["events.json"] or {}
    for name, doc in (("MySQL", layers_doc), ("Kafka", events_doc)):
        r = doc.get("rate") or {}
        checks.append((f"{name} 负载速率（整个窗口）", bool(r.get("kept")),
                       f"{fmt(r.get('per_second'))}/秒，目标 {fmt(r.get('target'))}；"
                       f"窗口最后一分钟最多落后 {fmt(r.get('late_at_end_ms'))} ms；"
                       f"失败 {fmt(r.get('failed_transactions', r.get('errors')))}"))
    schedule = load.get("schedule") or {}
    share, log = schedule.get("on_time_share"), schedule.get("log") or {}
    checks.append(("看板负载按时发出", bool(schedule.get("kept")),
                   "；".join(f"{QUERY_GROUPS.get(g, g)}发出 {fmt(v.get('sent'))} 个，晚于应发出 "
                            f"{fmt(schedule.get('late_after_ms'))} ms 以上的 {fmt(v.get('late'))} 个，"
                            f"最晚 {fmt(v.get('max_late_ms'))} ms"
                            for g, v in (schedule.get("groups") or {}).items())
                   + f"（每组至多 {fmt(None if share is None else 100 - 100 * share)}%）；日志"
                   + ("完整" if log.get("whole") else "不完整") + "：收回 "
                   + json.dumps(log.get("recorded"), ensure_ascii=False) + "，负载 Pod 写出 "
                   + json.dumps(log.get("written"), ensure_ascii=False)))
    errors = load.get("errors")
    checks.append(("看板查询没有出错", errors == {},
                   "—" if errors is None else json.dumps(errors, ensure_ascii=False)))
    for name, doc in (("layers.json", layers_doc), ("events.json", events_doc)):
        for layer, s in sorted((doc.get("steady") or {}).items()):
            limits = s.get("limits") or {}
            bound = limits.get("max_backlog_min")
            checks.append((f"稳态：{layer}", bool(s.get("steady")),
                           f"前后三分之一 p95 {fmt(s.get('first_third_p95_min'))} → "
                           f"{fmt(s.get('last_third_p95_min'))} 分钟（至多高 "
                           f"{fmt(limits.get('thirds_growth_min'))} 分钟）；整个窗口最大 "
                           f"{fmt(s.get('max_backlog_min'))} 分钟（"
                           + (f"上限 {fmt(bound)} 分钟" if bound is not None else "无上限")
                           + f"）；10 秒格点的斜率 {fmt(s.get('grid_slope_min_per_hour'))} 分钟/小时"
                           "（仅观测，与 rc.2 及以前比较）"))
    findings = layers_doc.get("findings") or {}
    total = sum(sum(f.values()) for f in findings.values()) if layers_doc else None
    checks.append(("正确性（版本、校验和、删除、前缀）", total == 0,
                   json.dumps(findings, ensure_ascii=False)))
    exact = events_doc.get("exactly_once") or {}
    checks.append(("事件恰好一次", exact.get("repeated") == 0 and exact.get("missing") == 0,
                   f"重复 {fmt(exact.get('repeated'))}，缺失 {fmt(exact.get('missing'))}"))
    for name, doc in (("layers.json", layers_doc), ("events.json", events_doc)):
        h = doc.get("observer") or {}
        checks.append((f"观测器完整（{name}）",
                       bool(doc) and not h.get("missing_end") and not h.get("errors"),
                       f"结束 {len(h.get('ended') or [])} 张表，缺 {h.get('missing_end')}，"
                       f"读失败 {h.get('errors')}"))
    for name, doc in (("layers.json", layers_doc), ("events.json", events_doc)):
        groups = (doc.get("clock") or {}).get("groups") or {}
        measured = all(g.get("measurements") for g in groups.values()) and bool(groups)
        checks.append((f"时钟（{name}）", measured,
                       "；".join(f"{g} {v.get('measurements')} 次，最大误差 "
                                 f"{fmt(v.get('max_error_ms'))} ms，未同步 {v.get('unsynchronized')}"
                                 for g, v in groups.items())))
    verdicts = [line.rstrip("\n").split("\t") for line in open(os.path.join(
        root, "dashboards/verdicts.tsv"))] if os.path.exists(os.path.join(
            root, "dashboards/verdicts.tsv")) else []
    functional = [v for v in verdicts if len(v) >= 3 and v[1] not in SUPERSEDED
                  and not v[1].startswith("fresh-")]
    failed = [v[1] for v in functional if v[2] != "PASS"]
    checks.append(("看板运行的功能检查", bool(functional) and not failed,
                   f"{len(functional) - len(failed)}/{len(functional)} PASS"
                   + (f"；未通过 {failed}" if failed else "")))
    statuses = load_json(os.path.join(root, "statuses.json")) or {}
    checks.append(("各运行的退出与清理", bool(statuses) and all(v == 0 for v in statuses.values()),
                   json.dumps(statuses, ensure_ascii=False)))
    results = {r["result"] for r in rows}
    outcome = "PASS"
    if "FAIL" in results or "MISSING" in results or not all(ok for _, ok, _ in checks):
        outcome = "FAIL"
    elif "NOT_JUDGED" in results:
        outcome = "NOT_JUDGED"
    doc = {"outcome": outcome, "rows": rows,
           "checks": [{"check": c, "ok": bool(ok), "value": v} for c, ok, v in checks]}
    with open(os.path.join(os.path.dirname(os.path.abspath(args.out)), "release.json"), "w") as f:
        json.dump(doc, f, indent=1, default=str)
    out = ["| 路径 | 起点 → 终点 | 样本 | 作废 | p50 | p95 | p99 | 最大 | 目标 p95 / p99 | 终点稳态 "
           "| 判定 |",
           "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for r in rows:
        targets = r.get("targets") or [None, None]
        target = "—" if targets[0] is None or math.isinf(targets[0]) else \
            f"{fmt(targets[0])} / {fmt(targets[1])} 秒"
        steady = "—" if r["steady"] is None else ("是" if r["steady"] else "否")
        out.append(f"| {r['path']} | {r['from']} → {r['to']} | {fmt(r.get('samples'))} | "
                   f"{fmt(r.get('voided_by_clock'))} | {fmt(r.get('p50'))} | {fmt(r.get('p95'))} | "
                   f"{fmt(r.get('p99'))} | {fmt(r.get('max'))} | {target} | {steady} | "
                   f"{r['result']} |")
    out += ["", "| 检查 | 结果 | 数值 |", "| --- | --- | --- |"]
    out += [f"| {c} | {'PASS' if ok else 'FAIL'} | {v} |" for c, ok, v in checks]
    out += ["", f"结论：{outcome}"]
    with open(args.out, "w") as f:
        f.write("\n".join(out) + "\n")
    print("\n".join(out))
    return 0 if outcome == "PASS" else 1


def parser():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = p.add_subparsers(dest="command", required=True)
    s = sub.add_parser("layers")
    s.add_argument("release")
    s.add_argument("marks")
    s.add_argument("out")
    s.add_argument("--window", nargs=2, type=int, required=True)
    s.add_argument("--run", required=True)
    s.add_argument("--cdc-target", type=float, default=200)
    s.set_defaults(func=layers)
    s = sub.add_parser("events")
    s.add_argument("events")
    s.add_argument("out")
    s.add_argument("--window", nargs=2, type=int, required=True)
    s.add_argument("--run", required=True)
    s.add_argument("--broker-namespace", required=True)
    s.add_argument("--rate", type=float, required=True)
    s.set_defaults(func=events)
    s = sub.add_parser("report")
    s.add_argument("root")
    s.add_argument("out")
    s.set_defaults(func=report)
    return p


def main(argv=None):
    args = parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
