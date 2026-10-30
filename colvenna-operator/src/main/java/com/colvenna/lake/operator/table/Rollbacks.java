package com.colvenna.lake.operator.table;

import com.colvenna.lake.operator.flink.FlinkRest;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * When a failed upgrade may be rolled back: to the spec that ran before, from the savepoint the
 * upgrade took, and only if the new job wrote nothing, or the old job would apply the same input
 * again on top of the new job's output. Each refusal says what can be done instead.
 */
final class Rollbacks {
    /** How a refused rollback can be recovered from: the user manual's rebuild (4.3). */
    static final String REBUILD =
            "rebuild the table: declare another one, or delete this one with deletionPolicy Delete"
                    + " and declare it again";

    private Rollbacks() {}

    /**
     * What the operator knows about a failed upgrade. {@code savepoint}: the one the upgrade took;
     * {@code checkpointBefore}: the highest checkpoint id the old job had used when the upgrade
     * began ({@code FlinkRest.Checkpoints.lastId}), or null when its JobManager did not tell;
     * {@code newJob}: the new job's checkpoints, empty when its JobManager does not tell.
     */
    record Failed(
            boolean hadStableSpec,
            String savepoint,
            String savepointBefore,
            String checkpointRoot,
            String jobId,
            Long checkpointBefore,
            String startedAt,
            Optional<FlinkRest.Checkpoints> newJob) {}

    /**
     * Why the upgrade may not be rolled back, and what can be done instead; empty when it may.
     * {@code lastCommit} is the checkpoint id the target's latest snapshot was committed under,
     * read only when it decides.
     */
    static Optional<String> refusal(Failed f, Supplier<Optional<Long>> lastCommit) {
        if (!f.hadStableSpec()) {
            return Optional.of("there is no earlier spec that ran. Fix the declaration");
        }
        if (f.savepoint() == null
                || f.savepoint().equals(f.savepointBefore())
                || !f.savepoint().startsWith(f.checkpointRoot())) {
            // Also the Flink operator's last-state fallback, which records a placeholder path.
            return Optional.of(
                    "the upgrade took no savepoint to return to. Change the declaration, and the job"
                            + " continues from its own latest checkpoint, or "
                            + REBUILD);
        }
        Optional<String> written = mayHaveWritten(f, lastCommit);
        if (written.isEmpty()) {
            return Optional.empty();
        }
        String refused =
                written.get()
                        + ", so the new job may have written to the target, and the old job would"
                        + " apply that input again. ";
        if (f.newJob().map(c -> c.completed() > 0).orElse(false)) {
            // It ran: its checkpoints and HA metadata are there for the Flink operator.
            return Optional.of(
                    refused
                            + "Change the declaration, and the job continues from the new job's"
                            + " own latest checkpoint, or "
                            + REBUILD);
        }
        return Optional.of(
                refused
                        + "The new job's snapshots of the target would be those committed "
                        + (f.checkpointBefore() == null
                                ? "after the upgrade began (" + f.startedAt() + ")"
                                : "under a checkpoint after " + (f.checkpointBefore() + 1))
                        + "; the operator judges again each round. Otherwise change the"
                        + " declaration, and the job continues from the new job's own latest"
                        + " checkpoint if it ran, or "
                        + REBUILD);
    }

    /**
     * What says the new job may have written to the target; empty when it certainly wrote nothing.
     * Paimon commits a snapshot only when a checkpoint completes, under that checkpoint's id, and
     * checkpoint ids continue across a savepoint: the old job commits under ids up to its
     * savepoint's, which is at least one after the highest id it had used when the upgrade began
     * ({@code checkpointBefore}), and the new job, restored from that savepoint, under later ones.
     * Either of two things proves it:
     *
     * <ul>
     *   <li>the new job's JobManager counts no completed checkpoint and says the job restored from
     *       the upgrade's savepoint. The count starts over when the JobManager does, but a
     *       restarted one restores the job from its latest checkpoint, not from that savepoint;
     *   <li>the target's latest snapshot carries an id no later than {@code checkpointBefore + 1}.
     *       A checkpoint the old job triggered between that reading and its savepoint moves the
     *       savepoint later and fails this proof: a refusal on the safe side.
     * </ul>
     *
     * Anything else counts as written: a wrong rollback applies the input twice, a refused one only
     * waits.
     */
    static Optional<String> mayHaveWritten(Failed f, Supplier<Optional<Long>> lastCommit) {
        if (f.jobId() == null) {
            return Optional.of("the new job is not known");
        }
        String jobManager = "the new job's JobManager does not answer";
        if (f.newJob().isPresent()) {
            FlinkRest.Checkpoints c = f.newJob().get();
            if (c.completed() > 0) {
                return Optional.of("the new job completed " + c.completed() + " checkpoints");
            }
            if (c.restored().equals(f.savepoint())) {
                return Optional.empty();
            }
            jobManager =
                    "the new job's JobManager counts no checkpoint but has it restored from "
                            + (c.restored().isEmpty() ? "nothing" : c.restored())
                            + ", not from the upgrade's savepoint";
        }
        if (f.checkpointBefore() == null) {
            return Optional.of(
                    jobManager
                            + ", and the old job's checkpoints were not known when the upgrade"
                            + " began");
        }
        long last = f.checkpointBefore() + 1;
        Optional<Long> committed;
        try {
            committed = lastCommit.get();
        } catch (RuntimeException e) {
            return Optional.of(
                    jobManager
                            + ", and the target's latest snapshot could not be read ("
                            + e.getMessage()
                            + ")");
        }
        if (committed.isEmpty() || committed.get() <= last) {
            return Optional.empty();
        }
        return Optional.of(
                jobManager
                        + ", and the target's latest snapshot was committed under checkpoint "
                        + committed.get()
                        + ", after "
                        + last
                        + ", the last the old job is known to commit under");
    }
}
