package com.colvenna.lake.operator.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether a source table may be ingested, and if not, why. For MySQL and PostgreSQL the rules are
 * the source contract of HLD 7.4, as verified (docs/history/m1/mysql-cdc.md,
 * docs/history/m1/postgres-cdc.md): a primary key, a positive BIGINT version the source refuses to
 * lower or repeat, keys that are immutable and never reused, and only column types the pipeline is
 * known to carry faithfully. MongoDB collections need only a key the target keeps exactly.
 */
public final class Admission {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern COLUMN = Pattern.compile("[A-Za-z0-9_]{1,64}");

    /**
     * MySQL types an EKS run carried with every value intact (docs/history/m3/declarative-cdc.md):
     * any other type is rejected until it is verified. TINYINT is a number here because the jobs
     * map TINYINT(1) with tinyint1-not-bool.
     */
    private static final Set<String> MYSQL_TYPES =
            Set.of(
                    "tinyint",
                    "smallint",
                    "mediumint",
                    "int",
                    "bigint",
                    "decimal",
                    "float",
                    "double",
                    "char",
                    "varchar",
                    "tinytext",
                    "text",
                    "mediumtext",
                    "longtext",
                    "date",
                    "datetime");

    /**
     * Paimon 2.0.0's CDC writer cannot carry binary values: Paimon maps BINARY(n) and VARBINARY(n)
     * to VARBINARY(n) and turns the CDC value into bytes with String.getBytes(UTF_8)
     * (TypeUtils.castFromCdcValueString). Debezium's default mode decodes the bytes into a String
     * first, so non-UTF-8 bytes are corrupted; base64 mode stores the base64 text, which overflows
     * the length and fails the job. Measured on EKS (docs/history/m3/declarative-cdc.md).
     */
    private static final Set<String> MYSQL_BINARY =
            Set.of("binary", "varbinary", "tinyblob", "blob", "mediumblob", "longblob", "bit");

    /** PostgreSQL types an EKS run carried with every value intact, as for MySQL. */
    private static final Set<String> POSTGRES_TYPES =
            Set.of(
                    "int2",
                    "int4",
                    "int8",
                    "numeric",
                    "float4",
                    "float8",
                    "varchar",
                    "bpchar",
                    "text",
                    "date",
                    "timestamp");

    private Admission() {}

    /** Admitted, or rejected with a reason code and a message for the table's status. */
    public record Verdict(boolean admitted, String reason, String message) {
        static Verdict ok() {
            return new Verdict(true, null, null);
        }

        static Verdict reject(String reason, String message) {
            return new Verdict(false, reason, message);
        }
    }

    public static Verdict check(String sourceType, SourceModel.Table table, String versionColumn) {
        if (!NAME.matcher(table.name()).matches()) {
            return Verdict.reject(
                    "UnsupportedName",
                    "table names must be lowercase letters, digits and underscores");
        }
        if (sourceType.equals("mongodb")) {
            return mongodb(table);
        }
        for (SourceModel.Column column : table.columns()) {
            if (!COLUMN.matcher(column.name()).matches()) {
                return Verdict.reject(
                        "UnsupportedName",
                        "column " + column.name() + " is not a plain identifier");
            }
        }
        // Both sources list only the columns the user holds a privilege on.
        if (table.columns().isEmpty()) {
            return Verdict.reject(
                    "NotReadable", "the CDC user sees none of its columns: grant it SELECT");
        }
        if (table.primaryKey().isEmpty()) {
            return Verdict.reject("NoPrimaryKey", "the table has no primary key");
        }
        if (table.primaryKey().stream().anyMatch(k -> k.equalsIgnoreCase(versionColumn))) {
            return Verdict.reject(
                    "VersionInPrimaryKey", "the version column cannot be part of the key");
        }
        Verdict types = sourceType.equals("mysql") ? mysqlTypes(table) : postgresTypes(table);
        if (!types.admitted()) {
            return types;
        }
        var version = table.column(versionColumn);
        if (version.isEmpty()) {
            return Verdict.reject("VersionColumnMissing", "no column " + versionColumn);
        }
        boolean bigint =
                sourceType.equals("mysql")
                        ? version.get().type().equals("bigint")
                                && !version.get().fullType().contains("unsigned")
                        : version.get().type().equals("int8");
        if (!bigint || version.get().nullable()) {
            return Verdict.reject(
                    "VersionColumnType",
                    versionColumn
                            + " must be a signed BIGINT NOT NULL, not "
                            + version.get().fullType());
        }
        if (table.checkClauses().stream().noneMatch(c -> positiveCheck(c, versionColumn))) {
            return Verdict.reject(
                    "VersionCheckMissing",
                    "no enforced CHECK (" + versionColumn + " > 0); an omitted version would be 0");
        }
        if (!table.triggersVisible()) {
            return Verdict.reject(
                    "SourceGuardsNotVisible",
                    "the source user cannot read trigger definitions; grant TRIGGER or set"
                            + " connection.inspectorSecret");
        }
        if (!hasGuard(table, "UPDATE", List.of(versionColumn))) {
            return Verdict.reject(
                    "VersionGuardMissing",
                    "no BEFORE UPDATE trigger refusing a " + versionColumn + " that does not rise");
        }
        if (sourceType.equals("mysql") && !hasGuard(table, "UPDATE", table.primaryKey())) {
            // PostgreSQL's full before image lets the job refuse key changes itself.
            return Verdict.reject(
                    "KeyGuardMissing", "no BEFORE UPDATE trigger refusing primary key changes");
        }
        if (!hasKeyReservation(table)) {
            return Verdict.reject(
                    "KeyReservationMissing",
                    "no BEFORE INSERT trigger reserving new keys, so a deleted key could return");
        }
        if (table.postgres() != null) {
            SourceModel.Postgres pg = table.postgres();
            if (pg.multiLevel()) {
                return Verdict.reject(
                        "UnsupportedPartitioning", "only one level of partitions is supported");
            }
            if (pg.partitioned() && pg.readTables().isEmpty()) {
                return Verdict.reject("UnsupportedPartitioning", "the table has no partitions");
            }
            List<String> notFull = new ArrayList<>();
            pg.replicaIdentity()
                    .forEach(
                            (name, identity) -> {
                                if (identity != 'f') {
                                    notFull.add(name);
                                }
                            });
            if (!notFull.isEmpty()) {
                return Verdict.reject(
                        "ReplicaIdentityNotFull",
                        "REPLICA IDENTITY FULL is required on " + String.join(", ", notFull));
            }
            if (!pg.notInPublication().isEmpty()) {
                return Verdict.reject(
                        "NotInPublication",
                        "not in the publication: " + String.join(", ", pg.notInPublication()));
            }
        }
        return Verdict.ok();
    }

    /**
     * A MongoDB collection (HLD 7.2): keyed by {@code _id}, every other field a STRING. The change
     * stream delivers a collection's changes in order, so no version column or source guards are
     * needed; the key must be an ObjectId or a string, whose text the target keeps exactly.
     */
    private static Verdict mongodb(SourceModel.Table table) {
        String id = table.column("_id").map(SourceModel.Column::type).orElse("");
        if (!id.equals("objectId") && !id.equals("string")) {
            return Verdict.reject(
                    "UnsupportedKeyType",
                    "_id is "
                            + id
                            + " in sampled documents; only ObjectId or string keys are kept"
                            + " exactly");
        }
        return Verdict.ok();
    }

    private static Verdict mysqlTypes(SourceModel.Table table) {
        for (SourceModel.Column column : table.columns()) {
            if (MYSQL_BINARY.contains(column.type())) {
                return Verdict.reject(
                        "UnsupportedBinaryColumn",
                        column.name()
                                + " is "
                                + column.fullType()
                                + ": Paimon 2.0.0 CDC cannot carry binary values intact;"
                                + " exclude the table or store the value as text");
            }
            if (!MYSQL_TYPES.contains(column.type())) {
                return Verdict.reject(
                        "UnsupportedType",
                        column.name() + " has unverified type " + column.fullType());
            }
        }
        return Verdict.ok();
    }

    private static Verdict postgresTypes(SourceModel.Table table) {
        for (String key : table.primaryKey()) {
            var column = table.column(key);
            // Debezium reports PostgreSQL varchar and char without a length, so Paimon 2.0.0 CDC
            // evolves such a column to STRING, and a key column cannot change type.
            if (column.isPresent()
                    && (column.get().type().equals("varchar")
                            || column.get().type().equals("bpchar"))) {
                return Verdict.reject(
                        "UnsupportedKeyType",
                        key
                                + " is "
                                + column.get().fullType()
                                + ": a varchar or char key would change type to STRING on the first"
                                + " change; use text or an integer key");
            }
        }
        for (SourceModel.Column column : table.columns()) {
            if (column.type().equals("bytea")) {
                return Verdict.reject(
                        "UnsupportedBinaryColumn",
                        column.name()
                                + " is bytea: Paimon 2.0.0 CDC cannot carry binary values intact"
                                + "; exclude the table or store the value as text");
            }
            if (!POSTGRES_TYPES.contains(column.type())) {
                return Verdict.reject(
                        "UnsupportedType",
                        column.name() + " has unverified type " + column.fullType());
            }
        }
        return Verdict.ok();
    }

    /**
     * An enforced CHECK that the column is positive: {@code version_no > 0} or {@code 0 <
     * version_no}.
     */
    static boolean positiveCheck(String clause, String column) {
        String c = clause.toLowerCase(Locale.ROOT).replaceAll("[`\"]", "");
        String v = Pattern.quote(column.toLowerCase(Locale.ROOT));
        return Pattern.compile("(^|[^a-z0-9_])" + v + "\\s*>\\s*0(?![0-9.])").matcher(c).find()
                || Pattern.compile("(?<![0-9.])0\\s*<\\s*" + v + "(?![a-z0-9_])").matcher(c).find();
    }

    /**
     * A BEFORE trigger on the event whose body raises an error and compares NEW and OLD of every
     * given column. A heuristic over the trigger text: it cannot prove the comparison is the right
     * one, which the job's guard still checks on every change.
     */
    static boolean hasGuard(SourceModel.Table table, String event, List<String> columns) {
        for (SourceModel.Trigger trigger : table.triggers()) {
            if (!trigger.timing().equals("BEFORE") || !trigger.event().equals(event)) {
                continue;
            }
            String body = trigger.body().toLowerCase(Locale.ROOT).replaceAll("[`\"]", "");
            boolean raises =
                    Pattern.compile("\\bsignal\\s+sqlstate\\b|\\braise\\s+exception\\b")
                            .matcher(body)
                            .find();
            boolean all =
                    columns.stream()
                            .map(c -> Pattern.quote(c.toLowerCase(Locale.ROOT)))
                            .allMatch(
                                    c ->
                                            Pattern.compile("\\bnew\\s*\\.\\s*" + c + "\\b")
                                                            .matcher(body)
                                                            .find()
                                                    && Pattern.compile(
                                                                    "\\bold\\s*\\.\\s*" + c + "\\b")
                                                            .matcher(body)
                                                            .find());
            if (raises && all) {
                return true;
            }
        }
        return false;
    }

    static boolean hasKeyReservation(SourceModel.Table table) {
        return table.triggers().stream()
                .anyMatch(
                        t ->
                                t.timing().equals("BEFORE")
                                        && t.event().equals("INSERT")
                                        && t.body()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("insert into"));
    }
}
