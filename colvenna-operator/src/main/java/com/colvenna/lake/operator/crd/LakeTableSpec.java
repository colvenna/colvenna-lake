package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Max;
import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What a user declares about a layered table. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LakeTableSpec {

    @Required public Target target;

    @Required
    @Size(min = 1, max = 16)
    @JsonPropertyDescription("The target's primary key: columns of the query's output.")
    public List<@Pattern("^[a-z][a-z0-9_]{0,62}$") String> primaryKey = new ArrayList<>();

    @Required
    @Size(min = 1, max = 65536)
    @JsonPropertyDescription(
            "One Flink SQL SELECT over catalog colvenna (database.table names). Name every output"
                    + " column; the output becomes the target's schema.")
    public String query;

    @Required
    @Size(min = 1, max = 32)
    @JsonPropertyDescription(
            "Every table the query reads, as database.table. The job's identity can read these"
                    + " and nothing else; they must be ODS tables of a LakeSource or targets of"
                    + " LakeTables in this namespace.")
    public List<@Pattern("^[a-z][a-z0-9_]{0,62}\\.[a-z][a-z0-9_]{0,62}$") String> upstream =
            new ArrayList<>();

    @Min(1)
    @Max(1024)
    @Default("1")
    public Integer bucket;

    @JsonPropertyDescription("Extra Paimon table options; cannot override the managed ones.")
    public Map<String, String> tableOptions = new LinkedHashMap<>();

    public LakeSourceSpec.Job job;

    @JsonPropertyDescription(
            "The data's owner, level, categories and subject keys (HLD 12.5). Without it the"
                    + " tables are treated as unclassified (temporary L4) and not published.")
    public Governance governance;

    @Size(max = 32)
    @JsonPropertyDescription(
            "Catalog principals the tables are published to (table-level READ), while governed.")
    public List<@Pattern("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$") String> readers = new ArrayList<>();

    @JsonPropertyDescription(
            "Checks of the rows the query yields (LLD 数据质量): a row failing one goes, with the"
                    + " check, to <target>_quarantine instead of the target. Declared with the"
                    + " table: adding or removing them changes the job's graph.")
    public Quality quality;

    @JsonPropertyDescription("Stop the job with a savepoint, keeping its position, until false.")
    public Boolean suspend;

    @Pattern("^(Retain|Delete)$")
    @Default("Retain")
    @JsonPropertyDescription(
            "What happens to the target table when this declaration is deleted: Retain (default)"
                    + " keeps it, Delete drops it unless another LakeTable reads it.")
    public String deletionPolicy;

    /** The declared checks, each of a column of the query's output. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Quality {
        @JsonPropertyDescription("Columns that may not be null.")
        public List<@Pattern("^[a-z][a-z0-9_]{0,62}$") String> notNull = new ArrayList<>();

        @JsonPropertyDescription("Columns and the values they may hold, compared as text.")
        public Map<String, List<String>> allowed = new LinkedHashMap<>();

        @JsonPropertyDescription("Numeric, date or timestamp columns and their bounds, inclusive.")
        public Map<String, Range> ranges = new LinkedHashMap<>();
    }

    /** A column's bounds; either may be left out. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Range {
        public String min;
        public String max;
    }

    /** Where the table lives. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Target {
        @Required
        @Pattern("^[a-z][a-z0-9_]{0,62}$")
        @JsonPropertyDescription(
                "A database of this namespace's layered tables; created if missing. Not a"
                        + " LakeSource's database.")
        public String database;

        @Required
        @Pattern("^[a-z][a-z0-9_]{0,62}$")
        public String table;
    }
}
