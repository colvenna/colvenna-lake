package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.List;

/** What colvenna-operator published of a table, and why not when it did not. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LakeIcebergPublicationStatus {
    @JsonPropertyDescription(
            "Pending (no Iceberg catalog configured, or the table or its first Iceberg"
                    + " metadata is not there yet), Published,"
                    + " Withheld (it may not be published now: withdrawn) or Failed (another"
                    + " publication came first for the table).")
    public String phase;

    @JsonPropertyDescription("database.table, for kubectl get.")
    public String table;

    public Long observedGeneration;
    public String message;
    public List<StatusCondition> conditions = new ArrayList<>();

    @JsonPropertyDescription("What the catalog points at now.")
    public Published published;

    @JsonPropertyDescription(
            "The last notification's sequence number; the catalog refuses one not above it.")
    public Long sequence;

    @JsonPropertyDescription("How far Paimon's latest snapshot is ahead of the published one.")
    public Lag lag;

    @JsonPropertyDescription("The catalog roles granted TABLE_READ_DATA on the table.")
    public List<String> readers = new ArrayList<>();

    public String measuredAt;

    /** The published version. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Published {
        public Long snapshot;
        public String metadataLocation;

        @JsonPropertyDescription("When the snapshot was committed in Paimon.")
        public String committedAt;

        @JsonPropertyDescription("When the catalog was pointed at it.")
        public String publishedAt;
    }

    /** The published version's distance from Paimon's latest. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Lag {
        public Long snapshots;
        public Long seconds;
    }
}
