package com.colvenna.lake.upstream.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.core.execution.SavepointFormatType;
import org.junit.jupiter.api.Test;

/**
 * FLINK-40741 against Paimon's MySQL sync-database action, as colvenna-jobs runs it: a table added
 * to the job's selection is snapshotted after a restore (Flink CDC's newly-added-table scan), and a
 * failover right after that, at the new table's first change from the binlog, may lose every later
 * change of the table. Run with {@code -Dcdc.version=3.5.0} and {@code 3.7.0-1.20}; the test
 * asserts the new table's rows and changelog against the source, and says how many changes were
 * lost.
 */
class NewlyAddedFailoverTest {
    /** The source's keys the lake does not hold, comma-separated, or "none". */
    static String missing(TreeMap<Long, String> want, TreeMap<Long, String> have) {
        StringBuilder out = new StringBuilder();
        for (Long id : want.keySet()) {
            if (!have.containsKey(id)) {
                out.append(out.length() > 0 ? "," : "").append(id);
            }
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    @Test
    void aTableAddedOnRestoreKeepsItsChangesThroughAFailover() throws Exception {
        String version = System.getProperty("cdc.version");
        Path warehouse = Files.createTempDirectory("warehouse");
        Path savepoints = Files.createTempDirectory("savepoints");
        MySqlSource.sql(
                "CREATE TABLE orders (id BIGINT PRIMARY KEY, v VARCHAR(16))",
                MySqlSource.rows("orders", 1, 20));

        // The job reads orders only, until its selection changes: a savepoint, a new job.
        JobClient first = MySqlJob.start(warehouse, "orders", null);
        assertEquals(
                true, Lake.await(Lake.has(warehouse, "orders", 20), 180), "orders snapshotted");
        String savepoint =
                first.stopWithSavepoint(
                                false, savepoints.toUri().toString(), SavepointFormatType.CANONICAL)
                        .get();

        MySqlSource.sql(
                "CREATE TABLE products (id BIGINT PRIMARY KEY, v VARCHAR(16))",
                MySqlSource.rows("products", 1, 10),
                MySqlSource.rows("orders", 21, 25));
        MySqlJob.FAIL_AT.set("products");
        JobClient second = MySqlJob.start(warehouse, "orders|products", savepoint);
        assertEquals(
                true, Lake.await(Lake.has(warehouse, "products", 10), 180), "products snapshotted");
        // Its first change from the binlog fails the job once; the changes after it must arrive.
        MySqlSource.sql(MySqlSource.rows("products", 11, 20));
        Thread.sleep(15_000);
        MySqlSource.sql(
                MySqlSource.rows("products", 21, 30),
                "UPDATE products SET v = 'u' WHERE id <= 3",
                "DELETE FROM products WHERE id = 4",
                MySqlSource.rows("orders", 26, 30));
        TreeMap<Long, String> want = MySqlSource.values("products");
        boolean all =
                Lake.await(
                        () -> {
                            try {
                                return Lake.values(warehouse, "products").equals(want);
                            } catch (Exception e) {
                                return false;
                            }
                        },
                        120);
        TreeMap<Long, String> products = Lake.values(warehouse, "products");
        String replay = Lake.replay(Lake.changelog(warehouse, "products"), want);
        int orders = Lake.values(warehouse, "orders").size();
        second.cancel().get();
        String line =
                String.format(
                        "RESULT cdc=%s checkpoint=%sms failed=%s products=%d/%d orders=%d/30 missing=%s"
                                + " changelog=%s",
                        version,
                        System.getProperty("checkpoint.ms", "1000"),
                        MySqlJob.FAIL_AT.get() == null,
                        products.size(),
                        want.size(),
                        orders,
                        missing(want, products),
                        replay == null ? "replays" : replay);
        System.out.println(line);
        Files.writeString(Path.of("target", "result-" + version + ".txt"), line + "\n");
        assertNull(MySqlJob.FAIL_AT.get(), "the failure was injected");
        assertEquals(true, all, line);
        assertNull(replay, line);
        assertEquals(30, orders, line);
    }
}
