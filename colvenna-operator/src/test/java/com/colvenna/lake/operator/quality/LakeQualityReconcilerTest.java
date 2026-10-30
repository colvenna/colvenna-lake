package com.colvenna.lake.operator.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.catalog.CatalogClient;
import com.colvenna.lake.operator.crd.LakeQuality;
import com.colvenna.lake.operator.crd.LakeQualitySpec;
import com.colvenna.lake.operator.crd.LakeTaskSpec;
import com.colvenna.lake.operator.crd.LakeWaiver;
import com.colvenna.lake.operator.crd.LakeWaiverSpec;
import com.colvenna.lake.operator.crd.StatusCondition;
import com.colvenna.lake.operator.metrics.Metrics;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A table's gate from its checks' results, against a Kubernetes mock, a fake colvenna-catalog and a
 * store in memory: decisions, waivers, tags and what publishers are held back from.
 */
@EnableKubernetesMockClient(crud = true, https = false)
class LakeQualityReconcilerTest {
    private static final String NS = "colvenna-data";
    private static final List<String> RULES = List.of("user-present", "volume");
    KubernetesClient kube;
    private MockWebServer catalog;

    /**
     * The test's clock, an hour behind the wall clock: the Kubernetes mock stamps a waiver's
     * creation with the wall clock, after the checks the tests write.
     */
    private final AtomicReference<Instant> now =
            new AtomicReference<>(
                    Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS));

    private MemoryQualityStore store;
    private LakeQualityReconciler reconciler;

    @BeforeEach
    void start() throws Exception {
        catalog = new MockWebServer();
        catalog.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest r) {
                        String call = r.getMethod() + " " + r.getPath();
                        if (call.equals("GET /v1/colvenna/databases/ods")) {
                            return new MockResponse()
                                    .setBody(
                                            "{\"options\":{\"colvenna-lake.owner\":\"LakeSource/"
                                                    + NS
                                                    + "/web/u1\"}}");
                        }
                        if (call.equals("GET /v1/colvenna/databases/ods/tables/clicks")) {
                            return new MockResponse().setBody("{\"schema\":{\"options\":{}}}");
                        }
                        return new MockResponse().setResponseCode(404).setBody("{}");
                    }
                });
        catalog.start();
        store = new MemoryQualityStore(now::get);
        store.columns.put(
                "ods.clicks",
                new QualityStore.Columns(
                        List.of(
                                new DataField(0, "event_id", DataTypes.STRING()),
                                new DataField(1, "user_id", DataTypes.BIGINT()),
                                new DataField(2, "dt", DataTypes.STRING())),
                        List.of("dt")));
        reconciler =
                new LakeQualityReconciler(
                        kube,
                        new CatalogClient(
                                URI.create(catalog.url("/").toString()),
                                () -> "admin",
                                SSLContext.getDefault()),
                        store,
                        new Metrics(),
                        now::get);
    }

    @AfterEach
    void stop() throws IOException {
        catalog.shutdown();
    }

    private LakeQuality create(String name, boolean publish) {
        LakeQuality q = new LakeQuality();
        q.setMetadata(
                new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(NS)
                        .withUid(UUID.randomUUID().toString())
                        .withGeneration(1L)
                        .withCreationTimestamp(now.get().toString())
                        .build());
        LakeQualitySpec spec = new LakeQualitySpec();
        spec.table = new LakeTaskSpec.Table();
        spec.table.database = "ods";
        spec.table.table = "clicks";
        LakeQualitySpec.Rule present = new LakeQualitySpec.Rule();
        present.name = "user-present";
        present.notNull = new LakeQualitySpec.NotNull();
        present.notNull.column = "user_id";
        LakeQualitySpec.Rule volume = new LakeQualitySpec.Rule();
        volume.name = "volume";
        volume.rowCount = new LakeQualitySpec.RowCount();
        volume.rowCount.min = 1L;
        spec.rules = List.of(present, volume);
        spec.gate = new LakeQualitySpec.Gate();
        spec.gate.publish = publish;
        q.setSpec(spec);
        now.set(now.get().plusSeconds(1));
        return kube.resource(q).create();
    }

    private LakeQuality round(LakeQuality q) {
        LakeQuality current =
                kube.resources(LakeQuality.class)
                        .inNamespace(NS)
                        .withName(q.getMetadata().getName())
                        .get();
        reconciler.reconcile(current, null);
        LakeQuality stored =
                kube.resources(LakeQuality.class)
                        .inNamespace(NS)
                        .withName(q.getMetadata().getName())
                        .get();
        stored.setStatus(current.getStatus());
        return kube.resource(stored).update();
    }

    private String version(LakeQuality q) {
        return QualityRules.version(q.getSpec().rules);
    }

    private LakeWaiver waive(String name, String check, String approvedBy, String... rules) {
        LakeWaiver w = new LakeWaiver();
        ObjectMetaBuilder meta =
                new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(NS)
                        .withUid(UUID.randomUUID().toString())
                        .withCreationTimestamp(now.get().toString());
        if (approvedBy != null) {
            meta.withAnnotations(Map.of(LakeWaiver.APPROVED_BY, approvedBy));
        }
        w.setMetadata(meta.build());
        w.setSpec(new LakeWaiverSpec());
        w.getSpec().check = check;
        w.getSpec().rules = List.of(rules);
        w.getSpec().reason = "accepted by the owner";
        return kube.resource(w).create();
    }

    private LakeWaiver waiver(String name) {
        return kube.resources(LakeWaiver.class).inNamespace(NS).withName(name).get();
    }

    @Test
    void aGateIsClosedUntilACheckPassesAndEachChangeIsRecorded() {
        LakeQuality q = round(create("clicks", true));
        assertThat(q.getStatus().phase).isEqualTo("Ready");
        assertThat(q.getStatus().store).isEqualTo("quality_colvenna_data");
        assertThat(q.getStatus().ruleVersion).isEqualTo(version(q));
        assertThat(q.getStatus().gate.open).isFalse();
        assertThat(q.getStatus().gate.cause).isEqualTo("NoCheck");
        assertThat(QualityGates.holds(List.of(q))).containsKey("ods.clicks");
        assertThat(store.decisions)
                .singleElement()
                .satisfies(
                        d -> {
                            assertThat(d.get("cause")).isEqualTo("NoCheck");
                            assertThat(d.get("open")).isEqualTo(false);
                        });

        store.check("u1", "check-1", "ods.clicks", 5, now.get(), "", version(q), RULES);
        q = round(q);
        assertThat(q.getStatus().gate.open).isTrue();
        assertThat(q.getStatus().gate.cause).isEqualTo("Passed");
        assertThat(q.getStatus().gate.check).contains("check-1").contains("snapshot 5");
        assertThat(StatusCondition.isTrue(q.getStatus().conditions, "GateOpen")).isTrue();
        assertThat(QualityGates.holds(List.of(q))).isEmpty();
        assertThat(store.decisions).hasSize(2);
        assertThat(store.decisions.get(1).get("check_uid")).isEqualTo("u1");

        // No change, no decision.
        q = round(q);
        assertThat(store.decisions).hasSize(2);
    }

    @Test
    void aFailedCheckClosesItTagsItsSnapshotAndAWaiverReopensIt() {
        LakeQuality q = round(create("clicks", true));
        store.check("u1", "check-1", "ods.clicks", 5, now.get(), "", version(q), RULES);
        now.set(now.get().plusSeconds(60));
        store.check(
                "u2aaaaaaaa",
                "check-2",
                "ods.clicks",
                9,
                now.get(),
                "",
                version(q),
                RULES,
                "volume");
        q = round(q);
        assertThat(q.getStatus().gate.open).isFalse();
        assertThat(q.getStatus().gate.cause).isEqualTo("Failed");
        assertThat(q.getStatus().gate.message).contains("check-2").contains("volume");
        // The failed check's snapshot is kept: no declaration classifies the table, so as personal
        // data (temporary L4), a day at most.
        assertThat(store.tags).containsKey("colvenna-check-u2aaaaaa");
        assertThat(store.tags.get("colvenna-check-u2aaaaaa").snapshot()).isEqualTo(9);
        assertThat(store.kept.get("colvenna-check-u2aaaaaa")).isEqualTo(24L);
        assertThat(q.getStatus().checks)
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.task).isEqualTo("check-2");
                            assertThat(c.passed).isFalse();
                            assertThat(c.tag).isEqualTo("colvenna-check-u2aaaaaa");
                        });

        // A waiver naming a rule the check passed is refused; one without its approver too.
        now.set(now.get().plusSeconds(60));
        waive("too-wide", "check-2", "alice", "volume", "user-present");
        waive("anonymous", "check-2", null, "volume");
        q = round(q);
        assertThat(waiver("too-wide").getStatus().phase).isEqualTo("Rejected");
        assertThat(waiver("too-wide").getStatus().message).contains("user-present");
        assertThat(waiver("anonymous").getStatus().phase).isEqualTo("Rejected");
        assertThat(q.getStatus().gate.open).isFalse();

        waive("late-volume", "check-2", "alice", "volume");
        q = round(q);
        assertThat(waiver("late-volume").getStatus().phase).isEqualTo("Accepted");
        assertThat(waiver("late-volume").getStatus().checkUid).isEqualTo("u2aaaaaaaa");
        assertThat(waiver("late-volume").getStatus().approvedBy).isEqualTo("alice");
        assertThat(q.getStatus().gate.open).isTrue();
        assertThat(q.getStatus().gate.cause).isEqualTo("Waived");
        assertThat(q.getStatus().gate.waivers).containsExactly("late-volume");
        Map<String, Object> decision = store.decisions.get(store.decisions.size() - 1);
        assertThat(decision.get("cause")).isEqualTo("Waived");
        assertThat(decision.get("waivers").toString())
                .contains("late-volume")
                .contains("approved by alice")
                .contains("accepted by the owner");

        // A newer passing check replaces the failed one: the waiver no longer matters.
        now.set(now.get().plusSeconds(60));
        store.check("u3", "check-3", "ods.clicks", 12, now.get(), "", version(q), RULES);
        q = round(q);
        assertThat(q.getStatus().gate.cause).isEqualTo("Passed");
        assertThat(waiver("late-volume").getStatus().phase).isEqualTo("Superseded");

        // The tag goes once its day is over.
        now.set(now.get().plus(Duration.ofDays(2)));
        round(q);
        assertThat(store.tags).isEmpty();
    }

    @Test
    void aWaiverMadeBeforeItsCheckFailedAcceptsNothing() {
        LakeQuality q = round(create("clicks", true));
        waive("early", "check-1", "alice", "volume");
        now.set(Instant.now().plusSeconds(60));
        store.check("u1", "check-1", "ods.clicks", 5, now.get(), "", version(q), RULES, "volume");
        q = round(q);
        assertThat(waiver("early").getStatus().phase).isEqualTo("Rejected");
        assertThat(q.getStatus().gate.open).isFalse();
    }

    @Test
    void changedRulesNeedACheckOfTheirOwn() {
        LakeQuality q = round(create("clicks", true));
        store.check("u1", "check-1", "ods.clicks", 5, now.get(), "", version(q), RULES);
        q = round(q);
        assertThat(q.getStatus().gate.open).isTrue();
        q.getSpec().rules.get(1).rowCount.min = 100L;
        q = kube.resource(q).update();
        q = round(q);
        assertThat(q.getStatus().ruleVersion).isEqualTo(version(q));
        assertThat(q.getStatus().gate.open).isFalse();
        assertThat(q.getStatus().gate.cause).isEqualTo("NoCheck");
    }

    @Test
    void invalidRulesOrAnUnreadableStoreCloseIt() {
        LakeQuality q = create("clicks", true);
        q.getSpec().rules.get(0).notNull.column = "nope";
        q = round(kube.resource(q).update());
        assertThat(q.getStatus().phase).isEqualTo("Degraded");
        assertThat(q.getStatus().gate.cause).isEqualTo("NotReady");
        assertThat(QualityGates.holds(List.of(q))).containsKey("ods.clicks");

        q.getSpec().rules.get(0).notNull.column = "user_id";
        q = kube.resource(q).update();
        store.check("u1", "check-1", "ods.clicks", 5, now.get(), "", version(q), RULES);
        q = round(q);
        assertThat(q.getStatus().gate.open).isTrue();
        store.failing = true;
        q = round(q);
        assertThat(q.getStatus().gate.cause).isEqualTo("NotReady");
        assertThat(q.getStatus().gate.message).contains("store unavailable");
        assertThat(QualityGates.holds(List.of(q))).containsKey("ods.clicks");
        // The decision waits in the status for the store.
        assertThat(q.getStatus().decisions.get(q.getStatus().decisions.size() - 1).recorded)
                .isFalse();
        store.failing = false;
        q = round(q);
        assertThat(q.getStatus().gate.open).isTrue();
        assertThat(q.getStatus().decisions).allMatch(d -> Boolean.TRUE.equals(d.recorded));
        assertThat(store.decisions)
                .extracting(d -> d.get("cause"))
                .containsExactly("NotReady", "Passed", "NotReady", "Passed");
    }

    @Test
    void aSecondLakeQualityOfATableIsNoGate() {
        LakeQuality first = round(create("clicks", true));
        LakeQuality second = round(create("clicks-again", true));
        assertThat(second.getStatus().phase).isEqualTo("Failed");
        assertThat(second.getStatus().gate).isNull();
        store.check("u1", "check-1", "ods.clicks", 5, now.get(), "", version(first), RULES);
        first = round(first);
        assertThat(QualityGates.holds(List.of(first, second))).isEmpty();
        assertThat(QualityGates.gateOf(List.of(first, second), "ods.clicks"))
                .get()
                .extracting(q -> q.getMetadata().getName())
                .isEqualTo("clicks");
    }

    @Test
    void withoutPublishItRecordsButHoldsNothing() {
        LakeQuality q = round(create("clicks", false));
        assertThat(q.getStatus().gate.open).isFalse();
        assertThat(QualityGates.holds(List.of(q))).isEmpty();
    }

    @Test
    void deletingItRecordsTheRemovalAndDropsItsTags() {
        LakeQuality q = round(create("clicks", true));
        store.check(
                "u1aaaaaaaa",
                "check-1",
                "ods.clicks",
                5,
                now.get(),
                "",
                version(q),
                RULES,
                "volume");
        q = round(q);
        assertThat(store.tags).isNotEmpty();
        reconciler.cleanup(q, null);
        assertThat(store.tags).isEmpty();
        assertThat(store.decisions.get(store.decisions.size() - 1).get("cause"))
                .isEqualTo("Removed");
    }
}
