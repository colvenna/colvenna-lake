package com.colvenna.lake.operator.iceberg;

import com.colvenna.lake.operator.catalog.CatalogClient;
import com.colvenna.lake.operator.catalog.CatalogException;
import com.colvenna.lake.operator.catalog.Owners;
import com.colvenna.lake.operator.crd.Governance;
import com.colvenna.lake.operator.crd.LakeIcebergPublication;
import com.colvenna.lake.operator.crd.LakeIcebergPublicationSpec;
import com.colvenna.lake.operator.crd.LakeIcebergPublicationStatus;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceStatus;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.crd.StatusCondition;
import com.colvenna.lake.operator.flink.JobObservation;
import com.colvenna.lake.operator.governance.GovernancePolicy;
import com.colvenna.lake.operator.kube.Events;
import com.colvenna.lake.operator.lake.LakeReader;
import com.colvenna.lake.operator.lake.TableOptions;
import com.colvenna.lake.operator.metrics.Metrics;
import com.colvenna.lake.operator.quality.QualityGates;
import com.colvenna.lake.operator.table.TableJobs;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes a lake table to the open layer's Iceberg catalog (LLD "开放层"): while it may be
 * published, points Polaris's EXTERNAL catalog at the Iceberg metadata its writers wrote for the
 * latest snapshot, and grants the declared readers' catalog roles; when it may not, takes it out of
 * the catalog. Writers never reach the catalog: Polaris being down only delays publication.
 */
@ControllerConfiguration(name = "lakeicebergpublication")
public final class LakeIcebergPublicationReconciler
        implements Reconciler<LakeIcebergPublication>, Cleaner<LakeIcebergPublication> {
    private static final Logger LOG =
            LoggerFactory.getLogger(LakeIcebergPublicationReconciler.class);
    public static final String KIND = "lakeicebergpublication";
    static final Duration POLL = Duration.ofSeconds(30);
    static final String READ = "TABLE_READ_DATA";

    private final KubernetesClient kube;
    private final CatalogClient catalog;
    private final IcebergTables tables;
    private final Optional<PolarisClient> polaris;
    private final Events events;
    private final Metrics metrics;
    private final Supplier<Instant> clock;

    public LakeIcebergPublicationReconciler(
            KubernetesClient kube,
            CatalogClient catalog,
            IcebergTables tables,
            Optional<PolarisClient> polaris,
            Metrics metrics,
            Supplier<Instant> clock) {
        this.kube = kube;
        this.catalog = catalog;
        this.tables = tables;
        this.polaris = polaris;
        this.events = new Events(kube);
        this.metrics = metrics;
        this.clock = clock;
    }

    /** What one round knows. */
    private record Round(
            LakeIcebergPublication publication,
            LakeIcebergPublicationSpec spec,
            LakeIcebergPublicationStatus status,
            Instant now) {
        String namespace() {
            return publication.getMetadata().getNamespace();
        }

        String database() {
            return spec.table.database;
        }

        String tableName() {
            return spec.table.table;
        }

        String qualified() {
            return spec.table.qualifiedName();
        }

        boolean condition(String type, boolean ok, String reason, String message) {
            return StatusCondition.set(
                    status.conditions,
                    type,
                    ok,
                    reason,
                    message,
                    Objects.requireNonNullElse(publication.getMetadata().getGeneration(), 0L),
                    now);
        }
    }

    /** Why a table may not be published now, as a condition reason and message. */
    record Refusal(String reason, String message) {}

    @Override
    public UpdateControl<LakeIcebergPublication> reconcile(
            LakeIcebergPublication publication, Context<LakeIcebergPublication> context) {
        LakeIcebergPublicationStatus status =
                publication.getStatus() == null
                        ? new LakeIcebergPublicationStatus()
                        : publication.getStatus();
        publication.setStatus(status);
        Round r = new Round(publication, publication.getSpec(), status, clock.get());
        status.observedGeneration = publication.getMetadata().getGeneration();
        status.table = r.qualified();
        if (polaris.isEmpty()) {
            status.phase = "Pending";
            status.message =
                    "no Iceberg catalog configured: install-polaris.sh, then the chart's"
                            + " polaris.uri";
            r.condition("Published", false, "NoCatalog", status.message);
            return UpdateControl.patchStatus(publication).rescheduleAfter(POLL);
        }
        String conflict = conflict(r);
        if (conflict != null) {
            status.phase = "Failed";
            status.message = conflict;
            r.condition("Publishable", false, "Conflict", conflict);
            return UpdateControl.patchStatus(publication).rescheduleAfter(POLL);
        }
        try {
            Refusal refused = publishable(r);
            if (refused != null) {
                if (r.condition("Publishable", false, refused.reason(), refused.message())) {
                    events.warning(publication, "Withheld", refused.message());
                }
                withdraw(r, refused.message());
                status.phase = "Withheld";
                status.message = refused.message();
            } else {
                r.condition(
                        "Publishable",
                        true,
                        "Publishable",
                        "governed, at most L2, gate open, columns reviewed");
                publish(r);
            }
        } catch (CatalogException | PolarisClient.PolarisException e) {
            unavailable(r, String.valueOf(e.getMessage()));
        } catch (Exception e) {
            // The catalogs say what failed; the table's own files do not.
            unavailable(r, "reading " + r.qualified() + ": " + e.getMessage());
        }
        status.measuredAt = r.now().toString();
        publishMetrics(r);
        return UpdateControl.patchStatus(publication).rescheduleAfter(POLL);
    }

    private static void unavailable(Round r, String why) {
        String message = JobObservation.abbreviate(why);
        r.condition("Published", false, "Unavailable", message);
        r.status().message = message;
    }

    /** Another publication of this namespace on the same table that came first, or null. */
    private String conflict(Round r) {
        Comparator<LakeIcebergPublication> order =
                Comparator.comparing(
                                (LakeIcebergPublication p) ->
                                        p.getMetadata().getCreationTimestamp())
                        .thenComparing(p -> p.getMetadata().getUid());
        for (LakeIcebergPublication other :
                kube.resources(LakeIcebergPublication.class)
                        .inNamespace(r.namespace())
                        .list()
                        .getItems()) {
            if (!other.getMetadata().getUid().equals(r.publication().getMetadata().getUid())
                    && other.getSpec() != null
                    && other.getSpec().table != null
                    && r.qualified().equals(other.getSpec().table.qualifiedName())
                    && order.compare(other, r.publication()) < 0) {
                return "LakeIcebergPublication "
                        + other.getMetadata().getName()
                        + " came first for "
                        + r.qualified();
            }
        }
        return null;
    }

    /** Why the table may not be published now, or null (LLD "能否发布"). */
    Refusal publishable(Round r) {
        Optional<JsonNode> table = catalog.table(r.database(), r.tableName());
        if (table.isEmpty()) {
            return new Refusal("TableMissing", r.qualified() + " does not exist");
        }
        JsonNode options = table.get().path("schema").path("options");
        String tableOwner = options.path(Owners.OPTION).asText(null);
        String databaseOwner =
                catalog.databaseOptions(r.database()).map(o -> o.get(Owners.OPTION)).orElse(null);
        if (!Owners.isSourceOf(databaseOwner, r.namespace())
                && !Owners.isTableOf(tableOwner, r.namespace())) {
            return new Refusal(
                    "NotManaged",
                    r.qualified()
                            + " is neither an ODS table of a LakeSource nor a LakeTable target in"
                            + " namespace "
                            + r.namespace());
        }
        List<LakeSource> sources =
                kube.resources(LakeSource.class).inNamespace(r.namespace()).list().getItems();
        List<LakeTable> layered =
                kube.resources(LakeTable.class).inNamespace(r.namespace()).list().getItems();
        String ungoverned = ungoverned(r, sources, layered);
        if (ungoverned != null) {
            return new Refusal("NotGoverned", ungoverned);
        }
        Governance g = GovernancePolicy.Declared.of(sources, layered).governanceOf(r.qualified());
        if (Governance.treatedAsPersonal(g) || Governance.level(g) > 2) {
            return new Refusal(
                    "Restricted",
                    r.qualified()
                            + " is "
                            + (g == null || g.level == null ? "unclassified" : g.level)
                            + (Governance.treatedAsPersonal(g) ? ", personal data" : "")
                            + ": only L1 and L2 tables without personal data are published to"
                            + " Iceberg readers, who keep none of the platform's user policies");
        }
        QualityGates.Hold held = QualityGates.holds(kube, r.namespace()).get(r.qualified());
        if (held != null) {
            return new Refusal("QualityGate", held.describe());
        }
        Map<String, String> opts = new LinkedHashMap<>();
        options.fields().forEachRemaining(e -> opts.put(e.getKey(), e.getValue().asText()));
        if (!TableOptions.writesIceberg(opts)) {
            return new Refusal(
                    "NoIcebergMetadata",
                    r.qualified()
                            + "'s writers write no Iceberg metadata: declare its tableOptions with "
                            + TableOptions.ICEBERG_STORAGE
                            + ": "
                            + TableOptions.ICEBERG_TABLE_LOCATION
                            + " when the table is made");
        }
        if ("true".equals(opts.get("deletion-vectors.enabled"))) {
            return new Refusal(
                    "DeletionVectors",
                    r.qualified()
                            + " has deletion vectors: Iceberg v2 cannot express them, and Paimon"
                            + " 2.0.0 writes no correct v3 metadata");
        }
        // Readers get the whole table: a column nobody reviewed (one a CDC source added) is not
        // shown to them (HLD 12.5).
        JsonNode fields = table.get().path("schema").path("fields");
        if (fields.isEmpty()) {
            return new Refusal(
                    "ColumnsUnreviewed",
                    "colvenna-catalog returned no columns for " + r.qualified());
        }
        Set<String> unreviewed = new TreeSet<>();
        for (JsonNode f : fields) {
            if (!r.spec().columns.contains(f.path("name").asText())) {
                unreviewed.add(f.path("name").asText());
            }
        }
        if (!unreviewed.isEmpty()) {
            return new Refusal(
                    "ColumnsUnreviewed",
                    r.qualified()
                            + " has columns its publication does not list: "
                            + String.join(", ", unreviewed)
                            + "; list them in spec.columns once reviewed for these readers");
        }
        return null;
    }

    /** Why the declaration of the table does not govern it, or null when it does. */
    private static String ungoverned(Round r, List<LakeSource> sources, List<LakeTable> layered) {
        for (LakeTable t : layered) {
            if (t.getSpec() != null
                    && t.getSpec().target != null
                    && (r.qualified().equals(TableJobs.target(t.getSpec()))
                            || r.qualified().equals(TableJobs.quarantineQualified(t.getSpec())))) {
                return t.getStatus() != null
                                && StatusCondition.isTrue(t.getStatus().conditions, "Governed")
                        ? null
                        : "LakeTable " + t.getMetadata().getName() + " does not govern it";
            }
        }
        for (LakeSource s : sources) {
            if (s.getSpec() == null
                    || s.getSpec().target == null
                    || !r.database().equals(s.getSpec().target.database)
                    || s.getStatus() == null) {
                continue;
            }
            if ("kafka".equals(s.getSpec().type)) {
                return StatusCondition.isTrue(s.getStatus().conditions, "Governed")
                        ? null
                        : "LakeSource " + s.getMetadata().getName() + " does not govern it";
            }
            for (LakeSourceStatus.SourceTable t : s.getStatus().tables) {
                if (r.tableName().equals(t.target)) {
                    return t.governance != null && Boolean.TRUE.equals(t.governance.governed)
                            ? null
                            : "LakeSource " + s.getMetadata().getName() + " does not govern it";
                }
            }
        }
        return "no declaration of this namespace governs " + r.qualified();
    }

    /** How many of the first set's names the second lacks. */
    private static long missing(Set<String> of, Set<String> in) {
        return of.stream().filter(name -> !in.contains(name)).count();
    }

    /**
     * Above the last sequence, and above any a lost status sent: the catalog refuses anything not
     * newer, so a late notification cannot set it back.
     */
    private static long nextSequence(Round r) {
        Long last = r.status().sequence;
        return Math.max(last == null ? 0 : last + 1, r.now().toEpochMilli());
    }

    /**
     * The latest snapshot is not published, for this reason: the catalog keeps what it has, if
     * anything.
     */
    private void keep(Round r, String reason, String why, IcebergTables.Head head) {
        LakeIcebergPublicationStatus status = r.status();
        String message =
                why
                        + "; the catalog keeps "
                        + (status.published == null
                                ? "nothing"
                                : "snapshot " + status.published.snapshot);
        if (r.condition("Published", status.published != null, reason, message)) {
            events.warning(r.publication(), reason, message);
        }
        status.phase = status.published == null ? "Pending" : "Published";
        status.message = message;
        lag(r, head);
    }

    /**
     * Points the catalog at the latest snapshot's Iceberg metadata, when that is newer than what it
     * points at, and keeps the readers' grants.
     */
    private void publish(Round r) throws Exception {
        LakeIcebergPublicationStatus status = r.status();
        Optional<IcebergTables.Head> head = tables.head(r.database(), r.tableName());
        if (head.isEmpty()) {
            status.phase = "Pending";
            status.message = r.qualified() + " has no snapshot yet";
            r.condition("Published", false, "NoSnapshot", status.message);
            return;
        }
        long latest = head.get().snapshot();
        LakeIcebergPublicationStatus.Published published = status.published;
        if (published != null && !polaris.get().exists(r.database(), r.tableName())) {
            // Taken out behind the operator's back (or the catalog's store was lost): published
            // again from the latest snapshot, or said to be nothing until it can be.
            events.warning(
                    r.publication(),
                    "Lost",
                    "the Iceberg catalog no longer has "
                            + r.qualified()
                            + " (snapshot "
                            + published.snapshot
                            + "); publishing it again");
            status.published = null;
            published = null;
        }
        if (published == null || published.snapshot == null || published.snapshot != latest) {
            Optional<LakeReader.IcebergMetadata> metadata =
                    tables.metadata(r.database(), r.tableName(), latest);
            if (metadata.isEmpty() || metadata.get().currentSnapshot() != latest) {
                keep(
                        r,
                        "MetadataMissing",
                        "the writers left no Iceberg metadata for snapshot "
                                + latest
                                + (metadata.isPresent()
                                        ? " (its file makes snapshot "
                                                + metadata.get().currentSnapshot()
                                                + " current)"
                                        : ""),
                        head.get());
                return;
            }
            // The metadata must show what the table holds: Paimon rebuilds it without its base
            // after a commit that wrote none, and a primary-key table's view then misses the
            // buckets with changes not yet compacted. The last good one stays published.
            LakeReader.IcebergFiles files = tables.files(r.database(), r.tableName(), latest);
            if (!files.complete()) {
                keep(
                        r,
                        "MetadataIncomplete",
                        "the writers' Iceberg metadata for snapshot "
                                + latest
                                + " holds "
                                + files.held().size()
                                + " data files where the table's files to show (a primary-key"
                                + " table's fully compacted ones) are "
                                + files.expected().size()
                                + " ("
                                + missing(files.expected(), files.held())
                                + " missing, "
                                + missing(files.held(), files.expected())
                                + " not the table's; a full compaction rewrites both)",
                        head.get());
                return;
            }
            long sequence = nextSequence(r);
            int answer =
                    polaris.get()
                            .update(
                                    r.database(),
                                    r.tableName(),
                                    sequence,
                                    metadata.get().uuid(),
                                    metadata.get().file());
            status.sequence = sequence;
            if (answer == PolarisClient.STALE) {
                throw new PolarisClient.PolarisException(
                        "the catalog has a newer notification than " + sequence + "; retrying",
                        answer);
            }
            LakeIcebergPublicationStatus.Published p = new LakeIcebergPublicationStatus.Published();
            p.snapshot = latest;
            p.metadataLocation = metadata.get().file();
            p.committedAt = Instant.ofEpochMilli(head.get().committedMillis()).toString();
            p.publishedAt = r.now().toString();
            status.published = p;
            events.normal(
                    r.publication(),
                    "Published",
                    r.qualified()
                            + " snapshot "
                            + latest
                            + " published as "
                            + metadata.get().file());
        }
        grants(r);
        status.phase = "Published";
        status.message =
                "the catalog points at snapshot "
                        + status.published.snapshot
                        + "; readers "
                        + status.readers;
        r.condition("Published", true, "Published", status.message);
        lag(r, head.get());
    }

    private static void lag(Round r, IcebergTables.Head head) {
        long latest = head.snapshot();
        LakeIcebergPublicationStatus.Published p = r.status().published;
        if (p == null) {
            r.status().lag = null;
            return;
        }
        LakeIcebergPublicationStatus.Lag lag = new LakeIcebergPublicationStatus.Lag();
        lag.snapshots = latest - p.snapshot;
        // How long the newest unpublished snapshot has waited.
        lag.seconds =
                p.snapshot == latest
                        ? 0L
                        : Math.max(0, (r.now().toEpochMilli() - head.committedMillis()) / 1000);
        r.status().lag = lag;
    }

    /** The declared readers' catalog roles hold TABLE_READ_DATA on the table, and no others. */
    private void grants(Round r) {
        Set<String> want = new TreeSet<>(r.spec().readers);
        Set<String> had = new TreeSet<>(r.status().readers);
        had.addAll(want);
        List<String> granted = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        for (String role : had) {
            PolarisClient.TableGrant grant =
                    new PolarisClient.TableGrant(role, r.database(), r.tableName(), READ);
            boolean held = polaris.get().tableGrants(role).contains(grant);
            if (want.contains(role)) {
                if (!held) {
                    try {
                        polaris.get().grant(grant);
                    } catch (PolarisClient.PolarisException e) {
                        if (e.notFound()) {
                            absent.add(role);
                            continue;
                        }
                        throw e;
                    }
                }
                granted.add(role);
            } else if (held) {
                polaris.get().revoke(grant);
            }
        }
        r.status().readers = granted;
        if (!absent.isEmpty()) {
            r.condition(
                    "ReadersReady",
                    false,
                    "ReaderMissing",
                    "the Iceberg catalog has no catalog role " + absent);
        } else {
            r.condition("ReadersReady", true, "Ready", "granted " + granted);
        }
    }

    /** Takes the table out of the catalog and revokes the readers' grants, if it is there. */
    private void withdraw(Round r, String why) {
        LakeIcebergPublicationStatus status = r.status();
        if (status.published == null && status.readers.isEmpty()) {
            return;
        }
        long sequence = nextSequence(r);
        polaris.get().drop(r.database(), r.tableName(), sequence);
        status.sequence = sequence;
        for (String role : status.readers) {
            polaris.get()
                    .revoke(new PolarisClient.TableGrant(role, r.database(), r.tableName(), READ));
        }
        status.readers = new ArrayList<>();
        status.published = null;
        status.lag = null;
        r.condition("Published", false, "Withdrawn", why);
        events.normal(
                r.publication(), "Withdrawn", r.qualified() + " taken out of the catalog: " + why);
    }

    private void publishMetrics(Round r) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("namespace", r.namespace());
        labels.put("lakeicebergpublication", r.publication().getMetadata().getName());
        labels.put("table", r.qualified());
        List<Metrics.Sample> samples = new ArrayList<>();
        samples.add(
                new Metrics.Sample(
                        "colvenna_iceberg_published",
                        "1 while the table is published to the Iceberg catalog",
                        labels,
                        "Published".equals(r.status().phase) ? 1 : 0));
        if (r.status().lag != null && r.status().lag.snapshots != null) {
            samples.add(
                    new Metrics.Sample(
                            "colvenna_iceberg_lag_snapshots",
                            "Paimon snapshots newer than the published one",
                            labels,
                            r.status().lag.snapshots));
            samples.add(
                    new Metrics.Sample(
                            "colvenna_iceberg_lag_seconds",
                            "How long Paimon's latest snapshot has waited to be published",
                            labels,
                            r.status().lag.seconds == null ? 0 : r.status().lag.seconds));
        }
        metrics.publish(key(r.publication()), samples);
    }

    private static String key(LakeIcebergPublication p) {
        return KIND + ":" + p.getMetadata().getNamespace() + "/" + p.getMetadata().getName();
    }

    @Override
    public DeleteControl cleanup(
            LakeIcebergPublication publication, Context<LakeIcebergPublication> context) {
        LakeIcebergPublicationStatus status = publication.getStatus();
        if (status != null && polaris.isPresent() && !"Failed".equals(status.phase)) {
            try {
                withdraw(
                        new Round(publication, publication.getSpec(), status, clock.get()),
                        "the publication was deleted");
            } catch (Exception e) {
                LOG.warn("Withdrawing {} failed; retrying", key(publication), e);
                return DeleteControl.noFinalizerRemoval().rescheduleAfter(POLL);
            }
        }
        metrics.forget(key(publication));
        return DeleteControl.defaultDelete();
    }

    @Override
    public ErrorStatusUpdateControl<LakeIcebergPublication> updateErrorStatus(
            LakeIcebergPublication publication,
            Context<LakeIcebergPublication> context,
            Exception e) {
        LakeIcebergPublicationStatus status =
                publication.getStatus() == null
                        ? new LakeIcebergPublicationStatus()
                        : publication.getStatus();
        status.message =
                "reconcile failed: " + JobObservation.abbreviate(String.valueOf(e.getMessage()));
        publication.setStatus(status);
        return ErrorStatusUpdateControl.patchStatus(publication);
    }
}
