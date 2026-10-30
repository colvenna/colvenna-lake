package com.colvenna.lake.operator.lineage;

import static com.colvenna.lake.operator.lineage.Fixtures.NAMING;
import static com.colvenna.lake.operator.lineage.Fixtures.NS;
import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.crd.LakeTask;
import com.colvenna.lake.operator.flink.FlinkJobs;
import com.colvenna.lake.operator.metrics.Metrics;
import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.apache.flink.kubernetes.operator.api.spec.JobState;
import org.apache.flink.kubernetes.operator.api.status.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What is sent, and when: each change once, each run ended, nothing lost to a failure. */
@EnableKubernetesMockClient(crud = true, https = false)
class LineagePublisherTest {
    KubernetesClient kube;

    private final Fixtures.Catalog catalog = new Fixtures.Catalog();
    private final List<JsonNode> sent = new ArrayList<>();
    private final Map<String, Map<String, LineagePublisher.Reported>> saved = new HashMap<>();
    private final Metrics metrics = new Metrics();
    private boolean down;
    private String refuse;

    private final List<LakeTable> tables = new ArrayList<>();
    private final List<LakeTask> tasks = new ArrayList<>();
    private final Map<String, FlinkDeployment> deployments = new HashMap<>();

    private final LineagePublisher.Sink sink =
            event -> {
                if (down) {
                    throw new IOException("503");
                }
                if (event.path("job").path("name").asText().equals(refuse)) {
                    throw new LineagePublisher.Rejected("422");
                }
                sent.add(event);
            };
    private final LineagePublisher.Store store =
            new LineagePublisher.Store() {
                @Override
                public Map<String, LineagePublisher.Reported> load(String namespace) {
                    return saved.getOrDefault(namespace, Map.of());
                }

                @Override
                public void save(String namespace, Map<String, LineagePublisher.Reported> runs) {
                    saved.put(namespace, Map.copyOf(runs));
                }
            };

    private LineagePublisher publisher() {
        return new LineagePublisher(
                Set.of(NS),
                ns ->
                        new LineageGraph.Namespace(
                                ns,
                                List.of(Fixtures.source()),
                                List.copyOf(tables),
                                List.copyOf(tasks),
                                List.of(),
                                List.copyOf(deployments.values())),
                catalog::get,
                NAMING,
                sink,
                store,
                metrics,
                () -> Instant.parse("2026-10-17T03:00:00Z"),
                java.time.Duration.ofMinutes(1));
    }

    private void deploy(FlinkDeployment fd) {
        deployments.put(fd.getMetadata().getName(), fd);
    }

    /** Replaces the table of that name, and runs its deployment. */
    private void table(LakeTable t, String jobId) {
        tables.removeIf(x -> x.getMetadata().getName().equals(t.getMetadata().getName()));
        tables.add(t);
        deploy(Fixtures.tableJob(t, jobId, true));
    }

    /** What was sent since the last call: type, job, and the datasets in and out. */
    private List<String> sent() {
        List<String> out = new ArrayList<>();
        for (JsonNode e : sent) {
            List<String> in = new ArrayList<>();
            e.path("inputs").forEach(d -> in.add(d.path("name").asText()));
            List<String> o = new ArrayList<>();
            e.path("outputs").forEach(d -> o.add(d.path("name").asText()));
            out.add(
                    e.path("eventType").asText()
                            + " "
                            + e.path("job").path("name").asText()
                            + " "
                            + in
                            + " -> "
                            + o);
        }
        sent.clear();
        return out;
    }

    private static final String DWD =
            "dwd-orders-table [colvenna.ods.orders, colvenna.ods.segments]"
                    + " -> [colvenna.dwd.orders, colvenna.dwd.orders_quarantine]";

    @BeforeEach
    void pipeline() {
        deploy(Fixtures.cdc(Fixtures.source(), "h-src", "j-src"));
        table(Fixtures.dwd("h1"), "j1");
        table(Fixtures.dws("h-dws"), "j-dws");
    }

    @Test
    void eachChangeIsSentOnceAndEachRunEnded() {
        LineagePublisher p = publisher();
        p.publish(NS);
        assertThat(sent())
                .containsExactlyInAnyOrder(
                        "START orders-src-cdc [shop.orders, shop.segments]"
                                + " -> [colvenna.ods.orders, colvenna.ods.segments]",
                        "START " + DWD,
                        "START dws-segments-table [colvenna.dwd.orders] -> [colvenna.dws.segments]");
        p.publish(NS);
        assertThat(sent()).isEmpty();

        // A column added to the ODS table: the runs writing and reading it say so.
        catalog.put(
                "ods.orders",
                "id:BIGINT NOT NULL",
                "customer_id:BIGINT",
                "pt:TIMESTAMP(3)",
                "note:STRING");
        p.publish(NS);
        assertThat(sent())
                .containsExactlyInAnyOrder(
                        "RUNNING orders-src-cdc [shop.orders, shop.segments]"
                                + " -> [colvenna.ods.orders, colvenna.ods.segments]",
                        "RUNNING " + DWD);

        // An upgrade under way (the deployment runs a spec the status does not describe yet):
        // nothing is said until it settles; then the old run ends, with no datasets so the
        // catalog keeps the job's, before the new one starts.
        FlinkDeployment upgrading = Fixtures.tableJob(Fixtures.dwd("h1"), "j1", true);
        upgrading.getMetadata().getAnnotations().put(FlinkJobs.SPEC_HASH, "h2");
        deploy(upgrading);
        p.publish(NS);
        assertThat(sent()).isEmpty();
        table(Fixtures.dwd("h2"), "j2");
        p.publish(NS);
        // The old run ends when the new one started, not later: the catalog's latest run of the
        // job is the one with the latest event.
        assertThat(sent.get(0).path("eventTime").asText())
                .isEqualTo(sent.get(1).path("eventTime").asText());
        assertThat(sent()).containsExactly("COMPLETE dwd-orders-table [] -> []", "START " + DWD);

        // The table goes: its run ends, and is forgotten.
        deployments.remove("dws-segments-table");
        p.publish(NS);
        assertThat(sent()).containsExactly("COMPLETE dws-segments-table [] -> []");
        assertThat(saved.get(NS).values())
                .extracting(LineagePublisher.Reported::job)
                .doesNotContain("dws-segments-table");
        assertThat(metrics.render())
                .contains("colvenna_lineage_open_runs{namespace=\"colvenna-data\"} 2.0")
                .contains("colvenna_lineage_events_sent{namespace=\"colvenna-data\"} 8.0")
                .contains(
                        "colvenna_lineage_last_success_timestamp_seconds{namespace=\"colvenna-data\"}");
    }

    @Test
    void aSuspendedJobsRunEndsAndItsResumptionStartsAgain() {
        LineagePublisher p = publisher();
        p.publish(NS);
        sent();
        FlinkDeployment suspended = Fixtures.tableJob(Fixtures.dws("h-dws"), "j-dws", true);
        suspended.getSpec().getJob().setState(JobState.SUSPENDED);
        suspended
                .getStatus()
                .getReconciliationStatus()
                .serializeAndSetLastReconciledSpec(suspended.getSpec(), suspended);
        suspended.getStatus().setJobStatus(new JobStatus());
        deploy(suspended);
        p.publish(NS);
        assertThat(sent()).containsExactly("COMPLETE dws-segments-table [] -> []");
        table(Fixtures.dws("h-dws"), "j-dws");
        p.publish(NS);
        assertThat(sent())
                .containsExactly(
                        "START dws-segments-table [colvenna.dwd.orders] -> [colvenna.dws.segments]");
    }

    @Test
    void whatTheEndpointDidNotTakeIsSentAgain() {
        down = true;
        publisher().publish(NS);
        assertThat(sent()).isEmpty();
        assertThat(metrics.render())
                .contains("colvenna_lineage_failures{namespace=\"colvenna-data\"} 1.0");
        down = false;
        publisher().publish(NS);
        assertThat(sent()).hasSize(3);
    }

    @Test
    void anEventRefusedForGoodHoldsNothingBack() {
        refuse = "dwd-orders-table";
        LineagePublisher p = publisher();
        p.publish(NS);
        // The other jobs are reported; the refused one is counted and not sent again.
        assertThat(sent()).hasSize(2);
        assertThat(metrics.render())
                .contains("colvenna_lineage_events_rejected{namespace=\"colvenna-data\"} 1.0")
                .contains("colvenna_lineage_failures{namespace=\"colvenna-data\"} 0.0");
        refuse = null;
        p.publish(NS);
        assertThat(sent()).isEmpty();
    }

    @Test
    void aRestartedOperatorEndsWhatItsPredecessorStarted() {
        publisher().publish(NS);
        sent();
        // The next operator: nothing new to say...
        LineagePublisher next = publisher();
        next.publish(NS);
        assertThat(sent()).isEmpty();
        // ...and the run the first one started ends when its version is replaced.
        table(Fixtures.dwd("h2"), "j2");
        next.publish(NS);
        assertThat(sent()).containsExactly("COMPLETE dwd-orders-table [] -> []", "START " + DWD);
    }

    @Test
    void aBackfillsAttemptsStartAndEnd() {
        LineagePublisher p = publisher();
        p.publish(NS);
        sent();
        String backfill =
                "LakeTask.bf [colvenna.ods.orders, colvenna.ods.segments]"
                        + " -> [colvenna.dwd.orders, colvenna.dwd.orders_quarantine]";
        tasks.add(Fixtures.backfill("bf", "Running", 1));
        p.publish(NS);
        assertThat(sent()).containsExactly("START " + backfill);
        // The first attempt failed and the second ran to its end before the next report.
        tasks.set(0, Fixtures.backfill("bf", "Succeeded", 2, "Failed", "Succeeded"));
        p.publish(NS);
        assertThat(sent())
                .containsExactly("FAIL " + backfill, "START " + backfill, "COMPLETE " + backfill);
        p.publish(NS);
        assertThat(sent()).isEmpty();
        // The task goes: its attempts are forgotten, with nothing more to say.
        tasks.clear();
        p.publish(NS);
        assertThat(sent()).isEmpty();
        assertThat(saved.get(NS).values())
                .extracting(LineagePublisher.Reported::job)
                .doesNotContain("LakeTask.bf");
    }

    @Test
    void theStoreKeepsEachNamespaceApart() {
        ConfigMapLineageStore cm =
                new ConfigMapLineageStore(kube, "colvenna-system", "colvenna-operator-lineage");
        assertThat(cm.load(NS)).isEmpty();
        var a =
                new LineagePublisher.Reported(
                        "dwd-orders-table",
                        true,
                        new LineageGraph.JobType(true, "FLINK", "JOB"),
                        "f1");
        cm.save(NS, Map.of("r1", a));
        cm.save("other", Map.of());
        assertThat(cm.load(NS)).containsExactly(Map.entry("r1", a));
        assertThat(cm.load("other")).isEmpty();
        // What an earlier operator recorded in another shape: its runs are forgotten, not fatal.
        kube.configMaps()
                .inNamespace("colvenna-system")
                .withName("colvenna-operator-lineage")
                .edit(
                        c -> {
                            c.getData()
                                    .put(
                                            NS,
                                            "{\"r0\":{\"job\":\"x\",\"open\":true,"
                                                    + "\"streaming\":true,\"integration\":\"FLINK\"}}");
                            c.getData().put("other", "not json");
                            return c;
                        });
        assertThat(cm.load(NS)).isEmpty();
        assertThat(cm.load("other")).isEmpty();
    }
}
