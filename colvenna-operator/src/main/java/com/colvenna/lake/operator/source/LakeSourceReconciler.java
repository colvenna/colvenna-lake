package com.colvenna.lake.operator.source;

import com.colvenna.lake.operator.Durations;
import com.colvenna.lake.operator.OperatorSettings;
import com.colvenna.lake.operator.catalog.CatalogClient;
import com.colvenna.lake.operator.catalog.CatalogException;
import com.colvenna.lake.operator.catalog.CatalogIdentities;
import com.colvenna.lake.operator.catalog.Grant;
import com.colvenna.lake.operator.catalog.Owners;
import com.colvenna.lake.operator.catalog.Publication;
import com.colvenna.lake.operator.crd.CatalogIdentity;
import com.colvenna.lake.operator.crd.Governance;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceSpec;
import com.colvenna.lake.operator.crd.LakeSourceStatus;
import com.colvenna.lake.operator.crd.LakeSourceStatus.JobState;
import com.colvenna.lake.operator.crd.LakeSourceStatus.SourceTable;
import com.colvenna.lake.operator.crd.StatusCondition;
import com.colvenna.lake.operator.flink.FlinkJobs;
import com.colvenna.lake.operator.flink.FlinkRest;
import com.colvenna.lake.operator.flink.JobObservation;
import com.colvenna.lake.operator.governance.GovernancePolicy;
import com.colvenna.lake.operator.kube.Creation;
import com.colvenna.lake.operator.kube.Events;
import com.colvenna.lake.operator.lake.Backlog;
import com.colvenna.lake.operator.lake.LakeReader;
import com.colvenna.lake.operator.lake.TableOptions;
import com.colvenna.lake.operator.metrics.Metrics;
import com.colvenna.lake.operator.quality.QualityGates;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.Mappers;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps a {@link LakeSource} converged: its catalog identity, its ingestion job(s), and each
 * matched table's state, from admission through a reconciled snapshot. See {@code
 * docs/design/colvenna-operator-lld.md}, "LakeSource".
 */
@ControllerConfiguration(name = "lakesource")
public final class LakeSourceReconciler implements Reconciler<LakeSource>, Cleaner<LakeSource> {
    private static final Logger LOG = LoggerFactory.getLogger(LakeSourceReconciler.class);
    public static final String KIND = "lakesource";
    static final Duration RESYNC = Duration.ofSeconds(30);
    static final Duration RECONCILE_EVERY = Duration.ofSeconds(30);

    /** How far the source's clock may be off the operator's for a heartbeat to be a sample. */
    static final Duration SKEW_TOLERANCE = Duration.ofSeconds(5);

    /** Heartbeats waiting to be seen in the lake, at most. */
    static final int PENDING_BEATS = 20;

    static final Duration STUCK_AFTER = Duration.ofMinutes(3);
    static final Duration SLOT_RELEASE = Duration.ofMinutes(5);
    private static final Pattern PAIMON_TABLE =
            Pattern.compile("of Paimon table ([A-Za-z0-9_]+)\\.([A-Za-z0-9_]+)");
    private static final Pattern GUARD_TABLE =
            Pattern.compile(
                    "(?:NON_MONOTONIC_SOURCE_VERSION|SOURCE_[A-Z_]+) table=([A-Za-z0-9_]+)");

    /** MySQL server-id blocks allocated but maybe not yet in any status the cache shows. */
    private static final Map<String, String> SERVER_IDS = new ConcurrentHashMap<>();

    private final OperatorSettings settings;
    private final KubernetesClient kube;
    private final CatalogClient catalog;
    private final CatalogIdentities identities;
    private final Publication publication;
    private final Retention retention;
    private final LakeReader lake;
    private final Backlog backlog;
    private final Map<String, SourceProbe> probes;
    private final SourceJobs jobs;
    private final FlinkRest flinkRest;
    private final Events events;
    private final Metrics metrics;
    private final Supplier<Instant> clock;
    private final KafkaSources kafka;

    /** When each table was last reconciled; in memory, so a restart only re-checks sooner. */
    private final Map<String, Instant> lastChecked = new ConcurrentHashMap<>();

    /**
     * Per source uid and table, the keys a repair deletes. In memory only (keys can be personal
     * data); after a restart the tables are reconciled again instead.
     */
    private final Map<String, Map<String, Set<String>>> repairs = new ConcurrentHashMap<>();

    /** First reconciliation reads waiting for their second, by source uid and table. */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /**
     * Per table, the keys the last judgement left unexplained: a key unexplained in two judgements
     * in a row is not in flight (#238). Kept in memory only: keys can be personal data.
     */
    private final Map<String, Set<String>> gaps = new ConcurrentHashMap<>();

    /** Per table, the lasting gap last warned about (RowsMissing): a Warning once per gap. */
    private final Map<String, Set<String>> warnedGaps = new ConcurrentHashMap<>();

    /** Per table, the source read whose stalled wait was last reported: once per read. */
    private final Map<String, Instant> stallsReported = new ConcurrentHashMap<>();

    /**
     * How long past the catch-up a live job's target may go without a commit before a Warning
     * (#245: a table stayed Snapshotting, with nothing but its status to tell why).
     */
    static final Duration STALLED = Duration.ofMinutes(10);

    /** When each deployment was first seen stuck after an upgrade. */
    private final Map<String, Instant> stuckSince = new ConcurrentHashMap<>();

    public LakeSourceReconciler(
            OperatorSettings settings,
            KubernetesClient kube,
            CatalogClient catalog,
            LakeReader lake,
            Map<String, SourceProbe> probes,
            FlinkRest flinkRest,
            Metrics metrics,
            Supplier<Instant> clock) {
        this.settings = settings;
        this.kube = kube;
        this.catalog = catalog;
        this.identities = new CatalogIdentities(catalog, kube);
        this.publication = new Publication(catalog);
        this.lake = lake;
        this.backlog = new Backlog(lake);
        this.probes = probes;
        this.jobs = new SourceJobs(settings);
        this.retention = new Retention(kube);
        this.flinkRest = flinkRest;
        this.events = new Events(kube);
        this.metrics = metrics;
        this.clock = clock;
        this.kafka =
                new KafkaSources(
                        this, kube, lake, new KafkaProbe(), events, settings.reconcileMaxRows());
    }

    @Override
    public List<EventSource<?, LakeSource>> prepareEventSources(
            EventSourceContext<LakeSource> context) {
        var config =
                InformerEventSourceConfiguration.from(FlinkDeployment.class, LakeSource.class)
                        .withSecondaryToPrimaryMapper(Mappers.fromOwnerReferences(LakeSource.class))
                        .withLabelSelector(FlinkJobs.MANAGED_BY + "=colvenna-operator")
                        .build();
        return List.of(new InformerEventSource<>(config, context));
    }

    /** What one round knows; passed to each step instead of a long parameter list. */
    record Round(
            LakeSource source,
            LakeSourceSpec spec,
            LakeSourceStatus status,
            long generation,
            Instant now) {
        String namespace() {
            return source.getMetadata().getNamespace();
        }

        boolean condition(String type, boolean ok, String reason, String message) {
            return StatusCondition.set(
                    status.conditions, type, ok, reason, message, generation, now);
        }
    }

    /** One probe: the target it reached (null without credentials) and what it read. */
    private record Probe(
            SourceProbe.Target target,
            SourceModel.Inventory inventory,
            boolean prerequisitesBroken) {}

    @Override
    public UpdateControl<LakeSource> reconcile(LakeSource source, Context<LakeSource> context) {
        LakeSourceStatus status =
                source.getStatus() == null ? new LakeSourceStatus() : source.getStatus();
        source.setStatus(status);
        Round r =
                new Round(
                        source,
                        source.getSpec(),
                        status,
                        Objects.requireNonNullElse(source.getMetadata().getGeneration(), 0L),
                        clock.get());
        status.observedGeneration = r.generation();

        String invalid = validate(r.spec());
        if (invalid != null) {
            return failed(r, "InvalidSpec", invalid);
        }
        String conflict = conflict(source, context);
        if (conflict != null) {
            return failed(r, "TargetConflict", conflict);
        }
        r.condition("SpecValid", true, "Valid", "the declaration is valid");

        // The catalog side: the target database, owned by this source alone, and its identity.
        boolean isolated;
        try {
            String owner = Owners.source(source);
            catalog.ensureDatabase(r.spec().target.database, Map.of(Owners.OPTION, owner));
            String actual =
                    catalog.databaseOptions(r.spec().target.database)
                            .map(o -> o.get(Owners.OPTION))
                            .orElse(null);
            if (!owner.equals(actual)) {
                return failed(
                        r,
                        "TargetConflict",
                        "target database "
                                + r.spec().target.database
                                + " exists and is not this source's (owner "
                                + actual
                                + ")");
            }
            String principal = CatalogIdentities.principalName(KIND, source);
            status.identity =
                    identities.ensure(
                            source,
                            KIND,
                            "LakeSource " + r.namespace() + "/" + source.getMetadata().getName(),
                            List.of(
                                    Grant.database(
                                            principal,
                                            r.spec().target.database,
                                            // The CDC action creates tables and evolves schemas.
                                            "MANAGE",
                                            null,
                                            source.getMetadata().getUid(),
                                            r.now())),
                            status.identity,
                            r.now());
            r.condition("CatalogReady", true, "Ready", "identity, token and grants are current");
            isolated = isolation(r, principal);
        } catch (CatalogException e) {
            if (r.condition("CatalogReady", false, "CatalogUnavailable", e.getMessage())) {
                events.warning(source, "CatalogUnavailable", e.getMessage());
            }
            status.phase = "Degraded";
            status.message = "colvenna-catalog: " + e.getMessage();
            publish(source, status);
            return UpdateControl.patchStatus(source).rescheduleAfter(RESYNC);
        }

        if (SourceJobs.isKafka(r.spec())) {
            return kafka.reconcile(r, context);
        }
        Map<String, String> failures = attributeFailures(r);
        Probe probe = probe(r);
        if (probe.inventory() != null) {
            plan(r, probe, isolated, failures);
        } else {
            for (SourceTable t : status.tables) {
                String failure = failures.get(t.name);
                if (failure != null && Enrollment.inJob(t)) {
                    Enrollment.fail(t, "JobFailed", failure, r.now())
                            .ifPresent(tr -> events.warning(source, "TableFailed", describe(tr)));
                }
            }
        }

        // The jobs follow the status even when the source cannot be probed, so a suspension, a
        // removed table or a new password still reaches them.
        List<String> pinned = applyJobs(r, context, probe.prerequisitesBroken());
        if (pinned.isEmpty()) {
            r.condition("SpecApplied", true, "Applied", "the jobs run the declaration");
        } else if (r.condition(
                "SpecApplied",
                false,
                "ParallelismPinned",
                String.join("; ", pinned)
                        + ": a running CDC job cannot change parallelism and keep its state;"
                        + " recreate the source to change it")) {
            events.warning(source, "ParallelismPinned", String.join("; ", pinned));
        }
        releaseSlots(r, probe.target());
        if (SourceJobs.viaKafka(r.spec())) {
            kafka.probeTransport(
                    r, status.tables.stream().filter(Enrollment::inJob).map(t -> t.name).toList());
        }
        observeJobs(r, probe.prerequisitesBroken());
        repairDeletes(r);
        if (probe.inventory() != null) {
            reconcileTables(r, probe);
        }
        govern(r, probe);
        retention.keep(r.source(), r.now());
        heartbeat(r);
        finishRotation(r);
        summarize(r);
        status.measuredAt = r.now().toString();
        publish(source, status);
        return UpdateControl.patchStatus(source).rescheduleAfter(RESYNC);
    }

    UpdateControl<LakeSource> failed(Round r, String reason, String message) {
        if (r.condition("SpecValid", false, reason, message)) {
            events.warning(r.source(), reason, message);
        }
        r.status().phase = "Failed";
        r.status().message = message;
        // Nothing is measured, and ColvennaDegraded says why.
        r.status().measuredAt = null;
        publish(r.source(), r.status());
        return UpdateControl.patchStatus(r.source());
    }

    // ---------------------------------------------------------------- validation and catalog

    static String validate(LakeSourceSpec spec) {
        if (spec != null && spec.transport != null) {
            if (spec.type == null || !SourceJobs.isMySql(spec) || spec.transport.kafka == null) {
                return "spec.transport.kafka is for mysql sources";
            }
            long overlap =
                    Durations.millis(spec.transport.kafka.overlap, SourceJobs.DEFAULT_OVERLAP);
            if (overlap < 60_000L || overlap > 86_400_000L) {
                return "spec.transport.kafka.overlap must be 1m to 24h";
            }
        }
        if (spec != null && spec.target != null && SourceJobs.isKafka(spec)) {
            String kafka = KafkaSources.validate(spec);
            return kafka != null
                    ? kafka
                    : TableOptions.refusal("spec.target.tableOptions", spec.target.tableOptions);
        }
        if (spec == null || spec.connection == null || spec.target == null) {
            return "spec.connection and spec.target are required";
        }
        if (SourceJobs.isMongo(spec)
                && (spec.connection.database == null || spec.target.versionColumn != null)) {
            return "a mongodb source names connection.database and no target.versionColumn: its"
                    + " collections are keyed by _id and ordered by the change stream";
        }
        if (spec.tables != null) {
            for (String p : concat(spec.tables.include, spec.tables.exclude)) {
                try {
                    Pattern.compile(p);
                } catch (PatternSyntaxException e) {
                    return "spec.tables: invalid regular expression " + p;
                }
            }
        }
        String options = TableOptions.refusal("spec.target.tableOptions", spec.target.tableOptions);
        if (options != null) {
            return options;
        }
        return null;
    }

    /** Another source writing the same target database would share its identity's MANAGE grant. */
    private static String conflict(LakeSource source, Context<LakeSource> context) {
        for (LakeSource other : context.getPrimaryCache().list().toList()) {
            if (!other.getMetadata().getUid().equals(source.getMetadata().getUid())
                    && other.getSpec() != null
                    && other.getSpec().target != null
                    && source.getSpec().target.database.equals(other.getSpec().target.database)
                    && Creation.before(other, source)) {
                return "target database "
                        + source.getSpec().target.database
                        + " already belongs to LakeSource "
                        + other.getMetadata().getNamespace()
                        + "/"
                        + other.getMetadata().getName();
            }
        }
        return null;
    }

    /**
     * Whether only this source (and admin platform identities) can read the target database: the
     * isolated ingest zone HLD 7.3 and 12.5 require before tables are enrolled automatically.
     * Table-level grants (publishing one reviewed table) keep it isolated for new tables.
     */
    private boolean isolation(Round r, String principal) {
        Set<String> admins = catalog.adminPrincipals();
        Set<String> readers = new LinkedHashSet<>();
        for (Grant g : catalog.allGrants()) {
            if (!g.principal().equals(principal)
                    && !admins.contains(g.principal())
                    && g.readsDatabase(r.spec().target.database, r.now())) {
                readers.add(g.principal() + " (" + g.scope() + " " + g.privilege() + ")");
            }
        }
        if (readers.isEmpty()) {
            r.condition(
                    "TargetIsolated",
                    true,
                    "Isolated",
                    "only this source reads the target database");
            return true;
        }
        String message =
                "the target database is readable by "
                        + String.join(", ", readers)
                        + "; new tables are not enrolled until only table-level grants remain";
        if (r.condition("TargetIsolated", false, "TargetReadable", message)) {
            events.warning(r.source(), "TargetReadable", message);
        }
        return false;
    }

    // ---------------------------------------------------------------- source

    private Probe probe(Round r) {
        SourceProbe.Target target;
        try {
            target = SourceTargets.target(kube, r.source());
        } catch (SourceTargets.CaMissing e) {
            sourceCondition(r, false, "CaMissing", e.getMessage());
            // Nothing was checked against a CA that could not be read.
            r.condition("SourceVerified", false, "CaMissing", e.getMessage());
            return new Probe(null, null, false);
        } catch (IllegalStateException e) {
            sourceCondition(r, false, "CredentialsMissing", e.getMessage());
            return new Probe(null, null, false);
        }
        sourceVerified(r, events, target.caFile() != null);
        try {
            SourceModel.Inventory inventory = probes.get(r.spec().type).inventory(target);
            if (!inventory.ready()) {
                // Definite, unlike an unreachable source: the jobs stop until it is fixed.
                sourceCondition(
                        r,
                        false,
                        "SourcePrerequisites",
                        inventory.failedChecks() + "; the jobs are suspended until this is fixed");
                return new Probe(target, inventory, true);
            }
            sourceCondition(
                    r, true, "Reachable", inventory.tables().size() + " tables in the source");
            if (inventory.retention() != null) {
                retention(r, inventory.retention());
            }
            return new Probe(target, inventory, false);
        } catch (SQLException e) {
            sourceCondition(r, false, "SourceUnreachable", e.getMessage());
            return new Probe(target, null, false);
        }
    }

    /**
     * SourceVerified: how the probe and the jobs check the source's certificate, with a Warning
     * when they do not. Without a declared CA, MySQL ({@code sslMode=REQUIRED}) and PostgreSQL
     * ({@code sslmode=require}) encrypt but verify nothing; the MongoDB and Kafka clients verify
     * the certificate and host name against the JVM's default trust store, which the operator and
     * the jobs make the JDK's CAs plus colvenna-catalog's. {@code caDeclared}: the declared CA was
     * read.
     */
    static void sourceVerified(Round r, Events events, boolean caDeclared) {
        String caField =
                SourceJobs.isKafka(r.spec()) ? "kafka.caConfigMap" : "connection.caConfigMap";
        if (caDeclared) {
            r.condition(
                    "SourceVerified",
                    true,
                    "Verified",
                    "server certificate and host verified against "
                            + caField
                            + (SourceJobs.isPostgres(r.spec())
                                    ? ", except Paimon's schema reads (they take no SSL options)"
                                    : ""));
        } else if (SourceJobs.isMongo(r.spec()) || SourceJobs.isKafka(r.spec())) {
            r.condition(
                    "SourceVerified",
                    true,
                    "DefaultTrustStore",
                    "server certificate and host verified against the JVM's default trust store"
                            + " (the JDK's CAs and colvenna-catalog's); for a CA outside it, set "
                            + caField);
        } else if (r.condition(
                "SourceVerified",
                false,
                "NoCaConfigured",
                "TLS is required but the server certificate is not verified; set "
                        + "connection.caConfigMap")) {
            events.warning(
                    r.source(),
                    "TlsNotVerified",
                    "set connection.caConfigMap to verify the source's certificate");
        }
    }

    /**
     * How far back the source's change log reaches, against the declared minimum: a job down longer
     * cannot resume. Short or unknown raises a Warning; the jobs keep running.
     */
    private void retention(Round r, SourceModel.Retention retention) {
        LakeSourceStatus.Retention state = new LakeSourceStatus.Retention();
        state.windowHours =
                retention.windowHours() == null
                        ? null
                        : Math.round(retention.windowHours() * 10) / 10.0;
        state.minimumHours =
                Durations.millis(
                                r.spec().connection.minOplogWindow, SourceJobs.DEFAULT_OPLOG_WINDOW)
                        / 3_600_000.0;
        state.detail = retention.detail();
        state.checkedAt = r.now().toString();
        r.status().retention = state;
        boolean ok = state.windowHours != null && state.windowHours >= state.minimumHours;
        String message =
                state.windowHours == null
                        ? "the oplog's reach is unknown: " + state.detail
                        : "the oplog reaches back "
                                + state.windowHours
                                + "h (at least "
                                + state.minimumHours
                                + "h declared); a job down longer cannot resume";
        String reason = ok ? "Sufficient" : state.windowHours == null ? "Unknown" : "Short";
        if (r.condition("RetentionWindow", ok, reason, message) && !ok) {
            events.warning(r.source(), "RetentionWindow" + reason, message);
        }
    }

    private void sourceCondition(Round r, boolean ok, String reason, String message) {
        if (r.condition("SourceReady", ok, reason, message) && !ok) {
            events.warning(r.source(), reason, message);
        }
    }

    private void plan(Round r, Probe probe, boolean isolated, Map<String, String> failures) {
        LakeSourceSpec spec = r.spec();
        List<String> include = spec.tables == null ? List.of() : spec.tables.include;
        List<String> exclude = spec.tables == null ? List.of() : spec.tables.exclude;
        Map<String, SourceModel.Table> selected = new TreeMap<>();
        probe.inventory()
                .tables()
                .forEach(
                        (name, table) -> {
                            if (Enrollment.selected(name, include, exclude)) {
                                selected.put(name, table);
                            }
                        });
        List<String> inJob =
                r.status().tables.stream().filter(Enrollment::inJob).map(t -> t.name).toList();
        if (!inJob.isEmpty() && inJob.stream().noneMatch(selected::containsKey)) {
            // Every table in the job gone at once looks like lost privileges or a changed
            // database rather than dropped tables: keep the job and its state.
            sourceCondition(
                    r,
                    false,
                    "TablesVanished",
                    "none of the tables in the job is visible in the source any more; the job is"
                            + " kept as it is");
            return;
        }
        retire(r.status().tables, selected.keySet(), r.status().retired);
        for (String name : inJob) {
            if (!probe.inventory().tables().containsKey(name)) {
                // Dropped or renamed at the source: its target keeps what it had, and is told.
                events.warning(
                        r.source(),
                        "TableGone",
                        name
                                + " is no longer in the source; it leaves the job and its target"
                                + " keeps the rows it had");
            }
        }
        Map<String, Admission.Verdict> verdicts = new HashMap<>();
        selected.forEach(
                (name, table) ->
                        verdicts.put(
                                name,
                                Admission.check(spec.type, table, SourceJobs.versionColumn(spec))));
        Admission.Verdict gate = null;
        if (probe.prerequisitesBroken()) {
            gate =
                    new Admission.Verdict(
                            false, "SourcePrerequisites", probe.inventory().failedChecks());
        } else if (!isolated) {
            gate =
                    new Admission.Verdict(
                            false,
                            "TargetNotIsolated",
                            "the target database is readable beyond this source (see condition"
                                    + " TargetIsolated)");
        } else {
            gate = addedAfterStart(spec, inJob);
        }
        Enrollment.Plan plan =
                Enrollment.plan(
                        r.status().tables,
                        selected,
                        verdicts,
                        targets(spec.target.database, selected.keySet()),
                        failures,
                        gate,
                        r.now());
        for (Enrollment.Transition t : plan.transitions()) {
            if (t.warning()) {
                events.warning(r.source(), "Table" + t.to(), describe(t));
            } else {
                events.normal(r.source(), "Table" + t.to(), describe(t));
            }
        }
        r.status().tables = new ArrayList<>(plan.tables());
    }

    /**
     * Records the targets of tables leaving the status (gone from the source or from the selection,
     * failed or not; a rejected one has none of its own): they stay this source's, for a Delete to
     * drop.
     */
    static void retire(List<SourceTable> tables, Set<String> selected, List<String> retired) {
        for (SourceTable t : tables) {
            if (!Enrollment.REJECTED.equals(t.state)
                    && !selected.contains(t.name)
                    && t.target != null
                    && !retired.contains(t.target)) {
                retired.add(t.target);
            }
        }
    }

    /**
     * Why a collection may not join a MongoDB source's running job, or null. Flink CDC 3.5 adds a
     * collection to a running job through its newly-added-table process; a failover in that
     * process's last step (the job restarting from an earlier checkpoint while the enumerator
     * stays) leaves the stream without the collection, whose changes are then dropped silently
     * (FLINK-40741, fixed upstream for MySQL only). Paimon's committer fails the job once on
     * purpose right after a restore, so that is the usual case. The collections a job starts with
     * are snapshotted on the initial path, which a failover does not break.
     */
    static Admission.Verdict addedAfterStart(LakeSourceSpec spec, List<String> inJob) {
        if (!SourceJobs.isMongo(spec) || inJob.isEmpty()) {
            return null;
        }
        return new Admission.Verdict(
                false,
                "AddedAfterStart",
                "the job already runs "
                        + inJob
                        + "; a collection is not added to a running job (Flink CDC can lose its"
                        + " changes after a failover, FLINK-40741). Declare a LakeSource for it");
    }

    private static String describe(Enrollment.Transition t) {
        return t.table()
                + ": "
                + (t.from() == null ? "" : t.from() + " -> ")
                + t.to()
                + " ("
                + t.reason()
                + ") "
                + t.message();
    }

    /** The existing target tables among these: their keys and whether they hold data. */
    private Map<String, Enrollment.Target> targets(String database, Set<String> tables) {
        Map<String, Enrollment.Target> out = new HashMap<>();
        List<String> existing;
        try {
            existing = catalog.tables(database);
        } catch (CatalogException e) {
            return out;
        }
        for (String table : tables) {
            if (existing.contains(table)) {
                catalog.table(database, table)
                        .ifPresent(
                                t -> {
                                    List<String> keys = new ArrayList<>();
                                    t.path("schema")
                                            .path("primaryKeys")
                                            .forEach(k -> keys.add(k.asText()));
                                    out.put(
                                            table,
                                            new Enrollment.Target(
                                                    keys, catalog.hasSnapshot(database, table)));
                                });
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- jobs

    /** Applies the jobs; returns the ones whose declared parallelism could not apply. */
    List<String> applyJobs(Round r, Context<LakeSource> context, boolean prerequisitesBroken) {
        LakeSource source = r.source();
        LakeSourceStatus status = r.status();
        var deployments = kube.resources(FlinkDeployment.class).inNamespace(r.namespace());
        Map<String, FlinkDeployment> existing = new HashMap<>();
        for (FlinkDeployment fd : context.getSecondaryResources(FlinkDeployment.class)) {
            existing.put(fd.getMetadata().getName(), fd);
        }
        List<SourceTable> inJob = status.tables.stream().filter(Enrollment::inJob).toList();
        if (SourceJobs.isMySql(r.spec()) && !inJob.isEmpty()) {
            status.serverIdRange = serverIds(source, status, context);
        }
        String caVersion = null;
        String caConfigMap = SourceJobs.caConfigMap(r.spec());
        if (caConfigMap != null) {
            ConfigMap ca = kube.configMaps().inNamespace(r.namespace()).withName(caConfigMap).get();
            caVersion = ca == null ? null : ca.getMetadata().getResourceVersion();
        }
        String credentials;
        try {
            credentials =
                    SourceTargets.secret(kube, source, SourceJobs.credentials(r.spec()).name)
                            .getMetadata()
                            .getResourceVersion();
        } catch (IllegalStateException e) {
            // Without the Secret the jobs cannot start; leave them as they are.
            return List.of();
        }
        if (SourceJobs.viaKafka(r.spec())) {
            // The direct job writes the same tables: it goes, and its last commit lands, before
            // the Kafka job starts from where the tables stopped.
            String kafkaJob = SourceJobs.kafkaCdcJobName(source);
            List<String> direct =
                    existing.keySet().stream().filter(n -> !n.equals(kafkaJob)).sorted().toList();
            if (!direct.isEmpty()) {
                for (String name : direct) {
                    if (existing.get(name).getMetadata().getDeletionTimestamp() == null
                            && !deployments.withName(name).delete().isEmpty()) {
                        events.normal(
                                source,
                                "JobDeleted",
                                name + " hands its tables to the Kafka transport");
                    }
                }
                return List.of();
            }
            // The Kafka job compacts a target the direct job left, then switches its changelog.
            for (SourceTable t : inJob) {
                observeChangelog(r, t);
            }
        }
        Map<String, SourceJobs.Desired> desired =
                jobs.desired(
                        source,
                        status,
                        inJob,
                        new SourceJobs.Context(
                                Boolean.TRUE.equals(r.spec().suspend)
                                        || prerequisitesBroken
                                        || status.repair != null,
                                credentials,
                                caVersion,
                                existing));
        Map<String, JobState> known = new HashMap<>();
        for (JobState j : status.jobs) {
            known.put(j.deployment, j);
        }
        List<JobState> states = new ArrayList<>();
        for (SourceJobs.Desired d : desired.values()) {
            FlinkDeployment fd = existing.get(d.name());
            if (fd == null) {
                // Not in the informer cache yet, or really absent.
                fd = deployments.withName(d.name()).get();
            }
            // A Kafka job starts from its tables: an event source after the offsets they hold, a
            // transport an overlap before their last write (what it rereads is absorbed).
            boolean kafka = SourceJobs.isKafka(r.spec()) || SourceJobs.viaKafka(r.spec());
            if (fd == null && kafka) {
                deployments.resource(d.deployment()).create();
                JobState was = known.get(d.name());
                events.normal(
                        source,
                        was != null && was.jobId != null ? "JobRecreated" : "JobCreated",
                        d.name()
                                + (SourceJobs.isKafka(r.spec())
                                        ? " continues after the offsets its tables hold"
                                        : " reads from an overlap before its tables' last write"));
            } else if (fd == null) {
                JobState was = known.get(d.name());
                if (was != null && was.jobId != null) {
                    lost(
                            r,
                            d,
                            "the job "
                                    + d.name()
                                    + " was deleted outside the operator and its state is gone");
                    continue;
                }
                List<String> withData =
                        d.tables().stream()
                                .filter(t -> catalog.hasSnapshot(r.spec().target.database, t))
                                .toList();
                if (!withData.isEmpty()) {
                    lost(r, d, "a new job would snapshot into targets that hold data: " + withData);
                    continue;
                }
                deployments.resource(d.deployment()).create();
                events.normal(source, "JobCreated", d.name() + " for " + d.tables());
            } else if ((SourceJobs.isKafka(r.spec()) || SourceJobs.viaKafka(r.spec()))
                    && !Objects.equals(
                            annotation(fd, SourceJobs.KAFKA_IDENTITY),
                            annotation(d.deployment(), SourceJobs.KAFKA_IDENTITY))) {
                // Offsets of other topics or another cluster: a new job starts from the tables.
                if (fd.getMetadata().getDeletionTimestamp() == null
                        && !deployments.withName(d.name()).delete().isEmpty()) {
                    events.normal(
                            source,
                            "JobDeleted",
                            d.name()
                                    + " reads other topics or another cluster; it starts again"
                                    + " from the offsets its tables hold");
                }
            } else if (!FlinkJobs.current(fd, d.deployment())) {
                fd.setSpec(d.deployment().getSpec());
                fd.getMetadata().setAnnotations(d.deployment().getMetadata().getAnnotations());
                fd.getMetadata().setLabels(d.deployment().getMetadata().getLabels());
                deployments.resource(fd).update();
                events.normal(source, "JobUpdated", d.name() + " now runs " + d.tables());
            }
            JobState state = known.getOrDefault(d.name(), new JobState());
            state.deployment = d.name();
            state.tables = d.tables();
            states.add(state);
        }
        for (FlinkDeployment fd : existing.values()) {
            // The informer cache can still show a deployment just deleted.
            if (!desired.containsKey(fd.getMetadata().getName())
                    && fd.getMetadata().getDeletionTimestamp() == null
                    && !deployments.withName(fd.getMetadata().getName()).delete().isEmpty()) {
                events.normal(
                        source, "JobDeleted", fd.getMetadata().getName() + " has no tables left");
                String slot = SourceJobs.slotOf(fd);
                if (slot != null && !status.pendingSlots.contains(slot)) {
                    status.pendingSlots.add(slot);
                }
            }
        }
        status.jobs = states;

        int declared =
                r.spec().job == null || r.spec().job.parallelism == null
                        ? 1
                        : r.spec().job.parallelism;
        List<String> pinned = new ArrayList<>();
        for (SourceJobs.Desired d : desired.values()) {
            int running = d.deployment().getSpec().getJob().getParallelism();
            if (running != declared) {
                pinned.add(
                        d.name()
                                + " keeps parallelism "
                                + running
                                + " (declared "
                                + declared
                                + ")");
            }
        }
        return pinned;
    }

    private static String annotation(FlinkDeployment fd, String key) {
        Map<String, String> annotations = fd.getMetadata().getAnnotations();
        return annotations == null ? null : annotations.get(key);
    }

    /** Records the target's changelog producer, and when the Kafka job has switched it. */
    private void observeChangelog(Round r, SourceTable t) {
        try {
            String producer =
                    lake.table(r.spec().target.database, t.target)
                            .map(info -> info.options().get("changelog-producer"))
                            .orElse(null);
            if ("lookup".equals(producer) && t.changelog != null && !"lookup".equals(t.changelog)) {
                events.normal(
                        r.source(),
                        "ChangelogSwitched",
                        t.target
                                + " was compacted and moved from the "
                                + t.changelog
                                + " to the lookup changelog");
            }
            if (producer != null) {
                t.changelog = producer;
            }
        } catch (Exception e) {
            throw new CatalogException("table options of " + t.target + ": " + e.getMessage(), e);
        }
    }

    /** The job's state is gone: its tables leave the job rather than snapshot again. */
    private void lost(Round r, SourceJobs.Desired d, String message) {
        for (SourceTable t : r.status().tables) {
            if (d.tables().contains(t.name) && Enrollment.inJob(t)) {
                Enrollment.fail(t, "JobStateLost", message, r.now())
                        .ifPresent(tr -> events.warning(r.source(), "TableFailed", describe(tr)));
            }
        }
        events.warning(r.source(), "JobLost", message);
    }

    /** A free server-id block for the job's readers, kept once allocated. */
    private String serverIds(
            LakeSource source, LakeSourceStatus status, Context<LakeSource> context) {
        int needed =
                source.getSpec().job == null || source.getSpec().job.parallelism == null
                        ? 1
                        : source.getSpec().job.parallelism;
        String uid = source.getMetadata().getUid();
        String current = status.serverIdRange != null ? status.serverIdRange : SERVER_IDS.get(uid);
        if (current != null && size(current) >= needed) {
            return current;
        }
        // Allocations of other sources, from their status or from rounds not yet in it.
        synchronized (SERVER_IDS) {
            List<int[]> used = new ArrayList<>();
            for (LakeSource other : context.getPrimaryCache().list().toList()) {
                String range =
                        other.getStatus() != null && other.getStatus().serverIdRange != null
                                ? other.getStatus().serverIdRange
                                : SERVER_IDS.get(other.getMetadata().getUid());
                if (!other.getMetadata().getUid().equals(uid) && range != null) {
                    used.add(bounds(range));
                }
            }
            SERVER_IDS.forEach(
                    (owner, range) -> {
                        if (!owner.equals(uid)) {
                            used.add(bounds(range));
                        }
                    });
            int block = Math.max(needed, 16);
            for (int first = settings.serverIdFirst();
                    first + block - 1 <= settings.serverIdLast();
                    first += block) {
                int last = first + block - 1;
                int a = first;
                if (used.stream().noneMatch(u -> u[0] <= last && a <= u[1])) {
                    String range = first + "-" + last;
                    SERVER_IDS.put(uid, range);
                    return range;
                }
            }
        }
        throw new IllegalStateException(
                "No free MySQL server-id range in "
                        + settings.serverIdFirst()
                        + "-"
                        + settings.serverIdLast());
    }

    private static int[] bounds(String range) {
        String[] b = range.split("-", 2);
        return new int[] {Integer.parseInt(b[0]), Integer.parseInt(b[1])};
    }

    private static int size(String range) {
        int[] b = bounds(range);
        return b[1] - b[0] + 1;
    }

    /** Drops the replication slots removed jobs held, as soon as each job has let go of it. */
    private void releaseSlots(Round r, SourceProbe.Target target) {
        if (r.status().pendingSlots.isEmpty() || target == null) {
            return;
        }
        List<String> left = new ArrayList<>();
        for (String slot : r.status().pendingSlots) {
            switch (ReplicationSlots.drop(target, slot)) {
                case DROPPED ->
                        events.normal(r.source(), "SlotDropped", "replication slot " + slot);
                case ABSENT -> {}
                case ACTIVE, FAILED -> left.add(slot);
            }
        }
        r.status().pendingSlots = left;
        if (left.isEmpty()) {
            r.condition("SlotsReleased", true, "Released", "no replication slot is left behind");
        } else if (r.condition(
                "SlotsReleased",
                false,
                "SlotsHeld",
                "replication slots still to drop, holding WAL meanwhile: " + left)) {
            events.warning(r.source(), "SlotsHeld", "still to drop: " + left);
        }
    }

    // ---------------------------------------------------------------- observation

    void observeJobs(Round r, boolean prerequisitesBroken) {
        LakeSourceStatus status = r.status();
        boolean wantSuspended =
                Boolean.TRUE.equals(r.spec().suspend)
                        || prerequisitesBroken
                        || status.repair != null;
        boolean allLive = !status.jobs.isEmpty();
        boolean allSuspended = !status.jobs.isEmpty();
        List<String> problems = new ArrayList<>();
        List<String> stuck = new ArrayList<>();
        for (JobState state : status.jobs) {
            FlinkDeployment fd =
                    kube.resources(FlinkDeployment.class)
                            .inNamespace(r.namespace())
                            .withName(state.deployment)
                            .get();
            if (fd == null || fd.getStatus() == null) {
                allLive = false;
                // Declared suspended from the start: the Flink operator never deploys it, nor
                // gives it a status.
                allSuspended &=
                        fd != null
                                && fd.getSpec().getJob() != null
                                && fd.getSpec().getJob().getState()
                                        == org.apache.flink.kubernetes.operator.api.spec.JobState
                                                .SUSPENDED;
                problems.add(state.deployment + " not deployed yet");
                continue;
            }
            JobObservation.read(fd, state);
            allSuspended &= JobObservation.suspended(fd);
            if (stuck(fd, r.now())) {
                allLive = false;
                stuck.add(
                        state.deployment
                                + " lost its job during an upgrade and has no state to resume"
                                + " from; redeploy it from its latest checkpoint (see the"
                                + " operator runbook)");
            } else if (!JobObservation.live(fd)) {
                allLive = false;
                problems.add(
                        "RUNNING".equals(state.state)
                                ? state.deployment + " is upgrading to its new spec"
                                : state.deployment + " is " + state.state + "/" + state.lifecycle);
            } else {
                recordRestore(r, state, fd);
                if (SourceJobs.viaKafka(r.spec())) {
                    countKafkaEvents(r, state);
                }
            }
        }
        if (!stuck.isEmpty()) {
            if (r.condition("JobRunning", false, "UpgradeStuck", String.join("; ", stuck))) {
                events.warning(r.source(), "UpgradeStuck", String.join("; ", stuck));
            }
        } else if (wantSuspended) {
            String by =
                    prerequisitesBroken
                            ? "the operator (source prerequisites)"
                            : status.repair != null && !Boolean.TRUE.equals(r.spec().suspend)
                                    ? "the operator (deleting rows the collector never sent)"
                                    : "the declaration";
            if (allSuspended) {
                r.condition("JobRunning", false, "Suspended", "suspended by " + by);
            } else {
                r.condition("JobRunning", false, "Suspending", "being suspended by " + by);
            }
        } else if (status.jobs.isEmpty()) {
            r.condition("JobRunning", false, "NoTables", "no admitted tables to ingest");
        } else if (allLive) {
            r.condition("JobRunning", true, "Running", status.jobs.size() + " job(s) running");
        } else if (r.condition("JobRunning", false, "NotRunning", String.join("; ", problems))) {
            events.normal(r.source(), "JobNotRunning", String.join("; ", problems));
        }
    }

    /** What the job dropped (DebeziumRecords' and KafkaEventGuard's counters), this run. */
    private void countKafkaEvents(Round r, JobState state) {
        LakeSourceStatus.TransportCounts was = r.status().transport;
        if (was != null && !Objects.equals(was.jobId, state.jobId)) {
            was = null;
        }
        LakeSourceStatus.TransportCounts now = new LakeSourceStatus.TransportCounts();
        now.jobId = state.jobId;
        Map<String, Long> counts =
                flinkRest
                        .counters(
                                r.namespace(),
                                state.deployment,
                                state.jobId,
                                List.of(
                                        "colvennaUnparsable",
                                        "colvennaStale",
                                        "colvennaUnverified",
                                        "colvennaResnapshot"))
                        .orElse(Map.of());
        // A counter the JobManager does not show (yet) keeps its last value.
        now.unparsable =
                counts.getOrDefault("colvennaUnparsable", was == null ? null : was.unparsable);
        now.stale = counts.getOrDefault("colvennaStale", was == null ? null : was.stale);
        now.unverified =
                counts.getOrDefault("colvennaUnverified", was == null ? null : was.unverified);
        now.resnapshot =
                counts.getOrDefault("colvennaResnapshot", was == null ? null : was.resnapshot);
        r.status().transport = now;
        if (rose(now.resnapshot, was == null ? null : was.resnapshot)) {
            // Rows deleted while the collector had no position are in no record: only the source
            // can tell, so its tables are reconciled with it again once the snapshot settled.
            List<String> recheck = new ArrayList<>();
            for (SourceTable t : r.status().tables) {
                if (state.tables.contains(t.name) && Enrollment.READY.equals(t.state)) {
                    Enrollment.recheck(t, r.now());
                    recheck.add(t.name);
                }
            }
            // A Warning only if rows turn out lost (DeletesLost): a new snapshot may lose none.
            events.normal(
                    r.source(),
                    "SnapshotAgain",
                    state.deployment
                            + " read "
                            + now.resnapshot
                            + " records of a new snapshot by the collector; rows deleted while it"
                            + " had no position would be in no record, so "
                            + (recheck.isEmpty() ? "the tables" : String.join(", ", recheck))
                            + " are reconciled with the source");
        }
        if (rose(now.unverified, was == null ? null : was.unverified)) {
            events.warning(
                    r.source(),
                    "TransportUnverified",
                    state.deployment
                            + " started "
                            + now.unverified
                            + " partitions where retention had removed records past its start;"
                            + " changes its tables lacked may be missing (partitions in its log)");
        }
        if (rose(now.unparsable, was == null ? null : was.unparsable)) {
            events.warning(
                    r.source(),
                    "UnparsableEvents",
                    state.deployment
                            + " dropped "
                            + now.unparsable
                            + " records that are not Debezium changes with their schema (topic"
                            + " and offset in its log)");
        }
    }

    private void recordRestore(Round r, JobState state, FlinkDeployment fd) {
        JobObservation.observe(flinkRest, r.namespace(), state, fd)
                .ifPresent(
                        restore -> {
                            if (restore == JobObservation.Restore.STATE) {
                                events.normal(
                                        r.source(),
                                        "JobRestored",
                                        state.deployment + " restored from " + state.restoredFrom);
                            } else {
                                events.warning(
                                        r.source(),
                                        "JobStartedWithoutState",
                                        state.deployment
                                                + " started without restoring its earlier state");
                            }
                        });
    }

    /**
     * An upgrade that lost its job: the new spec is reconciled but the JobManager has no job and no
     * HA state to restore (Flink cleans it when a job fails to start), so the Flink operator waits
     * forever. Reported after three minutes.
     */
    private boolean stuck(FlinkDeployment fd, Instant now) {
        var s = fd.getStatus();
        boolean missing =
                s.getError() != null
                        && s.getError().contains("Job Not Found")
                        && Objects.equals(
                                s.getObservedGeneration(), fd.getMetadata().getGeneration());
        String key = fd.getMetadata().getUid();
        if (!missing) {
            stuckSince.remove(key);
            return false;
        }
        Instant since = stuckSince.computeIfAbsent(key, k -> now);
        return since.plus(STUCK_AFTER).isBefore(now);
    }

    /**
     * Tables the jobs keep failing on, from the JobManager's exception history: Paimon's schema
     * evolution errors and the source guards both name the table (a PostgreSQL guard names the
     * partition, mapped to its table here).
     */
    private Map<String, String> attributeFailures(Round r) {
        Map<String, String> partitions = new HashMap<>();
        for (SourceTable t : r.status().tables) {
            for (String read : t.readTables) {
                partitions.put(read, t.name);
            }
        }
        Map<String, String> out = new HashMap<>();
        for (JobState state : r.status().jobs) {
            if (state.jobId == null) {
                continue;
            }
            long since =
                    state.lastErrorTime == null
                            ? 0
                            : Instant.parse(state.lastErrorTime).toEpochMilli();
            for (FlinkRest.Failure failure :
                    flinkRest.failures(r.namespace(), state.deployment, state.jobId)) {
                if (failure.timestamp() <= since) {
                    continue;
                }
                String cause = FlinkRest.rootCause(failure.stacktrace()).orElse("job failed");
                state.lastError = JobObservation.abbreviate(cause);
                state.lastErrorTime = Instant.ofEpochMilli(failure.timestamp()).toString();
                Set<String> named = new LinkedHashSet<>();
                for (String n : tablesNamed(failure.stacktrace(), r.spec().target.database)) {
                    named.add(partitions.getOrDefault(n, n));
                }
                if (named.isEmpty()
                        && state.tables.size() == 1
                        && deterministic(failure.stacktrace())) {
                    // A one-table job: a schema failure can only be its table's.
                    named.add(state.tables.get(0));
                }
                if (named.isEmpty() && positionLost(failure.stacktrace())) {
                    // The whole stream lost its place: every table would miss changes.
                    named.addAll(state.tables);
                }
                for (String table : named) {
                    if (state.tables.contains(table)) {
                        out.putIfAbsent(table, JobObservation.abbreviate(cause));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Whether a counter of the run has something new to tell. Any change counts: a task restart
     * resets it, and new records may stay below the old count.
     */
    private static boolean rose(Long now, Long was) {
        return now != null && now > 0 && !now.equals(was);
    }

    /**
     * Writes the deletes the collector never sent, once the job (their table's writer) is
     * suspended; lifting {@code status.repair} then resumes it, and its tables are reconciled
     * again. A repair whose keys the operator lost (a restart) starts over from reconciliation.
     */
    private void repairDeletes(Round r) {
        LakeSourceStatus.Repair repair = r.status().repair;
        if (repair == null
                || !"Suspended"
                        .equals(
                                r.status().conditions.stream()
                                        .filter(c -> c.type.equals("JobRunning"))
                                        .map(c -> c.reason)
                                        .findFirst()
                                        .orElse(""))) {
            return;
        }
        Map<String, Set<String>> plan =
                repairs.getOrDefault(r.source().getMetadata().getUid(), Map.of());
        String version = SourceJobs.versionColumn(r.spec());
        for (SourceTable t : r.status().tables) {
            if (!repair.tables.contains(t.name)) {
                continue;
            }
            Set<String> keys = plan.get(t.name);
            if (keys != null) {
                try {
                    int deleted =
                            lake.deleteRows(
                                    r.spec().target.database,
                                    t.target,
                                    lake.primaryKey(r.spec().target.database, t.target),
                                    version,
                                    keys);
                    events.normal(
                            r.source(),
                            "DeletesRepaired",
                            t.name
                                    + ": deleted "
                                    + deleted
                                    + " rows the source no longer has; the job resumes and"
                                    + " passes the deletes on");
                } catch (Exception e) {
                    throw new CatalogException(
                            "deleting the rows " + t.name + " lost: " + e.getMessage(), e);
                }
            }
            Enrollment.recheck(t, r.now());
        }
        repairs.remove(r.source().getMetadata().getUid());
        r.status().repair = null;
    }

    /**
     * The change stream cannot resume where the job's state says: MongoDB's oplog no longer holds
     * that point (ChangeStreamHistoryLost, code 286). Resuming later would skip changes.
     */
    static boolean positionLost(String stacktrace) {
        return stacktrace.contains("ChangeStreamHistoryLost")
                || stacktrace.contains("Resume of change stream was not possible")
                || stacktrace.contains("resume point may no longer be in the oplog");
    }

    /** Failures that recur on every restart, unlike connection or checkpoint failures. */
    static boolean deterministic(String stacktrace) {
        return stacktrace.contains("Cannot update primary key")
                || stacktrace.contains("Cannot convert field")
                || stacktrace.contains("casting result has a length");
    }

    static Set<String> tablesNamed(String stacktrace, String database) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher paimon = PAIMON_TABLE.matcher(stacktrace);
        while (paimon.find()) {
            if (paimon.group(1).equals(database)) {
                tables.add(paimon.group(2));
            }
        }
        Matcher guard = GUARD_TABLE.matcher(stacktrace);
        while (guard.find()) {
            tables.add(guard.group(1));
        }
        return tables;
    }

    // ---------------------------------------------------------------- reconciliation

    private void reconcileTables(Round r, Probe probe) {
        LakeSourceSpec spec = r.spec();
        SourceProbe source = probes.get(spec.type);
        for (SourceTable t : r.status().tables) {
            SourceModel.Table table = probe.inventory().tables().get(t.name);
            if (table == null
                    || !(Enrollment.SNAPSHOTTING.equals(t.state)
                            || Enrollment.rechecking(t, catchUp(spec).multipliedBy(2), r.now()))) {
                continue;
            }
            JobState job =
                    r.status().jobs.stream()
                            .filter(j -> j.tables.contains(t.name))
                            .findFirst()
                            .orElse(null);
            FlinkDeployment fd =
                    job == null
                            ? null
                            : kube.resources(FlinkDeployment.class)
                                    .inNamespace(r.namespace())
                                    .withName(job.deployment)
                                    .get();
            if (fd == null || !JobObservation.live(fd)) {
                t.message = "waiting for the job to run with this table";
                continue;
            }
            String key = r.source().getMetadata().getUid() + "/" + t.name;
            Instant last = lastChecked.get(key);
            if (last != null && last.plus(RECONCILE_EVERY).isAfter(r.now())) {
                continue;
            }
            lastChecked.put(key, r.now());
            reconcileTable(r, probe, source, table, t);
        }
    }

    /** A first reconciliation read whose candidates wait for the target to catch up. */
    private record Pending(
            Instant sourceRead,
            Map<String, Long> first,
            Map<String, Long> source,
            List<String> candidates,
            long sourceRows) {}

    private void reconcileTable(
            Round r, Probe probe, SourceProbe source, SourceModel.Table table, SourceTable t) {
        LakeSourceSpec spec = r.spec();
        String key = r.source().getMetadata().getUid() + "/" + t.name;
        try {
            Optional<LakeReader.Head> head = lake.head(spec.target.database, t.target);
            if (head.isEmpty()) {
                t.message = "the target has no snapshot yet";
                return;
            }
            List<String> keyColumns = lake.primaryKey(spec.target.database, t.target);
            String version = SourceJobs.versionColumn(spec);
            long limit = settings.reconcileMaxRows();
            Pending p = pending.get(key);
            if (p == null) {
                // The target first, then the source: the target can only trail what follows.
                Map<String, Long> first =
                        lake.versions(spec.target.database, t.target, keyColumns, version, limit);
                Instant read = clock.get();
                Map<String, Long> now =
                        source.versions(probe.target(), table, keyColumns, version, limit);
                List<String> candidates = Reconciliation.candidates(first, now);
                t.sourceRows = (long) now.size();
                t.targetRows = (long) first.size();
                if (candidates.isEmpty()) {
                    ready(
                            r,
                            t,
                            new Reconciliation.Result(
                                    now.size(), first.size(), 0, List.of(), List.of()),
                            head.get());
                } else if (candidates.size() > 10_000) {
                    t.message = candidates.size() + " keys differ; the target is far behind";
                } else {
                    pending.put(
                            key,
                            new Pending(
                                    read,
                                    only(first, candidates),
                                    only(now, candidates),
                                    candidates,
                                    now.size()));
                    t.message =
                            candidates.size()
                                    + " keys differ; checking them again once the target has"
                                    + " caught up"
                                    + gapNote(key);
                }
                return;
            }
            Instant due = p.sourceRead().plus(catchUp(spec));
            String progress =
                    head.get().commitMillis() < due.toEpochMilli()
                            ? caughtUpWithoutCommit(spec, r.status(), t.name, due)
                            : null;
            if (head.get().commitMillis() < due.toEpochMilli() && progress == null) {
                Instant last = Instant.ofEpochMilli(head.get().commitMillis());
                t.message =
                        "waiting for the target to commit past the source read: its last commit "
                                + last
                                + ", due "
                                + due
                                + gapNote(key);
                if (stalled(due, r.now()) && firstReport(stallsReported, key, p.sourceRead())) {
                    String what =
                            t.name
                                    + ": "
                                    + spec.target.database
                                    + "."
                                    + t.target
                                    + " has had no commit since "
                                    + last
                                    + ", "
                                    + Duration.between(due, r.now()).toMinutes()
                                    + " minutes past its catch-up after the source read, while its"
                                    + " job runs";
                    if (progressSignal(spec, r.status(), t.name)) {
                        // The heartbeat would have shown progress: the job is not passing on
                        // the source's changes.
                        events.warning(
                                r.source(),
                                "TargetNotCommitting",
                                what
                                        + ", and its heartbeat has not passed the read either: the"
                                        + " job does not pass the source's changes on (its"
                                        + " checkpoints and logs say why)");
                    } else {
                        // Without a progress signal an idle table waits like this by design.
                        events.normal(
                                r.source(),
                                "WaitingForCommit",
                                what
                                        + ": the table has likely not changed since; its next"
                                        + " change completes the check");
                    }
                }
                return;
            }
            if (progress != null) {
                LOG.info(
                        "Rechecking {}/{} without a new commit: {}",
                        r.source().getMetadata().getName(),
                        t.name,
                        progress);
            }
            pending.remove(key);
            Map<String, Long> later =
                    lake.versions(spec.target.database, t.target, keyColumns, version, limit);
            Reconciliation.Result judged =
                    Reconciliation.judge(p.first(), p.source(), p.candidates(), later);
            Reconciliation.Result result =
                    new Reconciliation.Result(
                            p.sourceRows(),
                            later.size(),
                            judged.unexplained(),
                            judged.sample(),
                            judged.deletedAtSource());
            t.targetRows = (long) later.size();
            Set<String> lasting =
                    result.matched() ? Set.of() : lastingGap(gaps, key, judged.unexplainedKeys());
            if (result.matched()) {
                ready(r, t, result, head.get());
            } else if (Enrollment.READY.equals(t.state)
                    && !result.deletedAtSource().isEmpty()
                    // Judged on the job's progress, not a commit: only deletes missing twice.
                    && (progress == null || lasting.containsAll(result.deletedAtSource()))) {
                // Deletes the collector never sent. The job is the table's writer: it is stopped
                // with a savepoint while they are written, and reads the table afresh after.
                repairs.computeIfAbsent(r.source().getMetadata().getUid(), u -> new HashMap<>())
                        .put(t.name, new HashSet<>(result.deletedAtSource()));
                if (r.status().repair == null) {
                    r.status().repair = new LakeSourceStatus.Repair();
                    r.status().repair.since = r.now().toString();
                }
                if (!r.status().repair.tables.contains(t.name)) {
                    r.status().repair.tables.add(t.name);
                }
                t.message =
                        result.deletedAtSource().size()
                                + " rows the source deleted are still here; suspending the job to"
                                + " delete them";
                events.warning(
                        r.source(),
                        "DeletesLost",
                        t.name
                                + ": "
                                + result.deletedAtSource().size()
                                + " rows the source deleted never reached the topic (the"
                                + " collector snapshotted again without them); deleting them");
            } else {
                t.message =
                        result.unexplained()
                                + " keys did not catch up with the source: "
                                + String.join("; ", result.sample());
                if (!lasting.isEmpty()) {
                    // Unexplained after two catch-ups: not in flight, the stream lost them.
                    String sample =
                            lasting.stream()
                                    .sorted()
                                    .limit(5)
                                    .map(Reconciliation::describe)
                                    .collect(Collectors.joining(", "));
                    t.message =
                            lasting.size()
                                    + " rows differ from the source after the target caught up"
                                    + " twice (missing, older, or deleted at the source); they do"
                                    + " not arrive by themselves: "
                                    + sample;
                    if (newGap(warnedGaps, key, lasting)) {
                        events.warning(
                                r.source(),
                                "RowsMissing",
                                t.name
                                        + ": "
                                        + lasting.size()
                                        + " of "
                                        + result.sourceRows()
                                        + " source rows differ from the target after it caught"
                                        + " up twice (missing, older, or deleted at the source): "
                                        + sample
                                        + ". The change stream lost them; the user manual (3.7)"
                                        + " says how to have the source send them again");
                    }
                }
            }
        } catch (SourceProbe.TooManyRowsException e) {
            if (!"TooLarge".equals(t.reason)) {
                events.warning(
                        r.source(),
                        "TableTooLarge",
                        t.name
                                + ": "
                                + e.getMessage()
                                + "; it stays "
                                + t.state
                                + ", unreconciled");
            }
            t.reason = "TooLarge";
            t.message = e.getMessage() + "; raise reconcileMaxRows to reconcile it";
        } catch (Exception e) {
            pending.remove(key);
            t.message =
                    "reconciliation: " + JobObservation.abbreviate(String.valueOf(e.getMessage()));
            LOG.warn("Reconciling {}/{} failed", r.source().getMetadata().getName(), t.name, e);
        }
    }

    private void ready(Round r, SourceTable t, Reconciliation.Result result, LakeReader.Head head) {
        String key = r.source().getMetadata().getUid() + "/" + t.name;
        gaps.remove(key);
        warnedGaps.remove(key);
        stallsReported.remove(key);
        if (Enrollment.READY.equals(t.state)) {
            String message = Enrollment.rechecked(t, result, head.snapshotId(), r.now());
            events.normal(
                    r.source(),
                    "TableReconciled",
                    t.name + ": " + message + " at snapshot " + head.snapshotId());
            return;
        }
        Enrollment.ready(
                        t,
                        result,
                        head.snapshotId(),
                        SourceJobs.versionColumn(r.spec()) != null,
                        r.now())
                .ifPresent(
                        tr ->
                                events.normal(
                                        r.source(),
                                        "TableReady",
                                        t.name
                                                + ": "
                                                + tr.message()
                                                + " at snapshot "
                                                + head.snapshotId()));
    }

    /**
     * Why a target with no commit since {@code due} has still applied every source change up to it,
     * or null. A table nobody writes to commits nothing, so its last commit can stay before the
     * source read for good (#238); the job's progress has to show it instead, through the declared
     * heartbeat: the source writes it, so it orders with the source's own changes.
     *
     * <ul>
     *   <li>MySQL read directly: one job reads the binlog for all its tables, in order, and commits
     *       them together. A heartbeat written after {@code due} and already in the lake means the
     *       job committed every change before it, this table's too, when they are in that job.
     *   <li>Through a Kafka transport each table has its own topic, with no order across them. The
     *       heartbeat in the lake shows the collector read the source past {@code due}; the table's
     *       topic consumed to its end by the job's group (committed offset equal to the end, as the
     *       probe saw it after that heartbeat was committed) shows the job took up what the
     *       collector had sent.
     * </ul>
     *
     * PostgreSQL gives each table its own job and replication slot, and MongoDB declares no
     * heartbeat: there, and without a heartbeat, only a new commit counts.
     */
    static String caughtUpWithoutCommit(
            LakeSourceSpec spec, LakeSourceStatus status, String table, Instant due) {
        LakeSourceStatus.HeartbeatState beat = status.heartbeat;
        if (!progressSignal(spec, status, table)
                || beat == null
                || beat.beatAt == null
                || !Boolean.TRUE.equals(beat.ok)
                || Boolean.TRUE.equals(beat.skewed)
                || Instant.parse(beat.beatAt).isBefore(due)) {
            return null;
        }
        if (spec.transport == null || spec.transport.kafka == null) {
            return "the heartbeat written at "
                    + beat.beatAt
                    + ", after the source read, is in the lake";
        }
        LakeSourceStatus.KafkaState kafka = status.kafka;
        if (kafka == null
                || kafka.reason != null
                || kafka.checkedAt == null
                || beat.committedAt == null
                || Instant.parse(kafka.checkedAt).isBefore(Instant.parse(beat.committedAt))) {
            return null;
        }
        String topic = SourceJobs.topic(spec, table);
        return kafka.topics.stream()
                .filter(
                        t ->
                                topic.equals(t.name)
                                        && t.endOffset != null
                                        && t.endOffset.equals(t.committedOffset))
                .findFirst()
                .map(
                        t ->
                                "the heartbeat written at "
                                        + beat.beatAt
                                        + " is in the lake and the job consumed "
                                        + topic
                                        + " to its end ("
                                        + t.endOffset
                                        + ")")
                .orElse(null);
    }

    /**
     * The keys unexplained now and in the table's previous judgement (#238): not in flight, the
     * target lacks them. Records this judgement's keys for the next.
     */
    static Set<String> lastingGap(Map<String, Set<String>> gaps, String table, List<String> keys) {
        Set<String> now = new HashSet<>(keys);
        Set<String> before = gaps.put(table, now);
        if (before == null) {
            return Set.of();
        }
        Set<String> both = new HashSet<>(now);
        both.retainAll(before);
        return both;
    }

    /** Whether a source read's stalled wait is reported for the first time, and records it. */
    static boolean firstReport(Map<String, Instant> reported, String table, Instant sourceRead) {
        return !sourceRead.equals(reported.put(table, sourceRead));
    }

    /**
     * Whether the table's source has the progress signal {@link #caughtUpWithoutCommit} reads: a
     * MySQL source with a declared heartbeat in the table's job.
     */
    static boolean progressSignal(LakeSourceSpec spec, LakeSourceStatus status, String table) {
        LakeSourceSpec.Heartbeat declared =
                spec.monitoring == null ? null : spec.monitoring.heartbeat;
        return "mysql".equals(spec.type)
                && declared != null
                && status.jobs.stream()
                        .anyMatch(
                                j -> j.tables.contains(table) && j.tables.contains(declared.table));
    }

    /** Whether a table's wait for a commit has gone {@link #STALLED} past its catch-up. */
    static boolean stalled(Instant due, Instant now) {
        return now.isAfter(due.plus(STALLED));
    }

    /** Whether a lasting gap is new since the last Warning about the table, and records it. */
    static boolean newGap(Map<String, Set<String>> warned, String table, Set<String> lasting) {
        return !lasting.isEmpty() && !lasting.equals(warned.put(table, Set.copyOf(lasting)));
    }

    /** What the last judgement found, while the next one waits: it does not vanish meanwhile. */
    private String gapNote(String key) {
        Set<String> warned = warnedGaps.get(key);
        return warned == null || warned.isEmpty()
                ? ""
                : "; the last check found "
                        + warned.size()
                        + " rows that differ after catching up twice (RowsMissing)";
    }

    /** Two checkpoints, at least a minute: time for the job to commit what it had read. */
    static Duration catchUp(LakeSourceSpec spec) {
        String interval =
                spec.job == null || spec.job.checkpointInterval == null
                        ? "30s"
                        : spec.job.checkpointInterval;
        long amount = Long.parseLong(interval.substring(0, interval.length() - 1));
        Duration checkpoint =
                interval.endsWith("m") ? Duration.ofMinutes(amount) : Duration.ofSeconds(amount);
        Duration twice = checkpoint.multipliedBy(2);
        return twice.compareTo(Duration.ofMinutes(1)) < 0 ? Duration.ofMinutes(1) : twice;
    }

    private static Map<String, Long> only(Map<String, Long> rows, List<String> keys) {
        Map<String, Long> out = new HashMap<>();
        for (String k : keys) {
            Long v = rows.get(k);
            if (v != null) {
                out.put(k, v);
            }
        }
        return out;
    }

    /**
     * Revokes the token a rotation replaced, once every job runs, on its current spec, the pod
     * template that carries the new token's id: only then does no job read the old one.
     */
    void finishRotation(Round r) {
        CatalogIdentity identity = r.status().identity;
        if (identity == null || identity.previousTokenId == null) {
            return;
        }
        for (JobState job : r.status().jobs) {
            FlinkDeployment fd =
                    kube.resources(FlinkDeployment.class)
                            .inNamespace(r.namespace())
                            .withName(job.deployment)
                            .get();
            if (fd == null
                    || !JobObservation.live(fd)
                    || !identity.tokenId.equals(FlinkJobs.tokenIdOf(fd))) {
                return;
            }
        }
        identities.revokePrevious(identity);
    }

    /**
     * The source's heartbeat (LLD "监控与数据质量"), when declared: written each round, read back from its
     * ODS table, as the acceptance contract measures the path (2.1): from the source's commit to
     * the snapshot that holds it (latency), and how old the newest change the lake holds is
     * (freshness).
     */
    private void heartbeat(Round r) {
        LakeSourceSpec.Heartbeat declared =
                r.spec().monitoring == null ? null : r.spec().monitoring.heartbeat;
        if (declared == null) {
            r.status().heartbeat = null;
            return;
        }
        LakeSourceStatus.HeartbeatState state =
                r.status().heartbeat == null
                        ? new LakeSourceStatus.HeartbeatState()
                        : r.status().heartbeat;
        String target =
                r.status().tables.stream()
                        .filter(t -> declared.table.equals(t.name) && t.target != null)
                        .map(t -> t.target)
                        .findFirst()
                        .orElse(declared.table);
        state.table = r.spec().target.database + "." + target;
        boolean read =
                r.status().tables.stream()
                        .anyMatch(
                                t ->
                                        declared.table.equals(t.name)
                                                && Enrollment.READY.equals(t.state));
        try {
            SourceProbe.Written written =
                    probes.get(r.spec().type)
                            .heartbeat(
                                    SourceTargets.target(kube, r.source()),
                                    SourceTargets.login(
                                            kube, r.source(), declared.credentialsSecret),
                                    declared.table,
                                    SourceJobs.versionColumn(r.spec()));
            state.writtenAt = written.at().toString();
            // The source's clock against the operator's, at the write's midpoint (the acceptance
            // contract's 2.1 records the skew; a sample beyond the tolerance is not one).
            Instant mid =
                    written.before()
                            .plus(Duration.between(written.before(), written.after()).dividedBy(2));
            Duration skew = Duration.between(mid, written.at());
            state.clockSkewSeconds = skew.toSeconds();
            state.skewed = Math.abs(skew.toMillis()) > SKEW_TOLERANCE.toMillis();
            state.ok = true;
            state.message = null;
            if (state.skewed) {
                state.message =
                        "the source's clock is "
                                + state.clockSkewSeconds
                                + " s off the operator's, past "
                                + SKEW_TOLERANCE.toSeconds()
                                + " s: its heartbeats are no samples";
            } else if (read && state.pending.size() < PENDING_BEATS) {
                // Before the job reads the table its beats wait for it, not for the path. Once
                // that many wait, the oldest already bounds the latency: newer ones add nothing.
                state.pending.add(written.at().toString());
            }
        } catch (Exception e) {
            state.ok = false;
            state.message =
                    "heartbeat: " + JobObservation.abbreviate(String.valueOf(e.getMessage()));
        }
        try {
            // Each beat's latency is to the first data commit that holds it (the acceptance
            // contract's 2.1: the first committed snapshot the change is read in); of those seen
            // this round, the longest.
            Long longest =
                    seen(
                            state,
                            lake.heartbeats(
                                    r.spec().target.database,
                                    target,
                                    state.seenSnapshot,
                                    state.committedAt == null
                                            ? null
                                            : Instant.parse(state.committedAt).toEpochMilli()));
            if (longest != null) {
                state.latencySeconds = longest;
            }
            if (state.beatAt == null && state.message == null) {
                state.message = "no heartbeat in " + state.table + " yet";
            }
        } catch (Exception e) {
            state.message =
                    "heartbeat: " + JobObservation.abbreviate(String.valueOf(e.getMessage()));
        }
        // From the newest beat the lake was seen to hold: it grows while nothing arrives. While
        // the heartbeat cannot be written, or the clocks disagree, it measures nothing
        // (ColvennaHeartbeatFailing, the status message).
        state.freshnessSeconds =
                state.beatAt == null
                                || !Boolean.TRUE.equals(state.ok)
                                || Boolean.TRUE.equals(state.skewed)
                        ? null
                        : Math.max(
                                0,
                                Duration.between(Instant.parse(state.beatAt), r.now()).toSeconds());
        r.status().heartbeat = state;
    }

    /**
     * Takes up the data commits of the heartbeat table seen this round, oldest first: each pending
     * heartbeat a commit holds was first seen there. Returns the longest of their latencies, in
     * seconds, or null when none was seen.
     */
    static Long seen(LakeSourceStatus.HeartbeatState state, LakeReader.Beats beats) {
        if (beats.restarted()) {
            // A table made again: the beats waiting were for the old one.
            state.pending.clear();
        }
        if (beats.latest() != null) {
            state.beatAt = Instant.ofEpochMilli(beats.latest().beatMillis()).toString();
        }
        Long longest = null;
        for (LakeReader.Beat beat : beats.commits()) {
            state.seenSnapshot = beat.snapshot();
            state.committedAt = Instant.ofEpochMilli(beat.commitMillis()).toString();
            while (!state.pending.isEmpty()
                    && Instant.parse(state.pending.get(0)).toEpochMilli() <= beat.beatMillis()) {
                long ms =
                        beat.commitMillis() - Instant.parse(state.pending.remove(0)).toEpochMilli();
                longest = Math.max(longest == null ? 0 : longest, Math.max(0, ms / 1000));
            }
        }
        return longest;
    }

    /**
     * Judges each ingested table's governance and publishes the governed Ready ones to the declared
     * readers (LLD "治理与个人数据删除").
     */
    private void govern(Round r, Probe probe) {
        LakeSourceStatus status = r.status();
        Governance g = r.spec().governance;
        List<String> ungoverned = new ArrayList<>();
        String reason = null;
        List<String> publishable = new ArrayList<>();
        for (SourceTable t : status.tables) {
            if (!Enrollment.inJob(t)) {
                t.governance = null;
                continue;
            }
            SourceModel.Table table =
                    probe.inventory() == null ? null : probe.inventory().tables().get(t.name);
            GovernancePolicy.Verdict v =
                    GovernancePolicy.verdict(
                            g,
                            g == null ? List.of() : g.subjectKeys.getOrDefault(t.name, List.of()),
                            g == null ? Map.of() : g.labelsOf(t.target),
                            table == null
                                    ? null
                                    : table.columns().stream()
                                            .map(SourceModel.Column::name)
                                            .toList(),
                            r.spec().target.tableOptions,
                            null);
            LakeSourceStatus.TableGovernance state = new LakeSourceStatus.TableGovernance();
            state.governed = v.governed();
            state.reason = v.reason();
            state.message = v.message();
            t.governance = state;
            if (!v.governed()) {
                ungoverned.add(t.name + ": " + v.message());
                reason = reason == null ? v.reason() : reason;
            } else if (Enrollment.READY.equals(t.state)) {
                publishable.add(r.spec().target.database + "." + t.target);
            }
        }
        if (ungoverned.isEmpty()) {
            r.condition("Governed", true, "Governed", "every ingested table is governed");
        } else if (r.condition("Governed", false, reason, String.join("; ", ungoverned))) {
            events.warning(r.source(), "NotGoverned", String.join("; ", ungoverned));
        }
        publishTables(
                r,
                publishable,
                Publication.Condition.withheld(
                        "NothingGoverned", "no governed Ready table to publish"));
    }

    /**
     * Publishes the source's tables to its readers (LLD "治理与个人数据删除"), less those their quality
     * gates hold back (LLD "门禁"); {@code none} is the Published condition when nothing is left to
     * publish for another reason.
     */
    void publishTables(Round r, List<String> tables, Publication.Condition none) {
        LakeSourceStatus status = r.status();
        List<String> publishable = new ArrayList<>(tables);
        List<String> held =
                QualityGates.withhold(QualityGates.holds(kube, r.namespace()), publishable);
        Publication.Outcome published =
                publication.publish(
                        Publication.mark(Owners.source(r.source())),
                        "published by LakeSource "
                                + r.namespace()
                                + "/"
                                + r.source().getMetadata().getName(),
                        new TreeSet<>(r.spec().readers),
                        publishable,
                        status.published,
                        publishable.isEmpty()
                                ? held.isEmpty()
                                        ? none
                                        : Publication.Condition.withheld(
                                                "QualityGate", String.join("; ", held))
                                : null,
                        r.now());
        status.published = published.published();
        r.condition(
                "Published",
                published.condition().status(),
                published.condition().reason(),
                published.condition().message()
                        + (held.isEmpty() || publishable.isEmpty()
                                ? ""
                                : "; withheld: " + String.join("; ", held)));
    }

    private void summarize(Round r) {
        LakeSourceStatus status = r.status();
        long ready = status.tables.stream().filter(t -> Enrollment.READY.equals(t.state)).count();
        long snapshotting =
                status.tables.stream().filter(t -> Enrollment.SNAPSHOTTING.equals(t.state)).count();
        long bad =
                status.tables.stream()
                        .filter(
                                t ->
                                        Enrollment.REJECTED.equals(t.state)
                                                || Enrollment.FAILED.equals(t.state))
                        .count();
        status.summary = ready + "/" + status.tables.size();
        boolean sourceReady =
                StatusCondition.isTrue(status.conditions, "SourceReady")
                        && (!SourceJobs.viaKafka(r.spec())
                                || !status.conditions.stream()
                                        .anyMatch(
                                                c ->
                                                        c.type.equals("TransportReady")
                                                                && "False".equals(c.status)));
        boolean jobRunning = StatusCondition.isTrue(status.conditions, "JobRunning");
        String jobReason =
                status.conditions.stream()
                        .filter(c -> c.type.equals("JobRunning"))
                        .map(c -> c.reason)
                        .findFirst()
                        .orElse("");
        if (jobReason.equals("Suspended")) {
            status.phase = "Suspended";
            status.message =
                    "suspended; the job keeps its position"
                            + (SourceJobs.isMySql(r.spec())
                                    ? ""
                                    : " and its replication slots retain WAL");
        } else if (jobReason.equals("Suspending")) {
            status.phase = "Provisioning";
            status.message = "suspending the jobs";
        } else if (status.jobs.isEmpty() && ready == 0 && snapshotting == 0) {
            status.phase = status.tables.isEmpty() && sourceReady ? "Pending" : "Degraded";
            status.message =
                    status.tables.isEmpty() ? "no matching source tables" : "no table is admitted";
        } else if (!jobRunning && snapshotting > 0 && ready == 0 && bad == 0) {
            status.phase = "Provisioning";
            status.message = "starting the job";
        } else if (!sourceReady || !jobRunning || bad > 0) {
            status.phase = "Degraded";
            List<String> why = new ArrayList<>();
            if (!sourceReady) {
                why.add("source not ready");
            }
            if (!jobRunning) {
                why.add("job not running");
            }
            if (bad > 0) {
                why.add(bad + " table(s) rejected or failed");
            }
            status.message = String.join(", ", why);
        } else if (snapshotting > 0) {
            status.phase = "Provisioning";
            status.message = snapshotting + " table(s) snapshotting";
        } else {
            status.phase = "Running";
            status.message = ready + " table(s) ready";
        }
        r.condition("Ready", "Running".equals(status.phase), status.phase, status.message);
    }

    void publish(LakeSource source, LakeSourceStatus status) {
        String id = source.getMetadata().getNamespace() + "/" + source.getMetadata().getName();
        List<Metrics.Sample> samples = new ArrayList<>();
        Map<String, String> base =
                Map.of(
                        "namespace",
                        source.getMetadata().getNamespace(),
                        "source",
                        source.getMetadata().getName(),
                        "type",
                        String.valueOf(source.getSpec() == null ? null : source.getSpec().type));
        for (String phase :
                List.of("Pending", "Provisioning", "Running", "Degraded", "Suspended", "Failed")) {
            Map<String, String> labels = new HashMap<>(base);
            labels.put("phase", phase);
            samples.add(
                    new Metrics.Sample(
                            "colvenna_source_phase",
                            "1 for the source's current phase",
                            labels,
                            phase.equals(status.phase) ? 1 : 0));
        }
        for (SourceTable t : status.tables) {
            Map<String, String> labels = new HashMap<>(base);
            labels.put("table", t.name);
            labels.put("state", t.state);
            labels.put("reason", String.valueOf(t.reason));
            samples.add(
                    new Metrics.Sample(
                            "colvenna_source_table_info",
                            "1 per matched source table, labelled with its state",
                            labels,
                            1));
        }
        for (SourceTable t : status.tables) {
            if (Enrollment.READY.equals(t.state) && t.target != null && source.getSpec() != null) {
                samples.addAll(
                        backlog.samples(
                                source.getSpec().target.database + "." + t.target,
                                base,
                                clock.get()));
            }
        }
        for (JobState j : status.jobs) {
            Map<String, String> labels = new HashMap<>(base);
            labels.put("deployment", j.deployment);
            samples.add(
                    new Metrics.Sample(
                            "colvenna_source_job_running",
                            "1 if the ingestion job runs its current spec",
                            labels,
                            "RUNNING".equals(j.state) ? 1 : 0));
            if (!"Suspended".equals(status.phase)) {
                // A suspended job's last checkpoint says nothing of now; a restarting one's does
                // (no checkpoint since is what ColvennaNoCheckpoint is for).
                samples.addAll(JobObservation.samples(j, labels));
            }
        }
        if (status.kafka != null) {
            for (LakeSourceStatus.TopicState t : status.kafka.topics) {
                Map<String, String> labels = new HashMap<>(base);
                labels.put("topic", t.name);
                samples.add(
                        new Metrics.Sample(
                                "colvenna_source_kafka_lag",
                                "records of the topic the job's group has not consumed",
                                labels,
                                t.lag == null ? 0 : t.lag));
            }
        }
        if (status.events != null) {
            samples.add(
                    new Metrics.Sample(
                            "colvenna_source_events_accepted",
                            "rows in the events table",
                            base,
                            status.events.accepted == null ? 0 : status.events.accepted));
            for (Map.Entry<String, Long> e : status.events.rejectedBy.entrySet()) {
                Map<String, String> labels = new HashMap<>(base);
                labels.put("reason", e.getKey());
                samples.add(
                        new Metrics.Sample(
                                "colvenna_source_events_rejected",
                                "rows in the rejections table, by reason",
                                labels,
                                e.getValue()));
            }
        }
        if (status.measuredAt != null) {
            samples.add(
                    new Metrics.Sample(
                            "colvenna_source_measured_timestamp_seconds",
                            "when the operator last measured the source: older, its measures are"
                                    + " stale",
                            base,
                            Instant.parse(status.measuredAt).getEpochSecond()));
        }
        if (status.heartbeat != null && status.heartbeat.latencySeconds != null) {
            Map<String, String> labels = new HashMap<>(base);
            labels.put("table", status.heartbeat.table);
            samples.add(
                    new Metrics.Sample(
                            "colvenna_source_latency_seconds",
                            "of the heartbeats last seen in ODS, the longest time from the source's"
                                    + " commit to the first ODS snapshot holding it (the acceptance"
                                    + " contract's 2.1: source to ODS)",
                            labels,
                            status.heartbeat.latencySeconds));
        }
        if (status.heartbeat != null
                && status.heartbeat.beatAt != null
                && Boolean.TRUE.equals(status.heartbeat.ok)
                && !Boolean.TRUE.equals(status.heartbeat.skewed)) {
            // Its age now, not when it was measured.
            Map<String, String> labels = new HashMap<>(base);
            labels.put("table", status.heartbeat.table);
            samples.add(
                    new Metrics.Sample(
                            "colvenna_source_freshness_seconds",
                            "how old the newest source change the lake holds is",
                            labels,
                            Math.max(
                                    0,
                                    Duration.between(
                                                    Instant.parse(status.heartbeat.beatAt),
                                                    clock.get())
                                            .toSeconds())));
        }
        if (status.heartbeat != null) {
            Map<String, String> labels = new HashMap<>(base);
            labels.put("table", status.heartbeat.table);
            samples.add(
                    new Metrics.Sample(
                            "colvenna_source_heartbeat_ok",
                            "1 if the operator wrote the latest heartbeat",
                            labels,
                            Boolean.TRUE.equals(status.heartbeat.ok) ? 1 : 0));
            if (status.heartbeat.clockSkewSeconds != null) {
                samples.add(
                        new Metrics.Sample(
                                "colvenna_source_clock_skew_seconds",
                                "the source's clock less the operator's, at the latest heartbeat",
                                labels,
                                status.heartbeat.clockSkewSeconds));
            }
        }
        samples.add(
                new Metrics.Sample(
                        "colvenna_source_pending_slots",
                        "replication slots of removed jobs not dropped yet",
                        base,
                        status.pendingSlots.size()));
        metrics.publish(KIND + ":" + id, samples);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        if (a != null) {
            out.addAll(a);
        }
        if (b != null) {
            out.addAll(b);
        }
        return out;
    }

    // ---------------------------------------------------------------- deletion and errors

    @Override
    public DeleteControl cleanup(LakeSource source, Context<LakeSource> context) {
        String prefix = source.getMetadata().getUid() + "/";
        gaps.keySet().removeIf(k -> k.startsWith(prefix));
        warnedGaps.keySet().removeIf(k -> k.startsWith(prefix));
        stallsReported.keySet().removeIf(k -> k.startsWith(prefix));
        var deployments =
                kube.resources(FlinkDeployment.class)
                        .inNamespace(source.getMetadata().getNamespace());
        List<FlinkDeployment> owned =
                new ArrayList<>(context.getSecondaryResources(FlinkDeployment.class));
        if (!owned.isEmpty()) {
            for (FlinkDeployment fd : owned) {
                if (fd.getMetadata().getDeletionTimestamp() == null) {
                    deployments.withName(fd.getMetadata().getName()).delete();
                }
            }
            return DeleteControl.noFinalizerRemoval().rescheduleAfter(Duration.ofSeconds(5));
        }
        LakeSourceSpec spec = source.getSpec();
        LakeSourceStatus status =
                source.getStatus() == null ? new LakeSourceStatus() : source.getStatus();
        if (SourceJobs.isPostgres(spec)) {
            List<String> held = dropSlots(source, status);
            Instant deleted = Instant.parse(source.getMetadata().getDeletionTimestamp());
            if (!held.isEmpty()) {
                if (deleted.plus(SLOT_RELEASE).isAfter(clock.get())) {
                    // A just-deleted job's pods may still hold their slot for a moment.
                    return DeleteControl.noFinalizerRemoval()
                            .rescheduleAfter(Duration.ofSeconds(10));
                }
                events.warning(
                        source,
                        "SlotNotDropped",
                        "replication slots left on the source, holding WAL until dropped: " + held);
            }
        }
        try {
            if ("Delete".equals(spec.deletionPolicy)) {
                // Only tables this source created: the database is its own (owner option).
                for (SourceTable t : status.tables) {
                    if (!Enrollment.REJECTED.equals(t.state)) {
                        catalog.dropTable(spec.target.database, t.target);
                    }
                }
                // And those of tables that left the source.
                for (String target : status.retired) {
                    catalog.dropTable(spec.target.database, target);
                }
                if (SourceJobs.isKafka(spec)) {
                    catalog.dropTable(spec.target.database, spec.target.table);
                    catalog.dropTable(spec.target.database, KafkaTables.rejections(spec));
                }
                catalog.dropDatabaseIfEmpty(spec.target.database);
            }
            if (source.getStatus() != null) {
                publication.withdraw(
                        Publication.mark(Owners.source(source)),
                        source.getStatus().published,
                        clock.get());
            }
            identities.remove(source, KIND);
        } catch (CatalogException e) {
            LOG.warn("Cleaning up {} failed; retrying", source.getMetadata().getName(), e);
            return DeleteControl.noFinalizerRemoval().rescheduleAfter(RESYNC);
        }
        SERVER_IDS.remove(source.getMetadata().getUid());
        if (source.getStatus() != null && source.getSpec() != null) {
            for (SourceTable t : source.getStatus().tables) {
                if (t.target != null) {
                    backlog.forget(source.getSpec().target.database + "." + t.target);
                }
            }
        }
        metrics.forget(
                KIND
                        + ":"
                        + source.getMetadata().getNamespace()
                        + "/"
                        + source.getMetadata().getName());
        events.normal(
                source,
                "Deleted",
                "jobs stopped, identity revoked"
                        + ("Delete".equals(spec.deletionPolicy)
                                ? ", target tables dropped"
                                : ", target tables retained"));
        return DeleteControl.defaultDelete();
    }

    /** Drops every slot the source's jobs had; returns the ones still held or not dropped. */
    private List<String> dropSlots(LakeSource source, LakeSourceStatus status) {
        List<String> held = new ArrayList<>();
        try {
            SourceProbe.Target target = SourceTargets.target(kube, source);
            Set<String> slots = new LinkedHashSet<>(status.pendingSlots);
            for (SourceTable t : status.tables) {
                slots.add(SourceJobs.slotName(source, t.name));
            }
            for (String slot : slots) {
                ReplicationSlots.Outcome outcome = ReplicationSlots.drop(target, slot);
                if (outcome == ReplicationSlots.Outcome.DROPPED) {
                    events.normal(source, "SlotDropped", "replication slot " + slot);
                } else if (outcome != ReplicationSlots.Outcome.ABSENT) {
                    held.add(slot);
                }
            }
        } catch (IllegalStateException e) {
            held.add("all (" + e.getMessage() + ")");
        }
        return held;
    }

    @Override
    public ErrorStatusUpdateControl<LakeSource> updateErrorStatus(
            LakeSource source, Context<LakeSource> context, Exception e) {
        LakeSourceStatus status =
                source.getStatus() == null ? new LakeSourceStatus() : source.getStatus();
        status.phase = "Degraded";
        status.message =
                "reconcile failed: " + JobObservation.abbreviate(String.valueOf(e.getMessage()));
        source.setStatus(status);
        // Its measures stay as last taken: colvenna_source_measured_timestamp_seconds ages.
        publish(source, status);
        return ErrorStatusUpdateControl.patchStatus(source);
    }
}
