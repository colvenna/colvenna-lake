package com.colvenna.lake.acceptance.flow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;

/**
 * The Flink side of the catalog acceptance: the catalog operations that the Flink flows (CDC ODS
 * tables, append event tables, consumer-id layering, maintenance procedures, batch correction) rely
 * on, run through colvenna-catalog with per-table data tokens. Every step checks its result and
 * prints one line {@code STEP <name> OK|FAIL <detail>}; the first failure exits 1.
 *
 * <p>Environment: {@code COLVENNA_CATALOG_URI}, {@code COLVENNA_CATALOG_TOKEN_FILE}. Argument: the
 * run's database name. The tables are left in place for the Spark and Doris flows to read.
 */
public final class FlinkCatalogFlow {

    private final String db;
    private final TableEnvironment batch;
    private final List<String> lines = new ArrayList<>();

    private FlinkCatalogFlow(String db, TableEnvironment batch) {
        this.db = db;
        this.batch = batch;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: FlinkCatalogFlow <database>");
        }
        String db = args[0];
        String ddl = catalogDdl();
        TableEnvironment batch = TableEnvironment.create(EnvironmentSettings.inBatchMode());
        batch.getConfig().set("table.dml-sync", "true");
        batch.executeSql(ddl);
        FlinkCatalogFlow flow = new FlinkCatalogFlow(db, batch);
        try {
            flow.run(ddl);
        } finally {
            Files.write(Path.of("/tmp/flow-result.txt"), flow.lines, StandardCharsets.UTF_8);
        }
    }

    /** colvenna-catalog with a bearer token and data tokens, as every flow reaches it. */
    static Map<String, String> catalogOptions() throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("metastore", "rest");
        options.put("uri", System.getenv("COLVENNA_CATALOG_URI"));
        options.put("warehouse", "colvenna");
        options.put("token.provider", "bear");
        options.put(
                "token",
                Files.readString(
                                Path.of(System.getenv("COLVENNA_CATALOG_TOKEN_FILE")),
                                StandardCharsets.UTF_8)
                        .trim());
        options.put("data-token.enabled", "true");
        return options;
    }

    static String catalogDdl() throws Exception {
        StringBuilder ddl = new StringBuilder("CREATE CATALOG colvenna WITH ('type'='paimon'");
        catalogOptions()
                .forEach((k, v) -> ddl.append(", '").append(k).append("'='").append(v).append("'"));
        return ddl.append(")").toString();
    }

    private void run(String ddl) throws Exception {
        String orders = "colvenna." + db + ".orders";
        String events = "colvenna." + db + ".events";
        step("create-database", () -> sql("CREATE DATABASE IF NOT EXISTS colvenna." + db), "");
        step(
                "create-primary-key-table",
                () ->
                        sql(
                                "CREATE TABLE "
                                        + orders
                                        + " (id BIGINT, amount INT, status STRING, PRIMARY KEY (id) NOT"
                                        + " ENFORCED) WITH ('bucket'='2',"
                                        + " 'changelog-producer'='input', 'write-only'='true')"),
                // Not deletion vectors: their readers skip level-0 files, so a write-only table
                // would read empty until the compaction step. Spark's spark_sales covers DV.
                "input changelog, write-only, merge on read");
        step(
                "create-append-table",
                () ->
                        sql(
                                "CREATE TABLE "
                                        + events
                                        + " (dt STRING, id BIGINT, payload STRING) PARTITIONED BY (dt)"
                                        + " WITH ('bucket'='-1', 'metastore.partitioned-table'='true',"
                                        + " 'write-only'='true')"),
                "unaware-bucket, catalog-managed partitions");
        step(
                "batch-insert",
                () -> {
                    StringBuilder values = new StringBuilder();
                    for (int i = 1; i <= 100; i++) {
                        values.append(i == 1 ? "" : ", ")
                                .append("(")
                                .append(i)
                                .append(", ")
                                .append(i)
                                .append(", 'new')");
                    }
                    sql("INSERT INTO " + orders + " VALUES " + values);
                    expect(100, count(orders));
                },
                "100 rows");
        step(
                "upsert-update-delete",
                () -> {
                    sql("INSERT INTO " + orders + " VALUES (1, 1000, 'paid'), (2, 2000, 'paid')");
                    sql("UPDATE " + orders + " SET status = 'shipped' WHERE id = 3");
                    sql("DELETE FROM " + orders + " WHERE id >= 96");
                    expect(95, count(orders));
                    expect(1000, scalar("SELECT amount FROM " + orders + " WHERE id = 1"));
                    expect(1, count(orders + " WHERE status = 'shipped'"));
                },
                "upsert 2, update 1, delete 5");
        step(
                "alter-add-column",
                () -> {
                    sql("ALTER TABLE " + orders + " ADD note STRING");
                    sql("INSERT INTO " + orders + " VALUES (200, 7, 'new', 'evolved')");
                    expect(1, count(orders + " WHERE note = 'evolved'"));
                    expect(96, count(orders));
                },
                "schema evolution through alterTable");
        step(
                "streaming-append-commits",
                this::streamingAppend,
                "checkpointed writes, writer parallelism 1");
        step(
                "catalog-partitions",
                () -> expect(3, count("colvenna." + db + ".`events$partitions`")),
                "3 partitions registered");
        step("consumer-id-stream-read", () -> consumerRead(ddl), "layered read with consumer");
        step(
                "tag-create-and-read",
                () -> {
                    sql(
                            "CALL colvenna.sys.create_tag(`table` => '"
                                    + db
                                    + ".orders', tag => 'flow_tag')");
                    expect(
                            1,
                            count("colvenna." + db + ".`orders$tags` WHERE tag_name = 'flow_tag'"));
                    sql(
                            "INSERT INTO "
                                    + orders
                                    + " VALUES (300, 3, 'after-tag', CAST(NULL AS STRING))");
                    expect(96, count(orders + " /*+ OPTIONS('scan.tag-name'='flow_tag') */"));
                    expect(97, count(orders));
                },
                "tag pins the pre-insert snapshot");
        step(
                "time-travel-by-snapshot",
                () -> expect(100, count(orders + " /*+ OPTIONS('scan.snapshot-id'='1') */")),
                "snapshot 1 holds the first insert");
        step(
                "branch-write-and-read",
                () -> {
                    sql(
                            // Positional: create_branch is overloaded, and Flink refuses named
                            // arguments then.
                            "CALL colvenna.sys.create_branch('"
                                    + db
                                    + ".orders', 'flow_branch', 'flow_tag')");
                    sql(
                            "INSERT INTO colvenna."
                                    + db
                                    + ".`orders$branch_flow_branch` VALUES (400, 4, 'branch', CAST(NULL AS STRING))");
                    expect(97, count("colvenna." + db + ".`orders$branch_flow_branch`"));
                    expect(97, count(orders));
                    expect(0, count(orders + " WHERE id = 400"));
                },
                "branch from tag, isolated writes");
        step(
                "compact-procedure",
                () -> {
                    sql("CALL colvenna.sys.compact(`table` => '" + db + ".orders')");
                    expect(
                            1,
                            count(
                                    "colvenna."
                                            + db
                                            + ".`orders$snapshots` WHERE commit_kind = 'COMPACT'"));
                    expect(97, count(orders));
                },
                "dedicated compaction commit");
        step(
                "expire-snapshots",
                () -> {
                    sql(
                            "CALL colvenna.sys.expire_snapshots(`table` => '"
                                    + db
                                    + ".events', retain_min => 1, retain_max => 2)");
                    expect(2, count("colvenna." + db + ".`events$snapshots`"));
                    expect(3000, count(events));
                },
                "retain 2");
        step("rollback-on-scratch-table", this::rollback, "rollback to snapshot 1");
        step(
                "view",
                () -> {
                    sql(
                            "CREATE VIEW colvenna."
                                    + db
                                    + ".big_orders AS SELECT id, amount FROM "
                                    + orders
                                    + " WHERE amount >= 1000");
                    expect(2, count("colvenna." + db + ".big_orders"));
                },
                "catalog view");
        step(
                "catalog-function",
                () -> {
                    String fn = "colvenna." + db + ".twice";
                    sql(
                            "CREATE FUNCTION "
                                    + fn
                                    + " AS '"
                                    + Twice.class.getName()
                                    + "' LANGUAGE JAVA");
                    expect(
                            2000,
                            scalar(
                                    "SELECT "
                                            + fn
                                            + "(CAST(amount AS BIGINT)) FROM "
                                            + orders
                                            + " WHERE id = 1"));
                    List<String> functions = new ArrayList<>();
                    try (CloseableIterator<Row> rows =
                            batch.executeSql("SHOW USER FUNCTIONS IN colvenna." + db).collect()) {
                        rows.forEachRemaining(r -> functions.add(String.valueOf(r.getField(0))));
                    }
                    if (!functions.contains("twice")) {
                        throw new AssertionError("function not listed: " + functions);
                    }
                    sql("DROP FUNCTION " + fn);
                },
                "create, call, list and drop a catalog function");
        step(
                "rename-and-drop",
                () -> {
                    sql("CREATE TABLE colvenna." + db + ".scratch2 (id INT)");
                    sql(
                            "ALTER TABLE colvenna."
                                    + db
                                    + ".scratch2 RENAME TO colvenna."
                                    + db
                                    + ".scratch3");
                    sql("DROP TABLE colvenna." + db + ".scratch3");
                    List<String> tables = new ArrayList<>();
                    try (CloseableIterator<Row> rows =
                            batch.executeSql("SHOW TABLES FROM colvenna." + db).collect()) {
                        rows.forEachRemaining(r -> tables.add(String.valueOf(r.getField(0))));
                    }
                    if (tables.stream().anyMatch(t -> t.startsWith("scratch"))) {
                        throw new AssertionError("scratch tables left: " + tables);
                    }
                },
                "metadata-only rename, purge on drop");
        System.out.println("FLOW PASS " + db);
    }

    private void streamingAppend() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(3_000);
        StreamTableEnvironment stream =
                StreamTableEnvironment.create(env, EnvironmentSettings.inStreamingMode());
        stream.executeSql(catalogDdl());
        stream.executeSql(
                "CREATE TEMPORARY TABLE gen (id BIGINT, payload STRING) WITH ('connector'='datagen',"
                        + " 'number-of-rows'='3000', 'rows-per-second'='300',"
                        + " 'fields.id.kind'='sequence', 'fields.id.start'='1',"
                        + " 'fields.id.end'='3000', 'fields.payload.length'='16')");
        stream.executeSql(
                        // One writer (#297): writing a bounded input in streaming mode, Paimon
                        // 2.0.0 can silently drop the end-of-input data of a writer subtask that
                        // finishes after another. The step checks that checkpointed writes commit
                        // several times through the catalog without losing rows; it no longer
                        // covers several writers' data in one commit.
                        "INSERT INTO colvenna."
                                + db
                                + ".events /*+ OPTIONS('sink.parallelism'='1') */ SELECT"
                                + " CONCAT('2026-10-1', CAST(MOD(id, 3) AS STRING)), id, payload"
                                + " FROM gen")
                .await();
        expect(3000, count("colvenna." + db + ".events"));
        long commits =
                scalar(
                        "SELECT count(*) FROM colvenna."
                                + db
                                + ".`events$snapshots` WHERE commit_kind = 'APPEND'");
        if (commits < 2) {
            throw new AssertionError("expected several checkpoint commits, got " + commits);
        }
    }

    private void consumerRead(String ddl) throws Exception {
        sql(
                "CREATE TABLE colvenna."
                        + db
                        + ".orders_dwd (id BIGINT, amount INT, status STRING, note STRING, PRIMARY"
                        + " KEY (id) NOT ENFORCED) WITH ('bucket'='2')");
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(3_000);
        StreamTableEnvironment stream =
                StreamTableEnvironment.create(env, EnvironmentSettings.inStreamingMode());
        stream.executeSql(ddl);
        TableResult job =
                stream.executeSql(
                        "INSERT INTO colvenna."
                                + db
                                + ".orders_dwd SELECT * FROM colvenna."
                                + db
                                + ".orders /*+ OPTIONS('consumer-id'='flow',"
                                + " 'consumer.expiration-time'='1 d') */");
        JobClient client = job.getJobClient().orElseThrow();
        try {
            Instant deadline = Instant.now().plus(Duration.ofMinutes(5));
            while (true) {
                long copied = count("colvenna." + db + ".orders_dwd");
                long consumers =
                        count("colvenna." + db + ".`orders$consumers` WHERE consumer_id = 'flow'");
                if (copied == count("colvenna." + db + ".orders") && consumers == 1) {
                    return;
                }
                JobStatus status = client.getJobStatus().get();
                if (status.isGloballyTerminalState() || Instant.now().isAfter(deadline)) {
                    throw new AssertionError(
                            "stream read stalled: copied=" + copied + " consumers=" + consumers);
                }
                Thread.sleep(3_000);
            }
        } finally {
            client.cancel().get();
        }
    }

    private void rollback() throws Exception {
        String scratch = "colvenna." + db + ".scratch";
        sql("CREATE TABLE " + scratch + " (id INT)");
        sql("INSERT INTO " + scratch + " VALUES (1)");
        sql("INSERT INTO " + scratch + " VALUES (2)");
        sql("INSERT INTO " + scratch + " VALUES (3)");
        sql("CALL colvenna.sys.rollback_to(`table` => '" + db + ".scratch', snapshot_id => 1)");
        expect(1, count(scratch));
        sql("INSERT INTO " + scratch + " VALUES (4)");
        expect(2, count(scratch));
        sql("DROP TABLE " + scratch);
    }

    // ---------------------------------------------------------------- helpers

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    private void step(String name, Body body, String detail) throws Exception {
        long start = System.nanoTime();
        try {
            body.run();
        } catch (Throwable e) {
            String line = "STEP " + name + " FAIL " + e;
            lines.add(line);
            System.out.println(line);
            e.printStackTrace(System.out);
            throw e instanceof Exception ? (Exception) e : new RuntimeException(e);
        }
        String line =
                String.format(
                        Locale.ROOT,
                        "STEP %s OK %dms %s",
                        name,
                        (System.nanoTime() - start) / 1_000_000,
                        detail);
        lines.add(line);
        System.out.println(line);
    }

    private void sql(String statement) throws Exception {
        batch.executeSql(statement).await();
    }

    private long count(String from) throws Exception {
        return scalar("SELECT count(*) FROM " + from);
    }

    private long scalar(String query) throws Exception {
        try (CloseableIterator<Row> rows = batch.executeSql(query).collect()) {
            if (!rows.hasNext()) {
                throw new AssertionError("no row for " + query);
            }
            Object value = rows.next().getField(0);
            return value == null ? 0 : ((Number) value).longValue();
        }
    }

    private static void expect(long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
