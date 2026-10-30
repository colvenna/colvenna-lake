package com.colvenna.lake.operator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Toleration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Operator configuration, from the environment the Helm chart sets ({@code
 * deploy/helm/colvenna-operator}).
 */
public record OperatorSettings(
        String catalogUri,
        Path catalogTokenFile,
        Path catalogCaFile,
        String checkpointRoot,
        String flinkServiceAccount,
        Map<String, String> jobImages,
        Map<String, String> jobNodeSelector,
        List<Toleration> jobTolerations,
        int serverIdFirst,
        int serverIdLast,
        long reconcileMaxRows,
        int metricsPort) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static OperatorSettings fromEnvironment(Function<String, String> env) {
        String range = optional(env, "COLVENNA_MYSQL_SERVER_ID_RANGE", "6000-6999");
        if (!range.matches("[0-9]{1,9}-[0-9]{1,9}")) {
            throw new IllegalArgumentException(
                    "COLVENNA_MYSQL_SERVER_ID_RANGE must be <first>-<last>, not " + range);
        }
        String[] bounds = range.split("-", 2);
        try {
            return new OperatorSettings(
                    required(env, "COLVENNA_CATALOG_URI"),
                    Path.of(required(env, "COLVENNA_CATALOG_TOKEN_FILE")),
                    Path.of(required(env, "COLVENNA_CATALOG_CA_FILE")),
                    stripSlash(required(env, "COLVENNA_CHECKPOINT_ROOT")),
                    optional(env, "COLVENNA_FLINK_SERVICE_ACCOUNT", "colvenna-flink"),
                    JSON.readValue(
                            required(env, "COLVENNA_JOB_IMAGES"),
                            new TypeReference<Map<String, String>>() {}),
                    JSON.readValue(
                            optional(env, "COLVENNA_JOB_NODE_SELECTOR", "{}"),
                            new TypeReference<Map<String, String>>() {}),
                    JSON.readValue(
                            optional(env, "COLVENNA_JOB_TOLERATIONS", "[]"),
                            new TypeReference<List<Toleration>>() {}),
                    Integer.parseInt(bounds[0]),
                    Integer.parseInt(bounds[1]),
                    Long.parseLong(optional(env, "COLVENNA_RECONCILE_MAX_ROWS", "2000000")),
                    Integer.parseInt(optional(env, "COLVENNA_METRICS_PORT", "9100")));
        } catch (IOException e) {
            throw new UncheckedIOException("Invalid operator settings", e);
        }
    }

    public String jobImage(String sourceType) {
        String image = jobImages.get(sourceType);
        if (image == null) {
            throw new IllegalStateException("No job image configured for " + sourceType);
        }
        return image;
    }

    private static String stripSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String required(Function<String, String> env, String name) {
        String value = env.apply(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value;
    }

    private static String optional(Function<String, String> env, String name, String fallback) {
        String value = env.apply(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
