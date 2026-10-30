package com.colvenna.lake.operator.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.crd.LakeSourceStatus.SourceTable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class EnrollmentTest {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final Admission.Verdict OK = new Admission.Verdict(true, null, null);
    private static final Admission.Verdict NO_KEY =
            new Admission.Verdict(false, "NoPrimaryKey", "the table has no primary key");

    private static SourceModel.Table table(String name, String... key) {
        return new SourceModel.Table(
                name, List.of(), List.of(key), List.of(), List.of(), true, null);
    }

    private static SourceTable status(String name, String state) {
        SourceTable t = new SourceTable();
        t.name = name;
        t.target = name;
        t.state = state;
        return t;
    }

    @Test
    void aReadyTableWaitingForARecheckStillWaitsNextRound() {
        SourceTable orders = status("orders", "Ready");
        Enrollment.recheck(orders, NOW);
        Map<String, SourceModel.Table> selected = new TreeMap<>();
        selected.put("orders", table("orders", "id"));
        Enrollment.Plan plan =
                Enrollment.plan(
                        List.of(orders),
                        selected,
                        Map.of("orders", OK),
                        Map.of(),
                        Map.of(),
                        null,
                        NOW);
        // Every status field survives the round: a lost mark would skip the reconciliation.
        assertThat(plan.tables())
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.recheckSince).isEqualTo(NOW.toString());
                            assertThat(
                                            Enrollment.rechecking(
                                                    t,
                                                    java.time.Duration.ofMinutes(2),
                                                    NOW.plusSeconds(121)))
                                    .isTrue();
                        });
    }

    @Test
    void admittedTablesJoinTheJobAndRejectedOnesDoNot() {
        Map<String, SourceModel.Table> selected = new TreeMap<>();
        selected.put("orders", table("orders", "id"));
        selected.put("no_key", table("no_key"));
        Enrollment.Plan plan =
                Enrollment.plan(
                        List.of(),
                        selected,
                        Map.of("orders", OK, "no_key", NO_KEY),
                        Map.of(),
                        Map.of(),
                        null,
                        NOW);
        assertThat(plan.jobTables()).containsExactly("orders");
        assertThat(plan.tables())
                .extracting(t -> t.state)
                .containsExactly("Rejected", "Snapshotting");
        assertThat(plan.transitions())
                .extracting(Enrollment.Transition::warning)
                .containsExactly(true, false);
    }

    @Test
    void aJobFailureTakesOnlyThatTableOutAndFailedIsSticky() {
        Map<String, SourceModel.Table> selected = new TreeMap<>();
        selected.put("orders", table("orders", "id"));
        selected.put("invoices", table("invoices", "id"));
        Enrollment.Plan plan =
                Enrollment.plan(
                        List.of(status("orders", "Ready"), status("invoices", "Ready")),
                        selected,
                        Map.of("orders", OK, "invoices", OK),
                        Map.of(),
                        Map.of("invoices", "Cannot convert field amount"),
                        null,
                        NOW);
        assertThat(plan.jobTables()).containsExactly("orders");
        SourceTable invoices = plan.tables().get(0);
        assertThat(invoices.state).isEqualTo("Failed");
        assertThat(invoices.reason).isEqualTo("JobFailed");
        // Admissible again later, it still stays out: its target missed changes meanwhile.
        Enrollment.Plan later =
                Enrollment.plan(
                        plan.tables(),
                        selected,
                        Map.of("orders", OK, "invoices", OK),
                        Map.of(),
                        Map.of(),
                        null,
                        NOW);
        assertThat(later.jobTables()).containsExactly("orders");
        assertThat(later.transitions()).isEmpty();
    }

    @Test
    void aChangedKeyFailsARunningTableAndRejectsANewOne() {
        Map<String, SourceModel.Table> selected = Map.of("orders", table("orders", "id", "tenant"));
        Enrollment.Plan running =
                Enrollment.plan(
                        List.of(status("orders", "Ready")),
                        selected,
                        Map.of("orders", OK),
                        Map.of("orders", new Enrollment.Target(List.of("id"), true)),
                        Map.of(),
                        null,
                        NOW);
        assertThat(running.tables().get(0).state).isEqualTo("Failed");
        assertThat(running.tables().get(0).reason).isEqualTo("PrimaryKeyChanged");
        Enrollment.Plan fresh =
                Enrollment.plan(
                        List.of(),
                        selected,
                        Map.of("orders", OK),
                        Map.of("orders", new Enrollment.Target(List.of("ID", "TENANT"), false)),
                        Map.of(),
                        null,
                        NOW);
        assertThat(fresh.tables().get(0).state).isEqualTo("Snapshotting");
    }

    @Test
    void aSourceChangeThatBreaksAdmissionFailsARunningTable() {
        Enrollment.Plan plan =
                Enrollment.plan(
                        List.of(status("orders", "Ready")),
                        Map.of("orders", table("orders", "id")),
                        Map.of("orders", NO_KEY),
                        Map.of(),
                        Map.of(),
                        null,
                        NOW);
        assertThat(plan.tables().get(0).state).isEqualTo("Failed");
        assertThat(plan.jobTables()).isEmpty();
    }

    @Test
    void unmatchedTablesLeaveTheStatus() {
        Enrollment.Plan plan =
                Enrollment.plan(
                        List.of(status("orders", "Ready"), status("old", "Ready")),
                        Map.of("orders", table("orders", "id")),
                        Map.of("orders", OK),
                        Map.of(),
                        Map.of(),
                        null,
                        NOW);
        assertThat(plan.tables()).extracting(t -> t.name).containsExactly("orders");
    }

    @Test
    void selectionUsesWholeNameRegexes() {
        assertThat(Enrollment.selected("orders", List.of(), List.of())).isTrue();
        assertThat(Enrollment.selected("orders", List.of("ord.*"), List.of())).isTrue();
        assertThat(Enrollment.selected("orders_archive", List.of("orders"), List.of())).isFalse();
        assertThat(Enrollment.selected("orders_tmp", List.of("orders.*"), List.of(".*_tmp")))
                .isFalse();
    }

    @Test
    void readyRecordsTheReconciliation() {
        SourceTable t = status("orders", "Snapshotting");
        var transition =
                Enrollment.ready(
                        t,
                        new Reconciliation.Result(5000, 5000, 0, List.of(), List.of()),
                        7,
                        true,
                        NOW);
        assertThat(transition).isPresent();
        assertThat(t.state).isEqualTo("Ready");
        assertThat(t.reconciledSnapshot).isEqualTo(7);
        assertThat(
                        Enrollment.ready(
                                t,
                                new Reconciliation.Result(5000, 5000, 0, List.of(), List.of()),
                                8,
                                true,
                                NOW))
                .isEmpty();
    }

    @Test
    void aTableJoinsTheJobOnlyIntoAnEmptyTarget() {
        Map<String, SourceModel.Table> selected = Map.of("orders", table("orders", "id"));
        Enrollment.Plan withData =
                Enrollment.plan(
                        List.of(),
                        selected,
                        Map.of("orders", OK),
                        Map.of("orders", new Enrollment.Target(List.of("id"), true)),
                        Map.of(),
                        null,
                        NOW);
        assertThat(withData.tables().get(0).state).isEqualTo("Rejected");
        assertThat(withData.tables().get(0).reason).isEqualTo("TargetNotEmpty");
        // Already in the job, the same target with data is the job's own: nothing changes.
        Enrollment.Plan running =
                Enrollment.plan(
                        List.of(status("orders", "Ready")),
                        selected,
                        Map.of("orders", OK),
                        Map.of("orders", new Enrollment.Target(List.of("id"), true)),
                        Map.of(),
                        null,
                        NOW);
        assertThat(running.tables().get(0).state).isEqualTo("Ready");
    }

    @Test
    void aClosedGateKeepsNewTablesOutButNotRunningOnes() {
        Map<String, SourceModel.Table> selected = new TreeMap<>();
        selected.put("orders", table("orders", "id"));
        selected.put("invoices", table("invoices", "id"));
        Admission.Verdict gate = new Admission.Verdict(false, "TargetNotIsolated", "readable");
        Enrollment.Plan plan =
                Enrollment.plan(
                        List.of(status("orders", "Ready")),
                        selected,
                        Map.of("orders", OK, "invoices", OK),
                        Map.of(),
                        Map.of(),
                        gate,
                        NOW);
        assertThat(plan.tables())
                .extracting(t -> t.name + "=" + t.state + "/" + t.reason)
                .containsExactly("invoices=Rejected/TargetNotIsolated", "orders=Ready/null");
    }

    @Test
    void theKeyAndPartitionsAreRecordedOnAdmission() {
        SourceModel.Table payments =
                new SourceModel.Table(
                        "payments",
                        List.of(),
                        List.of("id", "region"),
                        List.of(),
                        List.of(),
                        true,
                        new SourceModel.Postgres(
                                true,
                                false,
                                List.of("payments_east", "payments_west"),
                                Map.of(),
                                List.of()));
        Enrollment.Plan plan =
                Enrollment.plan(
                        List.of(),
                        Map.of("payments", payments),
                        Map.of("payments", OK),
                        Map.of(),
                        Map.of(),
                        null,
                        NOW);
        assertThat(plan.tables().get(0).primaryKey).containsExactly("id", "region");
        assertThat(plan.tables().get(0).readTables)
                .containsExactly("payments_east", "payments_west");
    }

    @Test
    void aCollectionReplacedAtTheSourceFailsItsTable() {
        Map<String, SourceModel.Table> selected = new TreeMap<>();
        selected.put(
                "orders",
                new SourceModel.Table(
                        "orders",
                        List.of(),
                        List.of("_id"),
                        List.of(),
                        List.of(),
                        true,
                        null,
                        "u-1"));
        Enrollment.Plan joined =
                Enrollment.plan(
                        List.of(), selected, Map.of("orders", OK), Map.of(), Map.of(), null, NOW);
        assertThat(joined.tables().get(0).sourceId).isEqualTo("u-1");
        SourceTable ready = joined.tables().get(0);
        ready.state = Enrollment.READY;
        selected.put(
                "orders",
                new SourceModel.Table(
                        "orders",
                        List.of(),
                        List.of("_id"),
                        List.of(),
                        List.of(),
                        true,
                        null,
                        "u-2"));
        Enrollment.Plan replaced =
                Enrollment.plan(
                        List.of(ready),
                        selected,
                        Map.of("orders", OK),
                        Map.of(),
                        Map.of(),
                        null,
                        NOW);
        assertThat(replaced.tables().get(0).state).isEqualTo("Failed");
        assertThat(replaced.tables().get(0).reason).isEqualTo("SourceReplaced");
        assertThat(replaced.jobTables()).isEmpty();
    }
}
