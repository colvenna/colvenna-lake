package com.colvenna.lake.jobs.cdc;

import java.util.List;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.util.Collector;
import org.apache.paimon.flink.action.cdc.CdcSourceRecord;
import org.apache.paimon.flink.sink.cdc.RichCdcMultiplexRecord;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Fails CDC before parsing a change that cannot produce a correct {@code input} changelog (HLD
 * 7.4), so the breach stops the job instead of corrupting the table. The MySQL and PostgreSQL
 * source guards ({@code docs/history/m1/mysql-cdc.md}, {@code docs/history/m1/postgres-cdc.md})
 * with the version column and key taken from the declaration.
 *
 * <p>The stock parsers emit DELETE(old) then INSERT(new) for every UPDATE. If the new version is
 * not greater, the DELETE removes the current Paimon row while {@code sequence.field} correctly
 * discards the INSERT. A before image without the version or key (PostgreSQL without {@code REPLICA
 * IDENTITY FULL}) cannot produce the changelog's before image at all.
 */
public final class SourceVersionGuard
        implements FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> {
    private static final long serialVersionUID = 1L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> delegate;
    private final String versionColumn;
    private final List<String> keys;
    private final boolean fullBeforeImage;

    /**
     * @param keys the primary key; when {@code fullBeforeImage}, updates must keep it and deletes
     *     and updates must carry it and the version in their before image.
     */
    public SourceVersionGuard(
            FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> delegate,
            String versionColumn,
            List<String> keys,
            boolean fullBeforeImage) {
        this.delegate = delegate;
        this.versionColumn = versionColumn;
        this.keys = List.copyOf(keys);
        this.fullBeforeImage = fullBeforeImage;
    }

    private long version(JsonNode row, String image, String table) {
        JsonNode value = row.path(versionColumn);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalStateException(
                    (fullBeforeImage && image.equals("before")
                                    ? "SOURCE_BEFORE_IMAGE_INCOMPLETE"
                                    : "SOURCE_VERSION_MISSING")
                            + " table="
                            + table
                            + " column="
                            + versionColumn
                            + " image="
                            + image);
        }
        return value.longValue();
    }

    private void requireKeys(JsonNode row, String table) {
        for (String key : keys) {
            if (row.path(key).isMissingNode() || row.path(key).isNull()) {
                throw new IllegalStateException(
                        "SOURCE_BEFORE_IMAGE_INCOMPLETE table=" + table + " key=" + key);
            }
        }
    }

    @Override
    public void flatMap(CdcSourceRecord source, Collector<RichCdcMultiplexRecord> out)
            throws Exception {
        check(source);
        delegate.flatMap(source, out);
    }

    /** Throws when the change breaks the source contract. */
    public void check(CdcSourceRecord source) throws Exception {
        Object raw = source.getValue();
        JsonNode root;
        if (raw instanceof JsonNode) {
            // From Kafka, already read (DebeziumRecords).
            root = (JsonNode) raw;
        } else if (raw instanceof String) {
            root = JSON.readTree((String) raw);
        } else {
            throw new IllegalArgumentException("CDC record is not Debezium JSON");
        }
        JsonNode payload = root.path("payload");
        String operation = payload.path("op").asText();
        // Named in every failure so colvenna-operator can tell which table to take out of the job.
        String table = payload.path("source").path("table").asText("unknown");
        if (operation.equals("u")) {
            JsonNode before = payload.path("before");
            JsonNode after = payload.path("after");
            if (fullBeforeImage) {
                requireKeys(before, table);
                for (String key : keys) {
                    if (!before.path(key).equals(after.path(key))) {
                        throw new IllegalStateException(
                                "SOURCE_KEY_CHANGED table=" + table + " key=" + key);
                    }
                }
            }
            long from = version(before, "before", table);
            long to = version(after, "after", table);
            if (to <= from) {
                throw new IllegalStateException(
                        "NON_MONOTONIC_SOURCE_VERSION table="
                                + table
                                + " before="
                                + from
                                + " after="
                                + to);
            }
        } else if (operation.equals("d") && fullBeforeImage) {
            requireKeys(payload.path("before"), table);
            version(payload.path("before"), "before", table);
        }
    }
}
