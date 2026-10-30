package com.colvenna.lake.jobs.cdc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.paimon.Snapshot;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.StreamTableScan;
import org.apache.paimon.table.source.TableScan;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypeFamily;
import org.apache.paimon.types.RowKind;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.SnapshotManager;

/**
 * What the targets already hold, for a Kafka job that starts without state (a switch from the
 * database's log, or a job recreated): when their writer last wrote, so that reading starts an
 * overlap before it, and which rows it deleted within that reach, so that their replayed older
 * changes are dropped rather than written again.
 */
final class TargetHistory implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    /** The operator's commits of deletes the collector never sent (LakeReader's). */
    static final String REPAIR_COMMIT_USER = "colvenna-repair";

    /** More deleted rows than this in the reach means the overlap is too long to remember. */
    static final int MAX_DELETED = 2_000_000;

    /** The targets' writer's last commit, or null when no target has been written. */
    final Long lastWriteMillis;

    /** The targets that have been written; the others are read from their topic's beginning. */
    final Set<String> written;

    /** Each existing target's key fields. */
    final Map<String, List<String>> keyFields;

    /**
     * Rows deleted within the reach, by {@link KafkaEventGuard#rowKey}, with the version deleted;
     * only for targets whose keys are integers or strings, which Debezium JSON writes as Paimon
     * does.
     */
    final Map<String, Long> deleted;

    TargetHistory(
            Long lastWriteMillis,
            Set<String> written,
            Map<String, List<String>> keyFields,
            Map<String, Long> deleted) {
        this.lastWriteMillis = lastWriteMillis;
        this.written = written;
        this.keyFields = keyFields;
        this.deleted = deleted;
    }

    /** Nothing known: what a job gets when the targets could not be read. */
    static TargetHistory none() {
        return new TargetHistory(null, Set.of(), Map.of(), Map.of());
    }

    /**
     * Reads the targets. The deletions are read from {@code 2 * overlapMillis} before the last
     * write: a change replayed from the overlap was committed by the previous writer no earlier
     * than its record's time minus the collector's lag, which the overlap bounds too.
     */
    static TargetHistory read(
            Catalog catalog,
            String database,
            List<String> targets,
            String versionColumn,
            long overlapMillis)
            throws Exception {
        Map<String, FileStoreTable> tables = new HashMap<>();
        Map<String, List<String>> keyFields = new HashMap<>();
        Set<String> written = new HashSet<>();
        Long last = null;
        for (String target : targets) {
            FileStoreTable table;
            try {
                table = (FileStoreTable) catalog.getTable(Identifier.create(database, target));
            } catch (Catalog.TableNotExistException e) {
                // Created from the topic's first record.
                continue;
            }
            keyFields.put(target, table.primaryKeys());
            Long write = lastWrite(target, table.snapshotManager());
            if (write != null) {
                tables.put(target, table);
                written.add(target);
                last = last == null ? write : Math.max(last, write);
            }
        }
        Map<String, Long> deleted = new HashMap<>();
        if (last != null) {
            long from = last - 2 * overlapMillis;
            for (Map.Entry<String, FileStoreTable> e : tables.entrySet()) {
                if (textKeys(e.getValue())) {
                    deletedSince(e.getKey(), e.getValue(), versionColumn, from, deleted);
                }
            }
        }
        return new TargetHistory(
                last,
                Collections.unmodifiableSet(written),
                Collections.unmodifiableMap(keyFields),
                deleted);
    }

    /**
     * The time of the table's latest commit with data (not a compaction), or null for a table never
     * written. Fails when the table has snapshots but none of them is a write.
     */
    static Long lastWrite(String target, SnapshotManager snapshots) {
        Long latest = snapshots.latestSnapshotId();
        Long earliest = snapshots.earliestSnapshotId();
        if (latest == null || earliest == null) {
            return null;
        }
        for (long id = latest; id >= earliest; id--) {
            if (!snapshots.snapshotExists(id)) {
                continue;
            }
            Snapshot s = snapshots.snapshot(id);
            // The operator's repair of lost deletes is no write of the job's changes.
            if ((s.commitKind() == Snapshot.CommitKind.APPEND
                            || s.commitKind() == Snapshot.CommitKind.OVERWRITE)
                    && !s.commitUser().startsWith(REPAIR_COMMIT_USER)) {
                return s.timeMillis();
            }
        }
        throw new IllegalStateException(
                "POSITION_UNKNOWN: "
                        + target
                        + " keeps no commit of its writer, only compactions; where its changes"
                        + " stopped is unknown");
    }

    /** Whether every key field is an integer or a string, so its text is Debezium's too. */
    static boolean textKeys(FileStoreTable table) {
        RowType type = table.rowType();
        for (String key : table.primaryKeys()) {
            DataType t = type.getTypeAt(type.getFieldIndex(key));
            if (!t.is(DataTypeFamily.INTEGER_NUMERIC) && !t.is(DataTypeFamily.CHARACTER_STRING)) {
                return false;
            }
        }
        return true;
    }

    private static void deletedSince(
            String target,
            FileStoreTable table,
            String versionColumn,
            long fromMillis,
            Map<String, Long> deleted)
            throws Exception {
        SnapshotManager snapshots = table.snapshotManager();
        Long earliest = snapshots.earliestSnapshotId();
        Long latest = snapshots.latestSnapshotId();
        Long start = null;
        for (long id = earliest; id <= latest; id++) {
            if (snapshots.snapshotExists(id) && snapshots.snapshot(id).timeMillis() >= fromMillis) {
                start = id;
                break;
            }
        }
        if (start == null) {
            return;
        }
        // Expiry takes snapshots older than the retention before a commit, so those it took
        // precede the reach unless the retention is shorter than it. A declared maximum count
        // takes snapshots whatever their age: then nothing is known of those it took.
        long expiredBefore =
                table.options().containsKey("snapshot.num-retained.max")
                        ? Long.MAX_VALUE
                        : snapshots.snapshot(latest).timeMillis()
                                - table.coreOptions().snapshotTimeRetain().toMillis();
        if (start.equals(earliest) && earliest > 1 && expiredBefore > fromMillis) {
            // Snapshots of the reach may have expired: rows deleted in them are unknown.
            throw new IllegalStateException(
                    "POSITION_UNKNOWN: "
                            + target
                            + " no longer keeps its snapshots from "
                            + fromMillis
                            + " (earliest "
                            + earliest
                            + "); keep snapshots longer than twice the overlap");
        }
        RowType type = table.rowType();
        List<String> keys = table.primaryKeys();
        int[] projection = new int[keys.size() + 1];
        for (int i = 0; i < keys.size(); i++) {
            projection[i] = type.getFieldIndex(keys.get(i));
        }
        projection[keys.size()] = type.getFieldIndex(versionColumn);
        if (projection[keys.size()] < 0) {
            throw new IllegalStateException(target + " has no column " + versionColumn);
        }
        Map<String, String> options = new HashMap<>();
        options.put("scan.mode", "from-snapshot");
        options.put("scan.snapshot-id", Long.toString(start));
        ReadBuilder read =
                table.copyWithoutTimeTravel(options).newReadBuilder().withProjection(projection);
        StreamTableScan scan = read.newStreamScan();
        Map<String, Long> removed = new HashMap<>();
        Map<String, Long> written = new HashMap<>();
        Long previous = null;
        int idle = 0;
        while (true) {
            TableScan.Plan plan = scan.plan();
            Long next = scan.checkpoint();
            if (!plan.splits().isEmpty()) {
                idle = 0;
                try (RecordReader<InternalRow> reader = read.newRead().createReader(plan)) {
                    reader.forEachRemaining(
                            row -> {
                                List<String> values = new ArrayList<>(keys.size());
                                for (int i = 0; i < keys.size(); i++) {
                                    // Key fields are never null.
                                    values.add(keyText(row, i, type.getTypeAt(projection[i])));
                                }
                                String key = KafkaEventGuard.rowKey(target, values);
                                long version = row.getLong(keys.size());
                                boolean gone =
                                        row.getRowKind() == RowKind.DELETE
                                                || row.getRowKind() == RowKind.UPDATE_BEFORE;
                                (gone ? removed : written).merge(key, version, Math::max);
                            });
                }
            } else if (Objects.equals(next, previous) && ++idle > 3) {
                throw new IllegalStateException(
                        "POSITION_UNKNOWN: the changelog of "
                                + target
                                + " stopped at snapshot "
                                + next
                                + " of "
                                + latest);
            }
            if (next != null && next > latest) {
                break;
            }
            previous = next;
        }
        // A delete removes the version it carries; an update writes a greater one after it.
        removed.forEach(
                (key, version) -> {
                    Long after = written.get(key);
                    if (after == null || after <= version) {
                        deleted.merge(key, version, Math::max);
                    }
                });
        if (deleted.size() > MAX_DELETED) {
            throw new IllegalStateException(
                    "OVERLAP_TOO_LONG: more than "
                            + MAX_DELETED
                            + " rows deleted within twice the overlap; shorten it");
        }
    }

    private static String keyText(InternalRow row, int pos, DataType type) {
        switch (type.getTypeRoot()) {
            case TINYINT:
                return Byte.toString(row.getByte(pos));
            case SMALLINT:
                return Short.toString(row.getShort(pos));
            case INTEGER:
                return Integer.toString(row.getInt(pos));
            case BIGINT:
                return Long.toString(row.getLong(pos));
            default:
                return row.getString(pos).toString();
        }
    }
}
