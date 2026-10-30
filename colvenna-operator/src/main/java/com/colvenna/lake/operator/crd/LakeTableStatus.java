package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** What colvenna-operator observed and did for a layered table. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LakeTableStatus {

    @JsonPropertyDescription(
            "Pending (waiting for an upstream table or colvenna-catalog), Validating, Provisioning"
                    + " (the job is not running: starting, upgrading, suspending or restarting),"
                    + " Running, Degraded (the job failed to upgrade, needs a rebuild or lost its"
                    + " state; the declaration as it now stands cannot be applied; or the reconcile"
                    + " failed), Suspended, or Failed (nothing can run).")
    public String phase;

    @JsonPropertyDescription("database.table, for kubectl get.")
    public String target;

    public Long observedGeneration;

    @JsonPropertyDescription(
            "The LakeTask whose backfill holds the job suspended, once the job is suspended.")
    public String fencedBy;

    public String message;

    @JsonPropertyDescription(
            "When the operator last measured the table (its latency, lag, freshness and backlog).")
    public String measuredAt;

    public List<StatusCondition> conditions = new ArrayList<>();

    public CatalogIdentity identity;

    @JsonPropertyDescription("The latest validation of the declared query.")
    public Validation validation;

    @JsonPropertyDescription("The validated declaration the job runs.")
    public Applied applied;

    @JsonPropertyDescription("An upgrade the job has not finished yet.")
    public Upgrade upgrade;

    @JsonPropertyDescription(
            "A change that failed to start and was rolled back; it is not applied again until"
                    + " the declaration changes.")
    public RolledBack rolledBack;

    @JsonPropertyDescription(
            "The savepoint the last rollback redeployed from; kept in the deployment so later"
                    + " changes upgrade normally.")
    public Redeploy redeploy;

    public LakeSourceStatus.JobState job;

    @JsonPropertyDescription("How far the job has read each streamed upstream table.")
    public List<Upstream> upstream = new ArrayList<>();

    @JsonPropertyDescription("The readers the target is published to (table-level READ).")
    public List<String> published = new ArrayList<>();

    @JsonPropertyDescription("The rows in the quarantine, by the check they failed.")
    public QualityState quality;

    @JsonPropertyDescription(
            "How old the newest source change a query of the target sees is: its upstream's"
                    + " freshness and how far the job is behind it (the acceptance contract"
                    + " composes the paths so);"
                    + " absent while an upstream's is unknown.")
    public Long freshnessSeconds;

    /** One validation run, keyed by a hash of what it depends on. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Validation {
        public String hash;
        public String validatedAt;

        @JsonPropertyDescription("Why the query is refused; absent when it is accepted.")
        public String reason;

        public String message;
        public List<Column> columns = new ArrayList<>();
        public List<String> streams = new ArrayList<>();
        public List<String> lookups = new ArrayList<>();

        @JsonPropertyDescription(
                "Whether the job keeps state (an aggregate, a regular join) a backfill of the target"
                        + " would not follow.")
        public Boolean stateful;
    }

    /** An output column: its name and Paimon type (as Paimon's JSON writes it). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Column {
        public String name;
        public String type;
    }

    /** What the deployment runs. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Applied {
        public String hash;
        public String target;

        @JsonPropertyDescription("The quarantine the job writes the rows failing a check to.")
        public String quarantine;

        public List<String> upstream = new ArrayList<>();
        public List<String> streams = new ArrayList<>();

        @JsonPropertyDescription("The tables the job looks up, as the planner found them.")
        public List<String> lookups = new ArrayList<>();
    }

    /** A deployment change in flight, kept to judge whether it can be rolled back. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Upgrade {
        @JsonPropertyDescription("What the deployment ran before the change.")
        public Applied from;

        public String toHash;
        public String fromJobId;

        @JsonPropertyDescription(
                "The highest checkpoint id the old job had used when the upgrade began: completed,"
                        + " failed or in progress, a savepoint, or the one it restored from; 0 for"
                        + " none. Its savepoint comes after it; any later checkpoint is the new"
                        + " job's.")
        public Long fromCheckpoint;

        @JsonPropertyDescription(
                "How many checkpoints the old job had completed when the upgrade began, as its"
                        + " JobManager counted them since it started; 0 when the upgrade came"
                        + " before its first.")
        public Long fromCheckpointsCompleted;

        @JsonPropertyDescription(
                "The deployment's savepoint path before the upgrade: a rollback needs a newer one.")
        public String fromSavepoint;

        public String startedAt;
    }

    /** A rolled-back change. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RolledBack {
        @JsonPropertyDescription("The deployment spec hash that failed.")
        public String hash;

        public String at;
        public String message;
    }

    /** A redeploy from a savepoint (the Flink operator's savepointRedeployNonce). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Redeploy {
        public String savepoint;
        public Long nonce;
    }

    /** One streamed upstream table. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Upstream {
        public String table;
        public String consumerId;

        @JsonPropertyDescription("The snapshot the job reads next, from its Paimon consumer.")
        public Long nextSnapshot;

        public Long latestSnapshot;

        @JsonPropertyDescription("Upstream snapshots committed but not read yet.")
        public Long lag;

        @JsonPropertyDescription(
                "Seconds since the oldest upstream snapshot the job has not yet carried was"
                        + " committed (the acceptance contract's path: ODS readable to DWD"
                        + " readable); 0 when caught up.")
        public Long lagSeconds;

        @JsonPropertyDescription(
                "The latest upstream snapshot the job carried: seconds from its commit to the"
                        + " target's commit holding it (the acceptance contract's path: ODS"
                        + " readable to DWD readable).")
        public Long latencySeconds;
    }

    /** Where the rows failing a check went, and how many. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QualityState {
        public String quarantine;
        public Long rows;

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public Map<String, Long> byRule = new TreeMap<>();

        @JsonPropertyDescription("When the newest row that failed each check was flagged.")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public Map<String, String> newestByRule = new TreeMap<>();

        public String countedAt;
    }
}
