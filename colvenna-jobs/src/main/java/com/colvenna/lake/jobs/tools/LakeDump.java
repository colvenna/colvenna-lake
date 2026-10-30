package com.colvenna.lake.jobs.tools;

import com.colvenna.lake.jobs.Catalogs;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.paimon.Snapshot;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.Decimal;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.data.Timestamp;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.StreamTableScan;
import org.apache.paimon.table.source.TableScan;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataType;
import org.apache.paimon.utils.InternalRowUtils;

/**
 * Evidence reader: dumps a Paimon table through colvenna-catalog, as JSON lines on stdout.
 *
 * <ul>
 *   <li>{@code table <db.table>...}: the {@code TABLE} line alone (schema, keys, earliest and
 *       latest snapshot, tags).
 *   <li>{@code rows <db.table>...}: a {@code TABLE} line (schema, keys, latest snapshot) and one
 *       {@code ROW} line per row of the latest snapshot.
 *   <li>{@code changelog <db.table>}: every change of every snapshot from the earliest retained
 *       one, in commit order, as {@code CHANGE} lines with the row kind, so a checker can replay
 *       the table and judge each before image.
 * </ul>
 *
 * Values are strings (decimals in plain notation), null as JSON null. The last line, written only
 * once every table is written, is {@code {"type":"END","mode":...,"lines":<lines before
 * it>,"tables":{<db.table>:<its ROW or CHANGE lines>,...}}}: a reader of the output can tell a
 * whole dump from one cut short at either end or in between (#244).
 */
public final class LakeDump {
    private static final ObjectMapper JSON = new ObjectMapper();

    private LakeDump() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "usage: LakeDump table|rows|changelog <db.table>...");
        }
        try (Catalog catalog =
                        CatalogFactory.createCatalog(
                                CatalogContext.create(Options.fromMap(Catalogs.restOptions())));
                PrintWriter out =
                        new PrintWriter(
                                new BufferedWriter(
                                        new OutputStreamWriter(
                                                System.out, StandardCharsets.UTF_8)))) {
            dump(catalog, args[0], Arrays.asList(args).subList(1, args.length), out);
            // PrintWriter keeps write errors to itself: a dump that could not be written fails.
            if (out.checkError()) {
                throw new IOException("writing the dump failed");
            }
        }
    }

    /**
     * The mode's lines of each table, then the END line. A table that fails to read throws before
     * the END line, so the lines written up to it never pass for a whole dump.
     */
    static void dump(Catalog catalog, String mode, List<String> tables, PrintWriter out)
            throws Exception {
        if (!mode.equals("table") && !mode.equals("rows") && !mode.equals("changelog")) {
            throw new IllegalArgumentException("Unknown mode " + mode);
        }
        Map<String, Long> written = new LinkedHashMap<>();
        long lines = 0;
        for (String name : tables) {
            // The END line counts per table: a table named twice could not be told apart.
            if (written.containsKey(name)) {
                throw new IllegalArgumentException("Table " + name + " given twice");
            }
            FileStoreTable table = (FileStoreTable) catalog.getTable(Identifier.fromString(name));
            header(name, table, out);
            long n = 0;
            if (mode.equals("rows")) {
                n = rows(name, table, out);
            } else if (mode.equals("changelog")) {
                n = changelog(name, table, out);
            }
            out.flush();
            written.put(name, n);
            lines += 1 + n;
        }
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("type", "END");
        end.put("mode", mode);
        end.put("lines", lines);
        end.put("tables", written);
        out.println(JSON.writeValueAsString(end));
        out.flush();
    }

    private static void header(String name, FileStoreTable table, PrintWriter out)
            throws Exception {
        Snapshot latest = table.snapshotManager().latestSnapshot();
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "TABLE");
        line.put("table", name);
        List<Map<String, String>> fields = new ArrayList<>();
        for (DataField f : table.rowType().getFields()) {
            Map<String, String> field = new LinkedHashMap<>();
            field.put("name", f.name());
            field.put("type", f.type().toString());
            fields.add(field);
        }
        line.put("fields", fields);
        line.put("primaryKeys", table.primaryKeys());
        line.put("snapshot", latest == null ? null : latest.id());
        line.put("earliestSnapshot", table.snapshotManager().earliestSnapshotId());
        line.put("schemaId", table.schema().id());
        Map<String, Long> tags = new TreeMap<>();
        table.tagManager()
                .tags()
                .forEach((snapshot, names) -> names.forEach(n -> tags.put(n, snapshot.id())));
        line.put("tags", tags);
        line.put("options", new TreeMap<>(table.options()));
        out.println(JSON.writeValueAsString(line));
    }

    /** The latest snapshot's rows as ROW lines; how many. */
    private static long rows(String name, FileStoreTable table, PrintWriter out) throws Exception {
        ReadBuilder read = table.newReadBuilder();
        TableScan.Plan plan = read.newScan().plan();
        return emit(name, table, read, plan, "ROW", null, out);
    }

    /** Every change from the earliest snapshot as CHANGE lines; how many. */
    private static long changelog(String name, FileStoreTable table, PrintWriter out)
            throws Exception {
        Long earliest = table.snapshotManager().earliestSnapshotId();
        Snapshot latest = table.snapshotManager().latestSnapshot();
        if (earliest == null || latest == null) {
            return 0;
        }
        Map<String, String> options = new HashMap<>();
        options.put("scan.mode", "from-snapshot");
        options.put("scan.snapshot-id", Long.toString(earliest));
        // Not copy(): with scan.snapshot-id it would time-travel to that snapshot's schema, and
        // rows
        // of later schemas would be read with the old one.
        ReadBuilder read = table.copyWithoutTimeTravel(options).newReadBuilder();
        StreamTableScan scan = read.newStreamScan();
        // Plans are per snapshot; stop once the scan has moved past the latest one. A scan that
        // stops short of it, or runs out of time, fails instead of passing for a full changelog.
        long deadline = System.currentTimeMillis() + 600_000;
        Long previous = null;
        int idle = 0;
        long n = 0;
        while (true) {
            if (System.currentTimeMillis() >= deadline) {
                // A partial changelog must not pass for a complete one.
                throw new IllegalStateException(
                        "Changelog of "
                                + name
                                + " not read to snapshot "
                                + latest.id()
                                + " in time");
            }
            TableScan.Plan plan = scan.plan();
            Long next = scan.checkpoint();
            if (!plan.splits().isEmpty()) {
                n += emit(name, table, read, plan, "CHANGE", next == null ? null : next - 1, out);
                idle = 0;
            } else if (java.util.Objects.equals(next, previous) && ++idle > 3) {
                throw new IllegalStateException(
                        "Changelog of "
                                + name
                                + " stopped at snapshot "
                                + next
                                + " of "
                                + latest.id());
            }
            System.err.println("LakeDump changelog " + name + " next=" + next);
            if (next != null && next > latest.id()) {
                break;
            }
            previous = next;
        }
        return n;
    }

    /** The plan's rows as lines of that type; how many. */
    private static long emit(
            String name,
            FileStoreTable table,
            ReadBuilder read,
            TableScan.Plan plan,
            String type,
            Long snapshot,
            PrintWriter out)
            throws Exception {
        List<DataField> fields = table.rowType().getFields();
        // When the changes became readable: what latency is measured to (the acceptance
        // contract's 2.1).
        Long committedAt =
                snapshot == null ? null : table.snapshotManager().snapshot(snapshot).timeMillis();
        long n = 0;
        try (RecordReader<InternalRow> reader = read.newRead().createReader(plan)) {
            RecordReader.RecordIterator<InternalRow> batch;
            while ((batch = reader.readBatch()) != null) {
                InternalRow row;
                while ((row = batch.next()) != null) {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("type", type);
                    line.put("table", name);
                    if (snapshot != null) {
                        line.put("snapshot", snapshot);
                        line.put("committedAt", committedAt);
                    }
                    line.put("kind", row.getRowKind().shortString());
                    Map<String, String> values = new LinkedHashMap<>();
                    for (int i = 0; i < fields.size(); i++) {
                        Object v = InternalRowUtils.get(row, i, fields.get(i).type());
                        values.put(fields.get(i).name(), text(v, fields.get(i).type()));
                    }
                    line.put("row", values);
                    out.println(JSON.writeValueAsString(line));
                    n++;
                }
                batch.releaseBatch();
            }
        }
        return n;
    }

    /** A value as text: dates and times in ISO form, decimals in plain notation. */
    private static String text(Object value, DataType type) {
        if (value == null) {
            return null;
        }
        switch (type.getTypeRoot()) {
            case DATE:
                return LocalDate.ofEpochDay((Integer) value).toString();
            case TIME_WITHOUT_TIME_ZONE:
                return LocalTime.ofNanoOfDay((Integer) value * 1_000_000L).toString();
            case TIMESTAMP_WITHOUT_TIME_ZONE:
                return ((Timestamp) value).toLocalDateTime().toString();
            case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                return ((Timestamp) value).toInstant().toString();
            default:
                break;
        }
        if (value instanceof Decimal) {
            BigDecimal d = ((Decimal) value).toBigDecimal();
            return d.toPlainString();
        }
        return value.toString();
    }
}
