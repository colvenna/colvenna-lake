package com.colvenna.lake.jobs.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.Timestamp;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.options.Options;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.types.DataTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErasureProbeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path warehouse;
    @TempDir Path dir;

    @Test
    void theIcebergManifestsATableKeepsNameTheSubjectUntilTheErasuresCompaction() throws Exception {
        TableEnvironment tEnv = TableEnvironment.create(EnvironmentSettings.inBatchMode());
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        Catalog catalog = ColvennaCatalog.register(tEnv, options);
        tEnv.executeSql("CREATE DATABASE ods").await();
        tEnv.executeSql(
                        "CREATE TABLE ods.orders (id BIGINT, customer_id BIGINT, PRIMARY KEY (id) NOT"
                                + " ENFORCED) WITH ('bucket' = '1',"
                                + " 'metadata.iceberg.storage' = 'table-location',"
                                + " 'metadata.iceberg.previous-versions-max' = '30')")
                .await();
        tEnv.executeSql("INSERT INTO ods.orders VALUES (1, 42), (2, 7), (3, 42)").await();
        Path values = dir.resolve("values");
        Files.writeString(values, "42\n");
        Set<List<String>> subject = ErasureProbe.subject(values);
        FileStoreTable t = (FileStoreTable) catalog.getTable(Identifier.create("ods", "orders"));
        assertEquals(
                true,
                (Long)
                                ErasureProbe.probe(
                                                t,
                                                "ods.orders",
                                                List.of("customer_id"),
                                                subject,
                                                null,
                                                null)
                                        .get("icebergMentions")
                        > 0);
        tEnv.executeSql("DELETE FROM ods.orders WHERE customer_id = 42").await();
        long clean = t.snapshotManager().latestSnapshotId();
        // A full compaction that keeps the Iceberg metadata chain: the files are clean, the
        // manifests, Paimon's and Iceberg's, still name the rewritten ones.
        TaskJob.run(
                tEnv,
                catalog,
                JSON.readTree(
                        "{\"type\":\"compact\",\"database\":\"ods\",\"table\":\"orders\","
                                + "\"full\":true}"));
        t = (FileStoreTable) catalog.getTable(Identifier.create("ods", "orders"));
        Map<String, Object> chained =
                ErasureProbe.probe(t, "ods.orders", List.of("customer_id"), subject, clean, null);
        assertEquals(0L, chained.get("records"));
        assertEquals(true, (Long) chained.get("mentions") > 0);
        assertEquals(true, (Long) chained.get("icebergMentions") > 0);
        // The erasure's: both rewritten, the Iceberg metadata anew.
        TaskJob.run(
                tEnv,
                catalog,
                JSON.readTree(
                        "{\"type\":\"compact\",\"database\":\"ods\",\"table\":\"orders\","
                                + "\"full\":true,\"manifests\":true}"));
        t = (FileStoreTable) catalog.getTable(Identifier.create("ods", "orders"));
        Map<String, Object> erased =
                ErasureProbe.probe(t, "ods.orders", List.of("customer_id"), subject, clean, null);
        assertEquals(0L, erased.get("mentions"));
        assertEquals(0L, erased.get("icebergMentions"));
    }

    @Test
    void rowsGoWithTheDeleteRecordsGoOnlyWithAFullCompaction() throws Exception {
        TableEnvironment tEnv = TableEnvironment.create(EnvironmentSettings.inBatchMode());
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        Catalog catalog = ColvennaCatalog.register(tEnv, options);
        tEnv.executeSql("CREATE DATABASE ods").await();
        tEnv.executeSql(
                        "CREATE TABLE ods.orders (id BIGINT, customer_id BIGINT, PRIMARY KEY (id) NOT"
                                + " ENFORCED) WITH ('bucket' = '1', 'write-only' = 'true')")
                .await();
        tEnv.executeSql("INSERT INTO ods.orders VALUES (1, 42), (2, 7), (3, 42)").await();
        Path values = dir.resolve("values");
        Files.writeString(values, "42\n\n");
        Set<List<String>> subject = ErasureProbe.subject(values);
        FileStoreTable t = (FileStoreTable) catalog.getTable(Identifier.create("ods", "orders"));
        Map<String, Object> before =
                ErasureProbe.probe(t, "ods.orders", List.of("customer_id"), subject, null, null);
        assertEquals(2L, before.get("rows"));
        assertEquals(2L, before.get("records"));

        tEnv.executeSql("DELETE FROM ods.orders WHERE customer_id = 42").await();
        t = (FileStoreTable) catalog.getTable(Identifier.create("ods", "orders"));
        long clean = t.snapshotManager().latestSnapshotId();
        Map<String, Object> deleted =
                ErasureProbe.probe(t, "ods.orders", List.of("customer_id"), subject, clean, null);
        // A reader no longer sees them, but the files still hold them and their deletes, and a
        // streaming reader from the clean snapshot reads the deletes.
        assertEquals(0L, deleted.get("rows"));
        assertEquals(true, (Long) deleted.get("records") > 0);
        assertEquals(true, (Long) deleted.get("changes") > 0);
        // The deletes are the erasure's own: nothing of the subject's is added back.
        assertEquals(0L, deleted.get("added"));

        tEnv.getConfig().set("table.dml-sync", "true");
        tEnv.executeSql("CALL sys.compact(`table` => 'ods.orders', compact_strategy => 'full')")
                .await();
        t = (FileStoreTable) catalog.getTable(Identifier.create("ods", "orders"));
        Map<String, Object> compacted =
                ErasureProbe.probe(t, "ods.orders", List.of("customer_id"), subject, clean, null);
        assertEquals(0L, compacted.get("rows"));
        assertEquals(0L, compacted.get("records"));
        // The manifests still list the rewritten files, whose statistics name 42.
        assertEquals(true, (Long) compacted.get("mentions") > 0);
        assertEquals(t.snapshotManager().latestSnapshotId(), compacted.get("snapshot"));
        try (var commit = t.newBatchWriteBuilder().newCommit()) {
            commit.compactManifests();
        }
        t = (FileStoreTable) catalog.getTable(Identifier.create("ods", "orders"));
        assertEquals(
                0L,
                ErasureProbe.probe(t, "ods.orders", List.of("customer_id"), subject, clean, null)
                        .get("mentions"));
        // The clean snapshot's changes stay readable until it expires.
        assertEquals(true, (Long) compacted.get("changes") > 0);
        tEnv.executeSql(
                        "CALL sys.expire_snapshots(`table` => 'ods.orders', retain_min => 1, retain_max => 1)")
                .await();
        t = (FileStoreTable) catalog.getTable(Identifier.create("ods", "orders"));
        Map<String, Object> expired =
                ErasureProbe.probe(t, "ods.orders", List.of("customer_id"), subject, clean, null);
        assertEquals(0L, expired.get("changes"));

        // The subject comes back after the clean snapshot.
        tEnv.executeSql("INSERT INTO ods.orders VALUES (5, 42)").await();
        t = (FileStoreTable) catalog.getTable(Identifier.create("ods", "orders"));
        Map<String, Object> back =
                ErasureProbe.probe(t, "ods.orders", List.of("customer_id"), subject, clean, null);
        assertEquals(1L, back.get("rows"));
        assertEquals(1L, back.get("added"));
    }

    @Test
    void aDeletionVectorMasksARecordTheFileStillHolds() throws Exception {
        TableEnvironment tEnv = TableEnvironment.create(EnvironmentSettings.inBatchMode());
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        Catalog catalog = ColvennaCatalog.register(tEnv, options);
        tEnv.getConfig().set("table.dml-sync", "true");
        tEnv.executeSql("CREATE DATABASE dwd").await();
        tEnv.executeSql(
                        "CREATE TABLE dwd.orders (id BIGINT, customer_id DECIMAL(10, 0), PRIMARY KEY"
                                + " (id) NOT ENFORCED) WITH ('bucket' = '1',"
                                + " 'deletion-vectors.enabled' = 'true')")
                .await();
        tEnv.executeSql("INSERT INTO dwd.orders VALUES (1, 42), (2, 7), (3, 42)").await();
        tEnv.executeSql("CALL sys.compact(`table` => 'dwd.orders', compact_strategy => 'full')")
                .await();
        tEnv.executeSql("INSERT INTO dwd.orders VALUES (1, 8)").await();
        Path values = dir.resolve("values");
        // Numbers compare by value: 42.0 is the 42 the DECIMAL column holds.
        Files.writeString(values, "42.0\n");
        Set<List<String>> subject = ErasureProbe.subject(values);
        FileStoreTable t = (FileStoreTable) catalog.getTable(Identifier.create("dwd", "orders"));
        Map<String, Object> probed =
                ErasureProbe.probe(t, "dwd.orders", List.of("customer_id"), subject, null, null);
        assertEquals(true, t.snapshotManager().latestSnapshot() != null);
        assertEquals(true, probed.get("fits"));
        assertEquals(1L, probed.get("rows"));
        // Order 1's old row with 42 is masked by a deletion vector, still in its file.
        assertEquals(true, (Long) probed.get("records") >= 2);

        Files.writeString(values, "forty-two\n");
        Map<String, Object> unfit =
                ErasureProbe.probe(
                        t,
                        "dwd.orders",
                        List.of("customer_id"),
                        ErasureProbe.subject(values),
                        null,
                        null);
        assertEquals(false, unfit.get("fits"));
    }

    @Test
    void keysCompareAsTheSourceDoes() {
        assertEquals("42", ErasureProbe.Key.text(" 42.00 ", DataTypes.DECIMAL(10, 2)));
        assertEquals("ann@x.org", ErasureProbe.Key.text("Ann@X.org ", DataTypes.STRING()));
        assertEquals(
                "2026-01-02",
                ErasureProbe.Key.internal(
                        (int) LocalDate.parse("2026-01-02").toEpochDay(), DataTypes.DATE()));
        assertEquals("2026-01-02", ErasureProbe.Key.text("2026-01-02", DataTypes.DATE()));
        assertEquals(
                ErasureProbe.Key.text("2026-01-02 03:04:05", DataTypes.TIMESTAMP(3)),
                ErasureProbe.Key.internal(
                        Timestamp.fromLocalDateTime(LocalDateTime.parse("2026-01-02T03:04:05")),
                        DataTypes.TIMESTAMP(3)));
        assertThrows(RuntimeException.class, () -> ErasureProbe.Key.text("x", DataTypes.BOOLEAN()));
        assertThrows(RuntimeException.class, () -> ErasureProbe.Key.text("x", DataTypes.DATE()));
    }

    @Test
    void aLongTextKeyIsFoundInStatisticsCutTo16Characters() throws Exception {
        TableEnvironment tEnv = TableEnvironment.create(EnvironmentSettings.inBatchMode());
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        Catalog catalog = ColvennaCatalog.register(tEnv, options);
        tEnv.executeSql("CREATE DATABASE crm").await();
        tEnv.executeSql(
                        "CREATE TABLE crm.contacts (id BIGINT, email STRING, PRIMARY KEY (id) NOT"
                                + " ENFORCED) WITH ('bucket' = '1')")
                .await();
        tEnv.executeSql("INSERT INTO crm.contacts VALUES (1, 'someone.long@example.org')").await();
        Path values = dir.resolve("values");
        Files.writeString(values, "Someone.Long@Example.org\n");
        FileStoreTable t = (FileStoreTable) catalog.getTable(Identifier.create("crm", "contacts"));
        Map<String, Object> probed =
                ErasureProbe.probe(
                        t,
                        "crm.contacts",
                        List.of("email"),
                        ErasureProbe.subject(values),
                        null,
                        null);
        // Text compares without case; the file's statistics hold only its first 16 characters.
        assertEquals(1L, probed.get("rows"));
        assertEquals(true, (Long) probed.get("mentions") > 0);

        // A column's own stats-mode cuts it shorter.
        tEnv.executeSql(
                        "CREATE TABLE crm.leads (id BIGINT, email STRING, PRIMARY KEY (id) NOT"
                                + " ENFORCED) WITH ('bucket' = '1', 'fields.email.stats-mode' ="
                                + " 'TRUNCATE(8)')")
                .await();
        tEnv.executeSql("INSERT INTO crm.leads VALUES (1, 'someone.long@example.org')").await();
        t = (FileStoreTable) catalog.getTable(Identifier.create("crm", "leads"));
        probed =
                ErasureProbe.probe(
                        t, "crm.leads", List.of("email"), ErasureProbe.subject(values), null, null);
        assertEquals(true, (Long) probed.get("mentions") > 0);
    }

    @Test
    void aResultTooLongForATerminationMessageDropsItsListsFirst() throws Exception {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("table", "ods.orders");
        Map<String, Long> tags = new TreeMap<>();
        for (int i = 0; i < 200; i++) {
            tags.put("tag-with-a-long-name-" + i, (long) i);
        }
        table.put("tags", tags);
        table.put("tagsMore", 3);
        table.put("consumers", Map.of());
        table.put("consumersMore", 0);
        table.put("branches", Map.of());
        table.put("branchesMore", 0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", List.of(table));
        out.put("state", List.of());
        String json = ErasureProbe.fitted(out);
        assertEquals(true, json.length() <= ErasureProbe.RESULT_BYTES);
        assertEquals(true, json.contains("\"tagsMore\":203"));
    }

    @Test
    void aResultThatStillDoesNotFitSaysSoAndBytesAreCounted() throws Exception {
        Map<String, Object> orphans = new LinkedHashMap<>();
        orphans.put("left", List.of("é".repeat(1000), "é".repeat(1000)));
        orphans.put("leftCount", 2);
        orphans.put("disposed", List.of());
        orphans.put("disposedCount", 0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", List.of());
        out.put("state", List.of());
        out.put("orphans", orphans);
        // 2000 characters, 4000 bytes: the orphans' list goes, its count stays.
        String json = ErasureProbe.fitted(out);
        assertEquals(
                true, json.getBytes(StandardCharsets.UTF_8).length <= ErasureProbe.RESULT_BYTES);
        assertEquals(true, json.contains("\"leftCount\":2"));

        List<Object> tables = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tables.add(Map.of("table", "ods.table_with_a_long_name_" + i, "missing", true));
        }
        out.put("tables", tables);
        assertEquals("{\"error\":\"result too long\"}", ErasureProbe.fitted(out));
    }

    @Test
    void stateOfOwnersThatAreGoneIsDisposedOf() throws Exception {
        Path root = dir.resolve("ns");
        FileTime old = FileTime.from(Instant.parse("2026-10-01T00:00:00Z"));
        for (String f :
                List.of(
                        "orders-wide-aaaaaaaa/checkpoints/j/chk-1/_metadata",
                        "orders-wide-bbbbbbbb/checkpoints/j/chk-2/_metadata",
                        "shop-cdc-cccccccc/ha/completedCheckpoint1",
                        "shop-cdc-dddddddd/checkpoints/j/chk-3/_metadata")) {
            Path file = root.resolve(f);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "x");
            if (!f.startsWith("shop-cdc-dddddddd")) {
                Files.setLastModifiedTime(file, old);
            }
        }
        Map<String, Object> out =
                ErasureProbe.orphans(
                        LocalFileIO.create(),
                        new org.apache.paimon.fs.Path(root.toUri()),
                        Set.of("orders-wide-aaaaaaaa"),
                        Instant.parse("2026-10-10T00:00:00Z").toEpochMilli());
        assertEquals(List.of("orders-wide-bbbbbbbb", "shop-cdc-cccccccc"), out.get("disposed"));
        // Written past the clean snapshots: not disposed of, and named.
        assertEquals(List.of("shop-cdc-dddddddd"), out.get("left"));
        assertEquals(true, Files.exists(root.resolve("orders-wide-aaaaaaaa")));
    }

    @Test
    void stateNoRestoreNeedsIsDisposedOfTheRunningJobsIsLeftToFlink() throws Exception {
        Path state = dir.resolve("state");
        FileTime old = FileTime.from(Instant.parse("2026-10-01T00:00:00Z"));
        for (String f :
                List.of(
                        "checkpoints/cancelled/chk-4/_metadata",
                        "checkpoints/running/chk-9/_metadata",
                        "checkpoints/previous/chk-7/_metadata",
                        "checkpoints/previous/chk-8/_metadata",
                        "savepoints/savepoint-a/_metadata",
                        "savepoints/savepoint-b/_metadata",
                        "savepoints/savepoint-c/_metadata")) {
            Path file = state.resolve(f);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "x");
            if (!f.contains("savepoint-c") && !f.contains("chk-8")) {
                Files.setLastModifiedTime(file, old);
            }
        }
        org.apache.paimon.fs.Path location = new org.apache.paimon.fs.Path(state.toUri());
        Map<String, Object> out =
                ErasureProbe.state(
                        LocalFileIO.create(),
                        "shop-cdc",
                        location,
                        Instant.parse("2026-10-10T00:00:00Z").toEpochMilli(),
                        "running",
                        false,
                        Set.of(
                                new org.apache.paimon.fs.Path(location, "savepoints/savepoint-b")
                                        .toString()));
        assertEquals(
                List.of("checkpoints/cancelled", "savepoints/savepoint-a"), out.get("disposed"));
        // A run that checkpointed past the clean snapshots: kept while the job does not run.
        assertEquals(
                List.of(
                        "checkpoints/previous",
                        "checkpoints/running",
                        "savepoints/savepoint-b (a restore needs it)"),
                out.get("left"));
        assertEquals(3L, out.get("older"));
        assertEquals(5L, out.get("objects"));
        assertEquals(false, Files.exists(state.resolve("checkpoints/cancelled")));
        out =
                ErasureProbe.state(
                        LocalFileIO.create(),
                        "shop-cdc",
                        location,
                        Instant.parse("2026-10-10T00:00:00Z").toEpochMilli(),
                        "running",
                        true,
                        Set.of());
        assertEquals(
                List.of("checkpoints/previous", "savepoints/savepoint-b"), out.get("disposed"));
        assertEquals(List.of("checkpoints/running"), out.get("left"));
    }
}
