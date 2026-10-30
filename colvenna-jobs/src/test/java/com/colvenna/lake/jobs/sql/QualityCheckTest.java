package com.colvenna.lake.jobs.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.options.Options;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.table.FileStoreTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A check's rules on a pinned snapshot, its results in the store, and a repeat of it. */
class QualityCheckTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path warehouse;
    private TableEnvironment tEnv;
    private Catalog catalog;

    /** 2026-10-16T12:00:00Z, the asOf of the checks below. */
    private static final long AS_OF = 1792152000000L;

    @BeforeEach
    void tables() throws Exception {
        tEnv = TableEnvironment.create(EnvironmentSettings.inBatchMode());
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        catalog = ColvennaCatalog.register(tEnv, options);
        tEnv.executeSql("CREATE DATABASE ods").await();
        tEnv.executeSql("CREATE DATABASE quality_colvenna_data").await();
        // An event table as a Kafka source writes it.
        tEnv.executeSql(
                        "CREATE TABLE ods.clicks (event_id STRING, user_id BIGINT, event_time_ms"
                                + " BIGINT, _offset BIGINT, dt STRING) PARTITIONED BY (dt)")
                .await();
        // The store's results table, as the operator makes it (QualityStore.RESULT_FIELDS).
        tEnv.executeSql(
                        "CREATE TABLE quality_colvenna_data.results (task_uid STRING NOT NULL, rule"
                                + " STRING NOT NULL, task STRING, checked_table STRING NOT NULL,"
                                + " partitions"
                                + " STRING, snapshot_id BIGINT, snapshot_time TIMESTAMP(3), as_of"
                                + " TIMESTAMP(3), rule_version STRING, definition STRING, metric"
                                + " STRING, measured DOUBLE, threshold STRING, passed BOOLEAN,"
                                + " failing_rows BIGINT, checked_rows BIGINT, locate STRING,"
                                + " checked_at TIMESTAMP(3), PRIMARY KEY (checked_table, task_uid,"
                                + " rule) NOT ENFORCED) PARTITIONED BY (checked_table) WITH"
                                + " ('bucket' = '1')")
                .await();
    }

    private long latest() throws Exception {
        return ((FileStoreTable) catalog.getTable(Identifier.create("ods", "clicks")))
                .snapshotManager()
                .latestSnapshotId();
    }

    private JsonNode spec(String uid, long snapshot, List<String> partitions) throws Exception {
        return JSON.valueToTree(
                Map.ofEntries(
                        Map.entry("type", "check"),
                        Map.entry("database", "ods"),
                        Map.entry("table", "clicks"),
                        Map.entry("task", "check-" + uid),
                        Map.entry("taskUid", uid),
                        Map.entry("snapshot", snapshot),
                        Map.entry("asOfMillis", AS_OF),
                        Map.entry("partitions", partitions),
                        Map.entry("ruleVersion", "abc123def456"),
                        Map.entry("store", "quality_colvenna_data"),
                        Map.entry("results", "results"),
                        Map.entry(
                                "rules",
                                List.of(
                                        Map.of(
                                                "name",
                                                "user-present",
                                                "kind",
                                                "notNull",
                                                "column",
                                                "user_id",
                                                "minRatio",
                                                "0.75"),
                                        Map.of(
                                                "name",
                                                "event-unique",
                                                "kind",
                                                "unique",
                                                "columns",
                                                List.of("event_id")),
                                        Map.of("name", "volume", "kind", "rowCount", "min", 3),
                                        Map.of(
                                                "name",
                                                "fresh",
                                                "kind",
                                                "freshness",
                                                "column",
                                                "event_time_ms",
                                                "maxAgeMillis",
                                                3_600_000L)))));
    }

    private List<String> results(String uid) throws Exception {
        List<String> out = new ArrayList<>();
        try (CloseableIterator<Row> it =
                tEnv.executeSql(
                                "SELECT rule, passed, measured, failing_rows, checked_rows,"
                                        + " snapshot_id, partitions, metric FROM"
                                        + " quality_colvenna_data.results WHERE task_uid = '"
                                        + uid
                                        + "'")
                        .collect()) {
            it.forEachRemaining(r -> out.add(r.toString()));
        }
        out.sort(null);
        return out;
    }

    @Test
    void checksItsSnapshotAndWritesEachRule() throws Exception {
        // 2026-10-15: four events, one without a user, two with the same id.
        tEnv.executeSql(
                        "INSERT INTO ods.clicks VALUES ('a', 1, 1792065600000, 0, '2026-10-15'),"
                                + " ('b', CAST(NULL AS BIGINT), 1792065600000, 1, '2026-10-15'), ('c', 3,"
                                + " 1792065600000, 2, '2026-10-15'), ('c', 4, 1792065600000, 3,"
                                + " '2026-10-15')")
                .await();
        // 2026-10-16, 30 minutes before asOf: two events.
        tEnv.executeSql(
                        "INSERT INTO ods.clicks VALUES ('d', 5, 1792150200000, 4, '2026-10-16'),"
                                + " ('e', 6, 1792150200000, 5, '2026-10-16')")
                .await();
        long pinned = latest();
        // Written after the check's snapshot: not checked.
        tEnv.executeSql(
                        "INSERT INTO ods.clicks VALUES ('f', CAST(NULL AS BIGINT), 1792151000000, 6,"
                                + " '2026-10-16')")
                .await();

        Map<String, Object> day15 =
                QualityCheck.run(catalog, tEnv, spec("u1", pinned, List.of("dt=2026-10-15")));
        @SuppressWarnings("unchecked")
        Map<String, Object> verdict = (Map<String, Object>) day15.get("check");
        assertEquals("Failed", verdict.get("verdict"));
        assertEquals(List.of("event-unique"), verdict.get("failed"));
        assertEquals(4L, verdict.get("rows"));
        assertEquals(pinned, day15.get("snapshotBefore"));
        assertEquals(
                List.of(
                        "+I[event-unique, false, 1.0, 2, 4, "
                                + pinned
                                + ", dt=2026-10-15, duplicate_keys]",
                        // Freshness is the whole table's: the newest event, 30 min old.
                        "+I[fresh, true, 1800.0, null, null, "
                                + pinned
                                + ", dt=2026-10-15, age_seconds]",
                        "+I[user-present, true, 0.75, 1, 4, "
                                + pinned
                                + ", dt=2026-10-15, not_null_ratio]",
                        "+I[volume, true, 4.0, null, 4, " + pinned + ", dt=2026-10-15, row_count]"),
                results("u1"));

        // The 16th at the pinned snapshot: two rows, so volume fails; the later null is not seen.
        Map<String, Object> day16 =
                QualityCheck.run(catalog, tEnv, spec("u2", pinned, List.of("dt=2026-10-16")));
        assertTrue(day16.get("detail").toString().contains("failed [volume]"));
        assertTrue(
                results("u2")
                        .contains(
                                "+I[user-present, true, 1.0, 0, 2, "
                                        + pinned
                                        + ", dt=2026-10-16, not_null_ratio]"));

        // The same check again (a retry, or a repeat with the same inputs): the same rows.
        List<String> first = results("u1");
        QualityCheck.run(catalog, tEnv, spec("u1", pinned, List.of("dt=2026-10-15")));
        assertEquals(first, results("u1"));
    }

    @Test
    void theRowsAFailedRuleNamesAreListedByItsStatement() throws Exception {
        tEnv.executeSql(
                        "INSERT INTO ods.clicks VALUES ('a', CAST(NULL AS BIGINT), 1792150200000, 0,"
                                + " '2026-10-16'), ('a', 2, 1792150200000, 1, '2026-10-16')")
                .await();
        long pinned = latest();
        QualityCheck.run(catalog, tEnv, spec("u3", pinned, List.of()));
        List<String> located = new ArrayList<>();
        try (CloseableIterator<Row> it =
                tEnv.executeSql(
                                "SELECT rule, locate FROM quality_colvenna_data.results WHERE task_uid"
                                        + " = 'u3' AND rule IN ('user-present', 'event-unique')")
                        .collect()) {
            while (it.hasNext()) {
                Row r = it.next();
                List<String> rows = new ArrayList<>();
                try (CloseableIterator<Row> found =
                        tEnv.executeSql(r.getField(1).toString()).collect()) {
                    found.forEachRemaining(f -> rows.add(f.toString()));
                }
                located.add(r.getField(0) + " " + rows);
            }
        }
        located.sort(null);
        assertEquals(
                List.of(
                        "event-unique [+I[a, 2]]",
                        "user-present [+I[a, null, 1792150200000, 0, 2026-10-16]]"),
                located);
    }

    @Test
    void refusesASnapshotNoLongerKeptAndForeignPartitions() throws Exception {
        tEnv.executeSql("INSERT INTO ods.clicks VALUES ('a', 1, 1, 0, '2026-10-16')").await();
        assertThrows(
                IllegalStateException.class,
                () -> QualityCheck.run(catalog, tEnv, spec("u4", 99, List.of())));
        assertThrows(
                IllegalArgumentException.class,
                () -> QualityCheck.run(catalog, tEnv, spec("u4", latest(), List.of("user_id=1"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        QualityCheck.run(
                                catalog, tEnv, spec("u4", latest(), List.of("dt=x' OR '1'='1"))));
    }
}
