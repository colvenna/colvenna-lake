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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A PostgreSQL source over TLS, read through the system catalogs. Only top-level tables are listed;
 * a partitioned table is read from its leaf partitions (one level), as verified
 * (docs/history/m1/postgres-cdc.md).
 */
public final class PostgresProbe implements SourceProbe {
    /** pg_get_triggerdef: CREATE TRIGGER t BEFORE INSERT OR UPDATE ON s.t FOR EACH ROW ... */
    private static final Pattern TRIGGER =
            Pattern.compile(
                    "^CREATE (?:CONSTRAINT )?TRIGGER \\S+ (BEFORE|AFTER|INSTEAD OF) (.+?) ON ");

    static Connection connect(Target target, Login login) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", login.user());
        props.setProperty("password", login.password());
        if (target.caFile() == null) {
            props.setProperty("sslmode", "require");
        } else {
            props.setProperty("sslmode", "verify-full");
            props.setProperty("sslrootcert", target.caFile().toString());
        }
        props.setProperty("connectTimeout", "10");
        props.setProperty("socketTimeout", "300");
        return DriverManager.getConnection(
                "jdbc:postgresql://"
                        + target.host()
                        + ":"
                        + target.port()
                        + "/"
                        + target.database(),
                props);
    }

    @Override
    public SourceModel.Inventory inventory(Target target) throws SQLException {
        List<SourceModel.Check> checks = new ArrayList<>();
        Map<String, SourceModel.Table> tables = new TreeMap<>();
        try (Connection c = connect(target, target.replication())) {
            String wal = single(c, "SHOW wal_level");
            checks.add(
                    new SourceModel.Check("wal_level", "logical".equals(wal), "wal_level=" + wal));
            boolean replication =
                    "t"
                            .equals(
                                    single(
                                                    c,
                                                    "SELECT (r.rolreplication OR EXISTS (SELECT 1 FROM pg_roles g"
                                                            + " WHERE g.rolname = 'rds_replication' AND"
                                                            + " pg_has_role(current_user, g.oid, 'member')))::text"
                                                            + " FROM pg_roles r WHERE r.rolname = current_user")
                                            .substring(0, 1));
            checks.add(
                    new SourceModel.Check(
                            "replication_role",
                            replication,
                            replication
                                    ? "the user may replicate"
                                    : "the user has neither REPLICATION nor rds_replication"));
            boolean viaRoot = false;
            boolean publication = false;
            try (PreparedStatement p =
                    c.prepareStatement("SELECT pubviaroot FROM pg_publication WHERE pubname = ?")) {
                p.setString(1, target.publication());
                try (ResultSet r = p.executeQuery()) {
                    if (r.next()) {
                        publication = true;
                        viaRoot = r.getBoolean(1);
                    }
                }
            }
            checks.add(
                    new SourceModel.Check(
                            "publication",
                            publication && !viaRoot,
                            !publication
                                    ? "publication " + target.publication() + " does not exist"
                                    : viaRoot
                                            ? "publish_via_partition_root must be false"
                                            : "publication " + target.publication()));
            List<String> published = new ArrayList<>();
            try (PreparedStatement p =
                    c.prepareStatement(
                            "SELECT tablename FROM pg_publication_tables WHERE pubname = ? AND"
                                    + " schemaname = ?")) {
                p.setString(1, target.publication());
                p.setString(2, target.schema());
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        published.add(r.getString(1));
                    }
                }
            }

            Map<String, String[]> relations = new LinkedHashMap<>();
            try (PreparedStatement p =
                    c.prepareStatement(
                            "SELECT c.relname, c.relkind::text, c.relreplident::text FROM pg_class"
                                    + " c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE"
                                    + " n.nspname = ? AND c.relkind IN ('r', 'p') AND NOT"
                                    + " c.relispartition ORDER BY c.relname")) {
                p.setString(1, target.schema());
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        relations.put(
                                r.getString(1), new String[] {r.getString(2), r.getString(3)});
                    }
                }
            }
            for (var e : relations.entrySet()) {
                String name = e.getKey();
                String qualified = quote(target.schema()) + "." + quote(name);
                boolean partitioned = e.getValue()[0].equals("p");
                Map<String, Character> identity = new LinkedHashMap<>();
                identity.put(name, e.getValue()[1].charAt(0));
                List<String> read = new ArrayList<>();
                boolean multiLevel = false;
                if (partitioned) {
                    try (PreparedStatement p =
                            c.prepareStatement(
                                    "SELECT c.relname, c.relkind::text, c.relreplident::text FROM"
                                            + " pg_inherits i JOIN pg_class c ON c.oid ="
                                            + " i.inhrelid WHERE i.inhparent = ?::regclass ORDER"
                                            + " BY c.relname")) {
                        p.setString(1, qualified);
                        try (ResultSet r = p.executeQuery()) {
                            while (r.next()) {
                                read.add(r.getString(1));
                                identity.put(r.getString(1), r.getString(3).charAt(0));
                                multiLevel |= r.getString(2).equals("p");
                            }
                        }
                    }
                } else {
                    read.add(name);
                }
                List<String> missing = new ArrayList<>();
                for (String t : read) {
                    if (!published.contains(t)) {
                        missing.add(t);
                    }
                }
                tables.put(
                        name,
                        new SourceModel.Table(
                                name,
                                columns(c, target.schema(), name),
                                primaryKey(c, qualified),
                                strings(
                                        c,
                                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                                + " WHERE conrelid = ?::regclass AND contype ="
                                                + " 'c'",
                                        qualified),
                                triggers(c, qualified),
                                true,
                                new SourceModel.Postgres(
                                        partitioned,
                                        multiLevel,
                                        List.copyOf(read),
                                        identity,
                                        List.copyOf(missing))));
            }
        }
        return new SourceModel.Inventory(checks, tables);
    }

    private static List<SourceModel.Column> columns(Connection c, String schema, String table)
            throws SQLException {
        List<SourceModel.Column> out = new ArrayList<>();
        try (PreparedStatement p =
                c.prepareStatement(
                        "SELECT column_name, udt_name, data_type, is_nullable FROM"
                                + " information_schema.columns WHERE table_schema = ? AND"
                                + " table_name = ? ORDER BY ordinal_position")) {
            p.setString(1, schema);
            p.setString(2, table);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    out.add(
                            new SourceModel.Column(
                                    r.getString(1),
                                    r.getString(2).toLowerCase(Locale.ROOT),
                                    r.getString(3).toLowerCase(Locale.ROOT),
                                    "YES".equals(r.getString(4))));
                }
            }
        }
        return out;
    }

    private static List<String> primaryKey(Connection c, String qualified) throws SQLException {
        return strings(
                c,
                "SELECT a.attname FROM pg_index i CROSS JOIN LATERAL unnest(i.indkey) WITH"
                        + " ORDINALITY AS k(attnum, ord) JOIN pg_attribute a ON a.attrelid ="
                        + " i.indrelid AND a.attnum = k.attnum WHERE i.indrelid = ?::regclass AND"
                        + " i.indisprimary ORDER BY k.ord",
                qualified);
    }

    private static List<SourceModel.Trigger> triggers(Connection c, String qualified)
            throws SQLException {
        List<SourceModel.Trigger> out = new ArrayList<>();
        try (PreparedStatement p =
                c.prepareStatement(
                        "SELECT pg_get_triggerdef(t.oid), p.prosrc FROM pg_trigger t JOIN pg_proc"
                                + " p ON p.oid = t.tgfoid WHERE t.tgrelid = ?::regclass AND NOT"
                                + " t.tgisinternal")) {
            p.setString(1, qualified);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    Matcher m = TRIGGER.matcher(r.getString(1).toUpperCase(Locale.ROOT));
                    if (!m.find()) {
                        continue;
                    }
                    String timing = m.group(1).startsWith("INSTEAD") ? "INSTEAD" : m.group(1);
                    for (String event : m.group(2).split(" OR ")) {
                        // "UPDATE OF col, ..." fires on those columns only: not a guard.
                        String e = event.trim();
                        if (e.equals("INSERT") || e.equals("UPDATE") || e.equals("DELETE")) {
                            out.add(new SourceModel.Trigger(timing, e, r.getString(2)));
                        }
                    }
                }
            }
        }
        return out;
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
        try (Connection c = connect(target, target.replication())) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.setFetchSize(10_000);
                try (ResultSet r =
                        s.executeQuery(
                                "SELECT "
                                        + columnList(keyColumns)
                                        + ", "
                                        + quote(versionColumn)
                                        + " FROM "
                                        + quote(target.schema())
                                        + "."
                                        + quote(table.name()))) {
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
            } finally {
                c.rollback();
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
                    quote(target.schema()) + "." + quote(table),
                    quote(versionColumn),
                    "now() AT TIME ZONE 'UTC'");
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
        // Compare in the key's own type, so the primary key index serves each lookup.
        String where =
                String.join(
                        " AND ",
                        keyColumns.stream()
                                .map(
                                        k ->
                                                quote(k)
                                                        + " = CAST(? AS "
                                                        + table.column(k).orElseThrow().type()
                                                        + ")")
                                .toList());
        try (Connection c = connect(target, target.replication());
                PreparedStatement p =
                        c.prepareStatement(
                                "SELECT "
                                        + quote(versionColumn)
                                        + " FROM "
                                        + quote(target.schema())
                                        + "."
                                        + quote(table.name())
                                        + " WHERE "
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

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String columnList(List<String> columns) {
        return String.join(", ", columns.stream().map(PostgresProbe::quote).toList());
    }

    private static String single(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
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
}
