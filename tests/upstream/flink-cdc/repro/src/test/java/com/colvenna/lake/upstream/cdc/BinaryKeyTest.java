package com.colvenna.lake.upstream.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.flink.action.MultiTablesSinkMode;
import org.apache.paimon.flink.action.cdc.mysql.MySqlSyncDatabaseAction;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.source.ReadBuilder;
import org.junit.jupiter.api.Test;

/**
 * Binary columns through Paimon's MySQL sync-database action, apart: a VARBINARY primary key (does
 * Flink CDC's snapshot split and compare it, and does the change stream key on it), and binary
 * values that are not UTF-8 (does Paimon's writer keep the bytes). Snapshot, then insert, update
 * and delete from the binlog; the table's rows against the source's, byte for byte, as hex.
 */
class BinaryKeyTest {
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format("%02x", b & 0xff));
        }
        return out.toString();
    }

    /**
     * -Dbinary.ascii=true keeps every byte below 0x80, valid UTF-8: Paimon's writer then carries
     * them, and what is left to test is Flink CDC splitting the snapshot on a binary key. Otherwise
     * the high bytes test the writer's decoding of binary values too.
     */
    static final boolean ASCII = Boolean.getBoolean("binary.ascii");

    /** Row i's key: 16 bytes, distinct per row, ordered by i only through their first byte. */
    static byte[] key(int i) {
        byte[] k = new byte[16];
        for (int j = 0; j < 16; j++) {
            k[j] = (byte) (ASCII ? 0x21 + ((i * 7 + j) % 0x5e) : 0x80 + ((i * 7 + j) % 0x7f));
        }
        k[0] = (byte) (0x20 + i);
        return k;
    }

    static byte[] payload(int i, int version) {
        return ASCII
                ? new byte[] {0x41, 0x42, (byte) (0x20 + i), (byte) (0x30 + version)}
                : new byte[] {(byte) 0xff, (byte) 0xfe, (byte) i, (byte) version, 0, (byte) 0xc3};
    }

    static void upsert(int i, int version) throws Exception {
        try (Connection c = MySqlSource.connect();
                PreparedStatement p =
                        c.prepareStatement(
                                "INSERT INTO blobs (k, v, payload) VALUES (?, ?, ?) ON DUPLICATE KEY"
                                        + " UPDATE v = VALUES(v), payload = VALUES(payload)")) {
            p.setBytes(1, key(i));
            p.setInt(2, version);
            p.setBytes(3, payload(i, version));
            p.executeUpdate();
        }
    }

    /** The source's rows: hex key to "version/hex payload". */
    static TreeMap<String, String> source() throws Exception {
        TreeMap<String, String> out = new TreeMap<>();
        try (Connection c = MySqlSource.connect();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT k, v, payload FROM blobs")) {
            while (r.next()) {
                out.put(hex(r.getBytes(1)), r.getInt(2) + "/" + hex(r.getBytes(3)));
            }
        }
        return out;
    }

    static TreeMap<String, String> lake(Path warehouse) throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        TreeMap<String, String> out = new TreeMap<>();
        try (Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options))) {
            Table t;
            try {
                t = catalog.getTable(Identifier.create("ods", "blobs"));
            } catch (Catalog.TableNotExistException e) {
                return out;
            }
            ReadBuilder read = t.newReadBuilder();
            try (RecordReader<InternalRow> reader =
                    read.newRead().createReader(read.newScan().plan())) {
                reader.forEachRemaining(
                        row ->
                                out.put(
                                        hex(row.getBinary(0)),
                                        row.getInt(1)
                                                + "/"
                                                + (row.isNullAt(2)
                                                        ? "null"
                                                        : hex(row.getBinary(2)))));
            }
        }
        return out;
    }

    @Test
    void binaryKeysAndValuesAreCarriedByteForByte() throws Exception {
        String version = System.getProperty("cdc.version");
        Path warehouse = Files.createTempDirectory("warehouse");
        try (Connection c = MySqlSource.connect();
                Statement s = c.createStatement()) {
            s.execute(
                    "CREATE TABLE blobs (k VARBINARY(16) PRIMARY KEY, v INT NOT NULL, payload"
                            + " VARBINARY(32))");
            for (int i = 1; i <= 50; i++) {
                upsert(i, 1);
            }
        }
        Configuration conf = new Configuration();
        conf.set(RestartStrategyOptions.RESTART_STRATEGY, "none");
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(conf);
        env.setParallelism(1);
        env.enableCheckpointing(1000);
        Map<String, String> mysql = new HashMap<>();
        mysql.put("hostname", MySqlSource.HOST);
        mysql.put("port", Integer.toString(MySqlSource.PORT));
        mysql.put("username", "root");
        mysql.put("password", MySqlSource.PASSWORD);
        mysql.put("database-name", "shop");
        mysql.put("server-id", "5410-5414");
        mysql.put("server-time-zone", "UTC");
        // Small chunks, so the snapshot splits on the binary key.
        mysql.put("scan.incremental.snapshot.chunk.size", "8");
        MySqlSyncDatabaseAction action =
                new MySqlSyncDatabaseAction(
                        "ods", Map.of("warehouse", warehouse.toUri().toString()), mysql);
        action.withMode(MultiTablesSinkMode.COMBINED);
        action.includingTables("blobs");
        action.withTableConfig(Map.of("bucket", "1"));
        action.withStreamExecutionEnvironment(env);
        action.build();
        JobClient job = env.executeAsync("binary " + version);
        String outcome;
        String status = "RUNNING";
        try {
            awaitOrEnd(job, () -> sizeOf(warehouse) >= 50, 180);
            try (Connection c = MySqlSource.connect();
                    Statement s = c.createStatement()) {
                for (int i = 51; i <= 60; i++) {
                    upsert(i, 1);
                }
                for (int i = 1; i <= 10; i++) {
                    upsert(i, 2);
                }
                try (PreparedStatement p = c.prepareStatement("DELETE FROM blobs WHERE k = ?")) {
                    for (int i = 11; i <= 15; i++) {
                        p.setBytes(1, key(i));
                        p.executeUpdate();
                    }
                }
            }
            TreeMap<String, String> want = source();
            awaitOrEnd(job, () -> sizeOf(warehouse) == want.size(), 60);
            Thread.sleep(5000);
            TreeMap<String, String> have = lake(warehouse);
            int keys = 0;
            int values = 0;
            for (Map.Entry<String, String> e : want.entrySet()) {
                String got = have.get(e.getKey());
                if (got == null) {
                    keys++;
                } else if (!got.equals(e.getValue())) {
                    values++;
                }
            }
            outcome =
                    String.format(
                            "source=%d lake=%d keys-missing=%d values-differing=%d extra=%d"
                                    + " sample-source=%s sample-lake=%s",
                            want.size(),
                            have.size(),
                            keys,
                            values,
                            have.size() - (want.size() - keys),
                            want.firstEntry(),
                            have.isEmpty() ? "none" : have.firstEntry());
            status = String.valueOf(job.getJobStatus().get());
        } catch (Exception e) {
            outcome = "job or read failed: " + e;
        }
        try {
            status = String.valueOf(job.getJobStatus().get());
            job.cancel().get();
        } catch (Exception e) {
            // The job ended, its cluster with it.
        }
        String failure = "FAILED".equals(status) ? failure(job) : null;
        String line =
                "BINARY cdc="
                        + version
                        + " ascii="
                        + ASCII
                        + " job="
                        + status
                        + " "
                        + outcome
                        + (failure == null ? "" : " failure=" + failure);
        System.out.println(line);
        Files.writeString(Path.of("target", "result-" + version + ".txt"), line + "\n");
        assertEquals(true, outcome.contains("keys-missing=0 values-differing=0 extra=0"), line);
    }

    /** Waits for the condition, or until the job ended (it does not restart). */
    static void awaitOrEnd(JobClient job, java.util.function.BooleanSupplier condition, int seconds)
            throws Exception {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end && !condition.getAsBoolean()) {
            if (job.getJobStatus().get().isGloballyTerminalState()) {
                throw new IllegalStateException("the job ended: " + failure(job));
            }
            Thread.sleep(1000);
        }
    }

    /** The innermost cause of the job's failure, if it failed. */
    static String failure(JobClient job) {
        try {
            job.getJobExecutionResult().get(5, java.util.concurrent.TimeUnit.SECONDS);
            return null;
        } catch (Exception e) {
            Throwable t = e;
            while (t.getCause() != null) {
                t = t.getCause();
            }
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    static int sizeOf(Path warehouse) {
        try {
            return lake(warehouse).size();
        } catch (Exception e) {
            return -1;
        }
    }
}
