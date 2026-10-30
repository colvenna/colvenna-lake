package com.colvenna.lake.jobs.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.BatchWriteBuilder;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TargetHistoryTest {
    @TempDir Path warehouse;

    private static FileStoreTable table(Catalog catalog, String name) throws Exception {
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
        return (FileStoreTable) catalog.getTable(id);
    }

    private static GenericRow row(RowKind kind, long id, long version) {
        return GenericRow.ofKind(kind, id, BinaryString.fromString("s"), version);
    }

    private static void commit(FileStoreTable table, GenericRow... rows) throws Exception {
        BatchWriteBuilder builder = table.newBatchWriteBuilder();
        try (BatchTableWrite write = builder.newWrite();
                BatchTableCommit commit = builder.newCommit()) {
            for (GenericRow r : rows) {
                write.write(r);
            }
            commit.commit(write.prepareCommit());
        }
    }

    @Test
    void findsTheLastWriteAndTheRowsDeletedSinceTheReach() throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        try (Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options))) {
            catalog.createDatabase("ods", false);
            FileStoreTable orders = table(catalog, "orders");
            table(catalog, "refunds");
            commit(orders, row(RowKind.INSERT, 1, 1), row(RowKind.INSERT, 2, 1));
            // The CDC parsers write an update as the old row's delete and the new row's insert.
            commit(orders, row(RowKind.DELETE, 1, 1), row(RowKind.INSERT, 1, 2));
            commit(orders, row(RowKind.DELETE, 2, 1));
            long last = orders.snapshotManager().latestSnapshot().timeMillis();

            TargetHistory history =
                    TargetHistory.read(
                            catalog,
                            "ods",
                            List.of("orders", "refunds", "missing"),
                            "version_no",
                            600_000L);
            assertEquals(last, history.lastWriteMillis);
            assertEquals(Set.of("orders"), history.written);
            assertEquals(Set.of("orders", "refunds"), history.keyFields.keySet());
            assertEquals(List.of("id"), history.keyFields.get("orders"));
            // Row 1 was updated, row 2 deleted at version 1.
            assertEquals(
                    Map.of(KafkaEventGuard.rowKey("orders", List.of("2")), 1L), history.deleted);
        }
    }

    @Test
    void nothingWrittenMeansNoPosition() throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        try (Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options))) {
            catalog.createDatabase("ods", false);
            table(catalog, "orders");
            TargetHistory history =
                    TargetHistory.read(catalog, "ods", List.of("orders"), "version_no", 600_000L);
            assertNull(history.lastWriteMillis);
            assertTrue(history.written.isEmpty());
            assertTrue(history.deleted.isEmpty());
        }
    }
}
