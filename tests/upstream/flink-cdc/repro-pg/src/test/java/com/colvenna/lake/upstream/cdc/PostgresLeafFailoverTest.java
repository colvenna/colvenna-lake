package com.colvenna.lake.upstream.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.core.execution.SavepointFormatType;
import org.apache.flink.runtime.jobgraph.SavepointConfigOptions;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.Collector;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.flink.action.cdc.CdcSourceRecord;
import org.apache.paimon.flink.action.cdc.postgres.PostgresSyncTableAction;
import org.apache.paimon.flink.sink.cdc.RichCdcMultiplexRecord;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.source.ReadBuilder;
import org.junit.jupiter.api.Test;

/**
 * FLINK-40741's question for PostgreSQL, as colvenna-jobs reads it: Paimon's single-table action
 * over a partitioned table's leaves (PostgresCdcJob). A leaf added later changes the job's table
 * list, and the operator restarts the job from its last state, so Flink CDC's incremental framework
 * (flink-cdc-base, which 3.7 did not change) snapshots the new leaf on restore. A failover at the
 * new leaf's first change from the slot, then: are its later changes kept? The source runs in
 * Docker (run-repro.sh); SQL goes through psql in it, so no second PostgreSQL driver joins the one
 * the connector bundles.
 */
class PostgresLeafFailoverTest {
    static final String HOST = System.getProperty("pg.host", "127.0.0.1");
    static final int PORT = Integer.parseInt(System.getProperty("pg.port", "5432"));
    static final String CONTAINER = System.getProperty("pg.container", "");

    /** Fails the job once, at the first change of orders_p2 read from the slot. */
    static final AtomicBoolean FAIL = new AtomicBoolean(false);

    static void psql(String sql) throws Exception {
        List<String> command =
                new ArrayList<>(
                        List.of(
                                "docker",
                                "exec",
                                CONTAINER,
                                "psql",
                                "-U",
                                "postgres",
                                "-d",
                                "shop",
                                "-v",
                                "ON_ERROR_STOP=1",
                                "-c",
                                sql));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("psql failed: " + out);
        }
    }

    static String rows(String table, int from, int to) {
        StringBuilder out = new StringBuilder("INSERT INTO " + table + " VALUES ");
        for (int i = from; i <= to; i++) {
            out.append(i > from ? ", " : "")
                    .append("(")
                    .append(i)
                    .append(", 'v")
                    .append(i)
                    .append("')");
        }
        return out.toString();
    }

    /** The action colvenna-jobs extends (PostgresCdcJob), with the failure put in its parser. */
    static final class Action extends PostgresSyncTableAction {
        Action(Map<String, String> catalog, Map<String, String> pg) {
            super("ods", "orders", catalog, pg);
        }

        @Override
        protected FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> recordParse() {
            FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> parse = super.recordParse();
            return new FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord>() {
                @Override
                public void flatMap(CdcSourceRecord r, Collector<RichCdcMultiplexRecord> out)
                        throws Exception {
                    String v = String.valueOf(r.getValue());
                    if (v.contains("\"table\":\"orders_p2\"")
                            && v.contains("\"op\":\"c\"")
                            && FAIL.compareAndSet(true, false)) {
                        throw new RuntimeException("injected failover at orders_p2's first change");
                    }
                    parse.flatMap(r, out);
                }
            };
        }
    }

    static JobClient run(Path warehouse, String leaves, String restore) throws Exception {
        Configuration conf = new Configuration();
        conf.set(RestartStrategyOptions.RESTART_STRATEGY, "fixed-delay");
        conf.set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_ATTEMPTS, 5);
        conf.set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_DELAY, Duration.ofSeconds(1));
        if (restore != null) {
            conf.set(SavepointConfigOptions.SAVEPOINT_PATH, restore);
        }
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(conf);
        env.setParallelism(1);
        env.enableCheckpointing(Long.parseLong(System.getProperty("checkpoint.ms", "1000")));
        Map<String, String> pg = new HashMap<>();
        pg.put("hostname", HOST);
        pg.put("port", Integer.toString(PORT));
        pg.put("username", "postgres");
        pg.put("password", "test");
        pg.put("database-name", "shop");
        pg.put("schema-name", "public");
        pg.put("table-name", leaves);
        pg.put("slot.name", "repro");
        pg.put("decoding.plugin.name", "pgoutput");
        pg.put("debezium.publication.name", "repro");
        pg.put("debezium.publication.autocreate.mode", "disabled");
        // colvenna-jobs' PostgresCdcJob leaves it unset (false); true snapshots a new leaf on
        // restore.
        pg.put("scan.newly-added-table.enabled", System.getProperty("pg.newly", "false"));
        Action action = new Action(Map.of("warehouse", warehouse.toUri().toString()), pg);
        action.withPrimaryKeys(List.of("id"));
        action.withTableConfig(Map.of("bucket", "1"));
        action.withStreamExecutionEnvironment(env);
        action.build();
        return env.executeAsync("repro " + leaves);
    }

    static String missing(TreeSet<Long> have, long from, long to) {
        StringBuilder out = new StringBuilder();
        for (long i = from; i <= to; i++) {
            if (!have.contains(i)) {
                out.append(out.length() > 0 ? "," : "").append(i);
            }
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    static TreeSet<Long> ids(Path warehouse, String table) throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        TreeSet<Long> out = new TreeSet<>();
        try (Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options))) {
            Table t;
            try {
                t = catalog.getTable(Identifier.create("ods", table));
            } catch (Catalog.TableNotExistException e) {
                return out;
            }
            ReadBuilder read = t.newReadBuilder();
            try (RecordReader<InternalRow> reader =
                    read.newRead().createReader(read.newScan().plan())) {
                reader.forEachRemaining(row -> out.add(row.getLong(0)));
            }
        }
        return out;
    }

    static boolean await(BooleanSupplier condition, int seconds) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(1000);
        }
        return condition.getAsBoolean();
    }

    static BooleanSupplier has(Path warehouse, String table, int n) {
        return () -> {
            try {
                return ids(warehouse, table).size() >= n;
            } catch (Exception e) {
                return false;
            }
        };
    }

    @Test
    void aLeafAddedOnRestoreKeepsItsChangesThroughAFailover() throws Exception {
        String version = System.getProperty("cdc.version");
        Path warehouse = Files.createTempDirectory("warehouse");
        Path savepoints = Files.createTempDirectory("savepoints");
        psql(
                "CREATE TABLE orders (id BIGINT PRIMARY KEY, v VARCHAR(16)) PARTITION BY RANGE (id);"
                        + " CREATE TABLE orders_p1 PARTITION OF orders FOR VALUES FROM (1) TO (101);"
                        + " ALTER TABLE orders_p1 REPLICA IDENTITY FULL;"
                        + " CREATE PUBLICATION repro FOR TABLE orders_p1; "
                        + rows("orders", 1, 20));
        JobClient first = run(warehouse, "orders_p1", null);
        assertEquals(true, await(has(warehouse, "orders", 20), 180), "orders_p1 snapshotted");
        // A new leaf and its rows while the job runs, as the operator finds it: the job's table
        // list has it only after the restart its new spec causes.
        psql(
                "CREATE TABLE orders_p2 PARTITION OF orders FOR VALUES FROM (101) TO (201);"
                        + " ALTER TABLE orders_p2 REPLICA IDENTITY FULL;"
                        + " ALTER PUBLICATION repro ADD TABLE orders_p2; "
                        + rows("orders", 101, 110));
        Thread.sleep(5_000);
        String savepoint =
                first.stopWithSavepoint(
                                false, savepoints.toUri().toString(), SavepointFormatType.CANONICAL)
                        .get();
        FAIL.set(true);
        JobClient second = run(warehouse, "orders_p1|orders_p2", savepoint);
        // Snapshotted (newly-added on) or not: either way the job runs on before new rows come.
        await(has(warehouse, "orders", 30), 60);
        psql(rows("orders", 111, 120));
        Thread.sleep(15_000);
        psql(rows("orders", 121, 130) + "; " + rows("orders", 21, 25));
        boolean all = await(has(warehouse, "orders", 55), 120);
        TreeSet<Long> ids = ids(warehouse, "orders");
        second.cancel().get();
        long leaf2 = ids.stream().filter(i -> i > 100).count();
        String line =
                String.format(
                        "RESULT source=postgres cdc=%s newly-added=%s checkpoint=%sms failed=%s"
                                + " p2=%d/30 p1=%d/25 p2-missing=%s",
                        version,
                        System.getProperty("pg.newly", "false"),
                        System.getProperty("checkpoint.ms", "1000"),
                        !FAIL.get(),
                        leaf2,
                        ids.size() - leaf2,
                        missing(ids, 101, 130));
        System.out.println(line);
        Files.writeString(Path.of("target", "result-" + version + ".txt"), line + "\n");
        assertEquals(true, all, line);
    }
}
