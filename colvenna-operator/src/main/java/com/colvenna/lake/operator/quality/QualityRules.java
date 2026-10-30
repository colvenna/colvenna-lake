package com.colvenna.lake.operator.quality;

import com.colvenna.lake.operator.Durations;
import com.colvenna.lake.operator.crd.LakeQualitySpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypeRoot;

/**
 * A LakeQuality's rules: whether they fit the table's columns, their canonical form (what the check
 * job runs) and their version, which a check's results carry (LLD "规则").
 */
public final class QualityRules {
    private static final ObjectMapper CANONICAL =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** Types a value of which is compared as a whole: not arrays, maps, rows or variants. */
    private static final Set<DataTypeRoot> NOT_COMPARABLE =
            Set.of(
                    DataTypeRoot.ARRAY,
                    DataTypeRoot.MAP,
                    DataTypeRoot.MULTISET,
                    DataTypeRoot.ROW,
                    DataTypeRoot.VARIANT,
                    DataTypeRoot.BLOB);

    private QualityRules() {}

    /** What is wrong with the rules on a table with these columns, or null. */
    public static String problem(List<LakeQualitySpec.Rule> rules, List<DataField> fields) {
        if (rules == null || rules.isEmpty()) {
            return "no rules";
        }
        Map<String, DataType> types = new HashMap<>();
        for (DataField f : fields) {
            types.put(f.name(), f.type());
        }
        Set<String> names = new HashSet<>();
        for (LakeQualitySpec.Rule r : rules) {
            if (!names.add(r.name)) {
                return "rule " + r.name + " is declared twice";
            }
            String p = problem(r, types);
            if (p != null) {
                return "rule " + r.name + ": " + p;
            }
        }
        return null;
    }

    private static String problem(LakeQualitySpec.Rule r, Map<String, DataType> types) {
        int kinds =
                (r.notNull == null ? 0 : 1)
                        + (r.unique == null ? 0 : 1)
                        + (r.rowCount == null ? 0 : 1)
                        + (r.freshness == null ? 0 : 1);
        if (kinds != 1) {
            return "a rule has exactly one of notNull, unique, rowCount and freshness";
        }
        if (r.notNull != null) {
            if (!types.containsKey(r.notNull.column)) {
                return "the table has no column " + r.notNull.column;
            }
            BigDecimal ratio = ratio(r.notNull);
            if (ratio == null
                    || ratio.compareTo(BigDecimal.ZERO) < 0
                    || ratio.compareTo(BigDecimal.ONE) > 0) {
                return "minRatio " + r.notNull.minRatio + " is not a decimal from 0 to 1";
            }
        }
        if (r.unique != null) {
            if (r.unique.columns == null || r.unique.columns.isEmpty()) {
                return "unique names no columns";
            }
            if (new HashSet<>(r.unique.columns).size() != r.unique.columns.size()) {
                return "unique names a column twice";
            }
            for (String c : r.unique.columns) {
                DataType t = types.get(c);
                if (t == null) {
                    return "the table has no column " + c;
                }
                if (NOT_COMPARABLE.contains(t.getTypeRoot())) {
                    return c + " is " + t + ", whose values are not compared for uniqueness";
                }
            }
        }
        if (r.rowCount != null) {
            if (r.rowCount.min == null && r.rowCount.max == null) {
                return "rowCount gives min, max or both";
            }
            if (r.rowCount.min != null
                    && r.rowCount.max != null
                    && r.rowCount.min > r.rowCount.max) {
                return "rowCount min is above its max";
            }
        }
        if (r.freshness != null) {
            DataType t = types.get(r.freshness.column);
            if (t == null) {
                return "the table has no column " + r.freshness.column;
            }
            DataTypeRoot root = t.getTypeRoot();
            if (root != DataTypeRoot.TIMESTAMP_WITHOUT_TIME_ZONE
                    && root != DataTypeRoot.TIMESTAMP_WITH_LOCAL_TIME_ZONE
                    && root != DataTypeRoot.BIGINT) {
                return r.freshness.column
                        + " is "
                        + t
                        + "; freshness reads a TIMESTAMP, TIMESTAMP_LTZ or BIGINT (epoch"
                        + " milliseconds) column";
            }
            if (r.freshness.maxAge == null || !r.freshness.maxAge.matches("[1-9][0-9]{0,4}[mhd]")) {
                return "freshness maxAge " + r.freshness.maxAge + " is not a duration";
            }
        }
        return null;
    }

    private static BigDecimal ratio(LakeQualitySpec.NotNull n) {
        try {
            return n.minRatio == null ? BigDecimal.ONE : new BigDecimal(n.minRatio);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The rules as the check job runs them, by name: defaults filled in, a ratio without trailing
     * zeros, an age in milliseconds. Two declarations meaning the same have the same form.
     */
    public static List<Map<String, Object>> canonical(List<LakeQualitySpec.Rule> rules) {
        List<LakeQualitySpec.Rule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparing(r -> r.name));
        List<Map<String, Object>> out = new ArrayList<>();
        for (LakeQualitySpec.Rule r : sorted) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.name);
            if (r.notNull != null) {
                m.put("kind", "notNull");
                m.put("column", r.notNull.column);
                m.put("minRatio", ratio(r.notNull).stripTrailingZeros().toPlainString());
            } else if (r.unique != null) {
                m.put("kind", "unique");
                m.put("columns", List.copyOf(r.unique.columns));
            } else if (r.rowCount != null) {
                m.put("kind", "rowCount");
                if (r.rowCount.min != null) {
                    m.put("min", r.rowCount.min);
                }
                if (r.rowCount.max != null) {
                    m.put("max", r.rowCount.max);
                }
            } else if (r.freshness != null) {
                m.put("kind", "freshness");
                m.put("column", r.freshness.column);
                m.put("maxAgeMillis", Durations.millis(r.freshness.maxAge, null));
            }
            out.add(m);
        }
        return out;
    }

    /** The first 12 hex digits of the SHA-256 of the canonical form's JSON, keys sorted. */
    public static String version(List<LakeQualitySpec.Rule> rules) {
        try {
            return sha256(CANONICAL.writeValueAsString(canonical(rules))).substring(0, 12);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The SHA-256 of a text's UTF-8 bytes, in hex. */
    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** What is wrong with a check's partitions on a table with these partition keys, or null. */
    public static String partitionsProblem(List<String> partitions, List<String> partitionKeys) {
        if (partitions == null || partitions.isEmpty()) {
            return null;
        }
        if (partitionKeys.isEmpty()) {
            return "the table is not partitioned; check it whole";
        }
        for (String p : partitions) {
            Set<String> seen = new HashSet<>();
            for (String part : p.split(",")) {
                String key = part.split("=", 2)[0];
                if (!partitionKeys.contains(key)) {
                    return p + ": " + key + " is not a partition key " + partitionKeys;
                }
                if (!seen.add(key)) {
                    return p + ": " + key + " is given twice";
                }
            }
        }
        return null;
    }
}
