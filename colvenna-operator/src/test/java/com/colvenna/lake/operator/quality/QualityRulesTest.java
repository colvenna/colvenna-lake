package com.colvenna.lake.operator.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.crd.LakeQualitySpec;
import java.util.List;
import java.util.Map;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataTypes;
import org.junit.jupiter.api.Test;

/** Rules checked against a table's columns, and their version. */
class QualityRulesTest {
    private static final List<DataField> FIELDS =
            List.of(
                    new DataField(0, "event_id", DataTypes.STRING().notNull()),
                    new DataField(1, "user_id", DataTypes.BIGINT()),
                    new DataField(2, "tags", DataTypes.ARRAY(DataTypes.STRING())),
                    new DataField(3, "event_time_ms", DataTypes.BIGINT().notNull()),
                    new DataField(4, "seen_at", DataTypes.TIMESTAMP(3)),
                    new DataField(5, "dt", DataTypes.STRING().notNull()),
                    new DataField(6, "hr", DataTypes.STRING().notNull()));

    static LakeQualitySpec.Rule notNull(String name, String column, String ratio) {
        LakeQualitySpec.Rule r = new LakeQualitySpec.Rule();
        r.name = name;
        r.notNull = new LakeQualitySpec.NotNull();
        r.notNull.column = column;
        r.notNull.minRatio = ratio;
        return r;
    }

    static LakeQualitySpec.Rule unique(String name, String... columns) {
        LakeQualitySpec.Rule r = new LakeQualitySpec.Rule();
        r.name = name;
        r.unique = new LakeQualitySpec.Unique();
        r.unique.columns = List.of(columns);
        return r;
    }

    static LakeQualitySpec.Rule rows(String name, Long min, Long max) {
        LakeQualitySpec.Rule r = new LakeQualitySpec.Rule();
        r.name = name;
        r.rowCount = new LakeQualitySpec.RowCount();
        r.rowCount.min = min;
        r.rowCount.max = max;
        return r;
    }

    static LakeQualitySpec.Rule fresh(String name, String column, String maxAge) {
        LakeQualitySpec.Rule r = new LakeQualitySpec.Rule();
        r.name = name;
        r.freshness = new LakeQualitySpec.Freshness();
        r.freshness.column = column;
        r.freshness.maxAge = maxAge;
        return r;
    }

    @Test
    void validRulesHaveNoProblem() {
        assertThat(
                        QualityRules.problem(
                                List.of(
                                        notNull("user-present", "user_id", "0.999"),
                                        unique("event-unique", "event_id"),
                                        rows("volume", 1L, null),
                                        fresh("fresh", "event_time_ms", "2h"),
                                        fresh("seen", "seen_at", "1d")),
                                FIELDS))
                .isNull();
    }

    @Test
    void namesWhatIsWrong() {
        assertThat(QualityRules.problem(List.of(notNull("a", "nope", null)), FIELDS))
                .contains("a")
                .contains("nope");
        assertThat(QualityRules.problem(List.of(unique("a", "tags")), FIELDS))
                .contains("tags")
                .contains("ARRAY");
        assertThat(QualityRules.problem(List.of(fresh("a", "event_id", "1h")), FIELDS))
                .contains("event_id");
        assertThat(QualityRules.problem(List.of(rows("a", 1L, null), rows("a", null, 5L)), FIELDS))
                .contains("twice");
        assertThat(QualityRules.problem(List.of(rows("a", 5L, 1L)), FIELDS)).contains("min");
        assertThat(QualityRules.problem(List.of(notNull("a", "user_id", "1.5")), FIELDS))
                .contains("minRatio");
    }

    @Test
    void theVersionIgnoresOrderAndSpelling() {
        String v =
                QualityRules.version(
                        List.of(
                                notNull("user-present", "user_id", null),
                                fresh("fresh", "event_time_ms", "2h")));
        assertThat(v).hasSize(12).matches("[0-9a-f]{12}");
        // Rules in another order, the default ratio spelt out, the same age in minutes.
        assertThat(
                        QualityRules.version(
                                List.of(
                                        fresh("fresh", "event_time_ms", "120m"),
                                        notNull("user-present", "user_id", "1.000"))))
                .isEqualTo(v);
        assertThat(
                        QualityRules.version(
                                List.of(
                                        notNull("user-present", "user_id", "0.99"),
                                        fresh("fresh", "event_time_ms", "2h"))))
                .isNotEqualTo(v);
    }

    @Test
    void canonicalFormIsWhatTheJobRuns() {
        List<Map<String, Object>> c =
                QualityRules.canonical(
                        List.of(
                                rows("volume", 1L, null),
                                notNull("user-present", "user_id", "0.9990"),
                                unique("event-unique", "event_id", "user_id")));
        assertThat(c)
                .containsExactly(
                        Map.of(
                                "name",
                                "event-unique",
                                "kind",
                                "unique",
                                "columns",
                                List.of("event_id", "user_id")),
                        Map.of(
                                "name",
                                "user-present",
                                "kind",
                                "notNull",
                                "column",
                                "user_id",
                                "minRatio",
                                "0.999"),
                        Map.of("name", "volume", "kind", "rowCount", "min", 1L));
    }

    @Test
    void partitionsMustBeTheTablesKeys() {
        List<String> keys = List.of("dt", "hr");
        assertThat(QualityRules.partitionsProblem(List.of("dt=2026-10-16"), keys)).isNull();
        assertThat(QualityRules.partitionsProblem(List.of("dt=2026-10-16,hr=03"), keys)).isNull();
        assertThat(QualityRules.partitionsProblem(List.of(), List.of())).isNull();
        assertThat(QualityRules.partitionsProblem(List.of("user_id=1"), keys)).contains("user_id");
        assertThat(QualityRules.partitionsProblem(List.of("dt=2026-10-16"), List.of()))
                .contains("not partitioned");
        assertThat(QualityRules.partitionsProblem(List.of("dt=a,dt=b"), keys)).contains("dt");
    }
}
