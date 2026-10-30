package com.colvenna.lake.operator.lake;

import com.colvenna.lake.operator.metrics.Metrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A table's maintenance backlog (HLD 11.2), as gauges: the snapshots it keeps, its data files, the
 * level-0 files a compaction has not merged yet, the small ones, its manifests' size, and what the
 * latest data commit added. Reading a table's manifests costs, so each table is counted at most
 * every {@link #EVERY}; between counts its last one is published.
 */
public final class Backlog {
    private static final Logger LOG = LoggerFactory.getLogger(Backlog.class);

    static final Duration EVERY = Duration.ofMinutes(5);

    private final LakeReader lake;
    private final Map<String, Counted> counted = new ConcurrentHashMap<>();

    private record Counted(Instant at, LakeReader.FileCount files) {}

    public Backlog(LakeReader lake) {
        this.lake = lake;
    }

    /** The table's backlog gauges, counting it again when its last count is old. */
    public List<Metrics.Sample> samples(String table, Map<String, String> labels, Instant now) {
        Counted last = counted.get(table);
        if (last == null || last.at().plus(EVERY).isBefore(now)) {
            String[] parts = table.split("\\.", 2);
            try {
                Optional<LakeReader.FileCount> files = lake.files(parts[0], parts[1]);
                if (files.isPresent()) {
                    last = new Counted(now, files.get());
                    counted.put(table, last);
                }
            } catch (Exception e) {
                LOG.debug("Counting the files of {} failed", table, e);
            }
        }
        List<Metrics.Sample> out = new ArrayList<>();
        if (last == null) {
            return out;
        }
        Map<String, String> l = new HashMap<>(labels);
        l.put("table", table);
        LakeReader.FileCount f = last.files();
        out.add(
                new Metrics.Sample(
                        "colvenna_table_snapshots", "snapshots the table keeps", l, f.snapshots()));
        out.add(
                new Metrics.Sample(
                        "colvenna_table_data_files",
                        "data files of its latest snapshot",
                        l,
                        f.files()));
        out.add(
                new Metrics.Sample(
                        "colvenna_table_level0_files",
                        "level-0 files no compaction has merged yet (primary-key tables)",
                        l,
                        f.level0()));
        out.add(
                new Metrics.Sample(
                        "colvenna_table_small_files",
                        "data files under a quarter of the target file size",
                        l,
                        f.small()));
        out.add(
                new Metrics.Sample(
                        "colvenna_table_data_bytes",
                        "bytes of its latest snapshot's data files",
                        l,
                        f.bytes()));
        out.add(
                new Metrics.Sample(
                        "colvenna_table_manifest_bytes",
                        "bytes of its latest snapshot's manifests",
                        l,
                        f.manifestBytes()));
        out.add(
                new Metrics.Sample(
                        "colvenna_table_commit_files",
                        "data files the latest data commit added",
                        l,
                        f.commitFiles()));
        out.add(
                new Metrics.Sample(
                        "colvenna_table_commit_file_bytes",
                        "the average size of the files the latest data commit added",
                        l,
                        f.commitFiles() == 0 ? 0 : (double) f.commitBytes() / f.commitFiles()));
        return out;
    }

    /** Forgets a table that is gone. */
    public void forget(String table) {
        counted.remove(table);
    }
}
