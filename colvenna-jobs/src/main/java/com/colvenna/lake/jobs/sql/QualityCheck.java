package com.colvenna.lake.jobs.sql;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.apache.paimon.Snapshot;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.Timestamp;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.BatchWriteBuilder;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypeFamily;
import org.apache.paimon.types.DataTypeRoot;
import org.apache.paimon.types.RowType;

/**
 * One batch quality check (LLD "批量质量校验"): a table's rules on one snapshot, read with {@code
 * scan.snapshot-id}, over the whole table or some partitions; freshness over the whole table at the
 * check's asOf. Every rule's result goes to the store in one commit, keyed by the task's uid and
 * the rule, so a retry writes the same rows over the first attempt's.
 */
final class QualityCheck {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** LakeTaskSpec's partition pattern, checked again: the value goes into SQL. */
    private static final Pattern PARTITION =
            Pattern.compile(
                    "[a-z][a-z0-9_]{0,62}=[A-Za-z0-9_.:-]{1,64}(,[a-z][a-z0-9_]{0,62}=[A-Za-z0-9_.:-]{1,64}){0,3}");

    private static final Pattern NUMBER = Pattern.compile("-?[0-9]{1,18}(\\.[0-9]{1,18})?");
    private static final Pattern RULE = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    /** A column of the checked table: a plain name, or one of the jobs' own (_offset). */
    private static final Pattern COLUMN = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private QualityCheck() {}

    /** One rule's outcome. */
    static final class Outcome {
        String rule;
        String definition;
        String metric;
        Double measured;
        String threshold;
        boolean passed;
        Long failingRows;
        Long checkedRows;
        String locate;
    }

    static Map<String, Object> run(Catalog catalog, TableEnvironment tEnv, JsonNode spec)
            throws Exception {
        String database = name(spec.path("database").asText(""));
        String tableName = name(spec.path("table").asText(""));
        long snapshotId = spec.path("snapshot").asLong(-1);
        long asOf = spec.path("asOfMillis").asLong(-1);
        if (snapshotId < 1 || asOf < 0) {
            throw new IllegalArgumentException("A check needs its snapshot and asOfMillis");
        }
        FileStoreTable table =
                (FileStoreTable) catalog.getTable(Identifier.create(database, tableName));
        if (!table.snapshotManager().snapshotExists(snapshotId)) {
            throw new IllegalStateException(
                    database + "." + tableName + " no longer keeps snapshot " + snapshotId);
        }
        Snapshot snapshot = table.snapshotManager().snapshot(snapshotId);
        List<String> partitions = new ArrayList<>();
        spec.path("partitions").forEach(p -> partitions.add(p.asText()));
        String base =
                "`"
                        + database
                        + "`.`"
                        + tableName
                        + "` /*+ OPTIONS('scan.snapshot-id'='"
                        + snapshotId
                        + "') */";
        String scope = scope(table, partitions);
        String where = scope.isEmpty() ? "" : " WHERE " + scope;

        List<JsonNode> rules = new ArrayList<>();
        spec.path("rules").forEach(rules::add);
        // One pass for the rows and every notNull column.
        List<String> counted = new ArrayList<>();
        for (JsonNode r : rules) {
            if ("notNull".equals(r.path("kind").asText())) {
                counted.add(column(r.path("column").asText("")));
            }
        }
        StringBuilder select = new StringBuilder("SELECT COUNT(*)");
        for (String c : counted) {
            select.append(", COUNT(`").append(c).append("`)");
        }
        Row counts = one(tEnv, select + " FROM " + base + where);
        long rows = ((Number) counts.getField(0)).longValue();

        List<Outcome> outcomes = new ArrayList<>();
        for (JsonNode r : rules) {
            Outcome o = new Outcome();
            o.rule = r.path("name").asText("");
            if (!RULE.matcher(o.rule).matches()) {
                throw new IllegalArgumentException("Not a rule name: " + o.rule);
            }
            o.definition = JSON.writeValueAsString(r);
            o.checkedRows = rows;
            switch (r.path("kind").asText()) {
                case "notNull":
                    {
                        String c = column(r.path("column").asText(""));
                        long nonNull =
                                ((Number) counts.getField(1 + counted.indexOf(c))).longValue();
                        BigDecimal min = new BigDecimal(r.path("minRatio").asText("1"));
                        o.metric = "not_null_ratio";
                        o.measured =
                                rows == 0
                                        ? null
                                        : BigDecimal.valueOf(nonNull)
                                                .divide(
                                                        BigDecimal.valueOf(rows),
                                                        9,
                                                        RoundingMode.DOWN)
                                                .doubleValue();
                        o.threshold = ">= " + min.toPlainString();
                        // Exactly: non-null rows at least min times the rows. A range without rows
                        // passes;
                        // whether it may be empty is rowCount's to say.
                        o.passed =
                                BigDecimal.valueOf(nonNull)
                                                .compareTo(min.multiply(BigDecimal.valueOf(rows)))
                                        >= 0;
                        o.failingRows = rows - nonNull;
                        o.locate =
                                "SELECT * FROM "
                                        + base
                                        + " WHERE "
                                        + and(scope, "`" + c + "` IS NULL");
                        break;
                    }
                case "unique":
                    {
                        List<String> cols = new ArrayList<>();
                        r.path("columns").forEach(c -> cols.add("`" + column(c.asText("")) + "`"));
                        List<String> present = new ArrayList<>();
                        for (String c : cols) {
                            present.add(c + " IS NOT NULL");
                        }
                        String grouped =
                                "SELECT "
                                        + String.join(", ", cols)
                                        + ", COUNT(*) AS n FROM "
                                        + base
                                        + " WHERE "
                                        + and(scope, String.join(" AND ", present))
                                        + " GROUP BY "
                                        + String.join(", ", cols)
                                        + " HAVING COUNT(*) > 1";
                        Row dup =
                                one(
                                        tEnv,
                                        "SELECT COUNT(*), COALESCE(SUM(n), 0) FROM ("
                                                + grouped
                                                + ")");
                        long keys = ((Number) dup.getField(0)).longValue();
                        o.metric = "duplicate_keys";
                        o.measured = (double) keys;
                        o.threshold = "= 0";
                        o.passed = keys == 0;
                        o.failingRows = ((Number) dup.getField(1)).longValue();
                        o.locate = grouped;
                        break;
                    }
                case "rowCount":
                    {
                        Long min = r.hasNonNull("min") ? r.path("min").asLong() : null;
                        Long max = r.hasNonNull("max") ? r.path("max").asLong() : null;
                        o.metric = "row_count";
                        o.measured = (double) rows;
                        o.threshold =
                                (min == null ? "" : ">= " + min)
                                        + (min != null && max != null ? " and " : "")
                                        + (max == null ? "" : "<= " + max);
                        o.passed = (min == null || rows >= min) && (max == null || rows <= max);
                        o.locate = "SELECT COUNT(*) FROM " + base + where;
                        break;
                    }
                case "freshness":
                    {
                        String c = column(r.path("column").asText(""));
                        long maxAge = r.path("maxAgeMillis").asLong();
                        Long newest =
                                newest(
                                        table.rowType(),
                                        c,
                                        one(tEnv, "SELECT MAX(`" + c + "`) FROM " + base));
                        o.metric = "age_seconds";
                        o.measured = newest == null ? null : (asOf - newest) / 1000.0;
                        o.threshold = "<= " + maxAge / 1000 + " s";
                        o.passed = newest != null && asOf - newest <= maxAge;
                        // Freshness is the whole table's, whatever the partitions checked.
                        o.checkedRows = null;
                        o.locate = "SELECT MAX(`" + c + "`) FROM " + base;
                        break;
                    }
                default:
                    throw new IllegalArgumentException(
                            "Unknown rule kind " + r.path("kind").asText());
            }
            outcomes.add(o);
        }
        write(catalog, spec, database + "." + tableName, snapshot, partitions, asOf, outcomes);

        List<String> failed = new ArrayList<>();
        for (Outcome o : outcomes) {
            if (!o.passed) {
                failed.add(o.rule);
            }
        }
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("verdict", failed.isEmpty() ? "Passed" : "Failed");
        check.put("failed", failed);
        check.put("rows", rows);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshotBefore", snapshotId);
        result.put("snapshotAfter", snapshotId);
        result.put(
                "detail",
                "checked "
                        + rows
                        + " rows of snapshot "
                        + snapshotId
                        + (partitions.isEmpty() ? "" : " in " + partitions)
                        + ": "
                        + (outcomes.size() - failed.size())
                        + " of "
                        + outcomes.size()
                        + " rules passed"
                        + (failed.isEmpty() ? "" : ", failed " + failed));
        result.put("check", check);
        return result;
    }

    /** The partitions as a filter: each {@code k=v[,k=v]} a conjunction, any of them. */
    static String scope(FileStoreTable table, List<String> partitions) {
        if (partitions.isEmpty()) {
            return "";
        }
        List<String> any = new ArrayList<>();
        for (String p : partitions) {
            if (!PARTITION.matcher(p).matches()) {
                throw new IllegalArgumentException("Not a partition: " + p);
            }
            List<String> all = new ArrayList<>();
            for (String part : p.split(",")) {
                String[] kv = part.split("=", 2);
                String key = name(kv[0]);
                if (!table.partitionKeys().contains(key)) {
                    throw new IllegalArgumentException(key + " is not a partition key");
                }
                all.add("`" + key + "` = " + literal(table.rowType().getField(key).type(), kv[1]));
            }
            any.add("(" + String.join(" AND ", all) + ")");
        }
        return "(" + String.join(" OR ", any) + ")";
    }

    /** A partition value as a literal of its column's type. */
    static String literal(DataType type, String value) {
        if (type.is(DataTypeFamily.CHARACTER_STRING)) {
            return "'" + value.replace("'", "''") + "'";
        }
        if (type.is(DataTypeFamily.NUMERIC) && NUMBER.matcher(value).matches()) {
            return value;
        }
        if (type.getTypeRoot() == DataTypeRoot.DATE
                && value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            return "DATE '" + value + "'";
        }
        if (type.getTypeRoot() == DataTypeRoot.BOOLEAN
                && (value.equals("true") || value.equals("false"))) {
            return value.toUpperCase(Locale.ROOT);
        }
        throw new IllegalArgumentException("Cannot filter a " + type + " partition by " + value);
    }

    private static String and(String scope, String condition) {
        return scope.isEmpty() ? condition : scope + " AND " + condition;
    }

    /** The newest value of a freshness column as epoch milliseconds, or null when none. */
    static Long newest(RowType rowType, String column, Row row) {
        Object v = row.getField(0);
        if (v == null) {
            return null;
        }
        DataTypeRoot root = rowType.getField(column).type().getTypeRoot();
        if (root == DataTypeRoot.BIGINT) {
            return ((Number) v).longValue();
        }
        if (v instanceof Instant) {
            return ((Instant) v).toEpochMilli();
        }
        if (v instanceof LocalDateTime) {
            return ((LocalDateTime) v).toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        throw new IllegalArgumentException(column + " is not a TIMESTAMP or BIGINT column");
    }

    /** The results, one row per rule, in one commit of the store's results table. */
    private static void write(
            Catalog catalog,
            JsonNode spec,
            String checked,
            Snapshot snapshot,
            List<String> partitions,
            long asOf,
            List<Outcome> outcomes)
            throws Exception {
        Table results =
                catalog.getTable(
                        Identifier.create(
                                name(spec.path("store").asText("")),
                                name(spec.path("results").asText(""))));
        RowType type = results.rowType();
        Timestamp now = Timestamp.fromInstant(Instant.now());
        List<String> sorted = new ArrayList<>(partitions);
        sorted.sort(null);
        BatchWriteBuilder builder = results.newBatchWriteBuilder();
        try (BatchTableWrite write = builder.newWrite();
                BatchTableCommit commit = builder.newCommit()) {
            for (Outcome o : outcomes) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("task_uid", text(spec.path("taskUid").asText()));
                v.put("rule", text(o.rule));
                v.put("task", text(spec.path("task").asText()));
                v.put("checked_table", text(checked));
                v.put("partitions", text(String.join(";", sorted)));
                v.put("snapshot_id", snapshot.id());
                v.put("snapshot_time", Timestamp.fromEpochMillis(snapshot.timeMillis()));
                v.put("as_of", Timestamp.fromEpochMillis(asOf));
                v.put("rule_version", text(spec.path("ruleVersion").asText()));
                v.put("definition", text(o.definition));
                v.put("metric", text(o.metric));
                v.put("measured", o.measured);
                v.put("threshold", text(o.threshold));
                v.put("passed", o.passed);
                v.put("failing_rows", o.failingRows);
                v.put("checked_rows", o.checkedRows);
                v.put("locate", text(o.locate));
                v.put("checked_at", now);
                GenericRow row = new GenericRow(type.getFieldCount());
                List<DataField> fields = type.getFields();
                for (int i = 0; i < fields.size(); i++) {
                    if (!v.containsKey(fields.get(i).name())) {
                        throw new IllegalStateException(
                                "The results table has a column the check does not write: "
                                        + fields.get(i).name());
                    }
                    row.setField(i, v.get(fields.get(i).name()));
                }
                write.write(row);
            }
            commit.commit(write.prepareCommit());
        }
    }

    private static BinaryString text(String s) {
        return s == null ? null : BinaryString.fromString(s);
    }

    private static Row one(TableEnvironment tEnv, String sql) throws Exception {
        try (CloseableIterator<Row> rows = tEnv.executeSql(sql).collect()) {
            if (!rows.hasNext()) {
                throw new IllegalStateException("No row from " + sql);
            }
            return rows.next();
        }
    }

    private static String column(String value) {
        if (!COLUMN.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a plain column name: " + value);
        }
        return value;
    }

    private static String name(String value) {
        if (!SqlSpec.NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a plain name: " + value);
        }
        return value;
    }
}
