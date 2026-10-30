package com.colvenna.lake.jobs.cdc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What colvenna-operator tells a CDC job to do, passed as the job's single JSON argument. Secrets
 * are not part of it: the source user and password arrive as {@code COLVENNA_SOURCE_USER} and
 * {@code COLVENNA_SOURCE_PASSWORD} from a Kubernetes Secret.
 */
public final class CdcSpec {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_$]{1,64}");

    /** The Paimon database the tables are written to. */
    public final String targetDatabase;

    /** PostgreSQL only: the Paimon table the source table (or its partitions) is written to. */
    public final String targetTable;

    public final String host;
    public final int port;
    public final String database;

    /** PostgreSQL only. */
    public final String schema;

    public final String slot;
    public final String publication;

    /** MySQL only: the server-id range of the job's readers, {@code <first>-<last>}. */
    public final String serverId;

    public final String serverTimeZone;

    /** The source tables, by exact name. */
    public final List<String> tables;

    public final List<String> primaryKeys;

    /** The version column; null for MongoDB. */
    public final String versionColumn;

    /** MongoDB only: the database the user is defined in. */
    public final String authSource;

    /**
     * The source's CA bundle (PEM) in the job's pod, when the declaration names one: the server
     * certificate and host name are then verified. Without it, TLS is required, unverified for
     * MySQL and PostgreSQL and checked against the JVM's default trust store for MongoDB.
     */
    public final String sourceCa;

    public final Map<String, String> tableOptions;

    /** Set when the changes come from the user's Kafka instead of the database's log. */
    public final Kafka kafka;

    /** Debezium JSON topics on a user's Kafka (HLD 7.6): one topic per table. */
    public static final class Kafka {
        public final String bootstrap;

        /** Each topic's target table, in the declaration's order. */
        public final Map<String, String> targets;

        public final String group;
        public final String mechanism;
        public final String caFile;

        /**
         * How far before the targets' last write a job without state starts reading: longer than
         * the previous writer's lag when it stopped, so nothing it had not written is skipped.
         */
        public final long overlapMillis;

        Kafka(JsonNode node) {
            bootstrap = text(node, "bootstrap", true);
            Map<String, String> t = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : iterable(node.path("targets").fields())) {
                if (!e.getKey().matches("[A-Za-z0-9._-]{1,249}")
                        || !NAME.matcher(e.getValue().asText()).matches()) {
                    throw new IllegalArgumentException(
                            "kafka.targets must map topic names to table names");
                }
                t.put(e.getKey(), e.getValue().asText());
            }
            if (t.isEmpty()) {
                throw new IllegalArgumentException("kafka.targets must name the tables' topics");
            }
            targets = Collections.unmodifiableMap(t);
            group = text(node, "group", true);
            mechanism = node.path("mechanism").asText("SCRAM-SHA-512");
            if (!mechanism.matches("SCRAM-SHA-(256|512)")) {
                throw new IllegalArgumentException("kafka.mechanism must be SCRAM-SHA-256 or -512");
            }
            caFile = text(node, "caFile", false);
            overlapMillis = node.path("overlapMillis").asLong(600_000L);
            if (overlapMillis < 60_000L || overlapMillis > 86_400_000L) {
                throw new IllegalArgumentException("kafka.overlapMillis must be 1 minute to 1 day");
            }
        }

        public List<String> topics() {
            return new ArrayList<>(targets.keySet());
        }
    }

    private static <T> Iterable<T> iterable(java.util.Iterator<T> it) {
        return () -> it;
    }

    private CdcSpec(JsonNode node) {
        targetDatabase = name(node, "targetDatabase", true);
        targetTable = name(node, "targetTable", false);
        host = text(node, "host", true);
        port = node.path("port").asInt(0);
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be 1-65535");
        }
        database = name(node, "database", true);
        schema = name(node, "schema", false);
        slot = name(node, "slot", false);
        publication = name(node, "publication", false);
        serverId = text(node, "serverId", false);
        if (serverId != null && !serverId.matches("[0-9]{1,10}(-[0-9]{1,10})?")) {
            throw new IllegalArgumentException("serverId must be <first>-<last>");
        }
        serverTimeZone = text(node, "serverTimeZone", false);
        tables = names(node, "tables");
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("tables must name at least one source table");
        }
        primaryKeys = names(node, "primaryKeys");
        // MongoDB has none: its change stream is ordered.
        versionColumn = name(node, "versionColumn", false);
        authSource = text(node, "authSource", false);
        if (authSource != null && !authSource.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("authSource is not a database name");
        }
        sourceCa = text(node, "sourceCa", false);
        if (sourceCa != null && !sourceCa.matches("/[A-Za-z0-9/._-]+")) {
            throw new IllegalArgumentException("sourceCa must be an absolute path");
        }
        Map<String, String> options = new LinkedHashMap<>();
        node.path("tableOptions")
                .fields()
                .forEachRemaining(e -> options.put(e.getKey(), e.getValue().asText()));
        tableOptions = Collections.unmodifiableMap(options);
        kafka = node.hasNonNull("kafka") ? new Kafka(node.path("kafka")) : null;
    }

    /** The database sources' contract needs the version column. */
    public void requireVersionColumn() {
        if (versionColumn == null) {
            throw new IllegalArgumentException("Missing versionColumn");
        }
    }

    public static CdcSpec parse(String json) {
        try {
            return new CdcSpec(JSON.readTree(json));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("The job spec is not JSON", e);
        }
    }

    /** The exact source tables as a Java regex, for the action's including-tables option. */
    public String tableRegex() {
        List<String> quoted = new ArrayList<>();
        for (String table : tables) {
            quoted.add(Pattern.quote(table));
        }
        return String.join("|", quoted);
    }

    private static String text(JsonNode node, String field, boolean required) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                throw new IllegalArgumentException("Missing " + field);
            }
            return null;
        }
        if (!value.isTextual() || value.asText().isEmpty()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return value.asText();
    }

    private static String name(JsonNode node, String field, boolean required) {
        String value = text(node, field, required);
        if (value != null && !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a plain identifier: " + value);
        }
        return value;
    }

    private static List<String> names(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : node.path(field)) {
            if (!value.isTextual() || !NAME.matcher(value.asText()).matches()) {
                throw new IllegalArgumentException(field + " must list plain identifiers");
            }
            values.add(value.asText());
        }
        return Collections.unmodifiableList(values);
    }
}
