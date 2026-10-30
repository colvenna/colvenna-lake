package com.colvenna.lake.acceptance.flow;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;

/**
 * The Flink side of the data-token renewal check: a checkpointed streaming write that runs longer
 * than its first data token lives, so it only keeps committing if the client renews credentials.
 * Prints {@code STEP <name> OK|FAIL} lines and {@code FLOW PASS <database>}.
 *
 * <p>Arguments: database, run minutes, data-token lifetime in minutes.
 */
public final class FlinkRenewalFlow {

    private static final DateTimeFormatter LITERAL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    private FlinkRenewalFlow() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: FlinkRenewalFlow <database> <run-minutes> <token-minutes>");
        }
        String db = args[0];
        Duration run = Duration.ofMinutes(Long.parseLong(args[1]));
        Duration lifetime = Duration.ofMinutes(Long.parseLong(args[2]));
        if (run.compareTo(lifetime.plusMinutes(5)) < 0) {
            throw new IllegalArgumentException("Run at least 5 minutes past the token lifetime");
        }
        String table = "colvenna." + db + ".renewal";
        String ddl = FlinkCatalogFlow.catalogDdl();
        TableEnvironment batch = TableEnvironment.create(EnvironmentSettings.inBatchMode());
        batch.getConfig().set("table.dml-sync", "true");
        batch.executeSql(ddl);
        batch.executeSql("CREATE DATABASE IF NOT EXISTS colvenna." + db).await();
        batch.executeSql("CREATE TABLE " + table + " (id BIGINT, payload STRING)").await();

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(60_000);
        // No restarts: a writer whose credentials lapse must fail the run, not recover on a token
        // fetched afresh by the restart.
        env.setRestartStrategy(RestartStrategies.noRestart());
        StreamTableEnvironment stream =
                StreamTableEnvironment.create(env, EnvironmentSettings.inStreamingMode());
        stream.executeSql(ddl);
        stream.executeSql(
                // Random ids: a sequence generator checkpoints every value it has yet to emit.
                "CREATE TEMPORARY TABLE gen (id BIGINT, payload STRING) WITH ('connector'='datagen',"
                        + " 'rows-per-second'='2', 'fields.payload.length'='16')");
        Instant start = Instant.now();
        TableResult job = stream.executeSql("INSERT INTO " + table + " SELECT * FROM gen");
        JobClient client = job.getJobClient().orElseThrow();
        try {
            Instant end = start.plus(run);
            while (Instant.now().isBefore(end)) {
                JobStatus status = client.getJobStatus().get();
                if (status.isGloballyTerminalState()) {
                    System.out.println("STEP stream-outlives-first-token FAIL job " + status);
                    throw new AssertionError("streaming job ended early: " + status);
                }
                Thread.sleep(60_000);
                System.out.println(
                        "PROGRESS "
                                + Duration.between(start, Instant.now()).toMinutes()
                                + "min "
                                + status);
            }
        } finally {
            client.cancel().get();
        }
        Instant expiry = start.plus(lifetime);
        long late =
                scalar(
                        batch,
                        "SELECT count(*) FROM colvenna."
                                + db
                                + ".`renewal$snapshots` WHERE commit_time > TIMESTAMP '"
                                + LITERAL.format(expiry)
                                + "'");
        long rows = scalar(batch, "SELECT count(*) FROM " + table);
        if (late < 3 || rows == 0) {
            System.out.println(
                    "STEP stream-outlives-first-token FAIL late commits "
                            + late
                            + ", rows "
                            + rows);
            throw new AssertionError("no commits after the first token expired");
        }
        System.out.println(
                String.format(
                        Locale.ROOT,
                        "STEP stream-outlives-first-token OK %d commits after %s (first token"
                                + " expiry), %d rows",
                        late,
                        expiry,
                        rows));
        System.out.println("FLOW PASS " + db);
    }

    private static long scalar(TableEnvironment env, String query) throws Exception {
        try (CloseableIterator<Row> rows = env.executeSql(query).collect()) {
            Object value = rows.next().getField(0);
            return value == null ? 0 : ((Number) value).longValue();
        }
    }
}
