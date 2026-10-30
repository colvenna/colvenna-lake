package com.colvenna.lake.operator.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.catalog.CatalogClient;
import com.colvenna.lake.operator.crd.Governance;
import com.colvenna.lake.operator.crd.LakeIcebergPublication;
import com.colvenna.lake.operator.crd.LakeIcebergPublicationSpec;
import com.colvenna.lake.operator.crd.LakeQuality;
import com.colvenna.lake.operator.crd.LakeQualitySpec;
import com.colvenna.lake.operator.crd.LakeQualityStatus;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceSpec;
import com.colvenna.lake.operator.crd.LakeSourceStatus;
import com.colvenna.lake.operator.crd.LakeTaskSpec;
import com.colvenna.lake.operator.crd.StatusCondition;
import com.colvenna.lake.operator.lake.LakeReader;
import com.colvenna.lake.operator.metrics.Metrics;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A publication from declaration to withdrawal against a Kubernetes mock, a fake colvenna-catalog,
 * a fake Polaris and tables in memory: what may be published, what the catalog is pointed at, the
 * readers' grants, and withdrawal.
 */
@EnableKubernetesMockClient(crud = true, https = false)
class LakeIcebergPublicationReconcilerTest {
    private static final String NS = "colvenna-data";
    private static final String ROOT = "s3://bucket/warehouse/ods_web.db/clicks-1a2b3c4d";
    KubernetesClient kube;
    private MockWebServer catalog;
    private final Map<String, String> options = new ConcurrentHashMap<>();
    private final List<String> columns = new CopyOnWriteArrayList<>(List.of("id", "page"));
    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-10-17T03:00:00Z"));
    private final FakeTables tables = new FakeTables();
    private FakePolaris polaris;
    private LakeIcebergPublicationReconciler reconciler;

    /** Tables in memory: the latest snapshot, and the snapshots the writers wrote metadata for. */
    static final class FakeTables implements IcebergTables {
        long latest = 0;
        final Set<Long> written = new HashSet<>();

        /** Snapshots whose metadata Paimon rebuilt without the buckets not yet compacted. */
        final Set<Long> incomplete = new HashSet<>();

        @Override
        public LakeReader.IcebergFiles files(String database, String table, long snapshot) {
            return incomplete.contains(snapshot)
                    ? new LakeReader.IcebergFiles(Set.of("a"), Set.of("a", "b"))
                    : new LakeReader.IcebergFiles(Set.of("a", "b"), Set.of("a", "b"));
        }

        @Override
        public Optional<Head> head(String database, String table) {
            return latest == 0 ? Optional.empty() : Optional.of(new Head(latest, 1_000L * latest));
        }

        @Override
        public Optional<LakeReader.IcebergMetadata> metadata(
                String database, String table, long snapshot) {
            return written.contains(snapshot)
                    ? Optional.of(
                            new LakeReader.IcebergMetadata(
                                    ROOT + "/metadata/v" + snapshot + ".metadata.json",
                                    "uuid-1",
                                    snapshot))
                    : Optional.empty();
        }
    }

    /** Polaris in memory: the notifications sent, the registered tables, the roles' grants. */
    static final class FakePolaris extends PolarisClient {
        final List<String> notifications = new ArrayList<>();
        final Map<String, Set<TableGrant>> grants = new HashMap<>();
        final Map<String, String> registered = new HashMap<>();
        long last = 0;

        FakePolaris() throws Exception {
            super(
                    URI.create("https://polaris:8181"),
                    "colvenna_open",
                    () -> null,
                    SSLContext.getDefault());
        }

        @Override
        public int update(String ns, String table, long sequence, String uuid, String location) {
            if (sequence <= last) {
                return STALE;
            }
            last = sequence;
            notifications.add("UPDATE " + ns + "." + table + " " + location);
            registered.put(ns + "." + table, location);
            return 204;
        }

        @Override
        public boolean exists(String ns, String table) {
            return registered.containsKey(ns + "." + table);
        }

        @Override
        public void drop(String ns, String table, long sequence) {
            notifications.add("DROP " + ns + "." + table);
            registered.remove(ns + "." + table);
        }

        @Override
        public List<TableGrant> tableGrants(String role) {
            return new ArrayList<>(grants.getOrDefault(role, Set.of()));
        }

        @Override
        public void grant(TableGrant grant) {
            if (!grants.containsKey(grant.role())) {
                throw new PolarisException("no catalog role " + grant.role(), 404);
            }
            grants.get(grant.role()).add(grant);
        }

        @Override
        public void revoke(TableGrant grant) {
            grants.getOrDefault(grant.role(), new HashSet<>()).remove(grant);
        }
    }

    @BeforeEach
    void start() throws Exception {
        catalog = new MockWebServer();
        catalog.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest r) {
                        String call = r.getMethod() + " " + r.getPath();
                        if (call.equals("GET /v1/colvenna/databases/ods_web")) {
                            return new MockResponse()
                                    .setBody(
                                            "{\"options\":{\"colvenna-lake.owner\":\"LakeSource/"
                                                    + NS
                                                    + "/web/u1\"}}");
                        }
                        if (call.equals("GET /v1/colvenna/databases/ods_web/tables/clicks")) {
                            StringBuilder o = new StringBuilder();
                            options.forEach(
                                    (k, v) ->
                                            o.append(o.length() == 0 ? "" : ",")
                                                    .append('"')
                                                    .append(k)
                                                    .append("\":\"")
                                                    .append(v)
                                                    .append('"'));
                            StringBuilder f = new StringBuilder();
                            columns.forEach(
                                    c ->
                                            f.append(f.length() == 0 ? "" : ",")
                                                    .append("{\"name\":\"")
                                                    .append(c)
                                                    .append("\"}"));
                            return new MockResponse()
                                    .setBody(
                                            "{\"schema\":{\"fields\":["
                                                    + f
                                                    + "],\"options\":{"
                                                    + o
                                                    + "}}}");
                        }
                        return new MockResponse().setResponseCode(404).setBody("{}");
                    }
                });
        catalog.start();
        polaris = new FakePolaris();
        polaris.grants.put("web_readers", new HashSet<>());
        polaris.grants.put("other_readers", new HashSet<>());
        options.put("metadata.iceberg.storage", "table-location");
        tables.latest = 3;
        tables.written.addAll(List.of(1L, 2L, 3L));
        source("L2", List.of());
        reconciler =
                new LakeIcebergPublicationReconciler(
                        kube,
                        new CatalogClient(
                                URI.create(catalog.url("/").toString()),
                                () -> "admin",
                                SSLContext.getDefault()),
                        tables,
                        Optional.of(polaris),
                        new Metrics(),
                        now::get);
    }

    @AfterEach
    void stop() throws IOException {
        catalog.shutdown();
    }

    /** The Kafka source writing ods_web, governed at this level and with these categories. */
    private void source(String level, List<String> categories) {
        LakeSource s = kube.resources(LakeSource.class).inNamespace(NS).withName("web").get();
        if (s == null) {
            s = new LakeSource();
            s.setMetadata(new ObjectMetaBuilder().withName("web").withNamespace(NS).build());
            s.setSpec(new LakeSourceSpec());
            s.getSpec().type = "kafka";
            s.getSpec().target = new LakeSourceSpec.Target();
            s.getSpec().target.database = "ods_web";
            s.getSpec().target.table = "clicks";
        }
        s.getSpec().governance = new Governance();
        s.getSpec().governance.owner = "web-team";
        s.getSpec().governance.level = level;
        s.getSpec().governance.categories = new ArrayList<>(categories);
        s.getSpec().governance.retention = "24h";
        s = kube.resource(s).createOr(c -> c.update());
        s.setStatus(new LakeSourceStatus());
        StatusCondition.set(
                s.getStatus().conditions, "Governed", true, "Governed", "", 1, now.get());
        kube.resource(s).updateStatus();
    }

    private LakeIcebergPublication create(String name, String... readers) {
        LakeIcebergPublication p = new LakeIcebergPublication();
        p.setMetadata(
                new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(NS)
                        .withUid(UUID.randomUUID().toString())
                        .withGeneration(1L)
                        .build());
        p.setSpec(new LakeIcebergPublicationSpec());
        p.getSpec().table = new LakeTaskSpec.Table();
        p.getSpec().table.database = "ods_web";
        p.getSpec().table.table = "clicks";
        p.getSpec().columns = new ArrayList<>(List.of("id", "page"));
        p.getSpec().readers = new ArrayList<>(List.of(readers));
        return kube.resource(p).create();
    }

    private LakeIcebergPublication round(LakeIcebergPublication p) {
        LakeIcebergPublication current =
                kube.resources(LakeIcebergPublication.class)
                        .inNamespace(NS)
                        .withName(p.getMetadata().getName())
                        .get();
        reconciler.reconcile(current, null);
        LakeIcebergPublication stored =
                kube.resources(LakeIcebergPublication.class)
                        .inNamespace(NS)
                        .withName(p.getMetadata().getName())
                        .get();
        stored.setStatus(current.getStatus());
        now.set(now.get().plusSeconds(30));
        return kube.resource(stored).update();
    }

    @Test
    void publishesTheLatestSnapshotAndGrantsItsReaders() {
        LakeIcebergPublication p = round(create("clicks", "web_readers"));
        assertThat(p.getStatus().phase).as(p.getStatus().message).isEqualTo("Published");
        assertThat(p.getStatus().published.snapshot).isEqualTo(3L);
        assertThat(polaris.registered)
                .containsEntry("ods_web.clicks", ROOT + "/metadata/v3.metadata.json");
        assertThat(polaris.grants.get("web_readers"))
                .containsExactly(
                        new PolarisClient.TableGrant(
                                "web_readers", "ods_web", "clicks", "TABLE_READ_DATA"));
        assertThat(p.getStatus().readers).containsExactly("web_readers");
        assertThat(p.getStatus().lag.snapshots).isZero();

        // Nothing new: no notification.
        p = round(p);
        assertThat(polaris.notifications).hasSize(1);
        // A new commit: the catalog follows, with a higher sequence.
        long sequence = p.getStatus().sequence;
        tables.latest = 4;
        tables.written.add(4L);
        p = round(p);
        assertThat(p.getStatus().published.snapshot).isEqualTo(4L);
        assertThat(p.getStatus().sequence).isGreaterThan(sequence);
        assertThat(polaris.notifications).hasSize(2);
    }

    @Test
    void keepsWhatItPublishedWhileTheWritersLeftNoMetadata() {
        LakeIcebergPublication p = round(create("clicks", "web_readers"));
        tables.latest = 5;
        p = round(p);
        assertThat(p.getStatus().published.snapshot).isEqualTo(3L);
        assertThat(p.getStatus().conditions)
                .anyMatch(c -> c.type.equals("Published") && c.reason.equals("MetadataMissing"));
        assertThat(p.getStatus().lag.snapshots).isEqualTo(2L);
        tables.written.add(5L);
        p = round(p);
        assertThat(p.getStatus().published.snapshot).isEqualTo(5L);
    }

    @Test
    void keepsWhatItPublishedWhileTheMetadataMissesTheTablesFiles() {
        LakeIcebergPublication p = round(create("clicks", "web_readers"));
        // Rebuilt without its base: the view would miss a bucket, so the catalog keeps 3.
        tables.latest = 5;
        tables.written.add(5L);
        tables.incomplete.add(5L);
        p = round(p);
        assertThat(p.getStatus().published.snapshot).isEqualTo(3L);
        assertThat(polaris.notifications).hasSize(1);
        assertThat(p.getStatus().conditions)
                .anyMatch(c -> c.type.equals("Published") && c.reason.equals("MetadataIncomplete"));
        assertThat(p.getStatus().message).contains("holds 1 data files").contains("1 missing");
        // A full compaction puts the metadata right: published.
        tables.latest = 6;
        tables.written.add(6L);
        p = round(p);
        assertThat(p.getStatus().published.snapshot).isEqualTo(6L);
    }

    @Test
    void aTableWhoseColumnsTheCatalogDoesNotSayIsNotPublished() {
        columns.clear();
        LakeIcebergPublication p = round(create("clicks", "web_readers"));
        assertThat(p.getStatus().phase).isEqualTo("Withheld");
        assertThat(p.getStatus().message).contains("no columns");
        assertThat(polaris.registered).isEmpty();
    }

    @Test
    void aColumnNotReviewedTakesTheTableOutUntilItIsListed() {
        LakeIcebergPublication p = round(create("clicks", "web_readers"));
        assertThat(p.getStatus().phase).isEqualTo("Published");
        // The source added a column: not shown to the readers.
        columns.add("email");
        p = round(p);
        assertThat(p.getStatus().phase).isEqualTo("Withheld");
        assertThat(p.getStatus().conditions)
                .anyMatch(
                        c -> c.type.equals("Publishable") && c.reason.equals("ColumnsUnreviewed"));
        assertThat(p.getStatus().message).contains(": email;");
        assertThat(polaris.registered).isEmpty();
        assertThat(polaris.grants.get("web_readers")).isEmpty();
        // Reviewed: published again.
        p.getSpec().columns.add("email");
        p = kube.resource(p).update();
        p = round(p);
        assertThat(p.getStatus().phase).isEqualTo("Published");
        assertThat(polaris.registered).containsKey("ods_web.clicks");
    }

    @Test
    void aTableTheCatalogLostIsPublishedAgainOrSaidToBeNothing() {
        LakeIcebergPublication p = round(create("clicks", "web_readers"));
        polaris.registered.clear();
        polaris.grants.get("web_readers").clear();
        p = round(p);
        assertThat(polaris.registered)
                .containsEntry("ods_web.clicks", ROOT + "/metadata/v3.metadata.json");
        assertThat(polaris.grants.get("web_readers")).hasSize(1);
        assertThat(p.getStatus().phase).isEqualTo("Published");
        assertThat(kube.v1().events().inNamespace(NS).list().getItems())
                .anyMatch(ev -> ev.getReason().equals("Lost"));
        // Lost while the latest snapshot cannot be published: nothing is, and the status says so.
        tables.latest = 4;
        tables.written.add(4L);
        tables.incomplete.add(4L);
        polaris.registered.clear();
        p = round(p);
        assertThat(polaris.registered).isEmpty();
        assertThat(p.getStatus().phase).isEqualTo("Pending");
        assertThat(p.getStatus().published).isNull();
        assertThat(p.getStatus().message).contains("the catalog keeps nothing");
    }

    @Test
    void restrictedDataIsNeverPublishedAndIsWithdrawnWhenItBecomesSo() {
        LakeIcebergPublication p = round(create("clicks", "web_readers"));
        assertThat(polaris.registered).containsKey("ods_web.clicks");
        source("L3", List.of("personal"));
        p = round(p);
        assertThat(p.getStatus().phase).isEqualTo("Withheld");
        assertThat(p.getStatus().conditions)
                .anyMatch(c -> c.type.equals("Publishable") && c.reason.equals("Restricted"));
        assertThat(polaris.registered).isEmpty();
        assertThat(polaris.grants.get("web_readers")).isEmpty();
        assertThat(polaris.notifications).last().isEqualTo("DROP ods_web.clicks");
        // Published again once it is L2 without personal data.
        source("L2", List.of());
        p = round(p);
        assertThat(p.getStatus().phase).isEqualTo("Published");
    }

    @Test
    void aClosedQualityGateHoldsItBack() {
        LakeQuality q = new LakeQuality();
        q.setMetadata(new ObjectMetaBuilder().withName("clicks").withNamespace(NS).build());
        q.setSpec(new LakeQualitySpec());
        q.getSpec().table = new LakeTaskSpec.Table();
        q.getSpec().table.database = "ods_web";
        q.getSpec().table.table = "clicks";
        q.getSpec().gate = new LakeQualitySpec.Gate();
        q.getSpec().gate.publish = true;
        q = kube.resource(q).create();
        q.setStatus(new LakeQualityStatus());
        q.getStatus().phase = "Ready";
        q.getStatus().gate = new LakeQualityStatus.GateState();
        q.getStatus().gate.open = false;
        q.getStatus().gate.cause = "Failed";
        q.getStatus().gate.message = "check-1 failed volume";
        q = kube.resource(q).updateStatus();
        LakeIcebergPublication p = round(create("clicks", "web_readers"));
        assertThat(p.getStatus().phase).isEqualTo("Withheld");
        assertThat(p.getStatus().message).contains("check-1 failed volume");
        assertThat(polaris.registered).isEmpty();
        q.getStatus().gate.open = true;
        q.getStatus().gate.cause = "Passed";
        kube.resource(q).updateStatus();
        p = round(p);
        assertThat(p.getStatus().phase).isEqualTo("Published");
    }

    @Test
    void aTableWithoutIcebergMetadataOrWithDeletionVectorsIsNotPublished() {
        options.remove("metadata.iceberg.storage");
        LakeIcebergPublication p = round(create("clicks"));
        assertThat(p.getStatus().conditions)
                .anyMatch(
                        c -> c.type.equals("Publishable") && c.reason.equals("NoIcebergMetadata"));
        options.put("metadata.iceberg.storage", "table-location");
        options.put("deletion-vectors.enabled", "true");
        p = round(p);
        assertThat(p.getStatus().conditions)
                .anyMatch(c -> c.type.equals("Publishable") && c.reason.equals("DeletionVectors"));
        assertThat(polaris.notifications).isEmpty();
    }

    @Test
    void readersFollowTheDeclarationAndAMissingRoleIsReported() {
        LakeIcebergPublication p = round(create("clicks", "web_readers", "nobody"));
        assertThat(p.getStatus().readers).containsExactly("web_readers");
        assertThat(p.getStatus().conditions)
                .anyMatch(c -> c.type.equals("ReadersReady") && c.reason.equals("ReaderMissing"));
        p.getSpec().readers = new ArrayList<>(List.of("other_readers"));
        p = kube.resource(p).update();
        p = round(p);
        assertThat(polaris.grants.get("web_readers")).isEmpty();
        assertThat(polaris.grants.get("other_readers")).hasSize(1);
        assertThat(p.getStatus().readers).containsExactly("other_readers");
    }

    @Test
    void deletingItTakesTheTableOutOfTheCatalog() {
        LakeIcebergPublication p = round(create("clicks", "web_readers"));
        reconciler.cleanup(p, null);
        assertThat(polaris.registered).isEmpty();
        assertThat(polaris.grants.get("web_readers")).isEmpty();
    }

    @Test
    void aSecondPublicationOfTheTableIsRefused() throws Exception {
        round(create("clicks", "web_readers"));
        // The mock stamps creation with the wall clock, to the second.
        Thread.sleep(1100);
        LakeIcebergPublication second = create("clicks-again");
        second = round(second);
        assertThat(second.getStatus().phase).isEqualTo("Failed");
    }
}
