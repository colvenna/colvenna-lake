package com.colvenna.lake.upstream.cdc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.flink.cdc.cli.parser.YamlPipelineDefinitionParser;
import org.apache.flink.cdc.common.configuration.Configuration;
import org.apache.flink.cdc.composer.definition.PipelineDef;
import org.apache.flink.cdc.composer.flink.FlinkPipelineComposer;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.source.ReadBuilder;
import org.junit.jupiter.api.Test;

/**
 * Flink CDC 3.7's YAML pipeline, MySQL to Paimon, against colvenna-catalog's REST API with a
 * principal of its own (run-pipeline.sh makes it, MANAGE on one database only, and passes its token
 * in COLVENNA_PIPE_TOKEN). The JVM has no AWS credentials of its own: it reaches S3 only with the
 * data tokens colvenna-catalog vends. One pipeline routes into the granted database: snapshot,
 * insert, update, delete, an added column, all read back through the catalog; another into a
 * database it has no grant on, which must be refused.
 */
class PipelineSmokeTest {
    static final int PORT = Integer.parseInt(System.getProperty("mysql.port", "3306"));
    static final String RUN = System.getProperty("run", "y1");
    static final String URI = System.getenv("COLVENNA_PIPE_URI");
    static final String TOKEN = System.getenv("COLVENNA_PIPE_TOKEN");
    static final String GRANTED = "pipe_" + RUN;
    static final String REFUSED = "pipe_other_" + RUN;
    static final List<String> RESULTS = new ArrayList<>();

    static void sql(String... statements) throws Exception {
        try (Connection c =
                        DriverManager.getConnection(
                                "jdbc:mysql://127.0.0.1:" + PORT + "/shop", "root", "test");
                Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    static String yaml(String database) {
        return String.join(
                "\n",
                "source:",
                "  type: mysql",
                "  hostname: 127.0.0.1",
                "  port: " + PORT,
                "  username: root",
                "  password: test",
                "  tables: shop.orders",
                "  server-id: 5600-5604",
                "  server-time-zone: UTC",
                "sink:",
                "  type: paimon",
                "  catalog.properties.metastore: rest",
                "  catalog.properties.uri: " + URI,
                "  catalog.properties.warehouse: colvenna",
                "  catalog.properties.token.provider: bear",
                "  catalog.properties.token: " + TOKEN,
                "  catalog.properties.data-token.enabled: 'true'",
                "  table.properties.bucket: '1'",
                "  table.properties.changelog-producer: input",
                "route:",
                "  - source-table: shop.orders",
                "    sink-table: " + database + ".orders",
                "pipeline:",
                "  name: cdc37-" + database,
                "  parallelism: 1",
                "");
    }

    static JobClient start(String database) throws Exception {
        PipelineDef def =
                new YamlPipelineDefinitionParser().parse(yaml(database), new Configuration());
        org.apache.flink.configuration.Configuration conf =
                new org.apache.flink.configuration.Configuration();
        conf.set(RestartStrategyOptions.RESTART_STRATEGY, "none");
        // The composer adds the connectors' jars to the job; loaded child-first, their Paimon
        // classes would be other classes than the ones the job was built with.
        conf.setString("classloader.resolve-order", "parent-first");
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(conf);
        env.enableCheckpointing(2000);
        FlinkPipelineComposer.ofApplicationCluster(env).compose(def);
        return env.executeAsync("cdc37 pipeline into " + database);
    }

    static Catalog catalog() {
        Options options = new Options();
        options.set("metastore", "rest");
        options.set("uri", URI);
        options.set("warehouse", "colvenna");
        options.set("token.provider", "bear");
        options.set("token", TOKEN);
        options.set("data-token.enabled", "true");
        return CatalogFactory.createCatalog(CatalogContext.create(options));
    }

    /** id to "v|c" of the table's latest snapshot, read through the catalog. */
    static TreeMap<Long, String> rows(String database) throws Exception {
        TreeMap<Long, String> out = new TreeMap<>();
        try (Catalog catalog = catalog()) {
            Table t;
            try {
                t = catalog.getTable(Identifier.create(database, "orders"));
            } catch (Catalog.TableNotExistException e) {
                return out;
            }
            int columns = t.rowType().getFieldCount();
            ReadBuilder read = t.newReadBuilder();
            try (RecordReader<InternalRow> reader =
                    read.newRead().createReader(read.newScan().plan())) {
                reader.forEachRemaining(
                        row ->
                                out.put(
                                        row.getLong(0),
                                        row.getString(1)
                                                + "|"
                                                + (columns > 2 && !row.isNullAt(2)
                                                        ? row.getInt(2)
                                                        : "")));
            }
        }
        return out;
    }

    static boolean await(BooleanSupplier condition, int seconds) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
            } catch (RuntimeException e) {
                // Not there yet.
            }
            Thread.sleep(2000);
        }
        return condition.getAsBoolean();
    }

    static BooleanSupplier count(String database, int n) {
        return () -> {
            try {
                return rows(database).size() == n;
            } catch (Exception e) {
                return false;
            }
        };
    }

    static void result(String line) throws Exception {
        System.out.println(line);
        RESULTS.add(line);
        Files.write(Path.of("target", "pipeline-result.txt"), RESULTS);
    }

    @Test
    void intoTheGrantedDatabaseAndRefusedTheOther() throws Exception {
        assertTrue(URI != null && TOKEN != null, "COLVENNA_PIPE_URI and COLVENNA_PIPE_TOKEN");
        sql("CREATE TABLE orders (id BIGINT PRIMARY KEY, v VARCHAR(16))");
        StringBuilder insert = new StringBuilder("INSERT INTO orders VALUES ");
        for (int i = 1; i <= 20; i++) {
            insert.append(i > 1 ? ", " : "")
                    .append("(")
                    .append(i)
                    .append(", 'v")
                    .append(i)
                    .append("')");
        }
        sql(insert.toString());

        JobClient job = start(GRANTED);
        boolean snapshot = await(count(GRANTED, 20), 240);
        result(
                "snapshot "
                        + (snapshot ? "PASS" : "FAIL")
                        + " rows="
                        + rows(GRANTED).size()
                        + "/20");
        StringBuilder more = new StringBuilder("INSERT INTO orders VALUES ");
        for (int i = 21; i <= 30; i++) {
            more.append(i > 21 ? ", " : "")
                    .append("(")
                    .append(i)
                    .append(", 'v")
                    .append(i)
                    .append("')");
        }
        sql(
                more.toString(),
                "UPDATE orders SET v = 'u' WHERE id <= 5",
                "DELETE FROM orders WHERE id BETWEEN 6 AND 8");
        sql("ALTER TABLE orders ADD COLUMN c INT", "INSERT INTO orders VALUES (31, 'v31', 7)");
        boolean changes = await(count(GRANTED, 28), 180);
        Thread.sleep(6000);
        TreeMap<Long, String> have = rows(GRANTED);
        TreeMap<Long, String> want = new TreeMap<>();
        for (long i = 1; i <= 31; i++) {
            if (i >= 6 && i <= 8) {
                continue;
            }
            want.put(i, (i <= 5 ? "u" : "v" + i) + "|" + (i == 31 ? "7" : ""));
        }
        result(
                "changes "
                        + (changes && have.equals(want) ? "PASS" : "FAIL")
                        + " rows="
                        + have.size()
                        + "/28 same="
                        + have.equals(want)
                        + " sample="
                        + have.get(31L));
        try (Catalog catalog = catalog()) {
            Table t = catalog.getTable(Identifier.create(GRANTED, "orders"));
            result(
                    "table fields="
                            + t.rowType().getFieldNames()
                            + " primaryKeys="
                            + t.primaryKeys()
                            + " options="
                            + new TreeMap<>(t.options()));
        }
        job.cancel().get(60, TimeUnit.SECONDS);

        // No grant on the other database: the sink is refused, nothing is written.
        JobClient other = start(REFUSED);
        String refusal;
        try {
            other.getJobExecutionResult().get(180, TimeUnit.SECONDS);
            refusal = "FAIL the job ran without a refusal";
        } catch (Exception e) {
            Throwable t = e;
            StringBuilder chain = new StringBuilder();
            while (t != null) {
                chain.append(t.getClass().getSimpleName())
                        .append(": ")
                        .append(t.getMessage())
                        .append(" <- ");
                t = t.getCause();
            }
            String text = chain.toString().replace(TOKEN, "<token>");
            boolean refused =
                    text.contains("Forbidden")
                            || text.contains("403")
                            || text.contains("not authorized");
            refusal =
                    (refused ? "PASS " : "FAIL ") + text.substring(0, Math.min(600, text.length()));
        }
        try {
            other.cancel().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Already over.
        }
        String readBack;
        try {
            readBack = "rows=" + rows(REFUSED).size();
        } catch (Exception e) {
            readBack = "read refused too: " + e.getClass().getSimpleName();
        }
        result("refused " + refusal + " " + readBack);
        assertTrue(
                RESULTS.stream().noneMatch(l -> l.contains(" FAIL")), String.join("\n", RESULTS));
    }
}
