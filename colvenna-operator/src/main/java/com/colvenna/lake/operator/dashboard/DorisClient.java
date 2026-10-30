package com.colvenna.lake.operator.dashboard;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The platform's Doris, over its FE's MySQL protocol. As root (whose password is in the Secret the
 * installer made, read when connecting) the operator makes catalogs, owners and users; each
 * dashboard's objects it builds as that dashboard's owner, whose password it derives from root's
 * and stores nowhere. Users get only the objects their dashboards grant them (LLD "看板服务": 管理账号 边界).
 */
public class DorisClient {
    private final DorisSettings settings;
    private final KubernetesClient kube;

    public DorisClient(DorisSettings settings, KubernetesClient kube) {
        this.settings = settings;
        this.kube = kube;
    }

    /** Where the platform's Doris is. */
    public DorisSettings settings() {
        return settings;
    }

    /** A root session for one round. */
    public Session root() throws SQLException {
        return open("root", rootPassword(), 60);
    }

    /** A session of a dashboard's owner, whose statements may take up to {@code seconds}. */
    public Session as(String user, String password, int seconds) throws SQLException {
        return open(user, password, seconds);
    }

    /**
     * A secret only this operator can make again: an HMAC of {@code value} under root's password.
     * The owner's password, and what status keeps of a user's password hash, are such.
     */
    public String seal(String value) throws SQLException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            rootPassword().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (GeneralSecurityException e) {
            throw new SQLException("cannot seal", e);
        }
    }

    private String rootPassword() throws SQLException {
        Secret secret =
                kube.secrets()
                        .inNamespace(settings.adminNamespace())
                        .withName(settings.adminSecret())
                        .get();
        if (secret == null
                || secret.getData() == null
                || !secret.getData().containsKey("password")) {
            throw new SQLException(
                    "no Secret "
                            + settings.adminNamespace()
                            + "/"
                            + settings.adminSecret()
                            + " with root's password");
        }
        return new String(
                Base64.getDecoder().decode(secret.getData().get("password")),
                StandardCharsets.UTF_8);
    }

    private Session open(String user, String password, int seconds) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("connectTimeout", "10000");
        props.setProperty("socketTimeout", Integer.toString((seconds + 30) * 1000));
        props.setProperty("useSSL", "false");
        props.setProperty("allowPublicKeyRetrieval", "false");
        Session s =
                new Session(
                        DriverManager.getConnection(
                                "jdbc:mysql://" + settings.host() + ":" + settings.port() + "/",
                                props));
        // The server gives up on a statement before the client does.
        s.execute("SET query_timeout = " + seconds);
        return s;
    }

    /** One connection: statements, and queries read as rows of column name to text. */
    public static class Session implements AutoCloseable {
        private final Connection connection;

        Session(Connection connection) {
            this.connection = connection;
        }

        protected Session() {
            this.connection = null;
        }

        public void execute(String sql) throws SQLException {
            try (Statement s = connection.createStatement()) {
                s.execute(sql);
            }
        }

        public List<Map<String, String>> query(String sql) throws SQLException {
            List<Map<String, String>> out = new ArrayList<>();
            try (Statement s = connection.createStatement();
                    ResultSet r = s.executeQuery(sql)) {
                ResultSetMetaData m = r.getMetaData();
                while (r.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= m.getColumnCount(); i++) {
                        row.put(m.getColumnLabel(i), r.getString(i));
                    }
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public void close() throws SQLException {
            if (connection != null) {
                connection.close();
            }
        }
    }
}
