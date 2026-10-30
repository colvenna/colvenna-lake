package com.colvenna.lake.operator.table;

import com.colvenna.lake.operator.crd.LakeSourceStatus;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.crd.LakeTableStatus;
import com.colvenna.lake.operator.flink.FlinkJobs;
import com.colvenna.lake.operator.flink.FlinkRest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.apache.flink.kubernetes.operator.api.spec.FlinkDeploymentSpec;
import org.apache.flink.kubernetes.operator.api.status.FlinkDeploymentReconciliationStatus;
import org.apache.flink.kubernetes.operator.api.status.FlinkDeploymentStatus;
import org.apache.flink.kubernetes.operator.api.status.ReconciliationState;

/**
 * A layered table through an upgrade, with a JobManager that answers what each job's checkpoint
 * statistics are, and a Flink operator the test plays: the deployment's status set as the Flink
 * operator would have it.
 */
final class UpgradeFixture {
    static final String ROOT = "s3://bucket/flink-state";
    static final String STATE = ROOT + "/colvenna-data/orders-wide-table-0123abcd/savepoints";

    /** The deployment's savepoint before the upgrade: the one the old job restored from. */
    static final String BEFORE = STATE + "/savepoint-0a0a0a-000000000012";

    /** The savepoint the upgrade took when it stopped the old job. */
    static final String TAKEN = STATE + "/savepoint-0b0b0b-000000000013";

    static final String QUERY = "SELECT order_id, amount FROM ods.orders";
    static final String FILTERED = QUERY + " WHERE amount >= 0";
    static final Instant T0 = Instant.parse("2026-10-26T01:16:26Z");
    static final Instant OVERDUE = T0.plus(Duration.ofMinutes(11));

    /**
     * Checkpoint statistics as Flink 1.20's JobManager answers them: {@code completed} completed
     * (the latest {@code latest}), and a restore from {@code restored} ({@code path}), or none.
     */
    static String checkpoints(long completed, Long latest, Long restored, String path) {
        return "{\"counts\":{\"restored\":"
                + (restored == null ? 0 : 1)
                + ",\"total\":"
                + (completed + 1)
                + ",\"in_progress\":0,\"completed\":"
                + completed
                + ",\"failed\":1},\"latest\":{\"completed\":"
                + (latest == null ? "null" : "{\"id\":" + latest + ",\"is_savepoint\":false}")
                + ",\"savepoint\":null,\"failed\":null,\"restored\":"
                + (restored == null
                        ? "null"
                        : "{\"id\":"
                                + restored
                                + ",\"is_savepoint\":true,\"external_path\":\""
                                + path
                                + "\"}")
                + "},\"history\":[]}";
    }

    /** A JobManager that answers each job's checkpoint statistics the test sets, 404 otherwise. */
    static final class JobManager extends FlinkRest {
        private static final Pattern CHECKPOINTS = Pattern.compile("/jobs/([^/]+)/checkpoints");
        final Map<String, String> checkpoints = new HashMap<>();

        @Override
        protected Optional<JsonNode> get(String namespace, String deployment, String path) {
            Matcher m = CHECKPOINTS.matcher(path);
            if (!m.matches() || !checkpoints.containsKey(m.group(1))) {
                return Optional.empty();
            }
            try {
                return Optional.of(new ObjectMapper().readTree(checkpoints.get(m.group(1))));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static Supplier<Optional<Long>> committed(long id) {
        return () -> Optional.of(id);
    }

    static Optional<Long> unreadable() {
        throw new IllegalStateException("colvenna-catalog unavailable");
    }

    final JobManager jobManager = new JobManager();
    final TableJobs jobs = new TableJobs(LakeTableTest.SETTINGS);
    final LakeTable table = LakeTableTest.table();

    /** The old job runs: its deployment stable on the declared spec, job {@code job}. */
    FlinkDeployment running(String job) {
        LakeTableStatus status = table.getStatus();
        FlinkDeployment fd = jobs.desired(table, status);
        fd.getMetadata().setGeneration(1L);
        LakeTableStatus.Applied applied = new LakeTableStatus.Applied();
        applied.hash = FlinkJobs.hashOf(fd);
        applied.target = "dwd.orders";
        applied.upstream = List.of("ods.orders");
        applied.streams = List.of("ods.orders");
        status.applied = applied;
        status.job = new LakeSourceStatus.JobState();
        status.job.deployment = fd.getMetadata().getName();
        status.job.jobId = job;
        FlinkDeploymentStatus s = new FlinkDeploymentStatus();
        var jobStatus = new org.apache.flink.kubernetes.operator.api.status.JobStatus();
        jobStatus.setJobId(job);
        jobStatus.setState(JobStatus.RUNNING);
        jobStatus.setUpgradeSavepointPath(BEFORE);
        s.setJobStatus(jobStatus);
        FlinkDeploymentReconciliationStatus reconciled = new FlinkDeploymentReconciliationStatus();
        reconciled.serializeAndSetLastReconciledSpec(fd.getSpec(), fd);
        reconciled.markReconciledSpecAsStable();
        reconciled.setState(ReconciliationState.DEPLOYED);
        s.setReconciliationStatus(reconciled);
        s.setObservedGeneration(1L);
        fd.setStatus(s);
        return fd;
    }

    /** The declaration with another query. */
    FlinkDeployment declared(String query) {
        table.getSpec().query = query;
        return jobs.desired(table, table.getStatus());
    }

    /**
     * What the Flink operator makes of the written upgrade: the old job stopped with a savepoint,
     * the new one (job {@code job}) never found; the spec that ran stays the stable one.
     */
    void newJobFails(FlinkDeployment fd, String job) {
        FlinkDeploymentSpec stable =
                fd.getStatus().getReconciliationStatus().deserializeLastStableSpec();
        fd.getMetadata().setGeneration(fd.getMetadata().getGeneration() + 1);
        FlinkDeploymentReconciliationStatus reconciled = new FlinkDeploymentReconciliationStatus();
        FlinkDeployment was = new FlinkDeployment();
        was.setMetadata(fd.getMetadata());
        reconciled.serializeAndSetLastReconciledSpec(stable, was);
        reconciled.markReconciledSpecAsStable();
        reconciled.serializeAndSetLastReconciledSpec(fd.getSpec(), fd);
        reconciled.setState(ReconciliationState.DEPLOYED);
        fd.getStatus().setReconciliationStatus(reconciled);
        fd.getStatus().getJobStatus().setJobId(job);
        fd.getStatus().getJobStatus().setState(JobStatus.RECONCILING);
        fd.getStatus().getJobStatus().setUpgradeSavepointPath(TAKEN);
        fd.getStatus().setError("Job Not Found");
        table.getStatus().job.jobId = job;
    }

    /** The Flink operator ran {@code spec} (as written): job {@code job}, stable, current. */
    void runs(FlinkDeployment fd, FlinkDeployment written, String job) {
        fd.setSpec(written.getSpec());
        fd.getMetadata().setAnnotations(written.getMetadata().getAnnotations());
        FlinkDeploymentReconciliationStatus reconciled = new FlinkDeploymentReconciliationStatus();
        reconciled.serializeAndSetLastReconciledSpec(fd.getSpec(), fd);
        reconciled.markReconciledSpecAsStable();
        reconciled.setState(ReconciliationState.DEPLOYED);
        fd.getStatus().setReconciliationStatus(reconciled);
        fd.getStatus().setObservedGeneration(fd.getMetadata().getGeneration());
        fd.getStatus().getJobStatus().setJobId(job);
        fd.getStatus().getJobStatus().setState(JobStatus.RUNNING);
        fd.getStatus().setError(null);
        table.getStatus().job.jobId = job;
    }
}
