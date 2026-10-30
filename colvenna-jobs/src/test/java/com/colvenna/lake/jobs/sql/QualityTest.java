package com.colvenna.lake.jobs.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.types.RowKind;
import org.apache.flink.util.CloseableIterator;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.options.Options;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QualityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path warehouse;

    private static Quality rules() throws Exception {
        return Quality.parse(
                JSON.readTree(
                        "{\"quarantine\":\"orders_quarantine\",\"rules\":["
                                + "{\"column\":\"state\",\"kind\":\"notNull\"},"
                                + "{\"column\":\"state\",\"kind\":\"allowed\",\"values\":[\"new\",\"paid\"]},"
                                + "{\"column\":\"qty\",\"kind\":\"range\",\"min\":\"1\",\"max\":\"100\"}]}"));
    }

    @Test
    void checksAreConditionsOfTheColumnsTypes() throws Exception {
        RowType type =
                RowType.of(
                        new org.apache.paimon.types.DataType[] {
                            DataTypes.BIGINT(), DataTypes.STRING(), DataTypes.INT()
                        },
                        new String[] {"id", "state", "qty"});
        Quality q = rules();
        assertEquals(
                "`state` IS NOT NULL AND (`state` IS NULL OR CAST(`state` AS STRING) IN ('new',"
                        + " 'paid')) AND (`qty` IS NULL OR (`qty` >= 1 AND `qty` <= 100))",
                q.passes(type));
        assertEquals(
                "TIMESTAMP '2026-10-15 00:00:00.000000000'",
                Quality.literal(DataTypes.TIMESTAMP(3), "2026-10-15T00:00", "t"));
        assertThrows(
                IllegalArgumentException.class,
                () -> Quality.literal(DataTypes.STRING(), "a", "state"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        Quality.parse(
                                        JSON.readTree(
                                                "{\"quarantine\":\"q\",\"rules\":[{\"column\":\"nope\","
                                                        + "\"kind\":\"notNull\"}]}"))
                                .passes(type));
    }

    @Test
    void aRowGoesWhereItsLatestVersionBelongs() throws Exception {
        org.apache.flink.configuration.Configuration conf =
                new org.apache.flink.configuration.Configuration();
        // A failure fails the test, instead of restarting the job for ever.
        conf.setString("restart-strategy.type", "none");
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(conf);
        env.setParallelism(1);
        env.enableCheckpointing(200);
        StreamTableEnvironment tEnv =
                StreamTableEnvironment.create(env, EnvironmentSettings.inStreamingMode());
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        Catalog catalog = ColvennaCatalog.register(tEnv, options);
        tEnv.getConfig().set("table.exec.sink.upsert-materialize", "NONE");
        tEnv.executeSql("CREATE DATABASE dwd").await();
        tEnv.executeSql(
                        "CREATE TABLE dwd.orders (id BIGINT NOT NULL, state STRING, qty INT, PRIMARY"
                                + " KEY (id) NOT ENFORCED) WITH ('bucket' = '1')")
                .await();
        tEnv.executeSql(
                        "CREATE TABLE dwd.orders_quarantine (id BIGINT NOT NULL, state STRING, qty"
                                + " INT, colvenna_rule STRING, colvenna_flagged_at TIMESTAMP_LTZ(3), PRIMARY KEY"
                                + " (id) NOT ENFORCED) WITH ('bucket' = '1')")
                .await();
        // 1 passes, then fails (state); 2 fails (qty), then passes; 3 fails and is deleted; 4
        // passes.
        tEnv.createTemporaryView(
                "changes",
                tEnv.fromChangelogStream(
                        env.fromData(
                                        Row.ofKind(RowKind.INSERT, 1L, "new", 5),
                                        Row.ofKind(RowKind.INSERT, 2L, "paid", 500),
                                        Row.ofKind(RowKind.INSERT, 3L, null, 1),
                                        Row.ofKind(RowKind.INSERT, 4L, "paid", 2),
                                        Row.ofKind(RowKind.UPDATE_BEFORE, 1L, "new", 5),
                                        Row.ofKind(RowKind.UPDATE_AFTER, 1L, "lost", 5),
                                        Row.ofKind(RowKind.UPDATE_BEFORE, 2L, "paid", 500),
                                        Row.ofKind(RowKind.UPDATE_AFTER, 2L, "paid", 50),
                                        Row.ofKind(RowKind.DELETE, 3L, null, 1))
                                .returns(
                                        Types.ROW_NAMED(
                                                new String[] {"id", "state", "qty"},
                                                Types.LONG,
                                                Types.STRING,
                                                Types.INT))));
        SqlSpec spec =
                SqlSpec.parse(
                        "{\"targetDatabase\":\"dwd\",\"targetTable\":\"orders\",\"query\":\"SELECT"
                                + " id, state, qty FROM changes\",\"primaryKey\":[\"id\"],"
                                + "\"upstream\":[\"ods.orders\"],\"streams\":[],\"quality\":"
                                + JSON.writeValueAsString(
                                        JSON.readTree(
                                                "{\"quarantine\":\"orders_quarantine\",\"rules\":["
                                                        + "{\"column\":\"state\",\"kind\":\"notNull\"},"
                                                        + "{\"column\":\"state\",\"kind\":\"allowed\","
                                                        + "\"values\":[\"new\",\"paid\"]},"
                                                        + "{\"column\":\"qty\",\"kind\":\"range\","
                                                        + "\"min\":\"1\",\"max\":\"100\"}]}"))
                                + "}");
        FileStoreTable target =
                (FileStoreTable) catalog.getTable(Identifier.create("dwd", "orders"));
        tEnv.executeSql(spec.statement(target.rowType())).await();

        TableEnvironment batch = TableEnvironment.create(EnvironmentSettings.inBatchMode());
        ColvennaCatalog.register(batch, options);
        assertEquals(
                List.of("[2, paid, 50]", "[4, paid, 2]"), rows(batch, "SELECT * FROM dwd.orders"));
        assertEquals(
                List.of("[1, lost, 5, allowed(state)]"),
                rows(batch, "SELECT id, state, qty, colvenna_rule FROM dwd.orders_quarantine"));
    }

    private static List<String> rows(TableEnvironment tEnv, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (CloseableIterator<Row> it = tEnv.executeSql(sql).collect()) {
            // The rows as a reader sees them, whatever kind of change last wrote each.
            it.forEachRemaining(r -> out.add(r.toString().substring(2)));
        }
        out.sort(null);
        return out;
    }
}
