package com.colvenna.lake.acceptance.flow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

/**
 * The Spark side of the data-token renewal check: one Spark session writes and reads a table, waits
 * longer than its first data token lives, and writes and reads again, so the second round only
 * succeeds if the client renewed its credentials. Prints {@code STEP} lines and {@code FLOW PASS}.
 *
 * <p>Arguments: database, wait minutes, and optionally minutes between reads of the held table
 * during the wait: an idle client renews on its next use after the expiry, an active one before it.
 * Environment as {@link SparkCatalogFlow}.
 */
public final class SparkRenewalFlow {

    private SparkRenewalFlow() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: SparkRenewalFlow <database> <wait-minutes> [<read-every-minutes>]");
        }
        String db = args[0];
        Duration wait = Duration.ofMinutes(Long.parseLong(args[1]));
        Duration every = args.length == 3 ? Duration.ofMinutes(Long.parseLong(args[2])) : null;
        String token =
                Files.readString(
                                Path.of(System.getenv("COLVENNA_CATALOG_TOKEN_FILE")),
                                StandardCharsets.UTF_8)
                        .trim();
        SparkSession spark =
                SparkSession.builder()
                        .appName("colvenna-spark-renewal")
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
            String table = "colvenna." + db + ".spark_renewal";
            spark.sql("CREATE DATABASE IF NOT EXISTS colvenna." + db).collectAsList();
            spark.sql("CREATE TABLE " + table + " (id BIGINT, at STRING)").collectAsList();
            round(spark, table, 1);
            // A table object held across the wait, so its credentials must be renewed in use
            // rather than fetched afresh for a reloaded table.
            try (Catalog paimon = paimonCatalog(token)) {
                Table held = paimon.getTable(Identifier.create(db, "spark_renewal"));
                expectHeld(held, 1);
                Instant resume = Instant.now().plus(wait);
                Instant nextRead = every == null ? null : Instant.now().plus(every);
                while (Instant.now().isBefore(resume)) {
                    Thread.sleep(60_000);
                    if (nextRead != null && !Instant.now().isBefore(nextRead)) {
                        expectHeld(held, 1);
                        nextRead = Instant.now().plus(every);
                    }
                }
                round(spark, table, 2);
                expectHeld(held, 2);
            }
            System.out.println("FLOW PASS " + db);
        } finally {
            spark.stop();
        }
    }

    private static Catalog paimonCatalog(String token) {
        Options options = new Options();
        options.set("metastore", "rest");
        options.set("uri", System.getenv("COLVENNA_CATALOG_URI"));
        options.set("warehouse", "colvenna");
        options.set("token.provider", "bear");
        options.set("token", token);
        options.set("data-token.enabled", "true");
        return CatalogFactory.createCatalog(CatalogContext.create(options));
    }

    private static void expectHeld(Table held, long expected) throws Exception {
        ReadBuilder read = held.newReadBuilder();
        long count = 0;
        try (RecordReader<InternalRow> reader =
                read.newRead().createReader(read.newScan().plan())) {
            RecordReader.RecordIterator<InternalRow> batch;
            while ((batch = reader.readBatch()) != null) {
                while (batch.next() != null) {
                    count++;
                }
                batch.releaseBatch();
            }
        }
        String step = "held-table-read-" + expected;
        if (count != expected) {
            System.out.println("STEP " + step + " FAIL count " + count);
            throw new AssertionError("held table shows " + count + " rows, expected " + expected);
        }
        System.out.println("STEP " + step + " OK same table object at " + Instant.now());
    }

    private static void round(SparkSession spark, String table, long expected) {
        spark.sql("INSERT INTO " + table + " VALUES (" + expected + ", '" + Instant.now() + "')")
                .collectAsList();
        List<Row> rows = spark.sql("SELECT count(*) FROM " + table).collectAsList();
        long count = rows.get(0).getLong(0);
        if (count != expected) {
            System.out.println("STEP round-" + expected + " FAIL count " + count);
            throw new AssertionError("expected " + expected + " rows, got " + count);
        }
        System.out.println("STEP round-" + expected + " OK write and read at " + Instant.now());
    }
}
