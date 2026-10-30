package com.colvenna.lake.operator.quality;

import com.colvenna.lake.operator.Names;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataTypes;

/**
 * A namespace's quality store (LLD "结果与可定位"): the database {@code quality_<namespace>} with the
 * checks' results, which the check jobs write, and the gate's decisions, which the operator
 * appends; and the tags keeping the snapshots of failed checks.
 */
public interface QualityStore {
    String RESULTS = "results";
    String DECISIONS = "decisions";

    /** The prefix of the tags the operator makes for failed checks. */
    String TAG_PREFIX = "colvenna-check-";

    /** One rule's result of one check, as the check job wrote it. */
    record Result(
            String taskUid,
            String task,
            String rule,
            String table,
            long snapshot,
            Instant asOf,
            String partitions,
            String ruleVersion,
            boolean passed,
            Instant checkedAt) {}

    /** A table's columns and partition keys. */
    record Columns(List<DataField> fields, List<String> partitionKeys) {}

    /** The table's columns, or empty if it does not exist. */
    Optional<Columns> columns(String database, String table) throws Exception;

    /**
     * Creates the namespace's store if it is not there.
     *
     * @return why it cannot be used (its database belongs to something else), or null
     */
    String ensure(String namespace) throws Exception;

    /** Every result of the table's checks, all rule versions. */
    List<Result> results(String namespace, String table) throws Exception;

    /** Appends decisions (column to value, as {@link #DECISION_FIELDS}) in one commit. */
    void decide(String namespace, List<Map<String, Object>> decisions) throws Exception;

    boolean snapshotExists(String database, String table, long snapshot) throws Exception;

    /** A tag of the operator's: its name, snapshot and when it is due to go. */
    record Tag(String name, long snapshot, Instant expires) {}

    List<Tag> tags(String database, String table) throws Exception;

    void tag(String database, String table, String name, long snapshot, Duration kept)
            throws Exception;

    void untag(String database, String table, String name) throws Exception;

    /**
     * The store's database for a namespace: {@code quality_} and the namespace, - as _, cut short
     * with a hash past 63 characters (Names.fit).
     */
    static String database(String namespace) {
        return Names.fit("quality_" + namespace.replace('-', '_'), 63, '_');
    }

    /** The owner option of a namespace's store database. */
    static String owner(String namespace) {
        return "LakeQuality/" + namespace;
    }

    /**
     * The results table: one row per rule of each check, keyed by the check's task uid, partitioned
     * by the checked table (the operator reads one table's at a time).
     */
    List<DataField> RESULT_FIELDS =
            List.of(
                    new DataField(0, "task_uid", DataTypes.STRING().notNull()),
                    new DataField(1, "rule", DataTypes.STRING().notNull()),
                    new DataField(2, "task", DataTypes.STRING()),
                    new DataField(3, "checked_table", DataTypes.STRING().notNull()),
                    new DataField(4, "partitions", DataTypes.STRING()),
                    new DataField(5, "snapshot_id", DataTypes.BIGINT()),
                    new DataField(6, "snapshot_time", DataTypes.TIMESTAMP(3)),
                    new DataField(7, "as_of", DataTypes.TIMESTAMP(3)),
                    new DataField(8, "rule_version", DataTypes.STRING()),
                    new DataField(9, "definition", DataTypes.STRING()),
                    new DataField(10, "metric", DataTypes.STRING()),
                    new DataField(11, "measured", DataTypes.DOUBLE()),
                    new DataField(12, "threshold", DataTypes.STRING()),
                    new DataField(13, "passed", DataTypes.BOOLEAN()),
                    new DataField(14, "failing_rows", DataTypes.BIGINT()),
                    new DataField(15, "checked_rows", DataTypes.BIGINT()),
                    new DataField(16, "locate", DataTypes.STRING()),
                    new DataField(17, "checked_at", DataTypes.TIMESTAMP(3)));

    /** The decisions table: an append-only log of the gates' changes. */
    List<DataField> DECISION_FIELDS =
            List.of(
                    new DataField(0, "checked_table", DataTypes.STRING()),
                    new DataField(1, "lake_quality", DataTypes.STRING()),
                    new DataField(2, "decided_at", DataTypes.TIMESTAMP(3)),
                    new DataField(3, "open", DataTypes.BOOLEAN()),
                    new DataField(4, "cause", DataTypes.STRING()),
                    new DataField(5, "message", DataTypes.STRING()),
                    new DataField(6, "rule_version", DataTypes.STRING()),
                    new DataField(7, "check_task", DataTypes.STRING()),
                    new DataField(8, "check_uid", DataTypes.STRING()),
                    new DataField(9, "snapshot_id", DataTypes.BIGINT()),
                    new DataField(10, "failed_rules", DataTypes.STRING()),
                    new DataField(11, "waivers", DataTypes.STRING()));

    /**
     * When a tag made at {@code created} and kept {@code kept} goes. Paimon records the maker's
     * local time; the operator made its tags itself, in this JVM's zone.
     */
    static Instant expires(LocalDateTime created, Duration kept) {
        return created == null || kept == null
                ? Instant.MAX
                : created.atZone(ZoneId.systemDefault()).toInstant().plus(kept);
    }
}
