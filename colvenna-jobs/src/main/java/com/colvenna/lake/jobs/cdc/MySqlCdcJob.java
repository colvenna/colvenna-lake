package com.colvenna.lake.jobs.cdc;

import com.colvenna.lake.jobs.Catalogs;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.paimon.flink.action.MultiTablesSinkMode;
import org.apache.paimon.flink.action.cdc.CdcSourceRecord;
import org.apache.paimon.flink.action.cdc.TypeMapping;
import org.apache.paimon.flink.action.cdc.mysql.MySqlSyncDatabaseAction;
import org.apache.paimon.flink.sink.cdc.RichCdcMultiplexRecord;

/**
 * A declared MySQL source: Paimon's MySQL sync-database action in combined mode, over exactly the
 * tables colvenna-operator admitted, committing through colvenna-catalog with per-table data
 * tokens, with the declaration in place of fixed settings.
 *
 * <p>Tables are named exactly, so a table the operator has not admitted is never captured, and one
 * it adds is snapshotted after the restart its new spec causes (Flink CDC's newly-added-table scan,
 * on by default, runs only on restore).
 */
public final class MySqlCdcJob extends MySqlSyncDatabaseAction {

    private final CdcSpec spec;

    private MySqlCdcJob(CdcSpec spec, Map<String, String> catalog, Map<String, String> mysql) {
        super(spec.targetDatabase, catalog, mysql);
        this.spec = spec;
    }

    @Override
    protected FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> recordParse() {
        // MySQL's before image is always the full row (binlog_row_image=FULL, checked on
        // admission); keys are immutable by the source contract's trigger.
        return new SourceVersionGuard(super.recordParse(), spec.versionColumn, List.of(), false);
    }

    static Map<String, String> mysql(CdcSpec spec) {
        Map<String, String> config = new HashMap<>();
        config.put("hostname", spec.host);
        config.put("port", Integer.toString(spec.port));
        config.put("username", Catalogs.required("COLVENNA_SOURCE_USER"));
        config.put("password", Catalogs.required("COLVENNA_SOURCE_PASSWORD"));
        config.put("database-name", spec.database);
        if (spec.serverId == null) {
            throw new IllegalArgumentException("A MySQL source needs serverId");
        }
        config.put("server-id", spec.serverId);
        if (spec.serverTimeZone != null) {
            config.put("server-time-zone", spec.serverTimeZone);
        }
        if (spec.sourceCa == null) {
            config.put("jdbc.properties.sslMode", "REQUIRED");
            config.put("debezium.database.ssl.mode", "required");
        } else {
            // Both trust the JVM store, which the pod's init container extended with the CA.
            config.put("jdbc.properties.sslMode", "VERIFY_IDENTITY");
            config.put("debezium.database.ssl.mode", "verify_identity");
        }
        return config;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: MySqlCdcJob <spec-json>");
        }
        CdcSpec spec = CdcSpec.parse(args[0]);
        spec.requireVersionColumn();
        MySqlCdcJob action = new MySqlCdcJob(spec, Catalogs.restOptions(), mysql(spec));
        action.withMode(MultiTablesSinkMode.COMBINED);
        action.includingTables(spec.tableRegex());
        // The operator excludes tables whose schema no longer fits; never skip one silently.
        action.ignoreIncompatible(false);
        action.withTableConfig(spec.tableOptions);
        // MySQL TINYINT(1) is a number, not a boolean: any value other than 0 and 1 would be lost.
        action.withTypeMapping(TypeMapping.parse(new String[] {"tinyint1-not-bool"}));
        action.run();
    }
}
