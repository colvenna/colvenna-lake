package com.colvenna.lake.operator.lineage;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * Where the operator reports lineage, from the environment the Helm chart sets (values {@code
 * lineage.*}): the OpenLineage endpoint, the file with its API key if it takes one, the ConfigMap
 * of what was reported, and how often. Without an endpoint nothing is reported.
 */
public record LineageSettings(URI endpoint, Path apiKeyFile, String state, Duration every) {
    public static Optional<LineageSettings> fromEnvironment(Function<String, String> env) {
        String url = env.apply("COLVENNA_LINEAGE_URL");
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        URI endpoint = URI.create(url);
        if (!"http".equals(endpoint.getScheme()) && !"https".equals(endpoint.getScheme())) {
            throw new IllegalArgumentException("COLVENNA_LINEAGE_URL must be http(s), not " + url);
        }
        String key = env.apply("COLVENNA_LINEAGE_API_KEY_FILE");
        String state = env.apply("COLVENNA_LINEAGE_STATE");
        String seconds = env.apply("COLVENNA_LINEAGE_INTERVAL_SECONDS");
        Duration every =
                Duration.ofSeconds(
                        seconds == null || seconds.isBlank() ? 60 : Long.parseLong(seconds));
        if (every.toSeconds() < 10) {
            throw new IllegalArgumentException(
                    "COLVENNA_LINEAGE_INTERVAL_SECONDS must be at least 10, not " + seconds);
        }
        return Optional.of(
                new LineageSettings(
                        endpoint,
                        key == null || key.isBlank() ? null : Path.of(key),
                        state == null || state.isBlank() ? "colvenna-operator-lineage" : state,
                        every));
    }
}
