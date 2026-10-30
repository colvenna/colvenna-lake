package com.colvenna.lake.operator.source;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** A MySQL source over TLS, read through information_schema. */
public final class MySqlProbe implements SourceProbe {

    static Connection connect(Target target, Login login) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", login.user());
        props.setProperty("password", login.password());
        if (target.caFile() == null) {
            props.setProperty("sslMode", "REQUIRED");
        } else {
            props.setProperty("sslMode", "VERIFY_IDENTITY");
            props.setProperty(
                    "trustCertificateKeyStoreUrl", Tls.pkcs12(target.caFile()).toUri().toString());
            props.setProperty("trustCertificateKeyStoreType", "PKCS12");
            props.setProperty("trustCertificateKeyStorePassword", Tls.STORE_PASSWORD);
            props.setProperty("fallbackToSystemTrustStore", "false");
        }
        props.setProperty("connectTimeout", "10000");
        props.setProperty("socketTimeout", "300000");
        props.setProperty("useCursorFetch", "true");
        return DriverManager.getConnection(
                "jdbc:mysql://" + target.host() + ":" + target.port() + "/" + target.database(),
                props);
    }

    @Override
    public SourceModel.Inventory inventory(Target target) throws SQLException {
        List<SourceModel.Check> checks = new ArrayList<>();
        Map<String, SourceModel.Table> tables = new TreeMap<>();
        try (Connection c = connect(target, target.replication())) {
            try (Statement s = c.createStatement();
                    ResultSet r =
                            s.executeQuery(
                                    "SELECT @@log_bin, @@binlog_format, @@binlog_row_image,"
                                            + " @@version")) {
                r.next();
                checks.add(
                        new SourceModel.Check(
                                "log_bin", r.getInt(1) == 1, "log_bin=" + r.getString(1)));
                checks.add(
                        new SourceModel.Check(
                                "binlog_format",
                                "ROW".equalsIgnoreCase(r.getString(2)),
                                "binlog_format=" + r.getString(2)));
                checks.add(
                        new SourceModel.Check(
                                "binlog_row_image",
                                "FULL".equalsIgnoreCase(r.getString(3)),
                                "binlog_row_image=" + r.getString(3)));
            }
            Map<String, List<SourceModel.Column>> columns = new LinkedHashMap<>();
            for (String table :
                    strings(
                            c,
                            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE"
                                    + " TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY"
                                    + " TABLE_NAME",
                            target.database())) {
                columns.put(table, new ArrayList<>());
            }
            try (PreparedStatement p =
                    c.prepareStatement(
                            "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE"
                                    + " FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ?"
                                    + " ORDER BY TABLE_NAME, ORDINAL_POSITION")) {
                p.setString(1, target.database());
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        List<SourceModel.Column> list = columns.get(r.getString(1));
                        if (list != null) {
                            list.add(
                                    new SourceModel.Column(
                                            r.getString(2),
                                            r.getString(3).toLowerCase(Locale.ROOT),
                                            r.getString(4).toLowerCase(Locale.ROOT),
                                            "YES".equals(r.getString(5))));
                        }
                    }
                }
            }
            Map<String, List<String>> keys =
                    grouped(
                            c,
                            "SELECT TABLE_NAME, COLUMN_NAME FROM"
                                    + " information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = ?"
                                    + " AND CONSTRAINT_NAME = 'PRIMARY' ORDER BY TABLE_NAME,"
                                    + " ORDINAL_POSITION",
                            target.database());
            Map<String, List<String>> checksByTable =
                    grouped(
                            c,
                            "SELECT tc.TABLE_NAME, cc.CHECK_CLAUSE FROM"
                                    + " information_schema.TABLE_CONSTRAINTS tc JOIN"
                                    + " information_schema.CHECK_CONSTRAINTS cc ON"
                                    + " cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND"
                                    + " cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME WHERE"
                                    + " tc.TABLE_SCHEMA = ? AND tc.CONSTRAINT_TYPE = 'CHECK' AND"
                                    + " tc.ENFORCED = 'YES'",
                            target.database());
            Map<String, List<SourceModel.Trigger>> triggers = new HashMap<>();
            Login inspector =
                    target.inspector() == null ? target.replication() : target.inspector();
            try (Connection i = target.inspector() == null ? null : connect(target, inspector)) {
                Connection reader = i == null ? c : i;
                try (PreparedStatement p =
                        reader.prepareStatement(
                                "SELECT EVENT_OBJECT_TABLE, ACTION_TIMING, EVENT_MANIPULATION,"
                                        + " ACTION_STATEMENT FROM information_schema.TRIGGERS"
                                        + " WHERE TRIGGER_SCHEMA = ?")) {
                    p.setString(1, target.database());
                    try (ResultSet r = p.executeQuery()) {
                        while (r.next()) {
                            triggers.computeIfAbsent(r.getString(1), t -> new ArrayList<>())
                                    .add(
                                            new SourceModel.Trigger(
                                                    r.getString(2).toUpperCase(Locale.ROOT),
                                                    r.getString(3).toUpperCase(Locale.ROOT),
                                                    r.getString(4)));
                        }
                    }
                }
                // Triggers are shown only to users with the TRIGGER privilege on the table.
                boolean visible = canSeeTriggers(reader, target.database());
                for (var e : columns.entrySet()) {
                    tables.put(
                            e.getKey(),
                            new SourceModel.Table(
                                    e.getKey(),
                                    List.copyOf(e.getValue()),
                                    keys.getOrDefault(e.getKey(), List.of()),
                                    checksByTable.getOrDefault(e.getKey(), List.of()),
                                    triggers.getOrDefault(e.getKey(), List.of()),
                                    visible,
                                    null));
                }
            }
        }
        return new SourceModel.Inventory(checks, tables);
    }

    private static boolean canSeeTriggers(Connection c, String database) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {
            while (r.next()) {
                String grant = r.getString(1).toUpperCase(Locale.ROOT);
                boolean onDatabase =
                        grant.contains(" ON *.*")
                                || grant.contains(
                                        " ON `" + database.toUpperCase(Locale.ROOT) + "`.*");
                if (onDatabase && (grant.contains("ALL PRIVILEGES") || grant.contains("TRIGGER"))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Map<String, Long> versions(
            Target target,
            SourceModel.Table table,
            List<String> keyColumns,
            String versionColumn,
            long limit)
            throws SQLException {
        Map<String, Long> out = new HashMap<>();
        try (Connection c = connect(target, target.replication());
                Statement s = c.createStatement()) {
            s.setFetchSize(10_000);
            try (ResultSet r =
                    s.executeQuery(
                            "SELECT "
                                    + columnList(keyColumns)
                                    + ", `"
                                    + versionColumn
                                    + "` FROM `"
                                    + table.name()
                                    + "`")) {
                int n = keyColumns.size();
                while (r.next()) {
                    if (out.size() >= limit) {
                        throw new TooManyRowsException(
                                table.name() + " has more than " + limit + " rows");
                    }
                    List<String> key = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) {
                        key.add(r.getString(i));
                    }
                    out.put(Keys.encode(key), r.getLong(n + 1));
                }
            }
        }
        return out;
    }

    @Override
    public Written heartbeat(Target target, Login login, String table, String versionColumn)
            throws SQLException {
        try (Connection c = connect(target, login)) {
            return SourceProbe.beat(
                    c,
                    "`" + table.replace("`", "``") + "`",
                    "`" + versionColumn.replace("`", "``") + "`",
                    "UTC_TIMESTAMP(6)");
        }
    }

    @Override
    public Map<String, Long> versionsOf(
            Target target,
            SourceModel.Table table,
            List<String> keyColumns,
            String versionColumn,
            List<List<String>> keys)
            throws SQLException {
        Map<String, Long> out = new HashMap<>();
        String where =
                String.join(" AND ", keyColumns.stream().map(k -> "`" + k + "` = ?").toList());
        try (Connection c = connect(target, target.replication());
                PreparedStatement p =
                        c.prepareStatement(
                                "SELECT `"
                                        + versionColumn
                                        + "` FROM `"
                                        + table.name()
                                        + "` WHERE "
                                        + where)) {
            for (List<String> key : keys) {
                for (int i = 0; i < key.size(); i++) {
                    p.setString(i + 1, key.get(i));
                }
                try (ResultSet r = p.executeQuery()) {
                    if (r.next()) {
                        out.put(Keys.encode(key), r.getLong(1));
                    }
                }
            }
        }
        return out;
    }

    private static String columnList(List<String> columns) {
        return String.join(", ", columns.stream().map(k -> "`" + k + "`").toList());
    }

    private static List<String> strings(Connection c, String sql, String arg) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, arg);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    out.add(r.getString(1));
                }
            }
        }
        return out;
    }

    private static Map<String, List<String>> grouped(Connection c, String sql, String arg)
            throws SQLException {
        Map<String, List<String>> out = new HashMap<>();
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, arg);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    out.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(r.getString(2));
                }
            }
        }
        return out;
    }
}
