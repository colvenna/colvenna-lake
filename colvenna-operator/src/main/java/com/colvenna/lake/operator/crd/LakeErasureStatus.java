package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.List;

/** Where the erasure stands, copy by copy, with the evidence of what is done. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LakeErasureStatus {
    @JsonPropertyDescription(
            "Accepted (in progress), LogicallyErased, PhysicallyErased, Blocked (a copy is held by"
                    + " policy) or SlaBreach (a deadline passed); or Failed (not accepted).")
    public String phase;

    public String message;
    public Long observedGeneration;
    public List<StatusCondition> conditions = new ArrayList<>();

    @JsonPropertyDescription("When the erasure was requested; its intake is from then to t0.")
    public String requestedAt;

    @JsonPropertyDescription("t0: when the erasure was accepted; the deadlines count from it.")
    public String acceptedAt;

    public String logicalDeadline;
    public String physicalDeadline;
    public String logicallyErasedAt;
    public String physicallyErasedAt;

    @JsonPropertyDescription(
            "Each deadline that passed before its level was reached, and what was left then. Kept"
                    + " once the erasure completes.")
    public List<String> breaches = new ArrayList<>();

    public CatalogIdentity identity;

    @JsonPropertyDescription("Every copy of the subject's data the erasure covers.")
    public List<Copy> copies = new ArrayList<>();

    @JsonPropertyDescription(
            "The LakeTasks the erasure ran (expiry, compaction, tags, orphans, backfill).")
    public List<String> tasks = new ArrayList<>();

    @JsonPropertyDescription("How many probe runs counted the subject's rows and records.")
    public Integer probes;

    @JsonPropertyDescription(
            "The run whose result was last taken up: a result is read again each round, but only"
                    + " a new one says the subject came back.")
    public Integer probeTaken;

    /** One copy of the subject's data. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Copy {
        @JsonPropertyDescription(
                "SourceDatabase, Topic, Rows, History, Files, FlinkState, Storage, Reader,"
                        + " Dashboard, DorisAuditLog or DorisStorage.")
        public String kind;

        @JsonPropertyDescription(
                "The table (database.table), source table (source/table), topics, job (or jobs no"
                        + " longer declared), reader, dashboard query (dashboard/query), or the"
                        + " platform's warehouse, Doris storage or Doris audit log.")
        public String name;

        @JsonPropertyDescription(
                "Who answers for the copy: its declaration's owner (\"unclassified: no owner"
                        + " declared\" without one), platform, the reader itself, or unknown (no"
                        + " declaration covers the table).")
        public String owner;

        @JsonPropertyDescription("FlinkState: where the job's checkpoints and savepoints are.")
        public String location;

        @JsonPropertyDescription(
                "Logical, Physical, or External (outside the platform; reported, not counted).")
        public String layer;

        @JsonPropertyDescription("Pending, Done, Blocked, PendingEvidence or External.")
        public String state;

        public String message;

        @JsonPropertyDescription("Blocked: when the owner is to have reviewed what holds it.")
        public String reviewBy;

        @JsonPropertyDescription(
                "Rows and SourceDatabase: the columns that hold the subject's key in the table.")
        public List<String> subjectKey;

        @JsonPropertyDescription("SourceDatabase: the lake table it feeds.")
        public String table;

        @JsonPropertyDescription(
                "Dashboard: the lake tables with personal data the copy is made of.")
        public List<String> tables;

        @JsonPropertyDescription("Rows: how many times the subject came back to the table.")
        public Integer round;

        @JsonPropertyDescription(
                "SourceDatabase and Rows: the most rows of the subject a check counted.")
        public Long seen;

        @JsonPropertyDescription("What proves it done: snapshots, counts, task results.")
        public String evidence;

        public String doneAt;

        @JsonPropertyDescription("Rows: the first snapshot without the subject's rows.")
        public Long cleanSnapshot;

        public Long cleanMillis;

        @JsonPropertyDescription(
                "Files: the first snapshot whose data files hold no record of the subject.")
        public Long compactedSnapshot;

        public Long compactedMillis;
    }
}
