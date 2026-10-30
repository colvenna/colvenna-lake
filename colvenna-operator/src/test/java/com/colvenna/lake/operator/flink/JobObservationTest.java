package com.colvenna.lake.operator.flink;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.crd.LakeSourceStatus.JobState;
import java.util.Map;
import java.util.Optional;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.apache.flink.kubernetes.operator.api.status.FlinkDeploymentStatus;
import org.apache.flink.kubernetes.operator.api.status.JobStatus;
import org.junit.jupiter.api.Test;

class JobObservationTest {
    /** A JobManager that reports whatever restore the test sets. */
    static final class Rest extends FlinkRest {
        String restored = "";

        @Override
        public Optional<Checkpoints> checkpoints(
                String namespace, String deployment, String jobId) {
            return restored == null
                    ? Optional.empty()
                    : Optional.of(new Checkpoints(1, 0, 1_700_000_000_000L, 1500L, restored, 5));
        }

        @Override
        public Optional<Heap> heap(String namespace, String deployment) {
            return Optional.of(new Heap(300L << 20, 1L << 30));
        }
    }

    static FlinkDeployment started(String startTime) {
        FlinkDeployment fd = new FlinkDeployment();
        FlinkDeploymentStatus status = new FlinkDeploymentStatus();
        JobStatus job = new JobStatus();
        job.setJobId("j1");
        job.setStartTime(startTime);
        status.setJobStatus(job);
        fd.setStatus(status);
        return fd;
    }

    @Test
    void everyRestartIsSeenThoughTheJobIdStays() {
        Rest rest = new Rest();
        JobState state = new JobState();
        state.deployment = "d";
        state.jobId = "j1";
        // The first submission starts fresh: nothing to say.
        assertThat(JobObservation.observe(rest, "ns", state, started("100"))).isEmpty();
        assertThat(JobObservation.observe(rest, "ns", state, started("100"))).isEmpty();
        // A last-state upgrade resubmits under the same job id.
        rest.restored = "s3://b/j1/chk-5";
        assertThat(JobObservation.observe(rest, "ns", state, started("200")))
                .contains(JobObservation.Restore.STATE);
        assertThat(state.restoredFrom).isEqualTo("s3://b/j1/chk-5");
        assertThat(JobObservation.observe(rest, "ns", state, started("200"))).isEmpty();
        // A TaskManager failover keeps the submission and restores a later checkpoint.
        rest.restored = "s3://b/j1/chk-7";
        assertThat(JobObservation.observe(rest, "ns", state, started("200")))
                .contains(JobObservation.Restore.STATE);
        // A resubmission from nothing after the job ran.
        rest.restored = "";
        assertThat(JobObservation.observe(rest, "ns", state, started("300")))
                .contains(JobObservation.Restore.WITHOUT_STATE);
        assertThat(state.restoredFrom).isNull();
        // The JobManager cannot tell yet.
        rest.restored = null;
        assertThat(JobObservation.observe(rest, "ns", state, started("400"))).isEmpty();
    }

    @Test
    void aJobsCheckpointsAndHeapAreItsGauges() {
        Rest rest = new Rest();
        JobState state = new JobState();
        state.deployment = "d";
        state.jobId = "j1";
        JobObservation.observe(rest, "ns", state, started("100"));
        assertThat(JobObservation.samples(state, Map.of("deployment", "d")))
                .extracting(s -> s.name() + " " + s.value())
                .contains(
                        "colvenna_job_last_checkpoint_timestamp_seconds 1.7E9",
                        "colvenna_job_last_checkpoint_duration_seconds 1.5",
                        "colvenna_job_checkpoints_failed 0.0",
                        "colvenna_job_taskmanager_heap_used_bytes " + (double) (300L << 20),
                        "colvenna_job_taskmanager_heap_max_bytes " + (double) (1L << 30));
    }
}
