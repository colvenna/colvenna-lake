package com.colvenna.lake.operator.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The gate from check results: which checks still stand, and what they leave it at. */
class GateTest {
    private static final Instant T0 = Instant.parse("2026-10-16T00:00:00Z");

    private static Gate.Check check(
            String uid, long snapshot, int minute, List<String> partitions, String... failed) {
        Instant at = T0.plus(Duration.ofMinutes(minute));
        return new Gate.Check("task-" + uid, uid, snapshot, at, at, partitions, List.of(failed));
    }

    private static Gate.Waiver waiver(String uid, String... rules) {
        return new Gate.Waiver("waive-" + uid, "w-" + uid, uid, List.of(rules), "alice", "ok");
    }

    @Test
    void closedWithoutAnyCheck() {
        Gate.Verdict v = Gate.judge(null, List.of(), List.of(), null, T0);
        assertThat(v.open()).isFalse();
        assertThat(v.cause()).isEqualTo("NoCheck");
    }

    @Test
    void closedWhenNotReadyWhateverTheChecks() {
        Gate.Verdict v =
                Gate.judge(
                        "rules invalid", List.of(check("a", 5, 1, List.of())), List.of(), null, T0);
        assertThat(v.open()).isFalse();
        assertThat(v.cause()).isEqualTo("NotReady");
        assertThat(v.message()).contains("rules invalid");
    }

    @Test
    void openOnceTheNewestWholeTableCheckPassed() {
        Gate.Verdict v =
                Gate.judge(
                        null,
                        List.of(check("a", 5, 1, List.of(), "fresh"), check("b", 7, 2, List.of())),
                        List.of(),
                        null,
                        T0.plus(Duration.ofMinutes(3)));
        assertThat(v.open()).isTrue();
        assertThat(v.cause()).isEqualTo("Passed");
        assertThat(v.live()).extracting(Gate.Check::uid).containsExactly("b");
        assertThat(v.basis().uid()).isEqualTo("b");
        assertThat(v.message()).contains("snapshot 7").contains("; data after it not checked yet");
        // Checks of some partitions pass the table: the rest has not been looked at.
        v =
                Gate.judge(
                        null,
                        List.of(check("c", 8, 3, List.of("dt=2026-10-16"))),
                        List.of(),
                        null,
                        T0.plus(Duration.ofMinutes(3)));
        assertThat(v.open()).isTrue();
        assertThat(v.message()).contains("other partitions, and data after it, not checked yet");
    }

    @Test
    void aFailedCheckOfOnePartitionHoldsUntilACheckCoveringItPasses() {
        Gate.Check old = check("a", 5, 1, List.of("dt=2026-10-15"), "user-present");
        Gate.Check other = check("b", 6, 2, List.of("dt=2026-10-16"));
        Gate.Verdict held = Gate.judge(null, List.of(old, other), List.of(), null, T0);
        assertThat(held.open()).isFalse();
        assertThat(held.cause()).isEqualTo("Failed");
        assertThat(held.message()).contains("task-a").contains("user-present");
        // The decision rests on the failed check, not the newer one that passed.
        assertThat(held.basis().uid()).isEqualTo("a");
        // A newer check of the same partition replaces it.
        Gate.Check again = check("c", 8, 3, List.of("dt=2026-10-15"));
        assertThat(Gate.judge(null, List.of(old, other, again), List.of(), null, T0).open())
                .isTrue();
        // So does a newer check of the whole table.
        Gate.Check whole = check("d", 9, 4, List.of());
        Gate.Verdict v = Gate.judge(null, List.of(old, other, whole), List.of(), null, T0);
        assertThat(v.open()).isTrue();
        assertThat(v.live()).extracting(Gate.Check::uid).containsExactly("d");
    }

    @Test
    void aCheckOfADateCoversItsHours() {
        Gate.Check hour = check("a", 5, 1, List.of("dt=2026-10-15,hr=03"), "volume");
        Gate.Check day = check("b", 6, 2, List.of("dt=2026-10-15"));
        assertThat(Gate.judge(null, List.of(hour, day), List.of(), null, T0).open()).isTrue();
        // An hour does not cover its day.
        Gate.Check dayFailed = check("c", 5, 1, List.of("dt=2026-10-15"), "volume");
        Gate.Check hourPassed = check("d", 6, 2, List.of("dt=2026-10-15,hr=03"));
        assertThat(Gate.judge(null, List.of(dayFailed, hourPassed), List.of(), null, T0).open())
                .isFalse();
    }

    @Test
    void anOlderSnapshotDoesNotReplaceANewerOne() {
        // A check repeated on an older snapshot (a rerun) is not the latest word on the data.
        Gate.Check newer = check("a", 9, 1, List.of(), "volume");
        Gate.Check rerun = check("b", 5, 2, List.of());
        Gate.Verdict v = Gate.judge(null, List.of(newer, rerun), List.of(), null, T0);
        assertThat(v.open()).isFalse();
        assertThat(v.live()).extracting(Gate.Check::uid).containsExactly("a");
    }

    @Test
    void aWaiverOfEveryFailedRuleOpensIt() {
        Gate.Check failed = check("a", 5, 1, List.of(), "fresh", "volume");
        Gate.Verdict partly =
                Gate.judge(null, List.of(failed), List.of(waiver("a", "fresh")), null, T0);
        assertThat(partly.open()).isFalse();
        assertThat(partly.message()).contains("volume").doesNotContain("fresh,");
        Gate.Verdict v =
                Gate.judge(
                        null, List.of(failed), List.of(waiver("a", "fresh", "volume")), null, T0);
        assertThat(v.open()).isTrue();
        assertThat(v.cause()).isEqualTo("Waived");
        assertThat(v.used()).extracting(Gate.Waiver::name).containsExactly("waive-a");
        // A waiver of another check does nothing for this one.
        assertThat(
                        Gate.judge(
                                        null,
                                        List.of(failed),
                                        List.of(waiver("z", "fresh", "volume")),
                                        null,
                                        T0)
                                .open())
                .isFalse();
    }

    @Test
    void expiresOnceTheNewestCheckIsOlderThanMaxAge() {
        Gate.Check passed = check("a", 5, 0, List.of());
        Duration day = Duration.ofHours(24);
        assertThat(Gate.judge(null, List.of(passed), List.of(), day, T0.plus(day)).open()).isTrue();
        Gate.Verdict v =
                Gate.judge(null, List.of(passed), List.of(), day, T0.plus(day).plusSeconds(1));
        assertThat(v.open()).isFalse();
        assertThat(v.cause()).isEqualTo("Expired");
    }

    @Test
    void failedComesBeforeExpired() {
        Gate.Check failed = check("a", 5, 0, List.of(), "volume");
        Gate.Verdict v =
                Gate.judge(
                        null,
                        List.of(failed),
                        List.of(),
                        Duration.ofMinutes(1),
                        T0.plus(Duration.ofHours(1)));
        assertThat(v.cause()).isEqualTo("Failed");
    }

    @Test
    void coversByPartitionValues() {
        assertThat(Gate.covers(List.of(), List.of("dt=a"))).isTrue();
        assertThat(Gate.covers(List.of("dt=a"), List.of())).isFalse();
        assertThat(Gate.covers(List.of("dt=a", "dt=b"), List.of("dt=b"))).isTrue();
        assertThat(Gate.covers(List.of("dt=a"), List.of("dt=a", "dt=b"))).isFalse();
        assertThat(Gate.covers(List.of("dt=a"), List.of("hr=1,dt=a"))).isTrue();
        assertThat(Gate.covers(List.of("dt=a,hr=1"), List.of("dt=a"))).isFalse();
    }
}
