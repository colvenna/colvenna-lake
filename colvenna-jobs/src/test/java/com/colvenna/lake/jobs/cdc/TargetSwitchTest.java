package com.colvenna.lake.jobs.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.disk.IOManager;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.StreamTableCommit;
import org.apache.paimon.table.sink.StreamTableWrite;
import org.apache.paimon.table.sink.StreamWriteBuilder;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.StreamTableScan;
import org.apache.paimon.table.source.TableScan;
import org.apache.paimon.types.DataTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TargetSwitchTest {
    @TempDir Path warehouse;

    /** A target as the direct job leaves it: input changelog, write-only, two rows. */
    private static FileStoreTable direct(Catalog catalog, String name) throws Exception {
        Identifier id = Identifier.create("ods", name);
        catalog.createTable(
                id,
                Schema.newBuilder()
                        .column("id", DataTypes.BIGINT().notNull())
                        .column("state", DataTypes.STRING())
                        .column("version_no", DataTypes.BIGINT().notNull())
                        .primaryKey("id")
                        .options(
                                Map.of(
                                        "bucket", "1",
                                        "changelog-producer", "input",
                                        "write-only", "true",
                                        "sequence.field", "version_no"))
                        .build(),
                false);
        FileStoreTable table = (FileStoreTable) catalog.getTable(id);
        write(table, 1, row(1, "a", 1), row(2, "b", 1));
        return (FileStoreTable) catalog.getTable(id);
    }

    private static GenericRow row(long id, String state, long version) {
        return GenericRow.of(id, BinaryString.fromString(state), version);
    }

    private static void write(FileStoreTable table, long checkpoint, GenericRow... rows)
            throws Exception {
        StreamWriteBuilder builder = table.newStreamWriteBuilder().withCommitUser("writer");
        try (StreamTableWrite w = builder.newWrite();
                StreamTableCommit c = builder.newCommit()) {
            // Lookup compaction spills to local disk, as it does in a TaskManager.
            w.withIOManager(IOManager.create(System.getProperty("java.io.tmpdir")));
            for (GenericRow r : rows) {
                w.write(r);
            }
            c.commit(checkpoint, w.prepareCommit(true, checkpoint));
        }
    }

    /** The changelog from a snapshot on: "kind id state version" per change. */
    private static List<String> changesFrom(FileStoreTable table, long snapshot) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put("scan.mode", "from-snapshot");
        options.put("scan.snapshot-id", Long.toString(snapshot));
        ReadBuilder read = table.copyWithoutTimeTravel(options).newReadBuilder();
        StreamTableScan scan = read.newStreamScan();
        long latest = table.snapshotManager().latestSnapshotId();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            TableScan.Plan plan = scan.plan();
            try (RecordReader<InternalRow> reader = read.newRead().createReader(plan)) {
                reader.forEachRemaining(
                        r ->
                                out.add(
                                        r.getRowKind().shortString()
                                                + " "
                                                + r.getLong(0)
                                                + " "
                                                + r.getString(1)
                                                + " "
                                                + r.getLong(2)));
            }
            Long next = scan.checkpoint();
            if (next != null && next > latest) {
                break;
            }
        }
        return out;
    }

    @Test
    void aSwitchedTargetRepublishesNothingAndKeepsBeforeImages() throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        try (Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options))) {
            catalog.createDatabase("ods", false);

            // Without the compaction, the first lookup compaction publishes the direct job's rows
            // again.
            FileStoreTable unswitched = direct(catalog, "unswitched");
            FileStoreTable lookup = unswitched.copy(TargetSwitch.KAFKA_CHANGELOG);
            long before = lookup.snapshotManager().latestSnapshotId();
            write(lookup, 2, row(1, "a", 1));
            assertTrue(changesFrom(lookup, before + 1).contains("+I 2 b 1"));

            FileStoreTable table = direct(catalog, "orders");
            Identifier id = Identifier.create("ods", "orders");
            TargetSwitch.apply(catalog, id);
            table = (FileStoreTable) catalog.getTable(id);
            assertTrue(TargetSwitch.switched(table));
            long switched = table.snapshotManager().latestSnapshotId();
            // A change replayed from Kafka, and a stale one: nothing reaches the changelog.
            write(table, 2, row(1, "a", 1));
            write(table, 3, row(2, "old", 0));
            // A real update: its before image is the row the table held.
            write(table, 4, row(1, "paid", 2));
            assertEquals(List.of("-U 1 a 1", "+U 1 paid 2"), changesFrom(table, switched + 1));

            // A switched table is left alone.
            long latest = table.snapshotManager().latestSnapshotId();
            TargetSwitch.apply(catalog, id);
            assertEquals(
                    latest,
                    ((FileStoreTable) catalog.getTable(id)).snapshotManager().latestSnapshotId());
        }
    }
}
