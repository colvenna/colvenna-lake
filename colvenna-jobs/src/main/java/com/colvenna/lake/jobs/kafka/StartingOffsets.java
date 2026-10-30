package com.colvenna.lake.jobs.kafka;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.kafka.shaded.org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.flink.kafka.shaded.org.apache.kafka.common.TopicPartition;
import org.apache.paimon.Snapshot;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.utils.SnapshotManager;

/**
 * Where a job without state continues each Kafka partition, and which records it must not write
 * again. Every record the job reads lands in exactly one of its two tables, so the tables are the
 * consumption record, whatever the consumer group says. Both commit every checkpoint ({@code
 * commit.force-create-snapshot}), but one after the other: a job deleted in between leaves one
 * table a checkpoint ahead. Up to the older of their last commits the tables agree; a new job
 * continues after what they hold as of that commit, and drops the records the table ahead already
 * holds beyond it. Commits of one checkpoint are told apart from the next by time: they land within
 * milliseconds of each other, checkpoints at least {@link #MIN_CHECKPOINT_INTERVAL_MS} apart.
 */
public final class StartingOffsets {
    private StartingOffsets() {}

    /** One topic partition. */
    public static final class Partition implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String topic;
        public final int partition;

        public Partition(String topic, int partition) {
            this.topic = topic;
            this.partition = partition;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Partition
                    && ((Partition) o).topic.equals(topic)
                    && ((Partition) o).partition == partition;
        }

        @Override
        public int hashCode() {
            return topic.hashCode() * 31 + partition;
        }

        @Override
        public String toString() {
            return topic + "-" + partition;
        }
    }

    /** What the tables say: the next offset per partition, and records written past it. */
    public static final class Resume implements Serializable {
        private static final long serialVersionUID = 1L;
        public final HashMap<Partition, Long> next;
        public final HashMap<Partition, HashSet<Long>> written;

        Resume(HashMap<Partition, Long> next, HashMap<Partition, HashSet<Long>> written) {
            this.next = next;
            this.written = written;
        }

        /** Whether a table already holds the record at this offset, beyond the agreed point. */
        public boolean holds(String topic, int partition, long offset) {
            Set<Long> offsets = written.get(new Partition(topic, partition));
            return offsets != null && offsets.contains(offset);
        }
    }

    /** Checkpoints of a Kafka source are at least this far apart (colvenna-operator checks). */
    public static final long MIN_CHECKPOINT_INTERVAL_MS = 10_000L;

    /** Two commits this close belong to the same checkpoint. */
    static final long SAME_CHECKPOINT_MS = MIN_CHECKPOINT_INTERVAL_MS / 2;

    /** Reads the tables. */
    public static Resume resume(List<FileStoreTable> tables) throws Exception {
        return resume(tables, SAME_CHECKPOINT_MS);
    }

    static Resume resume(List<FileStoreTable> tables, long sameCheckpointMs) throws Exception {
        Long agreed = null;
        boolean neverCommitted = false;
        for (FileStoreTable table : tables) {
            Snapshot last = lastWriteAtOrBefore(table.snapshotManager(), Long.MAX_VALUE);
            if (last == null) {
                neverCommitted = true;
            } else {
                agreed = agreed == null ? last.timeMillis() : Math.min(agreed, last.timeMillis());
            }
        }
        if (neverCommitted) {
            // A table that never committed agrees on nothing.
            agreed = null;
        }
        HashMap<Partition, Long> next = new HashMap<>();
        HashMap<Partition, HashSet<Long>> written = new HashMap<>();
        for (FileStoreTable table : tables) {
            Snapshot latest = table.snapshotManager().latestSnapshot();
            if (latest == null) {
                continue;
            }
            Snapshot asOf =
                    agreed == null
                            ? null
                            : lastWriteAtOrBefore(
                                    table.snapshotManager(), agreed + sameCheckpointMs);
            if (asOf != null) {
                Map<String, String> at = Map.of("scan.snapshot-id", Long.toString(asOf.id()));
                read(table.copy(at), (p, offset) -> next.merge(p, offset + 1, Math::max));
            }
            if (asOf == null || asOf.id() < latest.id()) {
                // What the table holds beyond the agreed commit.
                FileStoreTable beyond =
                        asOf == null
                                ? table
                                : table.copy(
                                        Map.of(
                                                "incremental-between",
                                                asOf.id() + "," + latest.id()));
                read(
                        beyond,
                        (p, offset) ->
                                written.computeIfAbsent(p, k -> new HashSet<>()).add(offset));
            }
        }
        return new Resume(next, written);
    }

    /** The job's latest commit (not a compaction) at or before the time, or null. */
    static Snapshot lastWriteAtOrBefore(SnapshotManager snapshots, long timeMillis) {
        Long latest = snapshots.latestSnapshotId();
        Long earliest = snapshots.earliestSnapshotId();
        if (latest == null || earliest == null) {
            return null;
        }
        for (long id = latest; id >= earliest; id--) {
            if (snapshots.snapshotExists(id)) {
                Snapshot s = snapshots.snapshot(id);
                if (s.commitKind() == Snapshot.CommitKind.APPEND && s.timeMillis() <= timeMillis) {
                    return s;
                }
            }
        }
        return null;
    }

    private interface Visitor {
        void visit(Partition partition, long offset);
    }

    private static void read(FileStoreTable table, Visitor visitor) throws Exception {
        List<String> names = table.rowType().getFieldNames();
        int[] projection = {
            names.indexOf(EventSpec.TOPIC),
            names.indexOf(EventSpec.PARTITION),
            names.indexOf(EventSpec.OFFSET)
        };
        ReadBuilder read = table.newReadBuilder().withProjection(projection);
        try (RecordReader<InternalRow> reader =
                read.newRead().createReader(read.newScan().plan())) {
            reader.forEachRemaining(
                    row ->
                            visitor.visit(
                                    new Partition(row.getString(0).toString(), row.getInt(1)),
                                    row.getLong(2)));
        }
    }

    /**
     * The source's start for partitions it has no state for. A partition the tables have seen
     * continues after them. One they have not starts where the declaration says: its beginning, or
     * for {@code latest} its end, unless the table ahead holds records of it, which the first job
     * read from where it started. Never the consumer group's offsets: those are kept for the lag.
     */
    public static final class Positions implements OffsetsInitializer {
        private static final long serialVersionUID = 1L;
        private final Resume resume;
        private final boolean latest;

        public Positions(Resume resume, boolean latest) {
            this.resume = resume;
            this.latest = latest;
        }

        @Override
        public Map<TopicPartition, Long> getPartitionOffsets(
                Collection<TopicPartition> partitions, PartitionOffsetsRetriever retriever) {
            Map<TopicPartition, Long> out = new HashMap<>();
            List<TopicPartition> unseen = new ArrayList<>();
            for (TopicPartition tp : partitions) {
                Partition p = new Partition(tp.topic(), tp.partition());
                Long next = resume.next.get(p);
                Set<Long> written = resume.written.get(p);
                if (next != null) {
                    out.put(tp, next);
                } else if (latest && written != null && !written.isEmpty()) {
                    out.put(tp, Collections.min(written));
                } else {
                    unseen.add(tp);
                }
            }
            if (!unseen.isEmpty()) {
                out.putAll(
                        latest ? retriever.endOffsets(unseen) : retriever.beginningOffsets(unseen));
            }
            return out;
        }

        @Override
        public OffsetResetStrategy getAutoOffsetResetStrategy() {
            return latest ? OffsetResetStrategy.LATEST : OffsetResetStrategy.EARLIEST;
        }
    }
}
