package com.colvenna.lake.jobs.cdc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.Counter;
import org.apache.flink.util.Collector;
import org.apache.paimon.flink.action.cdc.CdcSourceRecord;
import org.apache.paimon.flink.sink.cdc.RichCdcMultiplexRecord;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;

/**
 * What Kafka adds to CDC: a row's changes seen again (a collector's at-least-once delivery, a
 * replay, the overlap a job without state rereads) or out of order. The source contract is checked
 * first ({@link SourceVersionGuard}): a breach stops the job, stale or not. A change is stale when
 * its version is not above one already seen for its row, or not above the version a target deleted;
 * stale changes are dropped and counted ({@code colvennaStale}). The rest go on: what this subtask
 * does not remember, the target's version order ({@code sequence.field}) and its deduplicated
 * lookup changelog absorb, so neither the table nor its changelog changes. Only a delete forgotten
 * here could not be absorbed (the row would come back until its delete is replayed), which is why
 * the targets' recent deletes are given to the guard ({@link TargetHistory}).
 */
public final class KafkaEventGuard
        extends RichFlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> {
    private static final long serialVersionUID = 1L;
    static final int REMEMBERED_KEYS = 200_000;

    private final FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> parser;
    private final SourceVersionGuard contract;
    private final String versionColumn;
    private final Map<String, String> targets;
    private final TargetHistory history;
    private final int unverified;
    private transient Counter stale;
    private transient Counter resnapshot;
    private transient Set<String> streamed;
    private transient Map<String, Long> seen;

    /**
     * @param parser Paimon's parser, which the changes that are not stale reach
     * @param contract the source contract every change is checked against first
     * @param targets each topic's target table
     * @param history the targets at the start: their key fields, the rows they deleted recently,
     *     and their last write. A snapshot record read later than that is the collector
     *     snapshotting again ({@code colvennaResnapshot}): rows it deleted while it had no position
     *     never reach the topic.
     * @param unverified partitions whose start skipped unknown records, counted once ({@code
     *     colvennaUnverified}) for the operator to warn about
     */
    public KafkaEventGuard(
            FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> parser,
            SourceVersionGuard contract,
            String versionColumn,
            Map<String, String> targets,
            TargetHistory history,
            int unverified) {
        this.parser = parser;
        this.contract = contract;
        this.versionColumn = versionColumn;
        this.targets = targets;
        this.history = history;
        this.unverified = unverified;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        stale = getRuntimeContext().getMetricGroup().counter("colvennaStale");
        resnapshot = getRuntimeContext().getMetricGroup().counter("colvennaResnapshot");
        Counter skipped = getRuntimeContext().getMetricGroup().counter("colvennaUnverified");
        if (getRuntimeContext().getIndexOfThisSubtask() == 0) {
            skipped.inc(unverified);
        }
        seen = recentVersions();
    }

    /** The versions last seen, for the rows seen most recently. */
    static Map<String, Long> recentVersions() {
        return new LinkedHashMap<>(1024, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > REMEMBERED_KEYS;
            }
        };
    }

    @Override
    public void flatMap(CdcSourceRecord record, Collector<RichCdcMultiplexRecord> out)
            throws Exception {
        contract.check(record);
        String target = targets.get(record.getTopic());
        JsonNode payload = ((JsonNode) record.getValue()).path("payload");
        if (target != null
                && resnapshot(record.getTopic() + "#" + record.getMetadata("partition"), payload)) {
            resnapshot.inc();
        }
        if (target != null
                && isStale(
                        seen,
                        history.deleted,
                        target,
                        history.keyFields.get(target),
                        record.getKey(),
                        payload,
                        versionColumn)) {
            stale.inc();
            return;
        }
        parser.flatMap(record, out);
    }

    /**
     * Whether the change is a snapshot record (Debezium's {@code source.snapshot} true, first,
     * last...) in a partition that already streamed here, or taken after the targets were last
     * written: the collector snapshotted again. An incremental snapshot keeps the collector's
     * position, and loses nothing. Records that are not snapshot records mark their partition
     * streamed; partitions, not tables: one partition's stream may be read before another's
     * snapshot.
     */
    boolean resnapshot(String partition, JsonNode payload) {
        String snapshot = payload.path("source").path("snapshot").asText("false");
        if (streamed == null) {
            streamed = new HashSet<>();
        }
        if (snapshot.equals("false")) {
            streamed.add(partition);
            return false;
        }
        return !snapshot.equals("incremental")
                && (streamed.contains(partition)
                        || (history.lastWriteMillis != null
                                && payload.path("source").path("ts_ms").asLong(0)
                                        > history.lastWriteMillis));
    }

    /** A row's identity across Kafka and Paimon: its target and key values. */
    static String rowKey(String target, List<String> values) {
        StringBuilder key = new StringBuilder(target);
        for (String v : values) {
            key.append('|').append(v.length()).append(':').append(v);
        }
        return key.toString();
    }

    /**
     * Whether the change brings a version not above one seen (or deleted) for its row. A delete
     * carries the version it removes, so the row's current version is not stale for it. Changes
     * without the version are left to the version guard.
     */
    static boolean isStale(
            Map<String, Long> seen,
            Map<String, Long> deleted,
            String target,
            List<String> keyFields,
            Object recordKey,
            JsonNode payload,
            String versionColumn) {
        boolean delete = payload.path("op").asText().equals("d");
        JsonNode image = payload.path(delete ? "before" : "after");
        JsonNode version = image.path(versionColumn);
        if (!version.isIntegralNumber() || !version.canConvertToLong()) {
            return false;
        }
        String row;
        if (keyFields != null && !keyFields.isEmpty()) {
            List<String> values = new ArrayList<>(keyFields.size());
            for (String field : keyFields) {
                JsonNode v = image.path(field);
                if (!v.isValueNode() || v.isNull()) {
                    // Not this row's key (a field named otherwise): no judgement.
                    return false;
                }
                values.add(v.asText());
            }
            row = rowKey(target, values);
        } else if (recordKey != null) {
            // A table not created yet: Debezium keys each record by the row's primary key.
            row = target + "#" + recordKey;
        } else {
            return false;
        }
        long v = version.longValue();
        Long gone = deleted.get(row);
        if (gone != null && v <= gone) {
            return true;
        }
        Long last = seen.get(row);
        if (last != null && (delete ? v < last : v <= last)) {
            return true;
        }
        seen.put(row, last == null ? v : Math.max(last, v));
        return false;
    }
}
