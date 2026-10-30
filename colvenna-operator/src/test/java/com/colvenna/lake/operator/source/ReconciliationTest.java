package com.colvenna.lake.operator.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReconciliationTest {
    private static String k(String... values) {
        return Keys.encode(List.of(values));
    }

    @Test
    void identicalTablesMatchWithoutASecondRead() {
        Map<String, Long> rows = Map.of(k("1"), 3L, k("2"), 1L);
        assertThat(Reconciliation.candidates(rows, rows)).isEmpty();
    }

    @Test
    void aTrailingTargetIsConsistentOnceItCatchesUp() {
        // The target trails: key 1 updated, key 2 deleted, key 4 inserted since it was read.
        Map<String, Long> t1 = Map.of(k("1"), 1L, k("2"), 1L, k("3"), 1L);
        Map<String, Long> s = Map.of(k("1"), 2L, k("3"), 1L, k("4"), 1L);
        List<String> candidates = Reconciliation.candidates(t1, s);
        assertThat(candidates).containsExactlyInAnyOrder(k("1"), k("2"), k("4"));
        // Later the target has those changes, and key 1 moved on again.
        Map<String, Long> t2 = Map.of(k("1"), 3L, k("3"), 1L, k("4"), 1L, k("5"), 1L);
        assertThat(Reconciliation.judge(t1, s, candidates, t2).matched()).isTrue();
    }

    @Test
    void missingStaleExtraAndAheadRowsAreNot() {
        Map<String, Long> t1 = Map.of(k("1"), 1L, k("9"), 1L, k("7"), 5L);
        Map<String, Long> s = Map.of(k("1"), 2L, k("2"), 1L, k("7"), 3L);
        List<String> candidates = Reconciliation.candidates(t1, s);
        // Key 1 stays stale, key 2 never arrives, key 9 stays although the source deleted it, and
        // key 7 was ahead of the source.
        Map<String, Long> t2 = Map.of(k("1"), 1L, k("9"), 1L, k("7"), 5L);
        Reconciliation.Result result = Reconciliation.judge(t1, s, candidates, t2);
        assertThat(result.matched()).isFalse();
        assertThat(result.unexplained()).isEqualTo(4);
        // Keys can be personal data: status messages carry them hashed.
        assertThat(result.sample()).allMatch(x -> x.startsWith("key#"));
        // Key 9's delete never reached the target: the one to repair.
        assertThat(result.deletedAtSource()).containsExactly(k("9"));
    }

    @Test
    void keysRoundTripWithoutSeparatorCollisions() {
        List<String> key = List.of("a|b", "", "3:x");
        assertThat(Keys.decode(Keys.encode(key))).isEqualTo(key);
        assertThat(Keys.encode(List.of("a|", "b"))).isNotEqualTo(Keys.encode(List.of("a", "|b")));
        assertThat(Keys.decode(Keys.encode(java.util.Arrays.asList("1", null))))
                .containsExactly("1", null);
    }
}
