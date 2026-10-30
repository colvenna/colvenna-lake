package com.colvenna.lake.operator.lineage;

import com.colvenna.lake.operator.crd.LakeDashboard;
import com.colvenna.lake.operator.crd.LakeDashboardSpec;
import com.colvenna.lake.operator.crd.LakeDashboardStatus;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceSpec;
import com.colvenna.lake.operator.crd.LakeSourceStatus;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.crd.LakeTableStatus;
import com.colvenna.lake.operator.crd.LakeTask;
import com.colvenna.lake.operator.crd.LakeTaskStatus;
import com.colvenna.lake.operator.dashboard.DashboardSql;
import com.colvenna.lake.operator.flink.FlinkJobs;
import com.colvenna.lake.operator.flink.JobObservation;
import com.colvenna.lake.operator.source.SourceJobs;
import com.colvenna.lake.operator.table.TableJobs;
import io.fabric8.kubernetes.api.model.HasMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;

/**
 * Which jobs of a namespace read and write which datasets, as the operator knows them (LLD "血缘"):
 * each Flink deployment of a source or a layered table, each attempt of a backfill, each Doris
 * object of a dashboard. What a source job reads and writes is what it is declared and admitted to
 * carry; what a layered table's job reads is what the planner found its query reads. A run is one
 * version of a job running: a Flink deployment's spec hash and its Flink job id, a backfill's
 * attempt, a dashboard query's applied hash. Nothing that moves no rows between datasets
 * (maintenance, deletes, ad-hoc queries) is lineage.
 */
public final class LineageGraph {
    private LineageGraph() {}

    public static final String START = "START";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETE = "COMPLETE";
    public static final String FAIL = "FAIL";
    static final String PLANNER = "planner";
    static final String DECLARED = "declared";
    private static final String COLVENNA_LAKE = "colvenna-lake.io/v1alpha1";

    /** The OpenLineage namespaces of the lake (and its catalog's URI) and of Doris. */
    public record Naming(String paimon, String catalogUri, String doris) {
        /** The OpenLineage namespace of a Kubernetes namespace's jobs. */
        public static String jobNamespace(String namespace) {
            return "colvenna-lake." + namespace;
        }
    }

    /** A column as the authoritative catalog has it. */
    public record Column(String name, String type, String description) {}

    /**
     * A dataset by OpenLineage's namespace and name, with its type (TABLE, VIEW, TOPIC) and
     * subtype; a lake table with its columns as colvenna-catalog has them, and the catalog's URI.
     */
    public record Dataset(
            String namespace,
            String name,
            String type,
            String subType,
            List<Column> columns,
            String catalogUri) {}

    /** What OpenLineage's jobType facet says of a job: how it runs, on what, what it is. */
    public record JobType(boolean streaming, String integration, String type) {}

    /**
     * A job: its name, what kind it is, the version it runs and where that version is declared, and
     * the SQL it runs if it has one.
     */
    public record Job(
            String name,
            JobType kind,
            String version,
            String location,
            String sql,
            String dialect) {}

    /**
     * One run of a job: its id, state (RUNNING, COMPLETE, FAIL), when it started and ended (each
     * possibly unknown), what it read and wrote, what ran it, and its engine.
     */
    public record Run(
            Job job,
            String id,
            String state,
            Instant startedAt,
            Instant endedAt,
            List<Dataset> inputs,
            List<Dataset> outputs,
            Map<String, String> facts,
            String engine,
            String engineVersion) {}

    /**
     * What the graph shows: the runs it knows, and the jobs in transition (an upgrade under way, a
     * deployment the status does not describe yet) whose last reported run stands meanwhile.
     */
    public record Graph(List<Run> runs, Set<String> unsettled) {}

    /** One namespace's declarations and deployments, as the operator lists them. */
    public record Namespace(
            String name,
            List<LakeSource> sources,
            List<LakeTable> tables,
            List<LakeTask> tasks,
            List<LakeDashboard> dashboards,
            List<FlinkDeployment> deployments) {}

    public static Graph of(
            Namespace ns, Function<String, Optional<List<Column>>> catalog, Naming naming) {
        Builder b = new Builder(ns, catalog, naming);
        ns.sources().forEach(b::source);
        ns.tables().forEach(b::table);
        ns.tasks().forEach(b::task);
        ns.dashboards().forEach(b::dashboard);
        return new Graph(b.runs, b.unsettled);
    }

    /** A run id that is the same for the same run whenever it is computed. */
    static String runId(String... parts) {
        return UUID.nameUUIDFromBytes(String.join("\n", parts).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    /** OpenLineage's namespace of a database source: its scheme, host and port. */
    static String sourceNamespace(LakeSourceSpec spec) {
        int port =
                spec.connection.port != null ? spec.connection.port : SourceJobs.defaultPort(spec);
        return spec.type + "://" + spec.connection.host + ":" + port;
    }

    /**
     * OpenLineage's name of a source table: database.table (MySQL, a MongoDB collection),
     * database.schema.table (PostgreSQL).
     */
    static String sourceName(LakeSourceSpec spec, String table) {
        return SourceJobs.isPostgres(spec)
                ? spec.connection.database + "." + spec.connection.schema + "." + table
                : spec.connection.database + "." + table;
    }

    /** OpenLineage's namespace of a Kafka cluster: its first bootstrap server. */
    static String kafkaNamespace(String bootstrapServers) {
        int comma = bootstrapServers.indexOf(',');
        return "kafka://"
                + (comma < 0 ? bootstrapServers : bootstrapServers.substring(0, comma)).trim();
    }

    private static final class Builder {
        final Namespace ns;
        final Function<String, Optional<List<Column>>> catalog;
        final Naming naming;
        final List<Run> runs = new ArrayList<>();
        final Set<String> unsettled = new HashSet<>();

        Builder(Namespace ns, Function<String, Optional<List<Column>>> catalog, Naming naming) {
            this.ns = ns;
            this.catalog = catalog;
            this.naming = naming;
        }

        /** A lake table ({@code database.table}) as a dataset, with what colvenna-catalog has. */
        Dataset lake(String table) {
            return new Dataset(
                    naming.paimon(),
                    "colvenna." + table,
                    "TABLE",
                    null,
                    catalog.apply(table).orElse(List.of()),
                    naming.catalogUri());
        }

        static Dataset external(String namespace, String name, String type) {
            return new Dataset(namespace, name, type, null, List.of(), null);
        }

        List<FlinkDeployment> owned(HasMetadata owner) {
            List<FlinkDeployment> out = new ArrayList<>();
            for (FlinkDeployment fd : ns.deployments()) {
                if (fd.getMetadata().getOwnerReferences().stream()
                        .anyMatch(o -> o.getUid().equals(owner.getMetadata().getUid()))) {
                    out.add(fd);
                }
            }
            return out;
        }

        /**
         * A run's facts: what ran it, and how its datasets are known: {@code planner} (the tables
         * the planner found the query reads) or {@code declared} (the tables the declaration names,
         * which its catalog identity alone may read or write).
         */
        static Map<String, String> facts(HasMetadata owner, String provenance) {
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("provenance", provenance);
            facts.put("kind", owner.getKind());
            facts.put("name", owner.getMetadata().getName());
            facts.put("uid", owner.getMetadata().getUid());
            if (owner.getMetadata().getGeneration() != null) {
                facts.put("generation", owner.getMetadata().getGeneration().toString());
            }
            return facts;
        }

        /**
         * Where a version of a job is declared: its object in the Kubernetes API, at that version
         * (an https URL: Marquez keeps no other kind, and versions jobs by it).
         */
        String location(String api, String plural, String name, String version) {
            return "https://kubernetes.default.svc/apis/"
                    + api
                    + "/namespaces/"
                    + ns.name()
                    + "/"
                    + plural
                    + "/"
                    + name
                    + "?version="
                    + version;
        }

        /**
         * A Flink deployment's run: running the spec the operator last wrote (its hash the version,
         * with the Flink job id), or failed; suspended, it has none; anything else is a transition.
         * Its SQL is the query its job argument holds: what runs, not what is declared.
         */
        void flink(
                HasMetadata owner,
                FlinkDeployment fd,
                String provenance,
                List<Dataset> inputs,
                List<Dataset> outputs) {
            String name = fd.getMetadata().getName();
            String hash = FlinkJobs.hashOf(fd);
            var job = fd.getStatus() == null ? null : fd.getStatus().getJobStatus();
            String jobId = job == null ? null : job.getJobId();
            String jobState = job == null || job.getState() == null ? null : job.getState().name();
            String state;
            if (JobObservation.live(fd) && jobId != null) {
                state = RUNNING;
            } else if ("FAILED".equals(jobState) && jobId != null) {
                state = FAIL;
            } else {
                if (!JobObservation.suspended(fd)) {
                    unsettled.add(name);
                }
                return;
            }
            Map<String, String> facts = facts(owner, provenance);
            facts.put("deployment", name);
            facts.put("specHash", hash);
            facts.put("flinkJobId", jobId);
            if (fd.getSpec().getImage() != null) {
                facts.put("image", fd.getSpec().getImage());
            }
            Instant started = null;
            if (job.getStartTime() != null && job.getStartTime().matches("\\d{1,18}")) {
                started = Instant.ofEpochMilli(Long.parseLong(job.getStartTime()));
            }
            String version =
                    fd.getSpec().getFlinkVersion() == null
                            ? null
                            : fd.getSpec().getFlinkVersion().name().substring(1).replace('_', '.');
            String sql = owner instanceof LakeTable ? TableJobs.queryOf(fd) : null;
            runs.add(
                    new Run(
                            new Job(
                                    name,
                                    new JobType(true, "FLINK", "JOB"),
                                    hash,
                                    location(
                                            "flink.apache.org/v1beta1",
                                            "flinkdeployments",
                                            name,
                                            hash),
                                    sql,
                                    sql == null ? null : "flink"),
                            runId(ns.name(), fd.getMetadata().getUid(), hash, jobId),
                            state,
                            started,
                            null,
                            inputs,
                            outputs,
                            facts,
                            "flink",
                            version));
        }

        /**
         * A source's deployments: each reads the source tables it carries (or their Kafka topics)
         * and writes their ODS tables; a Kafka source's job reads its topics and writes its events
         * and rejections tables.
         */
        void source(LakeSource s) {
            LakeSourceSpec spec = s.getSpec();
            LakeSourceStatus status = s.getStatus();
            if (spec == null || status == null) {
                return;
            }
            String db = spec.target.database;
            for (FlinkDeployment fd : owned(s)) {
                List<Dataset> inputs = new ArrayList<>();
                List<Dataset> outputs = new ArrayList<>();
                if (SourceJobs.isKafka(spec)) {
                    for (String topic : spec.kafka.topics) {
                        inputs.add(
                                external(
                                        kafkaNamespace(spec.kafka.bootstrapServers),
                                        topic,
                                        "TOPIC"));
                    }
                    outputs.add(lake(db + "." + spec.target.table));
                    outputs.add(lake(db + "." + spec.target.table + "_rejections"));
                } else {
                    for (String table : SourceJobs.tablesOf(fd)) {
                        Optional<LakeSourceStatus.SourceTable> t =
                                status.tables.stream()
                                        .filter(x -> table.equals(x.name) && x.target != null)
                                        .findFirst();
                        if (t.isEmpty()) {
                            continue;
                        }
                        inputs.add(
                                SourceJobs.viaKafka(spec)
                                        ? external(
                                                kafkaNamespace(
                                                        spec.transport.kafka.bootstrapServers),
                                                SourceJobs.topic(spec, table),
                                                "TOPIC")
                                        : external(
                                                sourceNamespace(spec),
                                                sourceName(spec, table),
                                                "TABLE"));
                        outputs.add(lake(db + "." + t.get().target));
                    }
                }
                flink(s, fd, DECLARED, inputs, outputs);
            }
        }

        /**
         * A layered table's deployment: it reads the tables the planner found its applied query
         * streams and looks up, and writes its target and quarantine.
         */
        void table(LakeTable t) {
            LakeTableStatus.Applied applied = t.getStatus() == null ? null : t.getStatus().applied;
            for (FlinkDeployment fd : owned(t)) {
                if (applied == null || !applied.hash.equals(FlinkJobs.hashOf(fd))) {
                    // A deployment the status does not describe yet: an upgrade, a rollback.
                    unsettled.add(fd.getMetadata().getName());
                    continue;
                }
                flink(t, fd, PLANNER, reads(applied), writes(t, applied));
            }
        }

        List<Dataset> reads(LakeTableStatus.Applied applied) {
            TreeSet<String> tables = new TreeSet<>(applied.streams);
            tables.addAll(applied.lookups);
            List<Dataset> out = new ArrayList<>();
            tables.forEach(table -> out.add(lake(table)));
            return out;
        }

        List<Dataset> writes(LakeTable t, LakeTableStatus.Applied applied) {
            List<Dataset> out = new ArrayList<>();
            out.add(lake(applied.target));
            if (applied.quarantine != null) {
                out.add(lake(t.getSpec().target.database + "." + applied.quarantine));
            }
            return out;
        }

        /**
         * A backfill's attempts: each reads what its LakeTable's applied query reads and writes the
         * table (and its quarantine), and runs that query (its deployment's). An attempt starts
         * when the one before it ended. The other tasks maintain one table and move no rows.
         */
        void task(LakeTask task) {
            LakeTaskStatus status = task.getStatus();
            if (task.getSpec() == null
                    || !"backfill".equals(task.getSpec().type)
                    || status == null
                    || status.attempts == null) {
                return;
            }
            LakeTable layered =
                    TableJobs.layeredTable(task.getSpec().table.qualifiedName(), ns.tables());
            if (layered == null || layered.getStatus().applied == null) {
                return;
            }
            String sql =
                    owned(layered).stream()
                            .map(TableJobs::queryOf)
                            .filter(q -> q != null)
                            .findFirst()
                            .orElse(null);
            String uid = task.getMetadata().getUid();
            Job job =
                    new Job(
                            "LakeTask." + task.getMetadata().getName(),
                            new JobType(false, "FLINK", "JOB"),
                            uid,
                            location(COLVENNA_LAKE, "laketasks", task.getMetadata().getName(), uid),
                            sql,
                            sql == null ? null : "flink");
            Instant started = status.startedAt == null ? null : Instant.parse(status.startedAt);
            Set<Integer> ended = new HashSet<>();
            for (LakeTaskStatus.Attempt a : status.history) {
                ended.add(a.attempt);
                Instant at = a.finishedAt == null ? null : Instant.parse(a.finishedAt);
                String state = "Succeeded".equals(a.outcome) ? COMPLETE : FAIL;
                attempt(task, job, a.attempt, a.run, state, started, at, layered);
                started = at;
            }
            if (!ended.contains(status.attempts)
                    && ("Running".equals(status.phase) || "Converging".equals(status.phase))) {
                attempt(task, job, status.attempts, status.run, RUNNING, started, null, layered);
            }
        }

        void attempt(
                LakeTask task,
                Job job,
                int attempt,
                String run,
                String state,
                Instant started,
                Instant ended,
                LakeTable layered) {
            LakeTableStatus.Applied applied = layered.getStatus().applied;
            Map<String, String> facts = facts(task, PLANNER);
            facts.put("attempt", Integer.toString(attempt));
            if (run != null) {
                facts.put("kubernetesJob", run);
            }
            facts.put("lakeTable", layered.getMetadata().getName());
            facts.put("specHash", applied.hash);
            runs.add(
                    new Run(
                            job,
                            runId(
                                    ns.name(),
                                    task.getMetadata().getUid(),
                                    Integer.toString(attempt)),
                            state,
                            started,
                            ended,
                            reads(applied),
                            writes(layered, applied),
                            facts,
                            "flink",
                            null));
        }

        /**
         * A dashboard's Doris objects: each query as served reads the declared tables its SQL names
         * (its Doris catalog can read no others) and is a view, a materialized view or an internal
         * table of the dashboard's database.
         */
        void dashboard(LakeDashboard d) {
            LakeDashboardStatus status = d.getStatus();
            if (d.getSpec() == null || status == null) {
                return;
            }
            for (LakeDashboardSpec.Query q : d.getSpec().queries) {
                Optional<LakeDashboardStatus.QueryState> served = status.query(q.name);
                if (served.isEmpty()
                        || served.get().object == null
                        || served.get().applied == null) {
                    continue;
                }
                String applied = served.get().applied;
                DashboardSql.Serve serve =
                        DashboardSql.Serve.of(
                                served.get().serve == null ? q.serve : served.get().serve);
                List<Dataset> inputs = new ArrayList<>();
                for (String table : DashboardSql.tablesRead(q.sql, d.getSpec().tables)) {
                    inputs.add(lake(table));
                }
                Dataset object =
                        new Dataset(
                                naming.doris(),
                                "internal." + served.get().object,
                                serve == DashboardSql.Serve.INTERNAL ? "TABLE" : "VIEW",
                                serve == DashboardSql.Serve.MATERIALIZED ? "MATERIALIZED" : null,
                                List.of(),
                                null);
                Map<String, String> facts = facts(d, DECLARED);
                facts.put("query", q.name);
                facts.put("serve", serve.name().toLowerCase(java.util.Locale.ROOT));
                facts.put("applied", applied);
                runs.add(
                        new Run(
                                new Job(
                                        "LakeDashboard." + d.getMetadata().getName() + "." + q.name,
                                        new JobType(
                                                true,
                                                "DORIS",
                                                switch (serve) {
                                                    case EXTERNAL -> "VIEW";
                                                    case MATERIALIZED -> "MATERIALIZED_VIEW";
                                                    case INTERNAL -> "TABLE";
                                                }),
                                        applied,
                                        location(
                                                COLVENNA_LAKE,
                                                "lakedashboards",
                                                d.getMetadata().getName(),
                                                applied + "&query=" + q.name),
                                        q.sql,
                                        "doris"),
                                runId(ns.name(), d.getMetadata().getUid(), q.name, applied),
                                RUNNING,
                                null,
                                null,
                                inputs,
                                List.of(object),
                                facts,
                                "doris",
                                null));
            }
        }
    }
}
