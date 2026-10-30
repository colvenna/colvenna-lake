package com.colvenna.lake.jobs.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventParserTest {
    static final long NOW = Instant.parse("2026-10-15T12:00:00Z").toEpochMilli();

    static final String SPEC =
            "{\"bootstrap\":\"k:9093\",\"topics\":[\"events\"],\"group\":\"g\","
                    + "\"database\":\"ods\",\"table\":\"events\",\"rejections\":\"events_rejections\","
                    + "\"fields\":["
                    + "{\"name\":\"event_id\",\"type\":\"STRING\",\"required\":true,\"maxLength\":8},"
                    + "{\"name\":\"event_type\",\"type\":\"STRING\",\"required\":true,"
                    + "\"values\":[\"click\",\"view\"]},"
                    + "{\"name\":\"amount\",\"type\":\"DECIMAL(8,2)\",\"min\":0,\"max\":1000},"
                    + "{\"name\":\"user_id\",\"type\":\"BIGINT\"},"
                    + "{\"name\":\"mobile\",\"type\":\"BOOLEAN\"}],"
                    + "\"eventTime\":{\"field\":\"event_time\"}}";

    static EventParser.Result parse(String json) {
        return new EventParser(EventSpec.parse(SPEC))
                .parse(
                        json == null ? null : json.getBytes(StandardCharsets.UTF_8),
                        new EventParser.Source("events", 1, 42),
                        NOW);
    }

    static String reasonOf(String json) {
        EventParser.Result r = parse(json);
        return r.rejection == null ? null : r.rejection.reason + "/" + r.rejection.field;
    }

    @Test
    void anEventBecomesARowInTheTablesColumnOrder() {
        EventParser.Result r =
                parse(
                        "{\"event_id\":\"e1\",\"event_type\":\"click\",\"amount\":12.5,"
                                + "\"user_id\":7,\"event_time\":\"2026-10-15T11:59:00+02:00\","
                                + "\"campaign\":\"x\"}");
        assertNull(r.rejection);
        Object[] v = r.event.values;
        assertEquals("e1", v[0]);
        assertEquals("click", v[1]);
        assertEquals(new BigDecimal("12.50"), v[2]);
        assertEquals(7L, v[3]);
        assertNull(v[4]);
        assertEquals(Instant.parse("2026-10-15T09:59:00Z").toEpochMilli(), v[5]);
        assertEquals("2026-10-15", v[6]);
        assertEquals("{\"campaign\":\"x\"}", v[7]);
        assertEquals("events", v[8]);
        assertEquals(1, v[9]);
        assertEquals(42L, v[10]);
        assertEquals(EventSpec.parse(SPEC).eventRowType().getFieldCount(), v.length);
    }

    @Test
    void eachBrokenRuleHasItsReasonAndNoPayload() {
        String ok = "\"event_time\":\"2026-10-15T11:00:00Z\"";
        assertEquals("INVALID_SIZE/null", reasonOf(""));
        assertEquals("NOT_JSON_OBJECT/null", reasonOf("[1,2]"));
        assertEquals("NOT_JSON_OBJECT/null", reasonOf("{oops"));
        assertEquals("MISSING_FIELD/event_id", reasonOf("{\"event_type\":\"click\"," + ok + "}"));
        assertEquals(
                "WRONG_TYPE/event_id",
                reasonOf("{\"event_id\":5,\"event_type\":\"click\"," + ok + "}"));
        assertEquals(
                "TOO_LONG/event_id",
                reasonOf("{\"event_id\":\"123456789\",\"event_type\":\"click\"," + ok + "}"));
        assertEquals(
                "NOT_ALLOWED_VALUE/event_type",
                reasonOf("{\"event_id\":\"e\",\"event_type\":\"buy\"," + ok + "}"));
        assertEquals(
                "OUT_OF_RANGE/amount",
                reasonOf("{\"event_id\":\"e\",\"event_type\":\"view\",\"amount\":-1," + ok + "}"));
        assertEquals(
                "WRONG_TYPE/amount",
                reasonOf(
                        "{\"event_id\":\"e\",\"event_type\":\"view\",\"amount\":1.234,"
                                + ok
                                + "}"));
        assertEquals(
                "WRONG_TYPE/user_id",
                reasonOf(
                        "{\"event_id\":\"e\",\"event_type\":\"view\",\"user_id\":1.5," + ok + "}"));
        assertEquals(
                "BAD_EVENT_TIME/event_time",
                reasonOf(
                        "{\"event_id\":\"e\",\"event_type\":\"view\",\"event_time\":\"yesterday\"}"));
        assertEquals(
                "TOO_OLD/event_time",
                reasonOf(
                        "{\"event_id\":\"e\",\"event_type\":\"view\","
                                + "\"event_time\":\"2026-08-01T00:00:00Z\"}"));
        assertEquals(
                "TOO_NEW/event_time",
                reasonOf(
                        "{\"event_id\":\"e\",\"event_type\":\"view\","
                                + "\"event_time\":\"2026-10-18T00:00:00Z\"}"));
    }

    @Test
    void unknownFieldsAreKeptOrRejectedAsDeclared() {
        EventSpec strict =
                EventSpec.parse(
                        SPEC.replace(
                                "\"eventTime\"", "\"unknownFields\":\"reject\",\"eventTime\""));
        EventParser.Result r =
                new EventParser(strict)
                        .parse(
                                ("{\"event_id\":\"e\",\"event_type\":\"view\",\"x\":1,"
                                                + "\"event_time\":\"2026-10-15T11:00:00Z\"}")
                                        .getBytes(StandardCharsets.UTF_8),
                                new EventParser.Source("events", 0, 1),
                                NOW);
        assertEquals("UNKNOWN_FIELD", r.rejection.reason);
        assertEquals(
                strict.eventRowType().getFieldCount() + 1,
                EventSpec.parse(SPEC).eventRowType().getFieldCount());
    }

    @Test
    void aDeclarationOutsideTheSupportedTypesIsRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> EventSpec.parse(SPEC.replace("\"BOOLEAN\"", "\"TIMESTAMP\"")));
        assertThrows(
                IllegalArgumentException.class,
                () -> EventSpec.parse(SPEC.replace("\"mobile\"", "\"Mobile\"")));
    }

    @Test
    void lateIsBehindTheWatermark() {
        assertEquals(false, EventParser.isLate(10, Long.MIN_VALUE));
        assertEquals(true, EventParser.isLate(10, 10));
        assertEquals(false, EventParser.isLate(11, 10));
    }
}
