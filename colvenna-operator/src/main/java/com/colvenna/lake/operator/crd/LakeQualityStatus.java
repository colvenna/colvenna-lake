package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.List;

/** What colvenna-operator judged of a table's quality and its gate. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LakeQualityStatus {
    @JsonPropertyDescription(
            "Pending (its table is missing), Ready (rules valid, results readable), Degraded (rules"
                    + " invalid or results unreadable: the gate is closed) or Failed (another"
                    + " LakeQuality came first for the table, or the table is not this"
                    + " namespace's: it is no gate).")
    public String phase;

    @JsonPropertyDescription("database.table, for kubectl get.")
    public String table;

    public Long observedGeneration;
    public String message;
    public List<StatusCondition> conditions = new ArrayList<>();

    @JsonPropertyDescription(
            "The first 12 hex digits of the SHA-256 of the rules' canonical JSON; a check counts"
                    + " for the gate only under the version it ran with.")
    public String ruleVersion;

    @JsonPropertyDescription("The database of the namespace's check results and gate decisions.")
    public String store;

    public GateState gate;

    @JsonPropertyDescription("The checks the gate stands on: those no newer check supersedes.")
    public List<Check> checks = new ArrayList<>();

    @JsonPropertyDescription("The gate's latest changes, newest last (all are in the store).")
    public List<Decision> decisions = new ArrayList<>();

    @JsonPropertyDescription("When the gate was last judged.")
    public String measuredAt;

    /** The gate as judged. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GateState {
        public Boolean open;

        @JsonPropertyDescription("NoCheck, Failed, Expired, NotReady, Passed or Waived.")
        public String cause;

        public String message;

        @JsonPropertyDescription("When it took this state.")
        public String since;

        @JsonPropertyDescription("The newest check, its snapshot and range.")
        public String check;

        public String checkUid;

        @JsonPropertyDescription("The waivers it stands on.")
        public List<String> waivers = new ArrayList<>();
    }

    /** One check the gate stands on. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Check {
        public String task;
        public String uid;
        public Long snapshot;
        public String asOf;

        @JsonPropertyDescription("The partitions checked; none for the whole table.")
        public List<String> partitions = new ArrayList<>();

        public String checkedAt;
        public Boolean passed;
        public List<String> failed = new ArrayList<>();
        public List<String> waived = new ArrayList<>();

        @JsonPropertyDescription(
                "The tag keeping the checked snapshot of a failed check, to locate its rows.")
        public String tag;

        @JsonPropertyDescription("Why a failed check has no tag.")
        public String note;
    }

    /** One change of the gate. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Decision {
        public String at;
        public Boolean open;
        public String cause;
        public String message;
        public String ruleVersion;

        @JsonPropertyDescription("The newest check then: its task, uid, snapshot, failed rules.")
        public String check;

        public String checkUid;
        public Long snapshot;
        public List<String> failed = new ArrayList<>();

        @JsonPropertyDescription("The waivers relied on: name, uid, approver and reason.")
        public List<String> waivers = new ArrayList<>();

        @JsonPropertyDescription("Whether it is written to the store's decisions table.")
        public Boolean recorded;
    }
}
