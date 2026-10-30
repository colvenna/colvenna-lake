package com.colvenna.lake.operator.source;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** Reads a source database's prerequisites, tables and, for reconciliation, its keys. */
public interface SourceProbe {

    /** Credentials for one connection. */
    record Login(String user, String password) {}

    /**
     * Where the source is and who reads it; {@code caFile} is the source's CA bundle (PEM), or null
     * when the server is not to be verified; {@code authSource} is MongoDB's database of the user.
     */
    record Target(
            String host,
            int port,
            String database,
            String schema,
            String publication,
            Login replication,
            Login inspector,
            java.nio.file.Path caFile,
            String authSource) {}

    SourceModel.Inventory inventory(Target target) throws SQLException;

    /**
     * Every row's key and version, keyed by the key's text form ({@link Keys#encode}). At most
     * {@code limit} rows; more fails with {@link TooManyRowsException}.
     */
    Map<String, Long> versions(
            Target target,
            SourceModel.Table table,
            List<String> keyColumns,
            String versionColumn,
            long limit)
            throws SQLException;

    /** Versions of just these keys, as they are now; absent keys are deleted. */
    Map<String, Long> versionsOf(
            Target target,
            SourceModel.Table table,
            List<String> keyColumns,
            String versionColumn,
            List<List<String>> keys)
            throws SQLException;

    /**
     * A heartbeat written: the source's time in it, and the operator's clock just before the write
     * was sent and just after it returned, which bound when the source took that time.
     */
    record Written(Instant at, Instant before, Instant after) {}

    /**
     * Writes the source's heartbeat (LLD "监控与数据质量"): row 1 of {@code table}, its time the source's
     * own clock in UTC and its version one up, so that it reaches the lake as any change does.
     */
    default Written heartbeat(Target target, Login login, String table, String versionColumn)
            throws SQLException {
        throw new SQLException("this source type has no heartbeat");
    }

    /**
     * Writes a heartbeat over a connection: an update, and an insert only the first time (the
     * source's key-reserving trigger fires on every insert attempt, a duplicate one included).
     * {@code table} and {@code version} are quoted for the dialect, {@code now} its UTC clock.
     */
    static Written beat(Connection c, String table, String version, String now)
            throws SQLException {
        try (Statement s = c.createStatement()) {
            Instant before = Instant.now();
            int updated =
                    s.executeUpdate(
                            "UPDATE "
                                    + table
                                    + " SET beat_at = "
                                    + now
                                    + ", "
                                    + version
                                    + " = "
                                    + version
                                    + " + 1 WHERE id = 1");
            if (updated == 0) {
                s.executeUpdate(
                        "INSERT INTO "
                                + table
                                + " (id, beat_at, "
                                + version
                                + ") VALUES (1, "
                                + now
                                + ", 1)");
            }
            Instant after = Instant.now();
            try (ResultSet r = s.executeQuery("SELECT beat_at FROM " + table + " WHERE id = 1")) {
                r.next();
                return new Written(
                        r.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC")))
                                .toInstant(),
                        before,
                        after);
            }
        }
    }

    /** The table has more rows than reconciliation reads. */
    final class TooManyRowsException extends SQLException {
        private static final long serialVersionUID = 1L;

        public TooManyRowsException(String message) {
            super(message);
        }
    }
}
