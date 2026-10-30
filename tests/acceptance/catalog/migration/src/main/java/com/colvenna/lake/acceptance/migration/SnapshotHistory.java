package com.colvenna.lake.acceptance.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.paimon.Snapshot;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.Split;
import org.apache.paimon.utils.SnapshotManager;

/**
 * Whether every published snapshot of a table is still readable, so the history before an upgrade,
 * rollback or restore can be compared with the history after it. Per snapshot it prints {@code
 * HISTORY <table> <id> <commit-kind> <planned-rows> <read-rows>}: the rows its plan covers
 * (manifests read), and for the earliest, the latest and every 25th snapshot the rows actually read
 * from the data files ({@code -} for the others).
 *
 * <p>Arguments: {@code rest} (see {@link Catalogs}), database, table names.
 */
public final class SnapshotHistory {

    // Each snapshot's plan is a few dozen small object reads, and a table under test has hundreds
    // of snapshots: planned one at a time, a check outlasts the writers it runs beside.
    private static final int THREADS = 16;

    private SnapshotHistory() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("usage: SnapshotHistory rest <database> <table>...");
        }
        try (Catalog catalog = Catalogs.open(args[0])) {
            for (int i = 2; i < args.length; i++) {
                history(catalog, Identifier.create(args[1], args[i]));
            }
        }
        System.out.println("HISTORY DONE " + args[0]);
    }

    private static void history(Catalog catalog, Identifier id) throws Exception {
        FileStoreTable table = (FileStoreTable) catalog.getTable(id);
        Snapshot latest = table.latestSnapshot().orElse(null);
        SnapshotManager snapshots = table.snapshotManager();
        Long earliest = snapshots.earliestSnapshotId();
        if (latest == null || earliest == null) {
            System.out.println("HISTORY " + id.getFullName() + " none");
            return;
        }
        long first = earliest;
        long last = latest.id();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<String>> records = new ArrayList<>();
            for (long n = first; n <= last; n++) {
                long snapshotId = n;
                boolean readRows = n == first || n == last || n % 25 == 0;
                records.add(pool.submit(() -> record(table, snapshots, snapshotId, readRows)));
            }
            for (Future<String> record : records) {
                System.out.println("HISTORY " + id.getFullName() + " " + record.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** {@code <id> <commit-kind> <planned-rows> <read-rows>} of one snapshot. */
    private static String record(
            FileStoreTable table, SnapshotManager snapshots, long n, boolean readRows)
            throws Exception {
        Snapshot snapshot = snapshots.snapshot(n);
        ReadBuilder read =
                table.copy(Map.of("scan.snapshot-id", Long.toString(n))).newReadBuilder();
        long planned = 0;
        var splits = read.newScan().plan().splits();
        for (Split split : splits) {
            planned += split.rowCount();
        }
        String rows = "-";
        if (readRows) {
            long count = 0;
            try (RecordReader<InternalRow> reader = read.newRead().createReader(splits)) {
                RecordReader.RecordIterator<InternalRow> batch;
                while ((batch = reader.readBatch()) != null) {
                    while (batch.next() != null) {
                        count++;
                    }
                    batch.releaseBatch();
                }
            }
            rows = Long.toString(count);
        }
        return n + " " + snapshot.commitKind() + " " + planned + " " + rows;
    }
}
