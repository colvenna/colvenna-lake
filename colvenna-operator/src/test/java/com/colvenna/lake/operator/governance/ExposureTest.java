package com.colvenna.lake.operator.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.crd.Governance;
import com.colvenna.lake.operator.crd.LakeDashboardSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Column labels, and what a dashboard of some clearance sees of a labelled table. */
class ExposureTest {
    private static Governance table(String level, String... categories) {
        Governance g = new Governance();
        g.owner = "shop";
        g.level = level;
        g.categories = List.of(categories);
        g.retention = "24h";
        return g;
    }

    private static Governance.ColumnLabel label(String level, String mask, String... categories) {
        Governance.ColumnLabel l = new Governance.ColumnLabel();
        l.level = level;
        l.mask = mask;
        l.categories = List.of(categories);
        return l;
    }

    private static final List<String> COLUMNS = List.of("id", "email", "region", "amount");

    @Test
    void labelsStayWithinTheirTable() {
        Governance g = table("L3", "personal");
        Map<String, Governance.ColumnLabel> labels = new LinkedHashMap<>();
        labels.put("email", label("L3", "mask", "personal"));
        labels.put("id", label("L1", null));
        assertThat(Exposure.problem(g, labels, COLUMNS)).isNull();
        // Its columns are not known before the table exists: only the levels are judged.
        assertThat(Exposure.problem(g, labels, null)).isNull();
        assertThat(Exposure.problem(g, Map.of("phone", label("L3", null)), COLUMNS))
                .isEqualTo("the label of phone names no column of the table");
        assertThat(Exposure.problem(g, Map.of("email", label("L4", null)), COLUMNS))
                .isEqualTo("email is labelled L4, above its table's L3");
        assertThat(Exposure.problem(g, Map.of("email", label("L3", null, "sensitive")), COLUMNS))
                .contains("which its table does not declare");
        assertThat(Exposure.problem(g, Map.of("email", label("L2", null, "personal")), COLUMNS))
                .isEqualTo("email is personal data, at least L3, not L2");
        // A column's own level defaults to its table's.
        assertThat(Exposure.problem(g, Map.of("email", label(null, "mask", "personal")), COLUMNS))
                .isNull();
    }

    @Test
    void aColumnAboveTheClearanceIsMaskedAsItsLabelSays() {
        Governance g = table("L3", "personal");
        Map<String, Governance.ColumnLabel> labels = new LinkedHashMap<>();
        labels.put("email", label("L3", "mask", "personal"));
        labels.put("id", label("L1", null));
        labels.put("region", label("L2", "mask"));
        Exposure.Clearance internal = new Exposure.Clearance(2, Set.of());
        // amount has no label: labelled as its table, L3 personal, it is redacted.
        assertThat(Exposure.of(g, labels, COLUMNS, internal))
                .containsExactly(
                        new Exposure.Shown("id", null),
                        new Exposure.Shown("email", "mask"),
                        new Exposure.Shown("region", null),
                        new Exposure.Shown("amount", "redact"));
        // Cleared for personal data at L3, it sees every column.
        assertThat(
                        Exposure.masks(
                                Exposure.of(
                                        g,
                                        labels,
                                        COLUMNS,
                                        new Exposure.Clearance(3, Set.of("personal")))))
                .isFalse();
        // L3 without the category: the personal columns stay masked, the others are clear.
        assertThat(Exposure.of(g, labels, COLUMNS, new Exposure.Clearance(3, Set.of())))
                .extracting(Exposure.Shown::mask)
                .containsExactly(null, "mask", null, "redact");
    }

    @Test
    void aTableWithoutLabelsIsShownAsItsTableIsClassified() {
        // Above the clearance: every column redacted. Within it: all clear.
        assertThat(
                        Exposure.of(
                                table("L3", "personal"),
                                Map.of(),
                                COLUMNS,
                                new Exposure.Clearance(2, Set.of())))
                .allMatch(c -> "redact".equals(c.mask()));
        Exposure.Clearance cleared = new Exposure.Clearance(3, Set.of("personal"));
        assertThat(cleared.coversTable(table("L3", "personal"))).isTrue();
        assertThat(Exposure.masks(Exposure.of(table("L3", "personal"), Map.of(), COLUMNS, cleared)))
                .isFalse();
    }

    @Test
    void aDashboardsClearanceIsItsAccessElseItsGovernanceElseInternal() {
        LakeDashboardSpec spec = new LakeDashboardSpec();
        assertThat(Exposure.Clearance.of(spec)).isEqualTo(new Exposure.Clearance(2, Set.of()));
        spec.governance = table("L3", "personal");
        assertThat(Exposure.Clearance.of(spec))
                .isEqualTo(new Exposure.Clearance(3, Set.of("personal")));
        spec.access = new LakeDashboardSpec.Access();
        spec.access.level = "L1";
        assertThat(Exposure.Clearance.of(spec)).isEqualTo(new Exposure.Clearance(1, Set.of()));
    }
}
