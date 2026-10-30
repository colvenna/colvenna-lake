package com.colvenna.lake.performance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.table.sink.StreamTableCommit;
import org.apache.paimon.table.sink.StreamTableWrite;
import org.apache.paimon.table.sink.StreamWriteBuilder;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotObserverTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void specsNameATableAndItsColumns() {
        SnapshotObserver.Spec rows = SnapshotObserver.Spec.parse("rows=ods.orders,id,10000000,v");
        assertEquals("ods.orders", rows.table);
        assertEquals("id", rows.key);
        assertEquals(10_000_000L, rows.min);
        assertEquals(List.of("v"), rows.columns);
        SnapshotObserver.Spec all = SnapshotObserver.Spec.parse("rows=dws.segments");
        assertNull(all.key);
        SnapshotObserver.Spec offsets =
                SnapshotObserver.Spec.parse("offsets=ods.events,_partition,_offset");
        assertEquals("_partition", offsets.key);
        assertEquals(List.of("_offset"), offsets.columns);
        assertThrows(IllegalArgumentException.class, () -> SnapshotObserver.Spec.parse("rows=x"));
        assertThrows(
                IllegalArgumentException.class, () -> SnapshotObserver.Spec.parse("rows=a.b,id"));
        assertThrows(
                IllegalArgumentException.class, () -> SnapshotObserver.Spec.parse("offsets=a.b,p"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SnapshotObserver.Spec.parse("rows=a.b,id;drop,1"));
        assertThrows(IllegalArgumentException.class, () -> SnapshotObserver.Spec.parse("sum=a.b"));
    }

    @Test
    void offsetsBecomeRangesAndRepeatsShow() {
        assertRanges(List.of(), List.of());
        assertRanges(List.of(5L), List.of(new long[] {5, 5}));
        assertRanges(List.of(3L, 1L, 2L, 7L, 8L), List.of(new long[] {1, 3}, new long[] {7, 8}));
        // 2 twice: [0, 2] and [2, 2].
        assertRanges(
                List.of(0L, 2L, 1L, 2L, 3L),
                List.of(new long[] {0, 2}, new long[] {2, 2}, new long[] {3, 3}));
    }

    private static void assertRanges(List<Long> offsets, List<long[]> expected) {
        List<long[]> actual = SnapshotObserver.ranges(offsets);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i), actual.get(i));
        }
    }

    @Test
    void eventsHaveTheirSizeAndFields() throws Exception {
        byte[] event = EventProducer.event("r1", 42, 1_700_000_000_000L, 1024);
        assertEquals(1024, event.length);
        JsonNode node = JSON.readTree(new String(event, StandardCharsets.UTF_8));
        assertEquals("r1-42", node.get("event_id").asText());
        assertEquals(42, node.get("seq").asLong());
        assertEquals(1_700_000_000_000L, node.get("event_time").asLong());
        assertTrue(node.get("pad").asText().matches("[a-z]+"));
    }

    @Test
    void eachChangeComesWithTheSnapshotThatCommittedIt(@TempDir Path dir) throws Exception {
        Options options = new Options();
        options.set("warehouse", dir.toUri().toString());
        try (Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options))) {
            catalog.createDatabase("ods", false);
            Identifier id = Identifier.create("ods", "orders");
            catalog.createTable(
                    id,
                    Schema.newBuilder()
                            .column("id", DataTypes.BIGINT().notNull())
                            .column("v", DataTypes.STRING())
                            .primaryKey("id")
                            .option("bucket", "1")
                            .option("changelog-producer", "input")
                            // Avro needs no Hadoop configuration on this class path.
                            .option("file.format", "avro")
                            .build(),
                    false);
            FileStoreTable table = (FileStoreTable) catalog.getTable(id);
            StreamWriteBuilder builder = table.newStreamWriteBuilder();
            try (StreamTableWrite write = builder.newWrite();
                    StreamTableCommit commit = builder.newCommit()) {
                commit(write, commit, 1, row(RowKind.INSERT, 1, "a"), row(RowKind.INSERT, 20, "b"));

                StringWriter text = new StringWriter();
                SnapshotObserver.Out out = new SnapshotObserver.Out(new BufferedWriter(text));
                AtomicBoolean stop = new AtomicBoolean();
                SnapshotObserver.Spec spec = SnapshotObserver.Spec.parse("rows=ods.orders,id,10,v");
                List<Exception> failures = new ArrayList<>();
                Thread follower =
                        new Thread(
                                () -> {
                                    try {
                                        SnapshotObserver.follow(table, spec, out, stop::get);
                                    } catch (Exception e) {
                                        failures.add(e);
                                    }
                                });
                follower.start();
                Thread.sleep(1500);
                commit(write, commit, 2, row(RowKind.INSERT, 30, "c"), row(RowKind.INSERT, 2, "x"));
                commit(write, commit, 3, row(RowKind.UPDATE_AFTER, 20, "b2"));
                commit(write, commit, 4, row(RowKind.DELETE, 30, "c"));
                Thread.sleep(3000);
                stop.set(true);
                follower.join();
                assertTrue(failures.isEmpty(), failures.toString());

                List<JsonNode> changes = new ArrayList<>();
                for (String line : text.toString().lines().toArray(String[]::new)) {
                    JsonNode node = JSON.readTree(line);
                    if (node.get("type").asText().equals("CHANGE")) {
                        changes.add(node);
                    }
                }
                // Snapshot 1 is read whole (the rows held when the observer started); the keys
                // below 10 never show.
                assertEquals(4, changes.size(), text.toString());
                assertChange(changes.get(0), table, 1, "+I", "20", "b");
                assertChange(changes.get(1), table, 2, "+I", "30", "c");
                assertChange(changes.get(2), table, 3, "+U", "20", "b2");
                assertChange(changes.get(3), table, 4, "-D", "30", "c");
                assertTrue(text.toString().contains("\"type\":\"END\""));
            }
        }
    }

    private static GenericRow row(RowKind kind, long id, String v) {
        return GenericRow.ofKind(kind, id, BinaryString.fromString(v));
    }

    private static void commit(
            StreamTableWrite write, StreamTableCommit commit, long checkpoint, GenericRow... rows)
            throws Exception {
        for (GenericRow row : rows) {
            write.write(row);
        }
        List<CommitMessage> messages = write.prepareCommit(false, checkpoint);
        commit.commit(checkpoint, messages);
    }

    private static void assertChange(
            JsonNode change,
            FileStoreTable table,
            long snapshot,
            String kind,
            String id,
            String v) {
        assertEquals(snapshot, change.get("snapshot").asLong());
        assertEquals(
                table.snapshotManager().snapshot(snapshot).timeMillis(),
                change.get("committedAt").asLong());
        assertEquals(kind, change.get("kind").asText());
        assertEquals(Map.of("id", id, "v", v), JSON.convertValue(change.get("row"), Map.class));
        assertTrue(change.get("observedAt").asLong() >= change.get("committedAt").asLong());
    }
}
