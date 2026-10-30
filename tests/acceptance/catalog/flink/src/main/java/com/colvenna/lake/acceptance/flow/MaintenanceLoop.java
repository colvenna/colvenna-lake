package com.colvenna.lake.acceptance.flow;

import java.time.Duration;
import java.time.Instant;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;

/**
 * Maintenance running alongside the writers of {@link CommitStress}. Compacts the table and expires
 * old snapshots in a loop, through the same catalog, so compaction commits race the writers'
 * appends. A failed round is reported and the loop continues; the run fails only if no round
 * succeeds. Prints {@code STEP} lines and {@code FLOW PASS <database>}.
 *
 * <p>Arguments: database, table, minutes to run, seconds between rounds.
 */
public final class MaintenanceLoop {

    /** Commit retries of a compaction racing many writers. */
    static final String COMPACT_RETRIES =
            "commit.max-retries=200,commit.min-retry-wait=50 ms,commit.max-retry-wait=500 ms";

    private MaintenanceLoop() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "usage: MaintenanceLoop <database> <table> <minutes> <interval-seconds>");
        }
        String db = args[0];
        String table = db + "." + args[1];
        Instant end = Instant.now().plus(Duration.ofMinutes(Long.parseLong(args[2])));
        long interval = Long.parseLong(args[3]) * 1000;
        TableEnvironment batch = TableEnvironment.create(EnvironmentSettings.inBatchMode());
        batch.getConfig().set("table.dml-sync", "true");
        batch.getConfig().set("parallelism.default", "1");
        batch.executeSql(FlinkCatalogFlow.catalogDdl());
        int ok = 0;
        int failed = 0;
        // Wait for the writers to create the table.
        Thread.sleep(interval);
        while (Instant.now().isBefore(end)) {
            try {
                // Commits through REST are optimistic: while writers commit every few hundred
                // milliseconds, a compaction that re-plans for a second per attempt keeps losing
                // the race, so it gets many quick retries rather than Paimon's ten.
                batch.executeSql(
                                "CALL colvenna.sys.compact(`table` => '"
                                        + table
                                        + "', options => '"
                                        + COMPACT_RETRIES
                                        + "')")
                        .await();
                batch.executeSql(
                                "CALL colvenna.sys.expire_snapshots(`table` => '"
                                        + table
                                        + "', retain_min => 5, retain_max => 30)")
                        .await();
                ok++;
                System.out.println("ROUND OK " + Instant.now());
            } catch (Exception e) {
                failed++;
                System.out.println(
                        "ROUND FAIL " + Instant.now() + " " + CommitStress.rootMessage(e));
            }
            Thread.sleep(interval);
        }
        System.out.println(
                "STEP maintenance-rounds "
                        + (ok > 0 ? "OK " : "FAIL ")
                        + ok
                        + " compact+expire rounds, "
                        + failed
                        + " failed");
        if (ok == 0) {
            throw new AssertionError("no maintenance round succeeded");
        }
        System.out.println("FLOW PASS " + db);
    }
}
