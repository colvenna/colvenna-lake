package com.colvenna.lake.operator.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.Durations;
import com.colvenna.lake.operator.OperatorSettings;
import com.colvenna.lake.operator.catalog.CatalogClient;
import com.colvenna.lake.operator.crd.Governance;
import com.colvenna.lake.operator.crd.LakeDashboard;
import com.colvenna.lake.operator.crd.LakeDashboardSpec;
import com.colvenna.lake.operator.crd.LakeDashboardStatus;
import com.colvenna.lake.operator.crd.LakeQuality;
import com.colvenna.lake.operator.crd.LakeQualitySpec;
import com.colvenna.lake.operator.crd.LakeQualityStatus;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceSpec;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.crd.LakeTableSpec;
import com.colvenna.lake.operator.crd.LakeTableStatus;
import com.colvenna.lake.operator.crd.LakeTask;
import com.colvenna.lake.operator.crd.LakeTaskSpec;
import com.colvenna.lake.operator.crd.StatusCondition;
import com.colvenna.lake.operator.metrics.Metrics;
import com.colvenna.lake.operator.quality.MemoryQualityStore;
import com.colvenna.lake.operator.quality.QualityRules;
import com.colvenna.lake.operator.quality.QualityStore;
import com.colvenna.lake.operator.table.Fence;
import com.colvenna.lake.operator.table.TableJobs;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.apache.flink.kubernetes.operator.api.spec.FlinkDeploymentSpec;
import org.apache.flink.kubernetes.operator.api.spec.JobSpec;
import org.apache.flink.kubernetes.operator.api.spec.JobState;
import org.apache.flink.kubernetes.operator.api.status.FlinkDeploymentStatus;
import org.apache.flink.kubernetes.operator.api.status.ReconciliationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A task from request to final phase against a Kubernetes mock and a fake colvenna-catalog: the
 * run's Job, retries, one task per table at a time, a backfill's fence and its wait for the tables
 * downstream.
 */
@EnableKubernetesMockClient(crud = true, https = false)
class LakeTaskReconcilerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NS = "colvenna-data";
    KubernetesClient kube;
    private MockWebServer catalog;
    private final List<String> grants = new ArrayList<>();

    /** The owner option of LakeTable targets, by database.table; other tables are ODS. */
    private final Map<String, String> owners = new ConcurrentHashMap<>();

    private final Map<String, Long> snapshots = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-10-15T00:00:00Z"));
    private LakeTaskReconciler reconciler;
    private final MemoryQualityStore store = new MemoryQualityStore(now::get);

    @BeforeEach
    void start() throws Exception {
        catalog = new MockWebServer();
        catalog.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest r) {
                        String call = r.getMethod() + " " + r.getPath();
                        String[] p = r.getPath().split("/");
                        if (call.equals("GET /v1/colvenna/databases/ods")) {
                            return json(
                                    "{\"options\":{\"colvenna-lake.owner\":\"LakeSource/"
                                            + NS
                                            + "/shop/u1\"}}");
                        }
                        if (call.matches("GET /v1/colvenna/databases/[a-z]+")) {
                            return json("{\"options\":{}}");
                        }
                        if (call.matches(
                                "GET /v1/colvenna/databases/[a-z]+/tables/[a-z_]+/snapshot")) {
                            Long id = snapshots.get(p[4] + "." + p[6]);
                            return id == null
                                    ? new MockResponse().setResponseCode(404).setBody("{}")
                                    : json("{\"snapshot\":{\"snapshot\":{\"id\":" + id + "}}}");
                        }
                        if (call.matches("GET /v1/colvenna/databases/[a-z]+/tables/[a-z_]+")) {
                            String owner = owners.get(p[4] + "." + p[6]);
                            // ODS tables are written only and compacted by maintenance.
                            return json(
                                    "{\"schema\":{\"options\":{"
                                            + (owner == null
                                                    ? "\"write-only\":\"true\""
                                                    : "\"colvenna-lake.owner\":\"" + owner + "\"")
                                            + "}}}");
                        }
                        if (call.equals("GET /colvenna/admin/v1/principals")) {
                            return json("{\"principals\":[]}");
                        }
                        if (call.equals("POST /colvenna/admin/v1/principals")) {
                            return json("{\"name\":\"x\"}");
                        }
                        if (call.matches("POST /colvenna/admin/v1/principals/.*/tokens")) {
                            return json(
                                    "{\"id\":\"t1\",\"token\":\"colvenna_secret\",\"expiresAt\":"
                                            + "\"2026-11-14T00:00:00Z\"}");
                        }
                        if (call.matches("GET /colvenna/admin/v1/principals/.*/tokens")) {
                            return json("{\"tokens\":[{\"id\":\"t1\"}]}");
                        }
                        if (call.startsWith("GET /colvenna/admin/v1/grants")) {
                            return json("{\"grants\":[]}");
                        }
                        if (call.equals("POST /colvenna/admin/v1/grants")) {
                            String body = r.getBody().readString(StandardCharsets.UTF_8);
                            grants.add(body);
                            return json(body);
                        }
                        if (call.startsWith("DELETE ")) {
                            return new MockResponse();
                        }
                        return new MockResponse().setResponseCode(404).setBody("{}");
                    }
                });
        catalog.start();
        OperatorSettings settings =
                new OperatorSettings(
                        "https://colvenna-catalog:8181",
                        Path.of("/token"),
                        Path.of("/ca"),
                        "s3://bucket/flink-state",
                        "colvenna-flink",
                        Map.of("sql", "repo/colvenna-jobs@sha256:2"),
                        Map.of(),
                        List.of(),
                        6000,
                        6999,
                        1000,
                        9100);
        reconciler =
                new LakeTaskReconciler(
                        settings,
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

    private static MockResponse json(String body) {
        return new MockResponse().setBody(body);
    }

    private LakeTask create(String name, String type, String database, String table) {
        LakeTask task = new LakeTask();
        task.setMetadata(
                new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(NS)
                        .withUid(UUID.randomUUID().toString())
                        .withGeneration(1L)
                        .withCreationTimestamp(now.get().toString())
                        .build());
        LakeTaskSpec spec = new LakeTaskSpec();
        spec.type = type;
        spec.table = new LakeTaskSpec.Table();
        spec.table.database = database;
        spec.table.table = table;
        task.setSpec(spec);
        now.set(now.get().plusSeconds(1));
        return kube.resource(task).create();
    }

    /** A createTag task on ods.orders, as stored. */
    private LakeTask tag(String name, String tag, String retained) {
        LakeTask task = create(name, "createTag", "ods", "orders");
        task.getSpec().createTag = new LakeTaskSpec.CreateTag();
        task.getSpec().createTag.name = tag;
        task.getSpec().createTag.timeRetained = retained;
        return kube.resource(task).update();
    }

    /** One reconciliation, its status written back as JOSDK would. */
    private LakeTask round(LakeTask task) {
        LakeTask current =
                kube.resources(LakeTask.class)
                        .inNamespace(NS)
                        .withName(task.getMetadata().getName())
                        .get();
        reconciler.reconcile(current, null);
        LakeTask stored =
                kube.resources(LakeTask.class)
                        .inNamespace(NS)
                        .withName(task.getMetadata().getName())
                        .get();
        stored.setStatus(current.getStatus());
        return kube.resource(stored).update();
    }

    /** The attempt's pod, ended with an exit code and termination message. */
    private void finish(String run, int exitCode, String message) {
        kube.pods().inNamespace(NS).withName(run + "-x").delete();
        kube.resource(
                        new PodBuilder()
                                .withNewMetadata()
                                .withName(run + "-x")
                                .withNamespace(NS)
                                .withLabels(Map.of("job-name", run))
                                .endMetadata()
                                .withNewStatus()
                                .withPhase(exitCode == 0 ? "Succeeded" : "Failed")
                                .withContainerStatuses(
                                        new ContainerStatusBuilder()
                                                .withName("task")
                                                .withNewState()
                                                .withNewTerminated()
                                                .withExitCode(exitCode)
                                                .withReason(exitCode == 0 ? "Completed" : "Error")
                                                .withMessage(message)
                                                .endTerminated()
                                                .endState()
                                                .build())
                                .endStatus()
                                .build())
                .create();
    }

    /** The attempt's pod, still running. */
    private Pod running(String run) {
        return kube.resource(
                        new PodBuilder()
                                .withNewMetadata()
                                .withName(run + "-x")
                                .withNamespace(NS)
                                .withLabels(Map.of("job-name", run))
                                .endMetadata()
                                .withNewStatus()
                                .withPhase("Running")
                                .endStatus()
                                .build())
                .create();
    }

    @Test
    void aCompactionRunsOnceAsAJobAndReportsItsSnapshots() throws Exception {
        LakeTask task = round(create("compact-orders", "compact", "ods", "orders"));
        assertThat(task.getStatus().phase).isEqualTo("Running");
        assertThat(task.getStatus().attempts).isEqualTo(1);
        assertThat(task.getStatus().startedAt).isNotNull();
        Job job = kube.batch().v1().jobs().inNamespace(NS).withName(task.getStatus().run).get();
        var pod = job.getSpec().getTemplate().getSpec();
        assertThat(job.getSpec().getBackoffLimit()).isZero();
        assertThat(job.getSpec().getActiveDeadlineSeconds()).isEqualTo(3600L);
        assertThat(pod.getAutomountServiceAccountToken()).isFalse();
        assertThat(pod.getContainers().get(0).getSecurityContext().getReadOnlyRootFilesystem())
                .isTrue();
        List<String> command = pod.getContainers().get(0).getCommand();
        assertThat(command).contains(TaskRuns.ENTRY);
        assertThat(JSON.readTree(command.get(command.size() - 1)).path("type").asText())
                .isEqualTo("compact");
        // The task's identity writes this table and nothing else.
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0)).contains("\"WRITE\"").contains("orders");

        finish(
                task.getStatus().run,
                0,
                "{\"snapshotBefore\":4,\"snapshotAfter\":5,\"detail\":\"compacted\"}");
        task = round(task);
        assertThat(task.getStatus().phase).isEqualTo("Succeeded");
        assertThat(task.getStatus().result.snapshotBefore).isEqualTo(4L);
        assertThat(task.getStatus().result.snapshotAfter).isEqualTo(5L);
        assertThat(task.getStatus().finishedAt).isNotNull();
        assertThat(task.getStatus().history)
                .singleElement()
                .satisfies(a -> assertThat(a.outcome).isEqualTo("Succeeded"));
        assertThat(StatusCondition.isTrue(task.getStatus().conditions, "Finished")).isTrue();
        // A finished task writes nothing more: its identity is gone.
        assertThat(task.getStatus().identity).isNull();

        // Final: applying the same task again changes nothing and runs nothing.
        String finished = task.getStatus().finishedAt;
        task = round(task);
        assertThat(task.getStatus().finishedAt).isEqualTo(finished);
        assertThat(kube.batch().v1().jobs().inNamespace(NS).list().getItems()).hasSize(1);
    }

    /** A LakeQuality of the table, judged: valid rules of one version. */
    private LakeQuality quality(String table, long min) {
        LakeQuality q = new LakeQuality();
        q.setMetadata(
                new ObjectMetaBuilder()
                        .withName("q-" + table.replace('.', '-').replace('_', '-'))
                        .withNamespace(NS)
                        .withCreationTimestamp(now.get().toString())
                        .build());
        q.setSpec(new LakeQualitySpec());
        q.getSpec().table = new LakeTaskSpec.Table();
        q.getSpec().table.database = TableJobs.split(table)[0];
        q.getSpec().table.table = TableJobs.split(table)[1];
        LakeQualitySpec.Rule volume = new LakeQualitySpec.Rule();
        volume.name = "volume";
        volume.rowCount = new LakeQualitySpec.RowCount();
        volume.rowCount.min = min;
        q.getSpec().rules = new ArrayList<>(List.of(volume));
        q = kube.resource(q).create();
        q.setStatus(new LakeQualityStatus());
        q.getStatus().phase = "Ready";
        q.getStatus().ruleVersion = QualityRules.version(q.getSpec().rules);
        q.getStatus().observedGeneration = q.getMetadata().getGeneration();
        StatusCondition.set(
                q.getStatus().conditions, "RulesValid", true, "Valid", "", 1, now.get());
        return kube.resource(q).updateStatus();
    }

    @Test
    void aCheckReadsItsSnapshotWritesOnlyItsResultsAndCarriesItsVerdict() throws Exception {
        store.columns.put("ods.orders", new QualityStore.Columns(List.of(), List.of()));
        snapshots.put("ods.orders", 7L);
        // No rules yet: nothing to run.
        LakeTask none = round(create("check-none", "check", "ods", "orders"));
        assertThat(none.getStatus().conditions)
                .anyMatch(c -> c.type.equals("Finished") && c.reason.equals("NoRules"));

        LakeQuality q = quality("ods.orders", 1);
        LakeTask task = round(create("check-orders", "check", "ods", "orders"));
        assertThat(task.getStatus().phase).isEqualTo("Running");
        // Fixed as it starts: the latest snapshot, the time, the rules' version.
        assertThat(task.getStatus().check.snapshot).isEqualTo(7L);
        assertThat(task.getStatus().check.ruleVersion).isEqualTo(q.getStatus().ruleVersion);
        assertThat(task.getStatus().check.asOf).isNotNull();
        String asOf = task.getStatus().check.asOf;
        // It reads the table and writes only its results.
        assertThat(grants).hasSize(2);
        assertThat(grants.get(0)).contains("\"READ\"").contains("orders");
        assertThat(grants.get(1))
                .contains("\"WRITE\"")
                .contains("quality_colvenna_data")
                .contains("results");
        Job job = kube.batch().v1().jobs().inNamespace(NS).withName(task.getStatus().run).get();
        List<String> command =
                job.getSpec().getTemplate().getSpec().getContainers().get(0).getCommand();
        var arg = JSON.readTree(command.get(command.size() - 1));
        assertThat(arg.path("snapshot").asLong()).isEqualTo(7L);
        assertThat(arg.path("taskUid").asText()).isEqualTo(task.getMetadata().getUid());
        assertThat(arg.path("store").asText()).isEqualTo("quality_colvenna_data");
        assertThat(arg.path("rules").get(0).path("kind").asText()).isEqualTo("rowCount");

        // A retry checks what the first attempt did, though the table moved on.
        snapshots.put("ods.orders", 9L);
        finish(task.getStatus().run, 1, "");
        task = round(task);
        now.set(now.get().plus(LakeTaskReconciler.backoff(1)));
        task = round(task);
        assertThat(task.getStatus().attempts).isEqualTo(2);
        assertThat(task.getStatus().check.snapshot).isEqualTo(7L);
        assertThat(task.getStatus().check.asOf).isEqualTo(asOf);
        finish(
                task.getStatus().run,
                0,
                "{\"snapshotBefore\":7,\"snapshotAfter\":7,\"detail\":\"checked 0 rows\","
                        + "\"check\":{\"verdict\":\"Failed\",\"failed\":[\"volume\"],\"rows\":0}}");
        task = round(task);
        // The check ran: Succeeded, whatever it found.
        assertThat(task.getStatus().phase).isEqualTo("Succeeded");
        assertThat(task.getStatus().check.verdict).isEqualTo("Failed");
        assertThat(task.getStatus().check.failed).containsExactly("volume");
        assertThat(task.getStatus().check.rows).isZero();
    }

    @Test
    void aCheckWaitsForItsRulesToBeJudgedAndRefusesAFutureAsOf() {
        store.columns.put("ods.orders", new QualityStore.Columns(List.of(), List.of()));
        snapshots.put("ods.orders", 7L);
        LakeQuality q = quality("ods.orders", 1);
        // Changed, and not judged yet: the check waits rather than failing.
        q.getSpec().rules.get(0).rowCount.min = 3L;
        q = kube.resource(q).update();
        LakeTask waiting = round(create("check-early", "check", "ods", "orders"));
        assertThat(waiting.getStatus().phase).isEqualTo("Pending");
        assertThat(waiting.getStatus().message).contains("judge its rules");
        q.getStatus().observedGeneration = q.getMetadata().getGeneration();
        kube.resource(q).updateStatus();
        waiting = round(waiting);
        assertThat(waiting.getStatus().phase).isEqualTo("Running");
        assertThat(waiting.getStatus().check.ruleVersion)
                .isEqualTo(QualityRules.version(q.getSpec().rules));

        LakeTask future = create("check-future", "check", "ods", "orders");
        future.getSpec().check = new LakeTaskSpec.Check();
        future.getSpec().check.asOf = now.get().plus(Duration.ofHours(1)).toString();
        future = round(kube.resource(future).update());
        assertThat(future.getStatus().conditions)
                .anyMatch(c -> c.type.equals("Finished") && c.reason.equals("InvalidSpec"));
    }

    @Test
    void aCheckWhoseRulesChangedOrPartitionsAreNotTheTablesDoesNotRun() {
        store.columns.put("ods.orders", new QualityStore.Columns(List.of(), List.of()));
        snapshots.put("ods.orders", 7L);
        quality("ods.orders", 1);
        LakeTask p = create("check-part", "check", "ods", "orders");
        p.getSpec().check = new LakeTaskSpec.Check();
        p.getSpec().check.partitions = List.of("dt=2026-10-16");
        p = round(kube.resource(p).update());
        assertThat(p.getStatus().conditions)
                .anyMatch(c -> c.type.equals("Finished") && c.reason.equals("InvalidPartitions"));
        LakeTask s = create("check-old", "check", "ods", "orders");
        s.getSpec().check = new LakeTaskSpec.Check();
        s.getSpec().check.snapshot = 3L;
        store.oldestSnapshot = 5;
        s = round(kube.resource(s).update());
        assertThat(s.getStatus().conditions)
                .anyMatch(c -> c.type.equals("Finished") && c.reason.equals("SnapshotMissing"));

        LakeTask task = round(create("check-orders", "check", "ods", "orders"));
        finish(task.getStatus().run, 1, "");
        // The rules change before the retry: what the check started with is gone.
        LakeQuality q =
                kube.resources(LakeQuality.class).inNamespace(NS).withName("q-ods-orders").get();
        q.getSpec().rules.get(0).rowCount.min = 5L;
        q = kube.resource(q).update();
        q.getStatus().ruleVersion = QualityRules.version(q.getSpec().rules);
        kube.resource(q).updateStatus();
        task = round(task);
        task = round(task);
        assertThat(task.getStatus().conditions)
                .anyMatch(c -> c.type.equals("Finished") && c.reason.equals("RulesChanged"));
    }

    @Test
    void aTagOnPersonalOrUnclassifiedDataIsKeptADayAtMost() {
        snapshots.put("ods.orders", 7L);
        // No declaration classifies ods.orders: temporary L4, personal.
        LakeTask task = round(tag("tag-week", "week", "2d"));
        assertThat(task.getStatus().conditions)
                .anyMatch(c -> c.type.equals("Finished") && c.reason.equals("RetentionTooLong"));
        // Declared without personal data, the table may keep its tag longer.
        LakeSource ods = new LakeSource();
        ods.setMetadata(new ObjectMetaBuilder().withName("shop").withNamespace(NS).build());
        ods.setSpec(new LakeSourceSpec());
        ods.getSpec().type = "mysql";
        ods.getSpec().target = new LakeSourceSpec.Target();
        ods.getSpec().target.database = "ods";
        ods.getSpec().governance = new Governance();
        ods.getSpec().governance.owner = "shop-team";
        ods.getSpec().governance.level = "L2";
        ods.getSpec().governance.retention = "7d";
        kube.resource(ods).create();
        task = round(tag("tag-week-2", "week", "2d"));
        assertThat(task.getStatus().phase).isEqualTo("Running");
    }

    @Test
    void aTagOfATableWithoutASnapshotEndsBeforeAnyRun() {
        // The table runs but has committed nothing yet: there is no snapshot to name.
        LakeTask task = round(tag("tag-new", "first", "12h"));
        assertThat(task.getStatus().phase).isEqualTo("Failed");
        assertThat(task.getStatus().conditions)
                .anyMatch(c -> c.type.equals("Finished") && c.reason.equals("NoSnapshot"));
        assertThat(task.getStatus().message).contains("no snapshot");
        assertThat(task.getStatus().startedAt).isNull();
        assertThat(kube.batch().v1().jobs().inNamespace(NS).list().getItems()).isEmpty();

        // Once it has one, a new task tags it.
        snapshots.put("ods.orders", 1L);
        LakeTask again = round(tag("tag-new-2", "first", "12h"));
        assertThat(again.getStatus().phase).isEqualTo("Running");
    }

    @Test
    void retriesBackOffDoublingToACap() {
        assertThat(LakeTaskReconciler.backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(LakeTaskReconciler.backoff(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(LakeTaskReconciler.backoff(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(LakeTaskReconciler.backoff(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(LakeTaskReconciler.backoff(5)).isEqualTo(Duration.ofMinutes(10));
        assertThat(LakeTaskReconciler.backoff(6)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void aFailedAttemptIsRetriedAsANewJobOnceItsPodStopped() {
        snapshots.put("ods.orders", 7L);
        LakeTask task = tag("tag-orders", "eod-20261014", "12h");
        task.getSpec().retries = 1;
        task = round(kube.resource(task).update());
        String first = task.getStatus().run;
        // The Job failed (its deadline) while its pod still terminates: no second writer yet.
        Job failed = kube.batch().v1().jobs().inNamespace(NS).withName(first).get();
        failed.setStatus(
                new JobStatusBuilder()
                        .addNewCondition()
                        .withType("Failed")
                        .withStatus("True")
                        .withReason("DeadlineExceeded")
                        .endCondition()
                        .build());
        kube.resource(failed).update();
        running(first);
        task = round(task);
        assertThat(task.getStatus().attempts).isEqualTo(1);
        assertThat(task.getStatus().message).contains("waiting for its pod to stop");
        finish(first, 137, "");
        task = round(task);
        assertThat(task.getStatus().phase).isEqualTo("Running");
        Instant retryAt = now.get().plus(LakeTaskReconciler.backoff(1));
        assertThat(task.getStatus().message).contains("attempt 2 starts at " + retryAt);
        // Not at once: the next attempt waits out the backoff, so a passing cause can pass. Until
        // it starts, the task's attempt and run are still the first.
        now.set(retryAt.minusSeconds(1));
        task = round(task);
        assertThat(task.getStatus().message)
                .contains("exit 137")
                .contains("attempt 2 starts at " + retryAt);
        assertThat(task.getStatus().attempts).isEqualTo(1);
        assertThat(task.getStatus().run).isEqualTo(first);
        assertThat(kube.batch().v1().jobs().inNamespace(NS).list().getItems()).hasSize(1);
        assertThat(attemptFailedEvents()).isEqualTo(1);
        now.set(retryAt);
        task = round(task);
        assertThat(task.getStatus().attempts).isEqualTo(2);
        assertThat(task.getStatus().run).isNotEqualTo(first);
        assertThat(kube.batch().v1().jobs().inNamespace(NS).withName(task.getStatus().run).get())
                .isNotNull();
        finish(task.getStatus().run, 1, "");
        task = round(task);
        assertThat(task.getStatus().phase).isEqualTo("Failed");
        assertThat(task.getStatus().message).contains("attempt 2 of 2").contains("exit 1");
        // Each attempt's end is there to read.
        assertThat(task.getStatus().history)
                .extracting(a -> a.attempt + " " + a.outcome)
                .containsExactly("1 Failed", "2 Failed");
        assertThat(task.getStatus().history.get(0).message).contains("exit 137");
    }

    @Test
    void eachRetryWaitsLongerThanTheOneBefore() {
        snapshots.put("ods.orders", 7L);
        LakeTask task = round(tag("tag-thrice", "eod-20261015", "12h"));
        finish(task.getStatus().run, 1, "");
        task = round(task);
        now.set(now.get().plus(LakeTaskReconciler.backoff(1)));
        task = round(task);
        assertThat(task.getStatus().attempts).isEqualTo(2);
        String second = task.getStatus().run;
        finish(second, 1, "");
        task = round(task);
        // The third attempt waits 2 min, twice the first wait.
        Instant retryAt = now.get().plus(Duration.ofMinutes(2));
        assertThat(LakeTaskReconciler.backoff(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(task.getStatus().message).contains("attempt 3 starts at " + retryAt);
        now.set(retryAt.minusSeconds(1));
        task = round(task);
        assertThat(task.getStatus().attempts).isEqualTo(2);
        assertThat(task.getStatus().run).isEqualTo(second);
        assertThat(kube.batch().v1().jobs().inNamespace(NS).list().getItems()).hasSize(2);
        now.set(retryAt);
        task = round(task);
        assertThat(task.getStatus().attempts).isEqualTo(3);
        assertThat(kube.batch().v1().jobs().inNamespace(NS).withName(task.getStatus().run).get())
                .isNotNull();
    }

    private long attemptFailedEvents() {
        return kube.v1().events().inNamespace(NS).list().getItems().stream()
                .filter(e -> "AttemptFailed".equals(e.getReason()))
                .count();
    }

    @Test
    void aFailedAttemptWhoseJobIsDeletedIsNotRunAgainBeforeTheRetry() {
        snapshots.put("ods.orders", 7L);
        LakeTask task = round(tag("tag-deleted", "eod-20261016", "12h"));
        String first = task.getStatus().run;
        finish(first, 1, "");
        task = round(task);
        Instant retryAt = now.get().plus(LakeTaskReconciler.backoff(1));
        // Someone deletes the failed attempt's Job while the retry waits.
        kube.batch().v1().jobs().inNamespace(NS).withName(first).delete();
        kube.pods().inNamespace(NS).withName(first + "-x").delete();
        for (int i = 0; i < 3; i++) {
            now.set(now.get().plusSeconds(10));
            task = round(task);
            assertThat(kube.batch().v1().jobs().inNamespace(NS).list().getItems()).isEmpty();
            assertThat(task.getStatus().attempts).isEqualTo(1);
            assertThat(task.getStatus().message).contains("attempt 2 starts at " + retryAt);
        }
        // One warning for the failed attempt, however many rounds it waits.
        assertThat(attemptFailedEvents()).isEqualTo(1);
        assertThat(task.getStatus().history)
                .extracting(a -> a.attempt + " " + a.outcome)
                .containsExactly("1 Failed");
        now.set(retryAt);
        task = round(task);
        assertThat(task.getStatus().attempts).isEqualTo(2);
        assertThat(kube.batch().v1().jobs().inNamespace(NS).list().getItems())
                .extracting(j -> j.getMetadata().getName())
                .containsExactly(task.getStatus().run);
        assertThat(attemptFailedEvents()).isEqualTo(1);
    }

    @Test
    void tasksOnOneTableRunOneAtATimeAndAStartedOneIsNotOvertaken() {
        LakeTask first = round(create("expire-1", "expireSnapshots", "ods", "orders"));
        LakeTask second = round(create("expire-2", "expireSnapshots", "ods", "orders"));
        LakeTask other = round(create("expire-3", "expireSnapshots", "ods", "customers"));
        assertThat(first.getStatus().phase).isEqualTo("Running");
        assertThat(second.getStatus().phase).isEqualTo("Pending");
        assertThat(second.getStatus().message).contains("expire-1");
        assertThat(other.getStatus().phase).isEqualTo("Running");
        // Created before the running task (as a same-second uid order can make it): it waits.
        now.set(Instant.parse(first.getMetadata().getCreationTimestamp()));
        LakeTask early = round(create("expire-0", "expireSnapshots", "ods", "customers"));
        assertThat(early.getStatus().phase).isEqualTo("Pending");
        assertThat(early.getStatus().message).contains("expire-3");
        finish(first.getStatus().run, 0, "{\"detail\":\"expired 3\"}");
        round(first);
        assertThat(round(second).getStatus().phase).isEqualTo("Running");
    }

    @Test
    void refusesTablesOfNoDeclarationHereAndCompactionBesideACompactingWriter() {
        owners.put("dwd.orders", "LakeTable/colvenna-other/orders-wide/u2");
        LakeTask task = round(create("compact-elsewhere", "compact", "dwd", "orders"));
        assertThat(task.getStatus().phase).isEqualTo("Failed");
        assertThat(task.getStatus().message).contains("neither");
        owners.put("dwd.orders", "LakeTable/" + NS + "/orders-wide/u2");
        task = round(create("compact-wide", "compact", "dwd", "orders"));
        assertThat(task.getStatus().conditions)
                .anyMatch(c -> c.type.equals("Finished") && c.reason.equals("WriterCompacts"));
        task = round(create("backfill-ods", "backfill", "ods", "orders"));
        assertThat(task.getStatus().conditions)
                .anyMatch(c -> c.type.equals("Finished") && c.reason.equals("NotALakeTable"));
        assertThat(kube.batch().v1().jobs().inNamespace(NS).list().getItems()).isEmpty();
    }

    @Test
    void aCompactionOfALayeredTableRunsBehindItsFence() throws Exception {
        LakeTable wide = lakeTable("orders-wide", "dwd", "orders", List.of("ods.orders"));
        job(wide, false);
        LakeTask task = round(create("compact-wide", "compact", "dwd", "orders"));
        assertThat(task.getStatus().phase).isEqualTo("Fencing");
        assertThat(kube.batch().v1().jobs().inNamespace(NS).list().getItems()).isEmpty();
        job(wide, true);
        acknowledge("orders-wide");
        task = round(task);
        assertThat(task.getStatus().phase).isEqualTo("Running");
        // A compaction reads nothing beyond its table.
        assertThat(grants).noneMatch(g -> g.contains("\"READ\"") && g.contains("ods"));
        finish(
                task.getStatus().run,
                0,
                "{\"snapshotBefore\":7,\"snapshotAfter\":8,\"detail\":\"compacted\"}");
        task = round(task);
        assertThat(task.getStatus().phase).isEqualTo("Succeeded");
        assertThat(task.getStatus().fence.releasedAt).isNotNull();
        assertThat(Fence.holder(lakeTable("orders-wide"))).isNull();
    }

    private LakeTable lakeTable(String name, String database, String table, List<String> streams) {
        LakeTable t = new LakeTable();
        t.setMetadata(
                new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(NS)
                        .withUid(UUID.randomUUID().toString())
                        .build());
        t.setSpec(new LakeTableSpec());
        t.getSpec().target = new LakeTableSpec.Target();
        t.getSpec().target.database = database;
        t.getSpec().target.table = table;
        t.getSpec().primaryKey = List.of("order_id");
        t.getSpec().query = "SELECT order_id, amount FROM " + streams.get(0);
        t.getSpec().upstream = streams;
        LakeTableStatus status = new LakeTableStatus();
        status.phase = "Running";
        StatusCondition.set(status.conditions, "SpecApplied", true, "Applied", "", 1, now.get());
        status.applied = new LakeTableStatus.Applied();
        status.applied.streams = streams;
        status.validation = new LakeTableStatus.Validation();
        status.validation.stateful = false;
        for (String s : streams) {
            LakeTableStatus.Upstream u = new LakeTableStatus.Upstream();
            u.table = s;
            u.nextSnapshot = 3L;
            status.upstream.add(u);
        }
        t.setStatus(status);
        LakeTable created = kube.resource(t).create();
        owners.put(
                database + "." + table,
                "LakeTable/" + NS + "/" + name + "/" + created.getMetadata().getUid());
        return created;
    }

    /** The table's FlinkDeployment, as the Flink operator reports it after its last change. */
    private void job(LakeTable table, boolean suspended) {
        FlinkDeployment fd = new FlinkDeployment();
        fd.setMetadata(
                new ObjectMetaBuilder()
                        .withName(TableJobs.deploymentName(table))
                        .withNamespace(NS)
                        .withGeneration(1L)
                        .build());
        FlinkDeploymentSpec spec = new FlinkDeploymentSpec();
        spec.setJob(new JobSpec());
        spec.getJob().setState(suspended ? JobState.SUSPENDED : JobState.RUNNING);
        fd.setSpec(spec);
        FlinkDeploymentStatus status = new FlinkDeploymentStatus();
        status.getReconciliationStatus().serializeAndSetLastReconciledSpec(spec, fd);
        status.getReconciliationStatus().setState(ReconciliationState.DEPLOYED);
        status.setObservedGeneration(1L);
        fd.setStatus(status);
        kube.resources(FlinkDeployment.class)
                .inNamespace(NS)
                .withName(fd.getMetadata().getName())
                .delete();
        kube.resource(fd).create();
    }

    private LakeTable lakeTable(String name) {
        return kube.resources(LakeTable.class).inNamespace(NS).withName(name).get();
    }

    /** What the LakeTable's reconciler records once it suspended the job for the fence. */
    private void acknowledge(String table) {
        LakeTable t = lakeTable(table);
        t.getStatus().fencedBy = Fence.holder(t);
        t.getStatus().phase = t.getStatus().fencedBy == null ? "Running" : "Suspended";
        kube.resource(t).update();
    }

    private void reads(String reader, String table, long next) {
        LakeTable t = lakeTable(reader);
        t.getStatus().upstream.stream()
                .filter(u -> u.table.equals(table))
                .forEach(u -> u.nextSnapshot = next);
        kube.resource(t).update();
    }

    @Test
    void aBackfillRunsOnceTheFenceHoldsAndSucceedsOnceEveryTableDownstreamReadIt()
            throws Exception {
        LakeTable wide = lakeTable("orders-wide", "dwd", "orders", List.of("ods.orders"));
        lakeTable("orders-daily", "ads", "daily", List.of("dwd.orders"));
        lakeTable("daily-top", "top", "daily", List.of("ads.daily"));
        job(wide, false);
        LakeTask task = round(create("backfill-orders", "backfill", "dwd", "orders"));
        assertThat(task.getStatus().phase).isEqualTo("Fencing");
        assertThat(Fence.holder(lakeTable("orders-wide"))).startsWith("backfill-orders/");
        // The identity also reads what the query reads.
        assertThat(grants).anyMatch(g -> g.contains("\"READ\"") && g.contains("ods"));

        // Suspended, but the table's reconciler has not said it saw the fence: no run yet.
        job(wide, true);
        assertThat(round(task).getStatus().phase).isEqualTo("Fencing");
        assertThat(kube.batch().v1().jobs().inNamespace(NS).list().getItems()).isEmpty();
        acknowledge("orders-wide");
        task = round(task);
        assertThat(task.getStatus().phase).isEqualTo("Running");
        var argument =
                JSON.readTree(
                        kube.batch()
                                .v1()
                                .jobs()
                                .inNamespace(NS)
                                .withName(task.getStatus().run)
                                .get()
                                .getSpec()
                                .getTemplate()
                                .getSpec()
                                .getContainers()
                                .get(0)
                                .getCommand()
                                .getLast());
        assertThat(argument.path("query").asText())
                .isEqualTo("SELECT order_id, amount FROM ods.orders");
        assertThat(argument.path("primaryKey").get(0).asText()).isEqualTo("order_id");

        finish(
                task.getStatus().run,
                0,
                "{\"snapshotBefore\":7,\"snapshotAfter\":9,\"detail\":\"upserted 3 rows, deleted 1"
                        + " no longer derived\"}");
        task = round(task);
        assertThat(task.getStatus().phase).isEqualTo("Converging");
        assertThat(task.getStatus().fence.releasedAt).isNotNull();
        assertThat(Fence.holder(lakeTable("orders-wide"))).isNull();
        assertThat(task.getStatus().waitingFor)
                .anyMatch(w -> w.startsWith("orders-wide (not running"))
                .anyMatch(w -> w.startsWith("orders-daily (reads dwd.orders from 3"));

        acknowledge("orders-wide");
        reads("orders-daily", "dwd.orders", 9);
        assertThat(round(task).getStatus().phase).isEqualTo("Converging");
        // orders-daily read the backfill; its commit of it carries it, and daily-top must read
        // that.
        reads("orders-daily", "dwd.orders", 10);
        snapshots.put("ads.daily", 20L);
        task = round(task);
        assertThat(task.getStatus().frontier.get("ads.daily").snapshot).isNull();
        assertThat(task.getStatus().waitingFor)
                .anyMatch(w -> w.startsWith("ads.daily (its commit"));
        // Not before the commit's grace: rounds come faster than checkpoints.
        task = round(task);
        assertThat(task.getStatus().frontier.get("ads.daily").snapshot).isNull();
        now.set(now.get().plus(LakeTaskReconciler.COMMIT_GRACE));
        task = round(task);
        assertThat(task.getStatus().frontier.get("ads.daily").snapshot).isEqualTo(20L);
        assertThat(task.getStatus().waitingFor)
                .singleElement()
                .satisfies(w -> assertThat(w).startsWith("daily-top (reads ads.daily from 3"));
        reads("daily-top", "ads.daily", 21);
        task = round(task);
        // daily-top's own target may have readers: its commit is looked up too.
        assertThat(task.getStatus().waitingFor)
                .containsExactly("top.daily (its commit of the changes is looked up next)");
        now.set(now.get().plus(LakeTaskReconciler.COMMIT_GRACE));
        task = round(task);
        assertThat(task.getStatus().phase).isEqualTo("Succeeded");
        assertThat(task.getStatus().message).contains("deleted 1").contains("downstream");
    }

    /** A dashboard serving a materialized view of the query, last refreshed at refreshedAt. */
    private void dashboard(String sql, String refreshedAt) {
        LakeDashboard d = new LakeDashboard();
        d.setMetadata(new ObjectMetaBuilder().withName("sales").withNamespace(NS).build());
        d.setSpec(new LakeDashboardSpec());
        d.getSpec().tables = List.of("dwd.orders", "ads.daily");
        LakeDashboardSpec.Query q = new LakeDashboardSpec.Query();
        q.name = "totals";
        q.serve = "materialized";
        q.sql = sql;
        d.getSpec().queries = List.of(q);
        kube.resources(LakeDashboard.class).inNamespace(NS).withName("sales").delete();
        LakeDashboard created = kube.resource(d).create();
        created.setStatus(new LakeDashboardStatus());
        LakeDashboardStatus.QueryState s = new LakeDashboardStatus.QueryState();
        s.name = "totals";
        s.serve = "materialized";
        s.refreshedAt = refreshedAt;
        created.getStatus().queries.add(s);
        kube.resource(created).updateStatus();
    }

    @Test
    void aBackfillIsDoneOnlyOnceTheDashboardCopiesOfWhatItChangedRefreshed() {
        LakeTable wide = lakeTable("orders-wide", "dwd", "orders", List.of("ods.orders"));
        job(wide, true);
        // A materialized view of the backfilled table, refreshed before the backfill.
        dashboard("SELECT COUNT(*) FROM dwd.orders", now.get().toString());
        LakeTask task = round(create("backfill-orders", "backfill", "dwd", "orders"));
        acknowledge("orders-wide");
        task = round(task);
        finish(task.getStatus().run, 0, "{\"snapshotAfter\":9,\"detail\":\"upserted 3 rows\"}");
        task = round(task);
        acknowledge("orders-wide");
        task = round(task);
        assertThat(task.getStatus().phase).isEqualTo("Converging");
        assertThat(task.getStatus().waitingFor)
                .singleElement()
                .satisfies(w -> assertThat(w).startsWith("sales/totals (refreshed from"));
        // A refresh started after the fence was released holds the backfill.
        dashboard("SELECT COUNT(*) FROM dwd.orders", now.get().plusSeconds(1).toString());
        now.set(now.get().plusSeconds(2));
        assertThat(round(task).getStatus().phase).isEqualTo("Succeeded");
    }

    @Test
    void aReaderOfTwoCarryingTablesPassesOnlyOnceItReadBoth() {
        LakeTable wide = lakeTable("orders-wide", "dwd", "orders", List.of("ods.orders"));
        // daily reads the backfilled table; joined reads it and daily.
        lakeTable("orders-daily", "ads", "daily", List.of("dwd.orders"));
        lakeTable("joined", "top", "joined", List.of("dwd.orders", "ads.daily"));
        job(wide, true);
        LakeTask task = round(create("backfill-orders", "backfill", "dwd", "orders"));
        acknowledge("orders-wide");
        task = round(task);
        finish(task.getStatus().run, 0, "{\"snapshotAfter\":9,\"detail\":\"upserted 3 rows\"}");
        task = round(task);
        acknowledge("orders-wide");
        reads("orders-daily", "dwd.orders", 10);
        reads("joined", "dwd.orders", 10);
        task = round(task);
        // joined is past dwd.orders, but ads.daily's commit is not known: it has not passed.
        assertThat(task.getStatus().frontier).doesNotContainKey("top.joined");
        snapshots.put("ads.daily", 20L);
        now.set(now.get().plus(LakeTaskReconciler.COMMIT_GRACE));
        task = round(task);
        assertThat(task.getStatus().waitingFor)
                .anyMatch(w -> w.startsWith("joined (reads ads.daily from 3"));
        reads("joined", "ads.daily", 21);
        task = round(task);
        assertThat(task.getStatus().frontier.get("top.joined").via)
                .containsExactly("ads.daily", "dwd.orders");
        now.set(now.get().plus(LakeTaskReconciler.COMMIT_GRACE));
        assertThat(round(task).getStatus().phase).isEqualTo("Succeeded");
    }

    @Test
    void aBackfillOfATableWithChecksMayWriteItsQuarantine() throws Exception {
        LakeTable wide = lakeTable("orders-wide", "dwd", "orders", List.of("ods.orders"));
        wide.getSpec().quality = new LakeTableSpec.Quality();
        wide.getSpec().quality.notNull = List.of("customer_id");
        wide = kube.resource(wide).update();
        owners.put("dwd.orders_quarantine", owners.get("dwd.orders"));
        job(wide, false);
        round(create("backfill-orders", "backfill", "dwd", "orders"));
        // The rows that fail the checks are rewritten to the quarantine.
        assertThat(grants)
                .anyMatch(
                        g ->
                                g.contains("\"WRITE\"")
                                        && g.contains("\"dwd\"")
                                        && g.contains("orders_quarantine"));
        // A task on the quarantine waits for it.
        LakeTask expire =
                round(create("expire-quarantine", "expireSnapshots", "dwd", "orders_quarantine"));
        assertThat(expire.getStatus().phase).isEqualTo("Pending");
        assertThat(expire.getStatus().message).contains("backfill-orders");
    }

    @Test
    void aCompactionOfAQuarantineFencesTheJobThatWritesIt() {
        LakeTable wide = lakeTable("orders-wide", "dwd", "orders", List.of("ods.orders"));
        wide.getSpec().quality = new LakeTableSpec.Quality();
        wide.getSpec().quality.notNull = List.of("customer_id");
        wide = kube.resource(wide).update();
        owners.put("dwd.orders_quarantine", owners.get("dwd.orders"));
        job(wide, false);
        LakeTask task = round(create("compact-quarantine", "compact", "dwd", "orders_quarantine"));
        assertThat(task.getStatus().phase).isEqualTo("Fencing");
        assertThat(Fence.holder(lakeTable("orders-wide"))).startsWith("compact-quarantine/");
    }

    @Test
    void refusesToBackfillAStatefulTableAndGivesUpOnAFenceThatDoesNotHold() {
        LakeTable daily = lakeTable("orders-daily", "ads", "daily", List.of("dwd.orders"));
        daily.getStatus().validation.stateful = true;
        kube.resource(daily).update();
        LakeTask task = round(create("backfill-daily", "backfill", "ads", "daily"));
        assertThat(task.getStatus().conditions)
                .anyMatch(c -> c.type.equals("Finished") && c.reason.equals("StatefulQuery"));

        LakeTable wide = lakeTable("orders-wide", "dwd", "orders", List.of("ods.orders"));
        job(wide, false);
        task = round(create("backfill-orders", "backfill", "dwd", "orders"));
        assertThat(task.getStatus().phase).isEqualTo("Fencing");
        now.set(now.get().plus(LakeTaskReconciler.FENCE_TIMEOUT).plusSeconds(1));
        task = round(task);
        assertThat(task.getStatus().conditions)
                .anyMatch(c -> c.type.equals("Finished") && c.reason.equals("FenceTimeout"));
        assertThat(Fence.holder(lakeTable("orders-wide"))).isNull();
    }

    @Test
    void aBackfillHoldsWhatItReadsAndOneWhoseReadersDoNotConvergeNeedsReconciliation() {
        LakeTable wide = lakeTable("orders-wide", "dwd", "orders", List.of("ods.orders"));
        lakeTable("orders-daily", "ads", "daily", List.of("dwd.orders"));
        job(wide, true);
        LakeTask task = round(create("backfill-orders", "backfill", "dwd", "orders"));
        // An expiry of what the backfill reads waits for it.
        LakeTask expire = round(create("expire-ods", "expireSnapshots", "ods", "orders"));
        assertThat(expire.getStatus().phase).isEqualTo("Pending");
        assertThat(expire.getStatus().message).contains("backfill-orders");
        acknowledge("orders-wide");
        task = round(task);
        finish(task.getStatus().run, 0, "{\"snapshotAfter\":9,\"detail\":\"upserted 3 rows\"}");
        task = round(task);
        assertThat(task.getStatus().phase).isEqualTo("Converging");
        now.set(now.get().plus(Duration.ofMinutes(31)));
        task = round(task);
        assertThat(task.getStatus().phase).isEqualTo("Failed");
        assertThat(StatusCondition.message(task.getStatus().conditions, "Finished"))
                .contains("orders-daily");
        assertThat(task.getStatus().conditions)
                .anyMatch(c -> c.type.equals("Finished") && c.reason.equals("NeedsReconciliation"));
    }

    @Test
    void aBackfillOutOfAttemptsLiftsTheFence() {
        LakeTable wide = lakeTable("orders-wide", "dwd", "orders", List.of("ods.orders"));
        job(wide, true);
        LakeTask task = create("backfill-orders", "backfill", "dwd", "orders");
        task.getSpec().retries = 0;
        task = round(kube.resource(task).update());
        acknowledge("orders-wide");
        task = round(task);
        finish(task.getStatus().run, 1, "");
        task = round(task);
        assertThat(task.getStatus().phase).isEqualTo("Failed");
        assertThat(Fence.holder(lakeTable("orders-wide"))).isNull();
        assertThat(task.getStatus().fence.releasedAt).isNotNull();
    }

    @Test
    void deletingAFencingTaskStopsItsRunBeforeLiftingTheFence() {
        LakeTable wide = lakeTable("orders-wide", "dwd", "orders", List.of("ods.orders"));
        job(wide, true);
        LakeTask task = round(create("backfill-orders", "backfill", "dwd", "orders"));
        acknowledge("orders-wide");
        task = round(task);
        assertThat(task.getStatus().phase).isEqualTo("Running");
        Pod pod = running(task.getStatus().run);
        assertThat(reconciler.cleanup(task, null).isRemoveFinalizer()).isFalse();
        assertThat(Fence.holder(lakeTable("orders-wide"))).startsWith("backfill-orders/");
        kube.pods().inNamespace(NS).withName(pod.getMetadata().getName()).delete();
        assertThat(reconciler.cleanup(task, null).isRemoveFinalizer()).isTrue();
        assertThat(Fence.holder(lakeTable("orders-wide"))).isNull();
    }

    @Test
    void anArgumentCarriesTheTypesParameters() throws Exception {
        LakeTaskSpec spec = new LakeTaskSpec();
        spec.type = "createTag";
        spec.table = new LakeTaskSpec.Table();
        spec.table.database = "ods";
        spec.table.table = "orders";
        spec.createTag = new LakeTaskSpec.CreateTag();
        spec.createTag.name = "eod";
        spec.createTag.timeRetained = "12h";
        ObjectNode arg = (ObjectNode) JSON.readTree(LakeTaskReconciler.argument(spec, null));
        assertThat(arg.path("tag").asText()).isEqualTo("eod");
        assertThat(arg.path("timeRetained").asText()).isEqualTo("12 h");
        spec.type = "expireSnapshots";
        arg = (ObjectNode) JSON.readTree(LakeTaskReconciler.argument(spec, null));
        assertThat(arg.path("retainMin").asInt()).isEqualTo(10);
        assertThat(arg.path("olderThanMillis").asLong()).isEqualTo(3_600_000L);
        spec.type = "compact";
        spec.compact = new LakeTaskSpec.Compact();
        spec.compact.partitions = List.of("dt=2026-10-14");
        arg = (ObjectNode) JSON.readTree(LakeTaskReconciler.argument(spec, null));
        assertThat(arg.path("partitions").get(0).asText()).isEqualTo("dt=2026-10-14");
        // The job decides on the manifests unless the task does.
        assertThat(arg.has("manifests")).isFalse();
        spec.compact.manifests = true;
        arg = (ObjectNode) JSON.readTree(LakeTaskReconciler.argument(spec, null));
        assertThat(arg.path("manifests").asBoolean()).isTrue();
        assertThat(Durations.millis("2d", "1h")).isEqualTo(172_800_000L);
        spec.createTag = null;
        spec.type = "createTag";
        assertThat(LakeTaskReconciler.validate(spec)).contains("names its tag");
    }
}
