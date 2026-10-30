package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Max;
import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import io.fabric8.generator.annotation.ValidationRule;
import java.util.ArrayList;
import java.util.List;

/** What a user asks of one task. It runs once: applying it again changes nothing. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@ValidationRule(
        value = "self == oldSelf",
        message = "a task runs once as created; create another task to change it")
public class LakeTaskSpec {
    @Required
    @Pattern("^(backfill|compact|expireSnapshots|removeOrphanFiles|createTag|deleteTag|check)$")
    @JsonPropertyDescription(
            "backfill (recompute a LakeTable's target from its upstream), compact, expireSnapshots,"
                    + " removeOrphanFiles, createTag, deleteTag or check (run the table's"
                    + " LakeQuality rules).")
    public String type;

    @Required public Table table;

    public Compact compact;
    public ExpireSnapshots expireSnapshots;
    public RemoveOrphanFiles removeOrphanFiles;
    public CreateTag createTag;
    public DeleteTag deleteTag;
    public Check check;

    @Min(0)
    @Max(5)
    @Default("2")
    @JsonPropertyDescription("Attempts after the first when the task's run fails.")
    public Integer retries;

    @Pattern("^[1-9][0-9]{0,3}(m|h)$")
    @Default("1h")
    @JsonPropertyDescription(
            "How long one attempt may run; a backfill's wait for its readers counts separately.")
    public String timeout;

    public LakeSourceSpec.Resources resources;

    /** A table of this namespace: a LakeSource's ODS table or a LakeTable's target. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Table {
        @Required
        @Pattern("^[a-z][a-z0-9_]{0,62}$")
        public String database;

        @Required
        @Pattern("^[a-z][a-z0-9_]{0,62}$")
        public String table;

        /** {@code database.table}. */
        public String qualifiedName() {
            return database + "." + table;
        }
    }

    /** Rewrite small files (HLD 8.3); a full compaction merges every level. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Compact {
        @Default("false")
        public Boolean full;

        @JsonPropertyDescription(
                "With full: also rewrite the manifests, which still list the rewritten files with"
                        + " the minimum and maximum of their columns. Unless given, they are"
                        + " rewritten for a table that keeps no Iceberg metadata and left for one"
                        + " that does: rewriting them breaks its chain of Iceberg metadata, which"
                        + " then misses a primary-key table's uncompacted buckets until the next"
                        + " full compaction. An erasure's compaction always rewrites them.")
        public Boolean manifests;

        @Size(max = 100)
        @JsonPropertyDescription(
                "Only these partitions, each as column=value[,column=value]; all unless given.")
        public List<
                        @Pattern(
                                "^[a-z][a-z0-9_]{0,62}=[A-Za-z0-9_.:-]{1,64}(,[a-z][a-z0-9_]{0,62}=[A-Za-z0-9_.:-]{1,64}){0,3}$")
                        String>
                partitions = new ArrayList<>();
    }

    /**
     * Expire snapshots. Paimon keeps every snapshot a reader's consumer still needs, and every
     * tagged one.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ExpireSnapshots {
        @Min(1)
        @Default("10")
        @JsonPropertyDescription("Snapshots kept whatever their age.")
        public Integer retainMin;

        @Pattern("^[1-9][0-9]{0,4}(m|h|d)$")
        @Default("1h")
        @JsonPropertyDescription("Snapshots younger than this are kept.")
        public String olderThan;
    }

    /** Delete files no snapshot, tag or branch refers to. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RemoveOrphanFiles {
        @Pattern("^[1-9][0-9]{0,3}d$")
        @Default("1d")
        @JsonPropertyDescription(
                "Only files older than this, at least a day: a writer's uncommitted files are"
                        + " younger.")
        public String olderThan;
    }

    /** Delete a tag, for example one keeping erased personal data. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DeleteTag {
        @Required
        @Pattern("^[A-Za-z0-9][A-Za-z0-9 ._:-]{0,99}$")
        @JsonPropertyDescription("The tag, one a task made or one Paimon made (2026-10-15 10).")
        public String name;
    }

    /** Tag the table's latest snapshot, for audit and replay (HLD 11.3). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CreateTag {
        @Required
        @Pattern("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        public String name;

        @Pattern("^[1-9][0-9]{0,2}(h|d)$")
        @Default("7d")
        @JsonPropertyDescription("How long the tag keeps its snapshot, at most 999 days.")
        public String timeRetained;
    }

    /** One batch quality check: the table's LakeQuality rules on one snapshot. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Check {
        @Size(max = 100)
        @JsonPropertyDescription(
                "Only these partitions, each as column=value[,column=value]; the whole table"
                        + " unless given.")
        public List<
                        @Pattern(
                                "^[a-z][a-z0-9_]{0,62}=[A-Za-z0-9_.:-]{1,64}(,[a-z][a-z0-9_]{0,62}=[A-Za-z0-9_.:-]{1,64}){0,3}$")
                        String>
                partitions = new ArrayList<>();

        @Min(1)
        @JsonPropertyDescription(
                "The snapshot to check; the latest when the task starts unless" + " given.")
        public Long snapshot;

        @Pattern("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z$")
        @JsonPropertyDescription(
                "The time freshness is measured at (UTC); when the task starts unless given. With"
                        + " snapshot, it repeats an earlier check exactly.")
        public String asOf;
    }
}
