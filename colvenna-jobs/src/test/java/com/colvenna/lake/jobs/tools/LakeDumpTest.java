package com.colvenna.lake.jobs.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.BatchWriteBuilder;
import org.apache.paimon.types.DataTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** LakeDump's lines on a local warehouse: the END line counts what came before it (#244). */
class LakeDumpTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path warehouse;

    private Catalog catalog() throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        return CatalogFactory.createCatalog(CatalogContext.create(options));
    }

    private static FileStoreTable table(Catalog catalog, String name) throws Exception {
        Identifier id = Identifier.create("db", name);
        catalog.createTable(
                id,
                Schema.newBuilder()
                        .column("k", DataTypes.BIGINT().notNull())
                        .column("v", DataTypes.STRING())
                        .primaryKey("k")
                        .options(Map.of("bucket", "1"))
                        .build(),
                false);
        return (FileStoreTable) catalog.getTable(id);
    }

    /** One commit of these keys, each with value v<key>. */
    private static void write(FileStoreTable table, long... keys) throws Exception {
        BatchWriteBuilder builder = table.newBatchWriteBuilder();
        try (BatchTableWrite write = builder.newWrite();
                BatchTableCommit commit = builder.newCommit()) {
            for (long key : keys) {
                write.write(GenericRow.of(key, BinaryString.fromString("v" + key)));
            }
            commit.commit(write.prepareCommit());
        }
    }

    /** The lines LakeDump writes, parsed. */
    private static List<Map<String, Object>> dump(
            Catalog catalog, String mode, StringWriter text, String... tables) throws Exception {
        try (PrintWriter out = new PrintWriter(text)) {
            LakeDump.dump(catalog, mode, List.of(tables), out);
        }
        return parse(text);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parse(StringWriter text) throws Exception {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (String line : text.toString().split("\n")) {
            if (!line.isEmpty()) {
                lines.add(JSON.readValue(line, Map.class));
            }
        }
        return lines;
    }

    private static Map<String, Object> end(String mode, long lines, Map<String, Integer> tables) {
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("type", "END");
        end.put("mode", mode);
        end.put("lines", (int) lines);
        end.put("tables", tables);
        return end;
    }

    private static List<String> types(List<Map<String, Object>> lines) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            out.add(line.get("type") + " " + line.getOrDefault("table", "-"));
        }
        return out;
    }

    @Test
    void rowsEndWithTheirCountsPerTable() throws Exception {
        try (Catalog catalog = catalog()) {
            catalog.createDatabase("db", false);
            FileStoreTable a = table(catalog, "a");
            FileStoreTable b = table(catalog, "b");
            write(a, 1, 2);
            write(a, 2, 3);
            write(b, 7);
            List<Map<String, Object>> lines =
                    dump(catalog, "rows", new StringWriter(), "db.a", "db.b");
            assertEquals(
                    List.of(
                            "TABLE db.a",
                            "ROW db.a",
                            "ROW db.a",
                            "ROW db.a",
                            "TABLE db.b",
                            "ROW db.b",
                            "END -"),
                    types(lines));
            assertEquals(end("rows", 6, Map.of("db.a", 3, "db.b", 1)), lines.get(lines.size() - 1));
        }
    }

    @Test
    void aTableAloneEndsWithNoRows() throws Exception {
        try (Catalog catalog = catalog()) {
            catalog.createDatabase("db", false);
            write(table(catalog, "a"), 1);
            List<Map<String, Object>> lines = dump(catalog, "table", new StringWriter(), "db.a");
            assertEquals(List.of("TABLE db.a", "END -"), types(lines));
            assertEquals(end("table", 1, Map.of("db.a", 0)), lines.get(1));
        }
    }

    @Test
    void aChangelogEndsWithItsChanges() throws Exception {
        try (Catalog catalog = catalog()) {
            catalog.createDatabase("db", false);
            FileStoreTable a = table(catalog, "a");
            write(a, 1, 2);
            write(a, 2, 3);
            List<Map<String, Object>> lines =
                    dump(catalog, "changelog", new StringWriter(), "db.a");
            int changes = lines.size() - 2;
            assertTrue(changes >= 3, "changes: " + types(lines));
            assertEquals("TABLE db.a", types(lines).get(0));
            for (Map<String, Object> line : lines.subList(1, lines.size() - 1)) {
                assertEquals("CHANGE", line.get("type"));
            }
            assertEquals(
                    end("changelog", 1 + changes, Map.of("db.a", changes)),
                    lines.get(lines.size() - 1));
        }
    }

    @Test
    void aDumpThatFailsPartWayHasNoEnd() throws Exception {
        try (Catalog catalog = catalog()) {
            catalog.createDatabase("db", false);
            write(table(catalog, "a"), 1, 2);
            StringWriter text = new StringWriter();
            assertThrows(
                    Catalog.TableNotExistException.class,
                    () -> dump(catalog, "rows", text, "db.a", "db.gone"));
            // What was written stays, flushed: it ends without the END line.
            assertEquals(List.of("TABLE db.a", "ROW db.a", "ROW db.a"), types(parse(text)));
        }
    }

    @Test
    void aTableGivenTwiceIsRefused() throws Exception {
        try (Catalog catalog = catalog()) {
            catalog.createDatabase("db", false);
            table(catalog, "a");
            IllegalArgumentException twice =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> dump(catalog, "rows", new StringWriter(), "db.a", "db.a"));
            assertTrue(twice.getMessage().contains("db.a given twice"));
        }
    }
}
