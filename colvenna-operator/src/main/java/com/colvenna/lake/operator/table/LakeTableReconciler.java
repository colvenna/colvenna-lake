package com.colvenna.lake.operator.table;

import com.colvenna.lake.operator.OperatorSettings;
import com.colvenna.lake.operator.catalog.CatalogClient;
import com.colvenna.lake.operator.catalog.CatalogException;
import com.colvenna.lake.operator.catalog.CatalogIdentities;
import com.colvenna.lake.operator.catalog.Grant;
import com.colvenna.lake.operator.catalog.Owners;
import com.colvenna.lake.operator.catalog.Publication;
import com.colvenna.lake.operator.crd.CatalogIdentity;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceStatus;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.crd.LakeTableSpec;
import com.colvenna.lake.operator.crd.LakeTableStatus;
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
import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.apache.flink.kubernetes.operator.api.spec.JobState;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypeFamily;
import org.apache.paimon.types.DataTypeRoot;
import org.apache.paimon.utils.JsonSerdeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps a {@link LakeTable} converged: its query validated, its target table and catalog identity,
 * and the long-running SQL job, upgraded with savepoints and rolled back when an upgrade cannot
 * start. See {@code docs/design/colvenna-operator-lld.md}, "LakeTable".
 */
@ControllerConfiguration(name = "laketable")
public final class LakeTableReconciler implements Reconciler<LakeTable>, Cleaner<LakeTable> {
    private static final Logger LOG = LoggerFactory.getLogger(LakeTableReconciler.class);
    public static final String KIND = "laketable";
    static final Duration RESYNC = Duration.ofSeconds(30);
    static final Duration POLL = Duration.ofSeconds(10);

    /** How many quarantined rows a round counts at most. */
    static final long QUARANTINE_COUNTED = 100_000;

    /**
     * The tables each declaration claims to read (what it declares and what it runs), by uid,
     * written under the same lock as the cycle check: two concurrent changes cannot both pass it.
     */
    private static final Map<String, Dependencies.Node> CLAIMS = new HashMap<>();

    private final KubernetesClient kube;
    private final CatalogClient catalog;
    private final CatalogIdentities identities;
    private final Publication publication;
    private final LakeReader lake;
    private final Backlog backlog;
    private final TableJobs jobs;
    private final Validations validations;
    private final FlinkRest flinkRest;
    private final Upgrades upgrades;
    private final Events events;
    private final Metrics metrics;
    private final Supplier<Instant> clock;

    public LakeTableReconciler(
            OperatorSettings settings,
            KubernetesClient kube,
            CatalogClient catalog,
            LakeReader lake,
            FlinkRest flinkRest,
            Metrics metrics,
            Supplier<Instant> clock) {
        this.kube = kube;
        this.catalog = catalog;
        this.identities = new CatalogIdentities(catalog, kube);
        this.publication = new Publication(catalog);
        this.lake = lake;
        this.backlog = new Backlog(lake);
        this.jobs = new TableJobs(settings);
        this.validations = new Validations(settings, kube);
        this.flinkRest = flinkRest;
        this.upgrades =
                new Upgrades(
                        flinkRest,
                        settings.checkpointRoot(),
                        fd ->
                                kube.resources(FlinkDeployment.class)
                                        .inNamespace(fd.getMetadata().getNamespace())
                                        .resource(fd)
                                        .update());
        this.events = new Events(kube);
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public List<EventSource<?, LakeTable>> prepareEventSources(
            EventSourceContext<LakeTable> context) {
        var config =
                InformerEventSourceConfiguration.from(FlinkDeployment.class, LakeTable.class)
                        .withSecondaryToPrimaryMapper(Mappers.fromOwnerReferences(LakeTable.class))
                        .withLabelSelector(FlinkJobs.MANAGED_BY + "=colvenna-operator")
                        .build();
        return List.of(new InformerEventSource<>(config, context));
    }

    /** What one round knows, and whether it rolled an upgrade back. */
    static final class Round {
        private final LakeTable table;
        private final LakeTableSpec spec;
        private final LakeTableStatus status;
        private final long generation;
        private final Instant now;

        /** Set once this round wrote a rollback: the job is on its way back, in flight. */
        private boolean rolledBack;

        /** The table's round at {@code now}, its status created when it has none. */
        Round(LakeTable table, Instant now) {
            if (table.getStatus() == null) {
                table.setStatus(new LakeTableStatus());
            }
            this.table = table;
            this.spec = table.getSpec();
            this.status = table.getStatus();
            this.generation = Objects.requireNonNullElse(table.getMetadata().getGeneration(), 0L);
            this.now = now;
        }

        LakeTable table() {
            return table;
        }

        LakeTableSpec spec() {
            return spec;
        }

        LakeTableStatus status() {
            return status;
        }

        long generation() {
            return generation;
        }

        Instant now() {
            return now;
        }

        String namespace() {
            return table.getMetadata().getNamespace();
        }

        boolean condition(String type, boolean ok, String reason, String message) {
            return StatusCondition.set(
                    status.conditions, type, ok, reason, message, generation, now);
        }
    }

    /**
     * Why the declaration cannot be applied. A job already running keeps running what it ran;
     * {@code waiting} refusals (an upstream table not there yet) are retried.
     */
    record Refusal(String condition, String reason, String message, boolean waiting) {
        static final String VALIDATING = "Validating";

        boolean validating() {
            return reason.equals(VALIDATING);
        }
    }

    @Override
    public UpdateControl<LakeTable> reconcile(LakeTable table, Context<LakeTable> context) {
        Round r = new Round(table, clock.get());
        LakeTableStatus status = r.status();
        status.observedGeneration = r.generation();
        Refusal refusal;
        try {
            refusal = prepare(r, context);
            if (refusal == null) {
                refusal = apply(r, context);
            } else if (status.applied != null) {
                // Keep the running job's identity current while its new declaration waits.
                ensureIdentity(r, Set.of(), status.applied.target, status.applied.quarantine);
            }
        } catch (CatalogException e) {
            if (r.condition("CatalogReady", false, "CatalogUnavailable", e.getMessage())) {
                events.warning(table, "CatalogUnavailable", e.getMessage());
            }
            refusal = new Refusal("CatalogReady", "CatalogUnavailable", e.getMessage(), true);
        }
        if (refusal != null) {
            if (r.condition(refusal.condition(), false, refusal.reason(), refusal.message())
                    && !refusal.waiting()) {
                events.warning(table, refusal.reason(), refusal.message());
            }
            if (!refusal.condition().equals("SpecApplied")) {
                r.condition("SpecApplied", false, refusal.reason(), refusal.message());
            }
        }
        boolean upgrading = observe(r);
        positions(r);
        freshness(r);
        quarantined(r);
        govern(r);
        finishRotation(r);
        summarize(r, refusal);
        status.measuredAt = r.now().toString();
        publish(table, status);
        boolean validating = refusal != null && refusal.validating();
        return UpdateControl.patchStatus(table)
                .rescheduleAfter(upgrading || validating ? POLL : RESYNC);
    }

    // ---------------------------------------------------------------- declaration and catalog

    /** Every check before the deployment changes; null when it may. */
    private Refusal prepare(Round r, Context<LakeTable> context) {
        LakeTableSpec spec = r.spec();
        String invalid = validate(spec);
        if (invalid != null) {
            return new Refusal("SpecValid", "InvalidSpec", invalid, false);
        }
        String conflict = conflict(r.table(), context);
        if (conflict != null) {
            return new Refusal("SpecValid", "TargetConflict", conflict, false);
        }
        r.condition("SpecValid", true, "Valid", "the declaration is valid");

        Refusal dependencies = dependencies(r, context);
        if (dependencies != null) {
            return dependencies;
        }
        r.condition("DependenciesReady", true, "Ready", "every upstream table may be read");

        String database = spec.target.database;
        String owner = Owners.tables(r.namespace());
        catalog.ensureDatabase(database, Map.of(Owners.OPTION, owner));
        String actual =
                catalog.databaseOptions(database).map(o -> o.get(Owners.OPTION)).orElse(null);
        if (!owner.equals(actual)) {
            return new Refusal(
                    "SpecValid",
                    "TargetConflict",
                    "target database "
                            + database
                            + " is not a database of this namespace's tables (owner "
                            + actual
                            + ")",
                    false);
        }
        if (r.status().applied != null
                && !TableJobs.target(spec).equals(r.status().applied.target)) {
            return new Refusal(
                    "SpecValid",
                    "TargetChanged",
                    "the job writes "
                            + r.status().applied.target
                            + "; its state cannot move to another table, declare a new one",
                    false);
        }
        ensureIdentity(
                r,
                new TreeSet<>(spec.upstream),
                TableJobs.target(spec),
                TableJobs.quarantineName(spec));

        Refusal query = validation(r);
        if (query != null) {
            return query;
        }
        Refusal checks = checks(r);
        if (checks != null) {
            return checks;
        }
        Refusal target = target(r);
        return target != null ? target : quarantine(r);
    }

    /**
     * The declared checks against the query's output: each of a column of it, a range one of
     * numbers, dates or timestamps; declared with the table, as adding or removing them changes the
     * job's graph, which its savepoint no longer fits (LLD "数据质量").
     */
    private Refusal checks(Round r) {
        LakeTableSpec spec = r.spec();
        String quarantine = TableJobs.quarantineName(spec);
        LakeTableStatus.Applied applied = r.status().applied;
        if (applied != null && !Objects.equals(quarantine, applied.quarantine)) {
            return new Refusal(
                    "QualityValid",
                    "QualityChanged",
                    applied.quarantine == null
                            ? "checks added to a running table change its job's graph; declare a"
                                    + " new table with them"
                            : "the job writes the quarantine "
                                    + applied.quarantine
                                    + "; removing its checks changes the job's graph, declare a"
                                    + " new table",
                    false);
        }
        if (spec.quality == null) {
            return null;
        }
        List<String> problems = qualityProblems(spec, fields(r.status().validation));
        if (!problems.isEmpty()) {
            return new Refusal(
                    "QualityValid", "QualityInvalid", String.join("; ", problems), false);
        }
        r.condition(
                "QualityValid",
                true,
                "Valid",
                checkedColumns(spec.quality).size()
                        + " columns checked; failing rows go to "
                        + quarantine);
        return null;
    }

    /** The columns a table's checks read. */
    private static Set<String> checkedColumns(LakeTableSpec.Quality quality) {
        Set<String> columns = new TreeSet<>(quality.notNull);
        columns.addAll(quality.allowed.keySet());
        columns.addAll(quality.ranges.keySet());
        return columns;
    }

    /** What is wrong with a table's declared checks against its query's output, or nothing. */
    static List<String> qualityProblems(LakeTableSpec spec, List<DataField> output) {
        String quarantine = TableJobs.quarantineName(spec);
        Map<String, DataType> types = new HashMap<>();
        for (DataField f : output) {
            types.put(f.name(), f.type());
        }
        List<String> problems = new ArrayList<>();
        if (quarantine.length() > 63) {
            problems.add("the quarantine's name " + quarantine + " is longer than 63");
        }
        for (String column : checkedColumns(spec.quality)) {
            if (!types.containsKey(column)) {
                problems.add("no column " + column + " in the query's output");
            }
        }
        spec.quality.allowed.forEach(
                (column, values) -> {
                    if (values == null || values.isEmpty()) {
                        problems.add("no value allowed for " + column);
                    }
                });
        spec.quality.ranges.forEach(
                (column, range) -> {
                    DataType type = types.get(column);
                    if (range == null || range.min == null && range.max == null) {
                        problems.add("a range of " + column + " without a bound");
                    } else if (type != null
                            && !type.is(DataTypeFamily.NUMERIC)
                            && type.getTypeRoot() != DataTypeRoot.DATE
                            && !type.is(DataTypeFamily.TIMESTAMP)) {
                        problems.add("a range of " + column + ", a " + type);
                    }
                });
        if (spec.quality.notNull.isEmpty()
                && spec.quality.allowed.isEmpty()
                && spec.quality.ranges.isEmpty()) {
            problems.add("quality declares no check");
        }
        return problems;
    }

    /** The quarantine of a table with checks, created beside the target. */
    private Refusal quarantine(Round r) {
        LakeTableSpec spec = r.spec();
        String quarantine = TableJobs.quarantineName(spec);
        if (quarantine == null) {
            return null;
        }
        try {
            Optional<LakeReader.TableInfo> existing = lake.table(spec.target.database, quarantine);
            if (existing.isEmpty()) {
                lake.createTable(
                        spec.target.database,
                        quarantine,
                        TableJobs.quarantineFields(fields(r.status().validation)),
                        spec.primaryKey,
                        TableJobs.quarantineOptions(r.table()));
                events.normal(r.table(), "TableCreated", spec.target.database + "." + quarantine);
            } else if (!Owners.table(r.table())
                    .equals(existing.get().options().get(Owners.OPTION))) {
                return new Refusal(
                        "QualityValid",
                        "TargetConflict",
                        "the quarantine "
                                + spec.target.database
                                + "."
                                + quarantine
                                + " exists and is not this table's",
                        false);
            }
        } catch (Exception e) {
            throw new CatalogException("quarantine " + quarantine + ": " + e.getMessage(), e);
        }
        return null;
    }

    static String validate(LakeTableSpec spec) {
        if (spec == null || spec.target == null || spec.query == null) {
            return "spec.target and spec.query are required";
        }
        if (spec.primaryKey == null || spec.primaryKey.isEmpty()) {
            return "spec.primaryKey names at least one column";
        }
        if (spec.upstream == null || spec.upstream.isEmpty()) {
            return "spec.upstream names the tables the query reads";
        }
        for (String u : spec.upstream) {
            if (!u.matches("[a-z][a-z0-9_]{0,62}\\.[a-z][a-z0-9_]{0,62}")) {
                return "spec.upstream: " + u + " is not database.table";
            }
        }
        for (String k : spec.primaryKey) {
            if (!k.matches("[a-z][a-z0-9_]{0,62}")) {
                return "spec.primaryKey: " + k + " is not a column name";
            }
        }
        if (spec.upstream.contains(TableJobs.target(spec))) {
            return "the table reads itself: " + TableJobs.target(spec) + " is in spec.upstream";
        }
        return TableOptions.refusal("spec.tableOptions", spec.tableOptions);
    }

    /** Another declaration of the same target, created first, keeps it. */
    private static String conflict(LakeTable table, Context<LakeTable> context) {
        String target = TableJobs.target(table.getSpec());
        for (LakeTable other : context.getPrimaryCache().list().toList()) {
            if (!other.getMetadata().getUid().equals(table.getMetadata().getUid())
                    && other.getSpec() != null
                    && other.getSpec().target != null
                    && target.equals(TableJobs.target(other.getSpec()))
                    && Creation.before(other, table)) {
                return "target "
                        + target
                        + " already belongs to LakeTable "
                        + other.getMetadata().getNamespace()
                        + "/"
                        + other.getMetadata().getName();
            }
        }
        return null;
    }

    /**
     * The upstream tables: each must exist and belong to this namespace's sources or tables, and
     * the declaration must not close a cycle with the namespace's other tables.
     */
    private Refusal dependencies(Round r, Context<LakeTable> context) {
        List<String> missing = new ArrayList<>();
        for (String upstream : new TreeSet<>(r.spec().upstream)) {
            String[] parts = TableJobs.split(upstream);
            Optional<JsonNode> table = catalog.table(parts[0], parts[1]);
            if (table.isEmpty()) {
                missing.add(upstream);
                continue;
            }
            String tableOwner =
                    table.get().path("schema").path("options").path(Owners.OPTION).asText(null);
            String databaseOwner =
                    catalog.databaseOptions(parts[0]).map(o -> o.get(Owners.OPTION)).orElse(null);
            String refused =
                    Dependencies.refusal(r.namespace(), upstream, databaseOwner, tableOwner);
            if (refused != null) {
                return new Refusal("DependenciesReady", "UpstreamNotAllowed", refused, false);
            }
        }
        List<String> cycle = cycle(r, context);
        if (!cycle.isEmpty()) {
            return new Refusal(
                    "DependenciesReady",
                    "DependencyCycle",
                    "the tables would read each other: " + String.join(" reads ", cycle),
                    false);
        }
        if (!missing.isEmpty()) {
            return new Refusal(
                    "DependenciesReady",
                    "UpstreamMissing",
                    "waiting for upstream tables " + missing,
                    true);
        }
        return null;
    }

    private static List<String> cycle(Round r, Context<LakeTable> context) {
        String uid = r.table().getMetadata().getUid();
        Set<String> mine = new TreeSet<>(r.spec().upstream);
        if (r.status().applied != null) {
            mine.addAll(r.status().applied.upstream);
        }
        Dependencies.Node self = new Dependencies.Node(TableJobs.target(r.spec()), mine);
        synchronized (CLAIMS) {
            List<Dependencies.Node> others = new ArrayList<>();
            for (LakeTable other : context.getPrimaryCache().list().toList()) {
                String id = other.getMetadata().getUid();
                if (id.equals(uid)
                        || other.getSpec() == null
                        || other.getSpec().target == null
                        || other.getMetadata().getDeletionTimestamp() != null) {
                    continue;
                }
                Dependencies.Node claimed = CLAIMS.get(id);
                if (claimed == null) {
                    // Not checked by this operator yet: what it declares and what it runs.
                    Set<String> reads = new TreeSet<>(other.getSpec().upstream);
                    if (other.getStatus() != null && other.getStatus().applied != null) {
                        reads.addAll(other.getStatus().applied.upstream);
                    }
                    claimed = new Dependencies.Node(TableJobs.target(other.getSpec()), reads);
                }
                others.add(claimed);
            }
            List<String> cycle = Dependencies.cycle(self, others);
            if (cycle.isEmpty()) {
                CLAIMS.put(uid, self);
            }
            return cycle;
        }
    }

    /**
     * The table's identity: READ on what it reads, WRITE on its target and its quarantine (named in
     * the target's database, or null). While an upgrade is in flight the old job still reads its
     * own upstream tables, so those stay granted too; the quarantine the running job writes stays
     * writable likewise.
     */
    private void ensureIdentity(Round r, Set<String> declared, String target, String quarantine) {
        LakeTableStatus status = r.status();
        Set<String> readable = new TreeSet<>(declared);
        if (status.applied != null) {
            readable.addAll(status.applied.upstream);
        }
        if (status.upgrade != null && status.upgrade.from != null) {
            readable.addAll(status.upgrade.from.upstream);
        }
        String principal = CatalogIdentities.principalName(KIND, r.table());
        String approval = r.table().getMetadata().getUid();
        List<Grant> grants = new ArrayList<>();
        for (String t : readable) {
            String[] parts = TableJobs.split(t);
            grants.add(Grant.table(principal, parts[0], parts[1], "READ", approval));
        }
        for (String t : written(target, quarantine, status.applied)) {
            String[] parts = TableJobs.split(t);
            grants.add(Grant.table(principal, parts[0], parts[1], "WRITE", approval));
        }
        status.identity =
                identities.ensure(
                        r.table(),
                        KIND,
                        "LakeTable " + r.namespace() + "/" + r.table().getMetadata().getName(),
                        grants,
                        status.identity,
                        r.now());
        r.condition("CatalogReady", true, "Ready", "identity, token and grants are current");
    }

    /**
     * What the table's job writes: its target, its quarantine, and the running job's quarantine.
     */
    static Set<String> written(String target, String quarantine, LakeTableStatus.Applied applied) {
        Set<String> written = new TreeSet<>(Set.of(target));
        if (quarantine != null) {
            written.add(TableJobs.split(target)[0] + "." + quarantine);
        }
        if (applied != null && applied.quarantine != null) {
            written.add(TableJobs.split(applied.target)[0] + "." + applied.quarantine);
        }
        return written;
    }

    /**
     * Judges the target's governance and publishes it to the declared readers while it is governed
     * (LLD "治理与个人数据删除").
     */
    private void govern(Round r) {
        LakeTableSpec spec = r.spec();
        LakeTableStatus status = r.status();
        List<String> columns =
                status.validation == null || status.validation.columns.isEmpty()
                        ? null
                        : status.validation.columns.stream().map(c -> c.name).toList();
        GovernancePolicy.Declared declared =
                GovernancePolicy.Declared.of(
                        kube.resources(LakeSource.class)
                                .inNamespace(r.namespace())
                                .list()
                                .getItems(),
                        kube.resources(LakeTable.class)
                                .inNamespace(r.namespace())
                                .list()
                                .getItems());
        GovernancePolicy.Verdict verdict =
                GovernancePolicy.verdict(
                        spec.governance,
                        spec.governance == null ? List.of() : spec.governance.subjectKey,
                        spec.governance == null ? Map.of() : spec.governance.targetLabels(),
                        columns,
                        spec.tableOptions,
                        declared.upstream(spec.upstream));
        if (r.condition("Governed", verdict.governed(), verdict.reason(), verdict.message())
                && !verdict.governed()) {
            events.warning(r.table(), "NotGoverned", verdict.message());
        }
        List<String> tables = new ArrayList<>(List.of(TableJobs.target(spec)));
        if (TableJobs.quarantineQualified(spec) != null) {
            tables.add(TableJobs.quarantineQualified(spec));
        }
        // A table its quality gate holds back is not published (LLD "门禁"); the other is.
        List<String> held = QualityGates.withhold(QualityGates.holds(kube, r.namespace()), tables);
        Publication.Outcome published =
                publication.publish(
                        Publication.mark(Owners.table(r.table())),
                        "published by LakeTable "
                                + r.namespace()
                                + "/"
                                + r.table().getMetadata().getName(),
                        new TreeSet<>(spec.readers),
                        tables,
                        status.published,
                        !verdict.governed()
                                ? Publication.Condition.withheld("NotGoverned", verdict.message())
                                : status.applied == null
                                        ? Publication.Condition.withheld(
                                                "NoTarget", "the target is not created yet")
                                        : tables.isEmpty()
                                                ? Publication.Condition.withheld(
                                                        "QualityGate", String.join("; ", held))
                                                : null,
                        r.now());
        status.published = published.published();
        r.condition(
                "Published",
                published.condition().status(),
                published.condition().reason(),
                published.condition().message()
                        + (held.isEmpty() || tables.isEmpty()
                                ? ""
                                : "; withheld: " + String.join("; ", held)));
    }

    /** Validates the declared query once per change, in a Kubernetes Job. */
    private Refusal validation(Round r) {
        LakeTableStatus status = r.status();
        String hash = TableJobs.validationHash(r.spec());
        if (status.validation == null || !hash.equals(status.validation.hash)) {
            Optional<LakeTableStatus.Validation> done =
                    validations.run(r.table(), jobs.image(), hash);
            if (done.isEmpty()) {
                return new Refusal(
                        "QueryValid", Refusal.VALIDATING, "validating the query with Flink", true);
            }
            LakeTableStatus.Validation v = done.get();
            v.validatedAt = r.now().toString();
            status.validation = v;
            if (v.reason == null) {
                events.normal(
                        r.table(),
                        "QueryAccepted",
                        "streams " + v.streams + ", looks up " + v.lookups);
            }
        }
        LakeTableStatus.Validation v = status.validation;
        if ("ValidationError".equals(v.reason)) {
            // Not a verdict on the query: run it again next round.
            status.validation.hash = null;
            return new Refusal("QueryValid", v.reason, v.message, false);
        }
        if (v.reason != null) {
            return new Refusal("QueryValid", v.reason, v.message, false);
        }
        Set<String> read = new TreeSet<>(v.streams);
        read.addAll(v.lookups);
        Set<String> unread = new TreeSet<>(r.spec().upstream);
        unread.removeAll(read);
        if (!unread.isEmpty()) {
            return new Refusal(
                    "QueryValid",
                    "UpstreamMismatch",
                    "declared upstream tables the query does not read: " + unread,
                    false);
        }
        // A newly streamed table has no consumer yet, which the job cannot tell from a lost one
        // (REBUILD_REQUIRED); and dropping one would leave its consumer behind.
        if (r.status().applied != null
                && !new TreeSet<>(v.streams).equals(new TreeSet<>(r.status().applied.streams))) {
            return new Refusal(
                    "QueryValid",
                    "StreamsChanged",
                    "the query streams "
                            + v.streams
                            + " but the job streams "
                            + r.status().applied.streams
                            + "; a different set of streamed tables needs a new table",
                    false);
        }
        r.condition(
                "QueryValid", true, "Valid", "streams " + v.streams + ", looks up " + v.lookups);
        return null;
    }

    /** Creates the target from the validated schema, or checks the existing one still fits. */
    private Refusal target(Round r) {
        LakeTableSpec spec = r.spec();
        List<DataField> fields = fields(r.status().validation);
        Optional<LakeReader.TableInfo> existing;
        try {
            existing = lake.table(spec.target.database, spec.target.table);
            if (existing.isEmpty()) {
                if (r.status().applied != null) {
                    return new Refusal(
                            "TargetReady",
                            "TargetLost",
                            "the target was dropped outside the operator; its job's state no"
                                    + " longer matches any table",
                            false);
                }
                lake.createTable(
                        spec.target.database,
                        spec.target.table,
                        fields,
                        spec.primaryKey,
                        TableJobs.tableOptions(r.table()));
                events.normal(r.table(), "TableCreated", TableJobs.target(spec));
                r.condition("TargetReady", true, "Created", "the target matches the query");
                return null;
            }
        } catch (Exception e) {
            throw new CatalogException(
                    "target " + TableJobs.target(spec) + ": " + e.getMessage(), e);
        }
        LakeReader.TableInfo info = existing.get();
        if (!Owners.table(r.table()).equals(info.options().get(Owners.OPTION))) {
            return new Refusal(
                    "SpecValid",
                    "TargetConflict",
                    "target "
                            + TableJobs.target(spec)
                            + " exists and is not this table's (owner "
                            + info.options().get(Owners.OPTION)
                            + ")",
                    false);
        }
        String mismatch = schemaMismatch(info.fields(), fields);
        if (mismatch == null && !spec.primaryKey.equals(info.primaryKey())) {
            mismatch =
                    "the key is " + spec.primaryKey + " but the target's is " + info.primaryKey();
        }
        if (mismatch != null) {
            return new Refusal(
                    "TargetReady",
                    "SchemaChanged",
                    mismatch + "; existing rows would not follow, declare a new table instead",
                    false);
        }
        r.condition("TargetReady", true, "Ready", "the target matches the query");
        return null;
    }

    static List<DataField> fields(LakeTableStatus.Validation v) {
        List<DataField> out = new ArrayList<>();
        int id = 0;
        for (LakeTableStatus.Column c : v.columns) {
            String json = c.type.startsWith("{") ? c.type : quote(c.type);
            out.add(new DataField(id++, c.name, JsonSerdeUtil.fromJson(json, DataType.class)));
        }
        return out;
    }

    /**
     * Why the query's output does not fit the target, or null. The same rule as the job's own check
     * (SqlJob): the same columns and types, where a NOT NULL column gets no nulls.
     */
    static String schemaMismatch(List<DataField> target, List<DataField> output) {
        boolean fits = target.size() == output.size();
        for (int i = 0; fits && i < target.size(); i++) {
            DataField t = target.get(i);
            DataField o = output.get(i);
            fits =
                    t.name().equals(o.name())
                            && t.type().copy(true).equals(o.type().copy(true))
                            && (t.type().isNullable() || !o.type().isNullable());
        }
        return fits ? null : "the query outputs " + output + " but the target has " + target;
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---------------------------------------------------------------- deployment

    /** Creates or updates the deployment; a refusal when it may not. */
    Refusal apply(Round r, Context<LakeTable> context) {
        LakeTableStatus status = r.status();
        LakeTable table = r.table();
        var deployments = kube.resources(FlinkDeployment.class).inNamespace(r.namespace());
        FlinkDeployment desired = jobs.desired(table, status);
        String hash = FlinkJobs.hashOf(desired);
        String name = desired.getMetadata().getName();
        FlinkDeployment fd = deployment(r, context);
        if (fd == null) {
            if (status.job != null && status.job.jobId != null) {
                events.warning(table, "JobLost", name + " was deleted outside the operator");
                return new Refusal(
                        "JobRunning",
                        "JobStateLost",
                        "the job "
                                + name
                                + " was deleted outside the operator and its state is gone;"
                                + " starting again would apply its input twice. Rebuild the"
                                + " table",
                        false);
            }
            if (catalog.hasSnapshot(r.spec().target.database, r.spec().target.table)) {
                return new Refusal(
                        "TargetReady",
                        "TargetNotEmpty",
                        "a new job would write its input again into a target that holds data",
                        false);
            }
            deployments.resource(desired).create();
            events.normal(table, "JobCreated", name + " streams " + status.validation.streams);
            applied(r, hash);
            status.job = new LakeSourceStatus.JobState();
            status.job.deployment = name;
            return null;
        }
        if (!isOwned(fd, table)) {
            return new Refusal(
                    "SpecApplied",
                    "DeploymentConflict",
                    "FlinkDeployment " + name + " exists and is not this table's",
                    false);
        }
        if (status.job == null) {
            // Created in a round whose status update was lost.
            status.job = new LakeSourceStatus.JobState();
            status.job.deployment = name;
        }
        if (FlinkJobs.current(fd, desired)) {
            // Also after a rollback once the declaration is back to what runs.
            status.rolledBack = null;
            if (status.applied == null) {
                applied(r, hash);
            }
            r.condition("SpecApplied", true, "Applied", "the job runs the declaration");
            return null;
        }
        if (status.rolledBack != null && hash.equals(status.rolledBack.hash)) {
            return new Refusal(
                    "SpecApplied",
                    "RolledBack",
                    "the change failed to start and was rolled back: " + status.rolledBack.message,
                    false);
        }
        Upgrades.Step step = upgrades.change(table, fd, desired, r.now(), () -> lastCommit(r));
        return switch (step.kind()) {
            case UPDATED -> {
                events.normal(table, "JobUpdated", step.message());
                applied(r, hash);
                yield null;
            }
            case ROLLED_BACK -> {
                // The declaration applies once the job runs what it ran before.
                rolledBack(r, step);
                yield null;
            }
            case WAITING -> new Refusal("SpecApplied", "RollingBack", step.message(), true);
            case REFUSED -> throw new IllegalStateException("a change is not refused");
        };
    }

    private void applied(Round r, String hash) {
        LakeTableStatus.Applied applied = new LakeTableStatus.Applied();
        applied.hash = hash;
        applied.target = TableJobs.target(r.spec());
        applied.upstream = new ArrayList<>(new TreeSet<>(r.spec().upstream));
        applied.streams = new ArrayList<>(r.status().validation.streams);
        applied.lookups = new ArrayList<>(r.status().validation.lookups);
        applied.quarantine = TableJobs.quarantineName(r.spec());
        r.status().applied = applied;
    }

    private FlinkDeployment deployment(Round r, Context<LakeTable> context) {
        String name = TableJobs.deploymentName(r.table());
        for (FlinkDeployment fd : context.getSecondaryResources(FlinkDeployment.class)) {
            if (fd.getMetadata().getName().equals(name)) {
                return fd;
            }
        }
        // Not in the informer cache yet, or not this table's.
        return kube.resources(FlinkDeployment.class)
                .inNamespace(r.namespace())
                .withName(name)
                .get();
    }

    private static boolean isOwned(FlinkDeployment fd, LakeTable table) {
        return fd.getMetadata().getOwnerReferences().stream()
                .anyMatch(o -> o.getUid().equals(table.getMetadata().getUid()));
    }

    // ---------------------------------------------------------------- observation and rollback

    /** Records the job's state; returns whether an upgrade, or a rollback, is still in flight. */
    boolean observe(Round r) {
        LakeTableStatus status = r.status();
        if (status.job == null) {
            r.condition("JobRunning", false, "NotDeployed", "no job yet");
            return false;
        }
        FlinkDeployment fd =
                kube.resources(FlinkDeployment.class)
                        .inNamespace(r.namespace())
                        .withName(status.job.deployment)
                        .get();
        if (fd == null || fd.getStatus() == null) {
            r.condition("JobRunning", false, "NotDeployed", status.job.deployment + " starting");
            return status.upgrade != null;
        }
        JobObservation.read(fd, status.job);
        boolean wantSuspended =
                fd.getSpec().getJob() != null
                        && fd.getSpec().getJob().getState() == JobState.SUSPENDED;
        if (wantSuspended) {
            if (JobObservation.suspended(fd)) {
                r.condition("JobRunning", false, "Suspended", "suspended with a savepoint");
            } else {
                r.condition("JobRunning", false, "Suspending", "taking a savepoint to suspend");
            }
            return false;
        }
        if (JobObservation.live(fd)) {
            LakeTableStatus.Upgrade upgrade = status.upgrade;
            if (upgrade != null && upgrade.toHash.equals(FlinkJobs.hashOf(fd))) {
                status.upgrade = null;
                events.normal(
                        r.table(), "JobUpgraded", status.job.deployment + " runs its new spec");
            }
            JobObservation.observe(flinkRest, r.namespace(), status.job, fd)
                    .ifPresent(restore -> restored(r, restore));
            r.condition("JobRunning", true, "Running", status.job.deployment + " running");
            return status.upgrade != null;
        }
        if (r.rolledBack) {
            // This round wrote the rollback (the declaration changed after a refused one): the job
            // is not running yet, and its conditions say it is rolling back.
            return true;
        }
        String cause = failure(r);
        Upgrades.Step failed = upgrades.failed(r.table(), fd, r.now(), cause, () -> lastCommit(r));
        if (failed != null) {
            // Still in flight only while the rollback has not been refused.
            if (failed.kind() == Upgrades.Kind.ROLLED_BACK) {
                rolledBack(r, failed);
                return true;
            }
            if (r.condition("JobRunning", false, "UpgradeFailed", failed.message())) {
                events.warning(r.table(), "UpgradeFailed", failed.message());
            }
            return false;
        }
        String reason =
                cause != null && cause.contains("REBUILD_REQUIRED")
                        ? "RebuildRequired"
                        : status.upgrade != null ? "Upgrading" : "NotRunning";
        String message =
                status.job.deployment
                        + " is "
                        + status.job.state
                        + "/"
                        + status.job.lifecycle
                        + (cause == null ? "" : ": " + cause);
        if (r.condition("JobRunning", false, reason, message) || reason.equals("RebuildRequired")) {
            if (reason.equals("RebuildRequired")) {
                events.warning(r.table(), reason, message);
            } else {
                events.normal(r.table(), "JobNotRunning", message);
            }
        }
        return status.upgrade != null;
    }

    private void restored(Round r, JobObservation.Restore restore) {
        LakeSourceStatus.JobState job = r.status().job;
        if (restore == JobObservation.Restore.STATE) {
            events.normal(
                    r.table(),
                    "JobRestored",
                    job.deployment + " restored from " + job.restoredFrom);
        } else {
            events.warning(
                    r.table(),
                    "JobStartedWithoutState",
                    job.deployment + " started without restoring its earlier state");
        }
    }

    /** The job's latest root cause, recorded in {@code status.job.lastError}. */
    private String failure(Round r) {
        LakeSourceStatus.JobState job = r.status().job;
        if (job.jobId != null) {
            List<FlinkRest.Failure> failures =
                    flinkRest.failures(r.namespace(), job.deployment, job.jobId);
            if (!failures.isEmpty()) {
                FlinkRest.rootCause(failures.get(0).stacktrace())
                        .ifPresent(
                                cause -> {
                                    job.lastError = JobObservation.abbreviate(cause);
                                    job.lastErrorTime =
                                            Instant.ofEpochMilli(failures.get(0).timestamp())
                                                    .toString();
                                });
            }
        }
        return job.lastError;
    }

    /** A rollback {@link Upgrades} wrote: told in the conditions and a Warning. */
    private void rolledBack(Round r, Upgrades.Step step) {
        r.rolledBack = true;
        r.condition("SpecApplied", false, "RolledBack", step.message());
        r.condition("JobRunning", false, "RollingBack", step.message());
        events.warning(r.table(), "UpgradeRolledBack", step.message());
    }

    private Optional<Long> lastCommit(Round r) {
        try {
            return lake.head(r.spec().target.database, r.spec().target.table)
                    .map(LakeReader.Head::commitIdentifier);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- progress and summary

    /**
     * Where the rows failing a check went, and how many by check; a Warning when there are more.
     * Counted up to {@link #QUARANTINE_COUNTED} rows.
     */
    private void quarantined(Round r) {
        LakeTableStatus status = r.status();
        String quarantine = status.applied == null ? null : status.applied.quarantine;
        if (quarantine == null) {
            status.quality = null;
            return;
        }
        LakeTableStatus.QualityState q = new LakeTableStatus.QualityState();
        q.quarantine = status.applied.target.split("\\.", 2)[0] + "." + quarantine;
        // Every declared check, at 0 until a row fails it: the panels show each from the start.
        for (Map<String, Object> rule : TableJobs.rules(r.spec())) {
            q.byRule.put(TableJobs.ruleName(rule), 0L);
        }
        try {
            lake.quarantined(TableJobs.split(q.quarantine)[0], quarantine, QUARANTINE_COUNTED)
                    .forEach(
                            (rule, f) -> {
                                q.byRule.put(rule, f.rows());
                                if (f.newestMillis() != null) {
                                    q.newestByRule.put(
                                            rule,
                                            Instant.ofEpochMilli(f.newestMillis()).toString());
                                }
                            });
        } catch (Exception e) {
            LOG.debug("Counting {} failed", q.quarantine, e);
            return;
        }
        q.rows = q.byRule.values().stream().mapToLong(Long::longValue).sum();
        q.countedAt = r.now().toString();
        long before =
                status.quality == null || status.quality.rows == null ? 0 : status.quality.rows;
        // Newly flagged rows, though as many others left (fixed, or their history expired).
        Map<String, String> was =
                status.quality == null || status.quality.newestByRule == null
                        ? Map.of()
                        : status.quality.newestByRule;
        boolean newer =
                q.newestByRule.entrySet().stream()
                        .anyMatch(
                                e ->
                                        was.get(e.getKey()) == null
                                                || Instant.parse(e.getValue())
                                                        .isAfter(
                                                                Instant.parse(
                                                                        was.get(e.getKey()))));
        if (q.rows > before || newer) {
            events.warning(
                    r.table(),
                    "RowsQuarantined",
                    (q.rows - before)
                            + " more rows failed a check, "
                            + q.rows
                            + " in "
                            + q.quarantine
                            + ": "
                            + q.byRule);
        }
        status.quality = q;
    }

    /**
     * How old the newest source change a query of the target sees is: over the streamed upstream
     * tables, each one's own freshness (an ODS table's from its source's heartbeat, a layered
     * table's from its status) and how far the job is behind it.
     */
    private void freshness(Round r) {
        List<LakeSource> sources =
                kube.resources(LakeSource.class).inNamespace(r.namespace()).list().getItems();
        List<LakeTable> tables =
                kube.resources(LakeTable.class).inNamespace(r.namespace()).list().getItems();
        Long worst = null;
        for (LakeTableStatus.Upstream u : r.status().upstream) {
            Long upstream = freshnessOf(sources, tables, u.table);
            if (upstream == null || u.lagSeconds == null) {
                r.status().freshnessSeconds = null;
                return;
            }
            worst = Math.max(worst == null ? 0 : worst, upstream + u.lagSeconds);
        }
        r.status().freshnessSeconds = worst;
    }

    private static Long freshnessOf(
            List<LakeSource> sources, List<LakeTable> tables, String table) {
        String[] parts = TableJobs.split(table);
        for (LakeSource s : sources) {
            if (s.getSpec() != null
                    && s.getSpec().target != null
                    && parts[0].equals(s.getSpec().target.database)) {
                return s.getStatus() == null || s.getStatus().heartbeat == null
                        ? null
                        : s.getStatus().heartbeat.freshnessSeconds;
            }
        }
        for (LakeTable t : tables) {
            if (t.getSpec() != null
                    && t.getSpec().target != null
                    && table.equals(TableJobs.target(t.getSpec()))) {
                return t.getStatus() == null ? null : t.getStatus().freshnessSeconds;
            }
        }
        return null;
    }

    /** How far the job has read each streamed upstream table, from its Paimon consumer. */
    private void positions(Round r) {
        LakeTableStatus status = r.status();
        if (status.applied == null) {
            status.upstream = new ArrayList<>();
            return;
        }
        Map<String, LakeTableStatus.Upstream> before = new HashMap<>();
        status.upstream.forEach(u -> before.put(u.table, u));
        // The table's latest data commit: a compaction after it carried nothing.
        Long targetCommit = null;
        try {
            String[] target = TableJobs.split(status.applied.target);
            targetCommit = lake.lastDataCommit(target[0], target[1]).orElse(null);
        } catch (Exception e) {
            LOG.debug("Reading the latest data commit of {} failed", status.applied.target, e);
        }
        String consumer = TableJobs.consumerId(r.table());
        List<LakeTableStatus.Upstream> out = new ArrayList<>();
        for (String t : status.applied.streams) {
            String[] parts = TableJobs.split(t);
            LakeTableStatus.Upstream u = new LakeTableStatus.Upstream();
            u.table = t;
            u.consumerId = consumer;
            LakeTableStatus.Upstream was = before.get(t);
            u.latencySeconds = was == null ? null : was.latencySeconds;
            try {
                Optional<LakeReader.Position> found = lake.position(parts[0], parts[1], consumer);
                if (found.isPresent()) {
                    LakeReader.Position p = found.get();
                    u.nextSnapshot = p.nextSnapshot();
                    u.latestSnapshot = p.latestSnapshot();
                    if (p.nextSnapshot() != null && p.latestSnapshot() != null) {
                        u.lag = Math.max(0, p.latestSnapshot() - p.nextSnapshot() + 1);
                        u.lagSeconds =
                                p.unreadSince() == null
                                        ? 0L
                                        : Math.max(
                                                0,
                                                Duration.between(
                                                                Instant.ofEpochMilli(
                                                                        p.unreadSince()),
                                                                r.now())
                                                        .toSeconds());
                    }
                    // The reader moved on: the oldest snapshot it just carried took from its
                    // commit to the table's latest data commit (the acceptance contract's 2.1, one
                    // layer to
                    // the next).
                    if (was != null
                            && was.nextSnapshot != null
                            && p.nextSnapshot() != null
                            && p.nextSnapshot() > was.nextSnapshot
                            && targetCommit != null) {
                        Long carried = lake.snapshotMillis(parts[0], parts[1], was.nextSnapshot);
                        // A commit before the snapshot carried none of it (all quarantined, say).
                        if (carried != null && targetCommit >= carried) {
                            u.latencySeconds = (targetCommit - carried) / 1000;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.debug("Reading the position on {} failed", t, e);
            }
            out.add(u);
        }
        status.upstream = out;
    }

    /** Revokes the token a rotation replaced, once the job runs with the new one. */
    private void finishRotation(Round r) {
        CatalogIdentity identity = r.status().identity;
        if (identity == null || identity.previousTokenId == null || r.status().job == null) {
            return;
        }
        FlinkDeployment fd =
                kube.resources(FlinkDeployment.class)
                        .inNamespace(r.namespace())
                        .withName(r.status().job.deployment)
                        .get();
        if (fd != null
                && JobObservation.live(fd)
                && identity.tokenId.equals(FlinkJobs.tokenIdOf(fd))) {
            identities.revokePrevious(identity);
        }
    }

    private void summarize(Round r, Refusal refusal) {
        LakeTableStatus status = r.status();
        status.target = TableJobs.target(r.spec());
        String jobReason =
                status.conditions.stream()
                        .filter(c -> c.type.equals("JobRunning"))
                        .map(c -> c.reason)
                        .findFirst()
                        .orElse("");
        // This round saw the fence and the job suspended: no later round resumes the job while
        // the fence is there.
        status.fencedBy = jobReason.equals("Suspended") ? Fence.holder(r.table()) : null;
        boolean running = StatusCondition.isTrue(status.conditions, "JobRunning");
        if (status.applied == null && refusal != null) {
            status.phase =
                    refusal.validating()
                            ? Refusal.VALIDATING
                            : refusal.waiting() ? "Pending" : "Failed";
            status.message = refusal.message();
        } else if (jobReason.equals("Suspended")) {
            status.phase = "Suspended";
            String fence = Fence.holder(r.table());
            status.message =
                    fence != null
                            ? "suspended with a savepoint while LakeTask "
                                    + fence
                                    + " backfills the target"
                            : "suspended with a savepoint; its upstream consumers keep their"
                                    + " position";
        } else if (refusal != null && !refusal.validating()) {
            status.phase = "Degraded";
            status.message =
                    "the declaration is not applied ("
                            + refusal.reason()
                            + "): "
                            + refusal.message();
        } else if (jobReason.equals("UpgradeFailed")
                || jobReason.equals("RebuildRequired")
                || jobReason.equals("JobStateLost")) {
            status.phase = "Degraded";
            status.message = StatusCondition.message(status.conditions, "JobRunning");
        } else if (!running) {
            status.phase = "Provisioning";
            status.message = StatusCondition.message(status.conditions, "JobRunning");
        } else if (refusal != null) {
            status.phase = "Running";
            status.message = "running; validating the declaration's change";
        } else {
            status.phase = "Running";
            status.message = "streaming " + status.applied.streams;
        }
        r.condition(
                "Ready",
                "Running".equals(status.phase) && refusal == null,
                status.phase,
                status.message);
    }

    private void publish(LakeTable table, LakeTableStatus status) {
        String id = table.getMetadata().getNamespace() + "/" + table.getMetadata().getName();
        Map<String, String> base =
                Map.of(
                        "namespace",
                        table.getMetadata().getNamespace(),
                        "laketable",
                        table.getMetadata().getName(),
                        "table",
                        table.getSpec() == null || table.getSpec().target == null
                                ? ""
                                : TableJobs.target(table.getSpec()));
        List<Metrics.Sample> samples = new ArrayList<>();
        if (status.measuredAt != null) {
            samples.add(
                    new Metrics.Sample(
                            "colvenna_table_measured_timestamp_seconds",
                            "when the operator last measured the table: older, its measures are"
                                    + " stale",
                            base,
                            Instant.parse(status.measuredAt).getEpochSecond()));
        }
        for (String phase :
                List.of(
                        "Pending",
                        "Validating",
                        "Provisioning",
                        "Running",
                        "Degraded",
                        "Suspended",
                        "Failed")) {
            Map<String, String> labels = new HashMap<>(base);
            labels.put("phase", phase);
            samples.add(
                    new Metrics.Sample(
                            "colvenna_table_phase",
                            "1 for the layered table's current phase",
                            labels,
                            phase.equals(status.phase) ? 1 : 0));
        }
        samples.add(
                new Metrics.Sample(
                        "colvenna_table_job_running",
                        "1 if the table's job runs",
                        base,
                        StatusCondition.isTrue(status.conditions, "JobRunning") ? 1 : 0));
        if (status.job != null && !"Suspended".equals(status.phase)) {
            // A suspended (or fenced) job's last checkpoint says nothing of now; a restarting
            // one's does (no checkpoint since is what ColvennaNoCheckpoint is for).
            Map<String, String> labels = new HashMap<>(base);
            labels.put("deployment", String.valueOf(status.job.deployment));
            samples.addAll(JobObservation.samples(status.job, labels));
        }
        if (status.freshnessSeconds != null) {
            samples.add(
                    new Metrics.Sample(
                            "colvenna_table_freshness_seconds",
                            "how old the newest source change a query of the target sees is",
                            base,
                            status.freshnessSeconds));
        }
        if (status.quality != null) {
            status.quality.byRule.forEach(
                    (rule, rows) -> {
                        Map<String, String> labels = new HashMap<>(base);
                        labels.put("rule", rule);
                        samples.add(
                                new Metrics.Sample(
                                        "colvenna_table_quarantined_rows",
                                        "rows in the quarantine, by the check they failed",
                                        labels,
                                        rows));
                        String newest =
                                status.quality.newestByRule == null
                                        ? null
                                        : status.quality.newestByRule.get(rule);
                        if (newest != null) {
                            samples.add(
                                    new Metrics.Sample(
                                            "colvenna_table_quarantine_newest_timestamp_seconds",
                                            "when the newest row that failed the check was flagged",
                                            labels,
                                            Instant.parse(newest).getEpochSecond()));
                        }
                    });
        }
        for (LakeTableStatus.Upstream u : status.upstream) {
            if (u.latencySeconds != null) {
                Map<String, String> labels = new HashMap<>(base);
                labels.put("upstream", u.table);
                samples.add(
                        new Metrics.Sample(
                                "colvenna_table_layer_latency_seconds",
                                "the latest upstream snapshot the job carried: from its commit to"
                                        + " the table's data commit holding it (the acceptance"
                                        + " contract's 2.1, layer to layer)",
                                labels,
                                u.latencySeconds));
            }
            if (u.lagSeconds != null) {
                Map<String, String> labels = new HashMap<>(base);
                labels.put("upstream", u.table);
                samples.add(
                        new Metrics.Sample(
                                "colvenna_table_upstream_lag_seconds",
                                "seconds since the oldest upstream snapshot the job has not carried"
                                        + " was committed (the acceptance contract's 2.1: one layer"
                                        + " to the next)",
                                labels,
                                u.lagSeconds));
            }
            if (u.lag != null) {
                Map<String, String> labels = new HashMap<>(base);
                labels.put("upstream", u.table);
                samples.add(
                        new Metrics.Sample(
                                "colvenna_table_upstream_lag_snapshots",
                                "upstream snapshots committed but not yet read by the job",
                                labels,
                                u.lag));
            }
        }
        if (status.applied != null) {
            samples.addAll(backlog.samples(status.applied.target, base, clock.get()));
        }
        metrics.publish(KIND + ":" + id, samples);
    }

    // ---------------------------------------------------------------- deletion and errors

    @Override
    public DeleteControl cleanup(LakeTable table, Context<LakeTable> context) {
        var deployments =
                kube.resources(FlinkDeployment.class)
                        .inNamespace(table.getMetadata().getNamespace());
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
        LakeTableSpec spec = table.getSpec();
        LakeTableStatus status =
                table.getStatus() == null ? new LakeTableStatus() : table.getStatus();
        String retained = null;
        try {
            // The consumers would keep the upstream snapshots from expiring.
            Set<String> streams = new LinkedHashSet<>();
            if (status.applied != null) {
                streams.addAll(status.applied.streams);
            }
            if (status.upgrade != null && status.upgrade.from != null) {
                streams.addAll(status.upgrade.from.streams);
            }
            for (String t : streams) {
                String[] parts = TableJobs.split(t);
                lake.dropConsumer(parts[0], parts[1], TableJobs.consumerId(table));
            }
            String target =
                    status.applied != null
                            ? status.applied.target
                            : spec != null && spec.target != null ? TableJobs.target(spec) : null;
            if ("Delete".equals(spec == null ? null : spec.deletionPolicy) && target != null) {
                String[] parts = TableJobs.split(target);
                boolean ours =
                        lake.table(parts[0], parts[1])
                                .map(
                                        t ->
                                                Owners.table(table)
                                                        .equals(t.options().get(Owners.OPTION)))
                                .orElse(false);
                List<String> readers = readers(target, table, context);
                if (ours && readers.isEmpty()) {
                    catalog.dropTable(parts[0], parts[1]);
                    String quarantine = status.applied != null ? status.applied.quarantine : null;
                    if (quarantine != null) {
                        catalog.dropTable(parts[0], quarantine);
                    }
                } else if (ours) {
                    retained = target + " is read by " + readers;
                    events.warning(table, "TargetRetained", retained);
                }
            }
            // Unpublished, whether the target stays or goes: the declaration no longer governs it.
            if (table.getStatus() != null) {
                publication.withdraw(
                        Publication.mark(Owners.table(table)),
                        table.getStatus().published,
                        clock.get());
            }
            identities.remove(table, KIND);
        } catch (Exception e) {
            LOG.warn("Cleaning up {} failed; retrying", table.getMetadata().getName(), e);
            return DeleteControl.noFinalizerRemoval().rescheduleAfter(RESYNC);
        }
        synchronized (CLAIMS) {
            CLAIMS.remove(table.getMetadata().getUid());
        }
        if (table.getStatus() != null && table.getStatus().applied != null) {
            backlog.forget(table.getStatus().applied.target);
        }
        metrics.forget(
                KIND
                        + ":"
                        + table.getMetadata().getNamespace()
                        + "/"
                        + table.getMetadata().getName());
        events.normal(
                table,
                "Deleted",
                "job stopped, consumers dropped, identity revoked"
                        + ("Delete".equals(spec == null ? null : spec.deletionPolicy)
                                        && retained == null
                                ? ", target dropped"
                                : ", target retained"));
        return DeleteControl.defaultDelete();
    }

    /** Other declarations that read the target. */
    private static List<String> readers(
            String target, LakeTable table, Context<LakeTable> context) {
        List<String> out = new ArrayList<>();
        for (LakeTable other : context.getPrimaryCache().list().toList()) {
            if (!other.getMetadata().getUid().equals(table.getMetadata().getUid())
                    && other.getSpec() != null
                    && other.getSpec().upstream != null
                    && other.getSpec().upstream.contains(target)) {
                out.add(other.getMetadata().getName());
            }
        }
        return out;
    }

    @Override
    public ErrorStatusUpdateControl<LakeTable> updateErrorStatus(
            LakeTable table, Context<LakeTable> context, Exception e) {
        LakeTableStatus status =
                table.getStatus() == null ? new LakeTableStatus() : table.getStatus();
        status.phase = "Degraded";
        status.message =
                "reconcile failed: " + JobObservation.abbreviate(String.valueOf(e.getMessage()));
        table.setStatus(status);
        // Its measures stay as last taken: colvenna_table_measured_timestamp_seconds ages.
        publish(table, status);
        return ErrorStatusUpdateControl.patchStatus(table);
    }
}
