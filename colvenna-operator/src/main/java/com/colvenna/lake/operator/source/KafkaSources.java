package com.colvenna.lake.operator.source;

import com.colvenna.lake.operator.Durations;
import com.colvenna.lake.operator.catalog.Owners;
import com.colvenna.lake.operator.catalog.Publication;
import com.colvenna.lake.operator.crd.Governance;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceSpec;
import com.colvenna.lake.operator.crd.LakeSourceStatus;
import com.colvenna.lake.operator.crd.StatusCondition;
import com.colvenna.lake.operator.governance.GovernancePolicy;
import com.colvenna.lake.operator.kube.Events;
import com.colvenna.lake.operator.lake.LakeReader;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.paimon.types.DataField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The rest of a Kafka source's round, after the shared declaration and catalog steps: its two
 * tables, the cluster as the source's user sees it, the job, and what landed where (HLD 8). Also
 * the probe of a database source's Kafka transport, which sees its cluster the same way.
 */
final class KafkaSources {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaSources.class);

    private final LakeSourceReconciler reconciler;
    private final KubernetesClient kube;
    private final LakeReader lake;
    private final KafkaProbe probe;
    private final Events events;
    private final long maxRows;

    KafkaSources(
            LakeSourceReconciler reconciler,
            KubernetesClient kube,
            LakeReader lake,
            KafkaProbe probe,
            Events events,
            long maxRows) {
        this.reconciler = reconciler;
        this.kube = kube;
        this.lake = lake;
        this.probe = probe;
        this.events = events;
        this.maxRows = maxRows;
    }

    /** Why a Kafka declaration is invalid, or null. */
    static String validate(LakeSourceSpec spec) {
        if (spec.kafka == null || spec.events == null || spec.target.table == null) {
            return "a kafka source declares kafka, events and target.table";
        }
        if (spec.connection != null
                || (spec.tables != null
                        && !(spec.tables.include.isEmpty() && spec.tables.exclude.isEmpty()))) {
            return "a kafka source has no connection or tables; it reads spec.kafka.topics";
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        for (LakeSourceSpec.Field f : spec.events.fields) {
            if (!names.add(f.name)) {
                return "spec.events.fields: " + f.name + " is declared twice";
            }
            if (List.of(
                            "event_time_ms",
                            "dt",
                            "hr",
                            "extra_json",
                            "_topic",
                            "_partition",
                            "_offset")
                    .contains(f.name)) {
                return "spec.events.fields: " + f.name + " is a column the platform adds";
            }
            try {
                KafkaTables.type(f.type);
            } catch (IllegalArgumentException e) {
                return "spec.events.fields: " + e.getMessage();
            }
        }
        if (names.contains(spec.events.eventTime.field)) {
            return "spec.events.eventTime.field is read as the event time; do not declare it too";
        }
        if (spec.job != null
                && spec.job.checkpointInterval != null
                && Durations.millis(spec.job.checkpointInterval, "30s")
                        < MIN_CHECKPOINT_INTERVAL_MS) {
            return "spec.job.checkpointInterval must be at least 10s for a kafka source";
        }
        return null;
    }

    /**
     * A recreated job tells the two tables' commits of one checkpoint from the next by time
     * (com.colvenna.lake.jobs.kafka.StartingOffsets), so checkpoints must be this far apart.
     */
    static final long MIN_CHECKPOINT_INTERVAL_MS = 10_000L;

    /** The tables' state for the job: whether it can run, and on the declaration as it is. */
    private record Tables(boolean ready, boolean applied) {}

    UpdateControl<LakeSource> reconcile(LakeSourceReconciler.Round r, Context<LakeSource> context) {
        LakeSource source = r.source();
        LakeSourceStatus status = r.status();
        String target = r.spec().target.database + "." + r.spec().target.table;
        if (status.appliedTarget != null && !status.appliedTarget.equals(target)) {
            // New tables would leave the old ones, and the offsets they hold, behind.
            String why =
                    "the target is "
                            + status.appliedTarget
                            + "; declare a new source to write "
                            + target;
            if (r.condition("SpecApplied", false, "TargetChanged", why)) {
                events.warning(source, "TargetChanged", why);
            }
        } else {
            Tables tables = tables(r);
            if (tables.ready()) {
                status.appliedTarget = target;
                reconciler.applyJobs(r, context, false);
                if (tables.applied()) {
                    r.condition("SpecApplied", true, "Applied", "the job runs the declaration");
                }
            }
        }
        probe(r);
        reconciler.observeJobs(r, false);
        counts(r);
        govern(r);
        reconciler.finishRotation(r);
        summarize(r);
        status.measuredAt = r.now().toString();
        reconciler.publish(source, status);
        return UpdateControl.patchStatus(source).rescheduleAfter(LakeSourceReconciler.RESYNC);
    }

    /**
     * Judges the two tables' governance and publishes them to the declared readers while they are
     * governed (LLD "治理与个人数据删除"), as a database source's tables. An event source's tables keep no
     * bounded retention and have no erasure (LLD "已知限制"), so personal data, or data no declaration
     * classifies, is not published.
     */
    private void govern(LakeSourceReconciler.Round r) {
        LakeSourceSpec spec = r.spec();
        GovernancePolicy.Verdict v = verdict(spec);
        if (r.condition("Governed", v.governed(), v.reason(), v.message()) && !v.governed()) {
            events.warning(r.source(), "NotGoverned", v.message());
        }
        String db = spec.target.database;
        reconciler.publishTables(
                r,
                v.governed() && StatusCondition.isTrue(r.status().conditions, "TargetReady")
                        ? List.of(
                                db + "." + spec.target.table,
                                db + "." + KafkaTables.rejections(spec))
                        : List.of(),
                v.governed()
                        ? Publication.Condition.withheld(
                                "NoTarget", "the tables are not created yet")
                        : Publication.Condition.withheld("NotGoverned", v.message()));
    }

    /** Whether an event source's tables may be published: governed, and not personal data. */
    static GovernancePolicy.Verdict verdict(LakeSourceSpec spec) {
        Governance g = spec.governance;
        GovernancePolicy.Verdict v =
                GovernancePolicy.verdict(
                        g,
                        g == null
                                ? List.of()
                                : g.subjectKeys.getOrDefault(spec.target.table, List.of()),
                        g == null ? Map.of() : g.labelsOf(spec.target.table),
                        KafkaTables.eventFields(spec).stream().map(DataField::name).toList(),
                        spec.target.tableOptions,
                        null);
        if (v.governed() && Governance.treatedAsPersonal(g)) {
            return new GovernancePolicy.Verdict(
                    false,
                    "PersonalEvents",
                    "an event source's tables keep no bounded retention and have no erasure:"
                            + " personal data is not published");
        }
        return v;
    }

    /**
     * Creates the two tables, or adds new optional fields. A refused change leaves the tables, and
     * the events declaration the job runs, as they were.
     */
    private Tables tables(LakeSourceReconciler.Round r) {
        LakeSourceSpec spec = r.spec();
        String db = spec.target.database;
        String owner = Owners.source(r.source());
        try {
            Optional<List<DataField>> existing = KafkaTables.columns(lake, db, spec.target.table);
            if (existing.isEmpty()) {
                lake.createTable(
                        db,
                        spec.target.table,
                        KafkaTables.eventFields(spec),
                        List.of(),
                        KafkaTables.partitionKeys(spec),
                        KafkaTables.options(spec, owner));
                events.normal(r.source(), "TableCreated", db + "." + spec.target.table);
            } else {
                KafkaTables.Plan plan = KafkaTables.evolve(existing.get(), spec);
                if (plan.refusal() != null) {
                    if (r.condition("SpecApplied", false, "SchemaChanged", plan.refusal())) {
                        events.warning(r.source(), "SchemaChanged", plan.refusal());
                    }
                    // The job keeps the events declaration last applied; without one, it waits.
                    return new Tables(r.status().appliedEvents != null, false);
                }
                String after = null;
                List<DataField> want = KafkaTables.eventFields(spec);
                for (DataField added : plan.added()) {
                    int at = want.indexOf(added);
                    after = after == null ? want.get(at - 1).name() : after;
                    lake.addColumn(db, spec.target.table, added.name(), added.type(), after);
                    after = added.name();
                    events.normal(
                            r.source(),
                            "FieldAdded",
                            db + "." + spec.target.table + " gained " + added.name());
                }
            }
            if (KafkaTables.columns(lake, db, KafkaTables.rejections(spec)).isEmpty()) {
                lake.createTable(
                        db,
                        KafkaTables.rejections(spec),
                        KafkaTables.rejectionFields(),
                        List.of(),
                        List.of("dt"),
                        KafkaTables.options(spec, owner));
                events.normal(r.source(), "TableCreated", db + "." + KafkaTables.rejections(spec));
            }
            r.condition("TargetReady", true, "Ready", "the events and rejections tables exist");
            r.status().appliedEvents = spec.events;
            return new Tables(true, true);
        } catch (Exception e) {
            LOG.warn("Preparing the tables of {} failed", r.source().getMetadata().getName(), e);
            r.condition("TargetReady", false, "CatalogUnavailable", String.valueOf(e.getMessage()));
            return new Tables(false, false);
        }
    }

    /** The cluster as the job's user sees it: reachable, authenticated, topics, lag. */
    void probe(LakeSourceReconciler.Round r) {
        LakeSourceSpec spec = r.spec();
        boolean ok =
                probeKafka(
                        r,
                        spec.kafka.bootstrapServers,
                        spec.kafka.topics,
                        spec.kafka.mechanism,
                        spec.kafka.caConfigMap,
                        spec.kafka.credentialsSecret,
                        false);
        if (r.condition(
                        "SourceReady",
                        ok,
                        ok ? "Reachable" : r.status().kafka.reason,
                        ok ? spec.kafka.topics + " readable" : r.status().kafka.message)
                && !ok) {
            events.warning(
                    r.source(), "Source" + r.status().kafka.reason, r.status().kafka.message);
        }
        if ("CaMissing".equals(r.status().kafka.reason)) {
            // Nothing was checked against a CA that could not be read.
            r.condition("SourceVerified", false, "CaMissing", r.status().kafka.message);
        } else {
            LakeSourceReconciler.sourceVerified(r, events, spec.kafka.caConfigMap != null);
        }
    }

    /**
     * A database source's Kafka transport: its topics as the job's Kafka user sees them, in {@code
     * status.kafka} and the {@code TransportReady} condition. Returns whether it answered.
     */
    boolean probeTransport(LakeSourceReconciler.Round r, List<String> tables) {
        LakeSourceSpec.KafkaTransport k = r.spec().transport.kafka;
        if (tables.isEmpty()) {
            return true;
        }
        boolean ok =
                probeKafka(
                        r,
                        k.bootstrapServers,
                        tables.stream().map(t -> SourceJobs.topic(r.spec(), t)).toList(),
                        k.mechanism,
                        k.caConfigMap,
                        k.credentialsSecret,
                        true);
        if (r.condition(
                        "TransportReady",
                        ok,
                        ok ? "Reachable" : r.status().kafka.reason,
                        ok ? "the tables' topics are readable" : r.status().kafka.message)
                && !ok) {
            events.warning(
                    r.source(), "Transport" + r.status().kafka.reason, r.status().kafka.message);
        }
        return ok;
    }

    /** Reads the cluster into {@code status.kafka}; returns whether every topic answered. */
    private boolean probeKafka(
            LakeSourceReconciler.Round r,
            String bootstrap,
            List<String> topics,
            String mechanism,
            String caConfigMap,
            LakeSourceSpec.SecretRef credentials,
            boolean changeTopics) {
        LakeSourceStatus.KafkaState state = new LakeSourceStatus.KafkaState();
        state.checkedAt = r.now().toString();
        Path ca = null;
        if (caConfigMap != null) {
            ConfigMap cm = kube.configMaps().inNamespace(r.namespace()).withName(caConfigMap).get();
            if (cm == null || cm.getData() == null || cm.getData().get("ca.pem") == null) {
                state.reason = "CaMissing";
                state.message = "ConfigMap " + caConfigMap + " has no ca.pem";
            } else {
                ca = Tls.pem(cm.getData().get("ca.pem"));
            }
        }
        if (state.reason == null) {
            KafkaProbe.Result result;
            try {
                result =
                        probe.probe(
                                bootstrap,
                                topics,
                                SourceJobs.mechanism(mechanism),
                                ca,
                                SourceJobs.consumerGroup(r.source()),
                                SourceTargets.login(kube, r.source(), credentials),
                                changeTopics);
            } catch (IllegalStateException e) {
                result = KafkaProbe.Result.failed("CredentialsMissing", e.getMessage());
            }
            state.reason = result.reason();
            state.message = result.message();
            for (KafkaProbe.Topic t : result.topics()) {
                LakeSourceStatus.TopicState ts = new LakeSourceStatus.TopicState();
                ts.name = t.name();
                ts.partitions = t.partitions();
                ts.endOffset = t.end();
                ts.committedOffset = t.committed();
                ts.lag = t.lag();
                state.topics.add(ts);
            }
        }
        r.status().kafka = state;
        return state.reason == null;
    }

    /** What landed in the two tables, by their latest snapshots. */
    private void counts(LakeSourceReconciler.Round r) {
        LakeSourceSpec spec = r.spec();
        LakeSourceStatus.EventCounts counts = new LakeSourceStatus.EventCounts();
        try {
            counts.accepted = lake.rows(spec.target.database, spec.target.table);
            counts.rejected = lake.rows(spec.target.database, KafkaTables.rejections(spec));
            if (counts.rejected > 0) {
                counts.rejectedBy =
                        lake.countBy(
                                spec.target.database,
                                KafkaTables.rejections(spec),
                                "reason",
                                maxRows);
                long counted = counts.rejectedBy.values().stream().mapToLong(Long::longValue).sum();
                counts.rejectedByPartial = counted < counts.rejected ? true : null;
            }
        } catch (Exception e) {
            LOG.debug("Counting {} failed", spec.target.table, e);
        }
        r.status().events = counts;
    }

    private void summarize(LakeSourceReconciler.Round r) {
        LakeSourceStatus status = r.status();
        String jobReason =
                status.conditions.stream()
                        .filter(c -> c.type.equals("JobRunning"))
                        .map(c -> c.reason)
                        .findFirst()
                        .orElse("");
        boolean running =
                com.colvenna.lake.operator.crd.StatusCondition.isTrue(
                        status.conditions, "JobRunning");
        boolean reachable =
                com.colvenna.lake.operator.crd.StatusCondition.isTrue(
                        status.conditions, "SourceReady");
        long lag =
                status.kafka == null
                        ? 0
                        : status.kafka.topics.stream()
                                .mapToLong(t -> t.lag == null ? 0 : t.lag)
                                .sum();
        status.summary =
                (status.events == null || status.events.accepted == null
                                ? 0
                                : status.events.accepted)
                        + " in, "
                        + (status.events == null || status.events.rejected == null
                                ? 0
                                : status.events.rejected)
                        + " rejected";
        if (jobReason.equals("Suspended")) {
            status.phase = "Suspended";
            status.message = "suspended; the job keeps its offsets";
        } else if (jobReason.equals("Suspending")) {
            status.phase = "Provisioning";
            status.message = "suspending the job";
        } else if (!reachable) {
            status.phase = "Degraded";
            status.message = "source: " + (status.kafka == null ? "" : status.kafka.message);
        } else if (!running) {
            status.phase = status.jobs.isEmpty() ? "Pending" : "Provisioning";
            status.message = "starting the job";
        } else {
            status.phase = "Running";
            status.message = "consuming " + r.spec().kafka.topics + ", lag " + lag;
        }
        r.condition("Ready", "Running".equals(status.phase), status.phase, status.message);
    }
}
