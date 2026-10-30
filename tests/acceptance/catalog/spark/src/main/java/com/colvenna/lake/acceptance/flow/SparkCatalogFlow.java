package com.colvenna.lake.acceptance.flow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

/**
 * The Spark side of the catalog acceptance: Spark 3.5.8 with the Paimon 2.0.0 connector reads the
 * tables the Flink flow wrote and runs its own batch writes, row-level changes and procedures
 * through colvenna-catalog, all on per-table data tokens. Prints {@code STEP <name> OK|FAIL} lines
 * and exits 1 on the first failure.
 *
 * <p>Environment: {@code COLVENNA_CATALOG_URI}, {@code COLVENNA_CATALOG_TOKEN_FILE}. Argument: the
 * database the Flink flow created.
 */
public final class SparkCatalogFlow {

    private final SparkSession spark;
    private final String db;

    private SparkCatalogFlow(SparkSession spark, String db) {
        this.spark = spark;
        this.db = db;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: SparkCatalogFlow <database>");
        }
        String token =
                Files.readString(
                                Path.of(System.getenv("COLVENNA_CATALOG_TOKEN_FILE")),
                                StandardCharsets.UTF_8)
                        .trim();
        SparkSession spark =
                SparkSession.builder()
                        .appName("colvenna-spark-flow")
                        .config(
                                "spark.sql.extensions",
                                "org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions")
                        .config(
                                "spark.sql.catalog.colvenna",
                                "org.apache.paimon.spark.SparkCatalog")
                        .config("spark.sql.catalog.colvenna.metastore", "rest")
                        .config(
                                "spark.sql.catalog.colvenna.uri",
                                System.getenv("COLVENNA_CATALOG_URI"))
                        .config("spark.sql.catalog.colvenna.warehouse", "colvenna")
                        .config("spark.sql.catalog.colvenna.token.provider", "bear")
                        .config("spark.sql.catalog.colvenna.token", token)
                        .config("spark.sql.catalog.colvenna.data-token.enabled", "true")
                        .getOrCreate();
        try {
            new SparkCatalogFlow(spark, args[0]).run();
        } finally {
            spark.stop();
        }
    }

    private void run() throws Exception {
        String orders = "colvenna." + db + ".orders";
        String events = "colvenna." + db + ".events";
        String sales = "colvenna." + db + ".spark_sales";
        step(
                "read-flink-tables",
                () -> {
                    expect(97, count(orders));
                    expect(3000, count(events));
                    expect(1, count(orders + " WHERE note = 'evolved'"));
                },
                "cross-engine read of the Flink flow's tables");
        step(
                "time-travel-version-and-tag",
                () -> {
                    expect(100, count(orders + " VERSION AS OF 1"));
                    expect(96, count(orders + " VERSION AS OF 'flow_tag'"));
                },
                "snapshot id and tag");
        step(
                "read-branch",
                () -> expect(97, count("colvenna." + db + ".`orders$branch_flow_branch`")),
                "branch written by Flink");
        step(
                "system-tables",
                () -> {
                    positive(count("colvenna." + db + ".`orders$snapshots`"));
                    positive(count("colvenna." + db + ".`orders$schemas`"));
                    expect(
                            1,
                            count("colvenna." + db + ".`orders$tags` WHERE tag_name = 'flow_tag'"));
                    positive(count("colvenna." + db + ".`orders$files`"));
                },
                "snapshots, schemas, tags, files");
        step(
                "create-and-insert",
                () -> {
                    sql(
                            "CREATE TABLE "
                                    + sales
                                    + " (id BIGINT, region STRING, amount INT) TBLPROPERTIES"
                                    + " ('primary-key'='id', 'bucket'='2',"
                                    + " 'deletion-vectors.enabled'='true')");
                    sql(
                            "INSERT INTO "
                                    + sales
                                    + " VALUES (1, 'east', 10), (2, 'west', 20), (3, 'east', 30)");
                    expect(3, count(sales));
                },
                "Spark-created primary-key table");
        step(
                "update-delete-merge",
                () -> {
                    sql("UPDATE " + sales + " SET amount = amount + 1 WHERE region = 'east'");
                    sql("DELETE FROM " + sales + " WHERE id = 2");
                    sql(
                            "MERGE INTO "
                                    + sales
                                    + " t USING (SELECT 3 AS id, 'east' AS region, 300 AS amount"
                                    + " UNION ALL SELECT 4, 'north', 40) s ON t.id = s.id WHEN"
                                    + " MATCHED THEN UPDATE SET t.amount = s.amount WHEN NOT"
                                    + " MATCHED THEN INSERT *");
                    expect(3, count(sales));
                    expect(11, scalar("SELECT amount FROM " + sales + " WHERE id = 1"));
                    expect(300, scalar("SELECT amount FROM " + sales + " WHERE id = 3"));
                },
                "row-level changes");
        step(
                "alter-table",
                () -> {
                    sql("ALTER TABLE " + sales + " ADD COLUMNS (channel STRING)");
                    sql("INSERT INTO " + sales + " VALUES (5, 'south', 50, 'web')");
                    expect(1, count(sales + " WHERE channel = 'web'"));
                },
                "schema evolution");
        step(
                "procedures",
                () -> {
                    sql(
                            "CALL colvenna.sys.create_tag(table => '"
                                    + db
                                    + ".spark_sales', tag => 's1')");
                    sql("CALL colvenna.sys.compact(table => '" + db + ".spark_sales')");
                    expect(
                            1,
                            count("colvenna." + db + ".`spark_sales$tags` WHERE tag_name = 's1'"));
                    expect(4, count(sales));
                },
                "create_tag, compact");
        step(
                "write-to-flink-table",
                () -> {
                    sql("INSERT INTO " + orders + " VALUES (500, 5, 'from-spark', 'spark')");
                    expect(98, count(orders));
                },
                "one commit authority for both engines");
        System.out.println("FLOW PASS " + db);
    }

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    private static void step(String name, Body body, String detail) throws Exception {
        long start = System.nanoTime();
        try {
            body.run();
        } catch (Throwable e) {
            System.out.println("STEP " + name + " FAIL " + e);
            e.printStackTrace(System.out);
            throw e instanceof Exception ? (Exception) e : new RuntimeException(e);
        }
        System.out.println(
                String.format(
                        Locale.ROOT,
                        "STEP %s OK %dms %s",
                        name,
                        (System.nanoTime() - start) / 1_000_000,
                        detail));
    }

    private void sql(String statement) {
        spark.sql(statement).collectAsList();
    }

    private long count(String from) {
        return scalar("SELECT count(*) FROM " + from);
    }

    private long scalar(String query) {
        List<Row> rows = spark.sql(query).collectAsList();
        if (rows.isEmpty()) {
            throw new AssertionError("no row for " + query);
        }
        Object value = rows.get(0).get(0);
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static void expect(long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    private static void positive(long actual) {
        if (actual <= 0) {
            throw new AssertionError("expected a positive count, got " + actual);
        }
    }
}
