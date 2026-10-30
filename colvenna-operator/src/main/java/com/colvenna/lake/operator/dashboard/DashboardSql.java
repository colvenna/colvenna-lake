package com.colvenna.lake.operator.dashboard;

import com.colvenna.lake.operator.crd.LakeDashboard;
import com.colvenna.lake.operator.crd.LakeDashboardSpec;
import com.colvenna.lake.operator.governance.Exposure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Doris names and statements of a dashboard (LLD "看板服务"). Pure: what the reconciler sends,
 * testable without a Doris.
 */
public final class DashboardSql {
    private DashboardSql() {}

    /** How each query is served, and how its object is dropped (Doris refuses the wrong kind). */
    public enum Serve {
        EXTERNAL("DROP VIEW IF EXISTS %s"),
        MATERIALIZED("DROP MATERIALIZED VIEW IF EXISTS %s"),
        INTERNAL("DROP TABLE IF EXISTS %s FORCE");

        private final String drop;

        Serve(String drop) {
            this.drop = drop;
        }

        public static Serve of(String serve) {
            return valueOf(serve.toUpperCase(Locale.ROOT));
        }

        /** Whether it copies the lake into Doris (a materialized view, an internal table). */
        public boolean copies() {
            return this != EXTERNAL;
        }
    }

    /** Doris keeps identifiers to 64 characters. */
    private static final int NAME_LIMIT = 64;

    /** The dashboard's catalog over the lake: its own, read with its own identity. */
    public static String catalog(LakeDashboard d) {
        return name("lake", d);
    }

    /** The Doris database holding the dashboard's views, materialized views and tables. */
    public static String database(LakeDashboard d) {
        return name("dash", d);
    }

    /**
     * The Doris user that owns and builds the dashboard's objects: it may read the dashboard's own
     * catalog and write its own database, nothing else, so a query reads only what it declares.
     */
    public static String owner(LakeDashboard d) {
        return "lakedashboard-" + d.getMetadata().getUid().replace("-", "");
    }

    /** The dashboard's workload group: its queries' concurrency, apart from other dashboards'. */
    public static String workloadGroup(LakeDashboard d) {
        return name("wg", d);
    }

    /**
     * How Doris knows a dashboard user: {@code <namespace>.<dashboard>.<name>}, unique: neither a
     * namespace nor a user's name holds a dot. Hashed when longer than Doris allows.
     */
    public static String user(LakeDashboard d, String name) {
        String ns = d.getMetadata().getNamespace();
        String board = d.getMetadata().getName();
        String out = ns + "." + board + "." + name;
        return out.length() <= NAME_LIMIT ? out : "u-" + sha(ns + "/" + board) + "." + name;
    }

    /** {@code <prefix>_<namespace>_<name>_<uid prefix>}, hashed if it would not fit Doris's 64. */
    private static String name(String prefix, LakeDashboard d) {
        String ns = token(d.getMetadata().getNamespace());
        String name = token(d.getMetadata().getName());
        String out = prefix + "_" + ns + "_" + name + "_" + uid(d);
        return out.length() <= NAME_LIMIT
                ? out
                : prefix + "_" + sha(ns + "/" + name) + "_" + uid(d);
    }

    /** Schemas a dashboard's query may not name: Doris's own, whatever its owner may read. */
    private static final Pattern SYSTEM =
            Pattern.compile(
                    "(?i)(?<![\\w`])`?(__internal_schema|information_schema|mysql)`?\\s*\\.");

    /** Whether the query names one of Doris's own schemas (outside its quoted text). */
    public static boolean namesSystemSchema(String sql) {
        return SYSTEM.matcher(code(sql)).find();
    }

    /**
     * Whether the query names the dashboard's catalog itself, anywhere, its quoted text included (a
     * table function takes table names as strings): its owner reads the catalog, so a table named
     * there is read as it is, past the views that mask it.
     */
    public static boolean namesCatalog(String sql, String catalog) {
        return sql.toLowerCase(java.util.Locale.ROOT)
                .contains(catalog.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Whether the query names a table with a {@code $} (outside its quoted text): Paimon's system
     * tables ({@code orders$audit_log}, {@code orders$files}) hold the table's rows or statistics
     * unmasked.
     */
    public static boolean namesSystemTable(String sql) {
        return code(sql).contains("$");
    }

    /** Doris's table functions: they read files, metadata or other systems, not declared tables. */
    private static final Pattern TABLE_FUNCTION =
            Pattern.compile(
                    "(?i)(?<![\\w`.])(s3|hdfs|http|http_stream|local|file|query|paimon_meta"
                            + "|iceberg_meta|hudi_meta|parquet_[a-z_]+|partitions|partition_values"
                            + "|mv_infos|jobs|tasks|backends|frontends|frontends_disks|catalogs"
                            + "|group_commit|numbers|vector_search)\\s*\\(");

    /** Whether the query calls one of Doris's table functions (outside its quoted text). */
    public static boolean callsTableFunction(String sql) {
        return TABLE_FUNCTION.matcher(code(sql)).find();
    }

    /**
     * The query with each declared lake table, written {@code database.table} (either part
     * backticked or not), named in the dashboard's catalog. Quoted text and comments are left
     * alone, and so is a name already qualified (after a dot).
     */
    public static String qualify(String sql, List<String> tables, String catalog) {
        return qualify(sql, tables, catalog, Map.of());
    }

    /**
     * As {@link #qualify(String, List, String)}, a table with masked columns named by the view that
     * masks them instead ({@code shadows}: table to the database of its view, which has the table's
     * name, so a column written {@code table.column} still resolves).
     */
    public static String qualify(
            String sql, List<String> tables, String catalog, Map<String, String> shadows) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            int end = skip(sql, i);
            if (end > i) {
                out.append(sql, i, end);
                i = end;
                continue;
            }
            boolean start =
                    (Character.isLetter(c) || c == '_' || c == '`')
                            && (i == 0 || !isNamePart(sql.charAt(i - 1)));
            String match = start ? tableAt(sql, i, tables) : null;
            if (match == null && c == '`') {
                // A backticked name is copied whole: a quote inside it starts no string.
                int close = backticked(sql, i);
                out.append(sql, i, close);
                i = close;
                continue;
            }
            if (match != null) {
                String[] parts = match.split("\\.", 2);
                if (shadows.containsKey(match)) {
                    out.append(quote(shadows.get(match))).append('.').append(quote(parts[1]));
                } else {
                    out.append(quote(catalog))
                            .append('.')
                            .append(quote(parts[0]))
                            .append('.')
                            .append(quote(parts[1]));
                }
                i += matchLength(sql, i, parts);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** The declared tables the query names (outside its quoted text and comments). */
    public static List<String> tablesRead(String sql, List<String> tables) {
        Set<String> out = new LinkedHashSet<>();
        String code = code(sql);
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if ((Character.isLetter(c) || c == '_' || c == '`')
                    && (i == 0 || !isNamePart(code.charAt(i - 1)))) {
                String match = tableAt(code, i, tables);
                if (match != null) {
                    out.add(match);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** The end of a quoted string or comment starting at {@code i}, or {@code i} if none does. */
    private static int skip(String sql, int i) {
        char c = sql.charAt(i);
        if (c == '\'' || c == '"') {
            int j = i + 1;
            while (j < sql.length()) {
                if (sql.charAt(j) == '\\') {
                    j += 2;
                } else if (sql.charAt(j) == c) {
                    return j + 1;
                } else {
                    j++;
                }
            }
            return sql.length();
        }
        if (sql.startsWith("--", i)) {
            int j = sql.indexOf('\n', i);
            return j < 0 ? sql.length() : j;
        }
        if (sql.startsWith("/*", i)) {
            int j = sql.indexOf("*/", i + 2);
            return j < 0 ? sql.length() : j + 2;
        }
        return i;
    }

    /** The end of the backticked name at {@code i} (a doubled backtick is one inside it). */
    private static int backticked(String sql, int i) {
        int j = i + 1;
        while (j < sql.length()) {
            if (sql.charAt(j) == '`') {
                if (j + 1 < sql.length() && sql.charAt(j + 1) == '`') {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return sql.length();
    }

    /** The query without its quoted text and comments, for what it names. */
    private static String code(String sql) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            if (sql.charAt(i) == '`') {
                int close = backticked(sql, i);
                out.append(sql, i, close);
                i = close;
                continue;
            }
            int end = skip(sql, i);
            if (end > i) {
                out.append(' ');
                i = end;
            } else {
                out.append(sql.charAt(i++));
            }
        }
        return out.toString();
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '`' || c == '.';
    }

    /** The declared table named at {@code i}, if one is. */
    private static String tableAt(String sql, int i, List<String> tables) {
        for (String table : tables) {
            String[] parts = table.split("\\.", 2);
            if (matchLength(sql, i, parts) > 0) {
                return table;
            }
        }
        return null;
    }

    /** How long {@code db.table} (each part optionally backticked) is at {@code i}, or 0. */
    private static int matchLength(String sql, int i, String[] parts) {
        int j = name(sql, i, parts[0]);
        if (j < 0) {
            return 0;
        }
        while (j < sql.length() && Character.isWhitespace(sql.charAt(j))) {
            j++;
        }
        if (j >= sql.length() || sql.charAt(j) != '.') {
            return 0;
        }
        j++;
        while (j < sql.length() && Character.isWhitespace(sql.charAt(j))) {
            j++;
        }
        int k = name(sql, j, parts[1]);
        if (k < 0 || (k < sql.length() && isNamePart(sql.charAt(k)))) {
            return 0;
        }
        return k - i;
    }

    /** The end of {@code name} at {@code i}, backticked or not, or -1. */
    private static int name(String sql, int i, String name) {
        if (sql.startsWith("`" + name + "`", i)) {
            return i + name.length() + 2;
        }
        if (sql.regionMatches(true, i, name, 0, name.length())) {
            int end = i + name.length();
            return end < sql.length()
                            && (Character.isLetterOrDigit(sql.charAt(end))
                                    || sql.charAt(end) == '_')
                    ? -1
                    : end;
        }
        return -1;
    }

    /**
     * The catalog over colvenna-catalog with a bearer token and per-table data tokens; Doris keeps
     * no table metadata (a cached snapshot would hold queries back,
     * docs/history/m1/doris-paimon.md).
     */
    public static String createCatalog(String catalog, String uri, String token) {
        return "CREATE CATALOG IF NOT EXISTS "
                + quote(catalog)
                + " PROPERTIES ('type' = 'paimon', 'paimon.catalog.type' = 'rest', 'uri' = "
                + literal(uri)
                + ", 'warehouse' = 'colvenna', 'paimon.rest.token.provider' = 'bear',"
                + " 'paimon.rest.token' = "
                + literal(token)
                + ", 'paimon.rest.data-token.enabled' = 'true',"
                + " 'meta.cache.paimon.table.ttl-second' = '0')";
    }

    public static String catalogToken(String catalog, String token) {
        return "ALTER CATALOG "
                + quote(catalog)
                + " SET PROPERTIES ('paimon.rest.token' = "
                + literal(token)
                + ")";
    }

    public static String createDatabase(String database) {
        return "CREATE DATABASE IF NOT EXISTS " + quote(database);
    }

    /**
     * The owner and what it may do: read its catalog, build in its database, run in the dashboard's
     * workload group.
     */
    public static List<String> ownerGrants(
            String owner, String hash, String catalog, String database, String group) {
        List<String> out = new ArrayList<>();
        out.add(createUser(owner, hash));
        out.add(setPassword(owner, hash));
        out.add("GRANT SELECT_PRIV ON " + quote(catalog) + ".*.* TO " + account(owner));
        out.add(
                "GRANT SELECT_PRIV, LOAD_PRIV, ALTER_PRIV, CREATE_PRIV, DROP_PRIV, SHOW_VIEW_PRIV ON "
                        + quote(database)
                        + ".* TO "
                        + account(owner));
        out.addAll(userGroup(owner, group, OWNER_CONNECTIONS));
        return out;
    }

    /** The operator's sessions as a dashboard's owner: one a round, a spare. */
    static final int OWNER_CONNECTIONS = 2;

    /**
     * The dashboard's workload group: at most this many of its queries at once, the rest queued.
     */
    public static String createWorkloadGroup(String group, int maxConcurrency) {
        return "CREATE WORKLOAD GROUP IF NOT EXISTS "
                + quote(group)
                + " PROPERTIES "
                + groupProperties(maxConcurrency);
    }

    public static String alterWorkloadGroup(String group, int maxConcurrency) {
        return "ALTER WORKLOAD GROUP "
                + quote(group)
                + " PROPERTIES "
                + groupProperties(maxConcurrency);
    }

    /** Up to 4 times as many waiting, each 10 s at most. */
    private static String groupProperties(int maxConcurrency) {
        return "('max_concurrency' = '"
                + maxConcurrency
                + "', 'max_queue_size' = '"
                + 4 * maxConcurrency
                + "', 'queue_timeout' = '10000')";
    }

    public static String dropWorkloadGroup(String group) {
        return "DROP WORKLOAD GROUP IF EXISTS " + quote(group);
    }

    public static String dropDatabase(String database) {
        return "DROP DATABASE IF EXISTS " + quote(database) + " FORCE";
    }

    public static String dropCatalog(String catalog) {
        return "DROP CATALOG IF EXISTS " + quote(catalog);
    }

    /** The database's objects, one a row. */
    public static String showTables(String database) {
        return "SHOW TABLES FROM " + quote(database);
    }

    /**
     * When each of the database's materialized views last refreshed successfully, as Unix seconds
     * (columns MvName, started): Doris prints task times in its own zone, this does not depend on
     * it.
     */
    public static String refreshes(String database) {
        return "SELECT MvName, UNIX_TIMESTAMP(MAX(StartTime)) AS started FROM tasks('type' = 'mv')"
                + " WHERE MvDatabaseName = "
                + literal(database)
                + " AND Status = 'SUCCESS' GROUP BY MvName";
    }

    /** A materialized view's schedule held, while its declaration is refused, and resumed. */
    public static String pauseRefresh(String database, String name) {
        return "PAUSE MATERIALIZED VIEW JOB ON " + quote(database) + "." + quote(name);
    }

    public static String resumeRefresh(String database, String name) {
        return "RESUME MATERIALIZED VIEW JOB ON " + quote(database) + "." + quote(name);
    }

    /** The statements serving a query, the object of its previous serving dropped first. */
    public static List<String> serve(
            String database, LakeDashboardSpec.Query q, String sql, Serve previous) {
        String object = quote(database) + "." + quote(q.name);
        List<String> out = new ArrayList<>();
        Serve serve = Serve.of(q.serve);
        if (previous != null && previous != serve) {
            out.add(drop(database, q.name, previous));
        }
        switch (serve) {
            case EXTERNAL:
                out.add("CREATE OR REPLACE VIEW " + object + " AS " + sql);
                break;
            case MATERIALIZED:
                out.add(drop(database, q.name, Serve.MATERIALIZED));
                out.add(
                        "CREATE MATERIALIZED VIEW "
                                + object
                                + " BUILD IMMEDIATE REFRESH COMPLETE ON SCHEDULE EVERY "
                                + minutes(q.refresh)
                                + " MINUTE DISTRIBUTED BY RANDOM BUCKETS 1 PROPERTIES"
                                + " ('replication_num' = '1') AS "
                                + sql);
                break;
            case INTERNAL:
                String key = String.join(", ", q.key.stream().map(DashboardSql::quote).toList());
                out.add(drop(database, q.name, Serve.INTERNAL));
                out.add(
                        "CREATE TABLE "
                                + object
                                + " UNIQUE KEY("
                                + key
                                + ") DISTRIBUTED BY HASH("
                                + key
                                + ") BUCKETS 1 PROPERTIES ('replication_num' = '1') AS "
                                + sql);
                break;
            default:
                throw new IllegalArgumentException("unknown serving " + serve);
        }
        return out;
    }

    /** An internal table's refresh: the query's result replaces the table's rows at once. */
    public static String overwrite(String database, String name, String sql) {
        return "INSERT OVERWRITE TABLE " + quote(database) + "." + quote(name) + " " + sql;
    }

    /** Drops a query's object, by what served it. */
    public static String drop(String database, String name, Serve serve) {
        return String.format(serve.drop, quote(database) + "." + quote(name));
    }

    /** MySQL's password hash, what Doris stores: the password itself is never sent. */
    public static String passwordHash(String password) {
        try {
            // Doris (MySQL's protocol) defines this format, *SHA1(SHA1(pw)): not ours to choose.
            // nosemgrep: java.lang.security.audit.crypto.use-of-sha1.use-of-sha1
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] once = sha1.digest(password.getBytes(StandardCharsets.UTF_8));
            return "*" + HexFormat.of().withUpperCase().formatHex(sha1.digest(once));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String createUser(String user, String hash) {
        return "CREATE USER IF NOT EXISTS "
                + account(user)
                + " IDENTIFIED BY PASSWORD "
                + literal(hash);
    }

    public static String setPassword(String user, String hash) {
        return "SET PASSWORD FOR " + account(user) + " = " + literal(hash);
    }

    public static String dropUser(String user) {
        return "DROP USER IF EXISTS " + account(user);
    }

    /**
     * A user runs in its dashboard's workload group, on at most as many connections as the group
     * runs queries. Doris lets any user switch to the {@code normal} group, which the installer
     * keeps small; the connections bound what a user can run there too.
     */
    public static List<String> userGroup(String user, String group, int connections) {
        return List.of(
                "GRANT USAGE_PRIV ON WORKLOAD GROUP " + literal(group) + " TO " + account(user),
                "SET PROPERTY FOR "
                        + literal(user)
                        + " 'default_workload_group' = "
                        + literal(group),
                "SET PROPERTY FOR "
                        + literal(user)
                        + " 'max_user_connections' = '"
                        + connections
                        + "'");
    }

    /**
     * What Doris gives every new user and a dashboard's may not keep: {@code information_schema},
     * whose {@code active_queries} shows every user's running SQL (literals, and root's catalog
     * statements with their tokens), and {@code mysql}, every user's name. SHOW statements still
     * answer. Revoking twice fails, so these run quietly.
     */
    public static List<String> withdrawDefaults(String user) {
        return List.of(
                "REVOKE SELECT_PRIV ON `internal`.`information_schema`.* FROM " + account(user),
                "REVOKE SELECT_PRIV ON `internal`.`mysql`.* FROM " + account(user));
    }

    public static String grant(String database, String name, String user) {
        return "GRANT SELECT_PRIV ON "
                + quote(database)
                + "."
                + quote(name)
                + " TO "
                + account(user);
    }

    public static String revoke(String database, String name, String user) {
        return "REVOKE SELECT_PRIV ON "
                + quote(database)
                + "."
                + quote(name)
                + " FROM "
                + account(user);
    }

    /** What serving a query depends on, as a hash: a change rebuilds its object. */
    public static String applied(LakeDashboardSpec.Query q, String sql) {
        return sha(q.serve + "\n" + q.refresh + "\n" + String.join(",", q.key) + "\n" + sql);
    }

    public static int minutes(String refresh) {
        return Integer.parseInt((refresh == null ? "1m" : refresh).replace("m", ""));
    }

    /**
     * The Doris database of the views a dashboard reads one lake database's masked tables through:
     * only its owner may read or write it.
     */
    public static String shadowDatabase(LakeDashboard d, String lakeDatabase) {
        String out = "pv_" + uid(d) + "_" + lakeDatabase;
        return out.length() <= NAME_LIMIT ? out : "pv_" + uid(d) + "_" + sha(lakeDatabase);
    }

    /** The owner's rights on a shadow database: it builds the views there and reads them. */
    public static String shadowGrant(String owner, String shadow) {
        return "GRANT SELECT_PRIV, ALTER_PRIV, CREATE_PRIV, DROP_PRIV, SHOW_VIEW_PRIV ON "
                + quote(shadow)
                + ".* TO "
                + account(owner);
    }

    /** The view a masked table is read through: its columns in order, each clear or masked. */
    public static String protectedView(
            String shadow, String table, String catalog, List<Exposure.Shown> columns) {
        String[] parts = table.split("\\.", 2);
        List<String> select = new ArrayList<>();
        for (Exposure.Shown c : columns) {
            String column = quote(c.column());
            select.add(c.masked() ? mask(c.mask(), column) + " AS " + column : column);
        }
        return "CREATE OR REPLACE VIEW "
                + quote(shadow)
                + "."
                + quote(parts[1])
                + " AS SELECT "
                + String.join(", ", select)
                + " FROM "
                + quote(catalog)
                + "."
                + quote(parts[0])
                + "."
                + quote(parts[1]);
    }

    /**
     * A masked column: NULL of the column's own type (redact), or its text with letters and digits
     * replaced (mask). No hash: an unkeyed hash of a guessable value is no protection (HLD 12.1),
     * and a key in the view would show in EXPLAIN.
     */
    static String mask(String mask, String column) {
        return switch (mask) {
            case "redact" -> "NULLIF(" + column + ", " + column + ")";
            case "mask" -> "MASK(CAST(" + column + " AS STRING))";
            default -> throw new IllegalArgumentException("unknown mask " + mask);
        };
    }

    /**
     * The row policy keeping a user to its rows of one object, named for the three and its
     * condition: a changed condition is a policy of its own.
     */
    public static String rowPolicyName(String user, String database, String object, String rows) {
        return "colvenna_" + sha(user + "\n" + database + "." + object + "\n" + rows);
    }

    public static String showRowPolicies() {
        return "SHOW ROW POLICY";
    }

    public static String dropView(String view) {
        String[] parts = view.split("\\.", 2);
        return "DROP VIEW IF EXISTS " + quote(parts[0]) + "." + quote(parts[1]);
    }

    /** A hash of a statement as made, to tell when it changed. */
    public static String applied(String statement) {
        return sha(statement);
    }

    /**
     * A user's rows of an object: those its condition holds for, or none ({@code rows} null). A
     * restrictive policy, the only one for this user and object.
     */
    public static String createRowPolicy(
            String name, String database, String object, String user, String rows) {
        return "CREATE ROW POLICY IF NOT EXISTS "
                + quote(name)
                + " ON "
                + quote(database)
                + "."
                + quote(object)
                + " AS RESTRICTIVE TO "
                + account(user)
                + " USING ("
                + (rows == null ? "1 = 0" : rows)
                + ")";
    }

    public static String dropRowPolicy(String name, String database, String object) {
        return "DROP ROW POLICY IF EXISTS "
                + quote(name)
                + " ON "
                + quote(database)
                + "."
                + quote(object);
    }

    /** What may appear in a user's row condition: names, literals, comparisons, logic. */
    private static final Pattern ROW_TOKEN =
            Pattern.compile(
                    "\\s+|`[A-Za-z0-9_]{1,64}`|[A-Za-z_][A-Za-z0-9_]{0,63}|-?[0-9]+(\\.[0-9]+)?"
                            + "|'(?:[^'\\\\]|'')*'|<=|>=|<>|!=|=|<|>|\\(|\\)|,");

    private static final Set<String> ROW_WORDS =
            Set.of("AND", "OR", "NOT", "IN", "LIKE", "IS", "NULL", "BETWEEN", "TRUE", "FALSE");

    /** Words that would make a condition a query of its own. */
    private static final Set<String> QUERY_WORDS =
            Set.of(
                    "SELECT",
                    "FROM",
                    "WHERE",
                    "WITH",
                    "UNION",
                    "JOIN",
                    "EXISTS",
                    "EXCEPT",
                    "INTERSECT",
                    "INTO",
                    "GROUP",
                    "HAVING",
                    "ORDER",
                    "LIMIT");

    /**
     * Why a user's row condition is not one, or null. It may name columns and compare them with
     * literals: no function (so no subquery, no session variable), no comment, no second statement,
     * and its parentheses balance. Doris checks the names against the object.
     */
    public static String rowsProblem(String rows) {
        int depth = 0;
        int i = 0;
        String previous = null;
        while (i < rows.length()) {
            Matcher m = ROW_TOKEN.matcher(rows);
            m.region(i, rows.length());
            if (!m.lookingAt()) {
                return "the row condition has " + rows.charAt(i) + " at " + i;
            }
            String token = m.group();
            i = m.end();
            if (token.isBlank()) {
                continue;
            }
            if (QUERY_WORDS.contains(token.toUpperCase(Locale.ROOT))) {
                return "the row condition holds " + token + ": no subquery may appear";
            }
            if (token.equals("(")) {
                if (previous != null
                        && previous.matches("[A-Za-z_`].*")
                        && !ROW_WORDS.contains(previous.toUpperCase(Locale.ROOT))) {
                    return "the row condition calls " + previous + ": no function may appear";
                }
                depth++;
            } else if (token.equals(")") && --depth < 0) {
                return "the row condition's parentheses do not balance";
            }
            previous = token;
        }
        return depth == 0 ? null : "the row condition's parentheses do not balance";
    }

    static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    static String literal(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String account(String user) {
        return literal(user) + "@'%'";
    }

    private static String token(String name) {
        return name.replaceAll("[^a-z0-9]", "_");
    }

    private static String uid(LakeDashboard d) {
        return d.getMetadata().getUid().replace("-", "").substring(0, 8);
    }

    private static String sha(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
