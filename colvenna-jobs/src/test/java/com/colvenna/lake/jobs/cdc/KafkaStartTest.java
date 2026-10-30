package com.colvenna.lake.jobs.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.kafka.shaded.org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.flink.kafka.shaded.org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class KafkaStartTest {
    private static final TopicPartition NEW = new TopicPartition("cdc.shop.refunds", 0);
    private static final TopicPartition FOUND = new TopicPartition("cdc.shop.orders", 0);
    private static final TopicPartition QUIET = new TopicPartition("cdc.shop.orders", 1);
    private static final TopicPartition TRIMMED = new TopicPartition("cdc.shop.orders", 2);
    private static final TopicPartition KEPT = new TopicPartition("cdc.shop.orders", 3);
    private static final TopicPartition EMPTIED = new TopicPartition("cdc.shop.orders", 4);
    private static final TopicPartition NEW_TRIMMED = new TopicPartition("cdc.shop.refunds", 1);

    @Test
    void writtenTargetsStartAnOverlapBeforeTheirLastWrite() {
        KafkaStart.Offsets offsets =
                KafkaStart.choose(
                        List.of(NEW, FOUND, QUIET, TRIMMED, KEPT, EMPTIED, NEW_TRIMMED),
                        Map.of(
                                NEW,
                                0L,
                                FOUND,
                                0L,
                                QUIET,
                                3L,
                                TRIMMED,
                                5L,
                                KEPT,
                                5L,
                                EMPTIED,
                                6L,
                                NEW_TRIMMED,
                                2L),
                        Map.of(
                                NEW,
                                9L,
                                FOUND,
                                50L,
                                QUIET,
                                8L,
                                TRIMMED,
                                10L,
                                KEPT,
                                10L,
                                EMPTIED,
                                6L,
                                NEW_TRIMMED,
                                4L),
                        Map.of(
                                FOUND, new OffsetAndTimestamp(20L, 1000L),
                                TRIMMED, new OffsetAndTimestamp(5L, 1000L),
                                KEPT, new OffsetAndTimestamp(7L, 1000L)),
                        Set.of(FOUND, QUIET, TRIMMED, KEPT, EMPTIED));
        // A table never written reads its topic from the start.
        assertEquals(0L, offsets.start.get(NEW));
        assertEquals(20L, offsets.start.get(FOUND));
        // Nothing at or after the time: everything left was written before it.
        assertEquals(8L, offsets.start.get(QUIET));
        assertEquals(5L, offsets.start.get(TRIMMED));
        assertEquals(7L, offsets.start.get(KEPT));
        // Retention took records that may be changes the table lacks: the oldest left is past
        // the time, nothing is left, or a table read from the beginning lost its first records.
        assertEquals(
                Set.of(TRIMMED.toString(), EMPTIED.toString(), NEW_TRIMMED.toString()),
                Set.copyOf(offsets.unverified));
        // Retention removed records before the time, but kept some: they were written.
        assertEquals(8L, offsets.start.get(QUIET));
    }

    @Test
    void positionsAreAskedForOnlyWhenTheSourceHasNoState() {
        OffsetsInitializer.PartitionOffsetsRetriever retriever =
                new OffsetsInitializer.PartitionOffsetsRetriever() {
                    @Override
                    public Map<TopicPartition, Long> committedOffsets(
                            Collection<TopicPartition> partitions) {
                        throw new AssertionError("the group's offsets decide nothing");
                    }

                    @Override
                    public Map<TopicPartition, Long> endOffsets(
                            Collection<TopicPartition> partitions) {
                        throw new AssertionError();
                    }

                    @Override
                    public Map<TopicPartition, Long> beginningOffsets(
                            Collection<TopicPartition> partitions) {
                        Map<TopicPartition, Long> out = new HashMap<>();
                        partitions.forEach(p -> out.put(p, 3L));
                        return out;
                    }

                    @Override
                    public Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(
                            Map<TopicPartition, Long> timestampsToSearch) {
                        throw new AssertionError();
                    }
                };
        Map<TopicPartition, Long> start =
                KafkaStart.Positions.of(Map.of(FOUND, 20L))
                        .getPartitionOffsets(List.of(FOUND, QUIET), retriever);
        // A partition the topic gained after the start was worked out: from its beginning.
        assertEquals(Map.of(FOUND, 20L, QUIET, 3L), start);
        IllegalStateException unknown =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                KafkaStart.Positions.unknown("POSITION_UNKNOWN: x")
                                        .getPartitionOffsets(List.of(FOUND), retriever));
        assertEquals("POSITION_UNKNOWN: x", unknown.getMessage());
    }
}
