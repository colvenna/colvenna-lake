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
 * Upgrading a running MySQL source from one Flink CDC version to another (run-repro.sh upgrade):
 * the first version's job runs, and stops with a savepoint while the source keeps changing; the
 * second version's job, in a JVM of its own with the other connector on its classpath, restores
 * from it. The rows must equal the source's, values included, and the full changelog must replay to
 * them, every before image the row the table held, no change applied twice. Phases by -Dphase,
 * sharing -Dstate.dir.
 */
class UpgradeTest {
    @Test
    void aSavepointOfTheOldVersionRestoresInTheNew() throws Exception {
        String phase = System.getProperty("phase", "");
        Path state = Path.of(System.getProperty("state.dir", "target/upgrade"));
        Path warehouse = state.resolve("warehouse");
        String version = System.getProperty("cdc.version");
        if (phase.equals("before")) {
            Files.createDirectories(warehouse);
            MySqlSource.sql(
                    "CREATE TABLE orders (id BIGINT PRIMARY KEY, v VARCHAR(16))",
                    MySqlSource.rows("orders", 1, 20));
            JobClient job = MySqlJob.start(warehouse, "orders", null);
            assertEquals(true, Lake.await(Lake.has(warehouse, "orders", 20), 180));
            MySqlSource.sql(
                    MySqlSource.rows("orders", 21, 30), "UPDATE orders SET v = 'w' WHERE id = 30");
            Lake.await(Lake.has(warehouse, "orders", 30), 60);
            String savepoint =
                    job.stopWithSavepoint(
                                    false,
                                    state.resolve("savepoints").toUri().toString(),
                                    SavepointFormatType.CANONICAL)
                            .get();
            // Written while no job runs: the new version's job reads them from the binlog.
            MySqlSource.sql(
                    "UPDATE orders SET v = 'u' WHERE id <= 5",
                    "DELETE FROM orders WHERE id BETWEEN 6 AND 8",
                    MySqlSource.rows("orders", 31, 40));
            Files.writeString(state.resolve("savepoint.txt"), savepoint);
            Files.writeString(state.resolve("from.txt"), version);
            Files.writeString(
                    Path.of("target", "result-" + version + ".txt"),
                    "RESULT upgrade from "
                            + version
                            + ": savepoint taken; 18 changes after it (5 updates, 3 deletes, 10"
                            + " inserts)\n");
            return;
        }
        String savepoint = Files.readString(state.resolve("savepoint.txt")).strip();
        String from = Files.readString(state.resolve("from.txt")).strip();
        JobClient job = MySqlJob.start(warehouse, "orders", savepoint);
        MySqlSource.sql(
                MySqlSource.rows("orders", 41, 50), "UPDATE orders SET v = 'x' WHERE id = 45");
        TreeMap<Long, String> want = MySqlSource.values("orders");
        boolean same =
                Lake.await(
                        () -> {
                            try {
                                return Lake.values(warehouse, "orders").equals(want);
                            } catch (Exception e) {
                                return false;
                            }
                        },
                        180);
        Thread.sleep(5000);
        TreeMap<Long, String> have = Lake.values(warehouse, "orders");
        String replay = Lake.replay(Lake.changelog(warehouse, "orders"), want);
        job.cancel().get();
        String line =
                String.format(
                        "RESULT upgrade %s->%s rows=%d/%d values-equal=%s changelog=%s",
                        from,
                        version,
                        have.size(),
                        want.size(),
                        have.equals(want),
                        replay == null ? "replays" : replay);
        System.out.println(line);
        Files.writeString(Path.of("target", "result-" + version + ".txt"), line + "\n");
        assertEquals(true, same && have.equals(want), line);
        assertNull(replay, line);
    }
}
