package com.colvenna.lake.jobs.kafka;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.types.DecimalType;

/**
 * Turns one Kafka record into an event row, or into the reason it was rejected. A rejection keeps a
 * fixed reason code and the field it concerns, never the payload or a parser message: either could
 * carry personal data the rejections table must not hold (docs/history/m1/kafka-ingest.md).
 */
public final class EventParser implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_VALUE_BYTES = 1024 * 1024;

    private final EventSpec spec;
    private final int columns;
    private final Set<String> known = new HashSet<>();

    public EventParser(EventSpec spec) {
        this.spec = spec;
        this.columns = spec.eventRowType().getFieldCount();
        for (EventSpec.Field f : spec.fields) {
            known.add(f.name);
        }
        known.add(spec.eventTimeField);
    }

    /** Where a record came from. */
    public static final class Source implements Serializable {
        private static final long serialVersionUID = 1L;
        public String topic;
        public int partition;
        public long offset;

        public Source() {}

        public Source(String topic, int partition, long offset) {
            this.topic = topic;
            this.partition = partition;
            this.offset = offset;
        }
    }

    /** An accepted event: its values in the events table's column order. */
    public static final class Event implements Serializable {
        private static final long serialVersionUID = 1L;
        public Object[] values;
        public long eventTimeMs;
        public Source source;

        public Event() {}
    }

    /** A rejected record. */
    public static final class Rejection implements Serializable {
        private static final long serialVersionUID = 1L;
        public String reason;
        public String field;
        public long detectedAtMs;
        public Source source;

        public Rejection() {}

        Rejection(String reason, String field, long nowMs, Source source) {
            this.reason = reason;
            this.field = field;
            this.detectedAtMs = nowMs;
            this.source = source;
        }
    }

    /** One of the two. */
    public static final class Result {
        public final Event event;
        public final Rejection rejection;

        private Result(Event event, Rejection rejection) {
            this.event = event;
            this.rejection = rejection;
        }

        static Result rejected(String reason, String field, long nowMs, Source source) {
            return new Result(null, new Rejection(reason, field, nowMs, source));
        }
    }

    public Result parse(byte[] value, Source source, long nowMs) {
        if (value == null || value.length == 0 || value.length > MAX_VALUE_BYTES) {
            return Result.rejected("INVALID_SIZE", null, nowMs, source);
        }
        JsonNode root;
        try {
            root = JSON.readTree(value);
        } catch (IOException | RuntimeException e) {
            return Result.rejected("NOT_JSON_OBJECT", null, nowMs, source);
        }
        if (root == null || !root.isObject()) {
            return Result.rejected("NOT_JSON_OBJECT", null, nowMs, source);
        }
        Object[] row = new Object[columns];
        int i = 0;
        for (EventSpec.Field f : spec.fields) {
            JsonNode node = root.get(f.name);
            if (node == null || node.isNull()) {
                if (f.required) {
                    return Result.rejected("MISSING_FIELD", f.name, nowMs, source);
                }
                row[i++] = null;
                continue;
            }
            Object v = convert(f, node);
            if (v == null) {
                return Result.rejected("WRONG_TYPE", f.name, nowMs, source);
            }
            String broken = rule(f, v);
            if (broken != null) {
                return Result.rejected(broken, f.name, nowMs, source);
            }
            row[i++] = v;
        }
        Long eventTime = eventTime(root.get(spec.eventTimeField));
        if (eventTime == null) {
            return Result.rejected("BAD_EVENT_TIME", spec.eventTimeField, nowMs, source);
        }
        if (eventTime < nowMs - spec.acceptPastMs) {
            return Result.rejected("TOO_OLD", spec.eventTimeField, nowMs, source);
        }
        if (eventTime > nowMs + spec.acceptFutureMs) {
            return Result.rejected("TOO_NEW", spec.eventTimeField, nowMs, source);
        }
        Map<String, JsonNode> extra = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!known.contains(e.getKey())) {
                if (!spec.keepUnknown) {
                    return Result.rejected("UNKNOWN_FIELD", null, nowMs, source);
                }
                extra.put(e.getKey(), e.getValue());
            }
        }
        ZonedDateTime utc = Instant.ofEpochMilli(eventTime).atZone(ZoneOffset.UTC);
        row[i++] = eventTime;
        row[i++] = utc.toLocalDate().toString();
        if (spec.hourly) {
            row[i++] = String.format(java.util.Locale.ROOT, "%02d", utc.getHour());
        }
        if (spec.keepUnknown) {
            try {
                row[i++] = extra.isEmpty() ? null : JSON.writeValueAsString(extra);
            } catch (IOException e) {
                return Result.rejected("NOT_JSON_OBJECT", null, nowMs, source);
            }
        }
        row[i++] = source.topic;
        row[i++] = source.partition;
        row[i] = source.offset;
        Event event = new Event();
        event.values = row;
        event.eventTimeMs = eventTime;
        event.source = source;
        return new Result(event, null);
    }

    /** The JSON value as the field's type, or null when it is not one. */
    static Object convert(EventSpec.Field f, JsonNode node) {
        switch (f.type) {
            case "STRING":
                return node.isTextual() ? node.textValue() : null;
            case "BOOLEAN":
                return node.isBoolean() ? node.booleanValue() : null;
            case "INT":
                return node.isIntegralNumber() && node.canConvertToInt() ? node.intValue() : null;
            case "BIGINT":
                return node.isIntegralNumber() && node.canConvertToLong() ? node.longValue() : null;
            case "DOUBLE":
                return node.isNumber() ? node.doubleValue() : null;
            default:
                DecimalType type = (DecimalType) f.dataType();
                BigDecimal d;
                try {
                    d = node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText(""));
                } catch (NumberFormatException e) {
                    return null;
                }
                if (d.scale() > type.getScale()
                        || d.precision() - d.scale() > type.getPrecision() - type.getScale()) {
                    return null;
                }
                return d.setScale(type.getScale());
        }
    }

    /** The rule the value breaks, or null. */
    static String rule(EventSpec.Field f, Object v) {
        if (v instanceof String) {
            String s = (String) v;
            if (f.maxLength != null && s.length() > f.maxLength) {
                return "TOO_LONG";
            }
            if (!f.values.isEmpty() && !f.values.contains(s)) {
                return "NOT_ALLOWED_VALUE";
            }
            return null;
        }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if ((f.min != null && d < f.min) || (f.max != null && d > f.max)) {
                return "OUT_OF_RANGE";
            }
        }
        return null;
    }

    private Long eventTime(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if ("epoch-millis".equals(spec.eventTimeFormat)) {
            return node.isIntegralNumber() && node.canConvertToLong() ? node.longValue() : null;
        }
        if (!node.isTextual()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(node.textValue()).toInstant().toEpochMilli();
        } catch (DateTimeParseException | ArithmeticException e) {
            return null;
        }
    }

    /** Whether an event is behind the watermark: Flink would have closed its window already. */
    public static boolean isLate(long eventTimeMs, long watermarkMs) {
        return watermarkMs != Long.MIN_VALUE && eventTimeMs <= watermarkMs;
    }

    /** The UTC date a rejection is filed under: when it was detected. */
    static String dateOf(long nowMs) {
        return Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }
}
