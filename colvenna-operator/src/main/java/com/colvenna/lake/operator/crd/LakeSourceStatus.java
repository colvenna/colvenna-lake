package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.List;

/** What colvenna-operator observed and did. Bounded: one entry per matched source table. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LakeSourceStatus {

    @JsonPropertyDescription(
            "Pending (no source table matches yet, or a kafka source's job is not made yet),"
                    + " Provisioning (tables still snapshotting, a job starting or suspending, or a"
                    + " kafka source's job not running yet), Running, Degraded (the source not ready"
                    + " or colvenna-catalog unavailable, a table rejected or failed, a database"
                    + " source's job not running, or the reconcile failed), Suspended, or Failed"
                    + " (nothing can run).")
    public String phase;

    @JsonPropertyDescription(
            "Ready tables out of matched tables (kafka: events in and rejected), for kubectl get.")
    public String summary;

    public Long observedGeneration;
    public String message;

    @JsonPropertyDescription(
            "When the operator last measured the source (its latency, lag, freshness and backlog).")
    public String measuredAt;

    public List<StatusCondition> conditions = new ArrayList<>();

    public CatalogIdentity identity;

    @JsonPropertyDescription("mysql: the server-id range the job's readers use.")
    public String serverIdRange;

    public List<JobState> jobs = new ArrayList<>();

    @JsonPropertyDescription(
            "postgres: replication slots of removed jobs, dropped once their job has released"
                    + " them, so they stop holding WAL.")
    public List<String> pendingSlots = new ArrayList<>();

    public List<SourceTable> tables = new ArrayList<>();

    @JsonPropertyDescription("kafka and transport.kafka: the cluster as the source's user sees it.")
    public KafkaState kafka;

    @JsonPropertyDescription("mongodb: how far back the oplog reaches.")
    public Retention retention;

    /** How far back a source's change log reaches; a job down longer cannot resume. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Retention {
        @JsonPropertyDescription(
                "Hours from the oldest to the newest change kept; null if unknown.")
        public Double windowHours;

        @JsonPropertyDescription("The declared minimum (connection.minOplogWindow).")
        public Double minimumHours;

        public String detail;
        public String checkedAt;
    }

    @JsonPropertyDescription("kafka: records in the events and rejections tables.")
    public EventCounts events;

    @JsonPropertyDescription(
            "kafka: the events declaration the tables and the job follow. A refused change"
                    + " leaves it as it was, so the job keeps running the last one applied.")
    public LakeSourceSpec.Events appliedEvents;

    @JsonPropertyDescription(
            "kafka: the target the source writes (database.table); it cannot change.")
    public String appliedTarget;

    @JsonPropertyDescription("transport.kafka: what the running job dropped.")
    public TransportCounts transport;

    @JsonPropertyDescription(
            "Deletes the collector never sent, being written while the job is suspended.")
    public Repair repair;

    @JsonPropertyDescription("The readers the governed Ready tables are published to.")
    public List<String> published = new ArrayList<>();

    @JsonPropertyDescription(
            "Targets of tables that left the source (dropped or renamed there): still this"
                    + " source's, dropped with it under deletionPolicy Delete.")
    public List<String> retired = new ArrayList<>();

    @JsonPropertyDescription("The heartbeat, written to the source and read back from the lake.")
    public HeartbeatState heartbeat;

    /** The latest heartbeat written, and the latest the lake holds. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HeartbeatState {
        public String table;
        public String writtenAt;

        @JsonPropertyDescription("The newest heartbeat's time the lake's latest data commit holds.")
        public String beatAt;

        @JsonPropertyDescription("When the last snapshot looked at was committed.")
        public String committedAt;

        @JsonPropertyDescription(
                "Of the heartbeats last seen in the lake, the longest from the source's commit to the"
                        + " first snapshot holding it (the acceptance contract's path: source to"
                        + " ODS readable).")
        public Long latencySeconds;

        @JsonPropertyDescription("How old the newest source change the lake holds is.")
        public Long freshnessSeconds;

        @JsonPropertyDescription("Whether the latest heartbeat was written.")
        public Boolean ok;

        @JsonPropertyDescription("The source's clock less the operator's, at the latest heartbeat.")
        public Long clockSkewSeconds;

        @JsonPropertyDescription(
                "Whether that skew is past the tolerance: the heartbeats are then no samples.")
        public Boolean skewed;

        @JsonPropertyDescription(
                "Heartbeats written and not yet seen in the lake (their times, oldest first).")
        public List<String> pending = new ArrayList<>();

        @JsonPropertyDescription("The last snapshot of the heartbeat table looked at.")
        public Long seenSnapshot;

        public String message;
    }

    /** The Kafka transport job's counters, for its current run. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TransportCounts {
        @JsonPropertyDescription("The run the counts are for.")
        public String jobId;

        @JsonPropertyDescription("Records that were not Debezium changes with their schema.")
        public Long unparsable;

        @JsonPropertyDescription(
                "Changes seen again, out of order or of a row deleted, dropped by their version"
                        + " (as far as the run remembers; the rest the tables absorb).")
        public Long stale;

        @JsonPropertyDescription(
                "Partitions where the run started without proof that retention had kept every"
                        + " change its tables lacked.")
        public Long unverified;

        @JsonPropertyDescription(
                "Snapshot records read after the tables were last written: the collector"
                        + " snapshotted again, and rows deleted while it had no position never reached"
                        + " the topics. The tables are reconciled with the source.")
        public Long resnapshot;
    }

    /**
     * Rows the source deleted that never reached the topics, being deleted: the job is suspended
     * meanwhile, as their table's writer.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Repair {
        public String since;
        public List<String> tables = new ArrayList<>();
    }

    /** A Kafka cluster's answer to the source's user. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class KafkaState {
        @JsonPropertyDescription(
                "Null when every topic was read; else CaMissing, CredentialsMissing, Unreachable,"
                        + " TlsFailed, AuthenticationFailed, NotAuthorized, TopicMissing or (a"
                        + " transport) TopicCompacted.")
        public String reason;

        public String message;
        public String checkedAt;
        public List<TopicState> topics = new ArrayList<>();
    }

    /** One topic, summed over its partitions. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TopicState {
        public String name;
        public Integer partitions;
        public Long endOffset;
        public Long committedOffset;

        @JsonPropertyDescription("Records not yet consumed by the job's group.")
        public Long lag;
    }

    /** Rows in the two tables of a Kafka source, at their latest snapshots. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EventCounts {
        public Long accepted;
        public Long rejected;

        @JsonPropertyDescription("Rejected records by reason code.")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public java.util.Map<String, Long> rejectedBy = new java.util.TreeMap<>();

        @JsonPropertyDescription(
                "True when rejectedBy counts only the first reconcileMaxRows rejections.")
        public Boolean rejectedByPartial;
    }

    /** One Flink job: the database job (mysql) or one per table (postgres). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JobState {
        public String deployment;
        public List<String> tables = new ArrayList<>();
        public String lifecycle;
        public String state;
        public String jobId;
        public String lastError;
        public String lastErrorTime;

        @JsonPropertyDescription(
                "The checkpoint the running job restored from, or none for a fresh start.")
        public String restoredFrom;

        @JsonPropertyDescription(
                "When the running submission of the job started, from the Flink operator.")
        public String startTime;

        @JsonPropertyDescription("The running job's checkpoints that failed.")
        public Long checkpointsFailed;

        @JsonPropertyDescription("When its latest checkpoint completed, and so its last commit.")
        public String lastCheckpointAt;

        @JsonPropertyDescription("How long its latest checkpoint took, in milliseconds.")
        public Long lastCheckpointDurationMillis;

        @JsonPropertyDescription("The heap its busiest TaskManager uses, in bytes.")
        public Long heapUsedBytes;

        @JsonPropertyDescription("That TaskManager's maximum heap, in bytes.")
        public Long heapMaxBytes;
    }

    /** Whether a table is governed, and why not (LLD "治理与个人数据删除"). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TableGovernance {
        public Boolean governed;
        public String reason;
        public String message;
    }

    /** One matched source table. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SourceTable {
        public String name;
        public String target;

        @JsonPropertyDescription(
                "Snapshotting (in the job, not yet reconciled), Ready (rows reconciled with the"
                        + " source), Rejected (not admitted; not ingested), or Failed (taken out"
                        + " of the job; it does not rejoin by itself).")
        public String state;

        public String reason;
        public String message;
        public String lastTransitionTime;
        public Long sourceRows;
        public Long targetRows;

        @JsonPropertyDescription(
                "The target's changelog producer: input from the database's log, lookup from Kafka.")
        public String changelog;

        @JsonPropertyDescription(
                "mongodb: the collection's UUID when it joined the job; another one means the"
                        + " collection was replaced and the target missed changes.")
        public String sourceId;

        public String reconciledAt;

        @JsonPropertyDescription(
                "Since when a Ready table waits to be reconciled with the source again: the"
                        + " collector snapshotted again, or deletes it never sent were just"
                        + " written.")
        public String recheckSince;

        public TableGovernance governance;

        @JsonPropertyDescription("The target snapshot the last reconciliation read.")
        public Long reconciledSnapshot;

        /** The source key, recorded on admission. */
        public List<String> primaryKey = new ArrayList<>();

        @JsonPropertyDescription(
                "postgres: the tables the job reads (the table or its partitions).")
        public List<String> readTables = new ArrayList<>();
    }
}
