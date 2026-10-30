package com.colvenna.lake.operator.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.crd.Governance;
import com.colvenna.lake.operator.lake.TableOptions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GovernancePolicyTest {
    private static Governance personal(String level) {
        Governance g = new Governance();
        g.owner = "shop-team";
        g.level = level;
        g.categories = List.of("personal");
        g.retention = "24h";
        return g;
    }

    private static Governance internal(String level) {
        Governance g = new Governance();
        g.owner = "analytics";
        g.level = level;
        g.retention = "7d";
        return g;
    }

    private static String reason(GovernancePolicy.Verdict v) {
        return v.reason();
    }

    @Test
    void personalDataNeedsAnOwnerALevelASubjectKeyAndBoundedRetention() {
        List<String> columns = List.of("id", "customer_id", "amount");
        assertThat(
                        reason(
                                GovernancePolicy.verdict(
                                        null, List.of(), Map.of(), columns, Map.of(), null)))
                .isEqualTo("Unclassified");
        Governance unbounded = personal("L3");
        unbounded.retention = null;
        assertThat(
                        reason(
                                GovernancePolicy.verdict(
                                        unbounded,
                                        List.of("customer_id"),
                                        Map.of(),
                                        columns,
                                        Map.of(),
                                        null)))
                .isEqualTo("RetentionMissing");
        unbounded.retention = "2d";
        assertThat(
                        GovernancePolicy.verdict(
                                        unbounded,
                                        List.of("customer_id"),
                                        Map.of(),
                                        columns,
                                        Map.of(),
                                        null)
                                .message())
                .contains("24 h at most, not 2d");
        // Kept for its count, history outlives any time bound.
        assertThat(
                        reason(
                                GovernancePolicy.verdict(
                                        personal("L3"),
                                        List.of("customer_id"),
                                        Map.of(),
                                        columns,
                                        Map.of("changelog.num-retained.min", "100"),
                                        null)))
                .isEqualTo("RetentionTooLong");
        assertThat(
                        reason(
                                GovernancePolicy.verdict(
                                        personal("L2"),
                                        List.of("customer_id"),
                                        Map.of(),
                                        columns,
                                        Map.of(),
                                        null)))
                .isEqualTo("LevelTooLow");
        Governance sensitive = personal("L3");
        sensitive.categories = List.of("personal", "sensitive");
        assertThat(
                        reason(
                                GovernancePolicy.verdict(
                                        sensitive,
                                        List.of("customer_id"),
                                        Map.of(),
                                        columns,
                                        Map.of(),
                                        null)))
                .isEqualTo("LevelTooLow");
        assertThat(
                        reason(
                                GovernancePolicy.verdict(
                                        personal("L3"),
                                        List.of(),
                                        Map.of(),
                                        columns,
                                        Map.of(),
                                        null)))
                .isEqualTo("SubjectKeyMissing");
        assertThat(
                        reason(
                                GovernancePolicy.verdict(
                                        personal("L3"),
                                        List.of("email"),
                                        Map.of(),
                                        columns,
                                        Map.of(),
                                        null)))
                .isEqualTo("SubjectKeyMissing");
        assertThat(
                        reason(
                                GovernancePolicy.verdict(
                                        personal("L3"),
                                        List.of("customer_id"),
                                        Map.of(),
                                        columns,
                                        Map.of("snapshot.time-retained", "2 d"),
                                        null)))
                .isEqualTo("RetentionTooLong");
        assertThat(
                        GovernancePolicy.verdict(
                                        personal("L3"),
                                        List.of("customer_id"),
                                        Map.of(),
                                        columns,
                                        Map.of("snapshot.time-retained", "12 h"),
                                        null)
                                .governed())
                .isTrue();
    }

    @Test
    void aLayeredTableIsNoLowerThanWhatItReads() {
        GovernancePolicy.Upstream ods =
                new GovernancePolicy.Upstream(3, true, List.of("ods.orders"), List.of());
        assertThat(
                        reason(
                                GovernancePolicy.verdict(
                                        internal("L2"), List.of(), Map.of(), null, Map.of(), ods)))
                .isEqualTo("BelowUpstream");
        Governance l3 = internal("L3");
        // Same level, but the personal category is lost.
        assertThat(reason(GovernancePolicy.verdict(l3, List.of(), Map.of(), null, Map.of(), ods)))
                .isEqualTo("BelowUpstream");
        assertThat(
                        GovernancePolicy.verdict(
                                        personal("L3"),
                                        List.of("customer_id"),
                                        Map.of(),
                                        null,
                                        Map.of(),
                                        ods)
                                .governed())
                .isTrue();
        // What no declaration classifies is not known to be below anything: it is refused.
        Governance undeclared = new Governance();
        undeclared.owner = "shop-team";
        GovernancePolicy.Upstream mixed =
                GovernancePolicy.Upstream.of(
                        List.of("ods.orders", "raw.clicks"),
                        Map.of("ods", personal("L3"), "raw", undeclared),
                        Map.of());
        assertThat(mixed.unclassified()).containsExactly("raw.clicks");
        assertThat(
                        reason(
                                GovernancePolicy.verdict(
                                        personal("L4"),
                                        List.of("customer_id"),
                                        Map.of(),
                                        null,
                                        Map.of(),
                                        mixed)))
                .isEqualTo("UpstreamUnclassified");
    }

    @Test
    void personalDataGetsBoundedRetentionAndFullCompactions() {
        // Unclassified data is treated as personal: bounded as personal data is, its defaults
        // (within the bound) kept, what is declared cut to it, full compactions every hour.
        assertThat(GovernancePolicy.options(null, Map.of(), true))
                .doesNotContainKeys("snapshot.time-retained", "consumer.expiration-time")
                .containsEntry("snapshot.num-retained.min", "1")
                .containsEntry("compaction.optimization-interval", "1 h");
        assertThat(GovernancePolicy.options(null, Map.of("snapshot.time-retained", "3 d"), true))
                .containsEntry("snapshot.time-retained", "24 h")
                .containsEntry("compaction.optimization-interval", "1 h");
        // The interval is fixed: no declaration may set it, more often or not.
        assertThat(
                        TableOptions.refusal(
                                "spec.tableOptions",
                                Map.of("compaction.optimization-interval", "30 min")))
                .contains("compaction.optimization-interval is not allowed");
        assertThat(
                        GovernancePolicy.options(
                                personal("L3"), Map.of("snapshot.time-retained", "6 h"), true))
                .containsEntry("snapshot.time-retained", "6 h")
                .containsEntry("consumer.expiration-time", "24 h")
                .containsEntry("snapshot.num-retained.min", "1")
                .containsEntry("compaction.optimization-interval", "1 h");
        assertThat(GovernancePolicy.options(personal("L3"), Map.of(), false))
                .doesNotContainKey("compaction.optimization-interval");
        // Every writer that compacts keeps a lookup changelog, beside which Paimon 2.0 refuses
        // full-compaction.delta-commits.
        assertThat(GovernancePolicy.options(personal("L3"), Map.of(), true))
                .doesNotContainKey("full-compaction.delta-commits");
        // Any declaration bounds its history; only personal data drops the count floor.
        assertThat(GovernancePolicy.options(internal("L2"), Map.of(), true))
                .containsEntry("snapshot.time-retained", "7 d")
                .containsEntry("consumer.expiration-time", "7 d")
                .doesNotContainKey("snapshot.num-retained.min")
                .doesNotContainKey("compaction.optimization-interval");
        Governance brief = internal("L2");
        brief.retention = "30m";
        assertThat(GovernancePolicy.options(brief, Map.of(), false))
                .containsEntry("snapshot.time-retained", "30 min");
    }

    @Test
    void aTablesLabelsAreJudgedWithIt() {
        Governance g = personal("L3");
        Governance.ColumnLabel above = new Governance.ColumnLabel();
        above.level = "L4";
        GovernancePolicy.Verdict v =
                GovernancePolicy.verdict(
                        g,
                        List.of("customer_id"),
                        Map.of("email", above),
                        List.of("id", "customer_id", "email"),
                        Map.of(),
                        null);
        assertThat(v.reason()).isEqualTo("LabelInvalid");
        assertThat(v.message()).isEqualTo("email is labelled L4, above its table's L3");
        // A label on a column the table lost (dropped, renamed) stops its publication.
        Governance.ColumnLabel fine = new Governance.ColumnLabel();
        fine.level = "L3";
        assertThat(
                        GovernancePolicy.verdict(
                                        g,
                                        List.of("customer_id"),
                                        Map.of("email", fine),
                                        List.of("id", "customer_id"),
                                        Map.of(),
                                        null)
                                .reason())
                .isEqualTo("LabelInvalid");
    }
}
