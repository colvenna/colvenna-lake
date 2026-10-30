package com.colvenna.lake.operator.lake;

import com.colvenna.lake.operator.governance.GovernancePolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Table options a declaration may add: retention (snapshots, changelog, consumers), file layout,
 * file indexes, write buffering, and the Iceberg metadata a published table needs (LLD "开放层").
 * Everything else could change the verified table semantics (merge engine, sequence order, deletes,
 * changelog; docs/history/m1/table-semantics.md), so it is either managed or refused.
 */
public final class TableOptions {
    /**
     * Where a table's writers put Iceberg metadata for the open layer: beside the table, the only
     * place allowed (no Iceberg catalog in the write path).
     */
    public static final String ICEBERG_STORAGE = "metadata.iceberg.storage";

    public static final String ICEBERG_TABLE_LOCATION = "table-location";

    /** How many older Iceberg metadata files a writer keeps after each commit. */
    public static final String ICEBERG_VERSIONS = "metadata.iceberg.previous-versions-max";

    /**
     * Paimon keeps none and Polaris rereads the file it points at on every loadTable: a reader's
     * version must not vanish on the next commit.
     */
    static final String DEFAULT_ICEBERG_VERSIONS = "30";

    static final Set<String> ALLOWED =
            Set.of(
                    "snapshot.time-retained",
                    "snapshot.num-retained.min",
                    "snapshot.num-retained.max",
                    "changelog.time-retained",
                    "changelog.num-retained.min",
                    "changelog.num-retained.max",
                    "consumer.expiration-time",
                    "deletion-vectors.enabled",
                    "file.format",
                    "file.compression",
                    "target-file-size",
                    "write-buffer-size",
                    "write-buffer-spillable",
                    "num-sorted-run.compaction-trigger",
                    "num-sorted-run.stop-trigger",
                    "file-index.bloom-filter.columns",
                    "file-index.bitmap.columns",
                    ICEBERG_STORAGE,
                    ICEBERG_VERSIONS);

    /**
     * How long an idle consumer keeps a table's snapshots; a declaration may set its own. Paimon
     * refuses a consumer-id read of a table without one (HLD 9: abandoned consumers must not keep
     * history forever), and every layered table reads its upstream with a consumer id.
     */
    public static final String CONSUMER_EXPIRATION = "consumer.expiration-time";

    public static final String DEFAULT_CONSUMER_EXPIRATION = "1 d";

    private TableOptions() {}

    /** Why the declared options are refused, or null; {@code field} names them in the message. */
    public static String refusal(String field, Map<String, String> options) {
        if (options == null) {
            return null;
        }
        for (String key : options.keySet()) {
            if (!ALLOWED.contains(key) && !key.startsWith("file-index.")) {
                return field
                        + ": "
                        + key
                        + " is not allowed; allowed are "
                        + new TreeSet<>(ALLOWED)
                        + " and file-index.*";
            }
        }
        String storage = options.get(ICEBERG_STORAGE);
        if (storage != null && !ICEBERG_TABLE_LOCATION.equals(storage)) {
            return field
                    + ": "
                    + ICEBERG_STORAGE
                    + " can only be "
                    + ICEBERG_TABLE_LOCATION
                    + ": the writers write Iceberg metadata beside the table, never to a catalog";
        }
        String versions = options.get(ICEBERG_VERSIONS);
        if (versions != null && !versions.matches("[1-9][0-9]{0,2}")) {
            return field + ": " + ICEBERG_VERSIONS + " is a count from 1 to 999";
        }
        return null;
    }

    /** Whether a table's options have its writers write Iceberg metadata. */
    public static boolean writesIceberg(Map<String, String> options) {
        return options != null && ICEBERG_TABLE_LOCATION.equals(options.get(ICEBERG_STORAGE));
    }

    /**
     * The defaults a table writing Iceberg metadata gets unless declared: older metadata files
     * kept, and for a writer that compacts, a full compaction at the platform's interval (the one
     * personal data gets too): an Iceberg reader of a primary-key table sees only fully compacted
     * files.
     */
    public static Map<String, String> icebergDefaults(
            Map<String, String> declared, boolean writerCompacts) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!writesIceberg(declared)) {
            return out;
        }
        if (!declared.containsKey(ICEBERG_VERSIONS)) {
            out.put(ICEBERG_VERSIONS, DEFAULT_ICEBERG_VERSIONS);
        }
        if (writerCompacts) {
            out.put(
                    GovernancePolicy.OPTIMIZATION_INTERVAL,
                    GovernancePolicy.fullCompactionInterval());
        }
        return out;
    }
}
