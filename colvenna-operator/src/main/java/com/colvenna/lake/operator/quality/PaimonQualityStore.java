package com.colvenna.lake.operator.quality;

import com.colvenna.lake.operator.catalog.CatalogClient;
import com.colvenna.lake.operator.catalog.Owners;
import com.colvenna.lake.operator.lake.LakeReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The quality store in colvenna-catalog, read and written with the operator's own identity. */
public final class PaimonQualityStore implements QualityStore {
    private final CatalogClient catalog;
    private final LakeReader lake;

    public PaimonQualityStore(CatalogClient catalog, LakeReader lake) {
        this.catalog = catalog;
        this.lake = lake;
    }

    @Override
    public Optional<Columns> columns(String database, String table) throws Exception {
        Optional<LakeReader.TableInfo> info = lake.table(database, table);
        if (info.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                new Columns(
                        info.get().fields(),
                        lake.partitionKeys(database, table).orElse(List.of())));
    }

    @Override
    public String ensure(String namespace) throws Exception {
        String database = QualityStore.database(namespace);
        String owner = QualityStore.owner(namespace);
        catalog.ensureDatabase(database, Map.of(Owners.OPTION, owner));
        String found =
                catalog.databaseOptions(database).map(o -> o.get(Owners.OPTION)).orElse(null);
        if (!owner.equals(found)) {
            return "database " + database + " belongs to " + found + ", not " + owner;
        }
        Optional<List<String>> partitions = lake.partitionKeys(database, RESULTS);
        if (partitions.isPresent() && !partitions.get().equals(List.of("checked_table"))) {
            // Development: no upgrade between layouts (LLD "已知限制").
            return database
                    + ".results has partition keys "
                    + partitions.get()
                    + ", not [checked_table]: an older layout; drop it";
        }
        if (partitions.isEmpty()) {
            lake.createTable(
                    database,
                    RESULTS,
                    RESULT_FIELDS,
                    List.of("checked_table", "task_uid", "rule"),
                    List.of("checked_table"),
                    Map.of("bucket", "1", Owners.OPTION, owner));
        }
        if (lake.table(database, DECISIONS).isEmpty()) {
            lake.createTable(
                    database, DECISIONS, DECISION_FIELDS, List.of(), Map.of(Owners.OPTION, owner));
        }
        return null;
    }

    @Override
    public List<Result> results(String namespace, String table) throws Exception {
        String database = QualityStore.database(namespace);
        // Read again only once the results table has a new snapshot: it is read every round.
        long snapshot = lake.head(database, RESULTS).map(LakeReader.Head::snapshotId).orElse(0L);
        Cached cached = cache.get(database + "/" + table);
        if (cached != null && cached.snapshot() == snapshot) {
            return cached.results();
        }
        List<Result> out = new ArrayList<>();
        for (Map<String, Object> row :
                lake.readAll(database, RESULTS, Map.of("checked_table", table))) {
            out.add(
                    new Result(
                            (String) row.get("task_uid"),
                            (String) row.get("task"),
                            (String) row.get("rule"),
                            table,
                            ((Number) row.get("snapshot_id")).longValue(),
                            (Instant) row.get("as_of"),
                            Objects.requireNonNullElse((String) row.get("partitions"), ""),
                            (String) row.get("rule_version"),
                            Boolean.TRUE.equals(row.get("passed")),
                            (Instant) row.get("checked_at")));
        }
        cache.put(database + "/" + table, new Cached(snapshot, List.copyOf(out)));
        return out;
    }

    /** A table's results as of a snapshot of the results table. */
    private record Cached(long snapshot, List<Result> results) {}

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Override
    public void decide(String namespace, List<Map<String, Object>> decisions) throws Exception {
        lake.write(QualityStore.database(namespace), DECISIONS, decisions);
    }

    @Override
    public boolean snapshotExists(String database, String table, long snapshot) throws Exception {
        return lake.snapshotExists(database, table, snapshot);
    }

    @Override
    public List<Tag> tags(String database, String table) throws Exception {
        List<Tag> out = new ArrayList<>();
        for (LakeReader.TagInfo t : lake.tags(database, table, TAG_PREFIX)) {
            out.add(new Tag(t.name(), t.snapshot(), QualityStore.expires(t.created(), t.kept())));
        }
        return out;
    }

    @Override
    public void tag(String database, String table, String name, long snapshot, Duration kept)
            throws Exception {
        lake.createTag(database, table, name, snapshot, kept);
    }

    @Override
    public void untag(String database, String table, String name) throws Exception {
        lake.deleteTag(database, table, name);
    }
}
