package com.colvenna.lake.jobs.sql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.config.ExecutionConfigOptions;
import org.apache.flink.table.api.config.ExecutionConfigOptions.UpsertMaterialize;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.consumer.Consumer;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.BatchWriteBuilder;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlJobTest {
    @TempDir Path warehouse;

    private FileStoreTable table(Catalog catalog, String name) throws Exception {
        Identifier id = Identifier.create("db", name);
        catalog.createTable(
                id,
                Schema.newBuilder()
                        .column("k", DataTypes.BIGINT().notNull())
                        .column("v", DataTypes.STRING())
                        .primaryKey("k")
                        .options(Map.of("bucket", "1"))
                        .build(),
                false);
        return (FileStoreTable) catalog.getTable(id);
    }

    private static void write(FileStoreTable table, long key) throws Exception {
        BatchWriteBuilder builder = table.newBatchWriteBuilder();
        try (BatchTableWrite write = builder.newWrite();
                BatchTableCommit commit = builder.newCommit()) {
            write.write(GenericRow.of(key, null));
            commit.commit(write.prepareCommit());
        }
    }

    @Test
    void refusesToStartWithoutItsUpstreamPosition() throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        try (Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options))) {
            catalog.createDatabase("db", false);
            FileStoreTable upstream = table(catalog, "up");
            FileStoreTable target = table(catalog, "down");
            write(upstream, 1);
            write(upstream, 2);

            // A fresh table: nothing read yet, nothing written.
            assertDoesNotThrow(
                    () -> SqlJob.requireRecoverableConsumer(upstream, "db.up", target, "c"));

            write(target, 1);
            IllegalStateException missing =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    SqlJob.requireRecoverableConsumer(
                                            upstream, "db.up", target, "c"));
            assertTrue(missing.getMessage().startsWith("REBUILD_REQUIRED"));

            upstream.consumerManager().resetConsumer("c", new Consumer(3));
            assertDoesNotThrow(
                    () -> SqlJob.requireRecoverableConsumer(upstream, "db.up", target, "c"));

            // The upstream no longer keeps the snapshot the consumer is due to read.
            upstream.consumerManager().resetConsumer("c", new Consumer(1));
            upstream.snapshotManager().commitEarliestHint(2);
            IllegalStateException behind =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    SqlJob.requireRecoverableConsumer(
                                            upstream, "db.up", target, "c"));
            assertTrue(behind.getMessage().contains("behind the earliest snapshot"));
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** An upstream table: its BIGINT key, and other BIGINT columns. */
    private static void keyed(Catalog catalog, String table, String key, String... columns)
            throws Exception {
        Schema.Builder schema =
                Schema.newBuilder()
                        .column(key, DataTypes.BIGINT().notNull())
                        .primaryKey(key)
                        .options(Map.of("bucket", "1", "changelog-producer", "input"));
        for (String column : columns) {
            schema.column(column, DataTypes.BIGINT());
        }
        catalog.createTable(Identifier.fromString(table), schema.build(), false);
    }

    /** A table keyed by order_id with a check declared, its query streaming {@code streams}. */
    private static SqlSpec checked(String query, String... streams) throws Exception {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("targetDatabase", "dwd");
        spec.put("targetTable", "t");
        spec.put("query", query);
        spec.put("primaryKey", List.of("order_id"));
        spec.put("upstream", List.of("ods.orders", "ods.customers", "ods.payments"));
        spec.put("streams", List.of(streams));
        spec.put(
                "quality",
                Map.of(
                        "quarantine",
                        "t_quarantine",
                        "rules",
                        List.of(Map.of("column", "amount", "kind", "notNull"))));
        return SqlSpec.parse(JSON.writeValueAsString(spec));
    }

    @Test
    void aCheckedTableIsRevalidatedWithTheUpsertMaterializerStillOn() throws Exception {
        TableEnvironment tEnv = TableEnvironment.create(EnvironmentSettings.inStreamingMode());
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        try (Catalog catalog = ColvennaCatalog.register(tEnv, options)) {
            catalog.createDatabase("ods", false);
            keyed(catalog, "ods.orders", "order_id", "customer_id", "amount");
            keyed(catalog, "ods.customers", "customer_id", "region");
            keyed(catalog, "ods.payments", "order_id", "paid");

            // A join on a column outside the key: updates of one order can arrive out of order.
            SqlSpec unordered =
                    checked(
                            "SELECT o.order_id, c.region, o.amount FROM ods.orders AS o JOIN"
                                    + " ods.customers AS c ON o.customer_id = c.customer_id",
                            "ods.orders",
                            "ods.customers");
            IllegalStateException refused =
                    assertThrows(
                            IllegalStateException.class, () -> SqlJob.revalidate(tEnv, unordered));
            assertTrue(
                    refused.getMessage().startsWith("QUERY_REFUSED UnorderedChangelog"),
                    refused.getMessage());

            // A join on the key keeps order: the re-validation passes and leaves the materializer
            // Paimon refuses switched off for the job's statement.
            SqlValidate.Result ordered =
                    SqlJob.revalidate(
                            tEnv,
                            checked(
                                    "SELECT o.order_id, o.amount, p.paid FROM ods.orders AS o"
                                            + " JOIN ods.payments AS p ON o.order_id = p.order_id",
                                    "ods.orders",
                                    "ods.payments"));
            assertEquals(3, ordered.fields.size());
            assertEquals(
                    UpsertMaterialize.NONE,
                    tEnv.getConfig()
                            .get(ExecutionConfigOptions.TABLE_EXEC_SINK_UPSERT_MATERIALIZE));
        }
    }

    @Test
    void comparesTheOutputWithTheTargetAsideFromNullability() {
        List<DataField> output =
                List.of(
                        new DataField(0, "k", DataTypes.BIGINT().notNull()),
                        new DataField(1, "v", DataTypes.STRING().notNull()));
        RowType target =
                RowType.of(
                        new DataField(0, "k", DataTypes.BIGINT().notNull()),
                        new DataField(1, "v", DataTypes.STRING()));
        assertDoesNotThrow(() -> SqlJob.requireSchema(target, output));
        // Nulls could now reach a NOT NULL column.
        assertThrows(
                IllegalStateException.class,
                () ->
                        SqlJob.requireSchema(
                                target,
                                List.of(
                                        new DataField(0, "k", DataTypes.BIGINT()),
                                        new DataField(1, "v", DataTypes.STRING()))));
        IllegalStateException changed =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                SqlJob.requireSchema(
                                        target,
                                        List.of(
                                                new DataField(0, "k", DataTypes.BIGINT()),
                                                new DataField(1, "v", DataTypes.INT()))));
        assertTrue(changed.getMessage().startsWith("SCHEMA_MISMATCH"));
    }
}
