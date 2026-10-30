package com.colvenna.lake.operator.flink;

import com.colvenna.lake.operator.crd.LakeSourceStatus.JobState;
import com.colvenna.lake.operator.metrics.Metrics;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.apache.flink.kubernetes.operator.api.lifecycle.ResourceLifecycleState;

/** What a deployment's status says about its job, as both reconcilers record it. */
public final class JobObservation {
    private JobObservation() {}

    /** Copies lifecycle, job state, job id and the latest error into {@code state}. */
    public static void read(FlinkDeployment fd, JobState state) {
        ResourceLifecycleState lifecycle = fd.getStatus().getLifecycleState();
        state.lifecycle = lifecycle == null ? null : lifecycle.name();
        var job = fd.getStatus().getJobStatus();
        state.state = job == null || job.getState() == null ? null : job.getState().name();
        state.jobId = job == null ? null : job.getJobId();
        if (fd.getStatus().getError() != null && !fd.getStatus().getError().isBlank()) {
            state.lastError = abbreviate(fd.getStatus().getError());
        }
    }

    /** Running the spec the operator last wrote, not one still being upgraded to. */
    public static boolean live(FlinkDeployment fd) {
        var s = fd.getStatus();
        return s != null
                && s.getJobStatus() != null
                && s.getJobStatus().getState() != null
                && s.getJobStatus().getState().name().equals("RUNNING")
                && Objects.equals(s.getObservedGeneration(), fd.getMetadata().getGeneration())
                && (s.getLifecycleState() == ResourceLifecycleState.STABLE
                        || s.getLifecycleState() == ResourceLifecycleState.DEPLOYED);
    }

    /** Suspended on the spec the operator last wrote. */
    public static boolean suspended(FlinkDeployment fd) {
        var s = fd.getStatus();
        return s != null
                && Objects.equals(s.getObservedGeneration(), fd.getMetadata().getGeneration())
                && s.getLifecycleState() == ResourceLifecycleState.SUSPENDED;
    }

    /** What a job restored from, when that changed. */
    public enum Restore {
        /** Restored from a checkpoint or savepoint ({@code state.restoredFrom}). */
        STATE,
        /** A new submission started from nothing although an earlier one ran. */
        WITHOUT_STATE
    }

    /**
     * Observes the running job each round: its checkpoints (when the latest completed, how long it
     * took, how many failed), its busiest TaskManager's heap, and what it last restored from. A
     * restart (upgrade, failover, resume) must continue from the job's state, or it would replay
     * its input from scratch: a last-state upgrade keeps the job id, and a task failover keeps the
     * submission, so neither says by itself that the job restarted. Returns how it restarted, or
     * empty.
     */
    public static Optional<Restore> observe(
            FlinkRest rest, String namespace, JobState state, FlinkDeployment fd) {
        if (state.jobId == null) {
            return Optional.empty();
        }
        Optional<FlinkRest.Checkpoints> checkpoints =
                rest.checkpoints(namespace, state.deployment, state.jobId);
        if (checkpoints.isEmpty()) {
            return Optional.empty();
        }
        FlinkRest.Checkpoints c = checkpoints.get();
        state.checkpointsFailed = c.failed();
        state.lastCheckpointAt =
                c.latestAt() == null ? null : Instant.ofEpochMilli(c.latestAt()).toString();
        state.lastCheckpointDurationMillis = c.latestDurationMillis();
        rest.heap(namespace, state.deployment)
                .ifPresent(
                        h -> {
                            state.heapUsedBytes = h.used();
                            state.heapMaxBytes = h.max();
                        });
        String path = c.restored().isEmpty() ? null : c.restored();
        String started = fd.getStatus().getJobStatus().getStartTime();
        boolean resubmitted =
                state.startTime != null && started != null && !started.equals(state.startTime);
        String before = state.restoredFrom;
        state.startTime = started;
        state.restoredFrom = path;
        if (path != null && !path.equals(before)) {
            return Optional.of(Restore.STATE);
        }
        if (path == null && resubmitted) {
            return Optional.of(Restore.WITHOUT_STATE);
        }
        return Optional.empty();
    }

    /**
     * A job's health, as gauges: its last commit's time, the checkpoints failed, and its busiest
     * TaskManager's heap.
     */
    public static List<Metrics.Sample> samples(JobState state, Map<String, String> labels) {
        List<Metrics.Sample> out = new ArrayList<>();
        if (state.lastCheckpointAt != null) {
            out.add(
                    new Metrics.Sample(
                            "colvenna_job_last_checkpoint_timestamp_seconds",
                            "when the job's latest checkpoint, and so its latest commit, completed",
                            labels,
                            Instant.parse(state.lastCheckpointAt).getEpochSecond()));
        }
        if (state.lastCheckpointDurationMillis != null) {
            out.add(
                    new Metrics.Sample(
                            "colvenna_job_last_checkpoint_duration_seconds",
                            "how long the job's latest checkpoint took",
                            labels,
                            state.lastCheckpointDurationMillis / 1000.0));
        }
        if (state.checkpointsFailed != null) {
            out.add(
                    new Metrics.Sample(
                            "colvenna_job_checkpoints_failed",
                            "checkpoints of the running job that failed",
                            labels,
                            state.checkpointsFailed));
        }
        if (state.heapUsedBytes != null && state.heapMaxBytes != null) {
            out.add(
                    new Metrics.Sample(
                            "colvenna_job_taskmanager_heap_used_bytes",
                            "the heap the job's busiest TaskManager uses (its writers, committer)",
                            labels,
                            state.heapUsedBytes));
            out.add(
                    new Metrics.Sample(
                            "colvenna_job_taskmanager_heap_max_bytes",
                            "that TaskManager's maximum heap",
                            labels,
                            state.heapMaxBytes));
        }
        return out;
    }

    public static String abbreviate(String text) {
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
    }
}
