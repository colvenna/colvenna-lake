package com.colvenna.lake.jobs.cdc;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.Counter;
import org.apache.flink.util.Collector;
import org.apache.paimon.flink.action.cdc.CdcSourceRecord;
import org.apache.paimon.flink.sink.cdc.RichCdcMultiplexRecord;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What Paimon's MongoDB parser cannot take, or should not. An update whose document was deleted
 * before the change stream looked it up arrives without one; its delete follows, so it is skipped
 * and counted ({@code colvennaUpdateGone}) rather than failing the job on the missing document. A
 * change whose {@code _id} is neither an ObjectId nor a string stops the job naming the collection
 * ({@code SOURCE_UNSUPPORTED_KEY}): admission samples keys, and the target could not keep such a
 * key apart from a string of the same text. colvenna-operator then takes only that collection out
 * of the job. Dropped and renamed collections never reach here (Flink CDC passes only document
 * changes on); colvenna-operator sees them in the source's collections and their UUIDs.
 */
public final class MongoChangeGuard
        extends RichFlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(MongoChangeGuard.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> parser;
    private transient Counter updateGone;

    public MongoChangeGuard(FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> parser) {
        this.parser = parser;
    }

    @Override
    public void open(Configuration parameters) {
        updateGone = getRuntimeContext().getMetricGroup().counter("colvennaUpdateGone");
    }

    @Override
    public void flatMap(CdcSourceRecord record, Collector<RichCdcMultiplexRecord> out)
            throws Exception {
        if (skip(record)) {
            if (updateGone != null) {
                updateGone.inc();
            }
            return;
        }
        parser.flatMap(record, out);
    }

    /** Whether the change is an update of a document already gone; throws on an unkept key. */
    static boolean skip(CdcSourceRecord record) throws Exception {
        JsonNode root = JSON.readTree((String) record.getValue());
        String op = root.path("operationType").asText("");
        String collection = root.path("ns").path("coll").asText("unknown");
        JsonNode key = root.path("documentKey");
        if (key.isTextual()) {
            JsonNode id = JSON.readTree(key.asText()).path("_id");
            if (!id.isMissingNode() && !id.isTextual() && !id.path("$oid").isTextual()) {
                throw new IllegalStateException(
                        "SOURCE_UNSUPPORTED_KEY table=" + collection + " id=" + id.getNodeType());
            }
        }
        if (op.equals("update")) {
            JsonNode document = root.path("fullDocument");
            if (document.isMissingNode() || document.isNull() || "null".equals(document.asText())) {
                LOG.info("UPDATE_OF_DELETED_DOCUMENT table={}: its delete follows", collection);
                return true;
            }
        }
        return false;
    }
}
