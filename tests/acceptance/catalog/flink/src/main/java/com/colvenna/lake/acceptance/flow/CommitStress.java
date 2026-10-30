package com.colvenna.lake.acceptance.flow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.paimon.Snapshot;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.rest.exceptions.RESTException;
import org.apache.paimon.rest.exceptions.ServiceFailureException;
import org.apache.paimon.rest.exceptions.ServiceUnavailableException;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.table.sink.StreamTableCommit;
import org.apache.paimon.table.sink.StreamTableWrite;
import org.apache.paimon.table.sink.StreamWriteBuilder;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.types.DataTypes;

/**
 * Concurrent writers commit to one append table through colvenna-catalog while the operator injects
 * faults, and every commit's final state is then determined and checked.
 *
 * <ul>
 *   <li>Each writer has its own commit user and numbers its commits; a commit that fails is retried
 *       with {@code filterAndCommit}, which commits it only if no snapshot of that user and number
 *       exists, so a retry never duplicates rows.
 *   <li>A reader keeps loading the latest snapshot and checks that it shows whole commits only.
 *   <li>At the end every acknowledged commit must be present exactly once, every commit's outcome
 *       must be known, and snapshot ids must be contiguous.
 * </ul>
 *
 * <p>Arguments: database, table, writers, commits per writer, rows per commit, pause in ms between
 * commits. Environment as {@link FlinkCatalogFlow}. Prints {@code STEP} lines, {@code RESULT} JSON
 * and {@code FLOW PASS <database>}.
 */
public final class CommitStress {

    /** How one commit ended, as the writer saw it and after resolution. */
    enum Outcome {
        ACKED,
        FAILED_THEN_FOUND_COMMITTED,
        FAILED_THEN_RETRIED,
        UNRESOLVED
    }

    /** One commit's final state. Not a record: the flow jar targets the Flink image's Java 11. */
    private static final class Commit {
        private final int writer;
        private final long number;
        private final Outcome outcome;
        private final int attempts;
        private final String error;

        Commit(int writer, long number, Outcome outcome, int attempts, String error) {
            this.writer = writer;
            this.number = number;
            this.outcome = outcome;
            this.attempts = attempts;
            this.error = error;
        }

        int writer() {
            return writer;
        }

        long number() {
            return number;
        }

        Outcome outcome() {
            return outcome;
        }

        @Override
        public String toString() {
            return key(writer, number)
                    + " "
                    + outcome
                    + " after "
                    + attempts
                    + " attempts: "
                    + error;
        }
    }

    private static final int MAX_ATTEMPTS = 30;

    private final Catalog catalog;
    private final Identifier id;
    private final int rows;
    private final long pauseMillis;
    private final Map<String, Commit> commits = new ConcurrentHashMap<>();
    private final AtomicBoolean writing = new AtomicBoolean(true);
    private final AtomicLong readerChecks = new AtomicLong();
    private final List<String> readerViolations = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong readerOutages = new AtomicLong();

    private CommitStress(Catalog catalog, Identifier id, int rows, long pauseMillis) {
        this.catalog = catalog;
        this.id = id;
        this.rows = rows;
        this.pauseMillis = pauseMillis;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException(
                    "usage: CommitStress <database> <table> <writers> <commits> <rows> <pause-ms>");
        }
        Identifier id = Identifier.create(args[0], args[1]);
        int writers = Integer.parseInt(args[2]);
        int perWriter = Integer.parseInt(args[3]);
        int rows = Integer.parseInt(args[4]);
        long pause = Long.parseLong(args[5]);
        Options options = Options.fromMap(FlinkCatalogFlow.catalogOptions());
        try (Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options))) {
            catalog.createDatabase(id.getDatabaseName(), true);
            catalog.createTable(
                    id,
                    Schema.newBuilder()
                            .column("writer", DataTypes.INT())
                            .column("commit_no", DataTypes.BIGINT())
                            .column("row_no", DataTypes.INT())
                            .column("payload", DataTypes.STRING())
                            .option("bucket", "-1")
                            .option("write-only", "true")
                            .build(),
                    false);
            new CommitStress(catalog, id, rows, pause).run(writers, perWriter);
        }
    }

    private void run(int writers, int perWriter) throws Exception {
        Instant start = Instant.now();
        // Daemon threads: a failed writer must not leave the JVM waiting on the others.
        ExecutorService pool =
                Executors.newFixedThreadPool(
                        writers + 1,
                        r -> {
                            Thread t = new Thread(r);
                            t.setDaemon(true);
                            return t;
                        });
        try {
            Future<?> reader = pool.submit(this::readLoop);
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 1; w <= writers; w++) {
                int writer = w;
                futures.add(pool.submit(() -> write(writer, perWriter)));
            }
            try {
                for (Future<?> f : futures) {
                    f.get();
                }
            } finally {
                writing.set(false);
            }
            reader.get();
        } finally {
            pool.shutdownNow();
        }
        Duration took = Duration.between(start, Instant.now());

        Map<Outcome, Integer> outcomes = new TreeMap<>();
        commits.values().forEach(c -> outcomes.merge(c.outcome(), 1, Integer::sum));
        commits.values().stream()
                .filter(c -> c.outcome() != Outcome.ACKED)
                .forEach(c -> System.out.println("RETRIED " + c));
        System.out.println(
                "STEP writers OK "
                        + commits.size()
                        + " commits in "
                        + took.toSeconds()
                        + "s, outcomes "
                        + outcomes);

        // Every commit must be present exactly once, with all its rows.
        Map<String, Integer> present = rowsPerCommit();
        List<String> wrong = new ArrayList<>();
        for (Commit c : commits.values()) {
            int seen = present.getOrDefault(key(c.writer(), c.number()), 0);
            boolean expectPresent = c.outcome() != Outcome.UNRESOLVED;
            if (expectPresent && seen != rows) {
                wrong.add(key(c.writer(), c.number()) + " " + c.outcome() + " has " + seen);
            }
        }
        for (Map.Entry<String, Integer> e : present.entrySet()) {
            if (!commits.containsKey(e.getKey())) {
                wrong.add(e.getKey() + " present but never written");
            } else if (e.getValue() != rows) {
                wrong.add(e.getKey() + " has " + e.getValue() + " rows, expected " + rows);
            }
        }
        long unresolved = outcomes.getOrDefault(Outcome.UNRESOLVED, 0);
        step("every-commit-once", wrong.isEmpty(), wrong.size() + " mismatches " + head(wrong));
        step("every-outcome-known", unresolved == 0, unresolved + " unresolved");
        step(
                "readers-see-whole-commits",
                readerViolations.isEmpty() && readerChecks.get() > 0,
                readerChecks.get() + " reads, violations " + head(readerViolations));
        List<Long> gaps = snapshotGaps();
        step("snapshot-ids-contiguous", gaps.isEmpty(), "gaps " + gaps);

        Map<String, Object> result = new TreeMap<>();
        result.put("commits", commits.size());
        result.put("rowsPerCommit", rows);
        result.put("outcomes", outcomes.toString());
        result.put("readerChecks", readerChecks.get());
        result.put("readerOutages", readerOutages.get());
        result.put("seconds", took.toSeconds());
        result.put("latestSnapshot", latest() == null ? null : latest().id());
        System.out.println("RESULT " + result);
        if (wrong.isEmpty() && unresolved == 0 && readerViolations.isEmpty() && gaps.isEmpty()) {
            System.out.println("FLOW PASS " + id.getDatabaseName());
        } else {
            throw new AssertionError("commit invariants violated");
        }
    }

    private void write(int writer, int count) {
        try {
            FileStoreTable table = (FileStoreTable) catalog.getTable(id);
            StreamWriteBuilder builder =
                    table.newStreamWriteBuilder().withCommitUser("stress-w" + writer);
            try (StreamTableWrite write = builder.newWrite();
                    StreamTableCommit commit = builder.newCommit()) {
                for (long n = 1; n <= count; n++) {
                    for (int r = 0; r < rows; r++) {
                        write.write(
                                GenericRow.of(
                                        writer,
                                        n,
                                        r,
                                        BinaryString.fromString(
                                                String.format(
                                                        Locale.ROOT,
                                                        "w%d-c%d-r%d",
                                                        writer,
                                                        n,
                                                        r))));
                    }
                    List<CommitMessage> messages = write.prepareCommit(false, n);
                    commits.put(key(writer, n), commitOnce(writer, n, commit, messages));
                    Thread.sleep(pauseMillis);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("writer " + writer + " stopped", e);
        }
    }

    private Commit commitOnce(
            int writer, long n, StreamTableCommit commit, List<CommitMessage> messages)
            throws InterruptedException {
        try {
            commit.commit(n, messages);
            return new Commit(writer, n, Outcome.ACKED, 1, null);
        } catch (Exception first) {
            String error = rootMessage(first);
            for (int attempt = 2; attempt <= MAX_ATTEMPTS; attempt++) {
                Thread.sleep(Math.min(10_000, 500L * attempt));
                try {
                    Map<Long, List<CommitMessage>> retry = new HashMap<>();
                    retry.put(n, messages);
                    int committed = commit.filterAndCommit(retry);
                    return new Commit(
                            writer,
                            n,
                            committed == 0
                                    ? Outcome.FAILED_THEN_FOUND_COMMITTED
                                    : Outcome.FAILED_THEN_RETRIED,
                            attempt,
                            error);
                } catch (Exception again) {
                    error = rootMessage(again);
                }
            }
            return new Commit(writer, n, Outcome.UNRESOLVED, MAX_ATTEMPTS, error);
        }
    }

    /**
     * Whole commits only: a snapshot's rows are a multiple of the rows per commit, and never fewer
     * than an earlier read saw. Reads alternate between the catalog's latest snapshot and the
     * highest {@code snapshot-N} file, which is how a streaming reader finds new snapshots. A
     * failed read counts as a violation unless the catalog itself was unavailable, as during a
     * fault.
     */
    private void readLoop() {
        long[] lastRows = new long[2];
        int source = 0;
        while (writing.get()) {
            try {
                FileStoreTable table = (FileStoreTable) catalog.getTable(id);
                Long n =
                        source == 0
                                ? table.latestSnapshot().map(Snapshot::id).orElse(null)
                                : table.snapshotManager().latestSnapshotIdFromFileSystem();
                if (n != null) {
                    long count =
                            count(
                                    table.copy(Map.of("scan.snapshot-id", String.valueOf(n)))
                                            .newReadBuilder());
                    readerChecks.incrementAndGet();
                    if (count % rows != 0 || count < lastRows[source]) {
                        readerViolations.add(
                                "snapshot "
                                        + n
                                        + (source == 0 ? " (catalog)" : " (files)")
                                        + " shows "
                                        + count
                                        + " rows");
                    }
                    lastRows[source] = Math.max(lastRows[source], count);
                }
                source = 1 - source;
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (catalogOutage(e)) {
                    readerOutages.incrementAndGet();
                } else {
                    readerViolations.add("read failed: " + rootMessage(e));
                }
                sleepQuietly();
            }
        }
    }

    /** The catalog unreachable or answering 5xx, as during a fault; nothing else excuses a read. */
    static boolean catalogOutage(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ServiceFailureException || t instanceof ServiceUnavailableException) {
                return true;
            }
            // How the Paimon REST client reports a connection it could not make or finish.
            if (t.getClass() == RESTException.class
                    && String.valueOf(t.getMessage())
                            .startsWith("Error occurred while processing")) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Integer> rowsPerCommit() throws Exception {
        Map<String, Integer> counts = new HashMap<>();
        ReadBuilder read = catalog.getTable(id).newReadBuilder();
        try (RecordReader<InternalRow> reader =
                read.newRead().createReader(read.newScan().plan())) {
            reader.forEachRemaining(
                    row -> counts.merge(key(row.getInt(0), row.getLong(1)), 1, Integer::sum));
        }
        return counts;
    }

    private static long count(ReadBuilder read) throws Exception {
        AtomicLong n = new AtomicLong();
        try (RecordReader<InternalRow> reader =
                read.newRead().createReader(read.newScan().plan())) {
            reader.forEachRemaining(row -> n.incrementAndGet());
        }
        return n.get();
    }

    private List<Long> snapshotGaps() throws Exception {
        FileStoreTable table = (FileStoreTable) catalog.getTable(id);
        Long earliest = table.snapshotManager().earliestSnapshotId();
        Snapshot latest = latest();
        List<Long> gaps = new ArrayList<>();
        if (earliest == null || latest == null) {
            return gaps;
        }
        for (long s = earliest; s <= latest.id(); s++) {
            if (!table.snapshotManager().snapshotExists(s)) {
                gaps.add(s);
            }
        }
        return gaps;
    }

    private Snapshot latest() {
        try {
            return ((FileStoreTable) catalog.getTable(id)).latestSnapshot().orElse(null);
        } catch (Catalog.TableNotExistException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void step(String name, boolean ok, String detail) {
        System.out.println("STEP " + name + (ok ? " OK " : " FAIL ") + detail);
    }

    private static String key(int writer, long number) {
        return writer + "/" + number;
    }

    private static String head(List<String> items) {
        return items.size() <= 5 ? items.toString() : items.subList(0, 5) + "...";
    }

    static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
