package com.colvenna.lake.upstream.cdc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import org.apache.paimon.Snapshot;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.StreamTableScan;
import org.apache.paimon.table.source.TableScan;

/** The local warehouse the jobs write: tables of (id BIGINT, v STRING) in database ods. */
final class Lake {
    private Lake() {}

    /** One change of a table's changelog: its kind (+I, -U, +U, -D), key and value. */
    static final class Change {
        final String kind;
        final long id;
        final String v;

        Change(String kind, long id, String v) {
            this.kind = kind;
            this.id = id;
            this.v = v;
        }

        @Override
        public String toString() {
            return kind + "(" + id + ", " + v + ")";
        }
    }

    private static Catalog catalog(Path warehouse) {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        return CatalogFactory.createCatalog(CatalogContext.create(options));
    }

    /** The latest snapshot's rows: id to v; empty while the table does not exist. */
    static TreeMap<Long, String> values(Path warehouse, String table) throws Exception {
        TreeMap<Long, String> out = new TreeMap<>();
        try (Catalog catalog = catalog(warehouse)) {
            FileStoreTable t;
            try {
                t = (FileStoreTable) catalog.getTable(Identifier.create("ods", table));
            } catch (Catalog.TableNotExistException e) {
                return out;
            }
            ReadBuilder read = t.newReadBuilder();
            try (RecordReader<InternalRow> reader =
                    read.newRead().createReader(read.newScan().plan())) {
                reader.forEachRemaining(
                        row ->
                                out.put(
                                        row.getLong(0),
                                        row.isNullAt(1) ? null : row.getString(1).toString()));
            }
        }
        return out;
    }

    static TreeSet<Long> ids(Path warehouse, String table) throws Exception {
        return new TreeSet<>(values(warehouse, table).keySet());
    }

    /** Every change from the earliest snapshot to the latest, in commit order. */
    static List<Change> changelog(Path warehouse, String table) throws Exception {
        List<Change> out = new ArrayList<>();
        try (Catalog catalog = catalog(warehouse)) {
            FileStoreTable t = (FileStoreTable) catalog.getTable(Identifier.create("ods", table));
            Long earliest = t.snapshotManager().earliestSnapshotId();
            Snapshot latest = t.snapshotManager().latestSnapshot();
            if (earliest == null || latest == null) {
                return out;
            }
            Map<String, String> options = new HashMap<>();
            options.put("scan.mode", "from-snapshot");
            options.put("scan.snapshot-id", Long.toString(earliest));
            ReadBuilder read = t.copyWithoutTimeTravel(options).newReadBuilder();
            StreamTableScan scan = read.newStreamScan();
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline) {
                TableScan.Plan plan = scan.plan();
                if (!plan.splits().isEmpty()) {
                    try (RecordReader<InternalRow> reader = read.newRead().createReader(plan)) {
                        reader.forEachRemaining(
                                row ->
                                        out.add(
                                                new Change(
                                                        row.getRowKind().shortString(),
                                                        row.getLong(0),
                                                        row.isNullAt(1)
                                                                ? null
                                                                : row.getString(1).toString())));
                    }
                }
                Long next = scan.checkpoint();
                if (next != null && next > latest.id()) {
                    return out;
                }
            }
            throw new IllegalStateException(
                    "the changelog of " + table + " was not read to its end");
        }
    }

    /**
     * Replays a changelog: each -U and -D must carry the row the table held, no +I may insert a key
     * it held already (a change applied twice), and the replay must end on {@code want}. Null when
     * it does, else what went wrong first.
     */
    static String replay(List<Change> changes, Map<Long, String> want) {
        TreeMap<Long, String> state = new TreeMap<>();
        for (Change c : changes) {
            switch (c.kind) {
                case "+I":
                    if (state.containsKey(c.id)) {
                        return "+I of " + c.id + " it held already: " + c;
                    }
                    state.put(c.id, c.v);
                    break;
                case "+U":
                    state.put(c.id, c.v);
                    break;
                case "-U":
                case "-D":
                    if (!state.containsKey(c.id) || !Objects.equals(state.get(c.id), c.v)) {
                        return "a before image the table did not hold: "
                                + c
                                + ", held "
                                + state.get(c.id);
                    }
                    state.remove(c.id);
                    break;
                default:
                    return "unknown change " + c;
            }
        }
        return state.equals(want)
                ? null
                : "the replay ends on " + state + ", the source holds " + want;
    }

    static boolean await(BooleanSupplier condition, int seconds) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(1000);
        }
        return condition.getAsBoolean();
    }

    static BooleanSupplier has(Path warehouse, String table, int n) {
        return () -> {
            try {
                return values(warehouse, table).size() >= n;
            } catch (Exception e) {
                return false;
            }
        };
    }
}
