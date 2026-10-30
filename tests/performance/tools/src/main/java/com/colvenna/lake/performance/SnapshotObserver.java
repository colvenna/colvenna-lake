package com.colvenna.lake.performance;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.Decimal;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.StreamTableScan;
import org.apache.paimon.table.source.TableRead;
import org.apache.paimon.table.source.TableScan;
import org.apache.paimon.types.DataField;
import org.apache.paimon.utils.InternalRowUtils;

/**
 * Follows lake tables through colvenna-catalog as their snapshots commit, the way a downstream
 * streaming reader does, and writes when each change became readable: JSON lines, appended to
 * {@code <out-file>}, until {@code <stop-file>} exists.
 *
 * <p>usage: {@code SnapshotObserver <out-file> <stop-file> <spec>...}, a spec one of
 *
 * <ul>
 *   <li>{@code rows=<db.table>[,<key>,<min>[,<column>...]]}: every change, or only those whose
 *       {@code key} is at least {@code min}, as {@code CHANGE} lines in LakeDump's form ({@code
 *       snapshot}, {@code committedAt}, {@code kind}, {@code row}), with {@code observedAt}; the
 *       row holds the key and the columns named (all of them without a key);
 *   <li>{@code offsets=<db.table>,<partition-column>,<offset-column>}: per snapshot, the offsets
 *       its changes hold, per partition, as ranges ({@code OFFSETS} lines), for an append table of
 *       Kafka events.
 * </ul>
 *
 * Each table is read from its latest snapshot when the observer starts ({@code latest-full}: the
 * rows it holds then come first, under that snapshot). A snapshot's changes are what the table's
 * stream read returns for it: its changelog where the table has one ({@code input} or {@code
 * lookup}), else its new files. {@code committedAt} is the snapshot's commit time, by the clock of
 * the job that committed it. Every minute each table writes a {@code BEAT} line (the next snapshot
 * it waits for); a failed read writes an {@code ERROR} line and is retried. The catalog is
 * colvenna-catalog's REST API, as colvenna-jobs' {@code Catalogs.restOptions()} reaches it: {@code
 * COLVENNA_CATALOG_URI}, {@code COLVENNA_CATALOG_TOKEN_FILE}, per-table data tokens.
 */
public final class SnapshotObserver {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long POLL_MILLIS = 1000;
    private static final long BEAT_MILLIS = 60_000;
    private static final long RETRY_MILLIS = 5000;

    private SnapshotObserver() {}

    /** What to follow in one table and what to write of it. */
    static final class Spec {
        final String mode;
        final String table;

        /** rows: the key column, or null for every change; offsets: the partition column. */
        final String key;

        /** rows: the smallest key written; offsets: unused. */
        final long min;

        /** rows: the other columns written; offsets: the offset column. */
        final List<String> columns;

        Spec(String mode, String table, String key, long min, List<String> columns) {
            this.mode = mode;
            this.table = table;
            this.key = key;
            this.min = min;
            this.columns = columns;
        }

        static Spec parse(String text) {
            int eq = text.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("Not a spec: " + text);
            }
            String mode = text.substring(0, eq);
            String[] parts = text.substring(eq + 1).split(",", -1);
            if (!parts[0].matches("[a-z0-9_]+\\.[a-z0-9_]+")) {
                throw new IllegalArgumentException("Not a table: " + parts[0]);
            }
            List<String> rest = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i].matches("[A-Za-z0-9_]+")) {
                    throw new IllegalArgumentException("Not a column or number: " + parts[i]);
                }
                rest.add(parts[i]);
            }
            if (mode.equals("rows")) {
                if (rest.isEmpty()) {
                    return new Spec(mode, parts[0], null, 0, List.of());
                }
                if (rest.size() < 2) {
                    throw new IllegalArgumentException("rows=<db.table>,<key>,<min>[,<column>...]");
                }
                return new Spec(
                        mode,
                        parts[0],
                        rest.get(0),
                        Long.parseLong(rest.get(1)),
                        List.copyOf(rest.subList(2, rest.size())));
            }
            if (mode.equals("offsets")) {
                if (rest.size() != 2) {
                    throw new IllegalArgumentException(
                            "offsets=<db.table>,<partition-column>,<offset-column>");
                }
                return new Spec(mode, parts[0], rest.get(0), 0, List.of(rest.get(1)));
            }
            throw new IllegalArgumentException("Unknown mode " + mode);
        }
    }

    /** One JSON line at a time, from every table's thread. */
    static final class Out {
        private final BufferedWriter writer;

        Out(BufferedWriter writer) {
            this.writer = writer;
        }

        synchronized void line(Map<String, Object> line) throws IOException {
            writer.write(JSON.writeValueAsString(line));
            writer.newLine();
        }

        synchronized void flush() throws IOException {
            writer.flush();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "usage: SnapshotObserver <out-file> <stop-file> <spec>...");
        }
        Path stop = Path.of(args[1]);
        List<Spec> specs = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            specs.add(Spec.parse(args[i]));
        }
        Map<String, String> options = new HashMap<>();
        options.put("metastore", "rest");
        options.put("uri", required("COLVENNA_CATALOG_URI"));
        options.put("warehouse", "colvenna");
        options.put("token.provider", "bear");
        options.put(
                "token",
                Files.readString(
                                Path.of(required("COLVENNA_CATALOG_TOKEN_FILE")),
                                StandardCharsets.UTF_8)
                        .trim());
        options.put("data-token.enabled", "true");
        try (Catalog catalog =
                        CatalogFactory.createCatalog(
                                CatalogContext.create(Options.fromMap(options)));
                BufferedWriter writer =
                        Files.newBufferedWriter(
                                Path.of(args[0]),
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND)) {
            Out out = new Out(writer);
            List<Thread> threads = new ArrayList<>();
            for (Spec spec : specs) {
                FileStoreTable table =
                        (FileStoreTable) catalog.getTable(Identifier.fromString(spec.table));
                Thread t =
                        new Thread(
                                () -> {
                                    try {
                                        follow(table, spec, out, () -> Files.exists(stop));
                                    } catch (Exception e) {
                                        throw new IllegalStateException(e);
                                    }
                                },
                                spec.table);
                t.start();
                threads.add(t);
            }
            for (Thread t : threads) {
                t.join();
            }
            out.flush();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value;
    }

    /** Follows one table until stopped says so; then writes an END line. */
    static void follow(FileStoreTable table, Spec spec, Out out, BooleanSupplier stopped)
            throws Exception {
        Map<String, String> scanOptions = new HashMap<>();
        scanOptions.put("scan.mode", "latest-full");
        FileStoreTable scanned = table.copyWithoutTimeTravel(scanOptions);
        List<DataField> fields = scanned.rowType().getFields();
        List<String> wanted = new ArrayList<>();
        if (spec.mode.equals("offsets")) {
            wanted.add(spec.key);
            wanted.addAll(spec.columns);
        } else if (spec.key != null) {
            wanted.add(spec.key);
            wanted.addAll(spec.columns);
        } else {
            fields.forEach(f -> wanted.add(f.name()));
        }
        int[] projection = new int[wanted.size()];
        List<DataField> projected = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            int index = scanned.rowType().getFieldNames().indexOf(wanted.get(i));
            if (index < 0) {
                throw new IllegalArgumentException(spec.table + " has no column " + wanted.get(i));
            }
            projection[i] = index;
            projected.add(fields.get(index));
        }
        ReadBuilder read = scanned.newReadBuilder().withProjection(projection);
        StreamTableScan scan = read.newStreamScan();
        TableRead reader = read.newRead();
        Long previous = null;
        long lastBeat = 0;
        while (!stopped.getAsBoolean()) {
            long now = System.currentTimeMillis();
            if (now - lastBeat >= BEAT_MILLIS) {
                Map<String, Object> beat = new LinkedHashMap<>();
                beat.put("type", "BEAT");
                beat.put("table", spec.table);
                beat.put("at", now);
                beat.put("next", previous);
                out.line(beat);
                out.flush();
                lastBeat = now;
            }
            TableScan.Plan plan;
            Long next;
            try {
                plan = scan.plan();
                next = scan.checkpoint();
            } catch (RuntimeException e) {
                error(out, spec, previous, e);
                Thread.sleep(RETRY_MILLIS);
                continue;
            }
            if (plan.splits().isEmpty()) {
                if (Objects.equals(next, previous)) {
                    Thread.sleep(POLL_MILLIS);
                }
                previous = next;
                continue;
            }
            // A plan is one snapshot's changes; the scan now waits for the one after it.
            long snapshot = next - 1;
            long committedAt = scanned.snapshotManager().snapshot(snapshot).timeMillis();
            long observedAt = System.currentTimeMillis();
            try {
                emit(spec, projected, reader, plan, snapshot, committedAt, observedAt, out);
            } catch (Exception e) {
                // The plan's files cannot be read again through this scan: what it held is lost
                // to the observer, and its error says which snapshot.
                error(out, spec, snapshot, e);
            }
            out.flush();
            previous = next;
        }
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("type", "END");
        end.put("table", spec.table);
        end.put("at", System.currentTimeMillis());
        end.put("next", previous);
        out.line(end);
        out.flush();
    }

    private static void error(Out out, Spec spec, Long snapshot, Exception e) throws IOException {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "ERROR");
        line.put("table", spec.table);
        line.put("at", System.currentTimeMillis());
        line.put("snapshot", snapshot);
        line.put("error", String.valueOf(e));
        out.line(line);
        out.flush();
    }

    private static void emit(
            Spec spec,
            List<DataField> projected,
            TableRead reader,
            TableScan.Plan plan,
            long snapshot,
            long committedAt,
            long observedAt,
            Out out)
            throws Exception {
        Map<String, List<Long>> offsets = new TreeMap<>();
        try (RecordReader<InternalRow> records = reader.createReader(plan)) {
            RecordReader.RecordIterator<InternalRow> batch;
            while ((batch = records.readBatch()) != null) {
                InternalRow row;
                while ((row = batch.next()) != null) {
                    if (spec.mode.equals("offsets")) {
                        offsets.computeIfAbsent(text(row, 0, projected), p -> new ArrayList<>())
                                .add(row.getLong(1));
                        continue;
                    }
                    if (spec.key != null && Long.parseLong(text(row, 0, projected)) < spec.min) {
                        continue;
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (int i = 0; i < projected.size(); i++) {
                        values.put(projected.get(i).name(), text(row, i, projected));
                    }
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("type", "CHANGE");
                    line.put("table", spec.table);
                    line.put("snapshot", snapshot);
                    line.put("committedAt", committedAt);
                    line.put("observedAt", observedAt);
                    line.put("kind", row.getRowKind().shortString());
                    line.put("row", values);
                    out.line(line);
                }
                batch.releaseBatch();
            }
        }
        if (spec.mode.equals("offsets")) {
            Map<String, Object> ranges = new TreeMap<>();
            long rows = 0;
            for (Map.Entry<String, List<Long>> e : offsets.entrySet()) {
                ranges.put(e.getKey(), ranges(e.getValue()));
                rows += e.getValue().size();
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("type", "OFFSETS");
            line.put("table", spec.table);
            line.put("snapshot", snapshot);
            line.put("committedAt", committedAt);
            line.put("observedAt", observedAt);
            line.put("rows", rows);
            line.put("ranges", ranges);
            out.line(line);
        }
    }

    /**
     * The offsets as sorted, inclusive [from, to] ranges; an offset that appears twice makes the
     * range it falls in appear twice, so a reader counts it as repeated.
     */
    static List<long[]> ranges(List<Long> offsets) {
        long[] sorted = offsets.stream().mapToLong(Long::longValue).sorted().toArray();
        List<long[]> out = new ArrayList<>();
        int i = 0;
        while (i < sorted.length) {
            long from = sorted[i];
            long to = from;
            int j = i + 1;
            while (j < sorted.length && sorted[j] == to + 1) {
                to = sorted[j];
                j++;
            }
            out.add(new long[] {from, to});
            // Repeats of the range's last offset start a range of their own.
            i = j;
            while (i < sorted.length && sorted[i] <= to) {
                out.add(new long[] {sorted[i], sorted[i]});
                i++;
            }
        }
        return out;
    }

    /** A value as LakeDump writes it: decimals in plain notation, null as null. */
    static String text(InternalRow row, int pos, List<DataField> fields) {
        if (row.isNullAt(pos)) {
            return null;
        }
        Object value = InternalRowUtils.get(row, pos, fields.get(pos).type());
        if (value instanceof Decimal) {
            return ((Decimal) value).toBigDecimal().toPlainString();
        }
        return value.toString();
    }
}
