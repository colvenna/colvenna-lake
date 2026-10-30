package com.colvenna.lake.operator.table;

import static com.colvenna.lake.operator.table.UpgradeFixture.BEFORE;
import static com.colvenna.lake.operator.table.UpgradeFixture.FILTERED;
import static com.colvenna.lake.operator.table.UpgradeFixture.OVERDUE;
import static com.colvenna.lake.operator.table.UpgradeFixture.QUERY;
import static com.colvenna.lake.operator.table.UpgradeFixture.ROOT;
import static com.colvenna.lake.operator.table.UpgradeFixture.STATE;
import static com.colvenna.lake.operator.table.UpgradeFixture.T0;
import static com.colvenna.lake.operator.table.UpgradeFixture.TAKEN;
import static com.colvenna.lake.operator.table.UpgradeFixture.checkpoints;
import static com.colvenna.lake.operator.table.UpgradeFixture.committed;
import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.crd.LakeSourceSpec;
import com.colvenna.lake.operator.crd.LakeTableStatus;
import com.colvenna.lake.operator.flink.FlinkJobs;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.junit.jupiter.api.Test;

/** A layered table's upgrade from the change to its end, judged by {@link Upgrades}. */
class UpgradesTest {
    final UpgradeFixture f = new UpgradeFixture();
    final List<FlinkDeployment> written = new ArrayList<>();
    final Upgrades upgrades =
            new Upgrades(
                    f.jobManager,
                    ROOT,
                    fd ->
                            written.add(
                                    Serialization.unmarshal(
                                            Serialization.asJson(fd), FlinkDeployment.class)));

    FlinkDeployment last() {
        return written.get(written.size() - 1);
    }

    /** The old job runs as {@code checkpoints} say; the filter is declared and fails to start. */
    FlinkDeployment upgradeFails(String checkpoints) {
        FlinkDeployment fd = f.running("j-old");
        if (checkpoints != null) {
            f.jobManager.checkpoints.put("j-old", checkpoints);
        }
        Upgrades.Step upgrade = upgrades.change(f.table, fd, f.declared(FILTERED), T0, null);
        assertThat(upgrade.kind()).isEqualTo(Upgrades.Kind.UPDATED);
        f.newJobFails(fd, "j-new");
        return fd;
    }

    /** The deployment last written: back to the old query from the upgrade's savepoint. */
    void assertRedeployedFromTheUpgradesSavepoint(Upgrades.Step step, String hash) {
        assertThat(step.kind()).isEqualTo(Upgrades.Kind.ROLLED_BACK);
        assertThat(TableJobs.queryOf(last())).isEqualTo(QUERY);
        assertThat(last().getSpec().getJob().getInitialSavepointPath()).isEqualTo(TAKEN);
        assertThat(last().getSpec().getJob().getSavepointRedeployNonce()).isEqualTo(1L);
        assertThat(FlinkJobs.hashOf(last())).isEqualTo(hash);
        LakeTableStatus status = f.table.getStatus();
        assertThat(status.upgrade).isNull();
        assertThat(status.redeploy.savepoint).isEqualTo(TAKEN);
        assertThat(status.applied.hash).isEqualTo(hash);
        assertThat(step.message()).contains("rolled back to the previous spec from " + TAKEN);
    }

    @Test
    void anUpgradeBeforeTheOldJobsFirstCheckpointRollsBackWhenTheNewJobNeverRan() {
        // The old job restored from savepoint 12 and has completed no checkpoint of its own.
        String old = FlinkJobs.hashOf(f.running("j-old"));
        FlinkDeployment fd = upgradeFails(checkpoints(0, null, 12L, BEFORE));
        assertThat(f.table.getStatus().upgrade.fromCheckpoint).isEqualTo(12L);
        assertThat(f.table.getStatus().upgrade.fromCheckpointsCompleted).isZero();
        // The new job cannot map the savepoint's state and never runs: its JobManager knows no
        // such job. The target's latest snapshot is the old job's savepoint, checkpoint 13.
        assertThat(upgrades.failed(f.table, fd, T0.plusSeconds(60), null, committed(13))).isNull();
        Upgrades.Step back = upgrades.failed(f.table, fd, OVERDUE, "Job Not Found", committed(13));
        assertRedeployedFromTheUpgradesSavepoint(back, old);
        assertThat(f.table.getStatus().rolledBack.hash)
                .isEqualTo(FlinkJobs.hashOf(f.declared(FILTERED)));
    }

    @Test
    void aFreshJobUpgradedBeforeAnyCheckpointRollsBackToo() {
        // Deployed moments ago, no restore and no checkpoint: its savepoint is checkpoint 1.
        String old = FlinkJobs.hashOf(f.running("j-old"));
        FlinkDeployment fd = upgradeFails(checkpoints(0, null, null, null));
        assertRedeployedFromTheUpgradesSavepoint(
                upgrades.failed(f.table, fd, OVERDUE, null, committed(1)), old);
    }

    @Test
    void aNewJobThatRanAndCheckpointedKeepsTheRefusal() {
        FlinkDeployment fd = upgradeFails(checkpoints(0, null, 12L, BEFORE));
        int writes = written.size();
        // It ran: two checkpoints completed, so it committed, then it kept failing.
        f.jobManager.checkpoints.put("j-new", checkpoints(2, 15L, 13L, TAKEN));
        Upgrades.Step refused =
                upgrades.failed(f.table, fd, OVERDUE, "Job Not Found", committed(13));
        assertThat(refused.kind()).isEqualTo(Upgrades.Kind.REFUSED);
        assertThat(written).hasSize(writes);
        assertThat(f.table.getStatus().upgrade).isNotNull();
        assertThat(refused.message())
                .contains("the new job completed 2 checkpoints")
                .contains("Change the declaration, and the job continues from the new job's own")
                .contains(Rollbacks.REBUILD)
                .doesNotContain("Fix the declaration");
    }

    @Test
    void aJobManagerCountingNoCheckpointProvesItOnlyForARestoreFromTheUpgradesSavepoint() {
        String old = FlinkJobs.hashOf(f.running("j-old"));
        FlinkDeployment fd = upgradeFails(checkpoints(1, 12L, 11L, BEFORE));
        // Restored from the upgrade's savepoint and nothing completed since: never wrote, though
        // the target's latest snapshot came after 13 (a checkpoint the old job triggered between
        // the upgrade's reading and its savepoint).
        f.jobManager.checkpoints.put("j-new", checkpoints(0, null, 14L, TAKEN));
        assertRedeployedFromTheUpgradesSavepoint(
                upgrades.failed(f.table, fd, OVERDUE, null, committed(14)), old);
    }

    @Test
    void aJobManagerThatStartedOverIsNoProofAndTheTargetDecides() {
        String old = FlinkJobs.hashOf(f.running("j-old"));
        FlinkDeployment fd = upgradeFails(checkpoints(1, 12L, 11L, BEFORE));
        // A restarted JobManager counts from zero and restored the new job's own checkpoint.
        String own = STATE.replace("savepoints", "checkpoints") + "/j-new/chk-14";
        f.jobManager.checkpoints.put("j-new", checkpoints(0, null, 14L, own));
        Upgrades.Step refused = upgrades.failed(f.table, fd, OVERDUE, null, committed(14));
        assertThat(refused.kind()).isEqualTo(Upgrades.Kind.REFUSED);
        assertThat(refused.message())
                .contains("counts no checkpoint but has it restored from " + own)
                .contains("committed under checkpoint 14, after 13")
                .contains(
                        "snapshots of the target would be those committed under a checkpoint"
                                + " after 13");
        // Checkpoint 14 committed nothing: the target's latest is still the savepoint's.
        assertRedeployedFromTheUpgradesSavepoint(
                upgrades.failed(f.table, fd, OVERDUE, null, committed(13)), old);
    }

    @Test
    void aRefusedRollbackRedeploysFromTheUpgradesSavepointOnceTheDeclarationIsReverted() {
        String old = FlinkJobs.hashOf(f.running("j-old"));
        FlinkDeployment fd = upgradeFails(checkpoints(1, 12L, 11L, BEFORE));
        // The target cannot be read when the upgrade is judged: no proof, no rollback.
        Upgrades.Step refused =
                upgrades.failed(f.table, fd, OVERDUE, "Job Not Found", UpgradeFixture::unreadable);
        assertThat(refused.kind()).isEqualTo(Upgrades.Kind.REFUSED);
        assertThat(refused.message()).contains("could not be read (colvenna-catalog unavailable)");

        // Reverted: the deployment returns to what ran, from the savepoint the upgrade took, not
        // a savepoint-less upgrade the Flink operator cannot apply without a running job.
        FlinkDeployment reverted = f.declared(QUERY);
        assertThat(FlinkJobs.hashOf(reverted)).isEqualTo(old);
        assertRedeployedFromTheUpgradesSavepoint(
                upgrades.change(f.table, fd, reverted, OVERDUE.plusSeconds(60), committed(13)),
                old);
    }

    @Test
    void aFixedDeclarationAfterARefusalUpgradesFromTheSpecThatRanOnceItRuns() {
        String old = FlinkJobs.hashOf(f.running("j-old"));
        FlinkDeployment fd = upgradeFails(checkpoints(1, 12L, 11L, BEFORE));
        upgrades.failed(f.table, fd, OVERDUE, "Job Not Found", UpgradeFixture::unreadable);

        // Fixed: the old query with more TaskManager memory. First the way back...
        f.table.getSpec().job = new LakeSourceSpec.Job();
        f.table.getSpec().job.taskManager = new LakeSourceSpec.Resources();
        f.table.getSpec().job.taskManager.memory = "4096m";
        Instant later = OVERDUE.plusSeconds(60);
        assertRedeployedFromTheUpgradesSavepoint(
                upgrades.change(f.table, fd, f.declared(QUERY), later, committed(13)), old);
        // ...and the fix waits while the old job is not running yet...
        int writes = written.size();
        Upgrades.Step waiting = upgrades.change(f.table, fd, f.declared(QUERY), later, null);
        assertThat(waiting.kind()).isEqualTo(Upgrades.Kind.WAITING);
        assertThat(written).hasSize(writes);
        // ...then upgrades it with a savepoint, judged from where its checkpoints stand.
        f.runs(fd, last(), "j-back");
        f.jobManager.checkpoints.put("j-back", checkpoints(0, null, 13L, TAKEN));
        Upgrades.Step upgrade =
                upgrades.change(f.table, fd, f.declared(QUERY), later.plusSeconds(60), null);
        assertThat(upgrade.kind()).isEqualTo(Upgrades.Kind.UPDATED);
        assertThat(upgrade.message()).endsWith("upgrades with a savepoint");
        LakeTableStatus.Upgrade recorded = f.table.getStatus().upgrade;
        assertThat(recorded.fromJobId).isEqualTo("j-back");
        assertThat(recorded.fromCheckpoint).isEqualTo(13L);
        assertThat(recorded.fromSavepoint).isEqualTo(TAKEN);
        assertThat(recorded.from.hash).isEqualTo(old);
    }

    @Test
    void anUnprovenRollbackSaysHowToTellWhetherTheNewJobWroteAndHowToRecover() {
        // The old job's JobManager did not answer when the upgrade began: no baseline.
        FlinkDeployment fd = upgradeFails(null);
        Upgrades.Step refused =
                upgrades.failed(f.table, fd, OVERDUE, "Job Not Found", committed(13));
        assertThat(refused.kind()).isEqualTo(Upgrades.Kind.REFUSED);
        assertThat(refused.message())
                .contains("may have written to the target")
                .contains("those committed after the upgrade began (" + T0 + ")")
                .contains("the operator judges again each round")
                .contains(Rollbacks.REBUILD)
                .doesNotContain("Fix the declaration");

        // Reverted without a proof: the declaration goes to the Flink operator as it is.
        Upgrades.Step pushed =
                upgrades.change(
                        f.table, fd, f.declared(QUERY), OVERDUE.plusSeconds(60), committed(13));
        assertThat(pushed.kind()).isEqualTo(Upgrades.Kind.UPDATED);
        assertThat(last().getSpec().getJob().getInitialSavepointPath()).isNull();
        assertThat(f.table.getStatus().upgrade.fromCheckpoint).isNull();
        assertThat(f.table.getStatus().rolledBack).isNull();
    }
}
