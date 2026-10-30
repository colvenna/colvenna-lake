package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Size;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a declaration says about its tables' data (HLD 12.5): who owns it, its level, its
 * categories, and for personal data the columns that identify the data subject.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Governance {
    @Pattern("^[A-Za-z0-9][A-Za-z0-9._@-]{0,127}$")
    @JsonPropertyDescription("Who is responsible for the data: a team or a person.")
    public String owner;

    @Pattern("^L[1-4]$")
    @JsonPropertyDescription(
            "L1 (public candidate), L2 (internal), L3 (restricted) or L4 (highly sensitive).")
    public String level;

    @Size(max = 2)
    @JsonPropertyDescription(
            "personal (personal data, at least L3) and sensitive (sensitive personal data, L4)."
                    + " Either needs subject keys and bounded retention.")
    public List<@Pattern("^(personal|sensitive)$") String> categories = new ArrayList<>();

    @JsonPropertyDescription(
            "LakeSource: for each source table with personal data, the columns that identify the"
                    + " data subject.")
    public Map<String, List<@Pattern("^[A-Za-z0-9_$]{1,64}$") String>> subjectKeys =
            new LinkedHashMap<>();

    @Size(max = 4)
    @JsonPropertyDescription(
            "LakeTable: the target's columns that identify the data subject, for personal data.")
    public List<@Pattern("^[a-z][a-z0-9_]{0,62}$") String> subjectKey = new ArrayList<>();

    @Size(max = 256)
    @JsonPropertyDescription(
            "Column labels (HLD 12.5): a column's level and categories, and how a dashboard not"
                    + " cleared for them sees it. A LakeTable keys them by its target's column, a"
                    + " LakeSource by <ODS table>.<column>. Once a table has a label, each of its"
                    + " other columns is labelled as the table.")
    public Map<String, ColumnLabel> columns = new LinkedHashMap<>();

    @Pattern("^[1-9][0-9]{0,3}(m|h|d)$")
    @JsonPropertyDescription(
            "How long the tables keep history: old snapshots, changelog and an idle reader's"
                    + " position (30m, 24h, 7d). Required; at most 24h for personal data.")
    public String retention;

    /** A column's classification, and what a reader below it sees instead of the value. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ColumnLabel {
        @Pattern("^L[1-4]$")
        @JsonPropertyDescription("The column's level; the table's when not declared.")
        public String level;

        @Size(max = 2)
        @JsonPropertyDescription("personal or sensitive, among the table's categories.")
        public List<@Pattern("^(personal|sensitive)$") String> categories = new ArrayList<>();

        @Pattern("^(redact|mask)$")
        @JsonPropertyDescription(
                "What a dashboard not cleared for the column sees: redact (NULL, the default) or"
                        + " mask (letters and digits replaced).")
        public String mask;

        /** The mask, {@code redact} when none is declared. */
        public String mask() {
            return mask == null ? "redact" : mask;
        }
    }

    /** A LakeTable's labels of its target, by column (its keys are columns). */
    public Map<String, ColumnLabel> targetLabels() {
        return labelsOf(null);
    }

    /**
     * The labels of one table, by column: a LakeSource's for one of its ODS tables (keys {@code
     * <table>.<column>}), or with {@code sourceTable} null a LakeTable's ({@link #targetLabels}).
     */
    public Map<String, ColumnLabel> labelsOf(String sourceTable) {
        Map<String, ColumnLabel> out = new LinkedHashMap<>();
        if (columns == null) {
            return out;
        }
        columns.forEach(
                (key, label) -> {
                    int dot = key.indexOf('.');
                    if (sourceTable == null && dot < 0) {
                        out.put(key, label);
                    } else if (sourceTable != null
                            && dot > 0
                            && key.substring(0, dot).equals(sourceTable)) {
                        out.put(key.substring(dot + 1), label);
                    }
                });
        return out;
    }

    /** A level, L1 to L4, as a number. */
    public static int levelOf(String level) {
        return level.charAt(1) - '0';
    }

    /** Whether a declaration classifies its data: it names an owner and a level. */
    public static boolean classified(Governance g) {
        return g != null && g.owner != null && g.level != null;
    }

    /** Whether data counts as personal: declared so, or not classified (temporary L4). */
    public static boolean treatedAsPersonal(Governance g) {
        return !classified(g) || g.personal();
    }

    /** Whether the data is personal (or sensitive personal) data. */
    public boolean personal() {
        return categories != null && !categories.isEmpty();
    }

    /** The level's number, 4 (temporary L4) when undeclared. */
    public static int level(Governance g) {
        return g == null || g.level == null ? 4 : g.level.charAt(1) - '0';
    }
}
