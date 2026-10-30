package com.colvenna.lake.jobs.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.flink.kafka.shaded.org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.flink.util.Collector;
import org.apache.paimon.flink.action.cdc.CdcSourceRecord;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class DebeziumRecordsTest {
    private static String image(String field, boolean typed) {
        return "{\"type\":\"struct\",\"field\":\""
                + field
                + "\",\"fields\":[{\"type\":\"int64\",\"field\":\"id\""
                + (typed ? ",\"parameters\":{\"__debezium.source.column.type\":\"BIGINT\"}" : "")
                + "}]}";
    }

    private static String schema(boolean typed) {
        return "{\"type\":\"struct\",\"fields\":["
                + image("before", typed)
                + ","
                + image("after", typed)
                + "]}";
    }

    private static String event(String payload) {
        return "{\"schema\":" + schema(true) + ",\"payload\":" + payload + "}";
    }

    private static List<CdcSourceRecord> read(String key, String value) {
        List<CdcSourceRecord> out = new ArrayList<>();
        new DebeziumRecords()
                .deserialize(
                        new ConsumerRecord<>(
                                "cdc.shop.orders",
                                0,
                                42L,
                                key == null ? null : key.getBytes(StandardCharsets.UTF_8),
                                value == null ? null : value.getBytes(StandardCharsets.UTF_8)),
                        new Collector<>() {
                            @Override
                            public void collect(CdcSourceRecord record) {
                                out.add(record);
                            }

                            @Override
                            public void close() {}
                        });
        return out;
    }

    @Test
    void passesDebeziumChangesAsTheParserReadsThem() {
        List<CdcSourceRecord> out =
                read(
                        "{\"id\":1}",
                        event(
                                "{\"op\":\"u\",\"before\":{\"id\":1},\"after\":{\"id\":1},"
                                        + "\"source\":{\"table\":\"orders\"}}"));
        assertEquals(1, out.size());
        assertEquals("cdc.shop.orders", out.get(0).getTopic());
        assertTrue(out.get(0).getValue() instanceof JsonNode);
        assertEquals(1, ((JsonNode) out.get(0).getKey()).path("id").asInt());
        assertEquals(42L, out.get(0).getMetadata("offset"));
    }

    @Test
    void decimalsInTheConvertersDefaultFormBecomeNumbers() throws Exception {
        String column =
                "{\"type\":\"bytes\",\"name\":\"org.apache.kafka.connect.data.Decimal\","
                        + "\"parameters\":{\"scale\":\"2\",\"__debezium.source.column.type\":"
                        + "\"DECIMAL\"},\"field\":\"amount\"}";
        String schema =
                "{\"type\":\"struct\",\"fields\":[{\"type\":\"struct\",\"field\":\"after\","
                        + "\"fields\":["
                        + column
                        + "]}]}";
        // 1234 unscaled is 0x04D2; -1 is 0xFF.
        for (String[] c :
                new String[][] {{"\"BNI=\"", "12.34"}, {"\"/w==\"", "-0.01"}, {"7.50", "7.50"}}) {
            // Base64 text, or (decimal.format NUMERIC) a number, which stays as it is.
            String amount = c[0];
            List<CdcSourceRecord> out =
                    read(
                            null,
                            "{\"schema\":"
                                    + schema
                                    + ",\"payload\":{\"op\":\"c\",\"after\":{\"amount\":"
                                    + amount
                                    + "},\"source\":{\"table\":\"orders\"}}}");
            JsonNode after = ((JsonNode) out.get(0).getValue()).path("payload").path("after");
            assertTrue(after.path("amount").isNumber(), c[0]);
            assertEquals(
                    0,
                    new java.math.BigDecimal(c[1]).compareTo(after.path("amount").decimalValue()));
        }
    }

    @Test
    void dropsWhatIsNotADebeziumChangeWithItsSchema() {
        assertEquals(0, read(null, "not json").size());
        assertEquals(0, read(null, "[1,2]").size());
        // Without the schema every column would be read as a string.
        assertEquals(
                0,
                read(null, "{\"op\":\"c\",\"after\":{\"id\":1},\"source\":{\"table\":\"orders\"}}")
                        .size());
        // A truncate, or a delete without its before image.
        assertEquals(
                0, read(null, event("{\"op\":\"t\",\"source\":{\"table\":\"orders\"}}")).size());
        assertEquals(
                0,
                read(null, event("{\"op\":\"d\",\"before\":null,\"source\":{\"table\":\"o\"}}"))
                        .size());
        assertEquals(0, read("{broken", event("{\"op\":\"c\",\"after\":{}}")).size());
    }

    @Test
    void requiresTheSourcesColumnTypes() {
        // column.propagate.source.type unset: the types would be guessed, and targets widened.
        assertEquals(
                0,
                read(
                                null,
                                "{\"schema\":"
                                        + schema(false)
                                        + ",\"payload\":{\"op\":\"c\",\"after\":{\"id\":1},"
                                        + "\"source\":{\"table\":\"orders\"}}}")
                        .size());
    }

    @Test
    void skipsTheTombstoneAfterADelete() {
        assertEquals(0, read("{\"id\":1}", null).size());
    }
}
