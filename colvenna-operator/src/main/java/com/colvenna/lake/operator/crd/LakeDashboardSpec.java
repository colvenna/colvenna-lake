package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import io.fabric8.generator.annotation.ValidationRule;
import java.util.ArrayList;
import java.util.List;

/** What a user declares about a dashboard. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LakeDashboardSpec {

    @Required
    @Size(min = 1, max = 32)
    @JsonPropertyDescription(
            "The lake tables (database.table) the queries read: this namespace's LakeSource and"
                    + " LakeTable targets. Doris reads them with an identity granted these alone.")
    public List<@Pattern("^[a-z][a-z0-9_]{0,62}\\.[A-Za-z0-9_]{1,64}$") String> tables =
            new ArrayList<>();

    @Required
    @Size(min = 1, max = 32)
    public List<Query> queries = new ArrayList<>();

    @Size(max = 32)
    @JsonPropertyDescription("The Doris users of the dashboard and the queries each may run.")
    public List<User> users = new ArrayList<>();

    @JsonPropertyDescription(
            "The owner and level of the copies the dashboard makes (materialized views, internal"
                    + " tables), at least those of the tables it reads (HLD 12.5).")
    public Governance governance;

    @JsonPropertyDescription("The dashboard's share of Doris, apart from other dashboards'.")
    public Workload workload;

    @JsonPropertyDescription(
            "What its users may see in clear: labelled columns above this level, or in a"
                    + " category not listed, are masked as their labels say. Its governance when"
                    + " not declared, L2 without categories when neither is.")
    public Access access;

    /** A dashboard's clearance: a level and the categories of personal data. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Access {
        @Required
        @Pattern("^L[1-4]$")
        @JsonPropertyDescription(
                "The highest level its users see in clear; at most its governance's.")
        public String level;

        @Size(max = 2)
        @JsonPropertyDescription(
                "The categories of personal data its users see in clear; among its governance's.")
        public List<@Pattern("^(personal|sensitive)$") String> categories = new ArrayList<>();
    }

    /** The dashboard's Doris workload group. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Workload {
        @io.fabric8.generator.annotation.Min(1)
        @io.fabric8.generator.annotation.Max(64)
        @Default("10")
        @JsonPropertyDescription(
                "How many of its queries run at once; more wait in its queue (10 s at most).")
        public Integer maxConcurrency;
    }

    /** One query of the dashboard and how it is served. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @ValidationRule(
            value = "self.serve != 'internal' || (has(self.key) && size(self.key) > 0)",
            message = "an internal query declares its key")
    public static class Query {
        @Required
        @Pattern("^[a-z][a-z0-9_]{0,40}$")
        @JsonPropertyDescription("Its name, the Doris object users query.")
        public String name;

        @Required
        @Pattern("^(external|materialized|internal)$")
        @JsonPropertyDescription(
                "external: a view over the lake tables, read at query time; materialized: an"
                        + " asynchronous materialized view refreshed on a schedule; internal: a"
                        + " Unique Key table rewritten on a schedule, for point lookups (HLD 10.1).")
        public String serve;

        @Required
        @Size(min = 1, max = 20000)
        @JsonPropertyDescription(
                "A SELECT over the declared tables, named database.table as in the lake.")
        public String sql;

        @Pattern("^[1-9][0-9]{0,3}m$")
        @Default("1m")
        @JsonPropertyDescription("materialized and internal: how often the copy is refreshed.")
        public String refresh;

        @Size(max = 8)
        @JsonPropertyDescription("internal: the key columns of the table.")
        public List<@Pattern("^[a-z_][a-z0-9_]{0,63}$") String> key = new ArrayList<>();

        /** Whether it copies the lake into Doris: a materialized view or an internal table. */
        public boolean copies() {
            return !"external".equals(serve);
        }
    }

    /** A Doris user and the queries it may run. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class User {
        @Required
        @Pattern("^[a-z][a-z0-9_]{0,30}$")
        @JsonPropertyDescription(
                "Doris knows it as <namespace>.<dashboard>.<name> (hashed when longer than 64).")
        public String name;

        @Required
        @JsonPropertyDescription("A Secret in this namespace with its password (key password).")
        public SecretKey passwordSecret;

        @Required
        @Size(min = 1, max = 32)
        public List<@Pattern("^[a-z][a-z0-9_]{0,40}$") String> queries = new ArrayList<>();

        @Size(max = 512)
        @JsonPropertyDescription(
                "The rows of its queries it may see: a condition on their columns (comparisons,"
                        + " IN, LIKE, IS NULL, AND, OR, NOT). Once one user of a dashboard has one,"
                        + " a user without one sees no rows.")
        public String rows;

        @JsonPropertyDescription(
                "The person of the web entry's sign-in who is this user in the BI interface: they"
                        + " query the dashboard there as this Doris user, with its password.")
        public Sso sso;
    }

    /** Who signs in to the BI interface as a dashboard user. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Sso {
        @Required
        @Pattern("^[A-Za-z0-9][A-Za-z0-9._@+-]{0,127}$")
        @JsonPropertyDescription("Their user name in the entry's user pool.")
        public String username;
    }

    /** A key of a Secret in the dashboard's namespace. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SecretKey {
        @Required
        @Pattern("^[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?$")
        public String name;

        @Default("password")
        public String key;

        /** The key, {@code password} when none is declared. */
        public String key() {
            return key == null ? "password" : key;
        }
    }
}
