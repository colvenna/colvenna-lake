package com.colvenna.lake.jobs.kafka;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowType;

/**
 * A declared event stream, as colvenna-operator passes it to the Kafka job: where the events come
 * from, the fields they carry and the rules each must keep, and the two tables they land in (the
 * events, and one row per rejected record).
 */
public final class EventSpec implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final Pattern DECIMAL =
            Pattern.compile("DECIMAL\\((\\d{1,2}),\\s*(\\d{1,2})\\)");

    /** Columns the job adds to every event row, after the declared fields. */
    static final String EVENT_TIME = "event_time_ms";

    static final String EXTRA = "extra_json";
    static final String TOPIC = "_topic";
    static final String PARTITION = "_partition";
    static final String OFFSET = "_offset";

    public final String bootstrap;
    public final List<String> topics;
    public final String group;
    public final String mechanism;
    public final String caFile;
    public final String startingOffsets;
    public final String database;
    public final String table;
    public final String rejections;
    public final List<Field> fields;
    public final String eventTimeField;
    public final String eventTimeFormat;
    public final long maxOutOfOrdernessMs;
    public final long idlenessMs;
    public final long acceptPastMs;
    public final long acceptFutureMs;
    public final boolean keepUnknown;
    public final boolean hourly;

    /** One declared field and its rules. */
    public static final class Field implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String name;
        public final String type;
        public final boolean required;
        public final Integer maxLength;
        public final List<String> values;
        public final Double min;
        public final Double max;

        Field(JsonNode node) {
            name = name(node.path("name").asText(""), "field name");
            type = node.path("type").asText("").toUpperCase(java.util.Locale.ROOT);
            EventSpec.dataType(type);
            required = node.path("required").asBoolean(false);
            maxLength = node.hasNonNull("maxLength") ? node.path("maxLength").asInt() : null;
            List<String> v = new ArrayList<>();
            node.path("values").forEach(n -> v.add(n.asText()));
            values = Collections.unmodifiableList(v);
            min = node.hasNonNull("min") ? node.path("min").asDouble() : null;
            max = node.hasNonNull("max") ? node.path("max").asDouble() : null;
        }

        DataType dataType() {
            return EventSpec.dataType(type);
        }
    }

    private EventSpec(JsonNode node) {
        bootstrap = text(node, "bootstrap");
        List<String> t = new ArrayList<>();
        node.path("topics").forEach(n -> t.add(n.asText()));
        if (t.isEmpty()) {
            throw new IllegalArgumentException("topics must name at least one topic");
        }
        topics = Collections.unmodifiableList(t);
        group = text(node, "group");
        mechanism = node.path("mechanism").asText("SCRAM-SHA-512");
        if (!mechanism.matches("SCRAM-SHA-(256|512)|PLAIN")) {
            throw new IllegalArgumentException(
                    "mechanism must be SCRAM-SHA-256, SCRAM-SHA-512 or PLAIN");
        }
        caFile = node.path("caFile").asText(null);
        startingOffsets = node.path("startingOffsets").asText("earliest");
        if (!startingOffsets.matches("earliest|latest")) {
            throw new IllegalArgumentException("startingOffsets must be earliest or latest");
        }
        database = name(text(node, "database"), "database");
        table = name(text(node, "table"), "table");
        rejections = name(text(node, "rejections"), "rejections");
        List<Field> f = new ArrayList<>();
        for (JsonNode n : node.path("fields")) {
            f.add(new Field(n));
        }
        if (f.isEmpty()) {
            throw new IllegalArgumentException("fields must declare at least one field");
        }
        fields = Collections.unmodifiableList(f);
        eventTimeField = name(node.path("eventTime").path("field").asText(""), "eventTime.field");
        eventTimeFormat = node.path("eventTime").path("format").asText("rfc3339");
        if (!eventTimeFormat.matches("rfc3339|epoch-millis")) {
            throw new IllegalArgumentException("eventTime.format must be rfc3339 or epoch-millis");
        }
        maxOutOfOrdernessMs = node.path("maxOutOfOrdernessMs").asLong(300_000);
        idlenessMs = node.path("idlenessMs").asLong(60_000);
        acceptPastMs = node.path("acceptPastMs").asLong(30L * 24 * 3600 * 1000);
        acceptFutureMs = node.path("acceptFutureMs").asLong(24L * 3600 * 1000);
        keepUnknown = !"reject".equals(node.path("unknownFields").asText("keep"));
        hourly = "hourly".equals(node.path("partitioning").asText("daily"));
    }

    public static EventSpec parse(String json) {
        try {
            return new EventSpec(JSON.readTree(json));
        } catch (IOException e) {
            throw new IllegalArgumentException("The job spec is not JSON", e);
        }
    }

    /** The events table's columns, in order. */
    public RowType eventRowType() {
        List<DataField> out = new ArrayList<>();
        for (Field f : fields) {
            out.add(new DataField(out.size(), f.name, f.dataType()));
        }
        out.add(new DataField(out.size(), EVENT_TIME, DataTypes.BIGINT().notNull()));
        out.add(new DataField(out.size(), "dt", DataTypes.STRING().notNull()));
        if (hourly) {
            out.add(new DataField(out.size(), "hr", DataTypes.STRING().notNull()));
        }
        if (keepUnknown) {
            out.add(new DataField(out.size(), EXTRA, DataTypes.STRING()));
        }
        out.addAll(sourceFields(out.size()));
        return new RowType(out);
    }

    /** The rejections table's columns: why, and where the record was; never its payload. */
    public static RowType rejectionRowType() {
        List<DataField> out = new ArrayList<>();
        out.add(new DataField(0, "dt", DataTypes.STRING().notNull()));
        out.add(new DataField(1, "reason", DataTypes.STRING().notNull()));
        out.add(new DataField(2, "field", DataTypes.STRING()));
        out.add(new DataField(3, "detected_at_ms", DataTypes.BIGINT().notNull()));
        out.addAll(sourceFields(4));
        return new RowType(out);
    }

    private static List<DataField> sourceFields(int first) {
        return List.of(
                new DataField(first, TOPIC, DataTypes.STRING().notNull()),
                new DataField(first + 1, PARTITION, DataTypes.INT().notNull()),
                new DataField(first + 2, OFFSET, DataTypes.BIGINT().notNull()));
    }

    static DataType dataType(String type) {
        switch (type) {
            case "STRING":
                return DataTypes.STRING();
            case "BOOLEAN":
                return DataTypes.BOOLEAN();
            case "INT":
                return DataTypes.INT();
            case "BIGINT":
                return DataTypes.BIGINT();
            case "DOUBLE":
                return DataTypes.DOUBLE();
            default:
                Matcher m = DECIMAL.matcher(type);
                if (m.matches()) {
                    int precision = Integer.parseInt(m.group(1));
                    int scale = Integer.parseInt(m.group(2));
                    if (precision >= 1 && precision <= 38 && scale <= precision) {
                        return DataTypes.DECIMAL(precision, scale);
                    }
                }
                throw new IllegalArgumentException(
                        "type "
                                + type
                                + " is not one of STRING, BOOLEAN, INT, BIGINT, DOUBLE,"
                                + " DECIMAL(p,s)");
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String name(String value, String what) {
        if (!NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " is not a plain lowercase identifier");
        }
        return value;
    }
}
