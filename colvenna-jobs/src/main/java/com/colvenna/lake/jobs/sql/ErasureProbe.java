package com.colvenna.lake.jobs.sql;

import com.colvenna.lake.jobs.Catalogs;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.paimon.CoreOptions;
import org.apache.paimon.Snapshot;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.InternalArray;
import org.apache.paimon.data.InternalMap;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.data.Timestamp;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.FileStatus;
import org.apache.paimon.fs.Path;
import org.apache.paimon.iceberg.IcebergCommitCallback;
import org.apache.paimon.iceberg.IcebergPathFactory;
import org.apache.paimon.iceberg.manifest.IcebergConversions;
import org.apache.paimon.iceberg.manifest.IcebergDataFileMeta;
import org.apache.paimon.iceberg.manifest.IcebergManifestEntry;
import org.apache.paimon.iceberg.manifest.IcebergManifestFile;
import org.apache.paimon.io.DataFileMeta;
import org.apache.paimon.manifest.ManifestEntry;
import org.apache.paimon.manifest.ManifestFile;
import org.apache.paimon.manifest.ManifestFileMeta;
import org.apache.paimon.manifest.ManifestList;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.source.DataSplit;
import org.apache.paimon.table.source.ScanMode;
import org.apache.paimon.table.source.TableRead;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.InternalRowUtils;
import org.apache.paimon.utils.SnapshotManager;

/**
 * What an erasure needs to know (LLD "治理与个人数据删除"). Of each table: its latest snapshot and the
 * subject's rows in it (logical); the subject's records in that snapshot's data files, deletes
 * included and deletion vectors ignored (physical); what keeps older snapshots or changes readable
 * (the earliest snapshot, tags, branches, consumers, long-lived changelog) and, once the table is
 * clean, the subject's records in the changes of the snapshots still kept. Of each job's Flink
 * state: the objects of its checkpoints and savepoints written before the tables were clean, those
 * no restore needs disposed of. The subject's key values come from a file the erasure's Secret
 * mounts, one per line (tab-separated for a key of several columns), never from the argument, the
 * log or the result.
 */
public final class ErasureProbe {
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Each table lists this many tags, branches or consumers at most, and counts the rest: the
     * result travels as a termination message, of which Kubernetes keeps 4 KiB.
     */
    static final int LISTED = 5;

    private ErasureProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: ErasureProbe <spec-json>");
        }
        Set<List<String>> subject =
                subject(
                        java.nio.file.Path.of(
                                System.getenv()
                                        .getOrDefault("COLVENNA_SUBJECT_FILE", "/subject/values")));
        JsonNode spec = JSON.readTree(args[0]);
        Map<String, Object> out = new LinkedHashMap<>();
        try (Catalog catalog =
                CatalogFactory.createCatalog(
                        CatalogContext.create(Options.fromMap(Catalogs.restOptions())))) {
            List<Map<String, Object>> tables = new ArrayList<>();
            for (JsonNode t : spec.path("tables")) {
                String name = t.path("table").asText();
                List<String> key = new ArrayList<>();
                t.path("subjectKey").forEach(k -> key.add(k.asText()));
                try {
                    tables.add(
                            probe(
                                    (FileStoreTable) catalog.getTable(Identifier.fromString(name)),
                                    name,
                                    key,
                                    subject,
                                    id(t.path("clean")),
                                    id(t.path("compacted"))));
                } catch (Catalog.TableNotExistException e) {
                    tables.add(Map.of("table", name, "missing", true));
                } catch (Exception e) {
                    // One table the probe cannot read holds back no other.
                    tables.add(
                            Map.of("table", name, "error", capped(redact(e.toString(), subject))));
                }
            }
            out.put("tables", tables);
        }
        List<Map<String, Object>> states = new ArrayList<>();
        for (JsonNode s : spec.path("state")) {
            String name = s.path("name").asText();
            Path location = new Path(s.path("location").asText());
            Set<String> keep = new HashSet<>();
            s.path("keep").forEach(k -> keep.add(k.asText()));
            try {
                states.add(
                        state(
                                stateIo(location),
                                name,
                                location,
                                s.path("before").asLong(),
                                s.path("job").asText(null),
                                s.path("running").asBoolean(),
                                keep));
            } catch (Exception e) {
                // One job's state the probe cannot list holds back no other copy.
                states.add(Map.of("name", name, "error", capped(redact(e.toString(), subject))));
            }
        }
        out.put("state", states);
        JsonNode orphans = spec.path("orphans");
        if (orphans.has("root")) {
            Path root = new Path(orphans.path("root").asText());
            Set<String> known = new HashSet<>();
            orphans.path("known").forEach(o -> known.add(o.asText()));
            try {
                out.put(
                        "orphans",
                        orphans(stateIo(root), root, known, orphans.path("before").asLong()));
            } catch (Exception e) {
                out.put("orphans", Map.of("error", capped(redact(e.toString(), subject))));
            }
        }
        // The whole result in the log, what fits in the termination message.
        System.out.println("ERASURE_PROBE " + JSON.writeValueAsString(out));
        String json = fitted(out);
        java.nio.file.Path result =
                java.nio.file.Path.of(
                        System.getenv()
                                .getOrDefault("COLVENNA_RESULT_FILE", "/dev/termination-log"));
        if (Files.isWritable(result)) {
            Files.writeString(result, json, StandardCharsets.UTF_8);
        }
    }

    /** What a termination message can carry: Kubernetes keeps 4 KiB of it. */
    static final int RESULT_BYTES = 3800;

    /** An error's text, short enough for the result. */
    static String capped(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    /**
     * The result as JSON within {@link #RESULT_BYTES}: when longer, the lists of tags, consumers
     * and branches go first (their counts stay, and an unlisted one holds as listed ones do), then
     * the lists of objects left and disposed of, the state's and the orphans' (their counts stay).
     * Should it still not fit, the result says so alone: every copy waits for the next probe.
     */
    @SuppressWarnings("unchecked")
    static String fitted(Map<String, Object> out) throws Exception {
        String json = JSON.writeValueAsString(out);
        if (fits(json)) {
            return json;
        }
        for (Object t : (List<Object>) out.getOrDefault("tables", List.of())) {
            if (t instanceof LinkedHashMap) {
                Map<String, Object> table = (Map<String, Object>) t;
                for (String list : List.of("tags", "consumers", "branches")) {
                    Object listed = table.get(list);
                    if (listed instanceof Map) {
                        table.put(
                                list + "More",
                                ((Number) table.get(list + "More")).intValue()
                                        + ((Map<String, Object>) listed).size());
                        table.put(list, Map.of());
                    }
                }
            }
        }
        json = JSON.writeValueAsString(out);
        if (fits(json)) {
            return json;
        }
        List<Object> listings =
                new ArrayList<>((List<Object>) out.getOrDefault("state", List.of()));
        listings.add(out.get("orphans"));
        for (Object s : listings) {
            if (s instanceof LinkedHashMap) {
                ((Map<String, Object>) s).put("left", List.of("not listed: too many"));
                ((Map<String, Object>) s).put("disposed", List.of());
            }
        }
        json = JSON.writeValueAsString(out);
        if (fits(json)) {
            return json;
        }
        // Then the errors' text (the log has it whole).
        for (String key : List.of("tables", "state")) {
            List<Object> list = new ArrayList<>((List<Object>) out.getOrDefault(key, List.of()));
            for (int i = 0; i < list.size(); i++) {
                Map<String, Object> m = (Map<String, Object>) list.get(i);
                if (m.get("error") instanceof String) {
                    Map<String, Object> shorter = new LinkedHashMap<>(m);
                    shorter.put("error", shortened((String) m.get("error")));
                    list.set(i, shorter);
                }
            }
            out.put(key, list);
        }
        Object orphans = out.get("orphans");
        if (orphans instanceof Map
                && ((Map<String, Object>) orphans).get("error") instanceof String) {
            out.put(
                    "orphans",
                    Map.of(
                            "error",
                            shortened((String) ((Map<String, Object>) orphans).get("error"))));
        }
        json = JSON.writeValueAsString(out);
        return fits(json) ? json : JSON.writeValueAsString(Map.of("error", "result too long"));
    }

    private static String shortened(String error) {
        return error.length() <= 60 ? error : error.substring(0, 60) + "...";
    }

    private static boolean fits(String json) {
        return json.getBytes(StandardCharsets.UTF_8).length <= RESULT_BYTES;
    }

    /** {@code text} with each of the subject's values masked. */
    static String redact(String text, Set<List<String>> subject) {
        String out = text;
        for (List<String> values : subject) {
            for (String v : values) {
                if (!v.isBlank()) {
                    out = out.replace(v, "<subject>");
                }
            }
        }
        return out;
    }

    private static Long id(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? null : n.asLong();
    }

    static Set<List<String>> subject(java.nio.file.Path file) throws Exception {
        Set<List<String>> out = new HashSet<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                out.add(List.of(line.strip().split("\t", -1)));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("The subject file has no values");
        }
        return out;
    }

    /**
     * One table's account.
     *
     * @param clean the first snapshot without the subject's rows, once the erasure knows it: the
     *     changes of the snapshots kept from it on are searched for the subject
     * @param compacted the first snapshot whose files hold no record of the subject, once known
     */
    static Map<String, Object> probe(
            FileStoreTable table,
            String name,
            List<String> key,
            Set<List<String>> subject,
            Long clean,
            Long compacted)
            throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", name);
        SnapshotManager snapshots = table.snapshotManager();
        Snapshot latest = snapshots.latestSnapshot();
        out.put("snapshot", latest == null ? null : latest.id());
        out.put("snapshotMillis", latest == null ? null : latest.timeMillis());
        Long earliest = snapshots.earliestSnapshotId();
        out.put("earliest", earliest);
        out.put("changelogEarliest", table.changelogManager().earliestLongLivedChangelogId());
        // What keeps older snapshots: listed up to the latest the erasure still needs gone.
        long threshold =
                compacted != null
                        ? compacted
                        : clean != null ? clean : latest == null ? Long.MAX_VALUE : latest.id();
        Map<String, Long> tags = new TreeMap<>();
        table.tagManager()
                .tags()
                .forEach(
                        (s, names) -> {
                            if (s.id() < threshold) {
                                names.forEach(n -> tags.put(n, s.id()));
                            }
                        });
        listed(out, "tags", tags);
        Map<String, Long> consumers = new TreeMap<>();
        table.consumerManager()
                .consumers()
                .forEach(
                        (id, next) -> {
                            if (next <= threshold) {
                                consumers.put(id, next);
                            }
                        });
        listed(out, "consumers", consumers);
        Map<String, Long> branches = new TreeMap<>();
        table.branchManager().branches().forEach(b -> branches.put(b, 0L));
        listed(out, "branches", branches);

        RowType rowType = table.rowType();
        Key k = Key.of(rowType, key, subject, name);
        out.put("fits", k.fits());
        if (latest == null || !k.fits()) {
            out.put("rows", 0);
            out.put("records", 0);
            return out;
        }
        // The rows a reader sees: merged.
        long rows = 0;
        List<DataSplit> splits = table.newSnapshotReader().withSnapshot(latest).read().dataSplits();
        for (DataSplit split : splits) {
            rows += k.count(table.newRead(), split);
        }
        // What the files hold: each file alone, its deletes kept and no deletion vector applied
        // (one masks a record, it does not remove it).
        long records = 0;
        for (DataSplit split : splits) {
            for (DataFileMeta file : split.dataFiles()) {
                records += k.count(table.newRead().forceKeepDelete(), alone(split, file));
            }
        }
        out.put("rows", rows);
        out.put("records", records);
        out.put("mentions", mentions(table, latest, k));
        out.put("icebergMentions", icebergMentions(table, k));
        if (clean != null) {
            long[] changes = changes(table, k, Math.max(earliest, clean), clean, latest.id());
            out.put("changes", changes[0]);
            // Its rows back after the clean snapshot (whose changes are the erasure's own).
            out.put("added", changes[1]);
        }
        return out;
    }

    /** At most {@link #LISTED} entries of a list, and how many more there are. */
    private static void listed(Map<String, Object> out, String name, Map<String, Long> all) {
        Map<String, Long> some = new TreeMap<>();
        all.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(LISTED)
                .forEach(e -> some.put(e.getKey(), e.getValue()));
        out.put(name, some);
        out.put(name + "More", all.size() - some.size());
    }

    private static DataSplit alone(DataSplit split, DataFileMeta file) {
        return DataSplit.builder()
                .withSnapshot(split.snapshotId())
                .withPartition(split.partition())
                .withBucket(split.bucket())
                .withBucketPath(split.bucketPath())
                .withTotalBuckets(split.totalBuckets())
                .withDataFiles(List.of(file))
                .isStreaming(false)
                .rawConvertible(false)
                .build();
    }

    /**
     * The subject's records in what a streaming reader reads of the snapshots from {@code from} to
     * {@code to}: their changelog, or the data files a commit added when the table has none; and of
     * them those that add a row in a snapshot after {@code clean}.
     */
    private static long[] changes(FileStoreTable table, Key k, long from, long clean, long to)
            throws Exception {
        boolean changelog =
                CoreOptions.fromMap(table.options()).changelogProducer()
                        != CoreOptions.ChangelogProducer.NONE;
        long[] n = new long[2];
        for (long id = from; id <= to; id++) {
            if (!table.snapshotManager().snapshotExists(id)) {
                continue;
            }
            Snapshot s = table.snapshotManager().snapshot(id);
            if (!changelog && s.commitKind() != Snapshot.CommitKind.APPEND) {
                continue;
            }
            for (DataSplit split :
                    table.newSnapshotReader()
                            .withSnapshot(id)
                            .withMode(changelog ? ScanMode.CHANGELOG : ScanMode.DELTA)
                            .read()
                            .dataSplits()) {
                long[] kinds = k.kinds(table.newRead().forceKeepDelete(), split);
                n[0] += kinds[0];
                if (id > clean) {
                    n[1] += kinds[1];
                }
            }
        }
        return n;
    }

    /**
     * The files the snapshot's manifests list (added or removed) whose column statistics, the
     * minimum or maximum of a subject key column, are one of the subject's values: metadata a
     * manifest compaction rewrites.
     */
    private static long mentions(FileStoreTable table, Snapshot snapshot, Key k) {
        ManifestList list = table.store().manifestListFactory().create();
        ManifestFile manifests = table.store().manifestFileFactory().create();
        List<String> keys = table.schema().trimmedPrimaryKeys();
        long n = 0;
        for (ManifestFileMeta meta : list.readAllManifests(snapshot)) {
            for (ManifestEntry e : manifests.read(meta.fileName(), meta.fileSize())) {
                if (k.mentionedBy(e.file(), keys, table.options())) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * The entries of the Iceberg manifests a table's writers keep beside it (metadata.iceberg.*),
     * every one in storage whether a metadata file still reaches it or not, whose bounds (the
     * minimum or maximum of a subject key column) are one of the subject's values. A full
     * compaction that rewrites the manifests has Paimon write the Iceberg metadata anew and delete
     * these. 0 for a table keeping none.
     */
    private static long icebergMentions(FileStoreTable table, Key k) throws IOException {
        if (!TaskJob.keepsIcebergMetadata(table)) {
            return 0;
        }
        Path dir = IcebergCommitCallback.catalogTableMetadataPath(table);
        IcebergManifestFile manifests =
                IcebergManifestFile.create(table, new IcebergPathFactory(dir));
        int[][] truncations = {
            k.truncated(table.options(), 0),
            k.truncated(table.options(), table.coreOptions().numLevels() - 1)
        };
        if (!table.fileIO().exists(dir)) {
            return 0;
        }
        long n = 0;
        for (FileStatus f : table.fileIO().listStatus(dir)) {
            String name = f.getPath().getName();
            // Manifest lists (snap-*.avro) hold partitions' bounds only.
            if (!name.endsWith(".avro") || name.startsWith("snap-")) {
                continue;
            }
            for (IcebergManifestEntry e : manifests.read(name, f.getLen())) {
                if (k.mentionedBy(e.file(), truncations)) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * The subject key's columns and the subject's values as they compare: numbers by value, dates
     * and times by their instant, text without case or surrounding blanks (as the source's
     * collation commonly does). The values do not fit when one has another number of columns,
     * cannot be read as the column's type, or the column's type is none of these: counted against
     * it, they would find nothing.
     */
    static final class Key {
        private final int[] positions;
        private final List<String> names;
        private final RowType rowType;
        private final List<Set<String>> byColumn;
        private final Set<List<String>> values;
        private final boolean fits;

        private Key(
                int[] positions,
                List<String> names,
                RowType rowType,
                List<Set<String>> byColumn,
                Set<List<String>> values,
                boolean fits) {
            this.positions = positions;
            this.names = names;
            this.rowType = rowType;
            this.byColumn = byColumn;
            this.values = values;
            this.fits = fits;
        }

        boolean fits() {
            return fits;
        }

        static Key of(RowType rowType, List<String> key, Set<List<String>> subject, String table) {
            int[] positions = new int[key.size()];
            for (int i = 0; i < positions.length; i++) {
                positions[i] = rowType.getFieldNames().indexOf(key.get(i));
                if (positions[i] < 0) {
                    throw new IllegalArgumentException(table + " has no column " + key.get(i));
                }
            }
            Set<List<String>> values = new HashSet<>();
            List<Set<String>> byColumn = new ArrayList<>();
            for (int i = 0; i < positions.length; i++) {
                byColumn.add(new HashSet<>());
            }
            boolean fits = true;
            for (List<String> v : subject) {
                if (v.size() != positions.length) {
                    fits = false;
                    continue;
                }
                List<String> formed = new ArrayList<>();
                try {
                    for (int i = 0; i < v.size(); i++) {
                        formed.add(text(v.get(i), rowType.getTypeAt(positions[i])));
                    }
                } catch (RuntimeException e) {
                    fits = false;
                    continue;
                }
                values.add(formed);
                for (int i = 0; i < formed.size(); i++) {
                    byColumn.get(i).add(formed.get(i));
                }
            }
            return new Key(positions, key, rowType, byColumn, values, fits);
        }

        /** A value as given, in the form it compares in; throws when it cannot be one. */
        static String text(String v, DataType type) {
            String t = v.strip();
            switch (type.getTypeRoot()) {
                case TINYINT:
                case SMALLINT:
                case INTEGER:
                case BIGINT:
                case DECIMAL:
                case FLOAT:
                case DOUBLE:
                    return new BigDecimal(t).stripTrailingZeros().toPlainString();
                case CHAR:
                case VARCHAR:
                    return t.toLowerCase(Locale.ROOT);
                case DATE:
                    return LocalDate.parse(t).toString();
                case TIMESTAMP_WITHOUT_TIME_ZONE:
                case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                    return LocalDateTime.parse(t.replace(' ', 'T')).toString();
                default:
                    throw new IllegalArgumentException("no subject key of type " + type);
            }
        }

        /** A value as the table holds it, in the form it compares in. */
        static String internal(Object v, DataType type) {
            if (v == null) {
                return null;
            }
            switch (type.getTypeRoot()) {
                case DATE:
                    return LocalDate.ofEpochDay(((Number) v).longValue()).toString();
                case TIMESTAMP_WITHOUT_TIME_ZONE:
                case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                    return ((Timestamp) v).toLocalDateTime().toString();
                default:
                    return text(v.toString(), type);
            }
        }

        long count(TableRead read, DataSplit split) throws Exception {
            return kinds(read, split)[0];
        }

        /** The subject's records in a split, and of them those that add a row (+I, +U). */
        long[] kinds(TableRead read, DataSplit split) throws Exception {
            long n = 0;
            long added = 0;
            try (RecordReader<InternalRow> reader = read.createReader(split)) {
                RecordReader.RecordIterator<InternalRow> batch;
                while ((batch = reader.readBatch()) != null) {
                    InternalRow row;
                    while ((row = batch.next()) != null) {
                        List<String> key = new ArrayList<>(positions.length);
                        for (int p : positions) {
                            DataType type = rowType.getTypeAt(p);
                            key.add(internal(InternalRowUtils.get(row, p, type), type));
                        }
                        if (values.contains(key)) {
                            n++;
                            if (row.getRowKind().isAdd()) {
                                added++;
                            }
                        }
                    }
                    batch.releaseBatch();
                }
            }
            return new long[] {n, added};
        }

        /**
         * How many characters each subject key column's text statistics keep: Paimon's {@code
         * fields.<column>.stats-mode}, else {@code metadata.stats-mode}, truncate(n); 0 when whole.
         */
        int[] truncated(Map<String, String> options, int level) {
            String all = options.getOrDefault("metadata.stats-mode", "truncate(16)");
            // metadata.stats-mode.per.level: "0:none,1:truncate(8)".
            for (String entry :
                    options.getOrDefault("metadata.stats-mode.per.level", "").split(",")) {
                String[] kv = entry.split(":", 2);
                if (kv.length == 2 && kv[0].trim().equals(Integer.toString(level))) {
                    all = kv[1].trim();
                }
            }
            int[] out = new int[names.size()];
            for (int i = 0; i < out.length; i++) {
                Matcher mode =
                        TRUNCATE.matcher(
                                options.getOrDefault("fields." + names.get(i) + ".stats-mode", all)
                                        .trim());
                out[i] = mode.matches() ? Integer.parseInt(mode.group(1)) : 0;
            }
            return out;
        }

        private static final Pattern TRUNCATE =
                Pattern.compile("truncate\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);

        /**
         * Whether a file's statistics name one of the subject's values: the minimum or maximum of a
         * subject key column among its values, or among its keys when the column is one.
         */
        boolean mentionedBy(
                DataFileMeta file, List<String> primaryKeys, Map<String, String> options) {
            int[] truncated = truncated(options, file.level());
            for (int i = 0; i < positions.length; i++) {
                DataType type = rowType.getTypeAt(positions[i]);
                List<String> statsCols = file.valueStatsCols();
                int v = statsCols == null ? positions[i] : statsCols.indexOf(names.get(i));
                if (v >= 0
                        && (names(i, stat(file.valueStats().minValues(), v, type), truncated[i])
                                || names(
                                        i,
                                        stat(file.valueStats().maxValues(), v, type),
                                        truncated[i]))) {
                    return true;
                }
                // Keys are kept whole.
                int k = primaryKeys.indexOf(names.get(i));
                if (k >= 0
                        && (names(i, stat(file.minKey(), k, type), 0)
                                || names(i, stat(file.maxKey(), k, type), 0))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether an Iceberg manifest entry's bounds name one of the subject's values: Paimon
         * writes them, keyed by field id, from the file's statistics, cut as those are.
         */
        boolean mentionedBy(IcebergDataFileMeta file, int[][] truncations) {
            for (int i = 0; i < positions.length; i++) {
                DataType type = rowType.getTypeAt(positions[i]);
                int id = rowType.getFields().get(positions[i]).id();
                for (InternalMap bounds :
                        new InternalMap[] {file.lowerBounds(), file.upperBounds()}) {
                    String stat = bound(bounds, id, type);
                    for (int[] truncated : truncations) {
                        if (names(i, stat, truncated[i])) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private static String bound(InternalMap bounds, int id, DataType type) {
            if (bounds == null) {
                return null;
            }
            InternalArray ids = bounds.keyArray();
            InternalArray values = bounds.valueArray();
            for (int j = 0; j < ids.size(); j++) {
                if (ids.getInt(j) == id && !values.isNullAt(j)) {
                    try {
                        return internal(
                                IcebergConversions.toPaimonObject(type, values.getBinary(j)), type);
                    } catch (RuntimeException e) {
                        return null;
                    }
                }
            }
            return null;
        }

        /**
         * Whether a statistic names one of column {@code i}'s values. Text statistics are cut to
         * {@code truncated} characters (Paimon's {@code metadata.stats-mode} truncate(n)), the
         * maximum's last one raised: a cut one names every value sharing all but its last.
         */
        private boolean names(int i, String stat, int truncated) {
            if (stat == null) {
                return false;
            }
            if (byColumn.get(i).contains(stat)) {
                return true;
            }
            if (truncated > 1 && stat.length() == truncated) {
                String prefix = stat.substring(0, truncated - 1);
                return byColumn.get(i).stream()
                        .anyMatch(v -> v.length() >= truncated && v.startsWith(prefix));
            }
            return false;
        }

        private static String stat(InternalRow row, int index, DataType type) {
            if (row == null || index >= row.getFieldCount() || row.isNullAt(index)) {
                return null;
            }
            try {
                return internal(InternalRowUtils.get(row, index, type), type);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** The jobs' state is theirs, not a table's: read with the jobs' identity (IRSA). */
    private static FileIO stateIo(Path location) throws Exception {
        return FileIO.get(
                location,
                CatalogContext.create(
                        Options.fromMap(
                                Map.of(
                                        "s3.aws.credentials.provider",
                                        "com.amazonaws.auth.WebIdentityTokenCredentialsProvider"))));
    }

    /**
     * One job's Flink state under {@code location}: the objects of its checkpoints and savepoints
     * written before {@code before}. Disposed of, unless a restore needs it ({@code keep}: the
     * paths the deployment would restore from): a savepoint written before it; the checkpoint
     * directory of another run than {@code job} (left by a cancelled run), written wholly before
     * it, or whatever it holds when the job is {@code running}, as a running job restores from none
     * of them. The job's own older checkpoints are left to Flink, which drops them as new ones
     * complete; so are the completed-checkpoint records its high availability keeps beside them
     * (they may inline small state), unless the job is gone.
     */
    static Map<String, Object> state(
            FileIO io,
            String name,
            Path location,
            long before,
            String job,
            boolean running,
            Set<String> keep)
            throws Exception {
        long objects = 0;
        long older = 0;
        List<String> disposed = new ArrayList<>();
        List<String> left = new ArrayList<>();
        for (String kind : List.of("checkpoints", "savepoints")) {
            Path dir = new Path(location, kind);
            if (!io.exists(dir)) {
                continue;
            }
            FileStatus[] entries = io.listStatus(dir);
            Arrays.sort(entries, Comparator.comparing(e -> e.getPath().getName()));
            for (FileStatus entry : entries) {
                String relative = kind + "/" + entry.getPath().getName();
                FileStatus[] files =
                        entry.isDir()
                                ? io.listFiles(entry.getPath(), true)
                                : new FileStatus[] {entry};
                long old = 0;
                for (FileStatus f : files) {
                    if (f.getModificationTime() < before) {
                        old++;
                    }
                }
                boolean needed =
                        keep.stream().anyMatch(k -> k.startsWith(entry.getPath().toString()));
                boolean current =
                        kind.equals("checkpoints") && entry.getPath().getName().equals(job);
                boolean otherRun = kind.equals("checkpoints") && !current;
                if (old > 0
                        && !needed
                        && !current
                        && (old == files.length || otherRun && running)) {
                    io.delete(entry.getPath(), true);
                    disposed.add(relative);
                    continue;
                }
                objects += files.length;
                older += old;
                if (old > 0) {
                    left.add(relative + (needed ? " (a restore needs it)" : ""));
                }
            }
        }
        Path ha = new Path(location, "ha");
        if (io.exists(ha)) {
            for (FileStatus f : io.listFiles(ha, true)) {
                if (!f.getPath().getName().startsWith("completedCheckpoint")) {
                    continue;
                }
                objects++;
                if (f.getModificationTime() < before) {
                    if (job == null) {
                        io.delete(f.getPath(), false);
                        disposed.add("ha/" + f.getPath().getName());
                        objects--;
                    } else {
                        older++;
                        left.add("ha/" + f.getPath().getName());
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("before", before);
        out.put("objects", objects);
        out.put("older", older);
        out.put("left", left.subList(0, Math.min(LISTED, left.size())));
        out.put("disposed", disposed.subList(0, Math.min(LISTED, disposed.size())));
        out.put("disposedCount", disposed.size());
        return out;
    }

    /**
     * The state left under {@code root} (the namespace's) by jobs no declaration has any more (a
     * declaration deleted or made again, a job a source dropped): a directory not among the {@code
     * known} ones, those of the declared jobs. One written wholly before {@code before} is disposed
     * of; one with newer objects is left, and named.
     */
    static Map<String, Object> orphans(FileIO io, Path root, Set<String> known, long before)
            throws Exception {
        List<String> disposed = new ArrayList<>();
        List<String> left = new ArrayList<>();
        if (io.exists(root)) {
            FileStatus[] entries = io.listStatus(root);
            Arrays.sort(entries, Comparator.comparing(e -> e.getPath().getName()));
            for (FileStatus entry : entries) {
                String name = entry.getPath().getName();
                if (!entry.isDir() || known.contains(name)) {
                    continue;
                }
                FileStatus[] files = io.listFiles(entry.getPath(), true);
                boolean older = true;
                for (FileStatus f : files) {
                    older &= f.getModificationTime() < before;
                }
                if (older) {
                    io.delete(entry.getPath(), true);
                    disposed.add(name);
                } else {
                    left.add(name);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("before", before);
        out.put("left", left.subList(0, Math.min(LISTED, left.size())));
        out.put("leftCount", left.size());
        out.put("disposed", disposed.subList(0, Math.min(LISTED, disposed.size())));
        out.put("disposedCount", disposed.size());
        return out;
    }
}
