package com.colvenna.lake.jobs.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.kafka.shaded.org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.flink.kafka.shaded.org.apache.kafka.common.TopicPartition;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.StreamTableCommit;
import org.apache.paimon.table.sink.StreamTableWrite;
import org.apache.paimon.table.sink.StreamWriteBuilder;
import org.apache.paimon.types.RowType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartingOffsetsTest {
    /** How much further apart than one checkpoint's commits the test's checkpoints are. */
    private static final long CHECKPOINT_GAP_MARGIN_MS = 300L;

    @TempDir Path warehouse;

    private static FileStoreTable create(Catalog catalog, String name, RowType type)
            throws Exception {
        Schema.Builder b = Schema.newBuilder();
        type.getFields().forEach(f -> b.column(f.name(), f.type()));
        b.option("bucket", "-1");
        catalog.createTable(Identifier.create("ods", name), b.build(), false);
        return (FileStoreTable) catalog.getTable(Identifier.create("ods", name));
    }

    /** One table's commit of a checkpoint, as the job's sink makes it. */
    private static void commit(FileStoreTable table, long checkpoint, GenericRow... rows)
            throws Exception {
        StreamWriteBuilder builder = table.newStreamWriteBuilder().withCommitUser(table.name());
        try (StreamTableWrite w = builder.newWrite();
                StreamTableCommit c = builder.newCommit()) {
            for (GenericRow row : rows) {
                w.write(row);
            }
            c.commit(checkpoint, w.prepareCommit(false, checkpoint));
        }
    }

    /**
     * How far apart the two tables' last commits are, as resume() finds them: the test's window for
     * one checkpoint. A loaded machine commits slower, so the window is measured rather than
     * guessed; checkpoints are kept further apart than it by sleeping.
     */
    private static long apart(FileStoreTable a, FileStoreTable b) {
        return Math.abs(
                StartingOffsets.lastWriteAtOrBefore(a.snapshotManager(), Long.MAX_VALUE)
                                .timeMillis()
                        - StartingOffsets.lastWriteAtOrBefore(b.snapshotManager(), Long.MAX_VALUE)
                                .timeMillis());
    }

    private static BinaryString s(String v) {
        return BinaryString.fromString(v);
    }

    private static GenericRow event(int partition, long offset) {
        return GenericRow.of(
                s("e" + offset),
                s("click"),
                null,
                null,
                null,
                1L,
                s("2026-10-15"),
                null,
                s("events"),
                partition,
                offset);
    }

    private static GenericRow rejection(int partition, long offset) {
        return GenericRow.of(s("2026-10-15"), s("LATE"), null, 1L, s("events"), partition, offset);
    }

    private static StartingOffsets.Partition p(int partition) {
        return new StartingOffsets.Partition("events", partition);
    }

    @Test
    void aJobDeletedBetweenTheTwoCommitsNeitherSkipsNorRepeats() throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        EventSpec spec = EventSpec.parse(EventParserTest.SPEC);
        try (Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options))) {
            catalog.createDatabase("ods", false);
            FileStoreTable events = create(catalog, "events", spec.eventRowType());
            FileStoreTable rejections =
                    create(catalog, "events_rejections", EventSpec.rejectionRowType());
            StartingOffsets.Resume fresh = StartingOffsets.resume(List.of(events, rejections));
            assertTrue(fresh.next.isEmpty() && fresh.written.isEmpty());

            // Checkpoint 1 in both tables; offset 2 was rejected and counts as read.
            commit(events, 1, event(0, 0), event(0, 1));
            commit(rejections, 1, rejection(0, 2));
            long window = apart(events, rejections);
            Thread.sleep(window + CHECKPOINT_GAP_MARGIN_MS);
            // Checkpoint 2 reaches the events table only: the job is deleted before the
            // rejections table commits offset 4 of partition 0.
            commit(events, 2, event(0, 3), event(1, 0));

            StartingOffsets.Resume resume =
                    StartingOffsets.resume(List.of(events, rejections), window);
            // The tables agree on checkpoint 1: partition 0 continues at 3, partition 1 from
            // its beginning.
            assertEquals(Map.of(p(0), 3L), resume.next);
            // What only the events table holds is not written again; offset 4 is read again.
            assertTrue(resume.holds("events", 0, 3));
            assertTrue(resume.holds("events", 1, 0));
            assertFalse(resume.holds("events", 0, 4));
            assertFalse(resume.holds("events", 0, 2));

            Map<TopicPartition, Long> start =
                    new StartingOffsets.Positions(resume, false)
                            .getPartitionOffsets(
                                    List.of(
                                            new TopicPartition("events", 0),
                                            new TopicPartition("events", 1)),
                                    beginningAt(0L));
            assertEquals(
                    Map.of(
                            new TopicPartition("events", 0),
                            3L,
                            new TopicPartition("events", 1),
                            0L),
                    start);

            // Once both commit again they agree on the latest checkpoint, with the window widened
            // to how far apart they committed.
            commit(rejections, 2, rejection(0, 4));
            StartingOffsets.Resume agreed =
                    StartingOffsets.resume(
                            List.of(events, rejections),
                            Math.max(window, apart(events, rejections)));
            assertEquals(Map.of(p(0), 5L, p(1), 1L), agreed.next);
            assertTrue(agreed.written.isEmpty());

            KafkaEventJob.requireSchema("events", events.rowType(), spec.eventRowType());
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            KafkaEventJob.requireSchema(
                                    "events", rejections.rowType(), spec.eventRowType()));
        }
    }

    @Test
    void aTableThatNeverCommittedAgreesOnNothing() throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        EventSpec spec = EventSpec.parse(EventParserTest.SPEC);
        try (Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options))) {
            catalog.createDatabase("ods", false);
            FileStoreTable events = create(catalog, "events", spec.eventRowType());
            FileStoreTable rejections =
                    create(catalog, "events_rejections", EventSpec.rejectionRowType());
            commit(events, 1, event(0, 0), event(0, 2));
            StartingOffsets.Resume resume = StartingOffsets.resume(List.of(events, rejections));
            assertTrue(resume.next.isEmpty());
            assertEquals(Map.of(p(0), Set.of(0L, 2L)), resume.written);
            // Read from where the first job started: offset 1 is read again, 0 and 2 dropped.
            Map<TopicPartition, Long> latest =
                    new StartingOffsets.Positions(resume, true)
                            .getPartitionOffsets(
                                    List.of(new TopicPartition("events", 0)), beginningAt(0L));
            assertEquals(Map.of(new TopicPartition("events", 0), 0L), latest);
        }
    }

    /** A cluster whose partitions begin at the offset, and whose group offsets must not count. */
    private static OffsetsInitializer.PartitionOffsetsRetriever beginningAt(long offset) {
        return new OffsetsInitializer.PartitionOffsetsRetriever() {
            @Override
            public Map<TopicPartition, Long> committedOffsets(Collection<TopicPartition> ps) {
                throw new AssertionError("the group's offsets decide nothing");
            }

            @Override
            public Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> ps) {
                throw new AssertionError("no partition starts at its end here");
            }

            @Override
            public Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> ps) {
                Map<TopicPartition, Long> out = new HashMap<>();
                ps.forEach(tp -> out.put(tp, offset));
                return out;
            }

            @Override
            public Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(
                    Map<TopicPartition, Long> times) {
                throw new AssertionError();
            }
        };
    }

    @Test
    void everyTopicTheTablesHoldContinues() throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        EventSpec spec = EventSpec.parse(EventParserTest.SPEC);
        try (Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options))) {
            catalog.createDatabase("ods", false);
            FileStoreTable events = create(catalog, "events", spec.eventRowType());
            FileStoreTable rejections =
                    create(catalog, "events_rejections", EventSpec.rejectionRowType());
            GenericRow other = event(0, 9);
            other.setField(8, s("events-b"));
            commit(events, 1, event(0, 0), other);
            GenericRow otherRejected = rejection(0, 10);
            otherRejected.setField(4, s("events-b"));
            commit(rejections, 1, otherRejected);
            StartingOffsets.Resume resume =
                    StartingOffsets.resume(List.of(events, rejections), apart(events, rejections));
            assertEquals(
                    Map.of(p(0), 1L, new StartingOffsets.Partition("events-b", 0), 11L),
                    resume.next);
        }
    }
}
