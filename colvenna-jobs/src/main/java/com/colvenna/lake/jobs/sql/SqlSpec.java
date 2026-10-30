package com.colvenna.lake.jobs.sql;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.types.RowType;

/**
 * A declared table's query, as colvenna-operator passes it to the SQL job and to its validation:
 * the target and its primary key, the SELECT that fills it, the upstream tables it may read, which
 * of them the query reads as streams, and the consumer id those streaming reads keep progress
 * under.
 */
public final class SqlSpec {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final Pattern TABLE =
            Pattern.compile("[a-z][a-z0-9_]{0,62}\\.[a-z][a-z0-9_]{0,62}");

    public final String targetDatabase;
    public final String targetTable;
    public final String query;
    public final List<String> primaryKey;

    /** {@code database.table}, as declared. */
    public final List<String> upstream;

    /**
     * The upstream tables the query reads as streams, as validation found; the rest are lookups.
     */
    public final List<String> streams;

    public final String consumerId;

    /** The declared checks and the quarantine of the rows that fail them, or null. */
    final Quality quality;

    private SqlSpec(JsonNode node) {
        targetDatabase = name(node, "targetDatabase");
        targetTable = name(node, "targetTable");
        query = node.path("query").asText("");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must be a SELECT");
        }
        primaryKey = list(node, "primaryKey", NAME, "primaryKey must list column names");
        if (primaryKey.isEmpty()) {
            throw new IllegalArgumentException("primaryKey must name at least one column");
        }
        upstream = list(node, "upstream", TABLE, "upstream must list database.table names");
        if (upstream.isEmpty()) {
            throw new IllegalArgumentException("upstream must name the tables the query reads");
        }
        streams = list(node, "streams", TABLE, "streams must list database.table names");
        if (!upstream.containsAll(streams)) {
            throw new IllegalArgumentException("streams must be upstream tables");
        }
        consumerId = node.path("consumerId").asText("");
        if (!consumerId.matches("[A-Za-z0-9._-]{0,200}")) {
            throw new IllegalArgumentException("consumerId is not a plain identifier");
        }
        quality = Quality.parse(node.path("quality"));
    }

    public static SqlSpec parse(String json) {
        try {
            return new SqlSpec(JSON.readTree(json));
        } catch (IOException e) {
            throw new IllegalArgumentException("The job spec is not JSON", e);
        }
    }

    /** The INSERT the job runs. */
    public String insert() {
        return "INSERT INTO " + table(targetTable) + " " + query;
    }

    /**
     * What the job runs for a target of this type: the INSERT, or with checks declared, both the
     * table's and the quarantine's in one statement set (the query read once).
     */
    public String statement(RowType target) {
        if (quality == null) {
            return insert();
        }
        return "EXECUTE STATEMENT SET BEGIN INSERT INTO "
                + table(targetTable)
                + " "
                + quality.passing(query, target)
                + "; INSERT INTO "
                + table(quality.quarantine)
                + " "
                + quality.failing(query, target)
                + "; END";
    }

    private String table(String name) {
        return "`" + ColvennaCatalog.NAME + "`.`" + targetDatabase + "`.`" + name + "`";
    }

    private static String name(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (!NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a plain lowercase identifier");
        }
        return value;
    }

    private static List<String> list(JsonNode node, String field, Pattern item, String message) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : node.path(field)) {
            if (!item.matcher(n.asText()).matches()) {
                throw new IllegalArgumentException(message);
            }
            out.add(n.asText());
        }
        return Collections.unmodifiableList(out);
    }
}
