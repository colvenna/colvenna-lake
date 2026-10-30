package com.colvenna.lake.operator.source;

import com.colvenna.lake.operator.Durations;
import com.colvenna.lake.operator.Names;
import com.colvenna.lake.operator.OperatorSettings;
import com.colvenna.lake.operator.catalog.CatalogIdentities;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceSpec;
import com.colvenna.lake.operator.crd.LakeSourceStatus;
import com.colvenna.lake.operator.crd.LakeSourceStatus.SourceTable;
import com.colvenna.lake.operator.flink.FlinkJobs;
import com.colvenna.lake.operator.governance.GovernancePolicy;
import com.colvenna.lake.operator.lake.TableOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.EnvVar;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;

/**
 * The jobs a source runs, built from its status: one database job for MySQL (HLD 7.3), one job and
 * replication slot per table for PostgreSQL. Everything that differs by source type is here.
 */
public final class SourceJobs {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OperatorSettings settings;
    private final FlinkJobs jobs;

    SourceJobs(OperatorSettings settings) {
        this.settings = settings;
        this.jobs = new FlinkJobs(settings);
    }

    /** One desired job: its deployment, the tables it carries and, for PostgreSQL, its slot. */
    record Desired(FlinkDeployment deployment, List<String> tables, String slot) {
        String name() {
            return deployment.getMetadata().getName();
        }
    }

    /** What every job of the source shares, from this round. */
    record Context(
            boolean suspended,
            String credentialsVersion,
            String caVersion,
            Map<String, FlinkDeployment> existing) {}

    public static boolean isPostgres(LakeSourceSpec spec) {
        return spec.type.equals("postgres");
    }

    public static boolean isMongo(LakeSourceSpec spec) {
        return spec.type.equals("mongodb");
    }

    static boolean isMySql(LakeSourceSpec spec) {
        return spec.type.equals("mysql");
    }

    public static boolean isKafka(LakeSourceSpec spec) {
        return spec.type.equals("kafka");
    }

    /** A database source whose changes come from the user's Kafka. */
    public static boolean viaKafka(LakeSourceSpec spec) {
        return spec.transport != null && spec.transport.kafka != null;
    }

    /** The user and password the jobs use: the database's, or the Kafka cluster's. */
    static LakeSourceSpec.SecretRef credentials(LakeSourceSpec spec) {
        if (isKafka(spec)) {
            return spec.kafka.credentialsSecret;
        }
        return viaKafka(spec)
                ? spec.transport.kafka.credentialsSecret
                : spec.connection.credentialsSecret;
    }

    /** The ConfigMap with the CA bundle of what the jobs connect to, or null. */
    static String caConfigMap(LakeSourceSpec spec) {
        if (isKafka(spec)) {
            return spec.kafka.caConfigMap;
        }
        return viaKafka(spec) ? spec.transport.kafka.caConfigMap : spec.connection.caConfigMap;
    }

    public static int defaultPort(LakeSourceSpec spec) {
        return isMySql(spec) ? 3306 : isMongo(spec) ? 27017 : 5432;
    }

    /** The jobs for the tables in the job, keyed by deployment name. */
    Map<String, Desired> desired(
            LakeSource source, LakeSourceStatus status, List<SourceTable> inJob, Context ctx) {
        Map<String, Desired> out = new LinkedHashMap<>();
        if (isKafka(source.getSpec())) {
            String name = kafkaJobName(source);
            out.put(
                    name,
                    new Desired(
                            kafka(source, name, ctx),
                            List.of(
                                    source.getSpec().target.table,
                                    KafkaTables.rejections(source.getSpec())),
                            null));
        } else if (inJob.isEmpty()) {
            return out;
        } else if (viaKafka(source.getSpec())) {
            List<SourceTable> tables =
                    inJob.stream().sorted(Comparator.comparing(t -> t.name)).toList();
            String name = kafkaCdcJobName(source);
            out.put(
                    name,
                    new Desired(
                            kafkaCdc(source, tables, name, ctx),
                            tables.stream().map(t -> t.name).toList(),
                            null));
        } else if (isMySql(source.getSpec()) || isMongo(source.getSpec())) {
            // One job reads the database's admitted tables (MySQL) or collections (MongoDB).
            List<String> tables = inJob.stream().map(t -> t.name).sorted().toList();
            String name = databaseJobName(source);
            out.put(
                    name,
                    new Desired(
                            isMongo(source.getSpec())
                                    ? mongodb(source, tables, name, ctx)
                                    : mysql(source, status, tables, name, ctx),
                            tables,
                            null));
        } else {
            for (SourceTable t : inJob) {
                String name = postgresJobName(source, t.name);
                String slot = slotName(source, t.name);
                out.put(
                        name,
                        new Desired(
                                postgres(source, status, t, name, slot, ctx),
                                List.of(t.name),
                                slot));
            }
        }
        return out;
    }

    static String kafkaJobName(LakeSource source) {
        return FlinkJobs.name(source.getMetadata().getName(), "events");
    }

    /** The consumer group the job commits its offsets under, for lag; one per declaration. */
    static String consumerGroup(LakeSource source) {
        return "colvenna."
                + source.getMetadata().getNamespace()
                + "."
                + source.getMetadata().getName()
                + "."
                + source.getMetadata().getUid().replace("-", "").substring(0, 8);
    }

    private FlinkDeployment kafka(LakeSource source, String name, Context ctx) {
        LakeSourceSpec spec = source.getSpec();
        // A refused change of the fields leaves the job on the declaration last applied.
        LakeSourceSpec.Events events =
                source.getStatus() != null && source.getStatus().appliedEvents != null
                        ? source.getStatus().appliedEvents
                        : spec.events;
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("bootstrap", spec.kafka.bootstrapServers);
        job.put("topics", spec.kafka.topics);
        job.put("group", consumerGroup(source));
        job.put("mechanism", mechanism(spec.kafka.mechanism));
        if (spec.kafka.caConfigMap != null) {
            job.put("caFile", FlinkJobs.SOURCE_CA_FILE);
        }
        job.put(
                "startingOffsets",
                spec.kafka.startingOffsets == null ? "earliest" : spec.kafka.startingOffsets);
        job.put("database", spec.target.database);
        job.put("table", spec.target.table);
        job.put("rejections", KafkaTables.rejections(spec));
        List<Map<String, Object>> fields = new ArrayList<>();
        for (LakeSourceSpec.Field f : events.fields) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", f.name);
            field.put("type", f.type);
            field.put("required", Boolean.TRUE.equals(f.required));
            if (f.maxLength != null) {
                field.put("maxLength", f.maxLength);
            }
            if (f.values != null && !f.values.isEmpty()) {
                field.put("values", f.values);
            }
            if (f.min != null) {
                field.put("min", f.min);
            }
            if (f.max != null) {
                field.put("max", f.max);
            }
            fields.add(field);
        }
        job.put("fields", fields);
        job.put(
                "eventTime",
                Map.of(
                        "field",
                        events.eventTime.field,
                        "format",
                        events.eventTime.format == null ? "rfc3339" : events.eventTime.format));
        job.put("maxOutOfOrdernessMs", Durations.millis(events.maxOutOfOrderness, "5m"));
        job.put("idlenessMs", Durations.millis(events.idleness, "1m"));
        job.put("acceptPastMs", Durations.millis(events.acceptPast, "30d"));
        job.put("acceptFutureMs", Durations.millis(events.acceptFuture, "1d"));
        job.put("unknownFields", events.unknownFields == null ? "keep" : events.unknownFields);
        job.put("partitioning", KafkaTables.hourly(spec) ? "hourly" : "daily");
        return withKafkaIdentity(
                jobs.build(
                        source,
                        jobSpec(
                                source,
                                name,
                                "com.colvenna.lake.jobs.kafka.KafkaEventJob",
                                job,
                                Map.of("colvenna-lake.io/source", source.getMetadata().getName()),
                                ctx)),
                spec);
    }

    /** The deployment, its metadata naming what its restored offsets belong to. */
    static FlinkDeployment withKafkaIdentity(FlinkDeployment fd, LakeSourceSpec spec) {
        Map<String, String> annotations = new LinkedHashMap<>(fd.getMetadata().getAnnotations());
        annotations.put(KAFKA_IDENTITY, kafkaIdentity(spec));
        fd.getMetadata().setAnnotations(annotations);
        return fd;
    }

    /** The SASL mechanism, SCRAM-SHA-512 unless declared. */
    static String mechanism(String declared) {
        return declared == null ? "SCRAM-SHA-512" : declared;
    }

    /**
     * What a Kafka job's restored offsets mean something for: its cluster and topics. When they
     * change the job is recreated, from what its tables hold, instead of restored.
     */
    static final String KAFKA_IDENTITY = "colvenna-lake.io/kafka-identity";

    static String kafkaIdentity(LakeSourceSpec spec) {
        if (viaKafka(spec)) {
            // A transport's topics follow its tables; restored offsets keep their meaning as long
            // as the cluster and the connector's prefix stay.
            return FlinkJobs.sha256(
                            spec.transport.kafka.bootstrapServers
                                    + "|"
                                    + spec.transport.kafka.topicPrefix)
                    .substring(0, 16);
        }
        List<String> topics = new ArrayList<>(spec.kafka.topics);
        Collections.sort(topics);
        return FlinkJobs.sha256(spec.kafka.bootstrapServers + "|" + String.join(",", topics))
                .substring(0, 16);
    }

    /** A name of its own: a direct job's checkpoints mean nothing to it. */
    static String kafkaCdcJobName(LakeSource source) {
        return FlinkJobs.name(source.getMetadata().getName(), "kafka");
    }

    /** MongoDB's database of the user, unless declared. */
    static String authSource(LakeSourceSpec spec) {
        return spec.connection.authSource == null ? "admin" : spec.connection.authSource;
    }

    /** How far back a MongoDB source's oplog must reach, unless declared (CRD default too). */
    static final String DEFAULT_OPLOG_WINDOW = "24h";

    /** The Kafka transport's default overlap (CRD default too). */
    static final String DEFAULT_OVERLAP = "10m";

    /** {@code <prefix>.<database>.<table>}, as Debezium's MySQL connector names it. */
    public static String topic(LakeSourceSpec spec, String table) {
        return spec.transport.kafka.topicPrefix + "." + spec.connection.database + "." + table;
    }

    private FlinkDeployment kafkaCdc(
            LakeSource source, List<SourceTable> tables, String name, Context ctx) {
        LakeSourceSpec spec = source.getSpec();
        LakeSourceSpec.KafkaTransport k = spec.transport.kafka;
        Map<String, Object> job = common(spec);
        job.put("tables", tables.stream().map(t -> t.name).toList());
        // The Kafka CA, not the database's: the job reads only Kafka.
        job.remove("sourceCa");
        Map<String, Object> kafka = new LinkedHashMap<>();
        kafka.put("bootstrap", k.bootstrapServers);
        Map<String, String> targets = new LinkedHashMap<>();
        tables.forEach(t -> targets.put(topic(spec, t.name), t.target));
        kafka.put("targets", targets);
        kafka.put("group", consumerGroup(source));
        kafka.put("mechanism", k.mechanism == null ? "SCRAM-SHA-512" : k.mechanism);
        if (k.caConfigMap != null) {
            kafka.put("caFile", FlinkJobs.SOURCE_CA_FILE);
        }
        kafka.put("overlapMillis", Durations.millis(k.overlap, DEFAULT_OVERLAP));
        job.put("kafka", kafka);
        return withKafkaIdentity(
                jobs.build(
                        source,
                        jobSpec(
                                source,
                                name,
                                "com.colvenna.lake.jobs.cdc.KafkaCdcJob",
                                job,
                                Map.of("colvenna-lake.io/source", source.getMetadata().getName()),
                                ctx)),
                spec);
    }

    /** PostgreSQL's limit on a replication slot's name (NAMEDATALEN - 1). */
    static final int MAX_SLOT_NAME = 63;

    static String databaseJobName(LakeSource source) {
        return FlinkJobs.name(source.getMetadata().getName(), "cdc");
    }

    static String postgresJobName(LakeSource source, String table) {
        return FlinkJobs.name(source.getMetadata().getName(), table.replace('_', '-'));
    }

    /**
     * {@code colvenna_<uid prefix>_<table>}: PostgreSQL keeps 63 characters of a name, so a longer
     * one is cut short with a hash of the whole (Names.fit), and two tables never share a slot.
     */
    static String slotName(LakeSource source, String table) {
        String uid = source.getMetadata().getUid().replace("-", "").substring(0, 8);
        return Names.fit(
                ("colvenna_" + uid + "_" + table).toLowerCase(Locale.ROOT), MAX_SLOT_NAME, '_');
    }

    private FlinkDeployment mysql(
            LakeSource source,
            LakeSourceStatus status,
            List<String> tables,
            String name,
            Context ctx) {
        LakeSourceSpec spec = source.getSpec();
        Map<String, Object> job = common(spec);
        job.put("serverId", status.serverIdRange);
        if (spec.connection.serverTimeZone != null) {
            job.put("serverTimeZone", spec.connection.serverTimeZone);
        }
        job.put("tables", tables);
        return jobs.build(
                source,
                jobSpec(
                        source,
                        name,
                        "com.colvenna.lake.jobs.cdc.MySqlCdcJob",
                        job,
                        Map.of("colvenna-lake.io/source", source.getMetadata().getName()),
                        ctx));
    }

    private FlinkDeployment mongodb(
            LakeSource source, List<String> collections, String name, Context ctx) {
        LakeSourceSpec spec = source.getSpec();
        Map<String, Object> job = common(spec);
        job.put("tables", collections);
        job.put("authSource", authSource(spec));
        return jobs.build(
                source,
                jobSpec(
                        source,
                        name,
                        "com.colvenna.lake.jobs.cdc.MongoCdcJob",
                        job,
                        Map.of("colvenna-lake.io/source", source.getMetadata().getName()),
                        ctx));
    }

    private FlinkDeployment postgres(
            LakeSource source,
            LakeSourceStatus status,
            SourceTable table,
            String name,
            String slot,
            Context ctx) {
        LakeSourceSpec spec = source.getSpec();
        Map<String, Object> job = common(spec);
        job.put("targetTable", table.target);
        job.put("schema", spec.connection.schema);
        job.put("slot", slot);
        job.put("publication", spec.connection.publication);
        job.put("tables", table.readTables.isEmpty() ? List.of(table.name) : table.readTables);
        job.put("primaryKeys", table.primaryKey);
        return jobs.build(
                source,
                jobSpec(
                        source,
                        name,
                        "com.colvenna.lake.jobs.cdc.PostgresCdcJob",
                        job,
                        Map.of(
                                "colvenna-lake.io/source",
                                source.getMetadata().getName(),
                                "colvenna-lake.io/table",
                                table.name),
                        ctx));
    }

    private static Map<String, Object> common(LakeSourceSpec spec) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("targetDatabase", spec.target.database);
        job.put("host", spec.connection.host);
        job.put("port", spec.connection.port == null ? defaultPort(spec) : spec.connection.port);
        job.put("database", spec.connection.database);
        if (versionColumn(spec) != null) {
            job.put("versionColumn", versionColumn(spec));
        }
        if (spec.connection.caConfigMap != null) {
            job.put("sourceCa", FlinkJobs.SOURCE_CA_FILE);
        }
        job.put("tableOptions", tableOptions(spec));
        return job;
    }

    private FlinkJobs.Job jobSpec(
            LakeSource source,
            String name,
            String entry,
            Map<String, Object> arg,
            Map<String, String> labels,
            Context ctx) {
        LakeSourceSpec spec = source.getSpec();
        LakeSourceSpec.Job job = spec.job == null ? new LakeSourceSpec.Job() : spec.job;
        LakeSourceSpec.SecretRef credentials = credentials(spec);
        List<EnvVar> env =
                List.of(
                        FlinkJobs.secretEnv(
                                "COLVENNA_SOURCE_USER",
                                credentials.name,
                                credentials.usernameKey == null
                                        ? "username"
                                        : credentials.usernameKey),
                        FlinkJobs.secretEnv(
                                "COLVENNA_SOURCE_PASSWORD",
                                credentials.name,
                                credentials.passwordKey == null
                                        ? "password"
                                        : credentials.passwordKey));
        Map<String, String> annotations = new LinkedHashMap<>();
        // A new token, password or CA reaches the job only through a restart.
        annotations.put("colvenna-lake.io/token-id", source.getStatus().identity.tokenId);
        annotations.put("colvenna-lake.io/credentials-version", ctx.credentialsVersion());
        if (ctx.caVersion() != null) {
            annotations.put("colvenna-lake.io/source-ca-version", ctx.caVersion());
        }
        int declared = job.parallelism == null ? 1 : job.parallelism;
        FlinkDeployment existing = ctx.existing().get(name);
        return new FlinkJobs.Job(
                name,
                // The Kafka transport's job needs the Kafka connector, not the database's.
                settings.jobImage(viaKafka(spec) ? "kafka" : spec.type),
                entry,
                List.of(json(arg)),
                // A Kafka source's offsets follow any parallelism; a CDC source's readers do not.
                existing == null || isKafka(spec)
                        ? declared
                        : deployedParallelism(existing, declared),
                job.checkpointInterval == null ? "30s" : job.checkpointInterval,
                ctx.suspended(),
                FlinkJobs.resource(
                        job.jobManager == null ? null : job.jobManager.cpu,
                        job.jobManager == null ? null : job.jobManager.memory,
                        1.0,
                        "2048m"),
                FlinkJobs.resource(
                        job.taskManager == null ? null : job.taskManager.cpu,
                        job.taskManager == null ? null : job.taskManager.memory,
                        1.0,
                        "3072m"),
                env,
                CatalogIdentities.tokenSecretName(source),
                annotations,
                labels,
                FlinkJobs.quietLogging(),
                caConfigMap(spec),
                FlinkJobs.StatePolicy.LAST_STATE);
    }

    /**
     * The parallelism a job keeps once deployed. Paimon 2.0.0's CDC operators have no stable ids,
     * so a new parallelism re-chains them under new ids and the checkpoint no longer restores
     * ("There is no operator for the state ..."); the job would stay down. Measured on EKS.
     */
    private static int deployedParallelism(FlinkDeployment existing, int declared) {
        if (existing.getSpec() == null || existing.getSpec().getJob() == null) {
            return declared;
        }
        return existing.getSpec().getJob().getParallelism();
    }

    static Map<String, String> tableOptions(LakeSourceSpec spec) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put(
                "bucket", Integer.toString(spec.target.bucket == null ? 1 : spec.target.bucket));
        options.put("merge-engine", "deduplicate");
        options.putAll(changelogOptions(spec));
        if (versionColumn(spec) != null) {
            options.put("sequence.field", versionColumn(spec));
        }
        options.put(TableOptions.CONSUMER_EXPIRATION, TableOptions.DEFAULT_CONSUMER_EXPIRATION);
        if (spec.target.tableOptions != null) {
            options.putAll(new TreeMap<>(spec.target.tableOptions));
        }
        options.putAll(
                TableOptions.icebergDefaults(spec.target.tableOptions, writerCompacts(spec)));
        // Writers of a lookup changelog compact; full compactions drop deleted rows from files.
        options.putAll(
                GovernancePolicy.options(
                        spec.governance, spec.target.tableOptions, writerCompacts(spec)));
        return options;
    }

    /**
     * How the targets keep their changelog. From the database's log every change arrives once and
     * in order, so the input is the changelog and the writer leaves compaction to maintenance.
     * Kafka may repeat or reorder changes: the changelog is computed from the rows the table held
     * (lookup) and leaves out a row written again unchanged, so a repeated change leaves no trace;
     * that needs the writer to compact.
     */
    static Map<String, String> changelogOptions(LakeSourceSpec spec) {
        return writerCompacts(spec)
                ? Map.of(
                        "changelog-producer",
                        "lookup",
                        "changelog-producer.row-deduplicate",
                        "true",
                        "write-only",
                        "false")
                : Map.of("changelog-producer", "input", "write-only", "true");
    }

    /**
     * Whether the writer compacts its targets: a lookup changelog needs it. MongoDB deletes carry
     * only the key and updates no before image: the changelog's before images come from the rows
     * the table held, as for Kafka.
     */
    public static boolean writerCompacts(LakeSourceSpec spec) {
        return viaKafka(spec) || isMongo(spec);
    }

    /** The version column, or null for MongoDB: its change stream is ordered, and has none. */
    public static String versionColumn(LakeSourceSpec spec) {
        if (isMongo(spec)) {
            return null;
        }
        return spec.target.versionColumn == null ? "version_no" : spec.target.versionColumn;
    }

    /** The tables a deployment carries, from its job argument. */
    public static List<String> tablesOf(FlinkDeployment fd) {
        try {
            JsonNode spec = JSON.readTree(fd.getSpec().getJob().getArgs()[0]);
            List<String> tables = new ArrayList<>();
            if (spec.has("targetTable")) {
                tables.add(spec.path("targetTable").asText());
            } else {
                spec.path("tables").forEach(t -> tables.add(t.asText()));
            }
            return tables;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** The replication slot a PostgreSQL deployment reads, or null. */
    static String slotOf(FlinkDeployment fd) {
        try {
            return JSON.readTree(fd.getSpec().getJob().getArgs()[0]).path("slot").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
