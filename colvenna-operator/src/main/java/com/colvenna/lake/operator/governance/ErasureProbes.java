package com.colvenna.lake.operator.governance;

import com.colvenna.lake.operator.OperatorSettings;
import com.colvenna.lake.operator.crd.LakeErasure;
import com.colvenna.lake.operator.crd.LakeErasureStatus;
import com.colvenna.lake.operator.flink.BatchJobs;
import com.colvenna.lake.operator.flink.FlinkJobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An erasure's probe runs (com.colvenna.lake.jobs.sql.ErasureProbe), as Kubernetes Jobs: one at a
 * time, the next {@link #EVERY} after the last ended. Its result comes back as the pod's
 * termination message, of which Kubernetes keeps 4 KiB, so a run probes a few tables and jobs, the
 * next run the next ones.
 */
final class ErasureProbes {
    static final Duration EVERY = Duration.ofMinutes(2);
    static final String ENTRY = "com.colvenna.lake.jobs.sql.ErasureProbe";
    static final String CONTAINER = "probe";

    /** What one run probes: about 250 bytes of result a table, 600 a job's state. */
    static final int TABLES_PER_RUN = 6;

    static final int STATES_PER_RUN = 2;

    /** How long a run may take, and how long its Job stays after it ends. */
    static final Duration DEADLINE = Duration.ofMinutes(20);

    static final Duration KEPT = Duration.ofHours(1);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OperatorSettings settings;
    private final KubernetesClient kube;
    private final BatchJobs batch;

    ErasureProbes(OperatorSettings settings, KubernetesClient kube) {
        this.settings = settings;
        this.kube = kube;
        this.batch = new BatchJobs(settings);
    }

    /** The newest result and the run that gave it, and what kept the last run from giving one. */
    record Probed(JsonNode result, int run, String problem) {}

    /** A run: its result once it succeeded, when it ended, or that its Job is gone. */
    private record Run(JsonNode result, Instant endedAt, boolean gone, String problem) {}

    /**
     * The newest result, starting the next run when one is due over the tables and jobs a copy
     * waits for; each run takes the next of them in turn.
     */
    Probed probe(
            LakeErasure erasure,
            LakeErasureStatus status,
            List<Map<String, Object>> tables,
            List<Map<String, Object>> state,
            Map<String, Object> orphans,
            Instant now) {
        if (tables.isEmpty() && state.isEmpty() && orphans == null) {
            return new Probed(null, 0, null);
        }
        String ns = erasure.getMetadata().getNamespace();
        int runs = status.probes == null ? 0 : status.probes;
        Run last = runs == 0 ? null : run(erasure, runs);
        boolean due =
                last == null
                        || last.gone()
                        || last.endedAt() != null && !now.isBefore(last.endedAt().plus(EVERY));
        if (due) {
            String name = name(erasure, runs + 1);
            if (kube.batch().v1().jobs().inNamespace(ns).withName(name).get() == null) {
                kube.batch()
                        .v1()
                        .jobs()
                        .inNamespace(ns)
                        .resource(
                                job(
                                        erasure,
                                        name,
                                        turn(tables, runs, TABLES_PER_RUN),
                                        turn(state, runs, STATES_PER_RUN),
                                        orphans))
                        .create();
            }
            status.probes = runs + 1;
        }
        // The newest that finished: the last run's, or the one before while it runs.
        Run previous = runs > 1 && last.result() == null ? run(erasure, runs - 1) : null;
        boolean lastGave = last != null && last.result() != null;
        JsonNode result = lastGave ? last.result() : previous == null ? null : previous.result();
        return new Probed(
                result,
                result == null ? 0 : lastGave ? runs : runs - 1,
                last == null ? null : last.problem());
    }

    /** The run's share: {@code size} of them, from where the runs before left off. */
    static <T> List<T> turn(List<T> all, int runs, int size) {
        if (all.size() <= size) {
            return all;
        }
        List<T> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            out.add(all.get((runs * size + i) % all.size()));
        }
        return out;
    }

    static String name(LakeErasure erasure, int run) {
        return FlinkJobs.name(erasure.getMetadata().getName(), "probe", Integer.toString(run));
    }

    private Run run(LakeErasure erasure, int run) {
        String ns = erasure.getMetadata().getNamespace();
        String name = name(erasure, run);
        Job job = kube.batch().v1().jobs().inNamespace(ns).withName(name).get();
        if (job == null) {
            return new Run(null, null, true, null);
        }
        for (Pod pod : kube.pods().inNamespace(ns).withLabel("job-name", name).list().getItems()) {
            ContainerStateTerminated done = BatchJobs.terminated(pod, CONTAINER);
            if (done == null) {
                continue;
            }
            Instant ended =
                    done.getFinishedAt() == null
                            ? Instant.EPOCH
                            : Instant.parse(done.getFinishedAt());
            if (done.getExitCode() == null || done.getExitCode() != 0) {
                return new Run(
                        null,
                        ended,
                        false,
                        "probe "
                                + name
                                + " failed (exit "
                                + done.getExitCode()
                                + "); its pod log has the cause");
            }
            try {
                return new Run(JSON.readTree(done.getMessage()), ended, false, null);
            } catch (Exception e) {
                return new Run(
                        null,
                        ended,
                        false,
                        "probe "
                                + name
                                + " gave an unreadable result: more than the 4 KiB a"
                                + " termination message keeps?");
            }
        }
        // Stopped at its deadline: the Job failed and its pod is gone.
        JobCondition failed =
                job.getStatus() == null || job.getStatus().getConditions() == null
                        ? null
                        : job.getStatus().getConditions().stream()
                                .filter(c -> "Failed".equals(c.getType()))
                                .filter(c -> "True".equals(c.getStatus()))
                                .findFirst()
                                .orElse(null);
        if (failed != null) {
            return new Run(
                    null,
                    failed.getLastTransitionTime() == null
                            ? Instant.EPOCH
                            : Instant.parse(failed.getLastTransitionTime()),
                    false,
                    "probe " + name + " failed (" + failed.getReason() + ")");
        }
        return new Run(null, null, false, null);
    }

    private Job job(
            LakeErasure erasure,
            String name,
            List<Map<String, Object>> tables,
            List<Map<String, Object>> state,
            Map<String, Object> orphans) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("tables", tables);
        spec.put("state", state);
        if (orphans != null) {
            spec.put("orphans", orphans);
        }
        String argument;
        try {
            argument = JSON.writeValueAsString(spec);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Job job =
                batch.build(
                        new BatchJobs.Run(
                                erasure,
                                name,
                                Map.of(
                                        FlinkJobs.MANAGED_BY,
                                        "colvenna-operator",
                                        "colvenna-lake.io/erasure",
                                        erasure.getMetadata().getName()),
                                CONTAINER,
                                settings.jobImage("sql"),
                                ENTRY,
                                argument,
                                new BatchJobs.Size("500m", "2Gi", "1536m"),
                                DEADLINE,
                                KEPT));
        var pod = job.getSpec().getTemplate().getSpec();
        // The jobs' identity lists and disposes of their state; tables are read with the erasure's.
        pod.setServiceAccountName(settings.flinkServiceAccount());
        // The subject's values, as a file only the probe reads.
        pod.getVolumes()
                .add(
                        new VolumeBuilder()
                                .withName("subject")
                                .withNewSecret()
                                .withSecretName(erasure.getSpec().subjectSecret.name)
                                .endSecret()
                                .build());
        var container = pod.getContainers().get(0);
        container
                .getVolumeMounts()
                .add(
                        new VolumeMountBuilder()
                                .withName("subject")
                                .withMountPath("/subject-secret")
                                .withReadOnly(true)
                                .build());
        container
                .getEnv()
                .add(
                        new EnvVarBuilder()
                                .withName("COLVENNA_SUBJECT_FILE")
                                .withValue(
                                        "/subject-secret/" + erasure.getSpec().subjectSecret.key())
                                .build());
        return job;
    }
}
