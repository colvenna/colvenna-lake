package com.colvenna.lake.jobs.cdc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Base64;
import java.util.Map;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.kafka.shaded.org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.flink.metrics.Counter;
import org.apache.flink.util.Collector;
import org.apache.paimon.flink.action.cdc.CdcSourceRecord;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka records as Paimon's Debezium JSON parser reads them, minus those it cannot: a record that
 * is not a Debezium change with its schema is dropped and counted ({@code colvennaUnparsable}), its
 * topic, partition and offset logged and never its payload. Paimon's own deserializer logs the
 * payload and stops the job on the first such record, for good. Decimals in the JSON converter's
 * default form (base64 of the unscaled value) become numbers, the only form the parser reads.
 */
final class DebeziumRecords implements KafkaRecordDeserializationSchema<CdcSourceRecord> {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(DebeziumRecords.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private transient Counter unparsable;

    @Override
    public void open(DeserializationSchema.InitializationContext context) {
        unparsable = context.getMetricGroup().counter("colvennaUnparsable");
    }

    @Override
    public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<CdcSourceRecord> out) {
        if (record.value() == null) {
            // The tombstone Debezium writes after a delete, for log compaction: not a change.
            return;
        }
        JsonNode key;
        JsonNode value;
        try {
            key =
                    record.key() == null || record.key().length == 0
                            ? null
                            : JSON.readTree(record.key());
            value = JSON.readTree(record.value());
            requireChange(value);
            numericDecimals(value);
        } catch (Exception e) {
            if (unparsable != null) {
                unparsable.inc();
            }
            LOG.warn(
                    "UNPARSABLE_CDC_EVENT topic={} partition={} offset={}",
                    record.topic(),
                    record.partition(),
                    record.offset());
            return;
        }
        out.collect(
                new CdcSourceRecord(
                        record.topic(),
                        key,
                        value,
                        Map.of("partition", record.partition(), "offset", record.offset())));
    }

    /**
     * A Debezium change event with its schema (the connector's JSON converter with schemas
     * enabled), and the source's column types in it ({@code column.propagate.source.type}). Without
     * the schema the parser takes every column for a string; without the source types it takes a
     * VARCHAR(32) for a STRING, and would widen the target's columns to match. Throws otherwise.
     */
    static void requireChange(JsonNode value) {
        if (value == null
                || !value.path("schema").isObject()
                || !value.path("payload").isObject()) {
            throw new IllegalArgumentException("not Debezium JSON with its schema");
        }
        JsonNode payload = value.path("payload");
        String op = payload.path("op").asText("");
        String image = op.equals("d") ? "before" : "after";
        boolean ok = (op.equals("d") || op.matches("[cru]")) && payload.path(image).isObject();
        if (!ok || !payload.path("source").path("table").isTextual()) {
            throw new IllegalArgumentException("not a Debezium row change");
        }
        if (!sourceTypes(value.path("schema"), image)) {
            throw new IllegalArgumentException("the schema lacks the source's column types");
        }
    }

    /**
     * Rewrites the images' DECIMAL columns sent as base64 (the JSON converter's default {@code
     * decimal.format}) as numbers. Throws if one is not base64.
     */
    static void numericDecimals(JsonNode value) {
        for (JsonNode image : value.path("schema").path("fields")) {
            JsonNode row = value.path("payload").path(image.path("field").asText());
            if (!row.isObject()) {
                continue;
            }
            for (JsonNode column : image.path("fields")) {
                String name = column.path("field").asText();
                JsonNode v = row.path(name);
                if (!"org.apache.kafka.connect.data.Decimal".equals(column.path("name").asText())
                        || !v.isTextual()) {
                    continue;
                }
                // A Decimal sent as text is base64: the converter writes NUMERIC as a number.
                int scale = Integer.parseInt(column.path("parameters").path("scale").asText("0"));
                ((ObjectNode) row)
                        .put(
                                name,
                                new BigDecimal(
                                        new BigInteger(Base64.getDecoder().decode(v.asText())),
                                        scale));
            }
        }
    }

    /** Whether every column of the image's schema carries its source type. */
    static boolean sourceTypes(JsonNode schema, String image) {
        for (JsonNode field : schema.path("fields")) {
            if (image.equals(field.path("field").asText())) {
                JsonNode columns = field.path("fields");
                if (!columns.isArray() || columns.size() == 0) {
                    return false;
                }
                for (JsonNode column : columns) {
                    if (!column.path("parameters")
                            .path("__debezium.source.column.type")
                            .isTextual()) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public TypeInformation<CdcSourceRecord> getProducedType() {
        return TypeInformation.of(CdcSourceRecord.class);
    }
}
