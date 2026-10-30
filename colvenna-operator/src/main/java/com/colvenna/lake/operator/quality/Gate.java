package com.colvenna.lake.operator.quality;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A table's quality gate, from the results of its checks under the current rules (LLD "门禁"). A
 * check is replaced by a newer one (a newer snapshot) covering at least its range; the checks no
 * other replaces stand. The gate is closed while one of them has a failed rule no waiver accepts,
 * before any check, once the newest is older than the declared age, or when the rules or results
 * cannot be read.
 */
public final class Gate {
    private Gate() {}

    /**
     * One completed check: its task, the snapshot and time it judged, when it was written, the
     * partitions it covered (none: the whole table) and the rules it failed.
     */
    public record Check(
            String task,
            String uid,
            long snapshot,
            Instant asOf,
            Instant checkedAt,
            List<String> partitions,
            List<String> failed) {
        String describe() {
            return task
                    + " (snapshot "
                    + snapshot
                    + (partitions.isEmpty() ? ", whole table" : ", " + String.join(" ", partitions))
                    + ")";
        }
    }

    /** An accepted waiver: the check it is for, and the rules it accepts. */
    public record Waiver(
            String name,
            String uid,
            String checkUid,
            List<String> rules,
            String approvedBy,
            String reason) {}

    /**
     * What the gate is: open or not, why, the checks it stands on, the one its cause rests on (a
     * failed one when it failed, else the newest), and the waivers it relies on.
     */
    public record Verdict(
            boolean open,
            String cause,
            String message,
            List<Check> live,
            Check basis,
            Check newest,
            List<Waiver> used) {}

    /**
     * Judges the gate.
     *
     * @param notReady why the rules or results are not usable, or null
     * @param checks the completed checks under the current rule version
     * @param waivers the accepted waivers
     * @param maxAge the declared age past which the newest check no longer opens it, or null
     */
    public static Verdict judge(
            String notReady,
            List<Check> checks,
            List<Waiver> waivers,
            Duration maxAge,
            Instant now) {
        List<Check> live = live(checks);
        Check newest =
                checks.stream()
                        .max(Comparator.comparing(Check::asOf).thenComparing(Check::checkedAt))
                        .orElse(null);
        if (notReady != null) {
            return new Verdict(false, "NotReady", notReady, live, newest, newest, List.of());
        }
        if (checks.isEmpty()) {
            return new Verdict(
                    false,
                    "NoCheck",
                    "no completed check under the current rules",
                    live,
                    null,
                    null,
                    List.of());
        }
        Map<String, List<Waiver>> byCheck = new HashMap<>();
        for (Waiver w : waivers) {
            byCheck.computeIfAbsent(w.checkUid(), k -> new ArrayList<>()).add(w);
        }
        List<String> failing = new ArrayList<>();
        Check failed = null;
        List<Waiver> used = new ArrayList<>();
        for (Check c : live) {
            Set<String> left = new LinkedHashSet<>(c.failed());
            for (Waiver w : byCheck.getOrDefault(c.uid(), List.of())) {
                if (!c.failed().isEmpty() && left.removeAll(w.rules())) {
                    used.add(w);
                }
            }
            if (!left.isEmpty()) {
                failing.add(c.describe() + " failed " + String.join(", ", left));
                if (failed == null || c.snapshot() > failed.snapshot()) {
                    failed = c;
                }
            }
        }
        if (!failing.isEmpty()) {
            return new Verdict(
                    false, "Failed", String.join("; ", failing), live, failed, newest, List.of());
        }
        if (maxAge != null && newest.asOf().plus(maxAge).isBefore(now)) {
            return new Verdict(
                    false,
                    "Expired",
                    "the newest check, "
                            + newest.describe()
                            + " as of "
                            + newest.asOf()
                            + ", is older than "
                            + maxAge.toMinutes()
                            + " min",
                    live,
                    newest,
                    newest,
                    List.of());
        }
        // A check of some partitions passes the table: say what no check has looked at.
        boolean whole = live.stream().anyMatch(c -> c.partitions().isEmpty());
        String message =
                (used.isEmpty() ? "passed: " : "passed with waivers: ")
                        + newest.describe()
                        + (whole
                                ? "; data after it not checked yet"
                                : "; other partitions, and data after it, not checked yet");
        return new Verdict(
                true, used.isEmpty() ? "Passed" : "Waived", message, live, newest, newest, used);
    }

    /** The checks no other replaces: none newer covers their range. */
    static List<Check> live(List<Check> checks) {
        List<Check> out = new ArrayList<>();
        for (Check a : checks) {
            boolean replaced = false;
            for (Check b : checks) {
                if (b != a && newer(b, a) && covers(b.partitions(), a.partitions())) {
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                out.add(a);
            }
        }
        return out;
    }

    /** Whether b judged later data than a: a newer snapshot, or the same one written later. */
    private static boolean newer(Check b, Check a) {
        if (b.snapshot() != a.snapshot()) {
            return b.snapshot() > a.snapshot();
        }
        int written = b.checkedAt().compareTo(a.checkedAt());
        return written != 0 ? written > 0 : b.uid().compareTo(a.uid()) > 0;
    }

    /**
     * Whether a range covers another: the whole table covers anything; otherwise every partition of
     * the other is matched by one of the range's, whose values it includes ({@code dt=a} matches
     * {@code dt=a,hr=1}).
     */
    static boolean covers(List<String> range, List<String> other) {
        if (range.isEmpty()) {
            return true;
        }
        if (other.isEmpty()) {
            return false;
        }
        for (String o : other) {
            Map<String, String> values = values(o);
            boolean matched =
                    range.stream()
                            .anyMatch(r -> values.entrySet().containsAll(values(r).entrySet()));
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    /** A partition {@code k=v[,k=v]} as its values by column. */
    static Map<String, String> values(String partition) {
        Map<String, String> out = new HashMap<>();
        for (String part : partition.split(",")) {
            String[] kv = part.split("=", 2);
            out.put(kv[0], kv.length == 2 ? kv[1] : "");
        }
        return out;
    }
}
