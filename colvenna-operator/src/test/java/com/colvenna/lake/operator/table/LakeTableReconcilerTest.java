package com.colvenna.lake.operator.table;

import static com.colvenna.lake.operator.table.UpgradeFixture.BEFORE;
import static com.colvenna.lake.operator.table.UpgradeFixture.FILTERED;
import static com.colvenna.lake.operator.table.UpgradeFixture.OVERDUE;
import static com.colvenna.lake.operator.table.UpgradeFixture.QUERY;
import static com.colvenna.lake.operator.table.UpgradeFixture.T0;
import static com.colvenna.lake.operator.table.UpgradeFixture.TAKEN;
import static com.colvenna.lake.operator.table.UpgradeFixture.checkpoints;
import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.catalog.CatalogClient;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.crd.StatusCondition;
import com.colvenna.lake.operator.flink.FlinkJobs;
import com.colvenna.lake.operator.metrics.Metrics;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.junit.jupiter.api.Test;

/**
 * The reconciler's part of an upgrade, a round's apply and observe against a Kubernetes mock and a
 * JobManager the test answers for; the catalog and the lake are not reached, the target counts as
 * unreadable.
 */
@EnableKubernetesMockClient(crud = true, https = false)
class LakeTableReconcilerTest {
    KubernetesClient kube;

    /** A round's context: no deployment in the informer cache, so the reconciler asks the API. */
    @SuppressWarnings("unchecked")
    static Context<LakeTable> context() {
        return (Context<LakeTable>)
                Proxy.newProxyInstance(
                        Context.class.getClassLoader(),
                        new Class<?>[] {Context.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getSecondaryResources")) {
                                return Set.of();
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    FlinkDeployment deployment(FlinkDeployment fd) {
        return kube.resources(FlinkDeployment.class)
                .inNamespace(fd.getMetadata().getNamespace())
                .withName(fd.getMetadata().getName())
                .get();
    }

    static String reason(LakeTable table, String type) {
        return table.getStatus().conditions.stream()
                .filter(c -> c.type.equals(type))
                .map(c -> c.reason)
                .findFirst()
                .orElse(null);
    }

    @Test
    void aRollbackAChangedDeclarationWritesIsInFlightInItsOwnRound() throws Exception {
        UpgradeFixture f = new UpgradeFixture();
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        LakeTableReconciler reconciler =
                new LakeTableReconciler(
                        LakeTableTest.SETTINGS,
                        kube,
                        new CatalogClient(
                                URI.create("https://colvenna-catalog:8181"),
                                () -> "admin",
                                SSLContext.getDefault()),
                        null,
                        f.jobManager,
                        new Metrics(),
                        now::get);
        FlinkDeployment fd = f.running("j-old");
        String old = FlinkJobs.hashOf(fd);
        kube.resource(fd).create();
        f.jobManager.checkpoints.put("j-old", checkpoints(1, 12L, 11L, BEFORE));

        // The filter is declared and upgrades the job; the Flink operator stops the old one with
        // a savepoint, and the new one never runs.
        f.declared(FILTERED);
        assertThat(reconciler.apply(new LakeTableReconciler.Round(f.table, T0), context()))
                .isNull();
        FlinkDeployment upgraded = deployment(fd);
        f.newJobFails(upgraded, "j-new");
        kube.resource(upgraded).update();

        // Overdue, and the target cannot be read: refused, and not in flight.
        LakeTableReconciler.Round refusedRound = new LakeTableReconciler.Round(f.table, OVERDUE);
        assertThat(reconciler.apply(refusedRound, context())).isNull();
        assertThat(reconciler.observe(refusedRound)).isFalse();
        assertThat(reason(f.table, "JobRunning")).isEqualTo("UpgradeFailed");

        // Reverted, and the new job's JobManager now tells it restored from the upgrade's
        // savepoint and completed nothing: rolled back in this round, which stays in flight
        // (polled again soon) and says so, though the job does not run yet.
        f.jobManager.checkpoints.put("j-new", checkpoints(0, null, 13L, TAKEN));
        f.declared(QUERY);
        Instant later = OVERDUE.plusSeconds(60);
        LakeTableReconciler.Round round = new LakeTableReconciler.Round(f.table, later);
        assertThat(reconciler.apply(round, context())).isNull();
        assertThat(reconciler.observe(round)).isTrue();
        assertThat(reason(f.table, "JobRunning")).isEqualTo("RollingBack");
        assertThat(reason(f.table, "SpecApplied")).isEqualTo("RolledBack");
        assertThat(StatusCondition.isTrue(f.table.getStatus().conditions, "JobRunning")).isFalse();
        FlinkDeployment back = deployment(fd);
        assertThat(back.getSpec().getJob().getInitialSavepointPath()).isEqualTo(TAKEN);
        assertThat(back.getSpec().getJob().getSavepointRedeployNonce()).isEqualTo(1L);
        assertThat(FlinkJobs.hashOf(back)).isEqualTo(old);
        assertThat(
                        kube.v1()
                                .events()
                                .inNamespace(fd.getMetadata().getNamespace())
                                .list()
                                .getItems())
                .extracting(Event::getReason)
                .contains("UpgradeFailed", "UpgradeRolledBack");
    }
}
