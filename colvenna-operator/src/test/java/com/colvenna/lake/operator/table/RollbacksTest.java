package com.colvenna.lake.operator.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import com.colvenna.lake.operator.flink.FlinkRest;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class RollbacksTest {
    static final String ROOT = "s3://b/flink-state";
    static final String TAKEN = ROOT + "/sp-new";
    static final String STARTED = "2026-10-26T01:16:26Z";

    /**
     * The new job's JobManager: {@code completed} checkpoints since a restore from {@code from}.
     */
    static Optional<FlinkRest.Checkpoints> answered(long completed, String from) {
        return Optional.of(new FlinkRest.Checkpoints(completed, 0, null, null, from, 0));
    }

    static Rollbacks.Failed failed(
            String savepoint,
            String jobId,
            Long checkpointBefore,
            Optional<FlinkRest.Checkpoints> newJob) {
        return new Rollbacks.Failed(
                true, savepoint, ROOT + "/sp-old", ROOT, jobId, checkpointBefore, STARTED, newJob);
    }

    static Optional<String> refusal(Rollbacks.Failed f, Supplier<Optional<Long>> lastCommit) {
        return Rollbacks.refusal(f, lastCommit);
    }

    static Supplier<Optional<Long>> committed(long id) {
        return () -> Optional.of(id);
    }

    @Test
    void aJobThatCompletedNoCheckpointSinceTheUpgradesSavepointWroteNothing() {
        assertThat(refusal(failed(TAKEN, "j-new", 41L, answered(0, TAKEN)), Optional::empty))
                .isEmpty();
        assertThat(refusal(failed(TAKEN, "j-new", 41L, answered(3, TAKEN)), Optional::empty))
                .get(STRING)
                .contains("may have written");
    }

    @Test
    void aCountThatDoesNotStartAtTheUpgradesSavepointLeavesItToTheTarget() {
        // A restarted JobManager counts from zero, from the new job's own checkpoint (or nothing).
        for (String from : new String[] {ROOT + "/j-new/chk-44", ""}) {
            assertThat(refusal(failed(TAKEN, "j-new", 41L, answered(0, from)), committed(43)))
                    .as(from)
                    .get(STRING)
                    .contains("counts no checkpoint but has it restored from")
                    .contains("not from the upgrade's savepoint");
            assertThat(refusal(failed(TAKEN, "j-new", 41L, answered(0, from)), committed(42)))
                    .as(from)
                    .isEmpty();
        }
    }

    @Test
    void withoutAJobManagerTheTargetsCheckpointIdsTell() {
        // The old job had used checkpoint 41; its savepoint commits under 42.
        assertThat(refusal(failed(TAKEN, "j-new", 41L, Optional.empty()), committed(42))).isEmpty();
        assertThat(refusal(failed(TAKEN, "j-new", 41L, Optional.empty()), committed(43)))
                .get(STRING)
                .contains("does not answer")
                .contains("committed under checkpoint 43, after 42")
                .contains("those committed under a checkpoint after 42");
        assertThat(refusal(failed(TAKEN, "j-new", 41L, Optional.empty()), Optional::empty))
                .isEmpty();
        // Unknown: no checkpoint recorded before the upgrade, or an unreadable target.
        assertThat(refusal(failed(TAKEN, "j-new", null, Optional.empty()), committed(1)))
                .get(STRING)
                .contains("were not known when the upgrade began")
                .contains("those committed after the upgrade began (" + STARTED + ")");
        assertThat(
                        refusal(
                                failed(TAKEN, "j-new", 41L, Optional.empty()),
                                () -> {
                                    throw new IllegalStateException("catalog down");
                                }))
                .get(STRING)
                .contains("could not be read (catalog down)");
    }

    @Test
    void beforeItsFirstCheckpointTheOldJobIsAtTheIdItRestored() {
        // Restored from savepoint 12, no checkpoint since: the upgrade's savepoint is 13. The
        // new job never ran, and its JobManager knows no such job.
        assertThat(refusal(failed(TAKEN, "j-new", 12L, Optional.empty()), committed(13))).isEmpty();
        assertThat(refusal(failed(TAKEN, "j-new", 12L, Optional.empty()), committed(12))).isEmpty();
        // A fresh job, upgraded before any checkpoint: its savepoint is 1.
        assertThat(refusal(failed(TAKEN, "j-new", 0L, Optional.empty()), committed(1))).isEmpty();
        assertThat(refusal(failed(TAKEN, "j-new", 0L, Optional.empty()), committed(2))).isPresent();
    }

    @Test
    void eachRefusalSaysWhatCanBeDoneInstead() {
        // It ran and checkpointed: from its own checkpoints, or a rebuild.
        assertThat(refusal(failed(TAKEN, "j-new", 12L, answered(2, TAKEN)), committed(13)))
                .get(STRING)
                .contains("the new job completed 2 checkpoints")
                .contains("Change the declaration, and the job continues from the new job's own")
                .contains(Rollbacks.REBUILD)
                .doesNotContain("judges again");
        // Unproven: how to tell, that the operator keeps judging, and the same two ways out.
        assertThat(refusal(failed(TAKEN, "j-new", null, Optional.empty()), committed(13)))
                .get(STRING)
                .contains("the operator judges again each round")
                .contains(Rollbacks.REBUILD)
                .doesNotContain("Fix the declaration");
    }

    @Test
    void theUpgradeMustHaveTakenItsOwnSavepoint() {
        for (String savepoint :
                new String[] {null, ROOT + "/sp-old", "KUBERNETES_OPERATOR_LAST_STATE"}) {
            assertThat(refusal(failed(savepoint, "j-new", 41L, answered(0, TAKEN)), null))
                    .as(String.valueOf(savepoint))
                    .get(STRING)
                    .contains("no savepoint")
                    .contains("continues from its own latest checkpoint");
        }
        Rollbacks.Failed noStable =
                new Rollbacks.Failed(
                        false, TAKEN, null, ROOT, "j", 41L, STARTED, answered(0, TAKEN));
        assertThat(refusal(noStable, null)).get(STRING).contains("no earlier spec");
    }
}
