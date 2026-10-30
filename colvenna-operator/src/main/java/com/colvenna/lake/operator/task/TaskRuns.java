package com.colvenna.lake.operator.task;

import com.colvenna.lake.operator.OperatorSettings;
import com.colvenna.lake.operator.crd.LakeSourceSpec;
import com.colvenna.lake.operator.crd.LakeTask;
import com.colvenna.lake.operator.crd.LakeTaskStatus;
import com.colvenna.lake.operator.flink.BatchJobs;
import com.colvenna.lake.operator.flink.FlinkJobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * One attempt of a task, as a Kubernetes Job (HLD 9.2): Flink in batch mode inside the pod, on the
 * sql job image, under the task's catalog identity. Its outcome is the pod's termination message.
 */
final class TaskRuns {
    static final String TASK_LABEL = "colvenna-lake.io/task";
    static final String ENTRY = "com.colvenna.lake.jobs.sql.TaskJob";
    static final String CONTAINER = "task";

    /** A finished run's Job stays a day, for its log. */
    private static final Duration KEPT = Duration.ofDays(1);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OperatorSettings settings;
    private final BatchJobs batch;
    private final KubernetesClient kube;

    TaskRuns(OperatorSettings settings, KubernetesClient kube) {
        this.settings = settings;
        this.batch = new BatchJobs(settings);
        this.kube = kube;
    }

    static String name(LakeTask task, int attempt) {
        return FlinkJobs.name(task.getMetadata().getName(), "run", Integer.toString(attempt));
    }

    enum State {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    /**
     * An attempt's outcome: its result once it succeeded (and the termination message it came
     * from), why once it failed.
     */
    record Outcome(State state, LakeTaskStatus.Result result, String error, JsonNode message) {
        static Outcome running() {
            return new Outcome(State.RUNNING, null, null, null);
        }

        static Outcome succeeded(LakeTaskStatus.Result result, JsonNode message) {
            return new Outcome(State.SUCCEEDED, result, null, message);
        }

        static Outcome failed(String error) {
            return new Outcome(State.FAILED, null, error, null);
        }
    }

    /** The attempt's Job, created when absent. */
    void start(LakeTask task, String argument, int attempt, Duration timeout) {
        String ns = task.getMetadata().getNamespace();
        if (kube.batch().v1().jobs().inNamespace(ns).withName(name(task, attempt)).get() == null) {
            kube.batch()
                    .v1()
                    .jobs()
                    .inNamespace(ns)
                    .resource(build(task, argument, attempt, timeout))
                    .create();
        }
    }

    /** What the attempt has come to; an attempt whose Job is gone has failed. */
    Outcome observe(LakeTask task, int attempt) {
        String ns = task.getMetadata().getNamespace();
        Job job = kube.batch().v1().jobs().inNamespace(ns).withName(name(task, attempt)).get();
        if (job == null) {
            return Outcome.failed("the run's Job is gone");
        }
        for (Pod pod : pods(task, attempt)) {
            ContainerStateTerminated done = BatchJobs.terminated(pod, CONTAINER);
            if (done == null) {
                continue;
            }
            if (done.getExitCode() != null && done.getExitCode() == 0) {
                try {
                    return Outcome.succeeded(result(done.getMessage()), message(done.getMessage()));
                } catch (IOException e) {
                    return Outcome.failed("unreadable result: " + e.getMessage());
                }
            }
            return Outcome.failed(
                    "the run failed (exit "
                            + done.getExitCode()
                            + ", "
                            + done.getReason()
                            + "); its pod log has the cause");
        }
        JobCondition failed =
                job.getStatus() == null || job.getStatus().getConditions() == null
                        ? null
                        : job.getStatus().getConditions().stream()
                                .filter(c -> c.getType().equals("Failed"))
                                .filter(c -> c.getStatus().equals("True"))
                                .findFirst()
                                .orElse(null);
        // DeadlineExceeded: the attempt ran past its timeout and its pod was stopped.
        return failed == null
                ? Outcome.running()
                : Outcome.failed("the run failed (" + failed.getReason() + ")");
    }

    /**
     * Whether a pod of the attempt may still write: one that has not ended, a deleted one still
     * terminating included.
     */
    boolean active(LakeTask task, int attempt) {
        return pods(task, attempt).stream()
                .anyMatch(
                        p ->
                                p.getStatus() == null
                                        || !"Succeeded".equals(p.getStatus().getPhase())
                                                && !"Failed".equals(p.getStatus().getPhase()));
    }

    void delete(LakeTask task, int attempt) {
        kube.batch()
                .v1()
                .jobs()
                .inNamespace(task.getMetadata().getNamespace())
                .withName(name(task, attempt))
                .withPropagationPolicy(DeletionPropagation.BACKGROUND)
                .delete();
    }

    private List<Pod> pods(LakeTask task, int attempt) {
        return kube.pods()
                .inNamespace(task.getMetadata().getNamespace())
                .withLabel("job-name", name(task, attempt))
                .list()
                .getItems();
    }

    static JsonNode message(String message) throws IOException {
        return JSON.readTree(message == null || message.isBlank() ? "{}" : message);
    }

    static LakeTaskStatus.Result result(String message) throws IOException {
        JsonNode node = message(message);
        LakeTaskStatus.Result r = new LakeTaskStatus.Result();
        r.snapshotBefore =
                node.hasNonNull("snapshotBefore") ? node.path("snapshotBefore").asLong() : null;
        r.snapshotAfter =
                node.hasNonNull("snapshotAfter") ? node.path("snapshotAfter").asLong() : null;
        r.detail = node.path("detail").asText(null);
        return r;
    }

    Job build(LakeTask task, String argument, int attempt, Duration timeout) {
        return batch.build(
                new BatchJobs.Run(
                        task,
                        name(task, attempt),
                        Map.of(
                                FlinkJobs.MANAGED_BY,
                                "colvenna-operator",
                                TASK_LABEL,
                                task.getMetadata().getName()),
                        CONTAINER,
                        settings.jobImage("sql"),
                        ENTRY,
                        argument,
                        size(task.getSpec().resources),
                        timeout,
                        KEPT));
    }

    /** The run's size: half a CPU and 2 GiB unless declared; the heap leaves room for Flink. */
    static BatchJobs.Size size(LakeSourceSpec.Resources declared) {
        double cpu = declared == null || declared.cpu == null ? 0.5 : declared.cpu;
        String memory = declared == null || declared.memory == null ? "2048m" : declared.memory;
        long mib =
                memory.endsWith("g")
                        ? Long.parseLong(memory.substring(0, memory.length() - 1)) * 1024
                        : Long.parseLong(memory.substring(0, memory.length() - 1));
        return new BatchJobs.Size(
                Double.toString(cpu), mib + "Mi", Math.max(256, mib * 3 / 4) + "m");
    }
}
