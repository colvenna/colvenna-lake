package com.colvenna.lake.jobs.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.fs.FileStatus;
import org.apache.paimon.fs.Path;
import org.apache.paimon.iceberg.IcebergPathFactory;
import org.apache.paimon.iceberg.manifest.IcebergManifestFileMeta;
import org.apache.paimon.iceberg.manifest.IcebergManifestList;
import org.apache.paimon.iceberg.metadata.IcebergMetadata;
import org.apache.paimon.options.Options;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.table.FileStoreTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskJobTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir java.nio.file.Path warehouse;

    private static List<String> rows(TableEnvironment tEnv, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (CloseableIterator<Row> it = tEnv.executeSql(sql).collect()) {
            it.forEachRemaining(r -> out.add(r.toString()));
        }
        out.sort(null);
        return out;
    }

    @Test
    void aFullCompactionLeavesTheManifestsOfATableKeepingIcebergMetadata() throws Exception {
        TableEnvironment tEnv = TableEnvironment.create(EnvironmentSettings.inBatchMode());
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        Catalog catalog = ColvennaCatalog.register(tEnv, options);
        tEnv.executeSql("CREATE DATABASE ods").await();
        tEnv.executeSql(
                        "CREATE TABLE ods.orders (id BIGINT, state STRING, PRIMARY KEY (id) NOT"
                                + " ENFORCED) WITH ('bucket' = '1',"
                                + " 'metadata.iceberg.storage' = 'table-location')")
                .await();
        tEnv.executeSql("INSERT INTO ods.orders VALUES (1, 'created'), (2, 'created')").await();
        Map<String, Object> compact =
                TaskJob.run(
                        tEnv,
                        catalog,
                        JSON.readTree(
                                "{\"type\":\"compact\",\"database\":\"ods\",\"table\":\"orders\","
                                        + "\"full\":true}"));
        assertTrue(
                compact.get("detail")
                        .toString()
                        .endsWith("manifests not compacted: the table keeps Iceberg metadata"));
        // Every snapshot has its Iceberg metadata: the next commit's builds on it.
        FileStoreTable table =
                (FileStoreTable) catalog.getTable(Identifier.create("ods", "orders"));
        long latest = table.snapshotManager().latestSnapshotId();
        assertTrue(
                table.fileIO()
                        .exists(
                                new Path(
                                        table.location(),
                                        "metadata/v" + latest + ".metadata.json")));
    }

    @Test
    void anErasuresCompactionRewritesTheManifestsAndTheIcebergMetadataAnew() throws Exception {
        TableEnvironment tEnv = TableEnvironment.create(EnvironmentSettings.inBatchMode());
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        Catalog catalog = ColvennaCatalog.register(tEnv, options);
        tEnv.executeSql("CREATE DATABASE ods").await();
        tEnv.executeSql(
                        "CREATE TABLE ods.orders (id BIGINT, state STRING, PRIMARY KEY (id) NOT"
                                + " ENFORCED) WITH ('bucket' = '1',"
                                + " 'metadata.iceberg.storage' = 'table-location',"
                                + " 'metadata.iceberg.previous-versions-max' = '30')")
                .await();
        tEnv.executeSql("INSERT INTO ods.orders VALUES (1, 'created'), (2, 'created')").await();
        tEnv.executeSql("INSERT INTO ods.orders VALUES (3, 'created')").await();
        Map<String, Object> compact =
                TaskJob.run(
                        tEnv,
                        catalog,
                        JSON.readTree(
                                "{\"type\":\"compact\",\"database\":\"ods\",\"table\":\"orders\","
                                        + "\"full\":true,\"manifests\":true}"));
        FileStoreTable table =
                (FileStoreTable) catalog.getTable(Identifier.create("ods", "orders"));
        long latest = table.snapshotManager().latestSnapshotId();
        assertTrue(
                compact.get("detail")
                        .toString()
                        .endsWith(
                                "manifests compacted; Iceberg metadata written anew at snapshot "
                                        + latest),
                compact.get("detail").toString());
        // The latest snapshot (an empty commit after the manifest compaction) has its metadata,
        // and the older manifests, which hold the statistics of the files rewritten, are gone:
        // every manifest left is the new metadata's.
        Path metadata = new Path(table.location(), "metadata");
        IcebergPathFactory paths = new IcebergPathFactory(metadata);
        String list =
                IcebergMetadata.fromPath(table.fileIO(), paths.toMetadataPath(latest))
                        .currentSnapshot()
                        .manifestList();
        Set<String> reachable = new HashSet<>();
        reachable.add(new Path(list).getName());
        for (IcebergManifestFileMeta m :
                IcebergManifestList.create(table, paths).read(new Path(list).getName())) {
            reachable.add(new Path(m.manifestPath()).getName());
        }
        Set<String> avro = new HashSet<>();
        for (FileStatus f : table.fileIO().listStatus(metadata)) {
            if (f.getPath().getName().endsWith(".avro")) {
                avro.add(f.getPath().getName());
            }
        }
        assertEquals(reachable, avro);
    }

    @Test
    void aBackfillRecomputesTheTableAndMaintenanceRunsItsProcedures() throws Exception {
        TableEnvironment tEnv = TableEnvironment.create(EnvironmentSettings.inBatchMode());
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        Catalog catalog = ColvennaCatalog.register(tEnv, options);
        tEnv.executeSql("CREATE DATABASE ods").await();
        tEnv.executeSql("CREATE DATABASE dwd").await();
        tEnv.executeSql(
                        "CREATE TABLE ods.orders (id BIGINT, customer BIGINT, PRIMARY KEY (id) NOT"
                                + " ENFORCED) WITH ('bucket' = '1')")
                .await();
        tEnv.executeSql(
                        "CREATE TABLE ods.segments (id BIGINT, label STRING, PRIMARY KEY (id) NOT"
                                + " ENFORCED) WITH ('bucket' = '1')")
                .await();
        tEnv.executeSql(
                        "CREATE TABLE dwd.orders (order_id BIGINT, segment STRING, PRIMARY KEY"
                                + " (order_id) NOT ENFORCED) WITH ('bucket' = '1',"
                                + " 'changelog-producer' = 'lookup',"
                                + " 'changelog-producer.row-deduplicate' = 'true')")
                .await();
        tEnv.executeSql("INSERT INTO ods.orders VALUES (1, 10), (2, 20), (3, 10)").await();
        tEnv.executeSql("INSERT INTO ods.segments VALUES (10, 'gold'), (20, 'silver')").await();
        // What the table held before the dimension changed, and a row no longer derived.
        tEnv.executeSql("INSERT INTO dwd.orders VALUES (1, 'bronze'), (2, 'silver'), (99, 'gone')")
                .await();

        Map<String, Object> backfill =
                TaskJob.run(
                        tEnv,
                        catalog,
                        JSON.readTree(
                                "{\"type\":\"backfill\",\"database\":\"dwd\",\"table\":\"orders\","
                                        + "\"primaryKey\":[\"order_id\"],\"query\":\"SELECT o.id AS"
                                        + " order_id, s.label AS segment FROM ods.orders o JOIN"
                                        + " ods.segments s ON o.customer = s.id\"}"));
        assertEquals("upserted 3 rows, deleted 1 no longer derived", backfill.get("detail"));
        assertEquals(
                List.of("+I[1, gold]", "+I[2, silver]", "+I[3, gold]"),
                rows(tEnv, "SELECT * FROM dwd.orders"));

        Map<String, Object> compact =
                TaskJob.run(
                        tEnv,
                        catalog,
                        JSON.readTree(
                                "{\"type\":\"compact\",\"database\":\"dwd\",\"table\":\"orders\","
                                        + "\"full\":true}"));
        assertTrue((Long) compact.get("snapshotAfter") > (Long) compact.get("snapshotBefore"));

        // A layered table's lookup join, as streaming declares it, runs as a batch too.
        tEnv.executeSql("INSERT INTO ods.segments VALUES (10, 'platinum')").await();
        Map<String, Object> lookup =
                TaskJob.run(
                        tEnv,
                        catalog,
                        JSON.readTree(
                                "{\"type\":\"backfill\",\"database\":\"dwd\",\"table\":\"orders\","
                                        + "\"primaryKey\":[\"order_id\"],\"query\":\"SELECT o.id AS"
                                        + " order_id, s.label AS segment FROM (SELECT *, PROCTIME() AS"
                                        + " pt FROM ods.orders) AS o JOIN ods.segments FOR SYSTEM_TIME"
                                        + " AS OF o.pt AS s ON o.customer = s.id\"}"));
        assertEquals("upserted 3 rows, deleted 0 no longer derived", lookup.get("detail"));
        assertEquals(
                List.of("+I[1, platinum]", "+I[2, silver]", "+I[3, platinum]"),
                rows(tEnv, "SELECT * FROM dwd.orders"));

        // With a check declared, the rows that fail it go to the quarantine, and leave the table.
        tEnv.executeSql(
                        "CREATE TABLE dwd.orders_quarantine (order_id BIGINT, segment STRING,"
                                + " colvenna_rule STRING, colvenna_flagged_at TIMESTAMP_LTZ(3), PRIMARY KEY"
                                + " (order_id) NOT ENFORCED) WITH ('bucket' = '1')")
                .await();
        String checked =
                "{\"type\":\"backfill\",\"database\":\"dwd\",\"table\":\"orders\","
                        + "\"primaryKey\":[\"order_id\"],\"query\":\"SELECT o.id AS order_id,"
                        + " s.label AS segment FROM ods.orders o JOIN ods.segments s ON o.customer ="
                        + " s.id\",\"quality\":{\"quarantine\":\"orders_quarantine\",\"rules\":"
                        + "[{\"column\":\"segment\",\"kind\":\"allowed\",\"values\":[\"silver\","
                        + "\"gold\"]}]}}";
        assertEquals(
                "upserted 1 rows, deleted 2 no longer derived; quarantine upserted 2 rows, deleted 0"
                        + " no longer derived",
                TaskJob.run(tEnv, catalog, JSON.readTree(checked)).get("detail"));
        assertEquals(List.of("+I[2, silver]"), rows(tEnv, "SELECT * FROM dwd.orders"));
        assertEquals(
                List.of("+I[1, allowed(segment)]", "+I[3, allowed(segment)]"),
                rows(tEnv, "SELECT order_id, colvenna_rule FROM dwd.orders_quarantine"));
        // Fixed at the source: back in the table, out of the quarantine.
        tEnv.executeSql("INSERT INTO ods.segments VALUES (10, 'gold')").await();
        assertEquals(
                "upserted 3 rows, deleted 0 no longer derived; quarantine upserted 0 rows, deleted 2"
                        + " no longer derived",
                TaskJob.run(tEnv, catalog, JSON.readTree(checked)).get("detail"));
        assertEquals(List.of(), rows(tEnv, "SELECT order_id FROM dwd.orders_quarantine"));

        String tag =
                "{\"type\":\"createTag\",\"database\":\"dwd\",\"table\":\"orders\",\"tag\":\"eod\"}";
        assertEquals("tagged eod", TaskJob.run(tEnv, catalog, JSON.readTree(tag)).get("detail"));
        // Again, as a retry would: the tag is there.
        assertEquals(
                "tag eod exists", TaskJob.run(tEnv, catalog, JSON.readTree(tag)).get("detail"));
        FileStoreTable table =
                (FileStoreTable) catalog.getTable(Identifier.create("dwd", "orders"));
        assertTrue(table.tagManager().tagExists("eod"));
        String untag =
                "{\"type\":\"deleteTag\",\"database\":\"dwd\",\"table\":\"orders\",\"tag\":\"eod\"}";
        assertEquals(
                "deleted tag eod", TaskJob.run(tEnv, catalog, JSON.readTree(untag)).get("detail"));
        // Again, as a retry would: it is gone.
        assertEquals(
                "tag eod is gone", TaskJob.run(tEnv, catalog, JSON.readTree(untag)).get("detail"));
        table = (FileStoreTable) catalog.getTable(Identifier.create("dwd", "orders"));
        assertTrue(!table.tagManager().tagExists("eod"));

        Map<String, Object> expire =
                TaskJob.run(
                        tEnv,
                        catalog,
                        JSON.readTree(
                                "{\"type\":\"expireSnapshots\",\"database\":\"dwd\","
                                        + "\"table\":\"orders\",\"retainMin\":1,\"olderThanMillis\":1}"));
        assertTrue(expire.get("detail").toString().startsWith("expired "));
        Map<String, Object> orphans =
                TaskJob.run(
                        tEnv,
                        catalog,
                        JSON.readTree(
                                "{\"type\":\"removeOrphanFiles\",\"database\":\"dwd\","
                                        + "\"table\":\"orders\",\"olderThanMillis\":86400000}"));
        assertTrue(orphans.get("detail").toString().startsWith("removed "));
        // Nothing the table needs went: its rows are all there.
        assertEquals(3, rows(tEnv, "SELECT * FROM dwd.orders").size());
    }

    @Test
    void aCompactionOfSomePartitionsAndABackfillByTypedKeys() throws Exception {
        TableEnvironment tEnv = TableEnvironment.create(EnvironmentSettings.inBatchMode());
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        Catalog catalog = ColvennaCatalog.register(tEnv, options);
        tEnv.executeSql("CREATE DATABASE ods").await();
        tEnv.executeSql(
                        "CREATE TABLE ods.events (dt STRING, id BIGINT) PARTITIONED BY (dt) WITH"
                                + " ('bucket' = '-1', 'write-only' = 'true')")
                .await();
        for (int i = 0; i < 5; i++) {
            tEnv.executeSql("INSERT INTO ods.events VALUES ('2026-10-13', 1), ('2026-10-14', 2)")
                    .await();
        }
        Map<String, Object> compact =
                TaskJob.run(
                        tEnv,
                        catalog,
                        JSON.readTree(
                                "{\"type\":\"compact\",\"database\":\"ods\",\"table\":\"events\","
                                        + "\"partitions\":[\"dt=2026-10-13\"]}"));
        assertTrue((Long) compact.get("snapshotAfter") > (Long) compact.get("snapshotBefore"));
        assertEquals(
                ", partitions => 'dt=a;dt=b'",
                TaskJob.partitions(JSON.readTree("{\"partitions\":[\"dt=a\",\"dt=b\"]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> TaskJob.partitions(JSON.readTree("{\"partitions\":[\"dt='x'\"]}")));

        tEnv.executeSql("CREATE DATABASE dwd").await();
        tEnv.executeSql(
                        "CREATE TABLE ods.visits (`day` DATE, `at` TIMESTAMP(3), ok BOOLEAN, n BIGINT,"
                                + " PRIMARY KEY (`day`, `at`, ok) NOT ENFORCED) WITH ('bucket' = '1')")
                .await();
        tEnv.executeSql(
                        "CREATE TABLE dwd.visits (`day` DATE, `at` TIMESTAMP(3), ok BOOLEAN, n BIGINT,"
                                + " PRIMARY KEY (`day`, `at`, ok) NOT ENFORCED) WITH ('bucket' = '1',"
                                + " 'changelog-producer' = 'lookup')")
                .await();
        tEnv.executeSql(
                        "INSERT INTO ods.visits VALUES (DATE '2026-10-14', TIMESTAMP '2026-10-14"
                                + " 08:00:00.250', TRUE, 1)")
                .await();
        tEnv.executeSql(
                        "INSERT INTO dwd.visits VALUES (DATE '2026-10-14', TIMESTAMP '2026-10-14"
                                + " 08:00:00.250', TRUE, 9), (DATE '2026-10-13', TIMESTAMP"
                                + " '2026-10-13 09:30:00', FALSE, 5)")
                .await();
        Map<String, Object> backfill =
                TaskJob.run(
                        tEnv,
                        catalog,
                        JSON.readTree(
                                "{\"type\":\"backfill\",\"database\":\"dwd\",\"table\":\"visits\","
                                        + "\"primaryKey\":[\"day\",\"at\",\"ok\"],\"query\":"
                                        + "\"SELECT * FROM ods.visits\"}"));
        assertEquals("upserted 1 rows, deleted 1 no longer derived", backfill.get("detail"));
        assertEquals(
                List.of("+I[2026-10-14, 2026-10-14T08:00:00.250, true, 1]"),
                rows(tEnv, "SELECT * FROM dwd.visits"));
    }
}
