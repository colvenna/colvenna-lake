package com.colvenna.lake.operator.governance;

import com.colvenna.lake.operator.crd.Governance;
import com.colvenna.lake.operator.crd.LakeDashboardSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Column labels and what a dashboard sees of a table (LLD "列标签、脱敏与行过滤"). A label gives a column its
 * own level and categories, at most the table's; the other columns, all of them in a table without
 * labels, are labelled as the table. A dashboard is cleared for a level and categories: a column
 * above them is shown masked, as its label says (NULL by default).
 */
public final class Exposure {
    private Exposure() {}

    /** A clearance: up to a level, and these categories of personal data. */
    public record Clearance(int level, Set<String> categories) {
        /** A dashboard's: its declared access, else its governance, else L2 without categories. */
        public static Clearance of(LakeDashboardSpec spec) {
            if (spec.access != null) {
                return new Clearance(
                        Governance.levelOf(spec.access.level),
                        new TreeSet<>(spec.access.categories));
            }
            if (Governance.classified(spec.governance)) {
                return new Clearance(
                        Governance.level(spec.governance),
                        new TreeSet<>(spec.governance.categories));
            }
            return new Clearance(2, Set.of());
        }

        public boolean covers(int level, Collection<String> categories) {
            return level <= this.level && this.categories.containsAll(categories);
        }

        /** Whether the clearance covers the table's level and categories, all of its columns. */
        public boolean coversTable(Governance table) {
            return covers(Governance.level(table), table == null ? List.of() : table.categories);
        }

        @Override
        public String toString() {
            return "L" + level + (categories.isEmpty() ? "" : " " + categories);
        }
    }

    /** A column as shown: clear (mask null), or masked as its label says. */
    public record Shown(String column, String mask) {
        public boolean masked() {
            return mask != null;
        }
    }

    /**
     * What is wrong with a table's labels, or null: a label on a column the table does not have
     * (when its columns are known), above the table's level, in a category the table does not
     * declare, or below what its category requires.
     */
    public static String problem(
            Governance table,
            Map<String, Governance.ColumnLabel> labels,
            Collection<String> columns) {
        int level = Governance.level(table);
        for (Map.Entry<String, Governance.ColumnLabel> e : labels.entrySet()) {
            String column = e.getKey();
            Governance.ColumnLabel label = e.getValue();
            if (columns != null && !columns.contains(column)) {
                return "the label of " + column + " names no column of the table";
            }
            int own = label.level == null ? level : Governance.levelOf(label.level);
            if (own > level) {
                return column + " is labelled " + label.level + ", above its table's L" + level;
            }
            if (table != null && !table.categories.containsAll(label.categories)) {
                return column
                        + " is labelled "
                        + label.categories
                        + ", which its table does not declare";
            }
            if (label.categories.contains("sensitive") && own < 4) {
                return column + " is sensitive personal data, L4, not L" + own;
            }
            if (!label.categories.isEmpty() && own < 3) {
                return column + " is personal data, at least L3, not L" + own;
            }
        }
        return null;
    }

    /** Each column of a table as a dashboard with this clearance sees it, in the table's order. */
    public static List<Shown> of(
            Governance table,
            Map<String, Governance.ColumnLabel> labels,
            List<String> columns,
            Clearance clearance) {
        List<Shown> out = new ArrayList<>();
        int level = Governance.level(table);
        List<String> categories = table == null ? List.of() : table.categories;
        for (String column : columns) {
            Governance.ColumnLabel label = labels.get(column);
            if (label == null) {
                out.add(new Shown(column, clearance.covers(level, categories) ? null : "redact"));
            } else {
                int own = label.level == null ? level : Governance.levelOf(label.level);
                out.add(
                        new Shown(
                                column,
                                clearance.covers(own, label.categories) ? null : label.mask()));
            }
        }
        return out;
    }

    /** Whether any column is masked: the table is then read through a view that masks it. */
    public static boolean masks(List<Shown> shown) {
        return shown.stream().anyMatch(Shown::masked);
    }
}
