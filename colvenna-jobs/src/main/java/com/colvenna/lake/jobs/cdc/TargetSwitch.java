package com.colvenna.lake.jobs.cdc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.schema.SchemaChange;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.BatchWriteBuilder;
import org.apache.paimon.table.source.DataSplit;
import org.apache.paimon.table.source.Split;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves a target written from the database's log to the Kafka transport's changelog (lookup,
 * deduplicated). The direct writer is write-only, so the target's rows sit in level-0 files no
 * compaction has reached; a lookup changelog would publish every one of them again when its first
 * compaction does. So the table is first compacted fully while its changelog is still the input
 * one, which publishes nothing, and only then switched. Runs before the Kafka job writes, once: a
 * switched table is left alone.
 */
final class TargetSwitch {
    private static final Logger LOG = LoggerFactory.getLogger(TargetSwitch.class);

    /** What the Kafka transport's targets keep (colvenna-operator declares the same). */
    static final Map<String, String> KAFKA_CHANGELOG =
            Map.of(
                    "changelog-producer", "lookup",
                    "changelog-producer.row-deduplicate", "true",
                    "write-only", "false");

    private TargetSwitch() {}

    static boolean switched(FileStoreTable table) {
        Map<String, String> options = table.options();
        for (Map.Entry<String, String> e : KAFKA_CHANGELOG.entrySet()) {
            if (!e.getValue().equals(options.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** Compacts and switches the table, unless it is switched already or does not exist. */
    static void apply(Catalog catalog, Identifier id) throws Exception {
        FileStoreTable table;
        try {
            table = (FileStoreTable) catalog.getTable(id);
        } catch (Catalog.TableNotExistException e) {
            return;
        }
        if (switched(table)) {
            return;
        }
        int compacted = compact(table);
        List<SchemaChange> changes = new ArrayList<>();
        KAFKA_CHANGELOG.forEach((k, v) -> changes.add(SchemaChange.setOption(k, v)));
        catalog.alterTable(id, changes, false);
        LOG.info(
                "TARGET_SWITCHED table={} compactedBuckets={} options={}",
                id,
                compacted,
                KAFKA_CHANGELOG);
    }

    /** A full compaction of every bucket with data, under the table's own changelog setting. */
    static int compact(FileStoreTable table) throws Exception {
        if (table.snapshotManager().latestSnapshot() == null) {
            return 0;
        }
        // Compaction is skipped by a write-only writer; the changelog setting stays as it is.
        FileStoreTable writer = table.copy(Map.of("write-only", "false"));
        Set<String> seen = new HashSet<>();
        List<DataSplit> buckets = new ArrayList<>();
        for (Split split : writer.newReadBuilder().newScan().plan().splits()) {
            DataSplit d = (DataSplit) split;
            if (seen.add(d.partition() + "/" + d.bucket())) {
                buckets.add(d);
            }
        }
        BatchWriteBuilder builder = writer.newBatchWriteBuilder();
        try (BatchTableWrite write = builder.newWrite();
                BatchTableCommit commit = builder.newCommit()) {
            for (DataSplit d : buckets) {
                BinaryRow partition = d.partition();
                write.compact(partition, d.bucket(), true);
            }
            commit.commit(write.prepareCommit());
        }
        return buckets.size();
    }
}
