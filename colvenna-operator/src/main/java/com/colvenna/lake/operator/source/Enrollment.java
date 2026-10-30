package com.colvenna.lake.operator.source;

import com.colvenna.lake.operator.crd.LakeSourceStatus.SourceTable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Decides each matched table's state from the latest probe. Pure: the reconciler supplies what it
 * read and applies the result.
 *
 * <ul>
 *   <li>A table that fails admission is {@code Rejected} and stays out of the job; it is admitted
 *       once the source is fixed.
 *   <li>A table joins the job only while its target holds no data: a snapshot into a target that
 *       already has rows would repeat their inserts in the changelog and keep rows deleted since.
 *   <li>An admitted table is in the job: {@code Snapshotting} until its rows reconcile, then {@code
 *       Ready}.
 *   <li>{@code Failed} is sticky: the table left the job after an unsupported change or a lost job,
 *       and its target missed changes since, so it does not silently rejoin.
 *   <li>A table no longer matched or no longer in the source drops out of the status.
 * </ul>
 */
public final class Enrollment {
    public static final String SNAPSHOTTING = "Snapshotting";
    public static final String READY = "Ready";
    public static final String REJECTED = "Rejected";
    public static final String FAILED = "Failed";

    private Enrollment() {}

    /** A state change worth an event. */
    public record Transition(String table, String from, String to, String reason, String message) {
        public boolean warning() {
            return to.equals(REJECTED) || to.equals(FAILED);
        }
    }

    /** Next statuses, in source order, and the changes that led there. */
    public record Plan(List<SourceTable> tables, List<Transition> transitions) {
        /** The tables the job carries. */
        public List<String> jobTables() {
            return tables.stream().filter(Enrollment::inJob).map(t -> t.name).toList();
        }
    }

    /** An existing target table: its primary key and whether it holds any snapshot. */
    public record Target(List<String> primaryKey, boolean hasData) {}

    /** Whether a source table name matches the declaration (whole-name regexes). */
    public static boolean selected(String table, List<String> include, List<String> exclude) {
        boolean in =
                include == null
                        || include.isEmpty()
                        || include.stream().anyMatch(p -> Pattern.matches(p, table));
        return in
                && (exclude == null || exclude.stream().noneMatch(p -> Pattern.matches(p, table)));
    }

    /**
     * @param verdicts admission for every selected source table
     * @param targets the existing target tables, by name
     * @param failures tables the running job failed on, with the failure
     * @param gate why no table may join the job now (target not isolated, source prerequisites
     *     broken), or null; tables already in the job are not affected
     */
    public static Plan plan(
            List<SourceTable> previous,
            Map<String, SourceModel.Table> selected,
            Map<String, Admission.Verdict> verdicts,
            Map<String, Target> targets,
            Map<String, String> failures,
            Admission.Verdict gate,
            Instant now) {
        Map<String, SourceTable> before = new LinkedHashMap<>();
        for (SourceTable t : previous) {
            before.put(t.name, t);
        }
        List<SourceTable> next = new ArrayList<>();
        List<Transition> transitions = new ArrayList<>();
        for (var entry : selected.entrySet()) {
            String name = entry.getKey();
            SourceModel.Table source = entry.getValue();
            SourceTable prev = before.get(name);
            SourceTable t = prev == null ? fresh(name) : copy(prev);
            Admission.Verdict verdict = verdicts.get(name);
            String failure = failures.get(name);
            Target target = targets.get(name);
            if (!inJob(t)) {
                // Recorded for the job: PostgreSQL reads these tables with this key.
                t.primaryKey = new ArrayList<>(source.primaryKey());
                t.readTables =
                        source.postgres() == null
                                ? new ArrayList<>()
                                : new ArrayList<>(source.postgres().readTables());
            }
            if (FAILED.equals(t.state)) {
                // Sticky, see the class comment.
            } else if (inJob(t)
                    && t.sourceId != null
                    && source.sourceId() != null
                    && !t.sourceId.equals(source.sourceId())) {
                // Dropped and created again, or renamed over: the stream saw none of it.
                set(
                        t,
                        FAILED,
                        "SourceReplaced",
                        "the source replaced "
                                + name
                                + " (it was dropped and created again, or"
                                + " renamed over); its target missed those changes. To read it from"
                                + " scratch, drop its target (a MongoDB collection: declare a"
                                + " LakeSource of its own, with another target database)",
                        now,
                        transitions);
            } else if (failure != null && inJob(t)) {
                set(t, FAILED, "JobFailed", failure, now, transitions);
            } else if (target != null
                    && verdict.admitted()
                    && !sameKey(target.primaryKey(), source.primaryKey())) {
                set(
                        t,
                        inJob(t) ? FAILED : REJECTED,
                        "PrimaryKeyChanged",
                        "the source key is "
                                + source.primaryKey()
                                + " but the target table's is "
                                + target.primaryKey()
                                + "; a key cannot change in place",
                        now,
                        transitions);
            } else if (!verdict.admitted()) {
                // Admitted before, now not: the source changed under a running job.
                set(
                        t,
                        inJob(t) ? FAILED : REJECTED,
                        verdict.reason(),
                        verdict.message(),
                        now,
                        transitions);
            } else if (!inJob(t)) {
                if (target != null && target.hasData()) {
                    set(
                            t,
                            REJECTED,
                            "TargetNotEmpty",
                            "the target table already holds data; drop it to snapshot the table"
                                    + " again (a MongoDB collection: in a LakeSource of its own,"
                                    + " with another target database)",
                            now,
                            transitions);
                } else if (gate != null) {
                    set(t, REJECTED, gate.reason(), gate.message(), now, transitions);
                } else {
                    set(
                            t,
                            SNAPSHOTTING,
                            "Admitted",
                            "admitted; waiting for the snapshot to reconcile",
                            now,
                            transitions);
                }
            }
            if (inJob(t) && t.sourceId == null) {
                // The identity it joined with; later rounds compare against it.
                t.sourceId = source.sourceId();
            }
            next.add(t);
        }
        return new Plan(next, transitions);
    }

    /** Takes a table out of the job for a reason found outside the probe. */
    public static Optional<Transition> fail(
            SourceTable t, String reason, String message, Instant now) {
        List<Transition> out = new ArrayList<>();
        set(t, FAILED, reason, message, now, out);
        return out.stream().findFirst();
    }

    /** Marks a table reconciled; {@code versioned} when versions were compared, not keys only. */
    public static Optional<Transition> ready(
            SourceTable t,
            Reconciliation.Result result,
            long snapshot,
            boolean versioned,
            Instant now) {
        t.sourceRows = result.sourceRows();
        t.targetRows = result.targetRows();
        t.reconciledAt = now.toString();
        t.reconciledSnapshot = snapshot;
        List<Transition> out = new ArrayList<>();
        set(
                t,
                READY,
                "Reconciled",
                result.sourceRows()
                        + (versioned
                                ? " rows match the source by key and version"
                                : " rows match the source by key"),
                now,
                out);
        return out.stream().findFirst();
    }

    /**
     * A Ready table to reconcile with the source again: the collector snapshotted again, or deletes
     * were just repaired. Each call restarts the wait for the target to settle.
     */
    public static void recheck(SourceTable t, Instant now) {
        if (READY.equals(t.state)) {
            t.recheckSince = now.toString();
        }
    }

    /** Whether a Ready table waits to be reconciled again and has settled for {@code settle}. */
    public static boolean rechecking(SourceTable t, Duration settle, Instant now) {
        return READY.equals(t.state)
                && t.recheckSince != null
                && Instant.parse(t.recheckSince).plus(settle).isBefore(now);
    }

    /** A Ready table reconciled again; returns the message. */
    public static String rechecked(
            SourceTable t, Reconciliation.Result result, long snapshot, Instant now) {
        t.recheckSince = null;
        t.sourceRows = result.sourceRows();
        t.targetRows = result.targetRows();
        t.reconciledAt = now.toString();
        t.reconciledSnapshot = snapshot;
        t.message = result.sourceRows() + " rows match the source by key and version again";
        return t.message;
    }

    public static boolean inJob(SourceTable t) {
        return SNAPSHOTTING.equals(t.state) || READY.equals(t.state);
    }

    private static boolean sameKey(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equalsIgnoreCase(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static void set(
            SourceTable t,
            String state,
            String reason,
            String message,
            Instant now,
            List<Transition> transitions) {
        if (!Objects.equals(t.state, state) || !Objects.equals(t.reason, reason)) {
            transitions.add(new Transition(t.name, t.state, state, reason, message));
            t.lastTransitionTime = now.toString();
        }
        t.state = state;
        t.reason = reason;
        t.message = message;
    }

    private static SourceTable fresh(String name) {
        SourceTable t = new SourceTable();
        t.name = name;
        t.target = name;
        return t;
    }

    private static SourceTable copy(SourceTable p) {
        SourceTable t = new SourceTable();
        t.name = p.name;
        t.target = p.target;
        t.state = p.state;
        t.reason = p.reason;
        t.message = p.message;
        t.lastTransitionTime = p.lastTransitionTime;
        t.sourceRows = p.sourceRows;
        t.targetRows = p.targetRows;
        t.changelog = p.changelog;
        t.sourceId = p.sourceId;
        t.reconciledAt = p.reconciledAt;
        t.reconciledSnapshot = p.reconciledSnapshot;
        t.recheckSince = p.recheckSince;
        t.primaryKey = new ArrayList<>(p.primaryKey);
        t.readTables = new ArrayList<>(p.readTables);
        return t;
    }
}
