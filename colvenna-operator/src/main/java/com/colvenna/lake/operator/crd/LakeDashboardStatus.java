package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** What colvenna-operator observed and did for a dashboard. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LakeDashboardStatus {
    @JsonPropertyDescription(
            "Pending, Ready (every query served), Degraded (some query or user not), or Failed"
                    + " (the declaration cannot be served).")
    public String phase;

    public Long observedGeneration;
    public String message;
    public List<StatusCondition> conditions = new ArrayList<>();

    public CatalogIdentity identity;

    @JsonPropertyDescription("The Doris catalog over the lake, read with the dashboard's identity.")
    public String catalog;

    @JsonPropertyDescription("The Doris database holding the queries' objects.")
    public String database;

    @JsonPropertyDescription("The token the Doris catalog holds (its id).")
    public String catalogTokenId;

    @JsonPropertyDescription("Each query as served.")
    public List<QueryState> queries = new ArrayList<>();

    @JsonPropertyDescription(
            "The Doris users made, each with a seal of its password's hash (an HMAC only the"
                    + " operator can make: it tells a changed password, and nothing about it).")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, String> users = new LinkedHashMap<>();

    @JsonPropertyDescription("When the users' grants were last made again.")
    public String grantedAt;

    @JsonPropertyDescription("The Doris workload group its users and owner run in.")
    public String workloadGroup;

    @JsonPropertyDescription(
            "The declared tables the queries read through a view that masks columns the"
                    + " dashboard is not cleared for, and which.")
    public List<TableExposure> exposure = new ArrayList<>();

    @JsonPropertyDescription("The databases of the views that mask tables, the owner's alone.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<String> shadowDatabases = new ArrayList<>();

    @JsonPropertyDescription("The row policies made for the users, by name: the object of each.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, String> rowPolicies = new LinkedHashMap<>();

    @JsonPropertyDescription("When the operator last measured the dashboard's copies.")
    public String measuredAt;

    /** The query of that name as served, if it is. */
    public Optional<QueryState> query(String name) {
        return queries.stream().filter(q -> name.equals(q.name)).findFirst();
    }

    /** A table read through the view that masks it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TableExposure {
        public String table;

        @JsonPropertyDescription("The view (database.table) the queries read instead.")
        public String view;

        @JsonPropertyDescription("The masked columns, column:mask.")
        public List<String> masked = new ArrayList<>();

        @JsonPropertyDescription("A hash of the view's definition as made.")
        public String applied;
    }

    /** One query as served. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QueryState {
        public String name;
        public String serve;

        @JsonPropertyDescription("The Doris object users query (database.name).")
        public String object;

        @JsonPropertyDescription("What was applied: a hash of the serving, SQL and refresh.")
        public String applied;

        @JsonPropertyDescription(
                "materialized and internal: when the latest refresh that succeeded started; the"
                        + " copy holds the lake as of then.")
        public String refreshedAt;

        @JsonPropertyDescription("How old what the copy holds is: since that refresh started.")
        public Long refreshAgeSeconds;

        public String message;
    }
}
