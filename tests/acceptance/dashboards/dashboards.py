#!/usr/bin/env python3
"""Judges the dashboards run's Doris measurements (the acceptance contract's 2.1: p95 and p99 over
at least 10,000 valid samples a path; fewer are observed, not judged).

  dashboards.py load <load.log> <out.json> --burst <from-ms> <to-ms>
      The load pod's lines, "<kind> <due-ms> <sent> <answered> OK|ERR|SKIP [<error>]" (sent and
      answered in ms after due), and its last line, "load done totals=<n> hot=<n> lookup=<n>". The
      queries went open-loop: 10 fixed dashboard queries a second (totals), 5 point lookups of the
      internal table (hot) and 1 interactive lookup (lookup). Each response time counts from when
      the query was due, so a slow Doris cannot hold the load back; a query that failed or was not
      sent (ERR, SKIP) is one that never answered, a sample too late for any target. The fixed
      query's also while the ad-hoc burst ran. The schedule, for each of those groups: kept when at least
      99 % of its queries were sent within a second of when they were due; otherwise its times
      carry the pod's lag in sending as well as Doris's, and the judged groups are not judged.
      Nor are they when the log is not whole: a kind whose lines do not match the last line's
      count, or no last line (the kubelet keeps only the end of a container's output past 10 MiB).
  dashboards.py fresh <marks.log> <changelog.jsonl,...> <out.json> --window <from-ms> <to-ms>
          [--paimon-skew-ms <ms>]
      The marker pod's lines: "mark <id> <source-ms> <pod-ms>" for each source transaction (one
      order, its commit time by MySQL's clock), and its polls of each read path, "<path> <start-ms>
      <end-ms> OK <count> <max-id>" (live: the external view; mv: the materialized view over ODS;
      dws: the one over DWS; hotm: the internal table). A marker is first seen on a path at the end
      of the first poll whose answer holds it and every marker before it (a poll that holds a
      later one but not all before it is a gap, and does not count). Per marker in the window:
      source to the external tables and to the DWS dashboard; Paimon's commit of ODS (LakeDump
      changelog committedAt) to the external tables, the materialized view and the internal table.
      A marker never seen counts as too late. The pod's clock against MySQL's from each marker's
      round trip; the Flink pods' against the pod's (--paimon-skew-ms), for Paimon's commit times.
      Steady state: the source-to-external latency of the window's last five minutes no more than
      a minute above its first five's (the backlog does not grow).
  dashboards.py burst <audit.tsv> <out.json> <group>=<limit>|<group><=<limit>...
          [--sampled <log> --window <from-ms> <to-ms>]
      The audit log's rows of the ad-hoc users while they ran ("<ms> <user> <workload group>
      <state> <query ms> <queue ms> <error>", tab-separated): how many waited or were turned away.
      How many ran at once in each group: with --sampled, the FE's own count ("<ms> <group>
      <running> <waiting>" lines of SHOW WORKLOAD GROUPS), which is what its queue admits; without,
      from the log (from the end of its queueing to its end). A logged query's time includes the
      FE's planning, which for an external table reads the catalog and comes before the queue: the
      log's overlap is kept, but only as observed. The count must cover the window, a sample a
      second at least. "=" asks the limit held and was reached: with the count, it ran that many
      at once and some waited (or were turned away); without, queries waited or were turned away.
      "<=" asks only that it held.
  dashboards.py audit <audit.tsv> <user>:<object>:EOF|ERR...
      The audit log's counts by user, state and object ("<user> <state> <object> <count>",
      tab-separated): every triple asked for was logged at least once.
  dashboards.py recovered <marks.log> <injected-ms> <marker-id>
      After an injection: for each path, when it answered again for good (three answers since its
      last failure) and, for the lake paths, read the marker committed when the injection began or
      a later one with its lag back within the steady-state p95. Exit 0 once every path did.
  dashboards.py rows <doris.tsv> <lake.jsonl> <key,...> <column,...>
      The rows Doris returned (tab-separated, a header line) against the lake table's (LakeDump
      rows, one JSON per line), by key, on the columns named: equal or not, with samples.
"""
import argparse
import bisect
import json
import math
import re
import sys

MIN_SAMPLES = 10_000
# The load pod kept its schedule for a group of queries when at least this share of them was
# sent within this many ms of when it was due.
ON_TIME_SHARE = 0.99
MAX_SEND_LATE_MS = 1000
# Seconds. The acceptance contract's 2.1: the fixed dashboard query p95 3, p99 10 (and the internal
# table's lookups, a fixed dashboard's too); source to the Doris ODS external table 5 / 10 minutes;
# source to the DWS dashboard 15 / 30; Paimon commit to the external table 3 / 6, to a materialized
# copy 5 / 10.
TARGETS = {
    "dashboard": (3, 10),
    "source_to_external": (300, 600),
    "source_to_dws_dashboard": (900, 1800),
    "paimon_to_external": (180, 360),
    "paimon_to_materialized": (300, 600),
    "paimon_to_internal": (300, 600),
}
# A recovered path's lag is back within its steady-state p95: source to it, in seconds.
RECOVERED_LAG = {"live": 300, "mv": 600, "dws": 900, "hotm": 600}
# A clock further than this from the source's (or the Flink pods' from the marker pod's) makes the
# samples that use it invalid.
MAX_SKEW_MS = 1000
STEADY_MINUTES = 5
STEADY_GROWTH = 60
UNBOUNDED = (math.inf, math.inf)


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, max(0, math.ceil(p * len(ordered)) - 1))]


def summary(values, targets):
    p95, p99 = percentile(values, 0.95), percentile(values, 0.99)
    judged = len(values) >= MIN_SAMPLES
    within = bool(values) and p95 <= targets[0] and p99 <= targets[1]
    return {
        "samples": len(values),
        "p50": percentile(values, 0.50),
        "p95": p95,
        "p99": p99,
        "max": max(values) if values else None,
        "targets": list(targets),
        "judged": judged,
        "within": within,
        # Fewer than the acceptance contract's samples are observed only: they pass nothing.
        "pass": judged and within,
    }


def brief(v):
    if isinstance(v, dict) and "samples" in v:
        return {x: v.get(x) for x in ("samples", "p95", "p99", "judged", "pass")}
    return v


def lines(path):
    """The lines whose second and third fields are times."""
    with open(path) as f:
        for line in f:
            parts = line.rstrip("\n").split(" ")
            if len(parts) >= 4 and parts[1].isdigit() and parts[2].isdigit():
                yield parts


def on_schedule(lateness):
    """A group's queries against their schedule: lateness is each sent one's ms after it was due."""
    late = sum(1 for v in lateness if v > MAX_SEND_LATE_MS)
    return {"sent": len(lateness), "late": late, "max_late_ms": max(lateness, default=None),
            "kept": len(lateness) - late >= ON_TIME_SHARE * len(lateness)}


def load_lines(path):
    """The load pod's query lines as (kind, due, sent, end, status), and the counts of its last line
    (None without one)."""
    queries, counts = [], None
    with open(path) as f:
        for line in f:
            parts = line.split()
            if parts[:2] == ["load", "done"]:
                counts = {k: int(v) for k, _, v in (p.partition("=") for p in parts[2:]) if v.isdigit()}
            elif len(parts) >= 5 and all(re.fullmatch(r"-?\d+", p) for p in parts[1:4]):
                due = int(parts[1])
                queries.append((parts[0], due, due + int(parts[2]), due + int(parts[3]), parts[4]))
    return queries, counts


def load(args):
    times, lateness, errors, recorded = {}, {}, {}, {}
    burst_from, burst_to = args.burst
    queries, counts = load_lines(args.log)
    for kind, due, sent, end, status in queries:
        recorded[kind] = recorded.get(kind, 0) + 1
        groups = [{"totals": "dashboard", "hot": "internal"}.get(kind, kind)]
        if groups[0] == "dashboard" and burst_from <= due < burst_to:
            groups.append("dashboard_during_burst")
        if status != "OK":
            errors[kind + " " + status] = errors.get(kind + " " + status, 0) + 1
        for group in groups:
            # A query that failed or was not sent never answered: too late for any target.
            times.setdefault(group, []).append((end - due) / 1000 if status == "OK" else math.inf)
            if status != "SKIP":
                lateness.setdefault(group, []).append(sent - due)
    result = {
        "dashboard": summary(times.get("dashboard", []), TARGETS["dashboard"]),
        "internal": summary(times.get("internal", []), TARGETS["dashboard"]),
        # No promise for interactive queries (the acceptance contract's 2.1): observed.
        "lookup": summary(times.get("lookup", []), UNBOUNDED),
        "dashboard_during_burst": summary(times.get("dashboard_during_burst", []),
                                          TARGETS["dashboard"]),
        "errors": errors,
    }
    groups = {g: on_schedule(lateness.get(g, [])) for g in
              ("dashboard", "internal", "dashboard_during_burst", "lookup")}
    judged = ("dashboard", "internal", "dashboard_during_burst")
    kinds = ("totals", "hot", "lookup")
    log = {"recorded": {k: recorded.get(k, 0) for k in kinds}, "written": counts,
           "whole": counts is not None and all(recorded.get(k, 0) == counts.get(k) for k in kinds)}
    result["schedule"] = {"late_after_ms": MAX_SEND_LATE_MS, "on_time_share": ON_TIME_SHARE,
                          # A load that sent nothing, or whose log lost lines, kept no schedule.
                          "kept": log["whole"] and groups["dashboard"]["sent"] > 0
                          and all(groups[g]["kept"] for g in judged), "groups": groups, "log": log}
    if not result["schedule"]["kept"]:
        # The times are still reported, from when each query was due, but judge nothing.
        for key in judged:
            result[key].update({"judged": False, "pass": False, "unjudged_by": "schedule"})
    with open(args.out, "w") as f:
        json.dump(result, f, indent=1)
    print(json.dumps({k: brief(v) for k, v in result.items()}))
    ok = result["dashboard"]["pass"] and result["internal"]["pass"] and not errors
    return 0 if ok else 1


def marks(path):
    """The markers committed (id to source ms), the ones known to exist, and the clock offsets."""
    committed, known, offsets = {}, set(), []
    with open(path) as f:
        for line in f:
            parts = line.split()
            if len(parts) == 4 and parts[0] == "mark":
                committed[int(parts[1])] = int(parts[2])
                known.add(int(parts[1]))
                # The pod read the source's clock within the round trip: half of it, at most, is
                # not skew.
                offsets.append(int(parts[3]) - int(parts[2]))
            elif len(parts) == 2 and parts[0] == "markx":
                known.add(int(parts[1]))
    return committed, sorted(known), offsets


def first_seen(path, kind, known):
    """When each marker was first seen on a path: by the end of a poll that held all before it."""
    polls = sorted(
        (int(p[2]), int(p[4]), int(p[5]))
        for p in lines(path)
        if p[0] == kind and p[3] == "OK" and len(p) >= 6 and p[4].isdigit() and p[5].isdigit())
    seen, gaps, upto = {}, 0, None
    for end, count, top in polls:
        if count != bisect.bisect_right(known, top):
            gaps += 1
            continue
        start = bisect.bisect_right(known, upto) if upto is not None else 0
        for m in known[start:bisect.bisect_right(known, top)]:
            seen[m] = end
        upto = top if upto is None else max(upto, top)
    return seen, gaps


def commits(changelog):
    """When Paimon first committed each marker's row in ODS."""
    out = {}
    with open(changelog) as f:
        for line in f:
            if not line.strip():
                continue
            d = json.loads(line)
            if d.get("type") != "CHANGE" or d.get("kind") != "+I" or d.get("committedAt") is None:
                continue
            key = int(d["row"]["id"])
            out[key] = min(out.get(key, d["committedAt"]), d["committedAt"])
    return out


def steady(window, committed, seen):
    """Whether the backlog held: the last minutes' source-to-external latency against the first's."""
    if not window:
        return {"steady": False, "why": "no markers"}
    start, end = min(committed[m] for m in window), max(committed[m] for m in window)
    span = STEADY_MINUTES * 60_000

    def p50(lo, hi):
        values = [(seen[m] - committed[m]) / 1000 if m in seen else math.inf
                  for m in window if lo <= committed[m] < hi]
        return percentile(values, 0.5)

    first, last = p50(start, start + span), p50(end - span, end + 1)
    ok = first is not None and last is not None and last - first <= STEADY_GROWTH
    return {"steady": ok, "first_p50": first, "last_p50": last, "max_growth": STEADY_GROWTH}


def fresh(args):
    committed, known, offsets = marks(args.marks)
    skew = percentile([abs(o) for o in offsets], 0.5) if offsets else None
    start, end = args.window
    window = [m for m, at in committed.items() if start <= at < end]
    paimon = {}
    for path in args.changelog.split(","):
        for k, v in commits(path).items():
            paimon[k] = min(paimon.get(k, v), v)
    seen, gaps = {}, {}
    for kind in ("live", "mv", "dws", "hotm"):
        seen[kind], gaps[kind] = first_seen(args.marks, kind, known)
    source_valid = skew is not None and skew <= MAX_SKEW_MS
    paimon_valid = (source_valid and args.paimon_skew_ms is not None
                    and abs(args.paimon_skew_ms) <= MAX_SKEW_MS)

    def latencies(kind, origin, valid):
        values = []
        for m in window:
            if m not in origin:
                continue
            # Never seen: later than any target.
            values.append((seen[kind][m] - origin[m]) / 1000 if m in seen[kind] else math.inf)
        return values if valid else []

    result = {
        "markers": len(window),
        "clock": {"source_median_offset_ms": skew, "paimon_skew_ms": args.paimon_skew_ms,
                  "max_ms": MAX_SKEW_MS, "source_valid": source_valid,
                  "paimon_valid": paimon_valid},
        "gaps": gaps,
        "uncommitted_in_paimon": sum(1 for m in window if m not in paimon),
        "steady": steady(window, committed, seen["live"]),
        "source_to_external": summary(latencies("live", committed, source_valid),
                                      TARGETS["source_to_external"]),
        "source_to_dws_dashboard": summary(latencies("dws", committed, source_valid),
                                           TARGETS["source_to_dws_dashboard"]),
        "paimon_to_external": summary(latencies("live", paimon, paimon_valid),
                                      TARGETS["paimon_to_external"]),
        "paimon_to_materialized": summary(latencies("mv", paimon, paimon_valid),
                                          TARGETS["paimon_to_materialized"]),
        "paimon_to_internal": summary(latencies("hotm", paimon, paimon_valid),
                                      TARGETS["paimon_to_internal"]),
        # Not the acceptance contract's paths: source to the copies of ODS.
        "source_to_materialized": summary(latencies("mv", committed, source_valid), UNBOUNDED),
        "source_to_internal": summary(latencies("hotm", committed, source_valid), UNBOUNDED),
    }
    with open(args.out, "w") as f:
        json.dump(result, f, indent=1, default=str)
    print(json.dumps({k: brief(v) for k, v in result.items()}, default=str))
    judged = ("source_to_external", "source_to_dws_dashboard", "paimon_to_external",
              "paimon_to_materialized", "paimon_to_internal")
    ok = all(result[k]["pass"] for k in judged) and result["steady"]["steady"]
    return 0 if ok else 1


# The FE's count covers the burst: its first and last sample within this of the window's ends.
SAMPLE_EDGE_MS = 5000


def fe_counts(path, window):
    """Per group, the most running and waiting queries the FE counted in the window, and whether
    the samples cover it (one a second at least, from its start to its end)."""
    start, end = window
    seen = {}
    with open(path) as f:
        for line in f:
            parts = line.split()
            if len(parts) != 4 or not all(parts[i].isdigit() for i in (0, 2, 3)):
                continue
            at = int(parts[0])
            if not start <= at <= end:
                continue
            g = seen.setdefault(parts[1], {"running": 0, "waiting": 0, "n": 0, "first": at, "last": at})
            g["running"] = max(g["running"], int(parts[2]))
            g["waiting"] = max(g["waiting"], int(parts[3]))
            g["n"] += 1
            g["first"], g["last"] = min(g["first"], at), max(g["last"], at)
    for g in seen.values():
        g["covers"] = (g["first"] - start <= SAMPLE_EDGE_MS and end - g["last"] <= SAMPLE_EDGE_MS
                       and g["n"] >= (end - start) / 1000)
    return seen


def burst(args):
    if args.sampled and not args.window:
        raise SystemExit("--sampled needs --window")
    counted = fe_counts(args.sampled, args.window) if args.sampled else None
    limits = {}
    for x in args.limits:
        bound = "<=" in x
        group, n = x.split("<=" if bound else "=", 1)
        limits[group] = (int(n), not bound)
    groups = {}
    with open(args.audit) as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 6 or not parts[0].isdigit():
                continue
            at, user, group, state = int(parts[0]), parts[1], parts[2], parts[3]
            query, queue = int(parts[4] or 0), int(parts[5] or 0)
            error = parts[6] if len(parts) > 6 else ""
            g = groups.setdefault(group, {"ran": 0, "queued": 0, "turned_away": 0, "failed": 0,
                                          "users": set(), "spans": []})
            g["users"].add(user)
            if "queue" in error:
                g["turned_away" if "full" in error else "queued"] += 1
                continue
            if queue > 0:
                g["queued"] += 1
            if state == "EOF":
                g["ran"] += 1
            else:
                g["failed"] += 1
            # It ran from the end of its wait to its end.
            g["spans"].append((at + queue, at + query))
    result, ok = {}, bool(groups)
    for group, g in groups.items():
        events = sorted([(s, 1) for s, _ in g["spans"]] + [(e, -1) for _, e in g["spans"]],
                        key=lambda x: (x[0], x[1]))
        now = peak = 0
        for _, step in events:
            now += step
            peak = max(peak, now)
        limit, reached = limits.get(group, (None, False))
        result[group] = {"ran": g["ran"], "queued": g["queued"], "turned_away": g["turned_away"],
                         "failed": g["failed"], "limit": limit, "users": sorted(g["users"])}
        if counted is None:
            at_once, full = peak, True
            waited = g["queued"] + g["turned_away"] > 0
        else:
            fe = counted.get(group)
            result[group].update({"logged_overlap": peak, "fe": fe})
            if fe is None or not fe["covers"]:
                ok, at_once, full, waited = False, None, False, False
            else:
                at_once, full = fe["running"], fe["running"] == limit
                waited = fe["waiting"] > 0 or g["turned_away"] > 0
        result[group]["at_once"] = at_once
        if limit is not None:
            # The limit held; where asked, it was reached: it ran that many and some waited.
            ok &= at_once is not None and at_once <= limit and g["ran"] > 0
            ok &= not reached or (full and waited)
    ok &= all(group in result for group in limits)
    with open(args.out, "w") as f:
        json.dump(result, f, indent=1)
    print(json.dumps(result))
    return 0 if ok else 1


def audit(args):
    counts = {}
    with open(args.audit) as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 4 and parts[3].isdigit():
                counts[(parts[0], parts[2], parts[1])] = int(parts[3])
    missing = []
    for want in args.expect:
        user, obj, state = want.rsplit(":", 2)
        if counts.get((user, obj, state), 0) == 0:
            missing.append(want)
    print(json.dumps({"logged": {f"{u}:{o}:{s}": n for (u, o, s), n in sorted(counts.items())},
                      "missing": missing}))
    return 0 if not missing else 1


def recovered(args):
    committed, _, _ = marks(args.marks)
    polls = {}
    for p in lines(args.marks):
        if int(p[1]) >= args.injected:
            polls.setdefault(p[0], []).append(p)
    result, ok = {}, True
    for kind in ("totals", "hot", "live", "mv", "dws", "hotm"):
        series = sorted(polls.get(kind, []), key=lambda p: int(p[2]))
        # Back for good: the first answer after the last failure, three answers in a row since.
        last_error = max((i for i, p in enumerate(series) if p[3] != "OK"), default=-1)
        tail = series[last_error + 1:]
        if kind in ("totals", "hot"):
            fresh_polls = tail
        else:
            fresh_polls = [
                p for p in tail
                if len(p) >= 6 and p[5].isdigit() and int(p[5]) >= args.marker
                and int(p[5]) in committed
                and (int(p[2]) - committed[int(p[5])]) / 1000 <= RECOVERED_LAG[kind]]
        if len(tail) >= 3 and fresh_polls:
            result[kind] = round((int(fresh_polls[0][2]) - args.injected) / 1000)
        else:
            result[kind] = None
            ok = False
    print(json.dumps(result))
    return 0 if ok else 1


def norm(value):
    if value is None or value == "NULL":
        return None
    try:
        return format(float(value), ".6f")
    except (TypeError, ValueError):
        return str(value)


def rows(args):
    with open(args.doris) as f:
        table = [line.rstrip("\n").split("\t") for line in f if line.strip()]
    header, body = table[0], table[1:]
    keys, columns = args.key.split(","), args.columns.split(",")
    doris = {}
    for r in body:
        row = dict(zip(header, r))
        doris[tuple(row[k] for k in keys)] = {c: norm(row.get(c)) for c in columns}
    lake = {}
    with open(args.lake) as f:
        for line in f:
            d = json.loads(line)
            if d.get("type") != "ROW":
                continue
            row = d["row"]
            lake[tuple(str(row[k]) for k in keys)] = {c: norm(row.get(c)) for c in columns}
    missing = sorted(set(lake) - set(doris))
    extra = sorted(set(doris) - set(lake))
    differing = sorted(k for k in set(lake) & set(doris) if lake[k] != doris[k])
    result = {"dorisRows": len(doris), "lakeRows": len(lake), "missing": len(missing),
              "extra": len(extra), "differing": len(differing),
              "samples": {"missing": missing[:3], "extra": extra[:3],
                          "differing": [[k, lake[k], doris[k]] for k in differing[:3]]}}
    result["pass"] = bool(lake) and not (missing or extra or differing)
    print(json.dumps(result, default=list))
    return 0 if result["pass"] else 1


def parser():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="mode", required=True)
    s = sub.add_parser("load")
    s.add_argument("log")
    s.add_argument("out")
    s.add_argument("--burst", nargs=2, type=int, default=[0, 0])
    s.set_defaults(run=load)
    s = sub.add_parser("fresh")
    s.add_argument("marks")
    s.add_argument("changelog")
    s.add_argument("out")
    s.add_argument("--window", nargs=2, type=int, required=True)
    s.add_argument("--paimon-skew-ms", type=int)
    s.set_defaults(run=fresh)
    s = sub.add_parser("burst")
    s.add_argument("audit")
    s.add_argument("out")
    s.add_argument("limits", nargs="+")
    s.add_argument("--sampled")
    s.add_argument("--window", nargs=2, type=int)
    s.set_defaults(run=burst)
    s = sub.add_parser("audit")
    s.add_argument("audit")
    s.add_argument("expect", nargs="+")
    s.set_defaults(run=audit)
    s = sub.add_parser("recovered")
    s.add_argument("marks")
    s.add_argument("injected", type=int)
    s.add_argument("marker", type=int)
    s.set_defaults(run=recovered)
    s = sub.add_parser("rows")
    s.add_argument("doris")
    s.add_argument("lake")
    s.add_argument("key")
    s.add_argument("columns")
    s.set_defaults(run=rows)
    return p


def main(argv=None):
    args = parser().parse_args(argv)
    return args.run(args)


if __name__ == "__main__":
    sys.exit(main())
