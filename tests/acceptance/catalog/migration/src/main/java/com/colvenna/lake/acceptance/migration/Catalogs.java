package com.colvenna.lake.acceptance.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.options.Options;

/**
 * The catalog the tools read: colvenna-catalog with data tokens ({@code COLVENNA_CATALOG_URI},
 * {@code COLVENNA_CATALOG_TOKEN_FILE}).
 */
final class Catalogs {

    private Catalogs() {}

    static Catalog open(String mode) throws IOException {
        if (!mode.equals("rest")) {
            throw new IllegalArgumentException("Unknown catalog " + mode);
        }
        return CatalogFactory.createCatalog(CatalogContext.create(Options.fromMap(restOptions())));
    }

    static Map<String, String> restOptions() throws IOException {
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

    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value;
    }
}
