package com.colvenna.lake.operator.task;

import com.colvenna.lake.operator.Durations;
import com.colvenna.lake.operator.OperatorSettings;
import com.colvenna.lake.operator.catalog.CatalogClient;
import com.colvenna.lake.operator.catalog.CatalogException;
import com.colvenna.lake.operator.catalog.CatalogIdentities;
import com.colvenna.lake.operator.catalog.Grant;
import com.colvenna.lake.operator.catalog.Owners;
import com.colvenna.lake.operator.crd.Governance;
import com.colvenna.lake.operator.crd.LakeDashboard;
import com.colvenna.lake.operator.crd.LakeDashboardSpec;
import com.colvenna.lake.operator.crd.LakeDashboardStatus;
import com.colvenna.lake.operator.crd.LakeQuality;
import com.colvenna.lake.operator.crd.LakeQualityStatus;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.crd.LakeTableStatus;
import com.colvenna.lake.operator.crd.LakeTask;
import com.colvenna.lake.operator.crd.LakeTaskSpec;
import com.colvenna.lake.operator.crd.LakeTaskStatus;
import com.colvenna.lake.operator.crd.StatusCondition;
import com.colvenna.lake.operator.dashboard.DashboardSql;
import com.colvenna.lake.operator.flink.FlinkJobs;
import com.colvenna.lake.operator.flink.JobObservation;
import com.colvenna.lake.operator.governance.GovernancePolicy;
import com.colvenna.lake.operator.kube.Events;
import com.colvenna.lake.operator.metrics.Metrics;
import com.colvenna.lake.operator.quality.QualityGates;
import com.colvenna.lake.operator.quality.QualityRules;
import com.colvenna.lake.operator.quality.QualityStore;
import com.colvenna.lake.operator.table.Fence;
import com.colvenna.lake.operator.table.TableJobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a LakeTask once (HLD 9.2), from request to a final phase an orchestrator can wait for. Tasks
 * touching one table run one at a time, in creation order. A backfill first suspends the layered
 * table's job (the fence), rewrites the table, lets the job resume, and succeeds only once every
 * table downstream has read its changes. A compaction of a layered table, whose job compacts it
 * too, runs behind the fence as well. See {@code docs/design/colvenna-operator-lld.md}, "LakeTask".
 */
@ControllerConfiguration(name = "laketask")
public final class LakeTaskReconciler implements Reconciler<LakeTask>, Cleaner<LakeTask> {
    private static final Logger LOG = LoggerFactory.getLogger(LakeTaskReconciler.class);
    public static final String KIND = "laketask";

    static final Duration POLL = Duration.ofSeconds(10);

    /** How long a tag keeps its snapshot when the task does not say. */
    static final String TAG_RETAINED = "7d";

    private static String retained(LakeTaskSpec.CreateTag tag) {
        return tag.timeRetained == null ? TAG_RETAINED : tag.timeRetained;
    }

    /** How far ahead of the operator's clock a check's asOf may be. */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

    /** How long a backfill waits for its readers before it reports them unconverged. */
    static final Duration CONVERGE_TIMEOUT = Duration.ofMinutes(30);

    /** How long a backfill waits for its LakeTable's job to suspend. */
    static final Duration FENCE_TIMEOUT = Duration.ofMinutes(20);

    /**
     * After a reader read past the changes, how long until its commit of them is done: the reader
     * commits what it read at the same checkpoint, and its commit follows within seconds.
     */
    static final Duration COMMIT_GRACE = Duration.ofSeconds(30);

    /** The wait after a first failed attempt; it doubles with each failure, up to the cap. */
    static final Duration RETRY_BACKOFF = Duration.ofMinutes(1);

    /** The longest wait between two attempts. */
    static final Duration RETRY_BACKOFF_CAP = Duration.ofMinutes(10);

    /** How long after the given attempt failed the next one starts. */
    static Duration backoff(int failedAttempt) {
        Duration wait = RETRY_BACKOFF;
        for (int i = 1; i < failedAttempt && wait.compareTo(RETRY_BACKOFF_CAP) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(RETRY_BACKOFF_CAP) > 0 ? RETRY_BACKOFF_CAP : wait;
    }

    public static final String SUCCEEDED = "Succeeded";
    public static final String FAILED = "Failed";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final KubernetesClient kube;
    private final CatalogClient catalog;
    private final CatalogIdentities identities;
    private final TaskRuns runs;
    private final QualityStore quality;
    private final Events events;
    private final Metrics metrics;
    private final Supplier<Instant> clock;

    public LakeTaskReconciler(
            OperatorSettings settings,
            KubernetesClient kube,
            CatalogClient catalog,
            QualityStore quality,
            Metrics metrics,
            Supplier<Instant> clock) {
        this.kube = kube;
        this.catalog = catalog;
        this.identities = new CatalogIdentities(catalog, kube);
        this.runs = new TaskRuns(settings, kube);
        this.quality = quality;
        this.events = new Events(kube);
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public List<EventSource<?, LakeTask>> prepareEventSources(
            EventSourceContext<LakeTask> context) {
        var config =
                InformerEventSourceConfiguration.from(Job.class, LakeTask.class)
                        .withSecondaryToPrimaryMapper(Mappers.fromOwnerReferences(LakeTask.class))
                        .withLabelSelector(FlinkJobs.MANAGED_BY + "=colvenna-operator")
                        .build();
        return List.of(new InformerEventSource<>(config, context));
    }

    /** What one round knows. */
    private record Round(LakeTask task, LakeTaskSpec spec, LakeTaskStatus status, Instant now) {
        String namespace() {
            return task.getMetadata().getNamespace();
        }

        String name() {
            return task.getMetadata().getName();
        }

        String table() {
            return spec.table.qualifiedName();
        }

        boolean backfill() {
            return isBackfill(spec);
        }

        /**
         * This task's fence: its name, and its uid, so that no other task of that name holds it.
         */
        String fence() {
            return task.getMetadata().getName() + "/" + task.getMetadata().getUid();
        }

        void condition(String type, boolean ok, String reason, String message) {
            StatusCondition.set(
                    status.conditions,
                    type,
                    ok,
                    reason,
                    message,
                    Objects.requireNonNullElse(task.getMetadata().getGeneration(), 0L),
                    now);
        }
    }

    @Override
    public UpdateControl<LakeTask> reconcile(LakeTask task, Context<LakeTask> context) {
        LakeTaskStatus status = task.getStatus() == null ? new LakeTaskStatus() : task.getStatus();
        task.setStatus(status);
        Round r = new Round(task, task.getSpec(), status, clock.get());
        // Final: the task ran once; only its identity may still be there to remove.
        if (finished(status) && status.identity == null) {
            return UpdateControl.noUpdate();
        }
        status.observedGeneration = task.getMetadata().getGeneration();
        try {
            if (!finished(status)) {
                step(r);
            }
            if (finished(status) && status.identity != null) {
                // A finished task writes nothing more: its token and grants go now.
                identities.remove(task, KIND);
                status.identity = null;
            }
        } catch (CatalogException e) {
            if (!finished(status)) {
                status.message = "colvenna-catalog: " + e.getMessage();
            }
            r.condition("CatalogReady", false, "CatalogUnavailable", e.getMessage());
        } catch (KubernetesClientException e) {
            // Retried next round, as the catalog's errors are.
            if (!finished(status)) {
                status.message = "Kubernetes API: " + JobObservation.abbreviate(e.getMessage());
            }
        }
        publish(task, status);
        return finished(status) && status.identity == null
                ? UpdateControl.patchStatus(task)
                : UpdateControl.patchStatus(task).rescheduleAfter(POLL);
    }

    static boolean isBackfill(LakeTaskSpec spec) {
        return "backfill".equals(spec.type);
    }

    static boolean isCheck(LakeTaskSpec spec) {
        return "check".equals(spec.type);
    }

    static boolean isCreateTag(LakeTaskSpec spec) {
        return "createTag".equals(spec.type);
    }

    /** Whether a task has ended, as it ends: Succeeded or Failed. */
    public static boolean finished(LakeTaskStatus status) {
        return SUCCEEDED.equals(status.phase) || FAILED.equals(status.phase);
    }

    private void step(Round r) {
        LakeTaskStatus status = r.status();
        status.table = r.table();
        List<LakeTable> tables =
                kube.resources(LakeTable.class).inNamespace(r.namespace()).list().getItems();
        LakeTable layered = r.backfill() ? TableJobs.layeredTable(r.table(), tables) : null;
        // The job of the table a task rewrites (a target, or a quarantine its job writes too): a
        // second writer would conflict with its commits.
        LakeTable fenced =
                r.backfill() || "compact".equals(r.spec().type)
                        ? TableJobs.writer(r.table(), tables)
                        : null;
        if (status.startedAt == null) {
            // Checked once, before the task starts: a started task is not stopped halfway by a
            // change elsewhere.
            if (!admit(r, layered, tables)) {
                return;
            }
            status.startedAt = r.now().toString();
            events.normal(r.task(), "TaskStarted", r.spec().type + " on " + r.table());
        }
        if (r.backfill() && layered == null) {
            gone(r);
            return;
        }
        ensureIdentity(r, layered);
        if (status.result == null) {
            if (fenced != null && !holdFence(r, fenced)) {
                return;
            }
            run(r, layered, fenced);
        }
        if (status.result != null && layered != null && !finished(status)) {
            lift(r);
            converge(r, layered, tables);
        }
    }

    /**
     * Whether the task may start: its spec and table are valid for it and no task touching the same
     * tables comes first. Fails the task, or leaves it Pending, otherwise.
     */
    private boolean admit(Round r, LakeTable layered, List<LakeTable> tables) {
        String invalid = validate(r.spec());
        if (invalid == null
                && isCheck(r.spec())
                && r.spec().check != null
                && r.spec().check.asOf != null
                && Instant.parse(r.spec().check.asOf).isAfter(r.now().plus(CLOCK_SKEW))) {
            invalid =
                    "check.asOf "
                            + r.spec().check.asOf
                            + " is in the future: freshness is measured at a time already past";
        }
        if (invalid != null) {
            fail(r, "InvalidSpec", invalid);
            return false;
        }
        Optional<JsonNode> table = catalog.table(r.spec().table.database, r.spec().table.table);
        if (table.isEmpty()) {
            fail(r, "TableMissing", r.table() + " does not exist");
            return false;
        }
        JsonNode options = table.get().path("schema").path("options");
        String tableOwner = options.path(Owners.OPTION).asText(null);
        String databaseOwner =
                catalog.databaseOptions(r.spec().table.database)
                        .map(o -> o.get(Owners.OPTION))
                        .orElse(null);
        if (!Owners.isSourceOf(databaseOwner, r.namespace())
                && !Owners.isTableOf(tableOwner, r.namespace())) {
            fail(
                    r,
                    "NotManaged",
                    r.table()
                            + " is neither an ODS table of a LakeSource nor a LakeTable target in"
                            + " namespace "
                            + r.namespace());
            return false;
        }
        if (r.backfill()) {
            if (layered == null || !Owners.table(layered).equals(tableOwner)) {
                fail(
                        r,
                        "NotALakeTable",
                        "a backfill recomputes a LakeTable's target; " + r.table() + " is not one");
                return false;
            }
            if (!StatusCondition.isTrue(layered.getStatus().conditions, "SpecApplied")) {
                fail(
                        r,
                        "TableNotApplied",
                        "the LakeTable's declaration is not applied: its job does not run the"
                                + " query a backfill would");
                return false;
            }
            LakeTableStatus.Validation validation = layered.getStatus().validation;
            if (validation == null || !Boolean.FALSE.equals(validation.stateful)) {
                fail(
                        r,
                        "StatefulQuery",
                        "the LakeTable's job keeps state (an aggregate or a join) that a backfill"
                                + " of its target would not follow: once resumed it would carry"
                                + " on from the old state; declare a new table instead");
                return false;
            }
        }
        if (isCreateTag(r.spec())) {
            // A table no declaration classifies counts as personal data (temporary L4).
            Governance g =
                    GovernancePolicy.Declared.of(
                                    kube.resources(LakeSource.class)
                                            .inNamespace(r.namespace())
                                            .list()
                                            .getItems(),
                                    tables)
                            .governanceOf(r.table());
            String retained = retained(r.spec().createTag);
            if (Governance.treatedAsPersonal(g)
                    && Duration.ofMillis(Durations.millis(retained, TAG_RETAINED))
                                    .compareTo(GovernancePolicy.MAX_RETENTION)
                            > 0) {
                fail(
                        r,
                        "RetentionTooLong",
                        r.table()
                                + " holds personal data, or is not classified: a tag may keep its"
                                + " snapshot "
                                + GovernancePolicy.MAX_RETENTION.toHours()
                                + " h at most, not "
                                + retained);
                return false;
            }
        }
        if ("compact".equals(r.spec().type)
                && !"true".equals(options.path("write-only").asText())
                && TableJobs.writer(r.table(), tables) == null) {
            fail(
                    r,
                    "WriterCompacts",
                    r.table()
                            + "'s writer compacts it (not write-only) and is no LakeTable's job the"
                            + " task could suspend; a second compactor would conflict with its"
                            + " commits");
            return false;
        }
        String ahead = blockingTask(r, tables);
        if (ahead != null) {
            setPhase(r, "Pending", "waiting for task " + ahead + ", which touches " + r.table());
            return false;
        }
        // A tag names the latest snapshot: a table that has committed none (a LakeTable that has
        // just started, say) has nothing to tag, which no attempt would change. Looked at once the
        // tasks ahead are done (unlike the tag's retention above): one of them may commit it.
        if (isCreateTag(r.spec())
                && latestSnapshot(r, "to tag yet; create the task again once it has committed one")
                        .isEmpty()) {
            return false;
        }
        return !isCheck(r.spec()) || admitCheck(r);
    }

    /** The table's latest snapshot; the task fails as NoSnapshot if there is none. */
    private OptionalLong latestSnapshot(Round r, String missing) {
        OptionalLong latest =
                catalog.latestSnapshotId(r.spec().table.database, r.spec().table.table);
        if (latest.isEmpty()) {
            fail(r, "NoSnapshot", r.table() + " has no snapshot " + missing);
        }
        return latest;
    }

    /**
     * Whether a check may start: its table has valid rules, its partitions are the table's and its
     * snapshot is kept. Fixes what every attempt checks: the snapshot, the time freshness is
     * measured at and the rules' version.
     */
    private boolean admitCheck(Round r) {
        LakeTaskSpec.Check c = r.spec().check == null ? new LakeTaskSpec.Check() : r.spec().check;
        Optional<LakeQuality> gate =
                QualityGates.gateOf(QualityGates.of(kube, r.namespace()), r.table());
        if (gate.isEmpty()) {
            fail(r, "NoRules", r.table() + " has no LakeQuality");
            return false;
        }
        LakeQualityStatus judged = gate.get().getStatus();
        if (judged == null
                || !Objects.equals(
                        judged.observedGeneration, gate.get().getMetadata().getGeneration())) {
            // Declared together with the check, or just changed: its rules are judged next.
            setPhase(
                    r,
                    "Pending",
                    "waiting for LakeQuality "
                            + gate.get().getMetadata().getName()
                            + " to judge its rules");
            return false;
        }
        if (!StatusCondition.isTrue(judged.conditions, "RulesValid")) {
            fail(
                    r,
                    "InvalidRules",
                    "LakeQuality "
                            + gate.get().getMetadata().getName()
                            + "'s rules do not fit "
                            + r.table()
                            + ": "
                            + judged.message);
            return false;
        }
        String database = r.spec().table.database;
        String table = r.spec().table.table;
        Long snapshot = c.snapshot;
        try {
            Optional<QualityStore.Columns> columns = quality.columns(database, table);
            if (columns.isEmpty()) {
                fail(r, "TableMissing", r.table() + " does not exist");
                return false;
            }
            String invalid =
                    QualityRules.partitionsProblem(c.partitions, columns.get().partitionKeys());
            if (invalid != null) {
                fail(r, "InvalidPartitions", invalid);
                return false;
            }
            if (snapshot == null) {
                OptionalLong latest = latestSnapshot(r, "to check");
                if (latest.isEmpty()) {
                    return false;
                }
                snapshot = latest.getAsLong();
            } else if (!quality.snapshotExists(database, table, snapshot)) {
                fail(r, "SnapshotMissing", r.table() + " no longer keeps snapshot " + snapshot);
                return false;
            }
        } catch (CatalogException | KubernetesClientException e) {
            throw e;
        } catch (Exception e) {
            setPhase(
                    r,
                    "Pending",
                    "reading "
                            + r.table()
                            + ": "
                            + JobObservation.abbreviate(String.valueOf(e.getMessage())));
            return false;
        }
        LakeTaskStatus.CheckState pinned = new LakeTaskStatus.CheckState();
        pinned.quality = gate.get().getMetadata().getName();
        // The version of the rules as declared, which every attempt must still find.
        pinned.ruleVersion = QualityRules.version(gate.get().getSpec().rules);
        pinned.snapshot = snapshot;
        pinned.asOf = c.asOf != null ? Instant.parse(c.asOf).toString() : r.now().toString();
        pinned.partitions = c.partitions.stream().sorted().toList();
        r.status().check = pinned;
        return true;
    }

    /** Why the spec cannot run, or null. */
    static String validate(LakeTaskSpec spec) {
        if (spec == null || spec.type == null || spec.table == null) {
            return "spec.type and spec.table are required";
        }
        if (spec.type.equals("createTag")
                && (spec.createTag == null || spec.createTag.name == null)) {
            return "a createTag task names its tag in spec.createTag.name";
        }
        if (spec.type.equals("deleteTag")
                && (spec.deleteTag == null || spec.deleteTag.name == null)) {
            return "a deleteTag task names its tag in spec.deleteTag.name";
        }
        return null;
    }

    /** The tables a task writes or reads: a backfill also reads its LakeTable's upstream. */
    private static Set<String> touched(LakeTask task, List<LakeTable> tables) {
        Set<String> out = new HashSet<>();
        out.add(task.getSpec().table.qualifiedName());
        if (isBackfill(task.getSpec())) {
            LakeTable layered =
                    TableJobs.layeredTable(task.getSpec().table.qualifiedName(), tables);
            if (layered != null) {
                out.addAll(layered.getSpec().upstream);
                // It rewrites the quarantine of a table with checks too.
                String quarantine = TableJobs.quarantineQualified(layered.getSpec());
                if (quarantine != null) {
                    out.add(quarantine);
                }
            }
        }
        return out;
    }

    /**
     * An unfinished task touching a table this one touches that started already or was created
     * first, or null.
     */
    private String blockingTask(Round r, List<LakeTable> tables) {
        Comparator<LakeTask> order =
                Comparator.comparing((LakeTask t) -> t.getMetadata().getCreationTimestamp())
                        .thenComparing(t -> t.getMetadata().getUid());
        Set<String> mine = touched(r.task(), tables);
        for (LakeTask other :
                kube.resources(LakeTask.class).inNamespace(r.namespace()).list().getItems()) {
            if (other.getMetadata().getUid().equals(r.task().getMetadata().getUid())
                    || other.getSpec() == null
                    || other.getSpec().table == null
                    || (other.getStatus() != null && finished(other.getStatus()))
                    || Collections.disjoint(mine, touched(other, tables))) {
                continue;
            }
            boolean started = other.getStatus() != null && other.getStatus().startedAt != null;
            if (started || order.compare(other, r.task()) < 0) {
                return other.getMetadata().getName();
            }
        }
        return null;
    }

    private void ensureIdentity(Round r, LakeTable layered) {
        String principal = CatalogIdentities.principalName(KIND, r.task());
        String approval = r.task().getMetadata().getUid();
        List<Grant> grants = new ArrayList<>();
        // A check reads its table and writes only its results.
        grants.add(
                Grant.table(
                        principal,
                        r.spec().table.database,
                        r.spec().table.table,
                        isCheck(r.spec()) ? "READ" : "WRITE",
                        approval));
        if (isCheck(r.spec())) {
            grants.add(
                    Grant.table(
                            principal,
                            QualityStore.database(r.namespace()),
                            QualityStore.RESULTS,
                            "WRITE",
                            approval));
        }
        if (layered != null) {
            for (String upstream : layered.getSpec().upstream) {
                String[] parts = TableJobs.split(upstream);
                grants.add(Grant.table(principal, parts[0], parts[1], "READ", approval));
            }
            // A backfill (the only task with a layered table) of a table with checks rewrites its
            // quarantine as well.
            String quarantine = TableJobs.quarantineQualified(layered.getSpec());
            if (quarantine != null) {
                String[] parts = TableJobs.split(quarantine);
                grants.add(Grant.table(principal, parts[0], parts[1], "WRITE", approval));
            }
        }
        r.status().identity =
                identities.ensure(
                        r.task(),
                        KIND,
                        "LakeTask " + r.namespace() + "/" + r.name(),
                        grants,
                        r.status().identity,
                        r.now());
        r.condition("CatalogReady", true, "Ready", "identity, token and grants are current");
    }

    /**
     * Holds the fence on the LakeTable and reports whether it holds: the table's reconciler saw it
     * and the job is suspended with a savepoint, so the batch run is the target's only writer.
     */
    private boolean holdFence(Round r, LakeTable layered) {
        if (r.status().fence == null) {
            LakeTaskStatus.Fence fence = new LakeTaskStatus.Fence();
            fence.lakeTable = layered.getMetadata().getName();
            fence.fencedAt = r.now().toString();
            r.status().fence = fence;
            events.normal(
                    r.task(),
                    "Fencing",
                    "suspending "
                            + fence.lakeTable
                            + "'s job with a savepoint before the backfill");
        }
        if (!r.fence().equals(Fence.holder(layered))) {
            Fence.hold(kube, layered, r.fence());
        }
        FlinkDeployment fd =
                kube.resources(FlinkDeployment.class)
                        .inNamespace(r.namespace())
                        .withName(TableJobs.deploymentName(layered))
                        .get();
        if (!Fence.holds(layered, r.fence()) || fd == null || !JobObservation.suspended(fd)) {
            if (Instant.parse(r.status().fence.fencedAt).plus(FENCE_TIMEOUT).isBefore(r.now())) {
                fail(
                        r,
                        "FenceTimeout",
                        layered.getMetadata().getName()
                                + "'s job did not suspend within "
                                + FENCE_TIMEOUT.toMinutes()
                                + " min: "
                                + layered.getStatus().message);
                return false;
            }
            setPhase(
                    r,
                    "Fencing",
                    "waiting for " + layered.getMetadata().getName() + "'s job to suspend");
            return false;
        }
        return true;
    }

    /** Lifts the fence: the LakeTable's job resumes from its savepoint. */
    private void lift(Round r) {
        LakeTaskStatus.Fence fence = r.status().fence;
        if (fence == null || fence.releasedAt != null) {
            return;
        }
        LakeTable layered =
                kube.resources(LakeTable.class)
                        .inNamespace(r.namespace())
                        .withName(fence.lakeTable)
                        .get();
        if (layered != null && r.fence().equals(Fence.holder(layered))) {
            Fence.lift(kube, layered);
        }
        fence.releasedAt = r.now().toString();
        events.normal(
                r.task(), "FenceReleased", fence.lakeTable + "'s job resumes from its savepoint");
    }

    /** The backfill's LakeTable went away; the task fails once its run has stopped. */
    private void gone(Round r) {
        Integer attempt = r.status().attempts;
        if (attempt != null && runs.active(r.task(), attempt)) {
            runs.delete(r.task(), attempt);
            setPhase(r, r.status().phase, "the LakeTable is gone; stopping the run");
            return;
        }
        fail(r, "TableGone", "the LakeTable whose target this backfill rewrites was deleted");
    }

    /**
     * Starts or follows the current attempt; retries a failed one while attempts remain.
     *
     * @param layered a backfill's LakeTable
     * @param fenced the LakeTable whose job is suspended while the task runs, or null
     */
    private void run(Round r, LakeTable layered, LakeTable fenced) {
        LakeTaskStatus status = r.status();
        int attempt = status.attempts == null ? 1 : status.attempts;
        status.attempts = attempt;
        Duration timeout = Duration.ofMillis(Durations.millis(r.spec().timeout, "1h"));
        String argument;
        if (isCheck(r.spec())) {
            Optional<LakeQuality> gate =
                    QualityGates.gateOf(QualityGates.of(kube, r.namespace()), r.table());
            if (gate.isEmpty()
                    || !status.check.ruleVersion.equals(
                            QualityRules.version(gate.get().getSpec().rules))) {
                if (!runs.active(r.task(), attempt)) {
                    fail(
                            r,
                            "RulesChanged",
                            r.table()
                                    + "'s rules are no longer version "
                                    + status.check.ruleVersion
                                    + ", which the check started with; check again");
                    return;
                }
                argument = null;
            } else {
                argument = checkArgument(r.task(), status.check, gate.get());
            }
        } else {
            argument = argument(r.spec(), layered);
        }
        // An attempt that ended is not made again, its Job deleted or not: the task only waits for
        // the next one.
        if (argument != null && endOf(status, attempt).isEmpty()) {
            runs.start(r.task(), argument, attempt, timeout);
        }
        status.run = TaskRuns.name(r.task(), attempt);
        TaskRuns.Outcome outcome = runs.observe(r.task(), attempt);
        switch (outcome.state()) {
            case RUNNING ->
                    setPhase(r, "Running", "attempt " + attempt + " runs as Job " + status.run);
            case SUCCEEDED -> {
                ended(r, attempt, SUCCEEDED, outcome.result().detail);
                status.result = outcome.result();
                if (isCheck(r.spec())) {
                    verdict(r, outcome.message());
                }
                if (layered == null) {
                    if (fenced != null) {
                        lift(r);
                    }
                    succeed(r, outcome.result().detail);
                } else {
                    setPhase(r, "Converging", "the backfill committed; waiting for its readers");
                }
            }
            case FAILED -> {
                // The next attempt, or the table's job, writes only once this pod has stopped.
                if (runs.active(r.task(), attempt)) {
                    setPhase(
                            r,
                            "Running",
                            "attempt "
                                    + attempt
                                    + " failed ("
                                    + outcome.error()
                                    + "); waiting for its pod to stop");
                    return;
                }
                boolean first = ended(r, attempt, FAILED, outcome.error());
                int retries = r.spec().retries == null ? 2 : r.spec().retries;
                if (attempt <= retries) {
                    LakeTaskStatus.Attempt failed = endOf(status, attempt).orElseThrow();
                    if (first) {
                        events.warning(r.task(), "AttemptFailed", retrying(failed));
                    }
                    if (r.now().isBefore(retryAt(failed))) {
                        setPhase(r, "Running", retrying(failed));
                        return;
                    }
                    // The next attempt is counted, and its Job made, in the same round: attempts
                    // and run always name an attempt that exists.
                    status.attempts = attempt + 1;
                    run(r, layered, fenced);
                    return;
                }
                fail(
                        r,
                        "RunFailed",
                        "attempt " + attempt + " of " + (retries + 1) + ": " + outcome.error());
            }
        }
    }

    /** The recorded end of the given attempt, or empty while it has not ended. */
    private static Optional<LakeTaskStatus.Attempt> endOf(LakeTaskStatus status, int attempt) {
        return status.history.stream().filter(a -> Objects.equals(a.attempt, attempt)).findFirst();
    }

    /** When the attempt after a failed one starts: {@link #backoff} after it ended. */
    private static Instant retryAt(LakeTaskStatus.Attempt failed) {
        return Instant.parse(failed.finishedAt).plus(backoff(failed.attempt));
    }

    /**
     * The task's message while it waits to retry: why the attempt failed, and when the next starts.
     */
    private static String retrying(LakeTaskStatus.Attempt failed) {
        return "attempt "
                + failed.attempt
                + " failed: "
                + failed.message
                + "; attempt "
                + (failed.attempt + 1)
                + " starts at "
                + retryAt(failed);
    }

    /** Records an attempt's end, once; whether it was recorded now. */
    private static boolean ended(Round r, int attempt, String end, String message) {
        List<LakeTaskStatus.Attempt> history = r.status().history;
        if (history.stream().anyMatch(a -> Objects.equals(a.attempt, attempt))) {
            return false;
        }
        LakeTaskStatus.Attempt a = new LakeTaskStatus.Attempt();
        a.attempt = attempt;
        a.run = TaskRuns.name(r.task(), attempt);
        a.finishedAt = r.now().toString();
        a.outcome = end;
        a.message = message;
        history.add(a);
        return true;
    }

    /**
     * Succeeds once the LakeTable's job runs again and every table downstream has read the
     * backfill's changes; reports those that have not when that takes too long. The changes travel
     * table by table ({@code status.frontier}): once a reader has read past every snapshot carrying
     * them that it streams, its own commit carries them, and its readers must read past that. The
     * commit is looked up {@link #COMMIT_GRACE} later, by when it is done.
     */
    private void converge(Round r, LakeTable layered, List<LakeTable> tables) {
        LakeTaskStatus status = r.status();
        Map<String, LakeTaskStatus.Reach> frontier = status.frontier;
        long commit = status.result.snapshotAfter == null ? 0 : status.result.snapshotAfter;
        if (!frontier.containsKey(r.table())) {
            LakeTaskStatus.Reach root = new LakeTaskStatus.Reach();
            root.snapshot = commit;
            frontier.put(r.table(), root);
        }
        for (Map.Entry<String, LakeTaskStatus.Reach> e : frontier.entrySet()) {
            LakeTaskStatus.Reach reach = e.getValue();
            if (reach.snapshot == null
                    && !Instant.parse(reach.readAt).plus(COMMIT_GRACE).isAfter(r.now())) {
                String[] parts = TableJobs.split(e.getKey());
                OptionalLong latest = catalog.latestSnapshotId(parts[0], parts[1]);
                reach.snapshot = latest.isPresent() ? latest.getAsLong() : 0;
            }
        }
        List<String> waiting = new ArrayList<>();
        if (!Boolean.TRUE.equals(layered.getSpec().suspend)
                && !"Running".equals(layered.getStatus().phase)) {
            waiting.add(layered.getMetadata().getName() + " (not running again yet)");
        }
        for (LakeTable reader : tables) {
            // Only a stream has a position; a lookup reads the table as it is at each lookup.
            List<String> carrying =
                    reader.getStatus() == null || reader.getStatus().applied == null
                            ? List.of()
                            : reader.getStatus().applied.streams.stream()
                                    .filter(frontier::containsKey)
                                    .sorted()
                                    .toList();
            if (carrying.isEmpty()) {
                continue;
            }
            List<String> behind = new ArrayList<>();
            for (String table : carrying) {
                Long snapshot = frontier.get(table).snapshot;
                LakeTableStatus.Upstream position =
                        reader.getStatus().upstream.stream()
                                .filter(u -> table.equals(u.table))
                                .findFirst()
                                .orElse(null);
                if (snapshot == null) {
                    behind.add(table + "'s commit of the changes is not known yet");
                } else if (position == null
                        || position.nextSnapshot == null
                        || position.nextSnapshot <= snapshot) {
                    behind.add(
                            "reads "
                                    + table
                                    + " from "
                                    + (position == null ? "?" : position.nextSnapshot)
                                    + ", the changes are in "
                                    + snapshot);
                }
            }
            String target = TableJobs.target(reader.getSpec());
            LakeTaskStatus.Reach reach = frontier.get(target);
            if (!behind.isEmpty()) {
                waiting.add(
                        reader.getMetadata().getName() + " (" + String.join("; ", behind) + ")");
            } else if (reach == null || !reach.via.containsAll(carrying)) {
                // Past all of them now, or past one more than when last looked: its commit of what
                // it read is looked up afresh.
                reach = new LakeTaskStatus.Reach();
                reach.readAt = r.now().toString();
                reach.via = new ArrayList<>(carrying);
                frontier.put(target, reach);
                waiting.add(target + " (its commit of the changes is looked up next)");
            } else if (reach.snapshot == null) {
                waiting.add(target + " (its commit of the changes is looked up next)");
            }
        }
        waiting.addAll(copiesBehind(r, frontier));
        status.waitingFor = waiting;
        if (waiting.isEmpty()) {
            succeed(
                    r,
                    status.result.detail
                            + "; every table downstream has read it, every dashboard copy of"
                            + " them refreshed since");
            return;
        }
        Instant released = Instant.parse(status.fence.releasedAt);
        if (released.plus(CONVERGE_TIMEOUT).isBefore(r.now())) {
            fail(
                    r,
                    "NeedsReconciliation",
                    "the backfill committed snapshot "
                            + commit
                            + " but these have not converged within "
                            + CONVERGE_TIMEOUT.toMinutes()
                            + " min: "
                            + waiting);
            return;
        }
        setPhase(r, "Converging", "waiting for " + waiting);
    }

    /**
     * The dashboards' materialized views and internal tables over tables carrying the backfill's
     * changes that have not refreshed since (HLD 10.1: a derived copy is part of completion). A
     * table carried them once its commit of them was done: the backfilled table when its fence was
     * released, a reader's table {@link #COMMIT_GRACE} after the reader had read them.
     */
    private List<String> copiesBehind(Round r, Map<String, LakeTaskStatus.Reach> frontier) {
        List<String> out = new ArrayList<>();
        Instant released = Instant.parse(r.status().fence.releasedAt);
        for (LakeDashboard d :
                kube.resources(LakeDashboard.class)
                        .inNamespace(r.task().getMetadata().getNamespace())
                        .list()
                        .getItems()) {
            if (d.getSpec() == null) {
                continue;
            }
            for (LakeDashboardSpec.Query q : d.getSpec().queries) {
                if (!q.copies()) {
                    continue;
                }
                String name = d.getMetadata().getName() + "/" + q.name;
                Instant needed = null;
                String carried = null;
                boolean unknown = false;
                for (String table : DashboardSql.tablesRead(q.sql, d.getSpec().tables)) {
                    LakeTaskStatus.Reach reach = frontier.get(table);
                    if (reach == null) {
                        continue;
                    }
                    if (reach.snapshot == null) {
                        unknown = true;
                        continue;
                    }
                    Instant at =
                            table.equals(r.table())
                                    ? released
                                    : Instant.parse(reach.readAt).plus(COMMIT_GRACE);
                    if (needed == null || at.isAfter(needed)) {
                        needed = at;
                        carried = table;
                    }
                }
                if (unknown) {
                    out.add(name + " (a table it reads has not committed the changes yet)");
                    continue;
                }
                if (needed == null) {
                    continue;
                }
                LakeDashboardStatus.QueryState served =
                        d.getStatus() == null ? null : d.getStatus().query(q.name).orElse(null);
                if (served == null
                        || served.refreshedAt == null
                        || Instant.parse(served.refreshedAt).isBefore(needed)) {
                    out.add(
                            name
                                    + " (refreshed "
                                    + (served == null || served.refreshedAt == null
                                            ? "never"
                                            : "from " + served.refreshedAt)
                                    + "; "
                                    + carried
                                    + " carried the changes at "
                                    + needed
                                    + ")");
                }
            }
        }
        return out;
    }

    /** The runner's argument: the task, and for a backfill the LakeTable's query and key. */
    static String argument(LakeTaskSpec spec, LakeTable layered) {
        Map<String, Object> arg = new LinkedHashMap<>();
        arg.put("type", spec.type);
        arg.put("database", spec.table.database);
        arg.put("table", spec.table.table);
        switch (spec.type) {
            case "compact" -> {
                arg.put("full", spec.compact != null && Boolean.TRUE.equals(spec.compact.full));
                arg.put("partitions", spec.compact == null ? List.of() : spec.compact.partitions);
                if (spec.compact != null && spec.compact.manifests != null) {
                    arg.put("manifests", spec.compact.manifests);
                }
            }
            case "expireSnapshots" -> {
                LakeTaskSpec.ExpireSnapshots e = spec.expireSnapshots;
                arg.put("retainMin", e == null || e.retainMin == null ? 10 : e.retainMin);
                arg.put("olderThanMillis", Durations.millis(e == null ? null : e.olderThan, "1h"));
            }
            case "removeOrphanFiles" -> {
                LakeTaskSpec.RemoveOrphanFiles o = spec.removeOrphanFiles;
                arg.put("olderThanMillis", Durations.millis(o == null ? null : o.olderThan, "1d"));
            }
            case "deleteTag" -> arg.put("tag", spec.deleteTag.name);
            case "createTag" -> {
                arg.put("tag", spec.createTag.name);
                String retained = retained(spec.createTag);
                // Paimon's duration syntax: "7 d", "12 h".
                arg.put(
                        "timeRetained",
                        retained.substring(0, retained.length() - 1)
                                + " "
                                + retained.charAt(retained.length() - 1));
            }
            case "backfill" -> {
                arg.put("query", layered.getSpec().query);
                arg.put("primaryKey", layered.getSpec().primaryKey);
                // The rows failing a check go to the quarantine, as the job routes them.
                if (layered.getSpec().quality != null) {
                    arg.put("quality", TableJobs.quality(layered.getSpec()));
                }
            }
            default -> throw new IllegalArgumentException("Unknown task type " + spec.type);
        }
        return json(arg);
    }

    /**
     * A check's argument: its fixed inputs, the rules in their canonical form, and the store its
     * results go to under its task's uid.
     */
    static String checkArgument(
            LakeTask task, LakeTaskStatus.CheckState pinned, LakeQuality rules) {
        Map<String, Object> arg = new LinkedHashMap<>();
        arg.put("type", "check");
        arg.put("database", task.getSpec().table.database);
        arg.put("table", task.getSpec().table.table);
        arg.put("task", task.getMetadata().getName());
        arg.put("taskUid", task.getMetadata().getUid());
        arg.put("snapshot", pinned.snapshot);
        arg.put("asOfMillis", Instant.parse(pinned.asOf).toEpochMilli());
        arg.put("partitions", pinned.partitions);
        arg.put("ruleVersion", pinned.ruleVersion);
        arg.put("rules", QualityRules.canonical(rules.getSpec().rules));
        arg.put("store", QualityStore.database(task.getMetadata().getNamespace()));
        arg.put("results", QualityStore.RESULTS);
        return json(arg);
    }

    /** Takes a check's verdict from its run's termination message. */
    private void verdict(Round r, JsonNode message) {
        LakeTaskStatus.CheckState check = r.status().check;
        JsonNode v = message == null ? null : message.path("check");
        if (v == null || v.isMissingNode() || !v.hasNonNull("verdict")) {
            return;
        }
        check.verdict = v.path("verdict").asText();
        List<String> failed = new ArrayList<>();
        v.path("failed").forEach(f -> failed.add(f.asText()));
        check.failed = failed;
        check.rows = v.hasNonNull("rows") ? v.path("rows").asLong() : null;
        if ("Passed".equals(check.verdict)) {
            events.normal(
                    r.task(),
                    "QualityPassed",
                    r.table() + " passed every rule at snapshot " + check.snapshot);
        } else {
            events.warning(
                    r.task(),
                    "QualityFailed",
                    r.table() + " failed " + failed + " at snapshot " + check.snapshot);
        }
    }

    private void setPhase(Round r, String phase, String message) {
        r.status().phase = phase;
        r.status().message = message;
    }

    private void succeed(Round r, String detail) {
        setPhase(r, SUCCEEDED, detail);
        r.status().finishedAt = r.now().toString();
        r.condition("Finished", true, SUCCEEDED, detail);
        events.normal(
                r.task(), "TaskSucceeded", r.spec().type + " on " + r.table() + ": " + detail);
    }

    /** Ends the task as Failed; a fence it holds is lifted (no run of it is active by then). */
    private void fail(Round r, String reason, String message) {
        lift(r);
        setPhase(r, FAILED, message);
        r.status().finishedAt = r.now().toString();
        r.condition("Finished", false, reason, message);
        events.warning(r.task(), "Task" + reason, message);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void publish(LakeTask task, LakeTaskStatus status) {
        String id = task.getMetadata().getNamespace() + "/" + task.getMetadata().getName();
        List<Metrics.Sample> samples = new ArrayList<>();
        for (String phase :
                List.of("Pending", "Fencing", "Running", "Converging", SUCCEEDED, FAILED)) {
            samples.add(
                    new Metrics.Sample(
                            "colvenna_task_phase",
                            "1 for the task's current phase",
                            Map.of(
                                    "namespace",
                                    task.getMetadata().getNamespace(),
                                    "laketask",
                                    task.getMetadata().getName(),
                                    "type",
                                    task.getSpec() == null
                                            ? ""
                                            : String.valueOf(task.getSpec().type),
                                    "table",
                                    task.getSpec() == null || task.getSpec().table == null
                                            ? ""
                                            : task.getSpec().table.qualifiedName(),
                                    "phase",
                                    phase),
                            phase.equals(status.phase) ? 1 : 0));
        }
        metrics.publish(KIND + ":" + id, samples);
    }

    @Override
    public DeleteControl cleanup(LakeTask task, Context<LakeTask> context) {
        LakeTaskStatus status = task.getStatus();
        try {
            if (status != null && status.fence != null && status.fence.releasedAt == null) {
                // The run's pod must stop before the table's job writes again.
                if (status.attempts != null && runs.active(task, status.attempts)) {
                    runs.delete(task, status.attempts);
                    return DeleteControl.noFinalizerRemoval().rescheduleAfter(POLL);
                }
                lift(new Round(task, task.getSpec(), status, clock.get()));
            }
            identities.remove(task, KIND);
        } catch (Exception e) {
            LOG.warn("Cleaning up {} failed; retrying", task.getMetadata().getName(), e);
            return DeleteControl.noFinalizerRemoval().rescheduleAfter(POLL);
        }
        metrics.forget(
                KIND
                        + ":"
                        + task.getMetadata().getNamespace()
                        + "/"
                        + task.getMetadata().getName());
        return DeleteControl.defaultDelete();
    }

    @Override
    public ErrorStatusUpdateControl<LakeTask> updateErrorStatus(
            LakeTask task, Context<LakeTask> context, Exception e) {
        LakeTaskStatus status = task.getStatus() == null ? new LakeTaskStatus() : task.getStatus();
        status.message =
                "reconcile failed: " + JobObservation.abbreviate(String.valueOf(e.getMessage()));
        task.setStatus(status);
        return ErrorStatusUpdateControl.patchStatus(task);
    }
}
