package com.colvenna.lake.operator.lake;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.BatchWriteBuilder;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.types.DataTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The quality store's rows written from Java values and read back as them, on a local table. */
class RowsTest {
    @TempDir Path warehouse;

    @Test
    void javaValuesRoundTripThroughATable() throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options));
        catalog.createDatabase("quality_x", true);
        Identifier id = Identifier.create("quality_x", "decisions");
        catalog.createTable(
                id,
                Schema.newBuilder()
                        .column("checked_table", DataTypes.STRING())
                        .column("decided_at", DataTypes.TIMESTAMP(3))
                        .column("open", DataTypes.BOOLEAN())
                        .column("snapshot_id", DataTypes.BIGINT())
                        .column("measured", DataTypes.DOUBLE())
                        .column("amount", DataTypes.DECIMAL(12, 2))
                        .build(),
                false);
        Table t = catalog.getTable(id);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("checked_table", "ods.clicks");
        row.put("decided_at", Instant.parse("2026-10-16T08:00:00.123Z"));
        row.put("open", false);
        // Integers where the column is BIGINT or DOUBLE, as JSON gives them.
        row.put("snapshot_id", 9);
        row.put("measured", 3);
        row.put("amount", new BigDecimal("12.50"));
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("checked_table", "ods.orders");
        BatchWriteBuilder builder = t.newBatchWriteBuilder();
        try (BatchTableWrite w = builder.newWrite();
                BatchTableCommit c = builder.newCommit()) {
            w.write(Rows.toInternal(row, t.rowType()));
            w.write(Rows.toInternal(missing, t.rowType()));
            c.commit(w.prepareCommit());
        }
        List<Map<String, Object>> read = new ArrayList<>();
        ReadBuilder rb = t.newReadBuilder();
        try (RecordReader<InternalRow> reader = rb.newRead().createReader(rb.newScan().plan())) {
            reader.forEachRemaining(r -> read.add(Rows.toJava(r, t.rowType())));
        }
        read.sort(
                (a, b) ->
                        ((String) a.get("checked_table"))
                                .compareTo((String) b.get("checked_table")));
        Map<String, Object> expected = new LinkedHashMap<>(row);
        expected.put("snapshot_id", 9L);
        expected.put("measured", 3.0);
        assertThat(read.get(0)).isEqualTo(expected);
        assertThat(read.get(1))
                .containsEntry("checked_table", "ods.orders")
                .containsEntry("decided_at", null)
                .containsEntry("open", null);
    }
}
