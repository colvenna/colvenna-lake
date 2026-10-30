package com.colvenna.lake.jobs.cdc;

import com.colvenna.lake.jobs.Catalogs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.cdc.connectors.base.options.StartupOptions;
import org.apache.flink.cdc.connectors.mongodb.source.MongoDBSource;
import org.apache.paimon.flink.action.MultiTablesSinkMode;
import org.apache.paimon.flink.action.cdc.CdcSourceRecord;
import org.apache.paimon.flink.action.cdc.mongodb.MongoDBSyncDatabaseAction;
import org.apache.paimon.flink.action.cdc.serialization.CdcDebeziumDeserializationSchema;
import org.apache.paimon.flink.sink.cdc.RichCdcMultiplexRecord;

/**
 * A declared MongoDB source (HLD 7.2): Paimon's MongoDB sync-database action over exactly the
 * collections colvenna-operator admitted, each into a table keyed by {@code _id} with every other
 * top-level field a STRING, committing through colvenna-catalog with per-table data tokens.
 *
 * <p>The source is built here rather than by Paimon, for settings it does not make: the
 * newly-added-table scan stays on so that a job restored with fewer collections drops them from its
 * stream (colvenna-operator adds none to a running job: AddedAfterStart), the connection is TLS
 * against the JVM trust store, which the pod's init container extended with the source's CA, and
 * heartbeats keep the resume token moving. Deletes carry only the key and updates no before image,
 * so the targets keep a lookup changelog (colvenna-operator sets it) and downstream readers get
 * full before images from the rows the table held.
 */
public final class MongoCdcJob extends MongoDBSyncDatabaseAction {
    static final int HEARTBEAT_MILLIS = 30_000;

    private final CdcSpec spec;

    private MongoCdcJob(CdcSpec spec, Map<String, String> catalog, Map<String, String> mongodb) {
        super(spec.targetDatabase, catalog, mongodb);
        this.spec = spec;
    }

    @Override
    protected MongoDBSource<CdcSourceRecord> buildSource() {
        validateRuntimeExecutionMode();
        List<String> collections = new ArrayList<>();
        for (String table : spec.tables) {
            collections.add(spec.database + "." + table);
        }
        Map<String, Object> converter = new HashMap<>();
        converter.put("decimal.format", "numeric");
        return MongoDBSource.<CdcSourceRecord>builder()
                .hosts(spec.host + ":" + spec.port)
                .username(Catalogs.required("COLVENNA_SOURCE_USER"))
                .password(Catalogs.required("COLVENNA_SOURCE_PASSWORD"))
                .connectionOptions(connectionOptions(spec))
                .databaseList(spec.database)
                .collectionList(collections.toArray(new String[0]))
                .startupOptions(StartupOptions.initial())
                .scanNewlyAddedTableEnabled(true)
                // The resume token moves with the oplog even while the collections are quiet,
                // so a checkpoint never holds one the oplog has already dropped.
                .heartbeatIntervalMillis(HEARTBEAT_MILLIS)
                // As Paimon's own MongoDB source: Debezium's JSON without its schema.
                .deserializer(new CdcDebeziumDeserializationSchema(false, converter))
                .build();
    }

    @Override
    protected FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> recordParse() {
        return new MongoChangeGuard(super.recordParse());
    }

    /** TLS always; the user's database for authentication (colvenna-operator always names it). */
    static String connectionOptions(CdcSpec spec) {
        if (spec.authSource == null) {
            throw new IllegalArgumentException("A MongoDB source needs authSource");
        }
        return "tls=true&authSource=" + spec.authSource;
    }

    /** What Paimon's action reads of the source besides the source itself, which is built here. */
    static Map<String, String> mongodb(CdcSpec spec) {
        Map<String, String> config = new HashMap<>();
        config.put("hosts", spec.host + ":" + spec.port);
        config.put("database", spec.database);
        // _id as the ObjectId's hex, not {"$oid": ...}.
        config.put("default.id.generation", "true");
        return config;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: MongoCdcJob <spec-json>");
        }
        CdcSpec spec = CdcSpec.parse(args[0]);
        MongoCdcJob action = new MongoCdcJob(spec, Catalogs.restOptions(), mongodb(spec));
        action.withMode(MultiTablesSinkMode.COMBINED);
        action.includingTables(spec.tableRegex());
        action.withTableConfig(spec.tableOptions);
        action.run();
    }
}
