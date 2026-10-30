package com.colvenna.lake.operator.quality;

import com.colvenna.lake.operator.Durations;
import com.colvenna.lake.operator.catalog.CatalogClient;
import com.colvenna.lake.operator.catalog.Owners;
import com.colvenna.lake.operator.crd.LakeQuality;
import com.colvenna.lake.operator.crd.LakeQualitySpec;
import com.colvenna.lake.operator.crd.LakeQualityStatus;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.crd.LakeTask;
import com.colvenna.lake.operator.crd.LakeWaiver;
import com.colvenna.lake.operator.crd.LakeWaiverStatus;
import com.colvenna.lake.operator.crd.StatusCondition;
import com.colvenna.lake.operator.flink.JobObservation;
import com.colvenna.lake.operator.governance.GovernancePolicy;
import com.colvenna.lake.operator.kube.Events;
import com.colvenna.lake.operator.metrics.Metrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Judges a table's quality gate from its checks' results (LLD "批量质量校验"): checks the rules against
 * the table, keeps the namespace's quality store, judges the waivers of the table's checks, tags
 * the snapshots of failed checks and records each change of the gate. Publishers read the gate from
 * the status ({@link QualityGates}).
 */
@ControllerConfiguration(name = "lakequality")
public final class LakeQualityReconciler implements Reconciler<LakeQuality>, Cleaner<LakeQuality> {
    private static final Logger LOG = LoggerFactory.getLogger(LakeQualityReconciler.class);
    public static final String KIND = "lakequality";

    public static final String FAILED = "Failed";

    static final Duration POLL = Duration.ofSeconds(15);
    static final int KEPT = 20;

    /**
     * Decisions the store has not taken yet stay in the status up to this many; a longer outage of
     * the store loses the oldest of them from its record (they were events and status meanwhile).
     */
    static final int KEPT_UNRECORDED = 100;

    /** How long a failed check's tag keeps its snapshot, unless the table keeps history less. */
    static final Duration TAG_KEPT = Duration.ofDays(7);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final KubernetesClient kube;
    private final CatalogClient catalog;
    private final QualityStore store;
    private final Events events;
    private final Metrics metrics;
    private final Supplier<Instant> clock;

    public LakeQualityReconciler(
            KubernetesClient kube,
            CatalogClient catalog,
            QualityStore store,
            Metrics metrics,
            Supplier<Instant> clock) {
        this.kube = kube;
        this.catalog = catalog;
        this.store = store;
        this.events = new Events(kube);
        this.metrics = metrics;
        this.clock = clock;
    }

    /** What one round knows. */
    private record Round(
            LakeQuality quality, LakeQualitySpec spec, LakeQualityStatus status, Instant now) {
        String namespace() {
            return quality.getMetadata().getNamespace();
        }

        String database() {
            return spec.table.database;
        }

        String tableName() {
            return spec.table.table;
        }

        String table() {
            return spec.table.qualifiedName();
        }

        String id() {
            return quality.getMetadata().getName() + "/" + quality.getMetadata().getUid();
        }

        boolean condition(String type, boolean ok, String reason, String message) {
            return StatusCondition.set(
                    status.conditions,
                    type,
                    ok,
                    reason,
                    message,
                    Objects.requireNonNullElse(quality.getMetadata().getGeneration(), 0L),
                    now);
        }
    }

    @Override
    public UpdateControl<LakeQuality> reconcile(LakeQuality quality, Context<LakeQuality> c) {
        LakeQualityStatus status =
                quality.getStatus() == null ? new LakeQualityStatus() : quality.getStatus();
        quality.setStatus(status);
        Round r = new Round(quality, quality.getSpec(), status, clock.get());
        status.observedGeneration = quality.getMetadata().getGeneration();
        status.table = r.table();
        String conflict = conflict(r);
        if (conflict != null) {
            fail(r, "Conflict", conflict);
            return UpdateControl.patchStatus(quality).rescheduleAfter(POLL);
        }
        String notReady;
        String phase = "Ready";
        List<QualityStore.Result> results = List.of();
        try {
            Optional<JsonNode> table = catalog.table(r.database(), r.tableName());
            if (table.isEmpty()) {
                r.condition("RulesValid", false, "TableMissing", r.table() + " does not exist");
                notReady = r.table() + " does not exist";
                phase = "Pending";
            } else if (!managed(r, table.get())) {
                fail(
                        r,
                        "NotManaged",
                        r.table()
                                + " is neither an ODS table of a LakeSource nor a LakeTable target"
                                + " in namespace "
                                + r.namespace());
                return UpdateControl.patchStatus(quality).rescheduleAfter(POLL);
            } else {
                notReady = rules(r);
                if (notReady != null) {
                    phase = "Degraded";
                }
            }
            String problem = store.ensure(r.namespace());
            r.condition(
                    "StoreReady",
                    problem == null,
                    problem == null ? "Ready" : "StoreConflict",
                    problem == null ? QualityStore.database(r.namespace()) : problem);
            if (problem != null) {
                notReady = Objects.requireNonNullElse(notReady, problem);
                phase = "Degraded";
            } else {
                status.store = QualityStore.database(r.namespace());
                results = store.results(r.namespace(), r.table());
            }
        } catch (Exception e) {
            String message =
                    "quality store: " + JobObservation.abbreviate(String.valueOf(e.getMessage()));
            r.condition("StoreReady", false, "StoreUnavailable", message);
            notReady = message;
            phase = "Degraded";
        }
        List<Gate.Check> all = checks(results, null);
        List<Gate.Check> current = checks(results, status.ruleVersion);
        List<Gate.Waiver> accepted = waivers(r, all, current);
        Gate.Verdict verdict =
                Gate.judge(
                        notReady,
                        current,
                        accepted,
                        r.spec().gate == null || r.spec().gate.maxAge == null
                                ? null
                                : Duration.ofMillis(Durations.millis(r.spec().gate.maxAge, null)),
                        r.now());
        Map<String, String> tags = notReady == null ? tags(r, verdict) : Map.of();
        gate(r, verdict);
        record(r);
        status.checks = describe(verdict, tags, status.checks);
        status.phase = phase;
        status.message =
                (verdict.open() ? "gate open: " : "gate closed (" + verdict.cause() + "): ")
                        + verdict.message();
        status.measuredAt = r.now().toString();
        publish(r, verdict, results);
        return UpdateControl.patchStatus(quality).rescheduleAfter(POLL);
    }

    /** Another LakeQuality of this namespace on the same table that came first, or null. */
    private String conflict(Round r) {
        for (LakeQuality other : QualityGates.of(kube, r.namespace())) {
            if (other.getMetadata().getUid().equals(r.quality().getMetadata().getUid())
                    || other.getSpec() == null
                    || other.getSpec().table == null
                    || !r.table().equals(other.getSpec().table.qualifiedName())) {
                continue;
            }
            if (QualityGates.FIRST.compare(other, r.quality()) < 0) {
                return "LakeQuality "
                        + other.getMetadata().getName()
                        + " came first for "
                        + r.table();
            }
        }
        return null;
    }

    /** Whether the table is this namespace's, as a LakeTask's must be. */
    private boolean managed(Round r, JsonNode table) {
        String tableOwner = table.path("schema").path("options").path(Owners.OPTION).asText(null);
        String databaseOwner =
                catalog.databaseOptions(r.database()).map(o -> o.get(Owners.OPTION)).orElse(null);
        return Owners.isSourceOf(databaseOwner, r.namespace())
                || Owners.isTableOf(tableOwner, r.namespace());
    }

    /** Checks the rules against the table's columns; sets the version. Why not valid, or null. */
    private String rules(Round r) throws Exception {
        r.status().ruleVersion = QualityRules.version(r.spec().rules);
        Optional<QualityStore.Columns> columns = store.columns(r.database(), r.tableName());
        String problem =
                columns.isEmpty()
                        ? r.table() + " does not exist"
                        : QualityRules.problem(r.spec().rules, columns.get().fields());
        if (r.condition(
                        "RulesValid",
                        problem == null,
                        problem == null ? "Valid" : "InvalidRules",
                        problem == null ? "rules version " + r.status().ruleVersion : problem)
                && problem != null) {
            events.warning(r.quality(), "InvalidRules", problem);
        }
        return problem;
    }

    /** The completed checks in the results, by task uid; only those of {@code version} if given. */
    static List<Gate.Check> checks(List<QualityStore.Result> results, String version) {
        Map<String, List<QualityStore.Result>> byCheck = new LinkedHashMap<>();
        for (QualityStore.Result res : results) {
            if (version == null || version.equals(res.ruleVersion())) {
                byCheck.computeIfAbsent(res.taskUid(), k -> new ArrayList<>()).add(res);
            }
        }
        List<Gate.Check> out = new ArrayList<>();
        for (List<QualityStore.Result> rows : byCheck.values()) {
            QualityStore.Result first = rows.get(0);
            Instant written =
                    rows.stream()
                            .map(QualityStore.Result::checkedAt)
                            .max(Comparator.naturalOrder())
                            .orElseThrow();
            List<String> failed =
                    rows.stream()
                            .filter(x -> !x.passed())
                            .map(QualityStore.Result::rule)
                            .sorted()
                            .toList();
            List<String> partitions =
                    first.partitions().isEmpty()
                            ? List.of()
                            : Arrays.stream(first.partitions().split(";")).sorted().toList();
            out.add(
                    new Gate.Check(
                            first.task(),
                            first.taskUid(),
                            first.snapshot(),
                            first.asOf(),
                            written,
                            partitions,
                            failed));
        }
        return out;
    }

    /**
     * Judges the waivers of this table's checks and returns the accepted ones. A waiver is for the
     * newest check of its name written before it was made; it accepts only rules that check failed,
     * and only while that check stands under the current rules.
     */
    private List<Gate.Waiver> waivers(Round r, List<Gate.Check> all, List<Gate.Check> current) {
        Set<String> live =
                Gate.live(current).stream().map(Gate.Check::uid).collect(Collectors.toSet());
        Set<String> currentUids = current.stream().map(Gate.Check::uid).collect(Collectors.toSet());
        List<Gate.Waiver> accepted = new ArrayList<>();
        for (LakeWaiver w :
                kube.resources(LakeWaiver.class).inNamespace(r.namespace()).list().getItems()) {
            if (w.getSpec() == null || w.getSpec().check == null) {
                continue;
            }
            LakeWaiverStatus before = w.getStatus();
            LakeWaiverStatus s = new LakeWaiverStatus();
            s.table = r.table();
            s.approvedBy =
                    w.getMetadata().getAnnotations() == null
                            ? null
                            : w.getMetadata().getAnnotations().get(LakeWaiver.APPROVED_BY);
            List<Gate.Check> named =
                    all.stream().filter(x -> w.getSpec().check.equals(x.task())).toList();
            if (named.isEmpty()) {
                LakeTask task =
                        kube.resources(LakeTask.class)
                                .inNamespace(r.namespace())
                                .withName(w.getSpec().check)
                                .get();
                if (task == null
                        || task.getSpec() == null
                        || !"check".equals(task.getSpec().type)
                        || !r.table().equals(task.getSpec().table.qualifiedName())) {
                    continue;
                }
                s.phase = "Pending";
                s.message = "check " + w.getSpec().check + " has no results yet";
            } else {
                Instant made = Instant.parse(w.getMetadata().getCreationTimestamp());
                Gate.Check check =
                        before != null && before.checkUid != null
                                ? named.stream()
                                        .filter(x -> x.uid().equals(before.checkUid))
                                        .findFirst()
                                        .orElse(null)
                                : named.stream()
                                        .filter(x -> x.checkedAt().isBefore(made.plusSeconds(1)))
                                        .max(Comparator.comparing(Gate.Check::checkedAt))
                                        .orElse(null);
                if (check == null) {
                    s.phase = "Rejected";
                    s.message =
                            "made before check "
                                    + w.getSpec().check
                                    + " had results: a waiver accepts failures already known";
                } else {
                    s.checkUid = check.uid();
                    List<String> notFailed =
                            w.getSpec().rules.stream()
                                    .filter(rule -> !check.failed().contains(rule))
                                    .toList();
                    if (!currentUids.contains(check.uid())) {
                        s.phase = "Superseded";
                        s.message = "the rules changed since its check";
                    } else if (!live.contains(check.uid())) {
                        s.phase = "Superseded";
                        s.message = "a newer check replaced its check";
                    } else if (s.approvedBy == null || s.approvedBy.isBlank()) {
                        s.phase = "Rejected";
                        s.message =
                                "no "
                                        + LakeWaiver.APPROVED_BY
                                        + " annotation: is the admission policy installed?";
                    } else if (!notFailed.isEmpty()) {
                        s.phase = "Rejected";
                        s.message =
                                "names rules check "
                                        + w.getSpec().check
                                        + " did not fail: "
                                        + notFailed;
                    } else {
                        s.phase = "Accepted";
                        s.message =
                                "accepts "
                                        + w.getSpec().rules
                                        + " of "
                                        + check.describe()
                                        + ", approved by "
                                        + s.approvedBy;
                        s.acceptedAt =
                                before != null && before.acceptedAt != null
                                        ? before.acceptedAt
                                        : r.now().toString();
                        accepted.add(
                                new Gate.Waiver(
                                        w.getMetadata().getName(),
                                        w.getMetadata().getUid(),
                                        check.uid(),
                                        List.copyOf(w.getSpec().rules),
                                        s.approvedBy,
                                        w.getSpec().reason));
                    }
                }
            }
            if (before == null
                    || !Objects.equals(before.phase, s.phase)
                    || !Objects.equals(before.message, s.message)
                    || !Objects.equals(before.checkUid, s.checkUid)) {
                w.setStatus(s);
                kube.resources(LakeWaiver.class)
                        .inNamespace(r.namespace())
                        .resource(w)
                        .patchStatus();
                if (before == null || !Objects.equals(before.phase, s.phase)) {
                    if ("Accepted".equals(s.phase)) {
                        events.normal(w, "WaiverAccepted", s.message);
                    } else if ("Rejected".equals(s.phase)) {
                        events.warning(w, "WaiverRejected", s.message);
                    }
                }
            }
        }
        return accepted;
    }

    /**
     * Tags the snapshot of each standing failed check, so its rows can be listed after the snapshot
     * expires; removes the operator's tags past their time. Returns the tag of each check by uid,
     * or why it has none.
     */
    private Map<String, String> tags(Round r, Gate.Verdict verdict) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            Map<String, QualityStore.Tag> existing = new LinkedHashMap<>();
            for (QualityStore.Tag t : store.tags(r.database(), r.tableName())) {
                if (t.expires().isBefore(r.now())) {
                    store.untag(r.database(), r.tableName(), t.name());
                } else {
                    existing.put(t.name(), t);
                }
            }
            Duration kept = kept(r);
            for (Gate.Check c : verdict.live()) {
                if (c.failed().isEmpty()) {
                    continue;
                }
                String name = QualityStore.TAG_PREFIX + c.uid().substring(0, 8);
                if (existing.containsKey(name)) {
                    out.put(c.uid(), name);
                } else if (store.snapshotExists(r.database(), r.tableName(), c.snapshot())) {
                    store.tag(r.database(), r.tableName(), name, c.snapshot(), kept);
                    out.put(c.uid(), name);
                    events.normal(
                            r.quality(),
                            "CheckTagged",
                            "tagged snapshot "
                                    + c.snapshot()
                                    + " of "
                                    + r.table()
                                    + " as "
                                    + name
                                    + " for "
                                    + kept.toHours()
                                    + " h, to list the rows "
                                    + c.task()
                                    + " failed");
                } else {
                    out.put(c.uid(), null);
                }
            }
        } catch (Exception e) {
            LOG.warn("Tagging failed checks of {} failed; retrying", r.table(), e);
        }
        return out;
    }

    /** How long a tag keeps its snapshot: a week, or the table's retention if shorter. */
    private Duration kept(Round r) {
        Duration retention =
                GovernancePolicy.retention(
                        GovernancePolicy.Declared.of(
                                        kube.resources(LakeSource.class)
                                                .inNamespace(r.namespace())
                                                .list()
                                                .getItems(),
                                        kube.resources(LakeTable.class)
                                                .inNamespace(r.namespace())
                                                .list()
                                                .getItems())
                                .governanceOf(r.table()));
        return retention == null || retention.compareTo(TAG_KEPT) > 0 ? TAG_KEPT : retention;
    }

    /** Sets the gate; a change is a decision, recorded in the status and, next, in the store. */
    private void gate(Round r, Gate.Verdict v) {
        LakeQualityStatus status = r.status();
        LakeQualityStatus.GateState before = status.gate;
        String checkUid = v.basis() == null ? null : v.basis().uid();
        List<String> waivers = v.used().stream().map(Gate.Waiver::name).sorted().toList();
        boolean changed =
                before == null
                        || !Objects.equals(before.open, v.open())
                        || !Objects.equals(before.cause, v.cause())
                        || !Objects.equals(before.checkUid, checkUid)
                        || !Objects.equals(before.waivers, waivers);
        LakeQualityStatus.GateState g = changed ? new LakeQualityStatus.GateState() : before;
        g.open = v.open();
        g.cause = v.cause();
        g.message = v.message();
        g.check = v.basis() == null ? null : v.basis().describe();
        g.checkUid = checkUid;
        g.waivers = waivers;
        if (changed) {
            g.since = r.now().toString();
        }
        status.gate = g;
        r.condition("GateOpen", v.open(), v.cause(), v.message());
        if (!changed) {
            return;
        }
        LakeQualityStatus.Decision d = new LakeQualityStatus.Decision();
        d.at = r.now().toString();
        d.open = v.open();
        d.cause = v.cause();
        d.message = v.message();
        d.ruleVersion = status.ruleVersion;
        if (v.basis() != null) {
            d.check = v.basis().task();
            d.checkUid = v.basis().uid();
            d.snapshot = v.basis().snapshot();
            d.failed = v.basis().failed();
        }
        d.waivers =
                v.used().stream()
                        .map(
                                w ->
                                        w.name()
                                                + " ("
                                                + w.uid()
                                                + ", approved by "
                                                + w.approvedBy()
                                                + "): "
                                                + w.reason())
                        .toList();
        d.recorded = false;
        status.decisions.add(d);
        // The newest are kept, and those not yet in the store, up to a bound.
        while (status.decisions.size() > KEPT
                && (Boolean.TRUE.equals(status.decisions.get(0).recorded)
                        || status.decisions.size() > KEPT_UNRECORDED)) {
            status.decisions.remove(0);
        }
        String message =
                "gate " + (v.open() ? "open" : "closed") + " (" + v.cause() + "): " + v.message();
        if (v.open() || "NoCheck".equals(v.cause())) {
            events.normal(r.quality(), v.open() ? "GateOpened" : "GateClosed", message);
        } else {
            events.warning(r.quality(), "GateClosed", message);
        }
    }

    /** Writes the decisions not yet in the store, in order, in one commit. */
    private void record(Round r) {
        List<LakeQualityStatus.Decision> pending =
                r.status().decisions.stream()
                        .filter(d -> !Boolean.TRUE.equals(d.recorded))
                        .toList();
        if (pending.isEmpty() || r.status().store == null) {
            return;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LakeQualityStatus.Decision d : pending) {
            rows.add(row(r, d));
        }
        try {
            store.decide(r.namespace(), rows);
            pending.forEach(d -> d.recorded = true);
        } catch (Exception e) {
            String message =
                    "writing the gate's decisions: "
                            + JobObservation.abbreviate(String.valueOf(e.getMessage()));
            r.condition("StoreReady", false, "StoreUnavailable", message);
            LOG.warn("Recording decisions of {} failed; retrying", r.table(), e);
        }
    }

    private static Map<String, Object> row(Round r, LakeQualityStatus.Decision d) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("checked_table", r.table());
        row.put("lake_quality", r.id());
        row.put("decided_at", Instant.parse(d.at));
        row.put("open", d.open);
        row.put("cause", d.cause);
        row.put("message", d.message);
        row.put("rule_version", d.ruleVersion);
        row.put("check_task", d.check);
        row.put("check_uid", d.checkUid);
        row.put("snapshot_id", d.snapshot);
        row.put("failed_rules", d.failed == null ? null : String.join(",", d.failed));
        row.put("waivers", json(d.waivers));
        return row;
    }

    /** The standing checks for the status, newest first, with their tags. */
    private static List<LakeQualityStatus.Check> describe(
            Gate.Verdict verdict, Map<String, String> tags, List<LakeQualityStatus.Check> before) {
        Map<String, LakeQualityStatus.Check> previous = new LinkedHashMap<>();
        for (LakeQualityStatus.Check c : before) {
            previous.put(c.uid, c);
        }
        List<LakeQualityStatus.Check> out = new ArrayList<>();
        for (Gate.Check c :
                verdict.live().stream()
                        .sorted(
                                Comparator.comparing(Gate.Check::snapshot)
                                        .thenComparing(Gate.Check::checkedAt)
                                        .reversed())
                        .limit(KEPT)
                        .toList()) {
            LakeQualityStatus.Check s = new LakeQualityStatus.Check();
            s.task = c.task();
            s.uid = c.uid();
            s.snapshot = c.snapshot();
            s.asOf = c.asOf().toString();
            s.partitions = c.partitions();
            s.checkedAt = c.checkedAt().toString();
            s.passed = c.failed().isEmpty();
            s.failed = c.failed();
            s.waived =
                    verdict.used().stream()
                            .filter(w -> w.checkUid().equals(c.uid()))
                            .flatMap(w -> w.rules().stream())
                            .filter(c.failed()::contains)
                            .distinct()
                            .sorted()
                            .toList();
            if (tags.containsKey(c.uid())) {
                s.tag = tags.get(c.uid());
                s.note =
                        s.tag == null
                                ? "snapshot "
                                        + c.snapshot()
                                        + " expired before it could be tagged: only the counts"
                                        + " remain"
                                : null;
            } else if (previous.containsKey(c.uid())) {
                s.tag = previous.get(c.uid()).tag;
                s.note = previous.get(c.uid()).note;
            }
            out.add(s);
        }
        return out;
    }

    private void fail(Round r, String reason, String message) {
        r.status().phase = FAILED;
        r.status().message = message;
        r.status().gate = null;
        r.status().checks = new ArrayList<>();
        r.condition("RulesValid", false, reason, message);
        metrics.forget(KIND + ":" + r.namespace() + "/" + r.quality().getMetadata().getName());
    }

    private void publish(Round r, Gate.Verdict v, List<QualityStore.Result> results) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("namespace", r.namespace());
        labels.put("lakequality", r.quality().getMetadata().getName());
        labels.put("table", r.table());
        List<Metrics.Sample> samples = new ArrayList<>();
        Map<String, String> gate = new LinkedHashMap<>(labels);
        gate.put("cause", v.cause());
        gate.put("publish", Boolean.toString(r.spec().publishes()));
        samples.add(
                new Metrics.Sample(
                        "colvenna_quality_gate_open",
                        "1 while the table's quality gate is open",
                        gate,
                        v.open() ? 1 : 0));
        if (v.newest() != null) {
            samples.add(
                    new Metrics.Sample(
                            "colvenna_quality_last_check_timestamp_seconds",
                            "When the newest check under the current rules was written",
                            labels,
                            v.newest().checkedAt().getEpochSecond()));
            for (QualityStore.Result res : results) {
                if (res.taskUid().equals(v.newest().uid())) {
                    Map<String, String> rule = new LinkedHashMap<>(labels);
                    rule.put("rule", res.rule());
                    samples.add(
                            new Metrics.Sample(
                                    "colvenna_quality_rule_passed",
                                    "1 if the rule passed in the newest check",
                                    rule,
                                    res.passed() ? 1 : 0));
                }
            }
        }
        samples.add(
                new Metrics.Sample(
                        "colvenna_quality_measured_timestamp_seconds",
                        "When the operator last judged the gate",
                        labels,
                        r.now().getEpochSecond()));
        metrics.publish(
                KIND + ":" + r.namespace() + "/" + r.quality().getMetadata().getName(), samples);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public DeleteControl cleanup(LakeQuality quality, Context<LakeQuality> context) {
        LakeQualityStatus status = quality.getStatus();
        if (status != null && status.store != null && !FAILED.equals(status.phase)) {
            Round r = new Round(quality, quality.getSpec(), status, clock.get());
            try {
                LakeQualityStatus.Decision d = new LakeQualityStatus.Decision();
                d.at = r.now().toString();
                d.cause = "Removed";
                d.message = "the LakeQuality was deleted: the table is no longer gated";
                d.ruleVersion = status.ruleVersion;
                List<Map<String, Object>> rows = new ArrayList<>();
                for (LakeQualityStatus.Decision p : status.decisions) {
                    if (!Boolean.TRUE.equals(p.recorded)) {
                        rows.add(row(r, p));
                    }
                }
                rows.add(row(r, d));
                store.decide(r.namespace(), rows);
                if (store.columns(r.database(), r.tableName()).isPresent()) {
                    for (QualityStore.Tag t : store.tags(r.database(), r.tableName())) {
                        store.untag(r.database(), r.tableName(), t.name());
                    }
                }
            } catch (Exception e) {
                LOG.warn("Cleaning up {} failed; retrying", r.id(), e);
                return DeleteControl.noFinalizerRemoval().rescheduleAfter(POLL);
            }
        }
        metrics.forget(
                KIND
                        + ":"
                        + quality.getMetadata().getNamespace()
                        + "/"
                        + quality.getMetadata().getName());
        return DeleteControl.defaultDelete();
    }

    @Override
    public ErrorStatusUpdateControl<LakeQuality> updateErrorStatus(
            LakeQuality quality, Context<LakeQuality> context, Exception e) {
        LakeQualityStatus status =
                quality.getStatus() == null ? new LakeQualityStatus() : quality.getStatus();
        status.message =
                "reconcile failed: " + JobObservation.abbreviate(String.valueOf(e.getMessage()));
        quality.setStatus(status);
        return ErrorStatusUpdateControl.patchStatus(quality);
    }
}
