package com.colvenna.lake.upstream.cdc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.TreeMap;

/** The fresh MySQL run-repro.sh starts in Docker for each run (loopback, synthetic rows). */
final class MySqlSource {
    static final String HOST = "127.0.0.1";
    static final int PORT = Integer.parseInt(System.getProperty("mysql.port", "3306"));
    static final String PASSWORD = "test";

    private MySqlSource() {}

    static Connection connect() throws Exception {
        return DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":" + PORT + "/shop", "root", PASSWORD);
    }

    static void sql(String... statements) throws Exception {
        try (Connection c = connect();
                Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    /** "INSERT INTO table VALUES (from, 'v<from>'), ... (to, 'v<to>')". */
    static String rows(String table, int from, int to) {
        StringBuilder out = new StringBuilder("INSERT INTO " + table + " VALUES ");
        for (int i = from; i <= to; i++) {
            out.append(i > from ? ", " : "")
                    .append("(")
                    .append(i)
                    .append(", 'v")
                    .append(i)
                    .append("')");
        }
        return out.toString();
    }

    /** A table of (id, v): id to v. */
    static TreeMap<Long, String> values(String table) throws Exception {
        TreeMap<Long, String> out = new TreeMap<>();
        try (Connection c = connect();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT id, v FROM " + table)) {
            while (r.next()) {
                out.put(r.getLong(1), r.getString(2));
            }
        }
        return out;
    }
}
