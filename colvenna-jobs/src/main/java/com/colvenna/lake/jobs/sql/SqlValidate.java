package com.colvenna.lake.jobs.sql;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableDescriptor;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.utils.LogicalTypeChecks;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.flink.LogicalTypeConversion;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.types.DataField;
import org.apache.paimon.utils.JsonSerdeUtil;

/**
 * Validates a declared table's query with the Flink 1.20 planner its job runs on (HLD 6.6), as the
 * table's own catalog identity, which can read only the declared upstream tables. The result is one
 * JSON object: the output schema as Paimon fields, the tables the plan reads as streams and as
 * lookups, or a reason and message when the query is refused. It goes to the pod's termination
 * message (gzip, base64), which only this code writes, and to stdout.
 *
 * <p>Refused:
 *
 * <ul>
 *   <li>{@code InvalidQuery}: not a single valid SELECT;
 *   <li>{@code UpstreamNotDeclared}: it reads a table the identity has no grant on;
 *   <li>{@code OutsideCatalog}: it reads a table of another catalog;
 *   <li>{@code UnsupportedColumn}: an output column is unnamed or a time attribute;
 *   <li>{@code KeyColumnMissing}: the primary key names a column the query does not output;
 *   <li>{@code UnorderedChangelog}: updates of one target key can reach the sink out of order, so
 *       the planner would add a sink upsert materializer. That operator keeps order but emits no
 *       update-before rows, and the target's changelog (changelog-producer input) would no longer
 *       be complete for downstream tables.
 * </ul>
 */
public final class SqlValidate {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern AST_SCAN =
            Pattern.compile("LogicalTableScan\\(table=\\[\\[([^\\]]+)\\]\\]");
    private static final Pattern STREAM_SCAN =
            Pattern.compile("TableSourceScan\\(table=\\[\\[([^,\\]]+), ([^,\\]]+), ([^,\\]]+)");
    private static final Pattern LOOKUP = Pattern.compile("LookupJoin\\(table=\\[([^\\]]+)\\]");
    private static final String PROBE = "default_catalog.default_database.colvenna_probe";

    /**
     * Physical operators whose state outlives a record: an aggregate, a regular or temporal join, a
     * rank or deduplication. A lookup join reads its dimension at each lookup and keeps none.
     */
    static final Pattern STATEFUL =
            Pattern.compile(
                    "(?m)^[\\s:+|-]*(GroupAggregate|GlobalGroupAggregate|IncrementalGroupAggregate"
                            + "|GroupTableAggregate|Join|IntervalJoin|TemporalJoin|WindowJoin|Rank"
                            + "|WindowRank|Deduplicate|WindowDeduplicate|GroupWindowAggregate"
                            + "|WindowAggregate|GlobalWindowAggregate|OverAggregate)\\(");

    private SqlValidate() {}

    /** The validation's outcome; {@code reason} is null when the query is accepted. */
    public static final class Result {
        public final String reason;
        public final String message;
        public final List<DataField> fields;
        public final Set<String> streams;
        public final Set<String> lookups;

        /** Whether the job keeps state a backfill of the target would not follow. */
        public final boolean stateful;

        Result(
                String reason,
                String message,
                List<DataField> fields,
                Set<String> streams,
                Set<String> lookups,
                boolean stateful) {
            this.reason = reason;
            this.message = message;
            this.fields = fields;
            this.streams = streams;
            this.lookups = lookups;
            this.stateful = stateful;
        }

        static Result refused(String reason, String message) {
            return new Result(reason, message, List.of(), Set.of(), Set.of(), false);
        }

        String json() throws IOException {
            Map<String, Object> out = new LinkedHashMap<>();
            if (reason != null) {
                out.put("reason", reason);
                out.put("message", message);
            } else {
                List<Object> list = new ArrayList<>();
                for (DataField f : fields) {
                    list.add(JSON.readTree(JsonSerdeUtil.toFlatJson(f)));
                }
                out.put("fields", list);
                out.put("streams", streams);
                out.put("lookups", lookups);
                out.put("stateful", stateful);
            }
            return JSON.writeValueAsString(out);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: SqlValidate <spec-json>");
        }
        Result result;
        try {
            SqlSpec spec = SqlSpec.parse(args[0]);
            TableEnvironment tEnv = TableEnvironment.create(EnvironmentSettings.inStreamingMode());
            ColvennaCatalog.register(tEnv);
            result = validate(tEnv, spec);
        } catch (RuntimeException e) {
            result = Result.refused("InvalidQuery", rootMessage(e));
        }
        String json = result.json();
        System.out.println(json);
        String file = System.getenv().getOrDefault("COLVENNA_RESULT_FILE", "/dev/termination-log");
        if (Files.isWritable(Path.of(file))) {
            Files.writeString(Path.of(file), gzipBase64(json), StandardCharsets.US_ASCII);
        }
    }

    /**
     * Validates against the catalog registered as {@code colvenna} and current in the environment.
     */
    public static Result validate(TableEnvironment tEnv, SqlSpec spec) {
        List<Column> columns;
        String plan;
        try {
            Table table = tEnv.sqlQuery(spec.query);
            columns = table.getResolvedSchema().getColumns();
            for (Column c : columns) {
                if (!SqlSpec.NAME.matcher(c.getName()).matches()) {
                    return Result.refused(
                            "UnsupportedColumn",
                            "output column "
                                    + c.getName()
                                    + " needs a lowercase name (use AS <name>)");
                }
                if (LogicalTypeChecks.isTimeAttribute(c.getDataType().getLogicalType())) {
                    return Result.refused(
                            "UnsupportedColumn",
                            "output column " + c.getName() + " is a time attribute");
                }
            }
            List<String> names = new ArrayList<>();
            for (Column c : columns) {
                names.add(c.getName());
            }
            for (String key : spec.primaryKey) {
                if (!names.contains(key)) {
                    return Result.refused(
                            "KeyColumnMissing", "the query does not output key column " + key);
                }
            }
            Schema.Builder probe = Schema.newBuilder();
            for (Column c : columns) {
                probe.column(
                        c.getName(),
                        spec.primaryKey.contains(c.getName())
                                ? c.getDataType().notNull()
                                : c.getDataType());
            }
            probe.primaryKey(spec.primaryKey);
            tEnv.createTemporaryTable(
                    PROBE, TableDescriptor.forConnector("blackhole").schema(probe.build()).build());
            try {
                plan = tEnv.explainSql("INSERT INTO " + PROBE + " " + spec.query);
            } finally {
                tEnv.dropTemporaryTable(PROBE);
            }
        } catch (RuntimeException e) {
            Catalog.TableNoPermissionException denied = cause(e);
            if (denied != null) {
                return Result.refused(
                        "UpstreamNotDeclared",
                        "the query reads "
                                + denied.identifier().getFullName()
                                + ", which is not a declared upstream table");
            }
            return Result.refused("InvalidQuery", rootMessage(e));
        }

        Set<String> outside = new TreeSet<>();
        for (String scanned : scans(AST_SCAN, plan, 1)) {
            String[] parts = scanned.split(",\\s*");
            if (parts.length != 3 || !parts[0].equals(ColvennaCatalog.NAME)) {
                outside.add(scanned);
            }
        }
        if (!outside.isEmpty()) {
            return Result.refused(
                    "OutsideCatalog",
                    "the query reads tables outside catalog colvenna: " + outside);
        }
        String physical = section(plan, "== Optimized Physical Plan ==");
        if (physical.contains("upsertMaterialize=[true]")) {
            return Result.refused(
                    "UnorderedChangelog",
                    "updates of one key of "
                            + String.join(", ", spec.primaryKey)
                            + " can arrive out of order (for example a regular join on a column"
                            + " outside the key); key the table by the join key, or use a lookup"
                            + " join");
        }
        Set<String> streams = new TreeSet<>();
        Matcher m = STREAM_SCAN.matcher(physical);
        while (m.find()) {
            streams.add(m.group(2) + "." + m.group(3));
        }
        Set<String> lookups = new TreeSet<>();
        for (String t : scans(LOOKUP, physical, 1)) {
            String[] parts = t.split("\\.");
            lookups.add(parts[parts.length - 2] + "." + parts[parts.length - 1]);
        }
        List<DataField> fields = new ArrayList<>();
        int id = 0;
        for (Column c : columns) {
            LogicalType type = c.getDataType().getLogicalType();
            if (spec.primaryKey.contains(c.getName())) {
                type = type.copy(false);
            }
            fields.add(new DataField(id++, c.getName(), LogicalTypeConversion.toDataType(type)));
        }
        return new Result(null, null, fields, streams, lookups, STATEFUL.matcher(physical).find());
    }

    private static List<String> scans(Pattern pattern, String text, int group) {
        List<String> out = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            out.add(m.group(group));
        }
        return out;
    }

    private static String section(String plan, String header) {
        int start = plan.indexOf(header);
        if (start < 0) {
            return "";
        }
        int end = plan.indexOf("\n== ", start + header.length());
        return end < 0 ? plan.substring(start) : plan.substring(start, end);
    }

    private static Catalog.TableNoPermissionException cause(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof Catalog.TableNoPermissionException) {
                return (Catalog.TableNoPermissionException) t;
            }
        }
        return null;
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
        return message.length() > 600 ? message.substring(0, 600) : message;
    }

    private static String gzipBase64(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
