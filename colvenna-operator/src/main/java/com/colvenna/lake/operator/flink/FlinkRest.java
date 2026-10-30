package com.colvenna.lake.operator.flink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a job's recent failures and checkpoints from its JobManager's REST service, which stays
 * inside the cluster ({@code kubernetes.rest-service.exposed.type: ClusterIP}). Read-only.
 */
public class FlinkRest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** A failure the job restarted after: when, and the full stack trace text. */
    public record Failure(long timestamp, String stacktrace) {}

    /** The job's most recent failures, newest first; empty if the JobManager cannot be reached. */
    public List<Failure> failures(String namespace, String deployment, String jobId) {
        List<Failure> out = new ArrayList<>();
        get(namespace, deployment, "/jobs/" + jobId + "/exceptions?maxExceptions=10")
                .ifPresent(
                        body -> {
                            for (JsonNode entry : body.path("exceptionHistory").path("entries")) {
                                out.add(
                                        new Failure(
                                                entry.path("timestamp").asLong(),
                                                entry.path("stacktrace").asText("")));
                            }
                        });
        return out;
    }

    /**
     * The job's checkpoints, as its JobManager has counted them since it started: how many
     * completed and failed, when the latest completed and how long it took, what the job restored
     * from ({@code ""} for a fresh start), and the highest checkpoint id it has used: completed,
     * failed or in progress, a savepoint, or the one it restored from (0 for none). The job's
     * coordinator hands out ids in order, and a restored job continues after the id it restored, so
     * whatever the job checkpoints next (its savepoint when an upgrade stops it) gets a later id
     * than {@code lastId}; a job that restored from a savepoint and has completed no checkpoint yet
     * is at that savepoint's id. Paimon commits only when a checkpoint completes, so a job that
     * completed none since it restored has written nothing since.
     */
    public record Checkpoints(
            long completed,
            long failed,
            Long latestAt,
            Long latestDurationMillis,
            String restored,
            long lastId) {}

    /** The job's checkpoints, from one request; empty when the JobManager cannot tell. */
    public Optional<Checkpoints> checkpoints(String namespace, String deployment, String jobId) {
        return get(namespace, deployment, "/jobs/" + jobId + "/checkpoints")
                .filter(body -> body.path("counts").has("completed"))
                .map(FlinkRest::read);
    }

    private static Checkpoints read(JsonNode body) {
        JsonNode latest = body.path("latest");
        JsonNode completed = latest.path("completed");
        JsonNode restored = latest.path("restored");
        long lastId = 0;
        for (String kind : List.of("completed", "savepoint", "failed", "restored")) {
            lastId = Math.max(lastId, latest.path(kind).path("id").asLong(0));
        }
        for (JsonNode checkpoint : body.path("history")) {
            lastId = Math.max(lastId, checkpoint.path("id").asLong(0));
        }
        return new Checkpoints(
                body.path("counts").path("completed").asLong(),
                body.path("counts").path("failed").asLong(),
                completed.has("latest_ack_timestamp")
                        ? completed.path("latest_ack_timestamp").asLong()
                        : null,
                completed.has("end_to_end_duration")
                        ? completed.path("end_to_end_duration").asLong()
                        : null,
                restored.isMissingNode() || restored.isNull()
                        ? ""
                        : restored.path("external_path").asText(""),
                lastId);
    }

    /** The heap the job's TaskManagers use, the most of any one, and that one's maximum. */
    public record Heap(long used, long max) {}

    /**
     * The largest heap use of the cluster's TaskManagers (where the job's writers and committer
     * run, HLD 11.2), from one request; empty when the JobManager cannot tell.
     */
    public Optional<Heap> heap(String namespace, String deployment) {
        return get(
                        namespace,
                        deployment,
                        "/taskmanagers/metrics?get=Status.JVM.Memory.Heap.Used,"
                                + "Status.JVM.Memory.Heap.Max&agg=max")
                .flatMap(
                        body -> {
                            Long used = null;
                            Long max = null;
                            for (JsonNode m : body) {
                                String id = m.path("id").asText();
                                if (id.equals("Status.JVM.Memory.Heap.Used")) {
                                    used = m.path("max").asLong();
                                } else if (id.equals("Status.JVM.Memory.Heap.Max")) {
                                    max = m.path("max").asLong();
                                }
                            }
                            return used == null || max == null
                                    ? Optional.empty()
                                    : Optional.of(new Heap(used, max));
                        });
    }

    /**
     * User counters summed over every operator and subtask of the job, by name, from one walk of
     * its vertices; a counter no vertex has (yet) is absent. Empty when the JobManager cannot tell.
     * Flink scopes operator metrics as {@code <operator>.<name>}.
     */
    public Optional<Map<String, Long>> counters(
            String namespace, String deployment, String jobId, List<String> names) {
        Optional<JsonNode> job = get(namespace, deployment, "/jobs/" + jobId);
        if (job.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Long> sums = new HashMap<>();
        for (JsonNode vertex : job.get().path("vertices")) {
            String base =
                    "/jobs/"
                            + jobId
                            + "/vertices/"
                            + vertex.path("id").asText()
                            + "/subtasks/metrics";
            Optional<JsonNode> available = get(namespace, deployment, base);
            if (available.isEmpty()) {
                return Optional.empty();
            }
            for (JsonNode m : available.get()) {
                String id = m.path("id").asText();
                for (String name : names) {
                    if (!id.equals(name) && !id.endsWith("." + name)) {
                        continue;
                    }
                    Optional<JsonNode> value =
                            get(
                                    namespace,
                                    deployment,
                                    base
                                            + "?get="
                                            + URLEncoder.encode(id, StandardCharsets.UTF_8)
                                            + "&agg=sum");
                    if (value.isPresent() && value.get().size() > 0) {
                        sums.merge(name, value.get().get(0).path("sum").asLong(), Long::sum);
                    }
                }
            }
        }
        return Optional.of(sums);
    }

    /**
     * The JSON a GET of {@code path} answers from the deployment's JobManager; empty for any other
     * status, or when it cannot be reached. Tests answer it themselves.
     */
    protected Optional<JsonNode> get(String namespace, String deployment, String path) {
        URI uri = URI.create("http://" + deployment + "-rest." + namespace + ".svc:8081" + path);
        try {
            HttpResponse<String> response =
                    http.send(
                            HttpRequest.newBuilder(uri)
                                    .timeout(Duration.ofSeconds(10))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return Optional.of(JSON.readTree(response.body()));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** The first line of the deepest cause in a Java stack trace, for status messages. */
    public static Optional<String> rootCause(String stacktrace) {
        String last = null;
        for (String line : stacktrace.split("\n")) {
            if (line.startsWith("Caused by: ")) {
                last = line.substring("Caused by: ".length());
            }
        }
        if (last == null) {
            String first = stacktrace.split("\n", 2)[0];
            return first.isBlank() ? Optional.empty() : Optional.of(first);
        }
        return Optional.of(last.length() > 400 ? last.substring(0, 400) : last);
    }
}
