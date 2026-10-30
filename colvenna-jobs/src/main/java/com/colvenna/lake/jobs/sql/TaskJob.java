package com.colvenna.lake.jobs.sql;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.apache.paimon.CoreOptions;
import org.apache.paimon.Snapshot;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.InnerTableCommit;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypeFamily;
import org.apache.paimon.types.DataTypeRoot;

/**
 * One LakeTask's run (HLD 9.2): Flink in batch mode in this pod, against colvenna-catalog under the
 * task's identity, with the verified batch paths (docs/history/m1/batch-engine-boundary.md).
 * Maintenance is a Paimon procedure, waited for ({@code table.dml-sync}). A backfill recomputes a
 * layered table from its upstream: its query in batch upserts every row, then the target's keys the
 * query no longer yields are deleted; the target's deduplicated lookup changelog publishes only
 * what changed. Prints, and leaves as the pod's termination message, the table's snapshots before
 * and after and what changed.
 */
public final class TaskJob {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    static final int DELETE_BATCH = 200;
    private static final DateTimeFormatter KEY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");

    /** LakeTaskSpec's tag patterns, checked again: the name goes into SQL. */
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /** A tag to delete may be one Paimon made itself ("2026-10-15 10"). */
    private static final Pattern ANY_TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._:-]{0,99}");

    /** LakeTaskSpec's partition pattern, checked again: the value goes into SQL. */
    private static final Pattern PARTITION =
            Pattern.compile(
                    "[a-z][a-z0-9_]{0,62}=[A-Za-z0-9_.:-]{1,64}(,[a-z][a-z0-9_]{0,62}=[A-Za-z0-9_.:-]{1,64}){0,3}");

    private TaskJob() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: TaskJob <spec-json>");
        }
        TableEnvironment tEnv = TableEnvironment.create(EnvironmentSettings.inBatchMode());
        Catalog catalog = ColvennaCatalog.register(tEnv);
        String json = JSON.writeValueAsString(run(tEnv, catalog, JSON.readTree(args[0])));
        System.out.println("TASK_RESULT " + json);
        String file = System.getenv().getOrDefault("COLVENNA_RESULT_FILE", "/dev/termination-log");
        if (Files.isWritable(Path.of(file))) {
            Files.writeString(Path.of(file), json, StandardCharsets.UTF_8);
        }
    }

    /** Runs the task against the catalog current in the environment. */
    static Map<String, Object> run(TableEnvironment tEnv, Catalog catalog, JsonNode spec)
            throws Exception {
        String type = spec.path("type").asText();
        if (type.equals("check")) {
            return QualityCheck.run(catalog, tEnv, spec);
        }
        String database = name(spec, "database");
        String table = name(spec, "table");
        Identifier id = Identifier.create(database, table);
        String quoted = "'" + database + "." + table + "'";
        Long before = latest(catalog, id);
        String detail;
        switch (type) {
            case "compact":
                // The procedure submits its own job; it waits for it only in sync mode.
                tEnv.getConfig().set("table.dml-sync", "true");
                detail =
                        rows(
                                tEnv.executeSql(
                                        "CALL sys.compact(`table` => "
                                                + quoted
                                                + (spec.path("full").asBoolean(false)
                                                        ? ", compact_strategy => 'full'"
                                                        : "")
                                                + partitions(spec)
                                                + ")"));
                tEnv.getConfig().set("table.dml-sync", "false");
                FileStoreTable compacted = (FileStoreTable) catalog.getTable(id);
                boolean full = spec.path("full").asBoolean(false);
                boolean iceberg = keepsIcebergMetadata(compacted);
                if (full && spec.path("manifests").asBoolean(!iceberg)) {
                    // The manifests still list the rewritten files, with the min and max of their
                    // columns: rewritten too, they list only what the table holds.
                    try (BatchTableCommit commit = compacted.newBatchWriteBuilder().newCommit()) {
                        commit.compactManifests();
                    }
                    detail += "; manifests compacted";
                    if (iceberg) {
                        // That commit ran none of the table's callbacks. An empty one has them
                        // write the Iceberg metadata anew now, and Paimon deletes all the older
                        // Iceberg manifests, the statistics of the rewritten files with them.
                        try (BatchTableCommit commit =
                                compacted.newBatchWriteBuilder().newCommit()) {
                            ((InnerTableCommit) commit).ignoreEmptyCommit(false).commit(List.of());
                        }
                        detail +=
                                "; Iceberg metadata written anew at snapshot "
                                        + latest(catalog, id);
                    }
                } else if (full) {
                    // A manifest compaction commits without the table's callbacks: no Iceberg
                    // metadata for its snapshot, and the next is rebuilt without the buckets
                    // whose changes are not compacted yet (LLD "开放层").
                    detail +=
                            iceberg
                                    ? "; manifests not compacted: the table keeps Iceberg metadata"
                                    : "; manifests not compacted, as the task asks";
                }
                break;
            case "expireSnapshots":
                detail =
                        "expired "
                                + rows(
                                        tEnv.executeSql(
                                                "CALL sys.expire_snapshots(`table` => "
                                                        + quoted
                                                        + ", retain_min => "
                                                        + spec.path("retainMin").asInt(10)
                                                        + ", older_than => '"
                                                        + ago(spec.path("olderThanMillis").asLong())
                                                        + "')"));
                // A write-only table's writer expires none of these: tags past their retention,
                // readers idle past theirs.
                detail +=
                        "; expired tags "
                                + rows(
                                        tEnv.executeSql(
                                                "CALL sys.expire_tags(`table` => " + quoted + ")"));
                FileStoreTable expiring = (FileStoreTable) catalog.getTable(id);
                Duration idle = CoreOptions.fromMap(expiring.options()).consumerExpireTime();
                if (idle != null) {
                    expiring.consumerManager().expire(LocalDateTime.now().minus(idle));
                }
                break;
            case "removeOrphanFiles":
                detail =
                        "removed "
                                + rows(
                                        tEnv.executeSql(
                                                "CALL sys.remove_orphan_files(`table` => "
                                                        + quoted
                                                        + ", older_than => '"
                                                        + ago(spec.path("olderThanMillis").asLong())
                                                        + "')"));
                break;
            case "createTag":
                detail = tag(catalog, tEnv, id, quoted, spec);
                break;
            case "deleteTag":
                detail = untag(catalog, tEnv, id, quoted, spec);
                break;
            case "backfill":
                detail = backfill(catalog, tEnv, id, spec);
                break;
            default:
                throw new IllegalArgumentException("Unknown task type " + type);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshotBefore", before);
        result.put("snapshotAfter", latest(catalog, id));
        // The termination message holds 4 KiB.
        result.put("detail", detail.length() > 1000 ? detail.substring(0, 1000) + "..." : detail);
        return result;
    }

    /** The procedure's partitions argument, or nothing: {@code k=v[,k=v]} each, as declared. */
    static String partitions(JsonNode spec) {
        List<String> out = new ArrayList<>();
        for (JsonNode p : spec.path("partitions")) {
            if (!PARTITION.matcher(p.asText()).matches()) {
                throw new IllegalArgumentException("Not a partition: " + p.asText());
            }
            out.add(p.asText());
        }
        return out.isEmpty() ? "" : ", partitions => '" + String.join(";", out) + "'";
    }

    /** A tag of the latest snapshot; one that exists already, on any snapshot, is kept. */
    private static String tag(
            Catalog catalog, TableEnvironment tEnv, Identifier id, String quoted, JsonNode spec)
            throws Exception {
        String name = spec.path("tag").asText();
        if (!TAG.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a tag name: " + name);
        }
        FileStoreTable table = (FileStoreTable) catalog.getTable(id);
        if (table.tagManager().tagExists(name)) {
            // A retry after the tag was made: the task asked for one, and it is there.
            return "tag " + name + " exists";
        }
        tEnv.executeSql(
                        "CALL sys.create_tag(`table` => "
                                + quoted
                                + ", tag => '"
                                + name
                                + "', time_retained => '"
                                + spec.path("timeRetained").asText("7 d")
                                + "')")
                .await();
        return "tagged " + name;
    }

    /** A tag deleted; one already gone is too (a retry after the delete). */
    private static String untag(
            Catalog catalog, TableEnvironment tEnv, Identifier id, String quoted, JsonNode spec)
            throws Exception {
        String name = spec.path("tag").asText();
        if (!ANY_TAG.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a tag name: " + name);
        }
        FileStoreTable table = (FileStoreTable) catalog.getTable(id);
        if (!table.tagManager().tagExists(name)) {
            return "tag " + name + " is gone";
        }
        tEnv.executeSql("CALL sys.delete_tag(`table` => " + quoted + ", tag => '" + name + "')")
                .await();
        return "deleted tag " + name;
    }

    /**
     * The task's query into the target, then the target's keys it no longer yields deleted. With
     * checks declared, the rows that pass go to the target and those that fail to its quarantine,
     * each rewritten so.
     */
    static String backfill(Catalog catalog, TableEnvironment tEnv, Identifier id, JsonNode spec)
            throws Exception {
        String query = spec.path("query").asText();
        List<String> keys = new ArrayList<>();
        spec.path("primaryKey").forEach(k -> keys.add(name(k.asText())));
        if (query.isBlank() || keys.isEmpty()) {
            throw new IllegalArgumentException("A backfill needs its table's query and key");
        }
        Quality quality = Quality.parse(spec.path("quality"));
        FileStoreTable table = (FileStoreTable) catalog.getTable(id);
        String detail =
                rewrite(
                        catalog,
                        tEnv,
                        id,
                        quality == null ? query : quality.passing(query, table.rowType()),
                        keys);
        if (quality != null) {
            Identifier quarantine = Identifier.create(id.getDatabaseName(), quality.quarantine);
            detail +=
                    "; quarantine "
                            + rewrite(
                                    catalog,
                                    tEnv,
                                    quarantine,
                                    quality.failing(query, table.rowType()),
                                    keys);
        }
        return detail;
    }

    /** {@code select}'s rows into the table, then the table's keys it no longer yields deleted. */
    private static String rewrite(
            Catalog catalog, TableEnvironment tEnv, Identifier id, String select, List<String> keys)
            throws Exception {
        String target = "`" + id.getDatabaseName() + "`.`" + id.getObjectName() + "`";
        tEnv.executeSql("INSERT INTO " + target + " " + select).await();
        // Progress, for whoever follows the pod's log.
        System.out.println(
                "TASK_PROGRESS upserted "
                        + id.getObjectName()
                        + " snapshot="
                        + latest(catalog, id));
        String keyList = String.join(", ", quoteAll(keys));
        Set<List<Object>> wanted = keySet(tEnv, "SELECT " + keyList + " FROM (" + select + ")");
        Set<List<Object>> held = keySet(tEnv, "SELECT " + keyList + " FROM " + target);
        held.removeAll(wanted);
        List<List<Object>> stale = new ArrayList<>(held);
        FileStoreTable table = (FileStoreTable) catalog.getTable(id);
        System.out.println("TASK_PROGRESS deleting " + stale.size() + " rows no longer derived");
        for (int i = 0; i < stale.size(); i += DELETE_BATCH) {
            List<String> matches = new ArrayList<>();
            for (List<Object> key : stale.subList(i, Math.min(stale.size(), i + DELETE_BATCH))) {
                List<String> parts = new ArrayList<>();
                for (int k = 0; k < keys.size(); k++) {
                    parts.add("`" + keys.get(k) + "` = " + literal(table, keys.get(k), key.get(k)));
                }
                matches.add("(" + String.join(" AND ", parts) + ")");
            }
            tEnv.executeSql("DELETE FROM " + target + " WHERE " + String.join(" OR ", matches))
                    .await();
        }
        return "upserted "
                + wanted.size()
                + " rows, deleted "
                + stale.size()
                + " no longer derived";
    }

    private static Set<List<Object>> keySet(TableEnvironment tEnv, String sql) throws Exception {
        Set<List<Object>> out = new HashSet<>();
        try (CloseableIterator<Row> rows = tEnv.executeSql(sql).collect()) {
            while (rows.hasNext()) {
                Row row = rows.next();
                List<Object> key = new ArrayList<>(row.getArity());
                for (int i = 0; i < row.getArity(); i++) {
                    key.add(row.getField(i));
                }
                out.add(key);
            }
        }
        return out;
    }

    /** A key value as a SQL literal of its column's type. */
    static String literal(FileStoreTable table, String column, Object value) {
        if (value == null) {
            throw new IllegalStateException("A key column is null: " + column);
        }
        DataType type = table.rowType().getField(column).type();
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        if (type.is(DataTypeFamily.NUMERIC)) {
            return value.toString();
        }
        if (type.is(DataTypeFamily.CHARACTER_STRING)) {
            return "'" + value.toString().replace("'", "''") + "'";
        }
        if (type.getTypeRoot() == DataTypeRoot.BOOLEAN) {
            return value.toString().toUpperCase(Locale.ROOT);
        }
        if (type.getTypeRoot() == DataTypeRoot.DATE) {
            return "DATE '" + value + "'";
        }
        if (type.getTypeRoot() == DataTypeRoot.TIMESTAMP_WITHOUT_TIME_ZONE) {
            // LocalDateTime.toString() leaves out zero seconds, which the parser requires.
            return "TIMESTAMP '" + KEY_TIME.format((LocalDateTime) value) + "'";
        }
        throw new IllegalArgumentException(
                "A backfill cannot delete by a " + type + " key (" + column + ")");
    }

    private static List<String> quoteAll(List<String> names) {
        List<String> out = new ArrayList<>();
        for (String n : names) {
            out.add("`" + n + "`");
        }
        return out;
    }

    private static String rows(TableResult result) throws Exception {
        List<String> out = new ArrayList<>();
        try (CloseableIterator<Row> rows = result.collect()) {
            while (rows.hasNext()) {
                Row row = rows.next();
                List<String> fields = new ArrayList<>();
                for (int i = 0; i < row.getArity(); i++) {
                    fields.add(String.valueOf(row.getField(i)));
                }
                out.add(String.join(" ", fields));
            }
        }
        return String.join("; ", out);
    }

    private static Long latest(Catalog catalog, Identifier id) throws Exception {
        Snapshot s = ((FileStoreTable) catalog.getTable(id)).snapshotManager().latestSnapshot();
        return s == null ? null : s.id();
    }

    /** {@code millis} before now, as the procedures take a time. */
    static String ago(long millis) {
        if (millis <= 0) {
            throw new IllegalArgumentException("olderThanMillis must be positive");
        }
        return TIME.format(Instant.now().minus(Duration.ofMillis(millis)));
    }

    private static String name(JsonNode spec, String field) {
        return name(spec.path(field).asText(""));
    }

    private static String name(String value) {
        if (!SqlSpec.NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a plain name: " + value);
        }
        return value;
    }

    /**
     * Whether the table's writers keep Iceberg metadata (metadata.iceberg.storage), wherever: each
     * metadata file is built on the previous one. The operator lets a declaration choose only
     * table-location, but a table may have been made otherwise.
     */
    static boolean keepsIcebergMetadata(FileStoreTable table) {
        String storage = table.options().get("metadata.iceberg.storage");
        return storage != null && !storage.equalsIgnoreCase("disabled");
    }
}
