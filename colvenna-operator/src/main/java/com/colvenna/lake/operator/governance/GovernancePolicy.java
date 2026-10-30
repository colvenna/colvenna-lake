package com.colvenna.lake.operator.governance;

import com.colvenna.lake.operator.crd.Governance;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.table.TableJobs;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.paimon.utils.TimeUtils;

/**
 * Whether a table is governed (HLD 12.5, LLD "治理与个人数据删除"), and the options its governance imposes.
 * Pure: the reconcilers bring the declaration, the table's columns and its upstream.
 */
public final class GovernancePolicy {
    /** The longest a table with personal data keeps old snapshots, changelog or idle readers. */
    public static final Duration MAX_RETENTION = Duration.ofHours(24);

    /**
     * The longest between the writer's full compactions, for a writer that compacts (a lookup
     * changelog): {@code compaction.optimization-interval}, which Paimon's universal compaction
     * honours (EarlyFullCompaction). Not {@code full-compaction.delta-commits}: Paimon 2.0 refuses
     * it beside {@code changelog-producer=lookup}.
     */
    static final Duration FULL_COMPACTION_INTERVAL = Duration.ofHours(1);

    /** The option that sets {@link #FULL_COMPACTION_INTERVAL}. */
    public static final String OPTIMIZATION_INTERVAL = "compaction.optimization-interval";

    private static final List<String> BOUNDED =
            List.of(
                    "snapshot.time-retained",
                    "changelog.time-retained",
                    "consumer.expiration-time");

    /** Options that keep history for its count, whatever its age: refused for personal data. */
    private static final List<String> COUNTED =
            List.of(
                    "snapshot.num-retained.min",
                    "changelog.num-retained.min",
                    "changelog.num-retained.max");

    private GovernancePolicy() {}

    /** The judgement: governed or not, why, and what to fix. */
    public record Verdict(boolean governed, String reason, String message) {
        static Verdict not(String reason, String message) {
            return new Verdict(false, reason, message);
        }
    }

    /**
     * The governance a namespace's declarations give: an ODS database's from its LakeSource, a
     * layered table's from its LakeTable.
     */
    public record Declared(Map<String, Governance> byDatabase, Map<String, Governance> byTable) {
        public static Declared of(List<LakeSource> sources, List<LakeTable> tables) {
            Map<String, Governance> byDatabase = new HashMap<>();
            for (LakeSource s : sources) {
                if (s.getSpec() != null && s.getSpec().target != null) {
                    byDatabase.put(s.getSpec().target.database, s.getSpec().governance);
                }
            }
            Map<String, Governance> byTable = new HashMap<>();
            for (LakeTable t : tables) {
                if (t.getSpec() != null && t.getSpec().target != null) {
                    byTable.put(TableJobs.target(t.getSpec()), t.getSpec().governance);
                }
            }
            return new Declared(byDatabase, byTable);
        }

        /** What these tables read, as their declarations say. */
        public Upstream upstream(List<String> upstream) {
            return Upstream.of(upstream, byDatabase, byTable);
        }

        /** A table's column labels, by column, as its declaration gives them. */
        public Map<String, Governance.ColumnLabel> labelsOf(String table) {
            Governance g = byTable.get(table);
            if (g != null) {
                return g.targetLabels();
            }
            String[] parts = TableJobs.split(table);
            g = byDatabase.get(parts[0]);
            return g == null ? Map.of() : g.labelsOf(parts[1]);
        }

        /** The governance a table's declaration gives it, or null when none covers it. */
        public Governance governanceOf(String table) {
            Governance g = byTable.get(table);
            return g != null ? g : byDatabase.get(TableJobs.split(table)[0]);
        }
    }

    /** The highest level and the categories among the tables a LakeTable reads. */
    public record Upstream(
            int level, boolean personal, List<String> tables, List<String> unclassified) {
        /**
         * What the upstream tables' declarations say: an ODS table's is its LakeSource's (the
         * database it writes), a layered table's its LakeTable's. A table no declaration here
         * classifies counts as L4 personal, and is unclassified.
         */
        public static Upstream of(
                List<String> upstream,
                Map<String, Governance> byDatabase,
                Map<String, Governance> byTable) {
            int level = 0;
            boolean personal = false;
            List<String> unclassified = new ArrayList<>();
            Declared declared = new Declared(byDatabase, byTable);
            for (String table : upstream) {
                Governance g = declared.governanceOf(table);
                level = Math.max(level, Governance.level(g));
                personal |= Governance.treatedAsPersonal(g);
                if (!Governance.classified(g)) {
                    unclassified.add(table);
                }
            }
            return new Upstream(level, personal, upstream, unclassified);
        }
    }

    /**
     * Judges one table.
     *
     * @param subjectKey the table's subject key columns, empty when none is declared
     * @param labels the table's column labels, by column
     * @param columns the table's columns, or null before the table exists
     * @param declared the options the declaration adds
     * @param upstream what a LakeTable reads, or null for a source table
     */
    public static Verdict verdict(
            Governance g,
            List<String> subjectKey,
            Map<String, Governance.ColumnLabel> labels,
            Collection<String> columns,
            Map<String, String> declared,
            Upstream upstream) {
        if (!Governance.classified(g)) {
            return Verdict.not(
                    "Unclassified",
                    "no governance with owner and level: treated as L4, not published");
        }
        if (g.retention == null) {
            return Verdict.not(
                    "RetentionMissing",
                    "no retention declared: how long the tables keep their history is the"
                            + " declaration's to bound");
        }
        Duration retention = TimeUtils.parseDuration(g.retention);
        int level = Governance.level(g);
        if (g.categories.contains("sensitive") && level < 4) {
            return Verdict.not("LevelTooLow", "sensitive personal data is L4, not " + g.level);
        }
        if (g.personal() && level < 3) {
            return Verdict.not("LevelTooLow", "personal data is at least L3, not " + g.level);
        }
        if (g.personal()) {
            if (subjectKey == null || subjectKey.isEmpty()) {
                return Verdict.not(
                        "SubjectKeyMissing",
                        "personal data without subject key columns: an erasure could not find"
                                + " the subject's rows");
            }
            if (columns != null && !columns.containsAll(subjectKey)) {
                return Verdict.not(
                        "SubjectKeyMissing",
                        "subject key " + subjectKey + " is not among the columns " + columns);
            }
            if (retention.compareTo(MAX_RETENTION) > 0) {
                return Verdict.not(
                        "RetentionTooLong",
                        "personal data keeps its history "
                                + MAX_RETENTION.toHours()
                                + " h at most, not "
                                + g.retention);
            }
            for (String key : COUNTED) {
                if (declared != null && declared.containsKey(key)) {
                    return Verdict.not(
                            "RetentionTooLong",
                            key
                                    + " keeps history for its count, past the retention, of personal data");
                }
            }
        }
        String tooLong = tooLong(declared, retention);
        if (tooLong != null) {
            return Verdict.not("RetentionTooLong", tooLong);
        }
        String labelled = Exposure.problem(g, labels, columns);
        if (labelled != null) {
            return Verdict.not("LabelInvalid", labelled);
        }
        if (upstream != null) {
            if (!upstream.unclassified().isEmpty()) {
                return Verdict.not(
                        "UpstreamUnclassified",
                        "it reads tables no declaration classifies ("
                                + String.join(", ", upstream.unclassified())
                                + "): their level and categories are unknown");
            }
            if (level < upstream.level()) {
                return Verdict.not(
                        "BelowUpstream",
                        g.level
                                + " is below L"
                                + upstream.level()
                                + " of what it reads ("
                                + String.join(", ", upstream.tables())
                                + "); there is no de-identification path");
            }
            if (upstream.personal() && !g.personal()) {
                return Verdict.not(
                        "BelowUpstream",
                        "it reads personal data ("
                                + String.join(", ", upstream.tables())
                                + ") but declares no personal category");
            }
        }
        return new Verdict(
                true,
                "Governed",
                "owner " + g.owner + ", " + g.level + ", retention " + g.retention);
    }

    /** Why a declared option keeps history longer than the retention, or null. */
    static String tooLong(Map<String, String> declared, Duration retention) {
        if (declared == null) {
            return null;
        }
        for (String key : BOUNDED) {
            String value = declared.get(key);
            if (value != null && TimeUtils.parseDuration(value).compareTo(retention) > 0) {
                return key + " " + value + " is longer than the retention";
            }
        }
        return null;
    }

    /**
     * The options governance sets on a table, over the declared ones: history kept no longer than
     * the declared retention; for data treated as personal, at most {@link #MAX_RETENTION}, no
     * snapshot kept for its count alone, and a writer that compacts doing full compactions, which
     * drop deleted rows from the files. Without a declared retention (unclassified data) only what
     * is declared is cut to the bound; the defaults are within it.
     */
    public static Map<String, String> options(
            Governance g, Map<String, String> declared, boolean writerCompacts) {
        Map<String, String> out = new LinkedHashMap<>();
        Duration retention = retention(g);
        if (retention == null) {
            return out;
        }
        // Paimon's duration syntax: "24 h", "30 min".
        String bound = paimon(retention);
        boolean declaredRetention = g != null && g.retention != null;
        for (String key : List.of("snapshot.time-retained", "consumer.expiration-time")) {
            String value = declared == null ? null : declared.get(key);
            if (value == null && !declaredRetention) {
                continue;
            }
            out.put(
                    key,
                    value != null && TimeUtils.parseDuration(value).compareTo(retention) <= 0
                            ? value
                            : bound);
        }
        if (!Governance.treatedAsPersonal(g)) {
            return out;
        }
        out.put("snapshot.num-retained.min", "1");
        // The interval is fixed: TableOptions refuses a declared one.
        if (writerCompacts) {
            out.put(OPTIMIZATION_INTERVAL, paimon(FULL_COMPACTION_INTERVAL));
        }
        return out;
    }

    /**
     * How long tables keep their history: the declared retention, for data treated as personal
     * (declared so, or unclassified) at most {@link #MAX_RETENTION} and that when none is declared;
     * null for other data without one.
     */
    public static Duration retention(Governance g) {
        Duration declared =
                g == null || g.retention == null ? null : TimeUtils.parseDuration(g.retention);
        if (!Governance.treatedAsPersonal(g)) {
            return declared;
        }
        return declared == null || declared.compareTo(MAX_RETENTION) > 0 ? MAX_RETENTION : declared;
    }

    /** {@link #FULL_COMPACTION_INTERVAL} as Paimon reads it. */
    public static String fullCompactionInterval() {
        return paimon(FULL_COMPACTION_INTERVAL);
    }

    private static String paimon(Duration d) {
        if (d.toMinutes() % (60 * 24) == 0 && d.toDays() > 1) {
            return d.toDays() + " d";
        }
        return d.toMinutes() % 60 == 0 ? d.toHours() + " h" : d.toMinutes() + " min";
    }
}
