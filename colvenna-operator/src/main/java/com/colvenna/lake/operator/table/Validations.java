package com.colvenna.lake.operator.table;

import com.colvenna.lake.operator.OperatorSettings;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.crd.LakeTableStatus;
import com.colvenna.lake.operator.flink.BatchJobs;
import com.colvenna.lake.operator.flink.FlinkJobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Runs a declared query's validation as a Kubernetes Job: the job image's own planner and
 * connectors, under the table's catalog identity, outside the operator's process. The verdict is
 * the pod's termination message, which only the validator writes (gzip, base64 JSON).
 */
final class Validations {
    static final String HASH_LABEL = "colvenna-lake.io/validation";

    /** A planner in a JVM: room for Flink's catalog and the plan. */
    private static final BatchJobs.Size SIZE = new BatchJobs.Size("500m", "2Gi", "1g");

    private static final Duration DEADLINE = Duration.ofMinutes(5);

    /** The verdict is read at once; the Job stays an hour for its log. */
    private static final Duration KEPT = Duration.ofHours(1);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BatchJobs batch;
    private final KubernetesClient kube;

    Validations(OperatorSettings settings, KubernetesClient kube) {
        this.batch = new BatchJobs(settings);
        this.kube = kube;
    }

    static String jobName(LakeTable table, String hash) {
        return FlinkJobs.name(table.getMetadata().getName(), "validate", hash.substring(0, 8));
    }

    /** The finished validation, or empty while it runs; starts it when absent. */
    Optional<LakeTableStatus.Validation> run(LakeTable table, String image, String hash) {
        String ns = table.getMetadata().getNamespace();
        String name = jobName(table, hash);
        Job job = kube.batch().v1().jobs().inNamespace(ns).withName(name).get();
        if (job == null) {
            kube.batch().v1().jobs().inNamespace(ns).resource(build(table, image, hash)).create();
            return Optional.empty();
        }
        Optional<LakeTableStatus.Validation> result = Optional.empty();
        for (Pod pod : kube.pods().inNamespace(ns).withLabel("job-name", name).list().getItems()) {
            ContainerStateTerminated done = BatchJobs.terminated(pod, "validate");
            if (done != null) {
                result = Optional.of(read(done));
                break;
            }
        }
        boolean failed =
                job.getStatus() != null
                        && job.getStatus().getFailed() != null
                        && job.getStatus().getFailed() > 0;
        if (result.isEmpty() && failed) {
            result = Optional.of(refused("ValidationError", "the validation pod failed"));
        }
        if (result.isPresent()) {
            kube.batch()
                    .v1()
                    .jobs()
                    .inNamespace(ns)
                    .withName(name)
                    .withPropagationPolicy(DeletionPropagation.BACKGROUND)
                    .delete();
            result.get().hash = hash;
        }
        return result;
    }

    static LakeTableStatus.Validation read(ContainerStateTerminated done) {
        String message = done.getMessage();
        if (message == null || message.isBlank()) {
            return refused(
                    "ValidationError",
                    "the validation pod ended without a verdict ("
                            + done.getReason()
                            + ", exit "
                            + done.getExitCode()
                            + ")");
        }
        try {
            return parse(gunzip(message.trim()));
        } catch (IOException | IllegalArgumentException e) {
            return refused("ValidationError", "unreadable validation verdict: " + e.getMessage());
        }
    }

    static LakeTableStatus.Validation parse(String json) throws IOException {
        JsonNode node = JSON.readTree(json);
        LakeTableStatus.Validation v = new LakeTableStatus.Validation();
        if (node.hasNonNull("reason")) {
            v.reason = node.path("reason").asText();
            v.message = node.path("message").asText();
            return v;
        }
        for (JsonNode f : node.path("fields")) {
            LakeTableStatus.Column c = new LakeTableStatus.Column();
            c.name = f.path("name").asText();
            JsonNode type = f.path("type");
            c.type = type.isTextual() ? type.asText() : type.toString();
            v.columns.add(c);
        }
        node.path("streams").forEach(t -> v.streams.add(t.asText()));
        node.path("lookups").forEach(t -> v.lookups.add(t.asText()));
        v.stateful = node.hasNonNull("stateful") ? node.path("stateful").asBoolean() : null;
        return v;
    }

    private static LakeTableStatus.Validation refused(String reason, String message) {
        LakeTableStatus.Validation v = new LakeTableStatus.Validation();
        v.reason = reason;
        v.message = message;
        return v;
    }

    private static String gunzip(String base64) throws IOException {
        try (GZIPInputStream in =
                new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(base64)))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    Job build(LakeTable table, String image, String hash) {
        return batch.build(
                new BatchJobs.Run(
                        table,
                        jobName(table, hash),
                        Map.of(
                                FlinkJobs.MANAGED_BY,
                                "colvenna-operator",
                                TableJobs.TABLE_LABEL,
                                table.getMetadata().getName(),
                                HASH_LABEL,
                                hash),
                        "validate",
                        image,
                        TableJobs.VALIDATE,
                        TableJobs.argument(table, List.of()),
                        SIZE,
                        DEADLINE,
                        KEPT));
    }
}
