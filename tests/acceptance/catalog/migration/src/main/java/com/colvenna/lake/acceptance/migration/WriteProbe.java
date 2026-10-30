package com.colvenna.lake.acceptance.migration;

import java.util.List;
import java.util.Map;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.BatchWriteBuilder;
import org.apache.paimon.types.DataField;

/**
 * Whether a table takes writes: one row through colvenna-catalog per table, 103 in integer columns
 * and {@code rest} in string columns. Per table it prints {@code PROBE write <table> COMMITTED}.
 *
 * <p>Arguments: {@code rest} (see {@link Catalogs}), database, table names.
 */
public final class WriteProbe {

    private WriteProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("usage: WriteProbe rest <database> <table>...");
        }
        try (Catalog catalog = Catalogs.open(args[0])) {
            for (int i = 2; i < args.length; i++) {
                Identifier id = Identifier.create(args[1], args[i]);
                System.out.println("PROBE write " + id.getFullName() + " " + write(catalog, id));
            }
        }
        System.out.println("PROBES DONE write");
    }

    private static String write(Catalog catalog, Identifier id) throws Exception {
        // Few retries: a table that refuses the commit refuses every one of them.
        Table table =
                catalog.getTable(id)
                        .copy(
                                Map.of(
                                        "commit.max-retries", "2",
                                        "commit.min-retry-wait", "100 ms",
                                        "commit.max-retry-wait", "1 s"));
        // Any table: 103 in every integer column, "rest" in every string column, null elsewhere.
        List<DataField> fields = table.rowType().getFields();
        GenericRow row = new GenericRow(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            switch (fields.get(i).type().getTypeRoot()) {
                case INTEGER:
                    row.setField(i, 103);
                    break;
                case BIGINT:
                    row.setField(i, 103L);
                    break;
                case VARCHAR:
                case CHAR:
                    row.setField(i, BinaryString.fromString("rest"));
                    break;
                default:
                    // Null is a value only where the column takes one.
                    if (!fields.get(i).type().isNullable()) {
                        throw new IllegalArgumentException(
                                "The write probe cannot fill column "
                                        + fields.get(i).name()
                                        + " of type "
                                        + fields.get(i).type());
                    }
                    break;
            }
        }
        BatchWriteBuilder builder = table.newBatchWriteBuilder();
        try (BatchTableWrite write = builder.newWrite();
                BatchTableCommit commit = builder.newCommit()) {
            write.write(row);
            commit.commit(write.prepareCommit());
        }
        return "COMMITTED";
    }
}
