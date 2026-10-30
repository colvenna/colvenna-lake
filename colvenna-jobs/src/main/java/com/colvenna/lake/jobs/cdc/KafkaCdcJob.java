package com.colvenna.lake.jobs.cdc;

import com.colvenna.lake.jobs.Catalogs;
import com.colvenna.lake.jobs.kafka.KafkaLogin;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.connector.source.Source;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.flink.action.MultiTablesSinkMode;
import org.apache.paimon.flink.action.cdc.CdcSourceRecord;
import org.apache.paimon.flink.action.cdc.kafka.KafkaSyncDatabaseAction;
import org.apache.paimon.flink.sink.cdc.RichCdcMultiplexRecord;
import org.apache.paimon.options.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A declared MySQL source whose changes come from the user's Kafka (HLD 7.6): Paimon's Kafka
 * sync-database action over the admitted tables' Debezium JSON topics, into the same target tables
 * a direct source writes, so a source can move from its database's log to Kafka without its readers
 * noticing.
 *
 * <p>Kafka may repeat and reorder a row's changes, so the targets order by the version column and
 * keep a deduplicated lookup changelog (colvenna-operator sets both), and {@link KafkaEventGuard}
 * drops what it knows to be stale: a change seen again leaves neither the table nor its changelog
 * different. A job without state starts an overlap before the targets' last write ({@link
 * KafkaStart}, {@link TargetHistory}), never at the consumer group's offsets: those are committed
 * apart from the tables and are kept for the lag. The version guard still stops the job on a change
 * that breaks the source contract.
 */
public final class KafkaCdcJob extends KafkaSyncDatabaseAction {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaCdcJob.class);

    private final CdcSpec spec;
    private final TargetHistory history;
    private final KafkaStart.Positions positions;
    private final int unverified;

    private KafkaCdcJob(
            CdcSpec spec,
            Map<String, String> catalog,
            TargetHistory history,
            KafkaStart.Positions positions,
            int unverified) {
        super(spec.targetDatabase, catalog, actionConfig(spec));
        this.spec = spec;
        this.history = history;
        this.positions = positions;
        this.unverified = unverified;
    }

    @Override
    protected Source<CdcSourceRecord, ?, ?> buildSource() {
        CdcSpec.Kafka k = spec.kafka;
        return KafkaSource.<CdcSourceRecord>builder()
                .setTopics(k.topics())
                // Offsets committed under the group show the lag; they decide nothing.
                .setGroupId(k.group)
                .setStartingOffsets(positions)
                .setDeserializer(new DebeziumRecords())
                .setProperties(KafkaLogin.properties(k.bootstrap, k.mechanism, k.caFile))
                .build();
    }

    @Override
    protected FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> recordParse() {
        FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> parser = super.recordParse();
        return new KafkaEventGuard(
                parser,
                new SourceVersionGuard(parser, spec.versionColumn, List.of(), false),
                spec.versionColumn,
                spec.kafka.targets,
                history,
                unverified);
    }

    /**
     * What Paimon's action reads of Kafka: the topics and their format. No credentials: this job
     * builds the source itself.
     */
    static Map<String, String> actionConfig(CdcSpec spec) {
        Map<String, String> config = new HashMap<>();
        config.put("properties.bootstrap.servers", spec.kafka.bootstrap);
        config.put("topic", String.join(";", spec.kafka.topics()));
        config.put("properties.group.id", spec.kafka.group);
        config.put("value.format", "debezium-json");
        return config;
    }

    /** The topics whose targets have been written: those start an overlap before the last write. */
    static Set<String> writtenTopics(CdcSpec.Kafka kafka, TargetHistory history) {
        Set<String> out = new HashSet<>();
        kafka.targets.forEach(
                (topic, target) -> {
                    if (history.written.contains(target)) {
                        out.add(topic);
                    }
                });
        return out;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: KafkaCdcJob <spec-json>");
        }
        CdcSpec spec = CdcSpec.parse(args[0]);
        spec.requireVersionColumn();
        if (spec.kafka == null) {
            throw new IllegalArgumentException("KafkaCdcJob needs kafka settings");
        }
        Map<String, String> catalogOptions = Catalogs.restOptions();
        // Every start of the job comes here, restores included; a failure to work out where to
        // start is held until the source needs a start (KafkaStart.Positions).
        TargetHistory history = TargetHistory.none();
        KafkaStart.Positions positions;
        int unverified = 0;
        // Before anything is written: a target the direct job left is compacted, then switched.
        try (Catalog catalog =
                CatalogFactory.createCatalog(
                        CatalogContext.create(Options.fromMap(catalogOptions)))) {
            for (String target : spec.kafka.targets.values()) {
                TargetSwitch.apply(catalog, Identifier.create(spec.targetDatabase, target));
            }
        }
        try {
            try (Catalog catalog =
                    CatalogFactory.createCatalog(
                            CatalogContext.create(Options.fromMap(catalogOptions)))) {
                history =
                        TargetHistory.read(
                                catalog,
                                spec.targetDatabase,
                                new ArrayList<>(spec.kafka.targets.values()),
                                spec.versionColumn,
                                spec.kafka.overlapMillis);
            }
            Long from =
                    history.lastWriteMillis == null
                            ? null
                            : history.lastWriteMillis - spec.kafka.overlapMillis;
            KafkaStart.Offsets offsets =
                    KafkaStart.read(
                            KafkaLogin.properties(
                                    spec.kafka.bootstrap, spec.kafka.mechanism, spec.kafka.caFile),
                            spec.kafka.topics(),
                            writtenTopics(spec.kafka, history),
                            from);
            positions = KafkaStart.Positions.of(offsets.start);
            unverified = offsets.unverified.size();
            LOG.info(
                    "KAFKA_CDC_START lastWrite={} from={} written={} deletedRows={} offsets={}",
                    history.lastWriteMillis,
                    from,
                    history.written,
                    history.deleted.size(),
                    offsets.start);
            if (!offsets.unverified.isEmpty()) {
                LOG.warn(
                        "KAFKA_CDC_START_UNVERIFIED partitions={}: retention removed records and"
                                + " none before {} is left; changes the previous writer had not"
                                + " written may be missing",
                        offsets.unverified,
                        from);
            }
        } catch (Exception e) {
            String failure = e.getMessage() == null ? e.toString() : e.getMessage();
            LOG.warn("KAFKA_CDC_START_UNKNOWN {}", failure);
            positions = KafkaStart.Positions.unknown(failure);
        }
        KafkaCdcJob action = new KafkaCdcJob(spec, catalogOptions, history, positions, unverified);
        action.withMode(MultiTablesSinkMode.COMBINED);
        action.includingTables(spec.tableRegex());
        action.withTableConfig(spec.tableOptions);
        action.run();
    }
}
