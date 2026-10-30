package com.colvenna.lake.operator.quality;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** A quality store in memory: the tables' columns, results and tags a test sets up. */
public final class MemoryQualityStore implements QualityStore {
    public final Map<String, Columns> columns = new HashMap<>();
    public final List<Result> results = new ArrayList<>();
    public final List<Map<String, Object>> decisions = new ArrayList<>();
    public final Map<String, Tag> tags = new HashMap<>();
    public final Map<String, Long> kept = new HashMap<>();
    public final List<String> ensured = new ArrayList<>();
    public String conflict;
    public boolean failing;
    public long oldestSnapshot = 1;
    private final Supplier<Instant> clock;

    public MemoryQualityStore(Supplier<Instant> clock) {
        this.clock = clock;
    }

    private void maybeFail() {
        if (failing) {
            throw new IllegalStateException("store unavailable");
        }
    }

    @Override
    public Optional<Columns> columns(String database, String table) {
        maybeFail();
        return Optional.ofNullable(columns.get(database + "." + table));
    }

    @Override
    public String ensure(String namespace) {
        maybeFail();
        ensured.add(namespace);
        return conflict;
    }

    @Override
    public List<Result> results(String namespace, String table) {
        maybeFail();
        return results.stream().filter(r -> r.table().equals(table)).toList();
    }

    @Override
    public void decide(String namespace, List<Map<String, Object>> rows) {
        maybeFail();
        decisions.addAll(rows);
    }

    @Override
    public boolean snapshotExists(String database, String table, long snapshot) {
        maybeFail();
        return snapshot >= oldestSnapshot;
    }

    @Override
    public List<Tag> tags(String database, String table) {
        maybeFail();
        return tags.values().stream().filter(t -> t.name().startsWith(TAG_PREFIX)).toList();
    }

    @Override
    public void tag(String database, String table, String name, long snapshot, Duration kept) {
        maybeFail();
        tags.putIfAbsent(name, new Tag(name, snapshot, clock.get().plus(kept)));
        this.kept.put(name, kept.toHours());
    }

    @Override
    public void untag(String database, String table, String name) {
        maybeFail();
        tags.remove(name);
    }

    /** One check's results: each rule, passed unless named as failed. */
    public void check(
            String uid,
            String task,
            String table,
            long snapshot,
            Instant at,
            String partitions,
            String version,
            List<String> rules,
            String... failed) {
        for (String rule : rules) {
            results.add(
                    new Result(
                            uid,
                            task,
                            rule,
                            table,
                            snapshot,
                            at,
                            partitions,
                            version,
                            !List.of(failed).contains(rule),
                            at));
        }
    }
}
