package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What colvenna-operator observed and did for a task. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LakeTaskStatus {
    @JsonPropertyDescription(
            "Pending (waiting for its table, or another task on it), Fencing (a backfill"
                    + " suspends the table's job), Running, Converging (a backfill waits for the"
                    + " table's readers), Succeeded or Failed. Succeeded and Failed are final.")
    public String phase;

    @JsonPropertyDescription("database.table, for kubectl get.")
    public String table;

    public Long observedGeneration;
    public String message;
    public List<StatusCondition> conditions = new ArrayList<>();

    public CatalogIdentity identity;

    @JsonPropertyDescription("Attempts made, the first included.")
    public Integer attempts;

    @JsonPropertyDescription("Each attempt that ended: its Job, when, and how.")
    public List<Attempt> history = new ArrayList<>();

    public String startedAt;
    public String finishedAt;

    @JsonPropertyDescription("The Kubernetes Job of the current attempt.")
    public String run;

    @JsonPropertyDescription("A backfill's hold on the table's job, while it rewrites the table.")
    public Fence fence;

    @JsonPropertyDescription("What the run did.")
    public Result result;

    @JsonPropertyDescription(
            "A check's fixed inputs, set as it starts and used by every attempt, and its verdict.")
    public CheckState check;

    @JsonPropertyDescription("A backfill's readers that have not read its commit yet.")
    public List<String> waitingFor = new ArrayList<>();

    @JsonPropertyDescription(
            "A backfill's reach downstream, by database.table: the snapshot of each table that"
                    + " carries its changes. A table's readers have converged once they read past"
                    + " it.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, Reach> frontier = new LinkedHashMap<>();

    /** How far a backfill's changes reached in one table. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Reach {
        @JsonPropertyDescription(
                "The table's snapshot carrying the changes; unset until it is looked up, a while"
                        + " after its writer read them.")
        public Long snapshot;

        @JsonPropertyDescription("When the table's writer had read the changes of all of via.")
        public String readAt;

        @JsonPropertyDescription("The tables carrying the changes that the writer had read.")
        public List<String> via = new ArrayList<>();
    }

    /** One attempt that ended. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Attempt {
        public Integer attempt;
        public String run;
        public String finishedAt;

        @JsonPropertyDescription("Succeeded or Failed.")
        public String outcome;

        public String message;
    }

    /** The LakeTable a backfill suspended. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Fence {
        public String lakeTable;
        public String fencedAt;
        public String releasedAt;
    }

    /** The run's account: the table's snapshots before and after, and what changed. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Result {
        public Long snapshotBefore;
        public Long snapshotAfter;

        @JsonPropertyDescription(
                "For example the snapshots expired, the files removed or the rows written.")
        public String detail;
    }

    /** A check's inputs and verdict. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CheckState {
        @JsonPropertyDescription("The LakeQuality whose rules it runs.")
        public String quality;

        @JsonPropertyDescription("The version of the rules as declared when it started.")
        public String ruleVersion;

        @JsonPropertyDescription("The snapshot it reads, the latest when it started unless given.")
        public Long snapshot;

        @JsonPropertyDescription("The time freshness is measured at.")
        public String asOf;

        @JsonPropertyDescription("The partitions it reads; none for the whole table.")
        public List<String> partitions = new ArrayList<>();

        @JsonPropertyDescription("Passed or Failed, once the results are written.")
        public String verdict;

        @JsonPropertyDescription("The rules that did not pass.")
        public List<String> failed = new ArrayList<>();

        @JsonPropertyDescription("The rows checked (the range's, before freshness).")
        public Long rows;
    }
}
