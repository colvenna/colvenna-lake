package com.colvenna.lake.jobs.cdc;

import com.colvenna.lake.jobs.Catalogs;
import java.util.HashMap;
import java.util.Map;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.paimon.flink.action.cdc.CdcSourceRecord;
import org.apache.paimon.flink.action.cdc.postgres.PostgresSyncTableAction;
import org.apache.paimon.flink.sink.cdc.RichCdcMultiplexRecord;

/**
 * A declared PostgreSQL source table: Paimon's single-table action (PostgreSQL has no
 * database-level one, HLD 7.2) on its own replication slot, committing through colvenna-catalog. A
 * partitioned table is read from its leaf partitions into one Paimon table
 * (docs/history/m1/postgres-cdc.md).
 */
public final class PostgresCdcJob extends PostgresSyncTableAction {

    private final CdcSpec spec;

    private PostgresCdcJob(CdcSpec spec, Map<String, String> catalog, Map<String, String> pg) {
        super(spec.targetDatabase, spec.targetTable, catalog, pg);
        this.spec = spec;
    }

    @Override
    protected FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> recordParse() {
        return new SourceVersionGuard(
                super.recordParse(), spec.versionColumn, spec.primaryKeys, true);
    }

    static Map<String, String> postgres(CdcSpec spec) {
        if (spec.targetTable == null
                || spec.schema == null
                || spec.slot == null
                || spec.publication == null
                || spec.primaryKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "A PostgreSQL source needs targetTable, schema, slot, publication and"
                            + " primaryKeys");
        }
        Map<String, String> config = new HashMap<>();
        config.put("hostname", spec.host);
        config.put("port", Integer.toString(spec.port));
        config.put("username", Catalogs.required("COLVENNA_SOURCE_USER"));
        config.put("password", Catalogs.required("COLVENNA_SOURCE_PASSWORD"));
        config.put("database-name", spec.database);
        config.put("schema-name", spec.schema);
        config.put("table-name", spec.tableRegex());
        config.put("slot.name", spec.slot);
        config.put("decoding.plugin.name", "pgoutput");
        // The publication is the source owner's: the job only reads it.
        config.put("debezium.publication.name", spec.publication);
        config.put("debezium.publication.autocreate.mode", "disabled");
        if (spec.sourceCa == null) {
            config.put("debezium.database.sslmode", "require");
        } else {
            config.put("debezium.database.sslmode", "verify-full");
            config.put("debezium.database.sslrootcert", spec.sourceCa);
        }
        return config;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: PostgresCdcJob <spec-json>");
        }
        CdcSpec spec = CdcSpec.parse(args[0]);
        spec.requireVersionColumn();
        PostgresCdcJob action = new PostgresCdcJob(spec, Catalogs.restOptions(), postgres(spec));
        action.withPrimaryKeys(spec.primaryKeys);
        action.withTableConfig(spec.tableOptions);
        action.run();
    }
}
