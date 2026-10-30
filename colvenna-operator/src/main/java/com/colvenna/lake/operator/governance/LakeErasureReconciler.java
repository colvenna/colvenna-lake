package com.colvenna.lake.operator.governance;

import com.colvenna.lake.operator.Durations;
import com.colvenna.lake.operator.OperatorSettings;
import com.colvenna.lake.operator.catalog.CatalogClient;
import com.colvenna.lake.operator.catalog.CatalogException;
import com.colvenna.lake.operator.catalog.CatalogIdentities;
import com.colvenna.lake.operator.catalog.Grant;
import com.colvenna.lake.operator.crd.Governance;
import com.colvenna.lake.operator.crd.LakeDashboard;
import com.colvenna.lake.operator.crd.LakeDashboardSpec;
import com.colvenna.lake.operator.crd.LakeDashboardStatus;
import com.colvenna.lake.operator.crd.LakeErasure;
import com.colvenna.lake.operator.crd.LakeErasureSpec;
import com.colvenna.lake.operator.crd.LakeErasureStatus;
import com.colvenna.lake.operator.crd.LakeIcebergPublication;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceStatus;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.crd.LakeTableStatus;
import com.colvenna.lake.operator.crd.LakeTask;
import com.colvenna.lake.operator.crd.LakeTaskSpec;
import com.colvenna.lake.operator.crd.StatusCondition;
import com.colvenna.lake.operator.flink.FlinkJobs;
import com.colvenna.lake.operator.flink.JobObservation;
import com.colvenna.lake.operator.kube.Events;
import com.colvenna.lake.operator.metrics.Metrics;
import com.colvenna.lake.operator.source.SourceJobs;
import com.colvenna.lake.operator.source.SourceProbe;
import com.colvenna.lake.operator.source.SourceTargets;
import com.colvenna.lake.operator.table.TableJobs;
import com.colvenna.lake.operator.task.LakeTaskReconciler;
import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Erases one data subject across the namespace's managed copies of personal data, and reports each
 * copy's state with its evidence (HLD 12.4; LLD "治理与个人数据删除"). Nothing is reported done without
 * evidence; copies outside the platform are listed apart.
 */
@ControllerConfiguration(name = "lakeerasure")
public final class LakeErasureReconciler implements Reconciler<LakeErasure>, Cleaner<LakeErasure> {
    private static final Logger LOG = LoggerFactory.getLogger(LakeErasureReconciler.class);
    public static final String KIND = "lakeerasure";
    static final Duration POLL = Duration.ofSeconds(30);

    /** Orphan-file cleaning removes files older than a day: it covers what precedes it by that. */
    static final Duration ORPHAN_AGE = Duration.ofDays(1);

    /** The longest deadlines the acceptance contract sets (section 4), its defaults. */
    static final Duration MAX_LOGICAL =
            Duration.ofMillis(Durations.millis(LakeErasureSpec.DEFAULT_LOGICAL, null));

    /**
     * How long a request may wait to be accepted (its Secret, its deadlines) before it breaches.
     */
    static final Duration MAX_INTAKE = Duration.ofHours(24);

    static final Duration MAX_PHYSICAL =
            Duration.ofMillis(Durations.millis(LakeErasureSpec.DEFAULT_PHYSICAL, null));

    // Copy kinds, layers and states.
    static final String SOURCE_DATABASE = "SourceDatabase";
    static final String TOPIC = "Topic";
    static final String READER = "Reader";
    static final String ROWS = "Rows";
    static final String HISTORY = "History";
    static final String FILES = "Files";
    static final String FLINK_STATE = "FlinkState";
    static final String STORAGE = "Storage";
    static final String DASHBOARD = "Dashboard";
    static final String DORIS_AUDIT = "DorisAuditLog";

    /**
     * How long the platform's Doris keeps a query in its log
     * (deploy/scripts/doris/install-doris.sh): 7 daily partitions, today's included
     * (dynamic_partition.start = -6), and audit files deleted 5 days after their day's roll, so a
     * query is gone 7 days after it; an hour more for Doris's partition scheduler.
     */
    static final Duration DORIS_AUDIT_KEPT = Duration.ofDays(7).plusHours(1);

    static final String DORIS_STORAGE = "DorisStorage";

    /**
     * How long the platform's Doris keeps what a refresh replaced: dropped partitions and tables a
     * day in its recycle bin (catalog_trash_expire_second), none in its BEs' trash
     * (trash_file_expire_time_sec 0), whose garbage sweep runs within the hour.
     */
    static final Duration DORIS_STORAGE_KEPT = Duration.ofDays(1).plusHours(1);

    static final String LOGICAL = "Logical";
    static final String PHYSICAL = "Physical";
    static final String DONE = "Done";
    static final String PENDING = "Pending";
    static final String BLOCKED = "Blocked";
    static final String EXTERNAL = "External";
    static final String PENDING_EVIDENCE = "PendingEvidence";
    static final String PLATFORM = "platform";

    /** The FlinkState copy of state left by jobs no declaration owns any more. */
    static final String ORPHANED_STATE = "jobs no longer declared";

    // Phases; a copy held by policy is Blocked as the erasure is.
    static final String ACCEPTED = "Accepted";
    static final String LOGICALLY_ERASED = "LogicallyErased";
    static final String PHYSICALLY_ERASED = "PhysicallyErased";
    static final String SLA_BREACH = "SlaBreach";
    static final String FAILED = "Failed";

    private static final ResourceDefinitionContext SNAPSHOTS =
            new ResourceDefinitionContext.Builder()
                    .withGroup("flink.apache.org")
                    .withVersion("v1beta1")
                    .withPlural("flinkstatesnapshots")
                    .withKind("FlinkStateSnapshot")
                    .withNamespaced(true)
                    .build();

    private final OperatorSettings settings;
    private final KubernetesClient kube;
    private final CatalogClient catalog;
    private final CatalogIdentities identities;
    private final ErasureProbes probeRuns;
    private final Map<String, SourceProbe> probes;
    private final Events events;
    private final String storageEvidence;

    /** Whether the platform's Doris is installed: its query log is then a copy. */
    private final boolean doris;

    private final Metrics metrics;
    private final Supplier<Instant> clock;

    /**
     * @param storageEvidence what the installer verified of the warehouse bucket (no versions, no
     *     replication), or null when it did not
     */
    public LakeErasureReconciler(
            OperatorSettings settings,
            KubernetesClient kube,
            CatalogClient catalog,
            Map<String, SourceProbe> probes,
            String storageEvidence,
            boolean doris,
            Metrics metrics,
            Supplier<Instant> clock) {
        this.settings = settings;
        this.doris = doris;
        this.kube = kube;
        this.catalog = catalog;
        this.identities = new CatalogIdentities(catalog, kube);
        this.probeRuns = new ErasureProbes(settings, kube);
        this.probes = probes;
        this.events = new Events(kube);
        this.storageEvidence = storageEvidence;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** One reconciliation of an erasure: it, its status, and the round's time. */
    private record Round(LakeErasure erasure, LakeErasureStatus status, Instant now) {}

    @Override
    public UpdateControl<LakeErasure> reconcile(LakeErasure erasure, Context<LakeErasure> context) {
        LakeErasureStatus status =
                erasure.getStatus() == null ? new LakeErasureStatus() : erasure.getStatus();
        erasure.setStatus(status);
        Instant now = clock.get();
        if (FAILED.equals(status.phase) || PHYSICALLY_ERASED.equals(status.phase)) {
            return UpdateControl.noUpdate();
        }
        Round r = new Round(erasure, status, now);
        status.observedGeneration = erasure.getMetadata().getGeneration();
        if (status.requestedAt == null) {
            String created = erasure.getMetadata().getCreationTimestamp();
            status.requestedAt = created == null ? now.toString() : created;
        }
        String problem = null;
        try {
            if (status.acceptedAt == null && !accept(r)) {
                if (FAILED.equals(status.phase)) {
                    return UpdateControl.patchStatus(erasure);
                }
                intake(r);
                return UpdateControl.patchStatus(erasure).rescheduleAfter(POLL);
            }
            ensureIdentity(r);
            problem = advance(r);
        } catch (CatalogException e) {
            problem = "colvenna-catalog: " + e.getMessage();
        } catch (KubernetesClientException e) {
            problem = "Kubernetes API: " + JobObservation.abbreviate(e.getMessage());
        }
        // Whatever stalled, the deadlines are judged and what is left reported; acceptance too.
        if (status.acceptedAt != null) {
            summarize(r, problem);
        } else if (problem != null) {
            status.message = problem;
            intake(r);
        }
        publish(erasure, status);
        return PHYSICALLY_ERASED.equals(status.phase)
                ? UpdateControl.patchStatus(erasure)
                : UpdateControl.patchStatus(erasure).rescheduleAfter(POLL);
    }

    /** The erasure's phase and its breaches, as gauges (LLD "监控与数据质量"). */
    private void publish(LakeErasure erasure, LakeErasureStatus status) {
        Map<String, String> base =
                Map.of(
                        "namespace", erasure.getMetadata().getNamespace(),
                        "lakeerasure", erasure.getMetadata().getName());
        List<Metrics.Sample> samples = new ArrayList<>();
        for (String phase :
                List.of(
                        ACCEPTED,
                        LOGICALLY_ERASED,
                        PHYSICALLY_ERASED,
                        BLOCKED,
                        SLA_BREACH,
                        FAILED)) {
            Map<String, String> labels = new LinkedHashMap<>(base);
            labels.put("phase", phase);
            samples.add(
                    new Metrics.Sample(
                            "colvenna_erasure_phase",
                            "1 for the erasure's current phase",
                            labels,
                            phase.equals(status.phase) ? 1 : 0));
        }
        samples.add(
                new Metrics.Sample(
                        "colvenna_erasure_breaches",
                        "deadlines the erasure passed before reaching their level",
                        base,
                        status.breaches.size()));
        metrics.publish(
                KIND + ":" + base.get("namespace") + "/" + base.get("lakeerasure"), samples);
    }

    // ------------------------------------------------------------------- acceptance

    /**
     * Validates the subject and the deadlines, lists the copies, and fixes t0. An erasure waits for
     * its Secret; one whose Secret holds no values, or with deadlines past the acceptance
     * contract's, fails.
     */
    private boolean accept(Round r) {
        LakeErasure erasure = r.erasure();
        LakeErasureStatus status = r.status();
        Instant now = r.now();
        List<List<String>> subject = subject(erasure);
        if (subject == null) {
            status.message =
                    "waiting for Secret " + erasure.getSpec().subjectSecret.name + " to exist";
            return false;
        }
        if (subject.isEmpty()) {
            return refuse(
                    r, "Secret " + erasure.getSpec().subjectSecret.name + " has no subject values");
        }
        var deadlines = erasure.getSpec().deadlines;
        Duration logical =
                Duration.ofMillis(
                        Durations.millis(
                                deadlines == null ? null : deadlines.logical,
                                LakeErasureSpec.DEFAULT_LOGICAL));
        Duration physical =
                Duration.ofMillis(
                        Durations.millis(
                                deadlines == null ? null : deadlines.physical,
                                LakeErasureSpec.DEFAULT_PHYSICAL));
        if (logical.compareTo(MAX_LOGICAL) > 0
                || physical.compareTo(MAX_PHYSICAL) > 0
                || logical.compareTo(physical) > 0) {
            return refuse(
                    r,
                    "deadlines are at most "
                            + LakeErasureSpec.DEFAULT_LOGICAL
                            + " logical and "
                            + LakeErasureSpec.DEFAULT_PHYSICAL
                            + " physical, the logical first");
        }
        String ns = erasure.getMetadata().getNamespace();
        List<LakeErasureStatus.Copy> copies = copies(ns, now);
        status.acceptedAt = now.toString();
        status.logicalDeadline = now.plus(logical).toString();
        status.physicalDeadline = now.plus(physical).toString();
        int columns = subject.get(0).size();
        for (LakeErasureStatus.Copy c : copies) {
            if (ROWS.equals(c.kind)
                    && !BLOCKED.equals(c.state)
                    && (c.subjectKey.size() != columns
                            || subject.stream().anyMatch(v -> v.size() != columns))) {
                block(
                        c,
                        "the subject's values have "
                                + columns
                                + " columns, its subject key "
                                + c.subjectKey
                                + " "
                                + c.subjectKey.size());
            }
            if (BLOCKED.equals(c.state)) {
                c.reviewBy = status.logicalDeadline;
            }
        }
        status.copies = copies;
        status.phase = ACCEPTED;
        events.normal(
                erasure,
                "ErasureAccepted",
                "after "
                        + Duration.between(Instant.parse(status.requestedAt), now).toSeconds()
                        + " s of intake: "
                        + status.copies.size()
                        + " copies; logical by "
                        + status.logicalDeadline
                        + ", physical by "
                        + status.physicalDeadline);
        return true;
    }

    /** A request not accepted within {@link #MAX_INTAKE} breaches, as a deadline does. */
    private void intake(Round r) {
        LakeErasure erasure = r.erasure();
        LakeErasureStatus status = r.status();
        Instant now = r.now();
        String prefix = "intake: not accepted within " + MAX_INTAKE.toHours() + " h";
        if (now.isAfter(Instant.parse(status.requestedAt).plus(MAX_INTAKE))
                && status.breaches.stream().noneMatch(b -> b.startsWith(prefix))) {
            String breach = prefix + " of the request (seen " + now + "): " + status.message;
            status.breaches.add(breach);
            status.phase = SLA_BREACH;
            events.warning(erasure, "ErasureOverdue", breach);
        }
    }

    private boolean refuse(Round r, String why) {
        LakeErasure erasure = r.erasure();
        LakeErasureStatus status = r.status();
        status.phase = FAILED;
        status.message = why;
        events.warning(erasure, "ErasureRefused", why);
        return false;
    }

    /**
     * Every copy of the namespace's personal data, from the declarations as they are at t0, and the
     * tables in their databases no declaration covers.
     */
    List<LakeErasureStatus.Copy> copies(String ns, Instant now) {
        List<LakeErasureStatus.Copy> out = new ArrayList<>();
        List<LakeSource> sources =
                kube.resources(LakeSource.class).inNamespace(ns).list().getItems();
        List<LakeTable> layered = kube.resources(LakeTable.class).inNamespace(ns).list().getItems();
        Set<String> declared = new TreeSet<>();
        Set<String> databases = new TreeSet<>();
        Map<String, Set<String>> readers = new TreeMap<>();
        for (LakeSource s : sources) {
            Governance g = s.getSpec().governance;
            String db = s.getSpec().target.database;
            if (s.getStatus() == null) {
                continue;
            }
            for (LakeSourceStatus.SourceTable t : s.getStatus().tables) {
                if (t.target != null) {
                    declared.add(db + "." + t.target);
                }
            }
            if (!Governance.treatedAsPersonal(g)) {
                continue;
            }
            databases.add(db);
            for (LakeSourceStatus.SourceTable t : s.getStatus().tables) {
                if (t.target == null) {
                    continue;
                }
                String table = db + "." + t.target;
                List<String> key =
                        g == null ? List.of() : g.subjectKeys.getOrDefault(t.name, List.of());
                if (key.isEmpty()
                        || SourceJobs.isMongo(s.getSpec())
                        || "kafka".equals(s.getSpec().type)) {
                    LakeErasureStatus.Copy c = copy(ROWS, table, owner(g), LOGICAL, PENDING, null);
                    block(
                            c,
                            key.isEmpty()
                                    ? "no subject key declared: the subject's rows cannot be found"
                                    : "erasure from "
                                            + s.getSpec().type
                                            + " sources is not supported");
                    out.add(c);
                    continue;
                }
                // The user deletes at the source; the platform checks it did, before the lake's
                // rows can stay erased.
                LakeErasureStatus.Copy source =
                        copy(
                                SOURCE_DATABASE,
                                s.getMetadata().getName() + "/" + t.name,
                                owner(g),
                                LOGICAL,
                                PENDING,
                                "waiting for the source to delete the subject's rows");
                source.subjectKey = key;
                source.table = table;
                out.add(source);
                tableCopies(out, table, owner(g), key);
                readers.computeIfAbsent(table, k -> new TreeSet<>()).addAll(s.getSpec().readers);
            }
            if (SourceJobs.viaKafka(s.getSpec())) {
                out.add(
                        copy(
                                TOPIC,
                                s.getSpec().transport.kafka.topicPrefix + ".*",
                                owner(g),
                                EXTERNAL,
                                EXTERNAL,
                                "the user's Kafka keeps the subject's changes until its"
                                        + " retention; the platform cannot delete them"));
            }
            for (LakeSourceStatus.JobState j : s.getStatus().jobs) {
                out.add(flinkStateCopy(ns, j.deployment));
            }
        }
        GovernancePolicy.Declared governance = GovernancePolicy.Declared.of(sources, layered);
        for (LakeTable lt : layered) {
            declared.add(TableJobs.target(lt.getSpec()));
            if (TableJobs.quarantineQualified(lt.getSpec()) != null) {
                declared.add(TableJobs.quarantineQualified(lt.getSpec()));
            }
        }
        for (LakeTable lt : layered) {
            Governance g = lt.getSpec().governance;
            String table = TableJobs.target(lt.getSpec());
            if (!Governance.treatedAsPersonal(g)) {
                // Declared without personal data, yet reading some: its rows of the subject
                // cannot be told apart.
                if (governance.upstream(lt.getSpec().upstream).personal()) {
                    LakeErasureStatus.Copy c = copy(ROWS, table, owner(g), LOGICAL, PENDING, null);
                    block(
                            c,
                            "reads personal data but is not declared to hold any: the subject's"
                                    + " rows cannot be found");
                    out.add(c);
                }
                continue;
            }
            databases.add(lt.getSpec().target.database);
            if (g == null || g.subjectKey.isEmpty()) {
                LakeErasureStatus.Copy c = copy(ROWS, table, owner(g), LOGICAL, PENDING, null);
                block(c, "no subject key declared: the subject's rows cannot be found");
                out.add(c);
                continue;
            }
            tableCopies(out, table, owner(g), g.subjectKey);
            readers.computeIfAbsent(table, k -> new TreeSet<>()).addAll(lt.getSpec().readers);
            // The rows that failed a check are the table's too.
            String quarantine = TableJobs.quarantineQualified(lt.getSpec());
            if (quarantine != null) {
                tableCopies(out, quarantine, owner(g), g.subjectKey);
                readers.computeIfAbsent(quarantine, k -> new TreeSet<>())
                        .addAll(lt.getSpec().readers);
            }
            out.add(flinkStateCopy(ns, TableJobs.deploymentName(lt)));
        }
        // An Iceberg publication's readers too: published before the table was classified personal
        // (personal data is not published, LLD "开放层"), they may hold copies.
        for (LakeIcebergPublication p :
                kube.resources(LakeIcebergPublication.class).inNamespace(ns).list().getItems()) {
            if (p.getSpec() == null || p.getSpec().table == null) {
                continue;
            }
            Set<String> tableReaders = readers.get(p.getSpec().table.qualifiedName());
            if (tableReaders != null) {
                p.getSpec().readers.forEach(role -> tableReaders.add("iceberg:" + role));
            }
        }
        out.addAll(dashboardCopies(ns, governance));
        // The platform's Doris logs every query, literals included, a deleted dashboard's too.
        if (doris && out.stream().anyMatch(c -> ROWS.equals(c.kind))) {
            out.add(
                    copy(
                            DORIS_AUDIT,
                            "audit_log",
                            PLATFORM,
                            PHYSICAL,
                            PENDING,
                            "the platform's Doris keeps its query log "
                                    + DORIS_AUDIT_KEPT.toHours()
                                    + " h; a query's literals may hold the subject's values"));
        }
        // A table its declaration left behind, or one made beside them: whether it holds the
        // subject is unknown.
        for (String db : databases) {
            for (String t : catalog.tables(db)) {
                if (!declared.contains(db + "." + t)) {
                    LakeErasureStatus.Copy c =
                            copy(ROWS, db + "." + t, "unknown", LOGICAL, PENDING, null);
                    block(
                            c,
                            "no declaration covers this table beside the personal data: whether it"
                                    + " holds the subject is unknown");
                    out.add(c);
                }
            }
        }
        // State its jobs left that no declaration owns any more (one deleted, or made again).
        LakeErasureStatus.Copy orphans =
                copy(
                        FLINK_STATE,
                        ORPHANED_STATE,
                        PLATFORM,
                        PHYSICAL,
                        PENDING,
                        "waiting for the rows");
        orphans.location = settings.checkpointRoot() + "/" + ns;
        out.add(orphans);
        // What readers took out is theirs to erase.
        Map<String, Set<String>> byReader = new TreeMap<>();
        readers.forEach(
                (table, rs) ->
                        rs.forEach(
                                r -> byReader.computeIfAbsent(r, k -> new TreeSet<>()).add(table)));
        byReader.forEach(
                (reader, tables) ->
                        out.add(
                                copy(
                                        READER,
                                        reader,
                                        reader,
                                        EXTERNAL,
                                        EXTERNAL,
                                        "published READ on "
                                                + tables
                                                + ": what it copied out (a Doris, an export) is its"
                                                + " owner's to erase")));
        if (storageEvidence == null) {
            out.add(
                    copy(
                            STORAGE,
                            "warehouse",
                            PLATFORM,
                            PHYSICAL,
                            PENDING_EVIDENCE,
                            "no record that the warehouse bucket keeps no object versions or"
                                    + " replicas"));
        } else {
            // Done on what the installer verified of the bucket: that is its evidence.
            LakeErasureStatus.Copy storage =
                    copy(STORAGE, "warehouse", PLATFORM, PHYSICAL, PENDING, null);
            done(storage, now, storageEvidence);
            out.add(storage);
        }
        return out;
    }

    private static String owner(Governance g) {
        return g == null || g.owner == null ? "unclassified: no owner declared" : g.owner;
    }

    private static void block(LakeErasureStatus.Copy c, String why) {
        c.state = BLOCKED;
        c.message = why;
    }

    private static void tableCopies(
            List<LakeErasureStatus.Copy> out, String table, String owner, List<String> key) {
        LakeErasureStatus.Copy rows = copy(ROWS, table, owner, LOGICAL, PENDING, "waiting");
        rows.subjectKey = key;
        out.add(rows);
        out.add(copy(HISTORY, table, owner, LOGICAL, PENDING, "waiting for the rows"));
        out.add(copy(FILES, table, owner, PHYSICAL, PENDING, "waiting for the rows"));
    }

    /** A job's Flink state, where its checkpoints and savepoints are. */
    private LakeErasureStatus.Copy flinkStateCopy(String ns, String deployment) {
        LakeErasureStatus.Copy c =
                copy(FLINK_STATE, deployment, PLATFORM, PHYSICAL, PENDING, "waiting for the rows");
        FlinkDeployment fd =
                kube.resources(FlinkDeployment.class).inNamespace(ns).withName(deployment).get();
        String checkpoints =
                fd == null || fd.getSpec().getFlinkConfiguration() == null
                        ? null
                        : fd.getSpec()
                                .getFlinkConfiguration()
                                .asFlatMap()
                                .get("state.checkpoints.dir");
        if (checkpoints == null || !checkpoints.endsWith("/checkpoints")) {
            c.state = PENDING_EVIDENCE;
            c.message = "where the job keeps its state is not known: it cannot be checked";
        } else {
            c.location = checkpoints.substring(0, checkpoints.length() - "/checkpoints".length());
        }
        return c;
    }

    private static LakeErasureStatus.Copy copy(
            String kind, String name, String owner, String layer, String state, String message) {
        LakeErasureStatus.Copy c = new LakeErasureStatus.Copy();
        c.kind = kind;
        c.name = name;
        c.owner = owner;
        c.layer = layer;
        c.state = state;
        c.message = message;
        return c;
    }

    /**
     * The subject's key values: one per line, tab-separated for a key of several columns; null
     * while the Secret does not exist.
     */
    private List<List<String>> subject(LakeErasure erasure) {
        var ref = erasure.getSpec().subjectSecret;
        Secret secret =
                kube.secrets()
                        .inNamespace(erasure.getMetadata().getNamespace())
                        .withName(ref.name)
                        .get();
        if (secret == null) {
            return null;
        }
        String text = SourceTargets.value(secret, ref.key());
        List<List<String>> out = new ArrayList<>();
        if (text != null) {
            for (String line : text.split("\n")) {
                if (!line.isBlank()) {
                    out.add(List.of(line.strip().split("\t", -1)));
                }
            }
        }
        return out;
    }

    /** {@code text} with each of the subject's values masked: they are personal data. */
    static String redact(String text, List<List<String>> subject) {
        String out = String.valueOf(text);
        for (List<String> values : subject) {
            for (String v : values) {
                if (!v.isBlank()) {
                    out = out.replace(v, "<subject>");
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------- identity

    /**
     * READ on every covered table, for the probe; the maintenance the erasure runs are LakeTasks,
     * each with its own identity.
     */
    private void ensureIdentity(Round r) {
        LakeErasure erasure = r.erasure();
        LakeErasureStatus status = r.status();
        Instant now = r.now();
        String principal = CatalogIdentities.principalName(KIND, erasure);
        List<Grant> grants = new ArrayList<>();
        for (String table : tables(status, ROWS)) {
            String[] parts = TableJobs.split(table);
            grants.add(
                    Grant.table(
                            principal, parts[0], parts[1], "READ", erasure.getMetadata().getUid()));
        }
        status.identity =
                identities.ensure(
                        erasure,
                        KIND,
                        "LakeErasure "
                                + erasure.getMetadata().getNamespace()
                                + "/"
                                + erasure.getMetadata().getName(),
                        grants,
                        status.identity,
                        now);
    }

    private static List<String> tables(LakeErasureStatus status, String kind) {
        return status.copies.stream()
                .filter(c -> kind.equals(c.kind) && !BLOCKED.equals(c.state))
                .map(c -> c.name)
                .distinct()
                .toList();
    }

    private static LakeErasureStatus.Copy find(LakeErasureStatus status, String kind, String name) {
        return status.copies.stream()
                .filter(c -> kind.equals(c.kind) && name.equals(c.name))
                .findFirst()
                .orElse(null);
    }

    private static boolean open(LakeErasureStatus.Copy c) {
        return c != null && PENDING.equals(c.state);
    }

    private static void done(LakeErasureStatus.Copy c, Instant now, String evidence) {
        c.state = DONE;
        c.doneAt = now.toString();
        c.evidence = evidence;
    }

    // ------------------------------------------------------------------- progress

    /** One round of work; returns what kept it from looking, or null. */
    private String advance(Round r) {
        LakeErasure erasure = r.erasure();
        LakeErasureStatus status = r.status();
        Instant now = r.now();
        List<List<String>> subject = subject(erasure);
        if (subject == null) {
            return "Secret "
                    + erasure.getSpec().subjectSecret.name
                    + " is gone: nothing is checked";
        }
        sources(r, subject);
        savepoints(r);
        ErasureProbes.Probed probed =
                probeRuns.probe(
                        erasure,
                        status,
                        probedTables(status),
                        probedState(r),
                        probedOrphans(r),
                        now);
        if (probed.result() != null && probed.result().has("error")) {
            return "the probe's result was cut: " + probed.result().path("error").asText();
        }
        if (probed.result() != null) {
            boolean fresh = !Integer.valueOf(probed.run()).equals(status.probeTaken);
            status.probeTaken = probed.run();
            for (JsonNode t : probed.result().path("tables")) {
                table(r, t, fresh);
            }
            for (JsonNode s : probed.result().path("state")) {
                flinkState(status, s, now);
            }
            if (probed.result().has("orphans")) {
                orphans(status, probed.result().path("orphans"), now);
            }
        }
        dashboards(r);
        return probed.problem();
    }

    /**
     * The namespace's dashboards' copies of personal tables: each materialized view and internal
     * table (LLD "看板服务"; a view reads the lake as it is), and, physically, Doris's storage of what
     * their refreshes replaced.
     */
    private List<LakeErasureStatus.Copy> dashboardCopies(
            String ns, GovernancePolicy.Declared governance) {
        List<LakeErasureStatus.Copy> out = new ArrayList<>();
        for (LakeDashboard d :
                kube.resources(LakeDashboard.class).inNamespace(ns).list().getItems()) {
            if (d.getSpec() == null) {
                continue;
            }
            List<String> personal =
                    d.getSpec().tables.stream()
                            .filter(t -> governance.upstream(List.of(t)).personal())
                            .toList();
            if (personal.isEmpty()) {
                continue;
            }
            for (LakeDashboardSpec.Query q : d.getSpec().queries) {
                if (!q.copies()) {
                    continue;
                }
                LakeErasureStatus.Copy c =
                        copy(
                                DASHBOARD,
                                d.getMetadata().getName() + "/" + q.name,
                                owner(d.getSpec().governance),
                                LOGICAL,
                                PENDING,
                                "waiting for the rows of " + personal);
                c.tables = new ArrayList<>(personal);
                out.add(c);
            }
        }
        if (!out.isEmpty()) {
            out.add(
                    copy(
                            DORIS_STORAGE,
                            "be-storage",
                            PLATFORM,
                            PHYSICAL,
                            PENDING,
                            "what the copies' refreshes replace stays in Doris's storage up to "
                                    + DORIS_STORAGE_KEPT.toHours()
                                    + " h"));
        }
        return out;
    }

    /**
     * A dashboard's copy is done once a refresh in full started after every table it copies had
     * none of the subject's rows; a table holding them again opens it again. Doris's storage is
     * done its days after the last of them, the query log its days after the request.
     */
    private void dashboards(Round r) {
        LakeErasureStatus status = r.status();
        Instant now = r.now();
        String ns = r.erasure().getMetadata().getNamespace();
        // A dashboard made after the request copies the lake as it is then, the subject's rows
        // perhaps still in it: its copies join the request's.
        GovernancePolicy.Declared governance =
                GovernancePolicy.Declared.of(
                        kube.resources(LakeSource.class).inNamespace(ns).list().getItems(),
                        kube.resources(LakeTable.class).inNamespace(ns).list().getItems());
        for (LakeErasureStatus.Copy c : dashboardCopies(ns, governance)) {
            if (find(status, c.kind, c.name) == null) {
                status.copies.add(c);
            }
        }
        for (LakeErasureStatus.Copy c : status.copies) {
            if (DORIS_AUDIT.equals(c.kind) && open(c) && status.acceptedAt != null) {
                Instant gone = Instant.parse(status.acceptedAt).plus(DORIS_AUDIT_KEPT);
                if (!now.isBefore(gone)) {
                    done(
                            c,
                            now,
                            "the query log keeps a query "
                                    + DORIS_AUDIT_KEPT.toHours()
                                    + " h at most (7 daily partitions, files 5 days after their"
                                    + " day): nothing from before "
                                    + status.acceptedAt
                                    + " is left");
                } else {
                    c.message = "the query log's entries from before the request go by " + gone;
                }
            }
            if (DASHBOARD.equals(c.kind) && !BLOCKED.equals(c.state)) {
                dashboard(status, c, ns, now);
            }
        }
        storage(status, now);
    }

    private void dashboard(
            LakeErasureStatus status, LakeErasureStatus.Copy c, String ns, Instant now) {
        String[] parts = c.name.split("/", 2);
        LakeDashboard d =
                kube.resources(LakeDashboard.class).inNamespace(ns).withName(parts[0]).get();
        LakeDashboardStatus.QueryState q =
                d == null || d.getStatus() == null
                        ? null
                        : d.getStatus().query(parts[1]).orElse(null);
        if (q == null || q.serve == null) {
            boolean declared =
                    d != null
                            && d.getSpec() != null
                            && d.getSpec().queries.stream()
                                    .anyMatch(s -> s.name.equals(parts[1]) && s.copies());
            if (declared) {
                // Not served yet, or its serving failed: a copy may be on its way.
                c.message = "the dashboard has not served it yet";
            } else if (open(c)) {
                done(c, now, "the dashboard no longer serves it: its copy was dropped");
            }
            return;
        }
        long clean = 0;
        List<String> waiting = new ArrayList<>();
        for (String t : c.tables == null ? List.<String>of() : c.tables) {
            LakeErasureStatus.Copy rows = find(status, ROWS, t);
            if (rows == null || !DONE.equals(rows.state) || rows.cleanMillis == null) {
                waiting.add(t);
            } else {
                clean = Math.max(clean, rows.cleanMillis);
            }
        }
        if (!waiting.isEmpty()) {
            reopen(c);
            c.message = "waiting for the rows of " + waiting;
            return;
        }
        if (q.refreshedAt != null && Instant.parse(q.refreshedAt).toEpochMilli() > clean) {
            if (open(c)) {
                done(
                        c,
                        now,
                        "refreshed in full from "
                                + q.refreshedAt
                                + ", after the rows were gone (clean at "
                                + Instant.ofEpochMilli(clean)
                                + ")");
            }
        } else if (open(c)) {
            c.message =
                    "waiting for a refresh started after the rows were gone (clean at "
                            + Instant.ofEpochMilli(clean)
                            + ")";
        }
    }

    /** Doris's storage: done its days after the last dashboard copy was; open while one is. */
    private static void storage(LakeErasureStatus status, Instant now) {
        LakeErasureStatus.Copy storage = find(status, DORIS_STORAGE, "be-storage");
        if (storage == null) {
            return;
        }
        List<LakeErasureStatus.Copy> copies =
                status.copies.stream().filter(c -> DASHBOARD.equals(c.kind)).toList();
        if (copies.stream().anyMatch(c -> !DONE.equals(c.state))) {
            reopen(storage);
            storage.message = "waiting for the dashboards' copies";
            return;
        }
        Instant last =
                copies.stream()
                        .map(c -> Instant.parse(c.doneAt))
                        .max(Instant::compareTo)
                        .orElse(now);
        Instant gone = last.plus(DORIS_STORAGE_KEPT);
        if (!now.isBefore(gone)) {
            if (open(storage)) {
                done(
                        storage,
                        now,
                        "the last copy was refreshed at "
                                + last
                                + "; Doris keeps what a refresh replaced "
                                + DORIS_STORAGE_KEPT.toHours()
                                + " h at most");
            }
        } else if (open(storage)) {
            storage.message = "what the refreshes replaced goes by " + gone;
        }
    }

    /** A copy done before is open again: its evidence no longer holds. */
    private static void reopen(LakeErasureStatus.Copy c) {
        if (DONE.equals(c.state)) {
            c.state = PENDING;
            c.doneAt = null;
            c.evidence = null;
        }
    }

    /** The source databases: the user deletes there; the platform checks it did. */
    private void sources(Round r, List<List<String>> subject) {
        LakeErasure erasure = r.erasure();
        LakeErasureStatus status = r.status();
        Instant now = r.now();
        String ns = erasure.getMetadata().getNamespace();
        for (LakeErasureStatus.Copy c : status.copies) {
            // Done ones too: the source may get the subject's rows again.
            if (!SOURCE_DATABASE.equals(c.kind) || BLOCKED.equals(c.state)) {
                continue;
            }
            String[] parts = c.name.split("/", 2);
            LakeSource source =
                    kube.resources(LakeSource.class).inNamespace(ns).withName(parts[0]).get();
            if (source == null) {
                // A source checked done stays so; one not yet checked cannot be any more.
                if (open(c)) {
                    block(c, "the LakeSource is gone: the source cannot be checked");
                    c.reviewBy = status.logicalDeadline;
                }
                continue;
            }
            try {
                SourceProbe probe = probes.get(source.getSpec().type);
                SourceProbe.Target target = SourceTargets.target(kube, source);
                var table = probe.inventory(target).tables().get(parts[1]);
                if (table == null) {
                    if (open(c)) {
                        done(c, now, "the source table no longer exists");
                    }
                    continue;
                }
                // The key as accepted: the declaration may have changed since.
                int left =
                        probe.versionsOf(
                                        target,
                                        table,
                                        c.subjectKey,
                                        SourceJobs.versionColumn(source.getSpec()),
                                        subject)
                                .size();
                c.seen = Math.max(c.seen == null ? 0 : c.seen, left);
                if (left == 0) {
                    if (open(c)) {
                        done(c, now, "no row of the subject in the source at " + now);
                        c.message = "the source deleted the subject's rows";
                    }
                } else if (DONE.equals(c.state)) {
                    c.state = PENDING;
                    c.doneAt = null;
                    c.evidence = null;
                    c.message = "the source holds rows of the subject again";
                    status.logicallyErasedAt = null;
                    events.warning(
                            erasure,
                            "SubjectReturned",
                            c.name + " holds rows of the subject again");
                } else {
                    c.message =
                            "the source still holds rows of the subject; the source's owner deletes them";
                }
            } catch (Exception e) {
                // A driver's message may repeat a value it was given.
                String why = redact(e.getMessage(), subject);
                LOG.warn("Checking {} failed: {}", c.name, why);
                c.message = "checking the source: " + JobObservation.abbreviate(why);
            }
        }
    }

    /** The tables a copy waits for, with what the erasure knows of them. */
    private static List<Map<String, Object>> probedTables(LakeErasureStatus status) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String table : tables(status, ROWS)) {
            LakeErasureStatus.Copy rows = find(status, ROWS, table);
            LakeErasureStatus.Copy files = find(status, FILES, table);
            // Done tables too, until the erasure is physically complete: the subject may come back.
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("table", table);
            t.put("subjectKey", rows.subjectKey);
            if (rows.cleanSnapshot != null) {
                t.put("clean", rows.cleanSnapshot);
            }
            if (files.compactedSnapshot != null) {
                t.put("compacted", files.compactedSnapshot);
            }
            out.add(t);
        }
        return out;
    }

    /**
     * The jobs' state to list, once every table's rows are clean: what is older than the latest
     * clean snapshot may hold the subject.
     */
    private List<Map<String, Object>> probedState(Round r) {
        LakeErasure erasure = r.erasure();
        LakeErasureStatus status = r.status();
        long clean = cleanMillis(status);
        List<Map<String, Object>> out = new ArrayList<>();
        if (clean < 0) {
            return out;
        }
        String ns = erasure.getMetadata().getNamespace();
        for (LakeErasureStatus.Copy c : status.copies) {
            if (!FLINK_STATE.equals(c.kind)
                    || !open(c)
                    || c.location == null
                    || ORPHANED_STATE.equals(c.name)) {
                continue;
            }
            FlinkDeployment fd =
                    kube.resources(FlinkDeployment.class).inNamespace(ns).withName(c.name).get();
            var job = fd == null || fd.getStatus() == null ? null : fd.getStatus().getJobStatus();
            // What a restore would start from, unless the job runs: a running job that restarts
            // resumes from its latest checkpoint.
            List<String> keep = new ArrayList<>();
            boolean running =
                    job != null
                            && job.getState() != null
                            && job.getState().name().equals("RUNNING");
            if (fd != null && !running) {
                if (job != null && job.getUpgradeSavepointPath() != null) {
                    keep.add(job.getUpgradeSavepointPath());
                }
                if (fd.getSpec().getJob() != null
                        && fd.getSpec().getJob().getInitialSavepointPath() != null) {
                    keep.add(fd.getSpec().getJob().getInitialSavepointPath());
                }
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", c.name);
            s.put("location", c.location);
            s.put("before", clean);
            s.put("job", job == null ? null : job.getJobId());
            s.put("running", running);
            s.put("keep", keep);
            out.add(s);
        }
        return out;
    }

    /**
     * The namespace's state to look through for directories no current owner has, once every
     * table's rows are clean: a state directory's name ends in its owner's uid prefix.
     */
    private Map<String, Object> probedOrphans(Round r) {
        LakeErasure erasure = r.erasure();
        LakeErasureStatus status = r.status();
        long clean = cleanMillis(status);
        LakeErasureStatus.Copy c = find(status, FLINK_STATE, ORPHANED_STATE);
        if (clean < 0 || !open(c)) {
            return null;
        }
        String ns = erasure.getMetadata().getNamespace();
        // A job's state directory is its deployment's name and its owner's uid prefix.
        Set<String> known = new TreeSet<>();
        for (LakeSource s : kube.resources(LakeSource.class).inNamespace(ns).list().getItems()) {
            String uid = s.getMetadata().getUid().substring(0, 8);
            if (s.getStatus() != null) {
                s.getStatus().jobs.forEach(j -> known.add(j.deployment + "-" + uid));
            }
        }
        for (LakeTable t : kube.resources(LakeTable.class).inNamespace(ns).list().getItems()) {
            known.add(TableJobs.deploymentName(t) + "-" + t.getMetadata().getUid().substring(0, 8));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("root", c.location);
        out.put("known", known);
        out.put("before", clean);
        return out;
    }

    /** The latest clean snapshot's time, once every table's rows are clean; -1 before. */
    private static long cleanMillis(LakeErasureStatus status) {
        boolean allRows =
                status.copies.stream()
                        .filter(c -> ROWS.equals(c.kind) && !BLOCKED.equals(c.state))
                        .allMatch(c -> DONE.equals(c.state));
        if (!allRows) {
            return -1;
        }
        return status.copies.stream()
                .filter(c -> ROWS.equals(c.kind) && c.cleanMillis != null)
                .mapToLong(c -> c.cleanMillis)
                .max()
                .orElse(-1);
    }

    /**
     * One table's probe result. A result is taken up again each round until the next run gives one;
     * only a {@code fresh} one, not yet taken up, says the subject came back.
     */
    private void table(Round r, JsonNode t, boolean fresh) {
        LakeErasure erasure = r.erasure();
        LakeErasureStatus status = r.status();
        Instant now = r.now();
        String table = t.path("table").asText();
        LakeErasureStatus.Copy rows = find(status, ROWS, table);
        if (rows == null || BLOCKED.equals(rows.state)) {
            return;
        }
        LakeErasureStatus.Copy history = find(status, HISTORY, table);
        LakeErasureStatus.Copy files = find(status, FILES, table);
        if (t.has("error")) {
            rows.message =
                    "the probe could not read the table: "
                            + JobObservation.abbreviate(t.path("error").asText());
            return;
        }
        if (t.path("missing").asBoolean()) {
            for (LakeErasureStatus.Copy c : List.of(rows, history, files)) {
                if (open(c)) {
                    done(c, now, "the table no longer exists");
                }
            }
            // A table made again under its name is judged anew.
            rows.cleanSnapshot = null;
            files.compactedSnapshot = null;
            files.compactedMillis = null;
            return;
        }
        if (DONE.equals(rows.state) && rows.cleanSnapshot == null) {
            // Done as missing, and there again: judged anew.
            reopen(r, table, "TableReturned", table + " exists again");
        }
        if (!t.path("fits").asBoolean(true)) {
            // Nothing was counted: what is open cannot be done.
            for (LakeErasureStatus.Copy c : List.of(rows, history, files)) {
                if (open(c)) {
                    block(
                            c,
                            "the subject's values do not fit the type of the subject key "
                                    + rows.subjectKey
                                    + ": counted against it they would find nothing");
                    c.reviewBy = status.logicalDeadline;
                }
            }
            return;
        }
        boolean sourceDone =
                status.copies.stream()
                        .filter(c -> SOURCE_DATABASE.equals(c.kind))
                        .allMatch(c -> DONE.equals(c.state));
        long snapshot = t.path("snapshot").asLong(0);
        long millis = t.path("snapshotMillis").asLong(now.toEpochMilli());
        long n = t.path("rows").asLong();
        rows.seen = Math.max(rows.seen == null ? 0 : rows.seen, n);
        // Written again (a replay, the subject coming back): rows; rows of the subject added
        // after the clean snapshot; or, its history done, any of its records there again.
        boolean again =
                n > 0
                        || t.path("added").asLong(0) > 0
                        || DONE.equals(history.state) && t.path("changes").asLong(0) > 0;
        if (fresh && again && !open(rows)) {
            reopen(r, table, "SubjectReturned", table + " holds rows of the subject again");
        }
        if (!DONE.equals(rows.state)) {
            LakeErasureStatus.Copy source =
                    status.copies.stream()
                            .filter(c -> SOURCE_DATABASE.equals(c.kind) && table.equals(c.table))
                            .findFirst()
                            .orElse(null);
            if (n > 0 || !sourceDone) {
                rows.message =
                        n
                                + " rows of the subject in snapshot "
                                + snapshot
                                + (sourceDone ? "" : "; the source still has them");
                if (n > 0 && sourceDone) {
                    String backfill = backfillIfOrphaned(r, table, n);
                    if (backfill != null) {
                        rows.message += "; " + backfill;
                    }
                }
                return;
            }
            if (source != null && source.seen != null && source.seen > 0 && rows.seen == 0) {
                // A positive count is the check that the key matches: none, and no 0 is proof.
                block(
                        rows,
                        "the source held "
                                + source.seen
                                + " rows of the subject and this table never one: the subject key"
                                + " may not match the values it holds");
                rows.reviewBy = status.logicalDeadline;
                return;
            }
            done(rows, now, "no row of the subject in snapshot " + snapshot + backfilled(r, table));
            rows.cleanSnapshot = snapshot;
            rows.cleanMillis = millis;
            rows.message = "the current table returns no row of the subject";
        }
        long clean = rows.cleanSnapshot;
        long records = t.path("records").asLong();
        long mentions = t.path("mentions").asLong();
        // The Iceberg metadata a table's writers keep beside it lists files with statistics too.
        long icebergMentions = t.path("icebergMentions").asLong(0);
        long changes = t.path("changes").asLong(-1);
        // Files first: a full compaction rewrites the files and then the manifests, whose
        // statistics name values; once the latest snapshot's hold none of the subject, everything
        // before it can go, the clean snapshot's changes with it.
        if (open(files)) {
            if (records > 0 || mentions > 0 || icebergMentions > 0) {
                files.compactedSnapshot = null;
                runTask(r, table, "compact", "files", null);
            } else if (files.compactedSnapshot == null) {
                files.compactedSnapshot = snapshot;
                files.compactedMillis = millis;
            }
        }
        Long compacted = files.compactedSnapshot;
        long earliest = t.path("earliest").asLong(clean);
        Long changelog =
                t.path("changelogEarliest").isNumber()
                        ? t.path("changelogEarliest").asLong()
                        : null;
        Pins pins = Pins.of(t);
        for (String tag : pins.tagsBefore(compacted != null ? compacted : clean)) {
            runTask(r, table, "deleteTag", tag, tag);
        }
        // History: no read path reaches the subject's rows or changes. Every snapshot before the
        // clean one goes, the clean one too when its changes hold the subject's deletes; and the
        // files themselves hold none, as the read-optimized and audit-log system tables read them.
        if (open(history)) {
            List<String> holding = new ArrayList<>();
            if (earliest < clean) {
                holding.add("snapshots " + earliest + " to " + (clean - 1));
            }
            if (changes != 0) {
                holding.add(
                        changes < 0
                                ? "the changes of the snapshots kept are not searched yet"
                                : changes + " records of the subject in the changes kept");
            }
            if (changelog != null && changelog <= clean) {
                holding.add("changelog kept from snapshot " + changelog);
            }
            if (records > 0) {
                holding.add(
                        records + " records of the subject in the files, which system tables read");
            }
            holding.addAll(pins.before(clean));
            if (holding.isEmpty()) {
                done(
                        history,
                        now,
                        "earliest snapshot "
                                + earliest
                                + " >= clean snapshot "
                                + clean
                                + "; no record of the subject in the changes kept or the files; no"
                                + " older tag or branch; consumers past it");
                history.message =
                        "no read path reaches a snapshot, change or file with the subject";
            } else {
                history.message = "still reachable: " + String.join(", ", holding);
                expire(r, table, compacted != null ? files.compactedMillis : rows.cleanMillis);
            }
        }
        // Files: the subject's records and statistics gone from the latest snapshot, every
        // snapshot before the compacted one gone with their changelog, and an orphan cleaning that
        // covers the files written before it.
        if (open(files)) {
            if (records > 0 || mentions > 0 || icebergMentions > 0) {
                files.message =
                        records
                                + " records of the subject (deletes included) in the files of"
                                + " snapshot "
                                + snapshot
                                + ", "
                                + mentions
                                + " files its manifests list with the subject in their"
                                + " statistics"
                                + (icebergMentions > 0
                                        ? ", " + icebergMentions + " in its Iceberg metadata"
                                        : "")
                                + "; compacting";
                return;
            }
            List<String> holding = new ArrayList<>();
            if (earliest < compacted) {
                holding.add("snapshots " + earliest + " to " + (compacted - 1));
            }
            if (changelog != null && changelog < compacted) {
                holding.add("changelog kept from snapshot " + changelog);
            }
            if (changes > 0) {
                holding.add(changes + " records of the subject in the changelog files kept");
            }
            holding.addAll(pins.before(compacted));
            if (!holding.isEmpty()) {
                files.message =
                        "the files are clean; older files are still kept by: "
                                + String.join(", ", holding);
                expire(r, table, files.compactedMillis);
                return;
            }
            Instant orphansDue = Instant.ofEpochMilli(files.compactedMillis).plus(ORPHAN_AGE);
            if (now.isBefore(orphansDue)) {
                files.message =
                        "files clean; an orphan-file cleaning at "
                                + orphansDue
                                + " covers files written before snapshot "
                                + compacted;
                return;
            }
            LakeTask task = runTask(r, table, "removeOrphanFiles", "files", null);
            if (task == null) {
                return;
            }
            String orphans = task.getMetadata().getName();
            if (task.getStatus() != null
                    && LakeTaskReconciler.SUCCEEDED.equals(task.getStatus().phase)) {
                done(
                        files,
                        now,
                        "no record of the subject in the files of snapshot "
                                + snapshot
                                + " nor in their manifests' statistics; no snapshot before "
                                + compacted
                                + "; "
                                + orphans
                                + ": "
                                + task.getStatus().message);
                files.message = "no file holds the subject";
            } else {
                files.message = "files clean; waiting for " + orphans;
            }
        }
    }

    /**
     * The table holds rows of the subject again (a replay, or the subject coming back): what was
     * done is undone, its jobs' state with it, and the erasure is no longer logically complete.
     */
    private void reopen(Round r, String table, String reason, String why) {
        LakeErasure erasure = r.erasure();
        LakeErasureStatus status = r.status();
        for (LakeErasureStatus.Copy c : status.copies) {
            boolean ofTable =
                    table.equals(c.name) && List.of(ROWS, HISTORY, FILES).contains(c.kind);
            if ((ofTable || FLINK_STATE.equals(c.kind)) && DONE.equals(c.state)) {
                c.state = PENDING;
                c.doneAt = null;
                c.evidence = null;
            }
            if (ofTable) {
                c.cleanSnapshot = null;
                c.compactedSnapshot = null;
            }
            if (ofTable && ROWS.equals(c.kind)) {
                // Its maintenance runs again: the tasks of the earlier round are not this one's.
                c.round = (c.round == null ? 0 : c.round) + 1;
            }
        }
        status.logicallyErasedAt = null;
        events.warning(erasure, reason, why);
    }

    /**
     * What keeps snapshots of a table from expiring, as the probe listed it: tags, which the
     * erasure deletes; branches and readers' consumers, which it cannot. Lists the probe cut short
     * keep the rest unknown, which holds.
     */
    private record Pins(
            Map<String, Long> tags, List<String> branches, Map<String, Long> consumers, int more) {
        static Pins of(JsonNode t) {
            Map<String, Long> tags = new TreeMap<>();
            t.path("tags")
                    .fields()
                    .forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asLong()));
            List<String> branches = new ArrayList<>();
            t.path("branches").fieldNames().forEachRemaining(branches::add);
            Map<String, Long> consumers = new TreeMap<>();
            t.path("consumers")
                    .fields()
                    .forEachRemaining(e -> consumers.put(e.getKey(), e.getValue().asLong()));
            int more =
                    t.path("tagsMore").asInt()
                            + t.path("branchesMore").asInt()
                            + t.path("consumersMore").asInt();
            return new Pins(tags, branches, consumers, more);
        }

        List<String> tagsBefore(long snapshot) {
            return tags.entrySet().stream()
                    .filter(e -> e.getValue() < snapshot)
                    .map(Map.Entry::getKey)
                    .toList();
        }

        /** Those that reach a snapshot before {@code snapshot}, or its changes. */
        List<String> before(long snapshot) {
            List<String> out = new ArrayList<>();
            tagsBefore(snapshot).forEach(tag -> out.add("tag " + tag));
            branches.forEach(b -> out.add("branch " + b + " (its owner deletes it)"));
            consumers.forEach(
                    (id, next) -> {
                        if (next <= snapshot) {
                            out.add(
                                    "consumer "
                                            + id
                                            + " at "
                                            + next
                                            + " (it reads past or expires with the retention)");
                        }
                    });
            if (more > 0) {
                out.add(more + " more the probe did not list");
            }
            return out;
        }
    }

    /**
     * An expiry of the snapshots written before {@code millis}, the clean or the compacted
     * snapshot's time: older than its age, rounded down, so that snapshot goes too unless it is the
     * latest (one snapshot is always kept).
     */
    private void expire(Round r, String table, long millis) {
        Instant now = r.now();
        long minutes = Duration.between(Instant.ofEpochMilli(millis), now).toMinutes();
        if (minutes >= 1) {
            runTask(r, table, "expireSnapshots", "history", minutes + "m");
        }
    }

    /** How long the erasure waits after a run of a kind ended before it runs it again. */
    static final Duration RERUN_AFTER = Duration.ofMinutes(10);

    /**
     * How long a layered table's upstream stays clean before a backfill removes the subject's rows
     * its stream left: the stream's own deletions arrive within a few checkpoints.
     */
    static final Duration BACKFILL_AFTER = Duration.ofMinutes(5);

    /**
     * A layered table that still holds the subject's rows while every table it reads that the
     * erasure counts holds none, and its stream has read past each one's clean snapshot: the stream
     * did not carry the deletion. A lookup join that no longer finds the dimension row drops the
     * change it joins, deletions included, when the dimension went first. A backfill recomputes the
     * table from what it reads and deletes what that no longer derives (LakeTask), once its
     * upstream has been clean {@link #BACKFILL_AFTER}; an event says so, and the table's rows, once
     * clean, name it. The quarantine of a table with checks is the table's too: its rows go with
     * the same backfill of the table. A stream not yet past a clean snapshot is waited for; a table
     * none of whose upstream the erasure counts is left as it is: where its rows come from is not
     * known. What happens, or null.
     */
    private String backfillIfOrphaned(Round r, String table, long rowsLeft) {
        LakeErasureStatus status = r.status();
        LakeErasure erasure = r.erasure();
        String ns = erasure.getMetadata().getNamespace();
        LakeTable layered =
                TableJobs.writer(
                        table, kube.resources(LakeTable.class).inNamespace(ns).list().getItems());
        if (layered == null) {
            return null;
        }
        // A quarantine is rewritten by its table's backfill.
        String target = TableJobs.target(layered.getSpec());
        LakeTableStatus at = layered.getStatus();
        List<String> streams = at.applied == null ? List.of() : at.applied.streams;
        long cleanSince = -1;
        for (String upstream : layered.getSpec().upstream) {
            LakeErasureStatus.Copy rows = find(status, ROWS, upstream);
            if (rows == null) {
                // No personal data there: nothing to wait for.
                continue;
            }
            if (!DONE.equals(rows.state)
                    || rows.cleanMillis == null
                    || rows.cleanSnapshot == null) {
                return null;
            }
            if (streams.contains(upstream)) {
                // A lookup reads the table as it is; a stream must have read the deletion.
                Long next =
                        at.upstream == null
                                ? null
                                : at.upstream.stream()
                                        .filter(u -> upstream.equals(u.table))
                                        .map(u -> u.nextSnapshot)
                                        .filter(Objects::nonNull)
                                        .findFirst()
                                        .orElse(null);
                if (next == null || next <= rows.cleanSnapshot) {
                    return "its stream reads "
                            + upstream
                            + " from snapshot "
                            + (next == null ? "?" : next)
                            + ", not yet past its clean snapshot "
                            + rows.cleanSnapshot;
                }
            }
            cleanSince = Math.max(cleanSince, rows.cleanMillis);
        }
        if (cleanSince < 0) {
            return null;
        }
        Instant clean = Instant.ofEpochMilli(cleanSince);
        if (clean.plus(BACKFILL_AFTER).isAfter(r.now())) {
            return "what it reads is clean since " + clean + "; a backfill follows if it stays so";
        }
        int known = status.tasks.size();
        LakeTask task = runTask(r, target, "backfill", "rows", null);
        if (task == null) {
            return "the backfill could not be created";
        }
        String why =
                "what it reads holds none since "
                        + clean
                        + " and its stream read past that: the stream did not carry the deletion";
        if (status.tasks.size() > known) {
            events.normal(
                    erasure,
                    "ErasureBackfill",
                    table
                            + " holds "
                            + rowsLeft
                            + " rows of the subject; "
                            + why
                            + "; backfill "
                            + task.getMetadata().getName()
                            + " recomputes "
                            + target);
        }
        String phase = task.getStatus() == null ? "Pending" : task.getStatus().phase;
        return why
                + "; backfill "
                + task.getMetadata().getName()
                + " is "
                + phase
                + (task.getStatus() != null && task.getStatus().message != null
                        ? " (" + JobObservation.abbreviate(task.getStatus().message) + ")"
                        : "");
    }

    /**
     * The table's rows' evidence names the backfill that removed them, what it changed against the
     * most rows of the subject the table held; empty when none ran.
     */
    private String backfilled(Round r, String table) {
        LakeTable layered =
                TableJobs.writer(
                        table,
                        kube.resources(LakeTable.class)
                                .inNamespace(r.erasure().getMetadata().getNamespace())
                                .list()
                                .getItems());
        if (layered == null) {
            return "";
        }
        String target = TableJobs.target(layered.getSpec());
        LakeTask task = lastTask(r, taskBase(r, target, "backfill", "rows")).task();
        if (task == null || task.getStatus() == null || task.getStatus().result == null) {
            return "";
        }
        LakeErasureStatus.Copy rows = find(r.status(), ROWS, table);
        return ", after backfill "
                + task.getMetadata().getName()
                + " ("
                + task.getStatus().result.detail
                + "; the table held "
                + (rows == null || rows.seen == null ? "?" : rows.seen)
                + " rows of the subject at most)";
    }

    /** The erasure's tasks of one base name, numbered: the count of them and the last. */
    private record Tasks(int count, LakeTask task) {}

    private Tasks lastTask(Round r, String base) {
        String ns = r.erasure().getMetadata().getNamespace();
        Pattern mine = Pattern.compile(Pattern.quote(base) + "-(\\d+)");
        int n = 0;
        LakeTask last = null;
        for (String name : r.status().tasks) {
            Matcher m = mine.matcher(name);
            if (m.matches() && Integer.parseInt(m.group(1)) > n) {
                n = Integer.parseInt(m.group(1));
                last = kube.resources(LakeTask.class).inNamespace(ns).withName(name).get();
            }
        }
        return new Tasks(n, last);
    }

    /**
     * The name the erasure's tasks of this type and purpose on this table share, but for their
     * number. A tag's name may fold into another's: the purpose is its hash. A table reopened runs
     * its maintenance again, under names of that round.
     */
    private String taskBase(Round r, String table, String type, String purpose) {
        String what =
                "deleteTag".equals(type) ? FlinkJobs.sha256(purpose).substring(0, 8) : purpose;
        LakeErasureStatus.Copy rows = find(r.status(), ROWS, table);
        if (rows != null && rows.round != null && rows.round > 0) {
            what += "-r" + rows.round;
        }
        return FlinkJobs.name(
                r.erasure().getMetadata().getName(),
                type.toLowerCase(Locale.ROOT),
                table.replace('.', '-').replace('_', '-'),
                what.toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    /** An erasure's task of this type on the table; the parameter is the type's own. */
    static LakeTaskSpec taskSpec(String type, String table, String parameter) {
        LakeTaskSpec spec = new LakeTaskSpec();
        spec.type = type;
        spec.table = new LakeTaskSpec.Table();
        String[] parts = TableJobs.split(table);
        spec.table.database = parts[0];
        spec.table.table = parts[1];
        switch (type) {
            case "expireSnapshots" -> {
                spec.expireSnapshots = new LakeTaskSpec.ExpireSnapshots();
                spec.expireSnapshots.retainMin = 1;
                spec.expireSnapshots.olderThan = parameter;
            }
            case "compact" -> {
                spec.compact = new LakeTaskSpec.Compact();
                spec.compact.full = true;
                // The manifests' statistics name values, a table keeping Iceberg metadata too.
                spec.compact.manifests = true;
            }
            case "deleteTag" -> {
                spec.deleteTag = new LakeTaskSpec.DeleteTag();
                spec.deleteTag.name = parameter;
            }
            case "backfill" -> {
                // The LakeTable's own query, nothing to set.
            }
            case "removeOrphanFiles" -> {
                spec.removeOrphanFiles = new LakeTaskSpec.RemoveOrphanFiles();
                spec.removeOrphanFiles.olderThan = ORPHAN_AGE.toDays() + "d";
            }
            default -> throw new IllegalArgumentException(type);
        }
        return spec;
    }

    /**
     * The erasure's latest LakeTask of this type and purpose on this table, owned by the erasure: a
     * new one when none exists, or the last ended at least {@link #RERUN_AFTER} ago (an expiry a
     * reader's consumer held back is tried again, a compaction the subject's records came back
     * after, and any that failed). A succeeded tag deletion, cleaning or backfill is not run again.
     * A task created before the status recorded it (the operator stopped in between) is taken up
     * again; one the API server refuses is null.
     */
    private LakeTask runTask(Round r, String table, String type, String purpose, String parameter) {
        LakeErasure erasure = r.erasure();
        LakeErasureStatus status = r.status();
        Instant now = r.now();
        String ns = erasure.getMetadata().getNamespace();
        String base = taskBase(r, table, type, purpose);
        Tasks known = lastTask(r, base);
        int n = known.count();
        LakeTask last = known.task();
        if (last != null) {
            String phase = last.getStatus() == null ? null : last.getStatus().phase;
            boolean ended =
                    last.getStatus() != null && LakeTaskReconciler.finished(last.getStatus());
            // An expiry or a compaction runs again while what it was for is not there yet.
            boolean again =
                    ended
                            && ("expireSnapshots".equals(type)
                                    || "compact".equals(type)
                                    || LakeTaskReconciler.FAILED.equals(phase))
                            && Instant.parse(last.getStatus().finishedAt)
                                    .plus(RERUN_AFTER)
                                    .isBefore(now);
            if (!again) {
                return last;
            }
        }
        String name = base + "-" + (n + 1);
        LakeTask existing = kube.resources(LakeTask.class).inNamespace(ns).withName(name).get();
        if (existing != null
                && existing.getMetadata().getOwnerReferences().stream()
                        .anyMatch(o -> erasure.getMetadata().getUid().equals(o.getUid()))) {
            status.tasks.add(name);
            return existing;
        }
        LakeTask task = new LakeTask();
        task.getMetadata().setName(name);
        task.getMetadata().setNamespace(ns);
        task.getMetadata()
                .setLabels(Map.of("colvenna-lake.io/erasure", erasure.getMetadata().getName()));
        task.getMetadata()
                .setOwnerReferences(
                        List.of(
                                new OwnerReferenceBuilder()
                                        .withApiVersion(erasure.getApiVersion())
                                        .withKind(erasure.getKind())
                                        .withName(erasure.getMetadata().getName())
                                        .withUid(erasure.getMetadata().getUid())
                                        .withController(true)
                                        .build()));
        task.setSpec(taskSpec(type, table, parameter));
        try {
            LakeTask created = kube.resource(task).create();
            status.tasks.add(name);
            return created;
        } catch (KubernetesClientException e) {
            // One task the API server refuses holds back no other copy.
            LOG.warn("Creating {} failed: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * The savepoints of the covered jobs taken before the tables were clean: their
     * FlinkStateSnapshot goes, so the operator restores none of them; the probe then disposes of
     * their files.
     */
    private void savepoints(Round r) {
        LakeErasure erasure = r.erasure();
        LakeErasureStatus status = r.status();
        long clean = cleanMillis(status);
        if (clean < 0) {
            return;
        }
        String ns = erasure.getMetadata().getNamespace();
        Set<String> jobs = new TreeSet<>();
        status.copies.stream()
                .filter(c -> FLINK_STATE.equals(c.kind) && open(c))
                .forEach(c -> jobs.add(c.name));
        for (GenericKubernetesResource snapshot :
                kube.genericKubernetesResources(SNAPSHOTS).inNamespace(ns).list().getItems()) {
            Object job = path(snapshot.getAdditionalProperties(), "spec", "jobReference", "name");
            Instant created = Instant.parse(snapshot.getMetadata().getCreationTimestamp());
            if (jobs.contains(job) && created.toEpochMilli() < clean) {
                kube.genericKubernetesResources(SNAPSHOTS)
                        .inNamespace(ns)
                        .withName(snapshot.getMetadata().getName())
                        .delete();
            }
        }
    }

    /** A job's Flink state, from the probe's listing of it. */
    private static void flinkState(LakeErasureStatus status, JsonNode s, Instant now) {
        LakeErasureStatus.Copy c = find(status, FLINK_STATE, s.path("name").asText());
        if (open(c) && s.has("error")) {
            c.message = "the probe could not list the state: " + s.path("error").asText();
            return;
        }
        if (!open(c) || !current(status, s)) {
            return;
        }
        String disposed =
                s.path("disposedCount").asInt() == 0
                        ? ""
                        : "; disposed of "
                                + s.path("disposedCount").asInt()
                                + ": "
                                + s.path("disposed");
        long older = s.path("older").asLong();
        if (older == 0) {
            done(
                    c,
                    now,
                    "no object of "
                            + c.location
                            + "/checkpoints or /savepoints written before the clean snapshots ("
                            + s.path("objects").asLong()
                            + " objects)"
                            + disposed);
            c.message = "no state the job could restore holds the subject";
        } else {
            c.message =
                    older
                            + " objects written before the clean snapshots in "
                            + s.path("left")
                            + " (Flink drops a running job's older checkpoints as new ones"
                            + " complete)"
                            + disposed;
        }
    }

    /**
     * Whether the probe looked at the state against the tables' clean snapshots as they are: rows
     * that came back since make it stale.
     */
    private static boolean current(LakeErasureStatus status, JsonNode listed) {
        long clean = cleanMillis(status);
        return clean >= 0 && listed.path("before").asLong(-1) >= clean;
    }

    /** The state of jobs no longer declared, from the probe's look through the namespace's. */
    private static void orphans(LakeErasureStatus status, JsonNode o, Instant now) {
        LakeErasureStatus.Copy c = find(status, FLINK_STATE, ORPHANED_STATE);
        if (open(c) && o.has("error")) {
            c.message = "the probe could not look through the state: " + o.path("error").asText();
            return;
        }
        if (!open(c) || !current(status, o)) {
            return;
        }
        String disposed =
                o.path("disposedCount").asInt() == 0
                        ? "nothing to dispose of"
                        : "disposed of "
                                + o.path("disposedCount").asInt()
                                + ": "
                                + o.path("disposed");
        if (o.path("leftCount").asInt() == 0) {
            done(c, now, "no state under " + c.location + " without a current owner; " + disposed);
            c.message = "no job that is gone left state";
        } else {
            c.message =
                    o.path("leftCount").asInt()
                            + " state directories without a current owner hold objects written"
                            + " after the clean snapshots, kept: "
                            + o.path("left")
                            + "; "
                            + disposed;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object path(Map<String, Object> root, String... keys) {
        Object o = root;
        for (String k : keys) {
            if (!(o instanceof Map)) {
                return null;
            }
            o = ((Map<String, Object>) o).get(k);
        }
        return o;
    }

    // ------------------------------------------------------------------- summary

    private void summarize(Round r, String problem) {
        LakeErasure erasure = r.erasure();
        LakeErasureStatus status = r.status();
        Instant now = r.now();
        List<LakeErasureStatus.Copy> platform =
                status.copies.stream().filter(c -> !EXTERNAL.equals(c.state)).toList();
        List<LakeErasureStatus.Copy> blocked =
                platform.stream().filter(c -> BLOCKED.equals(c.state)).toList();
        List<String> logicalLeft = left(platform, List.of(LOGICAL));
        List<String> physicalLeft = left(platform, List.of(LOGICAL, PHYSICAL));
        if (logicalLeft.isEmpty() && status.logicallyErasedAt == null) {
            status.logicallyErasedAt = now.toString();
            events.normal(erasure, "LogicallyErased", "no managed read path returns the subject");
        }
        if (physicalLeft.isEmpty() && status.physicallyErasedAt == null) {
            status.physicallyErasedAt = now.toString();
            events.normal(
                    erasure, "PhysicallyErased", "no managed storage can bring the subject back");
        }
        // A deadline passed is recorded once, and stays when the erasure completes later.
        breach(r, "logical", status.logicalDeadline, status.logicallyErasedAt, logicalLeft);
        breach(r, "physical", status.physicalDeadline, status.physicallyErasedAt, physicalLeft);
        boolean overdue =
                status.logicallyErasedAt == null
                                && now.isAfter(Instant.parse(status.logicalDeadline))
                        || status.physicallyErasedAt == null
                                && now.isAfter(Instant.parse(status.physicalDeadline));
        String phase;
        String message;
        if (status.physicallyErasedAt != null) {
            phase = PHYSICALLY_ERASED;
            message =
                    "every managed copy is erased; external: "
                            + external(status)
                            + (status.breaches.isEmpty() ? "" : "; late: " + status.breaches);
        } else if (overdue) {
            phase = SLA_BREACH;
            message = String.join("; ", status.breaches);
        } else if (!blocked.isEmpty()) {
            phase = BLOCKED;
            message =
                    "blocked, for their owners to review by "
                            + status.logicalDeadline
                            + ": "
                            + blocked.stream()
                                    .map(
                                            c ->
                                                    c.kind + " " + c.name + " (" + c.owner + ": "
                                                            + c.message + ")")
                                    .toList();
        } else if (status.logicallyErasedAt != null) {
            phase = LOGICALLY_ERASED;
            message = "not physically erased yet: " + physicalLeft;
        } else {
            phase = ACCEPTED;
            message = "not logically erased yet: " + logicalLeft;
        }
        if (!phase.equals(status.phase) && BLOCKED.equals(phase)) {
            events.warning(erasure, "ErasureBlocked", message);
        }
        status.phase = phase;
        status.message = problem == null ? message : problem + "; " + message;
        long generation = Objects.requireNonNullElse(erasure.getMetadata().getGeneration(), 0L);
        StatusCondition.set(
                status.conditions,
                "LogicallyErased",
                status.logicallyErasedAt != null,
                status.logicallyErasedAt != null ? "Erased" : "Pending",
                status.logicallyErasedAt != null
                        ? "at " + status.logicallyErasedAt
                        : logicalLeft.toString(),
                generation,
                now);
        StatusCondition.set(
                status.conditions,
                "PhysicallyErased",
                status.physicallyErasedAt != null,
                status.physicallyErasedAt != null ? "Erased" : "Pending",
                status.physicallyErasedAt != null
                        ? "at " + status.physicallyErasedAt
                        : physicalLeft.toString(),
                generation,
                now);
        StatusCondition.set(
                status.conditions,
                "WithinDeadlines",
                status.breaches.isEmpty(),
                status.breaches.isEmpty() ? "OnTime" : "Breached",
                status.breaches.isEmpty()
                        ? "no deadline passed"
                        : String.join("; ", status.breaches),
                generation,
                now);
    }

    private void breach(
            Round r, String level, String deadline, String reachedAt, List<String> left) {
        LakeErasure erasure = r.erasure();
        LakeErasureStatus status = r.status();
        Instant now = r.now();
        String prefix = level + " deadline " + deadline + " passed";
        if (reachedAt != null
                || !now.isAfter(Instant.parse(deadline))
                || status.breaches.stream().anyMatch(b -> b.startsWith(prefix))) {
            return;
        }
        String breach = prefix + " (seen " + now + "); not done: " + left;
        status.breaches.add(breach);
        events.warning(erasure, "ErasureOverdue", breach);
    }

    private static List<String> left(List<LakeErasureStatus.Copy> copies, List<String> layers) {
        return copies.stream()
                .filter(c -> layers.contains(c.layer) && !DONE.equals(c.state))
                .map(
                        c ->
                                c.kind
                                        + " "
                                        + c.name
                                        + (c.location == null ? "" : " at " + c.location)
                                        + " ("
                                        + c.state
                                        + ": "
                                        + c.message
                                        + ")")
                .toList();
    }

    private static List<String> external(LakeErasureStatus status) {
        return status.copies.stream()
                .filter(c -> EXTERNAL.equals(c.state))
                .map(c -> c.kind + " " + c.name)
                .toList();
    }

    @Override
    public DeleteControl cleanup(LakeErasure erasure, Context<LakeErasure> context) {
        metrics.forget(
                KIND
                        + ":"
                        + erasure.getMetadata().getNamespace()
                        + "/"
                        + erasure.getMetadata().getName());
        try {
            identities.remove(erasure, KIND);
        } catch (Exception e) {
            LOG.warn("Cleaning up {} failed; retrying", erasure.getMetadata().getName(), e);
            return DeleteControl.noFinalizerRemoval().rescheduleAfter(POLL);
        }
        return DeleteControl.defaultDelete();
    }
}
