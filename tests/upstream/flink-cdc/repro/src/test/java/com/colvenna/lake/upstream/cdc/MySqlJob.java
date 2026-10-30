package com.colvenna.lake.upstream.cdc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.runtime.jobgraph.SavepointConfigOptions;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.Collector;
import org.apache.paimon.flink.action.MultiTablesSinkMode;
import org.apache.paimon.flink.action.cdc.CdcSourceRecord;
import org.apache.paimon.flink.action.cdc.mysql.MySqlSyncDatabaseAction;
import org.apache.paimon.flink.sink.cdc.RichCdcMultiplexRecord;

/**
 * A job as colvenna-jobs' MySqlCdcJob runs it: Paimon's MySQL sync-database action in combined
 * mode, into ods with the ODS targets' changelog (input) and no compaction (write-only), against
 * the local MySQL and warehouse. {@link #FAIL_AT} fails the job once, at the first change of that
 * table read from the binlog.
 */
final class MySqlJob {
    /** The table whose first binlog insert fails the job once; null for no failure. */
    static final AtomicReference<String> FAIL_AT = new AtomicReference<>();

    private MySqlJob() {}

    /** The action, with the failure put in its parser. */
    static final class Action extends MySqlSyncDatabaseAction {
        Action(Map<String, String> catalog, Map<String, String> mysql) {
            super("ods", catalog, mysql);
        }

        @Override
        protected FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> recordParse() {
            FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> parse = super.recordParse();
            return new FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord>() {
                @Override
                public void flatMap(CdcSourceRecord r, Collector<RichCdcMultiplexRecord> out)
                        throws Exception {
                    String table = FAIL_AT.get();
                    String v = String.valueOf(r.getValue());
                    if (table != null
                            && v.contains("\"table\":\"" + table + "\"")
                            && v.contains("\"op\":\"c\"")
                            && FAIL_AT.compareAndSet(table, null)) {
                        throw new RuntimeException(
                                "injected failover at " + table + "'s first change");
                    }
                    parse.flatMap(r, out);
                }
            };
        }
    }

    /**
     * Starts the job over the tables (a regular expression), restored from the savepoint if one is
     * given. -Dcheckpoint.ms sets the interval: the longer, the likelier a failover lands between
     * the newly-added-table process's last step and the checkpoint that would keep it.
     */
    static JobClient start(Path warehouse, String tables, String restore) throws Exception {
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
        Map<String, String> mysql = new HashMap<>();
        mysql.put("hostname", MySqlSource.HOST);
        mysql.put("port", Integer.toString(MySqlSource.PORT));
        mysql.put("username", "root");
        mysql.put("password", MySqlSource.PASSWORD);
        mysql.put("database-name", "shop");
        mysql.put("server-id", "5400-5404");
        mysql.put("server-time-zone", "UTC");
        Action action = new Action(Map.of("warehouse", warehouse.toUri().toString()), mysql);
        action.withMode(MultiTablesSinkMode.COMBINED);
        action.includingTables(tables);
        action.withTableConfig(
                Map.of("bucket", "1", "changelog-producer", "input", "write-only", "true"));
        action.withStreamExecutionEnvironment(env);
        action.build();
        return env.executeAsync("repro " + tables);
    }
}
