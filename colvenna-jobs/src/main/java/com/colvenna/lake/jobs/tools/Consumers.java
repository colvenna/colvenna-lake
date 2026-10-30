package com.colvenna.lake.jobs.tools;

import com.colvenna.lake.jobs.Catalogs;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.options.Options;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.table.FileStoreTable;

/**
 * A table's Paimon consumers, for the acceptance run scripts ({@code tests/acceptance/lake.sh}):
 * {@code list <db.table>} prints one JSON line per consumer; {@code drop <db.table> <id>} deletes
 * one, as an expired consumer would be, to show a layered job refusing to start without its
 * position.
 */
public final class Consumers {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Consumers() {}

    private static void print(Map<String, ?> line) throws IOException {
        System.out.println(JSON.writeValueAsString(line));
    }

    public static void main(String[] args) throws Exception {
        boolean list = args.length == 2 && args[0].equals("list");
        boolean drop = args.length == 3 && args[0].equals("drop");
        if (!list && !drop) {
            throw new IllegalArgumentException(
                    "usage: Consumers list <db.table> | drop <db.table> <id>");
        }
        try (Catalog catalog =
                CatalogFactory.createCatalog(
                        CatalogContext.create(Options.fromMap(Catalogs.restOptions())))) {
            FileStoreTable table =
                    (FileStoreTable) catalog.getTable(Identifier.fromString(args[1]));
            if (drop) {
                table.consumerManager().deleteConsumer(args[2]);
                print(Map.of("type", "DROPPED", "table", args[1], "id", args[2]));
                return;
            }
            for (Map.Entry<String, Long> c : table.consumerManager().consumers().entrySet()) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("type", "CONSUMER");
                line.put("table", args[1]);
                line.put("id", c.getKey());
                line.put("nextSnapshot", c.getValue());
                print(line);
            }
        }
    }
}
