package com.colvenna.lake.jobs.sql;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypeFamily;
import org.apache.paimon.types.DataTypeRoot;
import org.apache.paimon.types.RowType;

/**
 * A layered table's declared checks (LLD "数据质量"): a column not null, among allowed values, or
 * within a range. A row that fails one goes to the table's quarantine, with the check it failed,
 * instead of the table. Both are written by key, so a row's newer version that passes takes it out
 * of the quarantine, and one that fails takes it out of the table. The checks are deterministic: a
 * row's retraction is judged as its insertion was.
 */
final class Quality {
    /** The columns the quarantine adds to the table's. */
    static final String RULE = "colvenna_rule";

    static final String FLAGGED_AT = "colvenna_flagged_at";

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");

    /** One check. */
    static final class Rule {
        final String column;
        final String kind;
        final List<String> values;
        final String min;
        final String max;

        Rule(String column, String kind, List<String> values, String min, String max) {
            this.column = column;
            this.kind = kind;
            this.values = values;
            this.min = min;
            this.max = max;
        }

        String name() {
            return kind + "(" + column + ")";
        }
    }

    final String quarantine;
    final List<Rule> rules;

    private Quality(String quarantine, List<Rule> rules) {
        this.quarantine = quarantine;
        this.rules = rules;
    }

    /** The checks of a job's spec ({@code quality}), or null when it declares none. */
    static Quality parse(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String quarantine = node.path("quarantine").asText("");
        if (!SqlSpec.NAME.matcher(quarantine).matches()) {
            throw new IllegalArgumentException("quality.quarantine is not a plain table name");
        }
        List<Rule> rules = new ArrayList<>();
        for (JsonNode r : node.path("rules")) {
            String column = r.path("column").asText("");
            if (!SqlSpec.NAME.matcher(column).matches()) {
                throw new IllegalArgumentException("a quality rule names no plain column");
            }
            List<String> values = new ArrayList<>();
            r.path("values").forEach(v -> values.add(v.asText()));
            String kind = r.path("kind").asText();
            rules.add(
                    new Rule(
                            column,
                            kind,
                            Collections.unmodifiableList(values),
                            r.path("min").asText(null),
                            r.path("max").asText(null)));
        }
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("quality declares no rule");
        }
        return new Quality(quarantine, Collections.unmodifiableList(rules));
    }

    /** The condition a row of this type passes every check with. */
    String passes(RowType type) {
        List<String> all = new ArrayList<>();
        for (Rule r : rules) {
            all.add(condition(r, type));
        }
        return String.join(" AND ", all);
    }

    /** The first check a failing row fails, by name. */
    String failed(RowType type) {
        StringBuilder out = new StringBuilder("CASE");
        for (Rule r : rules) {
            out.append(" WHEN NOT (")
                    .append(condition(r, type))
                    .append(") THEN '")
                    .append(r.name())
                    .append("'");
        }
        return out.append(" END").toString();
    }

    /** The query's rows that pass, as the table takes them. */
    String passing(String query, RowType type) {
        return "SELECT * FROM (" + query + ") WHERE " + passes(type);
    }

    /**
     * The query's rows that fail, as the quarantine takes them: the check, and when it was made.
     */
    String failing(String query, RowType type) {
        return "SELECT *, "
                + failed(type)
                + " AS `"
                + RULE
                + "`, CURRENT_TIMESTAMP AS `"
                + FLAGGED_AT
                + "` FROM ("
                + query
                + ") WHERE NOT ("
                + passes(type)
                + ")";
    }

    private static String condition(Rule r, RowType type) {
        if (!type.getFieldNames().contains(r.column)) {
            throw new IllegalArgumentException(
                    "a quality rule names no column of the table: " + r.column);
        }
        String column = "`" + r.column + "`";
        DataType t = type.getField(r.column).type();
        switch (r.kind) {
            case "notNull":
                return column + " IS NOT NULL";
            case "allowed":
                List<String> quoted = new ArrayList<>();
                for (String v : r.values) {
                    quoted.add("'" + v.replace("'", "''") + "'");
                }
                return "("
                        + column
                        + " IS NULL OR CAST("
                        + column
                        + " AS STRING) IN ("
                        + String.join(", ", quoted)
                        + "))";
            case "range":
                List<String> bounds = new ArrayList<>();
                if (r.min != null) {
                    bounds.add(column + " >= " + literal(t, r.min, r.column));
                }
                if (r.max != null) {
                    bounds.add(column + " <= " + literal(t, r.max, r.column));
                }
                if (bounds.isEmpty()) {
                    throw new IllegalArgumentException("a range of " + r.column + " has no bound");
                }
                return "(" + column + " IS NULL OR (" + String.join(" AND ", bounds) + "))";
            default:
                throw new IllegalArgumentException("unknown quality rule " + r.kind);
        }
    }

    /** A bound as a literal of its column's type: numbers, dates and timestamps have ranges. */
    static String literal(DataType type, String value, String column) {
        String v = value.strip();
        if (type.is(DataTypeFamily.NUMERIC)) {
            return new BigDecimal(v).toPlainString();
        }
        if (type.getTypeRoot() == DataTypeRoot.DATE) {
            return "DATE '" + LocalDate.parse(v) + "'";
        }
        DataTypeRoot root = type.getTypeRoot();
        if (root == DataTypeRoot.TIMESTAMP_WITHOUT_TIME_ZONE
                || root == DataTypeRoot.TIMESTAMP_WITH_LOCAL_TIME_ZONE) {
            String timestamp =
                    "TIMESTAMP '" + TIME.format(LocalDateTime.parse(v.replace(' ', 'T'))) + "'";
            return root == DataTypeRoot.TIMESTAMP_WITHOUT_TIME_ZONE
                    ? timestamp
                    : "CAST(" + timestamp + " AS TIMESTAMP_LTZ(9))";
        }
        throw new IllegalArgumentException(
                "a range of " + column + " needs numbers, dates or timestamps, not " + type);
    }
}
