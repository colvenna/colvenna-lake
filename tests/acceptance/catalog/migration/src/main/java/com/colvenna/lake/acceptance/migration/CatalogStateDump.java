package com.colvenna.lake.acceptance.migration;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.apache.paimon.Snapshot;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.rest.RESTApi;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataTypeFamily;
import org.apache.paimon.utils.InternalRowUtils;

/**
 * The state of tables as one catalog sees them, so the JDBC view before a switch can be compared
 * with the REST view after it. Per table it prints one {@code STATE <json>} line: latest and
 * earliest snapshot, snapshot count, latest commit kind, schema id, tags, branches, and from a full
 * scan the row count, per-column sums of numeric columns, and an order-independent row checksum
 * (the sum of 64-bit row hashes).
 *
 * <p>Arguments: {@code rest} (see {@link Catalogs}), database, table names.
 */
public final class CatalogStateDump {

    private CatalogStateDump() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "usage: CatalogStateDump rest <database> <table>...");
        }
        String database = args[1];
        try (Catalog catalog = Catalogs.open(args[0])) {
            for (int i = 2; i < args.length; i++) {
                Map<String, Object> state = state(catalog, Identifier.create(database, args[i]));
                System.out.println("STATE " + RESTApi.toJson(state));
            }
        }
        System.out.println("DUMP DONE " + args[0]);
    }

    static Map<String, Object> state(Catalog catalog, Identifier id) throws Exception {
        FileStoreTable table = (FileStoreTable) catalog.getTable(id);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("table", id.getFullName());
        Snapshot latest = table.latestSnapshot().orElse(null);
        s.put("latestSnapshot", latest == null ? null : latest.id());
        s.put("latestCommitKind", latest == null ? null : latest.commitKind().name());
        Long earliest = table.snapshotManager().earliestSnapshotId();
        s.put("earliestSnapshot", earliest);
        s.put("snapshotCount", latest == null || earliest == null ? 0 : latest.id() - earliest + 1);
        s.put("schemaId", table.schema().id());
        s.put("tags", new TreeSet<>(table.tagManager().allTagNames()));
        s.put("branches", new TreeSet<>(table.branchManager().branches()));
        List<DataField> fields = table.rowType().getFields();
        long rows = 0;
        long checksum = 0;
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        List<Integer> numeric = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).type().is(DataTypeFamily.NUMERIC)) {
                numeric.add(i);
                sums.put(fields.get(i).name(), BigDecimal.ZERO);
            }
        }
        ReadBuilder read = table.newReadBuilder();
        try (RecordReader<InternalRow> reader =
                read.newRead().createReader(read.newScan().plan())) {
            RecordReader.RecordIterator<InternalRow> batch;
            while ((batch = reader.readBatch()) != null) {
                InternalRow row;
                while ((row = batch.next()) != null) {
                    rows++;
                    StringBuilder text = new StringBuilder();
                    for (int i = 0; i < fields.size(); i++) {
                        Object value = InternalRowUtils.get(row, i, fields.get(i).type());
                        text.append(stable(value)).append('\u0001');
                        if (value != null && numeric.contains(i) && finite(value)) {
                            String name = fields.get(i).name();
                            sums.put(name, sums.get(name).add(new BigDecimal(value.toString())));
                        }
                    }
                    checksum += fnv1a(text.toString());
                }
                batch.releaseBatch();
            }
        }
        s.put("rows", rows);
        s.put("checksum", Long.toHexString(checksum));
        Map<String, String> sumText = new LinkedHashMap<>();
        sums.forEach((k, v) -> sumText.put(k, v.toPlainString()));
        s.put("sums", sumText);
        return s;
    }

    /** NaN and the infinities have no sum; the row checksum still covers them. */
    private static boolean finite(Object value) {
        return !(value instanceof Double && !Double.isFinite((Double) value))
                && !(value instanceof Float && !Float.isFinite((Float) value));
    }

    /** A text form that is the same in every JVM: no identity hash codes. */
    private static String stable(Object value) {
        if (value instanceof byte[]) {
            StringBuilder hex = new StringBuilder();
            for (byte b : (byte[]) value) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16))
                        .append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        }
        if (value instanceof org.apache.paimon.data.InternalArray) {
            return "array[" + ((org.apache.paimon.data.InternalArray) value).size() + "]";
        }
        if (value instanceof org.apache.paimon.data.InternalMap) {
            return "map[" + ((org.apache.paimon.data.InternalMap) value).size() + "]";
        }
        if (value instanceof InternalRow) {
            return "row[" + ((InternalRow) value).getFieldCount() + "]";
        }
        return String.valueOf(value);
    }

    /** 64-bit FNV-1a; summed over rows, the total does not depend on the scan order. */
    static long fnv1a(String text) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
