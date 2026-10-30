package com.colvenna.lake.operator.lake;

import com.colvenna.lake.operator.source.Keys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.apache.paimon.CoreOptions;
import org.apache.paimon.Snapshot;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.consumer.Consumer;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.data.Timestamp;
import org.apache.paimon.io.DataFileMeta;
import org.apache.paimon.manifest.FileKind;
import org.apache.paimon.manifest.ManifestEntry;
import org.apache.paimon.manifest.ManifestFile;
import org.apache.paimon.manifest.ManifestFileMeta;
import org.apache.paimon.manifest.ManifestList;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.BatchWriteBuilder;
import org.apache.paimon.table.source.DataSplit;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypeChecks;
import org.apache.paimon.types.RowKind;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.InternalRowUtils;

/**
 * Reads target tables through colvenna-catalog with the operator's own identity and per-table data
 * tokens, the same path the jobs write through.
 */
public final class LakeReader implements AutoCloseable {
    private final String uri;
    private final Path tokenFile;
    private String token;
    private Catalog catalog;

    public LakeReader(String uri, Path tokenFile) throws IOException {
        this.uri = uri;
        this.tokenFile = tokenFile;
        catalog();
    }

    /**
     * The catalog client for the operator's current token: the Paimon client never rereads its
     * bearer token, so a rotated Secret means a new client.
     */
    private synchronized Catalog catalog() throws IOException {
        String current = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
        if (catalog == null || !current.equals(token)) {
            if (catalog != null) {
                try {
                    catalog.close();
                } catch (Exception e) {
                    // The old client is being replaced either way.
                }
            }
            Options options = new Options();
            options.set("metastore", "rest");
            options.set("uri", uri);
            options.set("warehouse", "colvenna");
            options.set("token.provider", "bear");
            options.set("token", current);
            options.set("data-token.enabled", "true");
            // Every round decides on what the table is now: a cached table keeps its options,
            // schema and snapshots for up to 30 minutes (Paimon's cache.expire-after-write).
            options.set("cache-enabled", "false");
            catalog = CatalogFactory.createCatalog(CatalogContext.create(options));
            token = current;
        }
        return catalog;
    }

    /** The latest snapshot of a table and its row count after merging, if it has one. */
    public record Head(long snapshotId, long commitMillis, long commitIdentifier) {}

    public Optional<Head> head(String database, String table)
            throws Catalog.TableNotExistException, IOException {
        FileStoreTable t = (FileStoreTable) catalog().getTable(Identifier.create(database, table));
        Snapshot latest = t.snapshotManager().latestSnapshot();
        return latest == null
                ? Optional.empty()
                : Optional.of(
                        new Head(latest.id(), latest.timeMillis(), latest.commitIdentifier()));
    }

    /** The rows at the table's latest snapshot (append tables), or 0 without one. */
    public long rows(String database, String table)
            throws Catalog.TableNotExistException, IOException {
        FileStoreTable t = (FileStoreTable) catalog().getTable(Identifier.create(database, table));
        Snapshot latest = t.snapshotManager().latestSnapshot();
        return latest == null ? 0 : latest.totalRecordCount();
    }

    /** How many rows have each value of a string column, reading at most {@code limit} rows. */
    public Map<String, Long> countBy(String database, String table, String column, long limit)
            throws Exception {
        Table t = catalog().getTable(Identifier.create(database, table));
        ReadBuilder read =
                t.newReadBuilder()
                        .withProjection(new int[] {index(t.rowType().getFieldNames(), column)});
        Map<String, Long> out = new java.util.TreeMap<>();
        long[] seen = {0};
        try (RecordReader<InternalRow> reader =
                read.newRead().createReader(read.newScan().plan())) {
            RecordReader.RecordIterator<InternalRow> batch;
            while ((batch = reader.readBatch()) != null && seen[0] < limit) {
                InternalRow row;
                while ((row = batch.next()) != null && seen[0]++ < limit) {
                    out.merge(row.isNullAt(0) ? "" : row.getString(0).toString(), 1L, Long::sum);
                }
                batch.releaseBatch();
            }
        }
        return out;
    }

    /** A quarantine's rows that failed one check, and when the newest of them was flagged. */
    public record Flagged(long rows, Long newestMillis) {}

    /**
     * A quarantine table's rows by the check they failed ({@code colvenna_rule}), with the newest
     * {@code colvenna_flagged_at} of each, reading at most {@code limit} rows.
     */
    public Map<String, Flagged> quarantined(String database, String table, long limit)
            throws Exception {
        Table t = catalog().getTable(Identifier.create(database, table));
        List<String> names = t.rowType().getFieldNames();
        int at = index(names, "colvenna_flagged_at");
        DataType type = t.rowType().getTypeAt(at);
        ReadBuilder read =
                t.newReadBuilder().withProjection(new int[] {index(names, "colvenna_rule"), at});
        Map<String, long[]> counted = new java.util.TreeMap<>();
        long seen = 0;
        try (RecordReader<InternalRow> reader =
                read.newRead().createReader(read.newScan().plan())) {
            RecordReader.RecordIterator<InternalRow> batch;
            while ((batch = reader.readBatch()) != null && seen < limit) {
                InternalRow row;
                while ((row = batch.next()) != null && seen++ < limit) {
                    long[] c =
                            counted.computeIfAbsent(
                                    row.isNullAt(0) ? "" : row.getString(0).toString(),
                                    k -> new long[] {0, Long.MIN_VALUE});
                    c[0]++;
                    if (!row.isNullAt(1)) {
                        c[1] =
                                Math.max(
                                        c[1],
                                        row.getTimestamp(1, DataTypeChecks.getPrecision(type))
                                                .getMillisecond());
                    }
                }
                batch.releaseBatch();
            }
        }
        Map<String, Flagged> out = new java.util.TreeMap<>();
        counted.forEach(
                (rule, c) ->
                        out.put(rule, new Flagged(c[0], c[1] == Long.MIN_VALUE ? null : c[1])));
        return out;
    }

    /** Adds a nullable column after another, as a declared field that grew. */
    public void addColumn(
            String database,
            String table,
            String column,
            org.apache.paimon.types.DataType type,
            String after)
            throws Exception {
        catalog()
                .alterTable(
                        Identifier.create(database, table),
                        List.of(
                                org.apache.paimon.schema.SchemaChange.addColumn(
                                        column,
                                        type.copy(true),
                                        null,
                                        org.apache.paimon.schema.SchemaChange.Move.after(
                                                column, after))),
                        false);
    }

    /** A table's columns, primary key and options. */
    public record TableInfo(
            List<DataField> fields, List<String> primaryKey, Map<String, String> options) {}

    public Optional<TableInfo> table(String database, String table) throws IOException {
        try {
            Table t = catalog().getTable(Identifier.create(database, table));
            return Optional.of(
                    new TableInfo(t.rowType().getFields(), t.primaryKeys(), t.options()));
        } catch (Catalog.TableNotExistException e) {
            return Optional.empty();
        }
    }

    /** Creates a table; fails if it exists. */
    public void createTable(
            String database,
            String table,
            List<DataField> fields,
            List<String> primaryKey,
            Map<String, String> options)
            throws Exception {
        createTable(database, table, fields, primaryKey, List.of(), options);
    }

    /** Creates a table with partition keys; fails if it exists. */
    public void createTable(
            String database,
            String table,
            List<DataField> fields,
            List<String> primaryKey,
            List<String> partitionKeys,
            Map<String, String> options)
            throws Exception {
        Schema.Builder schema = Schema.newBuilder();
        for (DataField f : fields) {
            schema.column(f.name(), f.type(), f.description());
        }
        schema.primaryKey(primaryKey).partitionKeys(partitionKeys).options(options);
        catalog().createTable(Identifier.create(database, table), schema.build(), false);
    }

    /**
     * A reader's position on a table: the snapshot it reads next, the latest, and when the next one
     * was committed while the reader is behind (its changes are readable there and not yet in the
     * reader's own commits: a consumer advances as the reader's checkpoint completes).
     */
    public record Position(Long nextSnapshot, Long latestSnapshot, Long unreadSince) {}

    public Optional<Position> position(String database, String table, String consumerId)
            throws IOException {
        try {
            FileStoreTable t =
                    (FileStoreTable) catalog().getTable(Identifier.create(database, table));
            Long next =
                    t.consumerManager()
                            .consumer(consumerId)
                            .map(Consumer::nextSnapshot)
                            .orElse(null);
            Long latest = t.snapshotManager().latestSnapshotId();
            Long earliest = t.snapshotManager().earliestSnapshotId();
            Long since = null;
            if (next != null && latest != null && next <= latest) {
                // Expired before the reader got to it: it has waited at least since the earliest.
                long from = earliest == null ? next : Math.max(next, earliest);
                since =
                        t.snapshotManager().snapshotExists(from)
                                ? t.snapshotManager().snapshot(from).timeMillis()
                                : null;
            }
            return Optional.of(new Position(next, latest, since));
        } catch (Catalog.TableNotExistException e) {
            return Optional.empty();
        }
    }

    /**
     * A table's files (HLD 11.2): the snapshots it keeps; its latest snapshot's data files, their
     * bytes, the level-0 ones and the small ones, and its manifests' bytes; the files the latest
     * data commit added and their bytes.
     */
    public record FileCount(
            long snapshots,
            long files,
            long level0,
            long small,
            long bytes,
            long manifestBytes,
            long commitFiles,
            long commitBytes) {}

    /**
     * {@link FileCount} of a table, from its latest snapshot's manifests; empty when it has none.
     */
    public Optional<FileCount> files(String database, String table) throws Exception {
        FileStoreTable t;
        try {
            t = (FileStoreTable) catalog().getTable(Identifier.create(database, table));
        } catch (Catalog.TableNotExistException e) {
            return Optional.empty();
        }
        return count(t);
    }

    /** {@link FileCount} of a table; empty when it has no snapshot. */
    static Optional<FileCount> count(FileStoreTable t) throws Exception {
        Long latest = t.snapshotManager().latestSnapshotId();
        Long earliest = t.snapshotManager().earliestSnapshotId();
        if (latest == null || earliest == null) {
            return Optional.empty();
        }
        long target = CoreOptions.fromMap(t.options()).targetFileSize(!t.primaryKeys().isEmpty());
        long files = 0;
        long level0 = 0;
        long small = 0;
        long bytes = 0;
        for (DataSplit split : t.newSnapshotReader().read().dataSplits()) {
            for (DataFileMeta file : split.dataFiles()) {
                files++;
                bytes += file.fileSize();
                if (file.level() == 0 && !t.primaryKeys().isEmpty()) {
                    level0++;
                }
                if (file.fileSize() < target / 4) {
                    small++;
                }
            }
        }
        ManifestList manifestList = t.store().manifestListFactory().create();
        long manifestBytes = 0;
        for (ManifestFileMeta m :
                manifestList.readDataManifests(t.snapshotManager().snapshot(latest))) {
            manifestBytes += m.fileSize();
        }
        // The latest data commit (a compaction's rewrites are not what a writer commits).
        long commitFiles = 0;
        long commitBytes = 0;
        Snapshot data = lastAppend(t);
        if (data != null) {
            ManifestFile manifests = t.store().manifestFileFactory().create();
            for (ManifestFileMeta m : manifestList.readDeltaManifests(data)) {
                for (ManifestEntry e : manifests.read(m.fileName(), m.fileSize())) {
                    if (e.kind() == FileKind.ADD) {
                        commitFiles++;
                        commitBytes += e.file().fileSize();
                    }
                }
            }
        }
        return Optional.of(
                new FileCount(
                        latest - earliest + 1,
                        files,
                        level0,
                        small,
                        bytes,
                        manifestBytes,
                        commitFiles,
                        commitBytes));
    }

    /** When the table's latest data commit (not a compaction) was made; empty without one. */
    public Optional<Long> lastDataCommit(String database, String table) throws Exception {
        FileStoreTable t;
        try {
            t = (FileStoreTable) catalog().getTable(Identifier.create(database, table));
        } catch (Catalog.TableNotExistException e) {
            return Optional.empty();
        }
        return Optional.ofNullable(lastAppend(t)).map(Snapshot::timeMillis);
    }

    /** The latest snapshot a writer's data commit made, or null. */
    private static Snapshot lastAppend(FileStoreTable t) {
        Long id = t.snapshotManager().latestSnapshotId();
        Long earliest = t.snapshotManager().earliestSnapshotId();
        for (; id != null && earliest != null && id >= earliest; id--) {
            if (t.snapshotManager().snapshotExists(id)) {
                Snapshot s = t.snapshotManager().snapshot(id);
                if (s.commitKind() == Snapshot.CommitKind.APPEND) {
                    return s;
                }
            }
        }
        return null;
    }

    /** A data commit of a heartbeat table: the newest heartbeat it holds, and when it was made. */
    public record Beat(long snapshot, long beatMillis, long commitMillis) {}

    /**
     * What a round reads of a heartbeat table: its data commits after the last one looked at,
     * oldest first; its latest data commit; and whether the table was made again (its snapshots
     * started over), so the commits are its first ones.
     */
    public record Beats(List<Beat> commits, Beat latest, boolean restarted) {}

    /** Data commits of a heartbeat table read each round at most: the rest wait for the next. */
    static final int BEATS_READ = 10;

    /**
     * The heartbeat table's data commits after snapshot {@code after}, committed at {@code
     * afterMillis} (a compaction's commit holds the same beats, later: it is skipped), the oldest
     * {@link #BEATS_READ}; the first time, its latest only. Empty before the first beat arrives.
     */
    public Beats heartbeats(String database, String table, Long after, Long afterMillis)
            throws Exception {
        FileStoreTable t;
        try {
            t = (FileStoreTable) catalog().getTable(Identifier.create(database, table));
        } catch (Catalog.TableNotExistException e) {
            return new Beats(List.of(), null, false);
        }
        return heartbeats(t, after, afterMillis);
    }

    static Beats heartbeats(FileStoreTable t, Long after, Long afterMillis) throws Exception {
        Snapshot last = lastAppend(t);
        Beat latest = null;
        if (last != null) {
            Long beat = beatAsOf(t, last);
            latest = beat == null ? null : new Beat(last.id(), beat, last.timeMillis());
        }
        Long newest = t.snapshotManager().latestSnapshotId();
        Long earliest = t.snapshotManager().earliestSnapshotId();
        // Made again: its ids start over, or the one looked at last is another commit now.
        boolean restarted =
                after != null
                        && (newest == null
                                || after > newest
                                || afterMillis != null
                                        && t.snapshotManager().snapshotExists(after)
                                        && t.snapshotManager().snapshot(after).timeMillis()
                                                != afterMillis);
        if (after == null || restarted || latest == null) {
            return new Beats(latest == null ? List.of() : List.of(latest), latest, restarted);
        }
        List<Beat> commits = new ArrayList<>();
        for (long id = Math.max(after + 1, earliest);
                id <= newest && commits.size() < BEATS_READ;
                id++) {
            if (!t.snapshotManager().snapshotExists(id)) {
                continue;
            }
            Snapshot s = t.snapshotManager().snapshot(id);
            if (s.commitKind() != Snapshot.CommitKind.APPEND) {
                continue;
            }
            Long beat = beatAsOf(t, s);
            if (beat != null) {
                commits.add(new Beat(s.id(), beat, s.timeMillis()));
            }
        }
        return new Beats(commits, latest, false);
    }

    /** The newest {@code beat_at} a snapshot of the heartbeat table holds, or null. */
    private static Long beatAsOf(FileStoreTable t, Snapshot snapshot) throws Exception {
        FileStoreTable asOf = t.copy(Map.of("scan.snapshot-id", Long.toString(snapshot.id())));
        int column = index(asOf.rowType().getFieldNames(), "beat_at");
        DataType type = asOf.rowType().getTypeAt(column);
        ReadBuilder read = asOf.newReadBuilder().withProjection(new int[] {column});
        Long beat = null;
        try (RecordReader<InternalRow> reader =
                read.newRead().createReader(read.newScan().plan())) {
            RecordReader.RecordIterator<InternalRow> batch;
            while ((batch = reader.readBatch()) != null) {
                InternalRow row;
                while ((row = batch.next()) != null) {
                    if (!row.isNullAt(0)) {
                        Timestamp ts = row.getTimestamp(0, DataTypeChecks.getPrecision(type));
                        beat = Math.max(beat == null ? 0 : beat, ts.getMillisecond());
                    }
                }
                batch.releaseBatch();
            }
        }
        return beat;
    }

    /** When a snapshot of a table was committed, or null when it is gone. */
    public Long snapshotMillis(String database, String table, long id) throws Exception {
        FileStoreTable t = (FileStoreTable) catalog().getTable(Identifier.create(database, table));
        return t.snapshotManager().snapshotExists(id)
                ? t.snapshotManager().snapshot(id).timeMillis()
                : null;
    }

    /** Deletes a consumer, so it no longer keeps the table's snapshots from expiring. */
    public void dropConsumer(String database, String table, String consumerId) throws IOException {
        try {
            FileStoreTable t =
                    (FileStoreTable) catalog().getTable(Identifier.create(database, table));
            t.consumerManager().deleteConsumer(consumerId);
        } catch (Catalog.TableNotExistException e) {
            // Nothing to keep alive.
        }
    }

    /** Commits of {@link #deleteRows}: a writer's start ignores them as its targets' last write. */
    public static final String REPAIR_COMMIT_USER = "colvenna-repair";

    /**
     * Deletes rows by key ({@link Keys#encode}), as their source deleted them. Only while the
     * table's writer is stopped: a running writer would not see the commit, and a resumed one reads
     * the table's files afresh, so its compaction puts the deletes in the changelog. Each delete
     * carries the row's values with its version one above, so that a replay of the row's last
     * change cannot bring it back. Returns how many rows were deleted.
     */
    public int deleteRows(
            String database,
            String table,
            List<String> keyColumns,
            String versionColumn,
            Set<String> keys)
            throws Exception {
        return deleteByKey(
                (FileStoreTable) catalog().getTable(Identifier.create(database, table)),
                keyColumns,
                versionColumn,
                keys);
    }

    static int deleteByKey(
            FileStoreTable table, List<String> keyColumns, String versionColumn, Set<String> keys)
            throws Exception {
        FileStoreTable t =
                table.copy(Map.of("write-only", "true", "commit.user-prefix", REPAIR_COMMIT_USER));
        RowType rowType = t.rowType();
        int[] key = indexes(rowType, keyColumns);
        int version = index(rowType.getFieldNames(), versionColumn);
        List<InternalRow> rows = new ArrayList<>();
        ReadBuilder read = t.newReadBuilder();
        try (RecordReader<InternalRow> reader =
                read.newRead().createReader(read.newScan().plan())) {
            RecordReader.RecordIterator<InternalRow> batch;
            while ((batch = reader.readBatch()) != null) {
                InternalRow row;
                while ((row = batch.next()) != null) {
                    if (keys.contains(key(row, key, rowType))) {
                        GenericRow copy =
                                (GenericRow) InternalRowUtils.copyInternalRow(row, rowType);
                        copy.setField(version, row.getLong(version) + 1);
                        copy.setRowKind(RowKind.DELETE);
                        rows.add(copy);
                    }
                }
                batch.releaseBatch();
            }
        }
        if (rows.isEmpty()) {
            return 0;
        }
        BatchWriteBuilder builder = t.newBatchWriteBuilder();
        try (BatchTableWrite write = builder.newWrite();
                BatchTableCommit commit = builder.newCommit()) {
            for (InternalRow row : rows) {
                write.write(row);
            }
            commit.commit(write.prepareCommit());
        }
        return rows.size();
    }

    /** A row's key as {@link Keys#encode} writes it, from the key columns at these positions. */
    private static String key(InternalRow row, int[] positions, RowType rowType) {
        List<String> values = new ArrayList<>(positions.length);
        for (int i : positions) {
            Object value = InternalRowUtils.get(row, i, rowType.getTypeAt(i));
            values.add(value == null ? null : value.toString());
        }
        return Keys.encode(values);
    }

    private static int[] indexes(RowType rowType, List<String> columns) {
        int[] out = new int[columns.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = index(rowType.getFieldNames(), columns.get(i));
        }
        return out;
    }

    /** The primary key of a table as Paimon stores it. */
    public List<String> primaryKey(String database, String table)
            throws Catalog.TableNotExistException, IOException {
        return catalog().getTable(Identifier.create(database, table)).primaryKeys();
    }

    /**
     * Every row's key and version at the latest snapshot, keyed like the source probe's ({@link
     * Keys#encode}); the version is -1 where null.
     */
    public Map<String, Long> versions(
            String database,
            String table,
            List<String> keyColumns,
            String versionColumn,
            long limit)
            throws Exception {
        Table t = catalog().getTable(Identifier.create(database, table));
        RowType rowType = t.rowType();
        List<String> columns = new ArrayList<>(keyColumns);
        // Without a version column (MongoDB), only the keys are read; every version is 0.
        if (versionColumn != null) {
            columns.add(versionColumn);
        }
        int[] projection = indexes(rowType, columns);
        RowType projected = rowType.project(projection);
        int[] key = new int[keyColumns.size()];
        for (int i = 0; i < key.length; i++) {
            key[i] = i;
        }
        ReadBuilder read = t.newReadBuilder().withProjection(projection);
        Map<String, Long> out = new HashMap<>();
        try (RecordReader<InternalRow> reader =
                read.newRead().createReader(read.newScan().plan())) {
            RecordReader.RecordIterator<InternalRow> batch;
            while ((batch = reader.readBatch()) != null) {
                InternalRow row;
                while ((row = batch.next()) != null) {
                    if (out.size() >= limit) {
                        batch.releaseBatch();
                        throw new IllegalStateException(
                                table + " has more than " + limit + " rows");
                    }
                    int v = keyColumns.size();
                    out.put(
                            key(row, key, projected),
                            versionColumn == null ? 0L : row.isNullAt(v) ? -1L : row.getLong(v));
                }
                batch.releaseBatch();
            }
        }
        return out;
    }

    private static int index(List<String> names, String column) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(column)) {
                return i;
            }
        }
        throw new IllegalArgumentException("No column " + column);
    }

    /** Every row of a small table at its latest snapshot, as column to Java value. */
    public List<Map<String, Object>> readAll(String database, String table) throws Exception {
        return readAll(database, table, Map.of());
    }

    /** The rows of these partitions (column to value) of a small table, as column to Java value. */
    public List<Map<String, Object>> readAll(
            String database, String table, Map<String, String> partition) throws Exception {
        Table t = catalog().getTable(Identifier.create(database, table));
        ReadBuilder read = t.newReadBuilder();
        if (!partition.isEmpty()) {
            read = read.withPartitionFilter(partition);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try (RecordReader<InternalRow> reader =
                read.newRead().createReader(read.newScan().plan())) {
            reader.forEachRemaining(row -> out.add(Rows.toJava(row, t.rowType())));
        }
        return out;
    }

    /** Writes rows (column to Java value) in one commit. */
    public void write(String database, String table, List<Map<String, Object>> rows)
            throws Exception {
        Table t = catalog().getTable(Identifier.create(database, table));
        BatchWriteBuilder builder = t.newBatchWriteBuilder();
        try (BatchTableWrite write = builder.newWrite();
                BatchTableCommit commit = builder.newCommit()) {
            for (Map<String, Object> row : rows) {
                write.write(Rows.toInternal(row, t.rowType()));
            }
            commit.commit(write.prepareCommit());
        }
    }

    /** A table's partition keys, or empty if it does not exist. */
    public Optional<List<String>> partitionKeys(String database, String table) throws IOException {
        try {
            return Optional.of(
                    catalog().getTable(Identifier.create(database, table)).partitionKeys());
        } catch (Catalog.TableNotExistException e) {
            return Optional.empty();
        }
    }

    /** Whether the table still keeps a snapshot. */
    public boolean snapshotExists(String database, String table, long id) throws Exception {
        FileStoreTable t = (FileStoreTable) catalog().getTable(Identifier.create(database, table));
        return t.snapshotManager().snapshotExists(id);
    }

    /** A tag: its name, snapshot, when it was made and how long it keeps its snapshot. */
    public record TagInfo(String name, long snapshot, LocalDateTime created, Duration kept) {}

    /** The table's tags whose names start with {@code prefix}. */
    public List<TagInfo> tags(String database, String table, String prefix) throws Exception {
        FileStoreTable t = (FileStoreTable) catalog().getTable(Identifier.create(database, table));
        List<TagInfo> out = new ArrayList<>();
        for (String name : t.tagManager().allTagNames()) {
            if (!name.startsWith(prefix)) {
                continue;
            }
            t.tagManager()
                    .get(name)
                    .ifPresent(
                            tag ->
                                    out.add(
                                            new TagInfo(
                                                    name,
                                                    tag.id(),
                                                    tag.getTagCreateTime(),
                                                    tag.getTagTimeRetained())));
        }
        return out;
    }

    /** Tags a snapshot, keeping it {@code kept}; a tag of that name already there stays. */
    public void createTag(String database, String table, String name, long snapshot, Duration kept)
            throws Exception {
        FileStoreTable t = (FileStoreTable) catalog().getTable(Identifier.create(database, table));
        if (!t.tagManager().tagExists(name)) {
            t.createTag(name, snapshot, kept);
        }
    }

    /** Deletes a tag; one already gone is fine. */
    public void deleteTag(String database, String table, String name) throws Exception {
        FileStoreTable t = (FileStoreTable) catalog().getTable(Identifier.create(database, table));
        if (t.tagManager().tagExists(name)) {
            t.deleteTag(name);
        }
    }

    /**
     * The Iceberg metadata a table's writers wrote for a snapshot (metadata.iceberg.storage
     * table-location): its file, the table's Iceberg uuid, and the snapshot the file makes current.
     * Empty when the file is not there.
     */
    public record IcebergMetadata(String file, String uuid, long currentSnapshot) {}

    public Optional<IcebergMetadata> icebergMetadata(String database, String table, long snapshot)
            throws Exception {
        FileStoreTable t = (FileStoreTable) catalog().getTable(Identifier.create(database, table));
        org.apache.paimon.fs.Path file =
                new org.apache.paimon.fs.Path(
                        t.location(), "metadata/v" + snapshot + ".metadata.json");
        if (!t.fileIO().exists(file)) {
            return Optional.empty();
        }
        com.fasterxml.jackson.databind.JsonNode json =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(t.fileIO().readFileUtf8(file));
        return Optional.of(
                new IcebergMetadata(
                        file.toString(),
                        json.path("table-uuid").asText(),
                        json.path("current-snapshot-id").asLong(-1)));
    }

    /**
     * Whether a snapshot's Iceberg metadata holds exactly the files Iceberg is to show: a
     * primary-key table's files at the highest level (its fully compacted data), every file of an
     * append table. Paimon builds each metadata file from the previous one; when that one is
     * missing (a commit ran no callback, as a manifest compaction does) it starts over from the
     * snapshot's splits that need no merge, and a primary-key table's buckets with changes not yet
     * compacted drop out of the view.
     */
    public IcebergFiles icebergFiles(String database, String table, long snapshot)
            throws Exception {
        return icebergFiles(
                (FileStoreTable) catalog().getTable(Identifier.create(database, table)), snapshot);
    }

    /** The data files (by name) an Iceberg metadata file holds, and those it is to hold. */
    public record IcebergFiles(Set<String> held, Set<String> expected) {
        public boolean complete() {
            return held.equals(expected);
        }
    }

    static IcebergFiles icebergFiles(FileStoreTable t, long snapshot) throws IOException {
        org.apache.paimon.iceberg.IcebergPathFactory paths =
                new org.apache.paimon.iceberg.IcebergPathFactory(
                        org.apache.paimon.iceberg.IcebergCommitCallback.catalogTableMetadataPath(
                                t));
        org.apache.paimon.iceberg.metadata.IcebergMetadata metadata =
                org.apache.paimon.iceberg.metadata.IcebergMetadata.fromPath(
                        t.fileIO(), paths.toMetadataPath(snapshot));
        org.apache.paimon.iceberg.manifest.IcebergManifestFile manifests =
                org.apache.paimon.iceberg.manifest.IcebergManifestFile.create(t, paths);
        Set<String> held = new TreeSet<>();
        for (org.apache.paimon.iceberg.manifest.IcebergManifestFileMeta m :
                org.apache.paimon.iceberg.manifest.IcebergManifestList.create(t, paths)
                        .read(metadata.currentSnapshot().manifestList())) {
            if (m.content()
                    != org.apache.paimon.iceberg.manifest.IcebergManifestFileMeta.Content.DATA) {
                continue;
            }
            for (org.apache.paimon.iceberg.manifest.IcebergManifestEntry e : manifests.read(m)) {
                if (e.isLive()) {
                    held.add(new org.apache.paimon.fs.Path(e.file().filePath()).getName());
                }
            }
        }
        boolean primaryKey = !t.primaryKeys().isEmpty();
        int top = t.coreOptions().numLevels() - 1;
        Set<String> expected = new TreeSet<>();
        for (org.apache.paimon.table.source.DataSplit split :
                t.newSnapshotReader().withSnapshot(snapshot).read().dataSplits()) {
            for (org.apache.paimon.io.DataFileMeta f : split.dataFiles()) {
                if (!primaryKey || f.level() == top) {
                    expected.add(f.fileName());
                }
            }
        }
        return new IcebergFiles(held, expected);
    }

    @Override
    public synchronized void close() throws Exception {
        catalog.close();
    }
}
