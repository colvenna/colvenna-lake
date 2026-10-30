package com.colvenna.lake.operator.source;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** What a probe reads from a source database. */
public final class SourceModel {
    private SourceModel() {}

    /** A column, with its base type lowercased ({@code varchar}, {@code bigint}, ...). */
    public record Column(String name, String type, String fullType, boolean nullable) {}

    /** A trigger: when it fires and what it does (statement or function body). */
    public record Trigger(String timing, String event, String body) {}

    /**
     * One source table and what admission needs to know about it; {@code sourceId} is the source
     * object's own identity where it has one (a MongoDB collection's UUID), else null.
     */
    public record Table(
            String name,
            List<Column> columns,
            List<String> primaryKey,
            List<String> checkClauses,
            List<Trigger> triggers,
            boolean triggersVisible,
            Postgres postgres,
            String sourceId) {

        public Table(
                String name,
                List<Column> columns,
                List<String> primaryKey,
                List<String> checkClauses,
                List<Trigger> triggers,
                boolean triggersVisible,
                Postgres postgres) {
            this(
                    name,
                    columns,
                    primaryKey,
                    checkClauses,
                    triggers,
                    triggersVisible,
                    postgres,
                    null);
        }

        public Optional<Column> column(String name) {
            return columns.stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst();
        }
    }

    /**
     * PostgreSQL facts: the tables the job reads (the table itself, or its leaf partitions), their
     * replica identities and publication membership.
     */
    public record Postgres(
            boolean partitioned,
            boolean multiLevel,
            List<String> readTables,
            Map<String, Character> replicaIdentity,
            List<String> notInPublication) {}

    /** A server-level prerequisite and whether it holds. */
    public record Check(String name, boolean ok, String detail) {}

    /**
     * How far back the source's change log reaches (MongoDB's oplog), or why that is unknown; a job
     * down longer than this cannot resume.
     */
    public record Retention(Double windowHours, String detail) {}

    /** Everything one probe read. */
    public record Inventory(List<Check> checks, Map<String, Table> tables, Retention retention) {
        public Inventory(List<Check> checks, Map<String, Table> tables) {
            this(checks, tables, null);
        }

        public boolean ready() {
            return checks.stream().allMatch(Check::ok);
        }

        public String failedChecks() {
            return String.join(
                    "; ",
                    checks.stream()
                            .filter(c -> !c.ok())
                            .map(c -> c.name() + ": " + c.detail())
                            .toList());
        }
    }
}
