package com.colvenna.lake.operator.source;

import com.colvenna.lake.operator.crd.LakeSourceSpec;
import com.colvenna.lake.operator.lake.LakeReader;
import com.colvenna.lake.operator.lake.TableOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypes;

/**
 * The two tables of a Kafka source (HLD 8.2): the events, partitioned by event date, and one row
 * per rejected record. Both are append tables written only by the job, with the same columns the
 * job builds its rows with ({@code com.colvenna.lake.jobs.kafka.EventSpec}).
 */
final class KafkaTables {
    private static final Pattern DECIMAL =
            Pattern.compile("DECIMAL\\((\\d{1,2}),\\s*(\\d{1,2})\\)");

    private KafkaTables() {}

    static String rejections(LakeSourceSpec spec) {
        return spec.target.table + "_rejections";
    }

    static boolean hourly(LakeSourceSpec spec) {
        return "hourly".equals(spec.target.partitioning);
    }

    static boolean keepUnknown(LakeSourceSpec spec) {
        return !"reject".equals(spec.events.unknownFields);
    }

    static List<DataField> eventFields(LakeSourceSpec spec) {
        List<DataField> out = new ArrayList<>();
        for (LakeSourceSpec.Field f : spec.events.fields) {
            out.add(new DataField(out.size(), f.name, type(f.type)));
        }
        out.add(new DataField(out.size(), "event_time_ms", DataTypes.BIGINT().notNull()));
        out.add(new DataField(out.size(), "dt", DataTypes.STRING().notNull()));
        if (hourly(spec)) {
            out.add(new DataField(out.size(), "hr", DataTypes.STRING().notNull()));
        }
        if (keepUnknown(spec)) {
            out.add(new DataField(out.size(), "extra_json", DataTypes.STRING()));
        }
        out.addAll(sourceFields(out.size()));
        return out;
    }

    static List<DataField> rejectionFields() {
        List<DataField> out = new ArrayList<>();
        out.add(new DataField(0, "dt", DataTypes.STRING().notNull()));
        out.add(new DataField(1, "reason", DataTypes.STRING().notNull()));
        out.add(new DataField(2, "field", DataTypes.STRING()));
        out.add(new DataField(3, "detected_at_ms", DataTypes.BIGINT().notNull()));
        out.addAll(sourceFields(4));
        return out;
    }

    private static List<DataField> sourceFields(int first) {
        return List.of(
                new DataField(first, "_topic", DataTypes.STRING().notNull()),
                new DataField(first + 1, "_partition", DataTypes.INT().notNull()),
                new DataField(first + 2, "_offset", DataTypes.BIGINT().notNull()));
    }

    static List<String> partitionKeys(LakeSourceSpec spec) {
        return hourly(spec) ? List.of("dt", "hr") : List.of("dt");
    }

    /**
     * Append tables written only by the job (HLD 8.2): no bucket constraint between writers, no
     * compaction in the writer, a done marker per idle partition for downstream batch tasks.
     * Partition expiry is not offered: the tables are the job's consumption record, which expiring
     * old partitions would lose.
     */
    static Map<String, String> options(LakeSourceSpec spec, String owner) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("bucket", "-1");
        options.put("write-only", "true");
        options.put(TableOptions.CONSUMER_EXPIRATION, TableOptions.DEFAULT_CONSUMER_EXPIRATION);
        options.put("partition.idle-time-to-done", "1 m");
        options.put("partition.mark-done-action", "success-file");
        // Both tables commit every checkpoint, rows or not, so a recreated job can tell where
        // they agree (com.colvenna.lake.jobs.kafka.StartingOffsets).
        options.put("commit.force-create-snapshot", "true");
        if (spec.target.tableOptions != null) {
            options.putAll(new TreeMap<>(spec.target.tableOptions));
        }
        // Append tables: every commit is visible to an Iceberg reader, no compaction needed.
        options.putAll(TableOptions.icebergDefaults(spec.target.tableOptions, false));
        options.put(com.colvenna.lake.operator.catalog.Owners.OPTION, owner);
        return options;
    }

    static DataType type(String declared) {
        switch (declared) {
            case "STRING":
                return DataTypes.STRING();
            case "BOOLEAN":
                return DataTypes.BOOLEAN();
            case "INT":
                return DataTypes.INT();
            case "BIGINT":
                return DataTypes.BIGINT();
            case "DOUBLE":
                return DataTypes.DOUBLE();
            default:
                Matcher m = DECIMAL.matcher(declared);
                if (!m.matches()) {
                    throw new IllegalArgumentException("Unsupported field type " + declared);
                }
                return DataTypes.DECIMAL(
                        Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
    }

    /** Why the declared fields cannot be applied to the existing table, or null. */
    record Plan(String refusal, List<DataField> added) {}

    /**
     * What it takes to bring the events table to the declaration: nothing, new optional fields
     * after the last declared one, or a refusal (a removed, renamed, retyped or required field:
     * existing rows could not follow).
     */
    static Plan evolve(List<DataField> existing, LakeSourceSpec spec) {
        List<DataField> want = eventFields(spec);
        List<String> have = new ArrayList<>();
        for (DataField f : existing) {
            have.add(f.name() + " " + f.type().copy(true));
        }
        int declared = spec.events.fields.size();
        int extra = want.size() - declared;
        int existingDeclared = existing.size() - extra;
        if (existingDeclared < 1 || existingDeclared > declared) {
            return new Plan("the table's columns do not match the declaration", List.of());
        }
        for (int i = 0; i < want.size(); i++) {
            boolean isNew = i >= existingDeclared && i < declared;
            if (isNew) {
                LakeSourceSpec.Field f = spec.events.fields.get(i);
                if (Boolean.TRUE.equals(f.required)) {
                    return new Plan(
                            "new field " + f.name + " is required; rows already written lack it",
                            List.of());
                }
                continue;
            }
            int at = i < existingDeclared ? i : i - (declared - existingDeclared);
            DataField w = want.get(i);
            if (!have.get(at).equals(w.name() + " " + w.type().copy(true))) {
                return new Plan(
                        "column "
                                + have.get(at)
                                + " cannot become "
                                + w.name()
                                + " "
                                + w.type()
                                + "; declare a new source to change it",
                        List.of());
            }
        }
        return new Plan(null, want.subList(existingDeclared, declared));
    }

    /** The table's columns, or empty when it does not exist yet. */
    static Optional<List<DataField>> columns(LakeReader lake, String db, String table)
            throws Exception {
        return lake.table(db, table).map(LakeReader.TableInfo::fields);
    }
}
