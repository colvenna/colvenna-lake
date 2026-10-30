package com.colvenna.lake.operator.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.OperatorSettings;
import com.colvenna.lake.operator.catalog.CatalogClient;
import com.colvenna.lake.operator.crd.Governance;
import com.colvenna.lake.operator.crd.LakeDashboard;
import com.colvenna.lake.operator.crd.LakeDashboardSpec;
import com.colvenna.lake.operator.crd.LakeDashboardStatus;
import com.colvenna.lake.operator.crd.LakeErasure;
import com.colvenna.lake.operator.crd.LakeErasureSpec;
import com.colvenna.lake.operator.crd.LakeErasureStatus;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceSpec;
import com.colvenna.lake.operator.crd.LakeSourceStatus;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.crd.LakeTableSpec;
import com.colvenna.lake.operator.crd.LakeTableStatus;
import com.colvenna.lake.operator.crd.LakeTask;
import com.colvenna.lake.operator.crd.LakeTaskSpec;
import com.colvenna.lake.operator.crd.LakeTaskStatus;
import com.colvenna.lake.operator.source.SourceModel;
import com.colvenna.lake.operator.source.SourceProbe;
import com.colvenna.lake.operator.table.TableJobs;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.apache.flink.kubernetes.operator.api.spec.FlinkDeploymentSpec;
import org.apache.flink.kubernetes.operator.api.status.FlinkDeploymentStatus;
import org.apache.flink.kubernetes.operator.api.status.JobStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An erasure from acceptance to physical completion against a Kubernetes mock, a fake source and a
 * fake catalog, the probe's results given: the subject never leaves its Secret, each copy is done
 * only with evidence, and a deadline passed is reported with what is left, and stays reported.
 */
@EnableKubernetesMockClient(crud = true, https = false)
class LakeErasureReconcilerTest {
    private static final String NS = "colvenna-data";
    KubernetesClient kube;
    private MockWebServer catalog;
    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-10-15T00:00:00Z"));
    private final Map<String, Long> sourceRows = new HashMap<>();
    private LakeErasureReconciler reconciler;

    /** Whether the platform's Doris is installed (its query log a copy). */
    private boolean doris;

    private String sourceFailure;
    private List<String> odsTables = List.of("orders");

    @BeforeEach
    void start() throws Exception {
        catalog = new MockWebServer();
        catalog.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest r) {
                        String call = r.getMethod() + " " + r.getPath();
                        if (call.equals("GET /colvenna/admin/v1/principals")) {
                            return new MockResponse().setBody("{\"principals\":[]}");
                        }
                        if (call.equals("POST /colvenna/admin/v1/principals")) {
                            return new MockResponse().setBody("{\"name\":\"x\"}");
                        }
                        if (call.matches("POST /colvenna/admin/v1/principals/.*/tokens")) {
                            return new MockResponse()
                                    .setBody(
                                            "{\"id\":\"t1\",\"token\":\"colvenna_secret\",\"expiresAt\":"
                                                    + "\"2026-11-14T00:00:00Z\"}");
                        }
                        if (call.matches("GET /colvenna/admin/v1/principals/.*/tokens")) {
                            return new MockResponse().setBody("{\"tokens\":[{\"id\":\"t1\"}]}");
                        }
                        if (call.equals("GET /v1/colvenna/databases/ods/tables")) {
                            return new MockResponse()
                                    .setBody(
                                            "{\"tables\":[\""
                                                    + String.join("\",\"", odsTables)
                                                    + "\"]}");
                        }
                        if (call.startsWith("GET /colvenna/admin/v1/grants")) {
                            return new MockResponse().setBody("{\"grants\":[]}");
                        }
                        if (call.equals("POST /colvenna/admin/v1/grants")) {
                            return new MockResponse()
                                    .setBody(
                                            r.getBody()
                                                    .readUtf8()
                                                    .replaceFirst("\\{", "{\"id\":\"g1\","));
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
        SourceProbe source =
                new SourceProbe() {
                    @Override
                    public SourceModel.Inventory inventory(Target target) {
                        return new SourceModel.Inventory(
                                List.of(),
                                Map.of(
                                        "orders",
                                        new SourceModel.Table(
                                                "orders",
                                                List.of(),
                                                List.of("id"),
                                                List.of(),
                                                List.of(),
                                                true,
                                                null)));
                    }

                    @Override
                    public Map<String, Long> versions(
                            Target target,
                            SourceModel.Table table,
                            List<String> keyColumns,
                            String versionColumn,
                            long limit) {
                        return Map.of();
                    }

                    @Override
                    public Map<String, Long> versionsOf(
                            Target target,
                            SourceModel.Table table,
                            List<String> keyColumns,
                            String versionColumn,
                            List<List<String>> keys) {
                        assertThat(keyColumns).containsExactly("customer_id");
                        assertThat(keys).containsExactly(List.of("42"));
                        if (sourceFailure != null) {
                            throw new IllegalStateException(sourceFailure);
                        }
                        return sourceRows;
                    }
                };
        reconciler =
                reconciler(settings, source, "bucket b: versioning never enabled, no replication");
    }

    private OperatorSettings settings;
    private SourceProbe source;

    private LakeErasureReconciler reconciler(
            OperatorSettings settings, SourceProbe source, String storage) throws Exception {
        this.settings = settings;
        this.source = source;
        return new LakeErasureReconciler(
                settings,
                kube,
                new CatalogClient(
                        URI.create(catalog.url("/").toString()),
                        () -> "admin",
                        SSLContext.getDefault()),
                Map.of("mysql", source),
                storage,
                doris,
                new com.colvenna.lake.operator.metrics.Metrics(),
                now::get);
    }

    @AfterEach
    void stop() throws Exception {
        catalog.shutdown();
    }

    private void declare() {
        kube.resource(
                        new SecretBuilder()
                                .withNewMetadata()
                                .withName("mysql-cdc")
                                .withNamespace(NS)
                                .endMetadata()
                                .withStringData(Map.of("username", "u", "password", "p"))
                                .build())
                .create();
        kube.resource(
                        new SecretBuilder()
                                .withNewMetadata()
                                .withName("erase-1")
                                .withNamespace(NS)
                                .endMetadata()
                                .withStringData(Map.of("values", "42\n"))
                                .build())
                .create();
        LakeSource s = new LakeSource();
        s.setMetadata(
                new ObjectMetaBuilder()
                        .withName("shop")
                        .withNamespace(NS)
                        .withUid(UUID.randomUUID().toString())
                        .build());
        LakeSourceSpec spec = new LakeSourceSpec();
        spec.type = "mysql";
        spec.connection = new LakeSourceSpec.Connection();
        spec.connection.host = "db";
        spec.connection.database = "shop";
        spec.connection.credentialsSecret = new LakeSourceSpec.SecretRef();
        spec.connection.credentialsSecret.name = "mysql-cdc";
        spec.target = new LakeSourceSpec.Target();
        spec.target.database = "ods";
        spec.governance = new Governance();
        spec.governance.owner = "shop-team";
        spec.governance.level = "L3";
        spec.governance.categories = List.of("personal");
        spec.governance.subjectKeys = Map.of("orders", List.of("customer_id"));
        s.setSpec(spec);
        LakeSourceStatus status = new LakeSourceStatus();
        LakeSourceStatus.SourceTable t = new LakeSourceStatus.SourceTable();
        t.name = "orders";
        t.target = "orders";
        t.state = "Ready";
        status.tables.add(t);
        LakeSourceStatus.JobState j = new LakeSourceStatus.JobState();
        j.deployment = "shop-cdc";
        status.jobs.add(j);
        s.setStatus(status);
        kube.resource(s).create();
        FlinkDeployment fd = new FlinkDeployment();
        fd.setMetadata(new ObjectMetaBuilder().withName("shop-cdc").withNamespace(NS).build());
        fd.setSpec(new FlinkDeploymentSpec());
        fd.getSpec()
                .setFlinkConfiguration(
                        Map.of(
                                "state.checkpoints.dir",
                                "s3://b/flink-state/colvenna-data/shop-cdc-1/checkpoints"));
        FlinkDeploymentStatus fs = new FlinkDeploymentStatus();
        JobStatus js = new JobStatus();
        js.setJobId("j1");
        js.setState(org.apache.flink.api.common.JobStatus.RUNNING);
        fs.setJobStatus(js);
        fd.setStatus(fs);
        kube.resource(fd).create();
    }

    private LakeErasure erasure() {
        LakeErasure e = new LakeErasure();
        e.setMetadata(
                new ObjectMetaBuilder()
                        .withName("erase-1")
                        .withNamespace(NS)
                        .withUid(UUID.randomUUID().toString())
                        .withGeneration(1L)
                        .build());
        e.setSpec(new LakeErasureSpec());
        e.getSpec().subjectSecret = new LakeErasureSpec.SubjectSecret();
        e.getSpec().subjectSecret.name = "erase-1";
        e.getSpec().deadlines = new LakeErasureSpec.Deadlines();
        e.getSpec().deadlines.logical = "1h";
        e.getSpec().deadlines.physical = "1d";
        return kube.resource(e).create();
    }

    private LakeErasure round() {
        LakeErasure current =
                kube.resources(LakeErasure.class).inNamespace(NS).withName("erase-1").get();
        reconciler.reconcile(current, null);
        LakeErasure stored =
                kube.resources(LakeErasure.class).inNamespace(NS).withName("erase-1").get();
        stored.setStatus(current.getStatus());
        return kube.resource(stored).update();
    }

    private static LakeErasureStatus.Copy find(LakeErasure e, String kind, String name) {
        return e.getStatus().copies.stream()
                .filter(c -> c.kind.equals(kind) && c.name.equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static LakeErasureStatus.Copy copy(LakeErasure e, String kind) {
        return e.getStatus().copies.stream()
                .filter(c -> c.kind.equals(kind))
                .findFirst()
                .orElseThrow();
    }

    /** The probe's latest run ends now with this result. */
    private void probed(String result) {
        Job job =
                kube.batch().v1().jobs().inNamespace(NS).list().getItems().stream()
                        .filter(j -> j.getMetadata().getName().startsWith("erase-1-probe-"))
                        .max(
                                Comparator.comparingInt(
                                        j ->
                                                Integer.parseInt(
                                                        j.getMetadata()
                                                                .getName()
                                                                .substring(
                                                                        "erase-1-probe-"
                                                                                .length()))))
                        .orElseThrow();
        kube.resource(
                        new PodBuilder()
                                .withNewMetadata()
                                .withName(job.getMetadata().getName() + "-x")
                                .withNamespace(NS)
                                .withLabels(Map.of("job-name", job.getMetadata().getName()))
                                .endMetadata()
                                .withNewStatus()
                                .withPhase("Succeeded")
                                .withContainerStatuses(
                                        new ContainerStatusBuilder()
                                                .withName("probe")
                                                .withNewState()
                                                .withNewTerminated()
                                                .withExitCode(0)
                                                .withFinishedAt(now.get().toString())
                                                .withMessage(result)
                                                .endTerminated()
                                                .endState()
                                                .build())
                                .endStatus()
                                .build())
                .createOr(p -> p.update());
    }

    private void finished(String task) {
        LakeTask t = kube.resources(LakeTask.class).inNamespace(NS).withName(task).get();
        t.setStatus(new LakeTaskStatus());
        t.getStatus().phase = "Succeeded";
        t.getStatus().message = "removed 0 0";
        t.getStatus().finishedAt = now.get().toString();
        kube.resource(t).update();
    }

    /** A probe result for ods.orders, open for the state list to follow. */
    private static String table(long snapshot, long millis, String rest) {
        return "{\"tables\":[{\"table\":\"ods.orders\",\"snapshot\":"
                + snapshot
                + ",\"snapshotMillis\":"
                + millis
                + ",\"fits\":true,"
                + rest
                + "}]";
    }

    @Test
    void anErasureIsDoneOnlyWithEvidenceAndReportsWhatIsLeftPastADeadline() throws Exception {
        declare();
        erasure();
        sourceRows.put("k", 1L);
        LakeErasure e = round();
        assertThat(e.getStatus().phase).isEqualTo("Accepted");
        assertThat(e.getStatus().copies)
                .extracting(c -> c.kind + " " + c.state)
                .contains(
                        "SourceDatabase Pending",
                        "Rows Pending",
                        "History Pending",
                        "Files Pending",
                        "FlinkState Pending",
                        "Storage Done");
        // Done on the installer's record of the bucket, which is its evidence.
        assertThat(copy(e, "Storage").evidence).contains("versioning never enabled");
        assertThat(copy(e, "Storage").doneAt).isNotNull();
        assertThat(copy(e, "FlinkState").location)
                .isEqualTo("s3://b/flink-state/colvenna-data/shop-cdc-1");
        assertThat(copy(e, "Rows").owner).isEqualTo("shop-team");
        assertThat(copy(e, "SourceDatabase").message).contains("still holds rows");
        // The probe reads the values from the Secret's file: they are in no argument. It runs
        // with the jobs' identity, which lists their state.
        Job probe = kube.batch().v1().jobs().inNamespace(NS).list().getItems().get(0);
        var pod = probe.getSpec().getTemplate().getSpec();
        var container = pod.getContainers().get(0);
        assertThat(String.join(" ", container.getCommand()))
                .contains("ods.orders")
                .doesNotContain("42");
        assertThat(container.getEnv()).anyMatch(v -> v.getName().equals("COLVENNA_SUBJECT_FILE"));
        assertThat(pod.getServiceAccountName()).isEqualTo("colvenna-flink");
        assertThat(pod.getVolumes())
                .anyMatch(
                        v ->
                                v.getSecret() != null
                                        && "erase-1".equals(v.getSecret().getSecretName()));

        // The lake holds the subject's rows too: a positive count, the check that the key matches.
        probed(
                table(
                                9,
                                now.get().toEpochMilli(),
                                "\"earliest\":3,\"rows\":2,\"records\":2,\"tags\":{},"
                                        + "\"consumers\":{},\"branches\":{}")
                        + "}");
        e = round();
        assertThat(copy(e, "Rows").message)
                .contains("2 rows")
                .contains("the source still has them");
        assertThat(copy(e, "Rows").seen).isEqualTo(2L);

        // The source's owner deletes; the lake has the deletes, its files and history not yet. A
        // compaction the operator created before it stopped is taken up, not created again.
        now.set(now.get().plus(Duration.ofMinutes(3)));
        sourceRows.clear();
        round();
        long clean = now.get().toEpochMilli();
        LakeTask stale = new LakeTask();
        stale.getMetadata().setName("erase-1-compact-ods-orders-files-1");
        stale.getMetadata().setNamespace(NS);
        stale.getMetadata()
                .setOwnerReferences(
                        List.of(
                                new OwnerReferenceBuilder()
                                        .withApiVersion("colvenna-lake.io/v1alpha1")
                                        .withKind("LakeErasure")
                                        .withName("erase-1")
                                        .withUid(e.getMetadata().getUid())
                                        .build()));
        stale.setSpec(new LakeTaskSpec());
        kube.resource(stale).create();
        probed(
                table(
                                10,
                                clean,
                                "\"earliest\":3,\"rows\":0,\"records\":2,\"icebergMentions\":3,"
                                        + "\"tags\":{\"eod\":5},"
                                        + "\"consumers\":{\"dwd\":11},\"branches\":{}")
                        + "}");
        e = round();
        assertThat(copy(e, "SourceDatabase").state).isEqualTo("Done");
        assertThat(copy(e, "Rows").state).isEqualTo("Done");
        assertThat(copy(e, "Rows").cleanSnapshot).isEqualTo(10L);
        assertThat(copy(e, "History").message)
                .contains("snapshots 3 to 9")
                .contains("not searched yet")
                .contains("tag eod");
        assertThat(copy(e, "Files").message)
                .contains("2 records")
                .contains("3 in its Iceberg metadata")
                .contains("compacting");
        assertThat(e.getStatus().tasks)
                .contains("erase-1-compact-ods-orders-files-1")
                .anyMatch(t -> t.contains("deletetag"));

        // The next run searches the changes kept from the clean snapshot, and lists the job's
        // state; the expiry of what precedes the clean snapshot starts.
        now.set(now.get().plus(Duration.ofMinutes(3)));
        e = round();
        Job second = kube.batch().v1().jobs().inNamespace(NS).withName("erase-1-probe-3").get();
        String argument =
                String.join(
                        " ",
                        second.getSpec()
                                .getTemplate()
                                .getSpec()
                                .getContainers()
                                .get(0)
                                .getCommand());
        assertThat(argument)
                .contains("\"clean\":10")
                .contains("\"location\":\"s3://b/flink-state/colvenna-data/shop-cdc-1\"")
                .contains("\"job\":\"j1\"")
                .contains("\"root\":\"s3://bucket/flink-state/colvenna-data\"");
        assertThat(e.getStatus().tasks).anyMatch(t -> t.contains("expiresnapshots"));
        // Compacted, expired, untagged: no read path reaches the subject. The state is listed.
        probed(
                table(
                                13,
                                now.get().toEpochMilli(),
                                "\"earliest\":11,\"rows\":0,\"records\":0,\"changes\":0,"
                                        + "\"tags\":{},\"consumers\":{},\"branches\":{}")
                        + ",\"state\":[{\"name\":\"shop-cdc\",\"before\":9999999999999,\"objects\":4,\"older\":0,"
                        + "\"left\":[],\"disposed\":[\"checkpoints/j0\"],\"disposedCount\":1}],"
                        + "\"orphans\":{\"before\":9999999999999,\"left\":[],\"leftCount\":0,\"disposed\":[\"old-cdc-0badc0de\"],"
                        + "\"disposedCount\":1}}");
        e = round();
        assertThat(copy(e, "History").state).isEqualTo("Done");
        assertThat(copy(e, "FlinkState").state).isEqualTo("Done");
        assertThat(copy(e, "FlinkState").evidence).contains("disposed of 1");
        assertThat(find(e, "FlinkState", LakeErasureReconciler.ORPHANED_STATE).state)
                .isEqualTo("Done");
        assertThat(e.getStatus().phase).isEqualTo("LogicallyErased");
        assertThat(e.getStatus().logicallyErasedAt).isNotNull();
        assertThat(copy(e, "Files").compactedSnapshot).isEqualTo(13L);
        assertThat(copy(e, "Files").message).contains("snapshots 11 to 12");

        // Snapshots before the compacted one gone; orphans younger than a day are not cleaned
        // yet: the files are not reported done.
        now.set(now.get().plus(Duration.ofMinutes(3)));
        round();
        probed(
                table(
                                14,
                                now.get().toEpochMilli(),
                                "\"earliest\":13,\"rows\":0,\"records\":0,\"changes\":0,"
                                        + "\"tags\":{},\"consumers\":{},\"branches\":{}")
                        + "}");
        e = round();
        assertThat(copy(e, "Files").state).isEqualTo("Pending");
        assertThat(copy(e, "Files").message).contains("orphan-file cleaning at");

        // A day later the physical deadline passed, with the cleaning still running.
        now.set(now.get().plus(Duration.ofDays(1)).plusSeconds(1));
        e = round();
        assertThat(e.getStatus().phase).isEqualTo("SlaBreach");
        assertThat(e.getStatus().message)
                .contains("physical deadline")
                .contains("Files ods.orders");
        assertThat(e.getStatus().breaches).hasSize(1);
        String orphans =
                e.getStatus().tasks.stream()
                        .filter(t -> t.contains("removeorphanfiles"))
                        .findFirst()
                        .orElseThrow();
        finished(orphans);
        e = round();
        assertThat(copy(e, "Files").state).isEqualTo("Done");
        assertThat(copy(e, "Files").evidence).contains("removeorphanfiles").contains("removed");
        assertThat(e.getStatus().phase).isEqualTo("PhysicallyErased");
        assertThat(e.getStatus().physicallyErasedAt).isNotNull();
        // Late is still late.
        assertThat(e.getStatus().breaches).hasSize(1);
        assertThat(e.getStatus().message).contains("late");
        assertThat(e.getStatus().conditions)
                .anyMatch(c -> c.type.equals("WithinDeadlines") && c.status.equals("False"));
    }

    @Test
    void whatCannotBeFoundOrCheckedHoldsTheErasureForItsOwner() throws Exception {
        reconciler = reconciler(settings, source, null);
        declare();
        // A Kafka event source with personal data, and a table beside the declared ones.
        LakeSource events = new LakeSource();
        events.setMetadata(
                new ObjectMetaBuilder()
                        .withName("clicks")
                        .withNamespace(NS)
                        .withUid(UUID.randomUUID().toString())
                        .build());
        LakeSourceSpec spec = new LakeSourceSpec();
        spec.type = "kafka";
        spec.target = new LakeSourceSpec.Target();
        spec.target.database = "events";
        spec.governance = new Governance();
        spec.governance.owner = "web-team";
        spec.governance.level = "L3";
        spec.governance.categories = List.of("personal");
        spec.governance.subjectKeys = Map.of("clicks", List.of("user_id"));
        events.setSpec(spec);
        LakeSourceStatus status = new LakeSourceStatus();
        LakeSourceStatus.SourceTable t = new LakeSourceStatus.SourceTable();
        t.name = "clicks";
        t.target = "clicks";
        t.state = "Failed";
        status.tables.add(t);
        events.setStatus(status);
        kube.resource(events).create();
        odsTables = List.of("orders", "orders_copy");
        // A declaration with a retention and no owner or level classifies nothing: its tables
        // count as personal data, and without a subject key they hold the erasure.
        LakeSource notes = new LakeSource();
        notes.setMetadata(
                new ObjectMetaBuilder()
                        .withName("notes")
                        .withNamespace(NS)
                        .withUid(UUID.randomUUID().toString())
                        .build());
        LakeSourceSpec notesSpec = new LakeSourceSpec();
        notesSpec.type = "mysql";
        notesSpec.target = new LakeSourceSpec.Target();
        notesSpec.target.database = "crm";
        notesSpec.governance = new Governance();
        notesSpec.governance.retention = "24h";
        notes.setSpec(notesSpec);
        LakeSourceStatus notesStatus = new LakeSourceStatus();
        LakeSourceStatus.SourceTable note = new LakeSourceStatus.SourceTable();
        note.name = "notes";
        note.target = "notes";
        note.state = "Ready";
        notesStatus.tables.add(note);
        notes.setStatus(notesStatus);
        kube.resource(notes).create();
        // The Secret comes after the erasure: it waits for it.
        kube.secrets().inNamespace(NS).withName("erase-1").delete();
        erasure();
        LakeErasure e = round();
        assertThat(e.getStatus().phase).isNull();
        assertThat(e.getStatus().message).contains("waiting for Secret erase-1");
        kube.resource(
                        new SecretBuilder()
                                .withNewMetadata()
                                .withName("erase-1")
                                .withNamespace(NS)
                                .endMetadata()
                                .withStringData(Map.of("values", "42\n"))
                                .build())
                .create();
        // A driver's message repeating the value never reaches the status.
        sourceFailure = "cannot convert '42' to the column's type";
        e = round();
        assertThat(e.getStatus().copies)
                .extracting(c -> c.kind + " " + c.name + " " + c.state)
                .contains(
                        "Rows events.clicks Blocked",
                        "Rows crm.notes Blocked",
                        "Rows ods.orders_copy Blocked",
                        "Storage warehouse PendingEvidence");
        assertThat(copy(e, "SourceDatabase").message).contains("<subject>").doesNotContain("42");
        assertThat(e.getStatus().phase).isEqualTo("Blocked");
        assertThat(e.getStatus().message)
                .contains("web-team")
                .contains("for their owners to review by " + e.getStatus().logicalDeadline);
        String reviewBy = e.getStatus().logicalDeadline;
        assertThat(e.getStatus().copies)
                .filteredOn(c -> c.state.equals("Blocked"))
                .allMatch(c -> reviewBy.equals(c.reviewBy));
        assertThat(kube.v1().events().inNamespace(NS).list().getItems())
                .anyMatch(ev -> ev.getReason().equals("ErasureBlocked"))
                // The driver quoted the value; uids and times may hold the digits by chance.
                .noneMatch(ev -> ev.getMessage().contains("'42'"));
    }

    @Test
    void deadlinesPastMZerosAreRefused() throws Exception {
        declare();
        LakeErasure e = erasure();
        e.getSpec().deadlines.logical = "2d";
        kube.resource(e).update();
        e = round();
        assertThat(e.getStatus().phase).isEqualTo("Failed");
        assertThat(e.getStatus().message).contains("at most 24h");
    }

    /** A probe result for ods.orders after the delete, with the state and orphans listed. */
    private String clean(long snapshot, String rows) {
        return table(
                        snapshot,
                        now.get().toEpochMilli(),
                        rows
                                + ",\"earliest\":"
                                + snapshot
                                + ",\"records\":0,\"changes\":0,\"tags\":{},\"consumers\":{},"
                                + "\"branches\":{}")
                + ",\"state\":[{\"name\":\"shop-cdc\",\"before\":9999999999999,\"objects\":1,\"older\":0,\"left\":[],"
                + "\"disposed\":[],\"disposedCount\":0}],\"orphans\":{\"before\":9999999999999,\"left\":[],"
                + "\"leftCount\":0,\"disposed\":[],\"disposedCount\":0}}";
    }

    @Test
    void aSubjectWhoseRowsComeBackUndoesWhatWasDone() throws Exception {
        declare();
        erasure();
        round();
        probed(clean(10, "\"rows\":0"));
        assertThat(copy(round(), "Rows").state).isEqualTo("Done");
        now.set(now.get().plus(Duration.ofMinutes(3)));
        round();
        probed(clean(10, "\"rows\":0"));
        LakeErasure e = round();
        assertThat(e.getStatus().phase).isEqualTo("LogicallyErased");
        assertThat(copy(e, "FlinkState").state).isEqualTo("Done");

        now.set(now.get().plus(Duration.ofMinutes(3)));
        round();
        probed(clean(12, "\"rows\":1"));
        e = round();
        assertThat(copy(e, "Rows").state).isEqualTo("Pending");
        assertThat(copy(e, "History").state).isEqualTo("Pending");
        assertThat(copy(e, "FlinkState").state).isEqualTo("Pending");
        assertThat(e.getStatus().logicallyErasedAt).isNull();
        assertThat(e.getStatus().phase).isEqualTo("Accepted");
        assertThat(kube.v1().events().inNamespace(NS).list().getItems())
                .anyMatch(ev -> ev.getReason().equals("SubjectReturned"));
        // The source, done long since, gets the subject's rows again: it is checked again too.
        sourceRows.put("k", 1L);
        e = round();
        assertThat(copy(e, "SourceDatabase").state).isEqualTo("Pending");
        assertThat(copy(e, "SourceDatabase").message).contains("again");
    }

    @Test
    void whatIsDoneStaysDoneUnlessTheSubjectOrItsTableComesBack() throws Exception {
        declare();
        erasure();
        round();
        probed(
                table(
                                10,
                                now.get().toEpochMilli(),
                                "\"rows\":0,\"earliest\":3,\"records\":0,"
                                        + "\"tags\":{},\"consumers\":{},\"branches\":{}")
                        + "}");
        LakeErasure e = round();
        assertThat(copy(e, "Rows").state).isEqualTo("Done");
        assertThat(copy(e, "SourceDatabase").state).isEqualTo("Done");
        assertThat(copy(e, "History").state).isEqualTo("Pending");

        // Its history not yet gone, the subject's rows come back after the clean snapshot, and go.
        now.set(now.get().plus(Duration.ofMinutes(3)));
        round();
        probed(
                table(
                                12,
                                now.get().toEpochMilli(),
                                "\"rows\":0,\"earliest\":3,\"records\":0,"
                                        + "\"changes\":3,\"added\":1,\"tags\":{},\"consumers\":{},\"branches\":{}")
                        + "}");
        e = round();
        // Gone again by now: the erasure starts over from this snapshot.
        assertThat(copy(e, "Rows").cleanSnapshot).isEqualTo(12L);
        assertThat(kube.v1().events().inNamespace(NS).list().getItems())
                .anyMatch(ev -> ev.getReason().equals("SubjectReturned"));
        // Until the next run, each round reads the same result: it reopens nothing again.
        Integer round = copy(e, "Rows").round;
        e = round();
        e = round();
        assertThat(copy(e, "Rows").round).isEqualTo(round);
        assertThat(copy(e, "Rows").state).isEqualTo("Done");

        // The table is dropped, then made again: done as missing, then judged anew.
        now.set(now.get().plus(Duration.ofMinutes(3)));
        round();
        probed("{\"tables\":[{\"table\":\"ods.orders\",\"missing\":true}]}");
        e = round();
        assertThat(copy(e, "Rows").state).isEqualTo("Done");
        assertThat(copy(e, "Rows").cleanSnapshot).isNull();
        now.set(now.get().plus(Duration.ofMinutes(3)));
        round();
        probed(
                table(
                                1,
                                now.get().toEpochMilli(),
                                "\"rows\":0,\"earliest\":1,\"records\":0,"
                                        + "\"tags\":{},\"consumers\":{},\"branches\":{}")
                        + "}");
        e = round();
        assertThat(kube.v1().events().inNamespace(NS).list().getItems())
                .anyMatch(ev -> ev.getReason().equals("TableReturned"));
        assertThat(copy(e, "Rows").cleanSnapshot).isEqualTo(1L);

        // The LakeSource is deleted: its source, checked done, stays so.
        kube.resources(LakeSource.class).inNamespace(NS).withName("shop").delete();
        e = round();
        assertThat(copy(e, "SourceDatabase").state).isEqualTo("Done");
    }

    @Test
    void aDashboardsCopyIsDoneByARefreshAfterTheRowsWentTheQueryLogByItsDays() throws Exception {
        doris = true;
        reconciler =
                reconciler(settings, source, "bucket b: versioning never enabled, no replication");
        declare();
        LakeDashboard d = new LakeDashboard();
        d.setMetadata(
                new ObjectMetaBuilder()
                        .withName("sales")
                        .withNamespace(NS)
                        .withUid(UUID.randomUUID().toString())
                        .build());
        d.setSpec(new LakeDashboardSpec());
        d.getSpec().tables = List.of("ods.orders");
        LakeDashboardSpec.Query totals = new LakeDashboardSpec.Query();
        totals.name = "totals";
        totals.serve = "materialized";
        totals.sql = "SELECT COUNT(*) FROM ods.orders";
        LakeDashboardSpec.Query live = new LakeDashboardSpec.Query();
        live.name = "live";
        live.serve = "external";
        live.sql = "SELECT * FROM ods.orders";
        d.getSpec().queries = List.of(totals, live);
        d.getSpec().governance = new Governance();
        d.getSpec().governance.owner = "bi";
        d.getSpec().governance.level = "L3";
        d.getSpec().governance.categories = List.of("personal");
        kube.resource(d).create();
        d.setStatus(new LakeDashboardStatus());
        LakeDashboardStatus.QueryState q = new LakeDashboardStatus.QueryState();
        q.name = "totals";
        q.serve = "materialized";
        q.refreshedAt = now.get().minus(Duration.ofHours(1)).toString();
        d.getStatus().queries.add(q);
        kube.resource(d).updateStatus();
        erasure();
        LakeErasure e = round();
        // The view reads the lake as it is: only the materialized view is a copy.
        assertThat(e.getStatus().copies)
                .extracting(c -> c.kind + " " + c.name + " " + c.state)
                .contains("Dashboard sales/totals Pending", "DorisAuditLog audit_log Pending")
                .noneMatch(c -> c.contains("sales/live"));
        probed(clean(10, "\"rows\":0"));
        e = round();
        assertThat(copy(e, "Rows").state).isEqualTo("Done");
        assertThat(copy(e, "Dashboard").state).isEqualTo("Pending");
        assertThat(copy(e, "Dashboard").message).contains("waiting for a refresh");
        // A refresh in full, started after the clean snapshot.
        d = kube.resources(LakeDashboard.class).inNamespace(NS).withName("sales").get();
        d.getStatus().queries.get(0).refreshedAt = now.get().plusSeconds(30).toString();
        kube.resource(d).updateStatus();
        now.set(now.get().plus(Duration.ofMinutes(3)));
        e = round();
        assertThat(copy(e, "Dashboard").state).isEqualTo("Done");
        assertThat(copy(e, "Dashboard").evidence).contains("refreshed in full");
        assertThat(copy(e, "DorisAuditLog").state).isEqualTo("Pending");
        // What the refreshes replaced stays in Doris's storage a day and an hour.
        assertThat(copy(e, "DorisStorage").state).isEqualTo("Pending");
        now.set(now.get().plus(Duration.ofHours(25)));
        e = round();
        assertThat(copy(e, "DorisStorage").state).isEqualTo("Done");
        // 7 daily partitions and files kept 5 days after their day: 7 days, and an hour.
        now.set(now.get().plus(Duration.ofDays(5)));
        e = round();
        assertThat(copy(e, "DorisAuditLog").state).isEqualTo("Pending");
        now.set(now.get().plus(Duration.ofDays(1)));
        e = round();
        assertThat(copy(e, "DorisAuditLog").state).isEqualTo("Done");
        // A dashboard made after the request copies the lake as it is then: its copy joins, and
        // stays open while it has not served it.
        LakeDashboard later = new LakeDashboard();
        later.setMetadata(
                new ObjectMetaBuilder()
                        .withName("later")
                        .withNamespace(NS)
                        .withUid(UUID.randomUUID().toString())
                        .build());
        later.setSpec(d.getSpec());
        later.getSpec().queries = List.of(totals);
        kube.resource(later).create();
        e = round();
        assertThat(
                        e.getStatus().copies.stream()
                                .filter(c -> c.name.equals("later/totals"))
                                .findFirst()
                                .orElseThrow())
                .satisfies(c -> assertThat(c.state).isEqualTo("Pending"))
                .satisfies(c -> assertThat(c.message).contains("not served it yet"));
    }

    @Test
    void withDorisInstalledItsQueryLogIsACopyDashboardOrNot() throws Exception {
        // A dashboard deleted since may have logged the subject's values in its queries.
        doris = true;
        reconciler =
                reconciler(settings, source, "bucket b: versioning never enabled, no replication");
        declare();
        erasure();
        LakeErasure e = round();
        assertThat(e.getStatus().copies)
                .extracting(c -> c.kind + " " + c.name + " " + c.state)
                .contains("DorisAuditLog audit_log Pending")
                .noneMatch(c -> c.startsWith("DorisStorage"));
    }

    @Test
    void zeroIsNoProofWhereTheSourceHadRowsOrTheValuesDoNotFit() throws Exception {
        declare();
        erasure();
        sourceRows.put("k", 1L);
        round();
        // The source holds the subject's rows; the lake counts none of them.
        probed(clean(10, "\"rows\":0"));
        LakeErasure e = round();
        assertThat(copy(e, "Rows").state).isEqualTo("Pending");
        now.set(now.get().plus(Duration.ofMinutes(3)));
        sourceRows.clear();
        round();
        probed(clean(11, "\"rows\":0"));
        e = round();
        assertThat(copy(e, "Rows").state).isEqualTo("Blocked");
        assertThat(copy(e, "Rows").message).contains("never one").contains("may not match");
        assertThat(e.getStatus().phase).isEqualTo("Blocked");
    }

    @Test
    void rowsALayeredTableKeepsOnceWhatItReadsIsCleanAreBackfilledAway() throws Exception {
        declare();
        // A lookup join drops the deletion of an order whose customer went first.
        layered("ods.orders", 10L);
        erasure();
        round();
        String tables = layeredTables(26);
        probed(tables);
        LakeErasure e = round();
        assertThat(find(e, "Rows", "ods.orders").state).isEqualTo("Done");
        assertThat(find(e, "Rows", "dwd.orders").message)
                .contains("26 rows")
                .contains("not yet past its clean snapshot 10");
        now.set(now.get().plus(LakeErasureReconciler.BACKFILL_AFTER).plusSeconds(1));
        round();
        probed(tables);
        e = round();
        // Its stream has not read the clean snapshot: the deletion may still come.
        assertThat(find(e, "Rows", "dwd.orders").message).contains("not yet past");
        assertThat(kube.resources(LakeTask.class).inNamespace(NS).list().getItems())
                .noneMatch(t -> "backfill".equals(t.getSpec().type));

        streamAt(11L);
        now.set(now.get().plusSeconds(150));
        round();
        probed(tables);
        e = round();
        assertThat(find(e, "Rows", "dwd.orders").message).contains("did not carry the deletion");
        LakeTask backfill =
                kube.resources(LakeTask.class).inNamespace(NS).list().getItems().stream()
                        .filter(t -> "backfill".equals(t.getSpec().type))
                        .findFirst()
                        .orElseThrow();
        assertThat(backfill.getSpec().table.qualifiedName()).isEqualTo("dwd.orders");
        assertThat(kube.v1().events().inNamespace(NS).list().getItems())
                .filteredOn(ev -> ev.getReason().equals("ErasureBackfill"))
                .singleElement()
                .satisfies(
                        ev ->
                                assertThat(ev.getMessage())
                                        .contains("dwd.orders holds 26 rows")
                                        .contains(backfill.getMetadata().getName()));

        // The backfill deleted what no longer derives: the rows, clean, name it.
        backfill.setStatus(new LakeTaskStatus());
        backfill.getStatus().phase = "Succeeded";
        backfill.getStatus().finishedAt = now.get().toString();
        backfill.getStatus().result = new LakeTaskStatus.Result();
        backfill.getStatus().result.detail = "upserted 5 rows, deleted 26 no longer derived";
        kube.resource(backfill).update();
        now.set(now.get().plusSeconds(150));
        round();
        probed(layeredTables(0));
        e = round();
        assertThat(find(e, "Rows", "dwd.orders").state).isEqualTo("Done");
        assertThat(find(e, "Rows", "dwd.orders").evidence)
                .contains("after backfill " + backfill.getMetadata().getName())
                .contains("deleted 26 no longer derived")
                .contains("held 26 rows of the subject at most");
        assertThat(kube.resources(LakeTask.class).inNamespace(NS).list().getItems())
                .filteredOn(t -> "backfill".equals(t.getSpec().type))
                .hasSize(1);
    }

    @Test
    void theRowsOfTheSubjectInAQuarantineGoWithItsTablesBackfill() throws Exception {
        declare();
        layered("ods.orders", 11L);
        LakeTable dwd =
                kube.resources(LakeTable.class).inNamespace(NS).withName("orders-wide").get();
        dwd.getSpec().quality = new LakeTableSpec.Quality();
        dwd.getSpec().quality.allowed = Map.of("state", List.of("paid"));
        kube.resource(dwd).update();
        erasure();
        LakeErasure e = round();
        assertThat(find(e, "Rows", "dwd.orders_quarantine").state).isEqualTo("Pending");
        probed(layeredTables(0, 3));
        round();
        now.set(now.get().plus(LakeErasureReconciler.BACKFILL_AFTER).plusSeconds(1));
        round();
        probed(layeredTables(0, 3));
        e = round();
        // The quarantine's rows are the table's: its backfill rewrites both.
        assertThat(find(e, "Rows", "dwd.orders_quarantine").message)
                .contains("3 rows")
                .contains("did not carry the deletion");
        assertThat(kube.resources(LakeTask.class).inNamespace(NS).list().getItems())
                .filteredOn(t -> "backfill".equals(t.getSpec().type))
                .singleElement()
                .satisfies(
                        t -> assertThat(t.getSpec().table.qualifiedName()).isEqualTo("dwd.orders"));
    }

    @Test
    void aLayeredTableNoneOfWhoseUpstreamTheErasureCountsIsNotBackfilled() throws Exception {
        declare();
        // It reads a table the erasure does not count: where its rows come from is not known.
        layered("ods.regions", 11L);
        erasure();
        round();
        probed(layeredTables(26));
        round();
        now.set(now.get().plus(LakeErasureReconciler.BACKFILL_AFTER).plusSeconds(1));
        round();
        probed(layeredTables(26));
        LakeErasure e = round();
        assertThat(find(e, "Rows", "dwd.orders").message)
                .contains("26 rows")
                .doesNotContain("backfill");
        assertThat(kube.resources(LakeTask.class).inNamespace(NS).list().getItems())
                .noneMatch(t -> "backfill".equals(t.getSpec().type));
    }

    /** The probe's account of ODS, clean in snapshot 10, and DWD with this many of the rows. */
    private String layeredTables(long dwdRows) {
        return layeredTables(dwdRows, -1);
    }

    /** The same, and DWD's quarantine with this many rows of the subject (none: no quarantine). */
    private String layeredTables(long dwdRows, long quarantineRows) {
        return "{\"tables\":[{\"table\":\"ods.orders\",\"snapshot\":10,\"snapshotMillis\":"
                + now.get().toEpochMilli()
                + ",\"fits\":true,\"rows\":0,\"earliest\":10,\"records\":0,\"changes\":0,"
                + "\"tags\":{},\"consumers\":{},\"branches\":{}},"
                + layeredTable("dwd.orders", dwdRows)
                + (quarantineRows < 0
                        ? ""
                        : "," + layeredTable("dwd.orders_quarantine", quarantineRows))
                + "],\"state\":[],\"orphans\":{\"before\":9999999999999,\"left\":[],"
                + "\"leftCount\":0,\"disposed\":[],\"disposedCount\":0}}";
    }

    private String layeredTable(String table, long rows) {
        return "{\"table\":\""
                + table
                + "\",\"snapshot\":11,\"snapshotMillis\":"
                + now.get().toEpochMilli()
                + ",\"fits\":true,\"rows\":"
                + rows
                + ",\"earliest\":11,\"records\":"
                + rows
                + ",\"changes\":0,\"tags\":{},\"consumers\":{},\"branches\":{}}";
    }

    /**
     * DWD, a layered table with personal data reading {@code upstream}; with a status, streaming it
     * from snapshot {@code next}, or none when null (no job has said anything yet).
     */
    private LakeTable layered(String upstream, Long next) {
        LakeTable dwd = new LakeTable();
        dwd.setMetadata(
                new ObjectMetaBuilder()
                        .withName("orders-wide")
                        .withNamespace(NS)
                        .withUid(UUID.randomUUID().toString())
                        .build());
        dwd.setSpec(new LakeTableSpec());
        dwd.getSpec().target = new LakeTableSpec.Target();
        dwd.getSpec().target.database = "dwd";
        dwd.getSpec().target.table = "orders";
        dwd.getSpec().upstream = List.of(upstream);
        dwd.getSpec().governance = new Governance();
        dwd.getSpec().governance.owner = "analytics";
        dwd.getSpec().governance.level = "L3";
        dwd.getSpec().governance.categories = List.of("personal");
        dwd.getSpec().governance.subjectKey = List.of("customer_id");
        LakeTable created = kube.resource(dwd).create();
        if (next == null) {
            return created;
        }
        created.setStatus(new LakeTableStatus());
        created.getStatus().applied = new LakeTableStatus.Applied();
        created.getStatus().applied.streams = List.of(upstream);
        kube.resource(created).update();
        streamAt(next);
        return created;
    }

    /** DWD's stream reads its upstream from this snapshot on. */
    private void streamAt(long next) {
        LakeTable dwd =
                kube.resources(LakeTable.class).inNamespace(NS).withName("orders-wide").get();
        LakeTableStatus.Upstream u = new LakeTableStatus.Upstream();
        u.table = dwd.getSpec().upstream.get(0);
        u.nextSnapshot = next;
        dwd.getStatus().upstream = List.of(u);
        kube.resource(dwd).update();
    }

    @Test
    void valuesThatDoNotFitAGoneSourceAndUnknownStateHoldTheErasure() throws Exception {
        declare();
        // A layered table with personal data, whose job is not there to say where its state is.
        LakeTable dwd = layered("ods.orders", null);
        erasure();
        sourceRows.put("k", 1L);
        LakeErasure e = round();
        assertThat(find(e, "FlinkState", TableJobs.deploymentName(dwd)).state)
                .isEqualTo("PendingEvidence");
        probed(
                "{\"tables\":[{\"table\":\"ods.orders\",\"fits\":false},"
                        + "{\"table\":\"dwd.orders\",\"error\":\"java.io.IOException: denied\"}]}");
        kube.resources(LakeSource.class).inNamespace(NS).withName("shop").delete();
        e = round();
        assertThat(copy(e, "Rows").state).isEqualTo("Blocked");
        assertThat(copy(e, "Rows").message).contains("do not fit");
        assertThat(find(e, "Rows", "dwd.orders").message)
                .contains("could not read")
                .contains("denied");
        assertThat(copy(e, "SourceDatabase").state).isEqualTo("Blocked");
        assertThat(copy(e, "SourceDatabase").message).contains("LakeSource is gone");
    }

    @Test
    void aRequestNotAcceptedInADayBreaches() throws Exception {
        declare();
        kube.secrets().inNamespace(NS).withName("erase-1").delete();
        erasure();
        LakeErasure e = round();
        assertThat(e.getStatus().requestedAt).isNotNull();
        now.set(Instant.parse(e.getStatus().requestedAt).plus(Duration.ofHours(25)));
        e = round();
        assertThat(e.getStatus().phase).isEqualTo("SlaBreach");
        assertThat(e.getStatus().breaches)
                .singleElement()
                .satisfies(b -> assertThat(b).startsWith("intake"));
    }

    @Test
    void anErasuresCompactionRewritesTheManifestsOfATableKeepingIcebergMetadataToo() {
        LakeTaskSpec compact = LakeErasureReconciler.taskSpec("compact", "ods.orders", null);
        assertThat(compact.compact.full).isTrue();
        assertThat(compact.compact.manifests).isTrue();
        assertThat(compact.table.qualifiedName()).isEqualTo("ods.orders");
    }
}
