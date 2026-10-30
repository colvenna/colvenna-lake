package com.colvenna.lake.jobs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The colvenna-catalog options every job uses: the REST catalog with the job's own bearer token and
 * per-table data tokens. colvenna-operator mounts the token and sets {@code COLVENNA_CATALOG_URI}
 * and {@code COLVENNA_CATALOG_TOKEN_FILE}.
 */
public final class Catalogs {

    private Catalogs() {}

    public static Map<String, String> restOptions() throws IOException {
        Map<String, String> options = new HashMap<>();
        options.put("metastore", "rest");
        options.put("uri", required("COLVENNA_CATALOG_URI"));
        options.put("warehouse", "colvenna");
        options.put("token.provider", "bear");
        options.put(
                "token",
                Files.readString(
                                Path.of(required("COLVENNA_CATALOG_TOKEN_FILE")),
                                StandardCharsets.UTF_8)
                        .trim());
        options.put("data-token.enabled", "true");
        return options;
    }

    public static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value;
    }
}
