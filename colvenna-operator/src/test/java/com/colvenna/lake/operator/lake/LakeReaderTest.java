package com.colvenna.lake.operator.lake;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.source.Keys;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.data.Timestamp;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.BatchWriteBuilder;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.types.DataTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deletes written by key on a local table shaped as a Kafka-carried CDC target. */
class LakeReaderTest {
    @TempDir Path warehouse;

    private FileStoreTable table() throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options));
        catalog.createDatabase("ods", true);
        catalog.createTable(
                Identifier.create("ods", "orders"),
                Schema.newBuilder()
                        .column("id", DataTypes.BIGINT().notNull())
                        .column("state", DataTypes.STRING())
                        .column("version_no", DataTypes.BIGINT().notNull())
                        .primaryKey("id")
                        .option("bucket", "1")
                        .option("sequence.field", "version_no")
                        .option("changelog-producer", "lookup")
                        .build(),
                false);
        return (FileStoreTable) catalog.getTable(Identifier.create("ods", "orders"));
    }

    /** As the table's writer would, but without its lookup compaction (no local disk here). */
    private static void write(FileStoreTable t, GenericRow... rows) throws Exception {
        BatchWriteBuilder builder = t.copy(Map.of("write-only", "true")).newBatchWriteBuilder();
        try (BatchTableWrite w = builder.newWrite();
                BatchTableCommit c = builder.newCommit()) {
            for (GenericRow row : rows) {
                w.write(row);
            }
            c.commit(w.prepareCommit());
        }
    }

    private static GenericRow row(long id, String state, long version) {
        return GenericRow.of(id, BinaryString.fromString(state), version);
    }

    private static List<String> rows(FileStoreTable t) throws Exception {
        List<String> out = new ArrayList<>();
        ReadBuilder read = t.newReadBuilder();
        try (RecordReader<InternalRow> reader =
                read.newRead().createReader(read.newScan().plan())) {
            reader.forEachRemaining(r -> out.add(r.getLong(0) + ":" + r.getLong(2)));
        }
        out.sort(null);
        return out;
    }

    @Test
    void deletesRowsByKeyAVersionAboveWhatAReplayBrings() throws Exception {
        FileStoreTable t = table();
        write(t, row(1, "paid", 3), row(2, "created", 1), row(3, "created", 2));
        int deleted =
                LakeReader.deleteByKey(
                        t,
                        List.of("id"),
                        "version_no",
                        Set.of(Keys.encode(List.of("1")), Keys.encode(List.of("9"))));
        assertThat(deleted).isEqualTo(1);
        assertThat(rows(t)).containsExactly("2:1", "3:2");
        // A replay of the row's last change does not bring it back: the delete is a version above.
        write(t, row(1, "paid", 3));
        assertThat(rows(t)).containsExactly("2:1", "3:2");
        // Its commit is the repair's, which a writer's start does not count as its last write.
        assertThat(t.snapshotManager().snapshot(2).commitUser())
                .startsWith(LakeReader.REPAIR_COMMIT_USER);
        // Nothing to delete: no commit.
        long snapshot = t.snapshotManager().latestSnapshotId();
        assertThat(
                        LakeReader.deleteByKey(
                                t, List.of("id"), "version_no", Set.of(Keys.encode(List.of("9")))))
                .isZero();
        assertThat(t.snapshotManager().latestSnapshotId()).isEqualTo(snapshot);
        assertThat(t.options()).doesNotContainEntry("write-only", "true");
    }

    private static void writeIds(FileStoreTable t, long... ids) throws Exception {
        BatchWriteBuilder builder = t.newBatchWriteBuilder();
        try (BatchTableWrite write = builder.newWrite();
                BatchTableCommit commit = builder.newCommit()) {
            for (long id : ids) {
                write.write(GenericRow.of(id, BinaryString.fromString("row-" + id)));
            }
            commit.commit(write.prepareCommit());
        }
    }

    @Test
    void aTablesFilesAreCountedWithWhatItsLatestDataCommitAdded() throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        try (Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options))) {
            catalog.createDatabase("ods", false);
            Identifier id = Identifier.create("ods", "orders");
            catalog.createTable(
                    id,
                    Schema.newBuilder()
                            .column("id", DataTypes.BIGINT().notNull())
                            .column("name", DataTypes.STRING())
                            .primaryKey("id")
                            .option("bucket", "2")
                            .option("write-only", "true")
                            .build(),
                    false);
            FileStoreTable t = (FileStoreTable) catalog.getTable(id);
            assertThat(LakeReader.count(t)).isEmpty();
            writeIds(t, 1, 2, 3, 4);
            writeIds(t, 5);
            LakeReader.FileCount f = LakeReader.count(t).orElseThrow();
            assertThat(f.snapshots()).isEqualTo(2);
            assertThat(f.level0()).isEqualTo(f.files());
            assertThat(f.bytes()).isPositive();
            assertThat(f.manifestBytes()).isPositive();
            // The second commit wrote one row, to one bucket: one file.
            assertThat(f.commitFiles()).isEqualTo(1);
            assertThat(f.commitBytes()).isPositive().isLessThan(f.bytes());
            // Every file is far under the target size.
            assertThat(f.small()).isEqualTo(f.files());
        }
    }

    @Test
    void aHeartbeatTablesDataCommitsAreReadWithTheBeatEachHolds() throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        try (Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options))) {
            catalog.createDatabase("ods", false);
            Identifier id = Identifier.create("ods", "colvenna_heartbeat");
            catalog.createTable(
                    id,
                    Schema.newBuilder()
                            .column("id", DataTypes.INT().notNull())
                            .column("beat_at", DataTypes.TIMESTAMP(6))
                            .primaryKey("id")
                            .option("bucket", "1")
                            .build(),
                    false);
            FileStoreTable t = (FileStoreTable) catalog.getTable(id);
            assertThat(LakeReader.heartbeats(t, null, null).commits()).isEmpty();
            for (long ms : new long[] {1_000, 31_000, 61_000}) {
                BatchWriteBuilder builder = t.newBatchWriteBuilder();
                try (BatchTableWrite write = builder.newWrite();
                        BatchTableCommit commit = builder.newCommit()) {
                    write.write(GenericRow.of(1, Timestamp.fromEpochMillis(ms)));
                    commit.commit(write.prepareCommit());
                }
            }
            // The first time, the latest only.
            LakeReader.Beats first = LakeReader.heartbeats(t, null, null);
            assertThat(first.commits())
                    .extracting(LakeReader.Beat::beatMillis)
                    .containsExactly(61_000L);
            assertThat(first.latest().beatMillis()).isEqualTo(61_000L);
            // After a snapshot: the later ones, oldest first.
            long one = 1;
            long oneMillis = t.snapshotManager().snapshot(one).timeMillis();
            assertThat(LakeReader.heartbeats(t, one, oneMillis).commits())
                    .extracting(LakeReader.Beat::beatMillis)
                    .containsExactly(31_000L, 61_000L);
            // Made again: ids beyond the latest, or another commit under the id looked at.
            assertThat(LakeReader.heartbeats(t, 99L, null).restarted()).isTrue();
            assertThat(LakeReader.heartbeats(t, one, oneMillis + 1).restarted()).isTrue();
            assertThat(LakeReader.heartbeats(t, one, oneMillis).restarted()).isFalse();
        }
    }

    /** A primary-key table that keeps Iceberg metadata beside it, as a published table does. */
    private FileStoreTable icebergTable() throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options));
        catalog.createDatabase("ods", true);
        catalog.createTable(
                Identifier.create("ods", "published"),
                Schema.newBuilder()
                        .column("id", DataTypes.BIGINT().notNull())
                        .column("state", DataTypes.STRING())
                        .column("version_no", DataTypes.BIGINT().notNull())
                        .primaryKey("id")
                        .option("bucket", "1")
                        .option("metadata.iceberg.storage", "table-location")
                        .option("metadata.iceberg.previous-versions-max", "30")
                        .build(),
                false);
        return (FileStoreTable) catalog.getTable(Identifier.create("ods", "published"));
    }

    private static void fullCompaction(FileStoreTable t) throws Exception {
        BatchWriteBuilder builder = t.newBatchWriteBuilder();
        try (BatchTableWrite w = builder.newWrite();
                BatchTableCommit c = builder.newCommit()) {
            w.compact(org.apache.paimon.data.BinaryRow.EMPTY_ROW, 0, true);
            c.commit(w.prepareCommit());
        }
    }

    private static long latest(FileStoreTable t) {
        return t.snapshotManager().latestSnapshotId();
    }

    @Test
    void anIcebergViewRebuiltWithoutItsBaseMissesUncompactedBuckets() throws Exception {
        FileStoreTable t = icebergTable();
        write(t, row(1, "created", 1), row(2, "created", 1));
        // Paimon's first metadata takes the splits that need no merge, compacted or not: it
        // holds the level 0 file the table's fully compacted view does not.
        LakeReader.IcebergFiles first = LakeReader.icebergFiles(t, latest(t));
        assertThat(first.held()).hasSize(1);
        assertThat(first.expected()).isEmpty();
        assertThat(first.complete()).isFalse();
        // A full compaction: the view is the compacted file, and stays it while changes arrive.
        fullCompaction(t);
        assertThat(LakeReader.icebergFiles(t, latest(t)).complete()).isTrue();
        write(t, row(1, "shipped", 2));
        assertThat(LakeReader.icebergFiles(t, latest(t)).complete()).isTrue();
        // A manifest compaction commits without callbacks: no metadata for its snapshot, so the
        // next commit's metadata starts over, and the bucket with an uncompacted change is gone.
        try (BatchTableCommit c = t.newBatchWriteBuilder().newCommit()) {
            c.compactManifests();
        }
        long manifests = latest(t);
        assertThat(
                        t.fileIO()
                                .exists(
                                        new org.apache.paimon.fs.Path(
                                                t.location(),
                                                "metadata/v" + manifests + ".metadata.json")))
                .isFalse();
        write(t, row(2, "shipped", 2));
        LakeReader.IcebergFiles rebuilt = LakeReader.icebergFiles(t, latest(t));
        assertThat(rebuilt.held()).isEmpty();
        assertThat(rebuilt.expected()).hasSize(1);
        assertThat(rebuilt.complete()).isFalse();
        // The next full compaction puts it right.
        fullCompaction(t);
        assertThat(LakeReader.icebergFiles(t, latest(t)).complete()).isTrue();
    }
}
