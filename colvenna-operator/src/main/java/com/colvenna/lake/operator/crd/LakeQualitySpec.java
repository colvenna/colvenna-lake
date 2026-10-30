package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import io.fabric8.generator.annotation.ValidationRule;
import java.util.ArrayList;
import java.util.List;

/** A table's batch quality rules and whether their checks gate its publication. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LakeQualitySpec {
    @Required
    @JsonPropertyDescription(
            "A table of this namespace: a LakeSource's ODS table or a LakeTable's target.")
    public LakeTaskSpec.Table table;

    @Required
    @Size(min = 1, max = 20)
    public List<Rule> rules = new ArrayList<>();

    public Gate gate;

    /** One rule: exactly one measure. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @ValidationRule(
            value =
                    "[has(self.notNull), has(self.unique), has(self.rowCount),"
                            + " has(self.freshness)].filter(x, x).size() == 1",
            message = "a rule has exactly one of notNull, unique, rowCount and freshness")
    public static class Rule {
        @Required
        @Pattern("^[a-z][a-z0-9-]{0,39}$")
        @JsonPropertyDescription("Its name, unique among the table's rules.")
        public String name;

        public NotNull notNull;
        public Unique unique;
        public RowCount rowCount;
        public Freshness freshness;
    }

    /** The share of rows whose column is not null. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NotNull {
        @Required
        @Pattern("^[a-z_][a-z0-9_]{0,62}$")
        public String column;

        @Pattern("^(0(\\.[0-9]{1,9})?|1(\\.0{1,9})?)$")
        @Default("1")
        @JsonPropertyDescription("The least share of non-null rows, a decimal from 0 to 1.")
        public String minRatio;
    }

    /** No two rows with the same values in these columns (rows with a null among them aside). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Unique {
        @Required
        @Size(min = 1, max = 8)
        public List<@Pattern("^[a-z_][a-z0-9_]{0,62}$") String> columns = new ArrayList<>();
    }

    /** How many rows the checked range holds. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @ValidationRule(
            value = "has(self.min) || has(self.max)",
            message = "a rowCount rule gives min, max or both")
    public static class RowCount {
        @Min(0)
        public Long min;

        @Min(0)
        public Long max;
    }

    /** How old the newest row of the table is at the check's asOf. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Freshness {
        @Required
        @Pattern("^[a-z_][a-z0-9_]{0,62}$")
        @JsonPropertyDescription(
                "A TIMESTAMP (read as UTC), TIMESTAMP_LTZ or BIGINT (epoch milliseconds) column.")
        public String column;

        @Required
        @Pattern("^[1-9][0-9]{0,4}(m|h|d)$")
        public String maxAge;
    }

    /** What the checks' results hold back. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Gate {
        @Default("false")
        @JsonPropertyDescription(
                "Publish the table (readers, dashboards) only while its gate is open.")
        public Boolean publish;

        @Pattern("^[1-9][0-9]{0,4}(m|h|d)$")
        @JsonPropertyDescription(
                "The gate closes once the newest check is older than this; unset, it does not.")
        public String maxAge;
    }

    /** Whether the gate holds back publication. */
    public boolean publishes() {
        return gate != null && Boolean.TRUE.equals(gate.publish);
    }
}
