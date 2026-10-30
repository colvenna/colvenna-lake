package com.colvenna.lake.operator.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The probes against real MySQL 8.4 and PostgreSQL 16: what admission reads (keys, checks,
 * triggers, replica identity, publication membership, partitions) and reconciliation reads.
 */
@Testcontainers
class ProbesTest {

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("shop")
                    .withUsername("root")
                    .withPassword("secret");

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16")
                    .withDatabaseName("pay")
                    .withCommand(
                            "postgres",
                            "-c",
                            "wal_level=logical",
                            "-c",
                            "ssl=on",
                            "-c",
                            "ssl_cert_file=/etc/ssl/certs/ssl-cert-snakeoil.pem",
                            "-c",
                            "ssl_key_file=/etc/ssl/private/ssl-cert-snakeoil.key");

    private static void run(Connection c, String... statements) throws Exception {
        try (Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    @Test
    void mysqlInventoryShowsWhatAdmissionNeeds() throws Exception {
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "secret")) {
            run(
                    c,
                    "CREATE TABLE used_keys (t VARCHAR(64), k VARCHAR(64), PRIMARY KEY (t, k))",
                    "CREATE TABLE orders (id BIGINT NOT NULL PRIMARY KEY, note VARCHAR(32),"
                            + " version_no BIGINT NOT NULL, CONSTRAINT v CHECK (version_no > 0))",
                    "CREATE TRIGGER orders_v BEFORE UPDATE ON orders FOR EACH ROW BEGIN IF"
                            + " NEW.version_no <= OLD.version_no THEN SIGNAL SQLSTATE '45000';"
                            + " END IF; END",
                    "CREATE TRIGGER orders_k BEFORE UPDATE ON orders FOR EACH ROW BEGIN IF"
                            + " NEW.id <> OLD.id THEN SIGNAL SQLSTATE '45000'; END IF; END",
                    "CREATE TRIGGER orders_r BEFORE INSERT ON orders FOR EACH ROW BEGIN INSERT"
                            + " INTO used_keys VALUES ('orders', NEW.id); END",
                    "CREATE TABLE no_key (id BIGINT, version_no BIGINT NOT NULL)",
                    "INSERT INTO orders VALUES (1, 'a', 1), (2, NULL, 3)");
        }
        SourceProbe.Login root = new SourceProbe.Login("root", "secret");
        SourceProbe.Target target =
                new SourceProbe.Target(
                        MYSQL.getHost(),
                        MYSQL.getMappedPort(3306),
                        "shop",
                        null,
                        null,
                        root,
                        null,
                        null,
                        null);
        MySqlProbe probe = new MySqlProbe();
        SourceModel.Inventory inventory = probe.inventory(target);
        assertThat(inventory.tables()).containsKeys("orders", "no_key", "used_keys");
        SourceModel.Table orders = inventory.tables().get("orders");
        assertThat(orders.primaryKey()).containsExactly("id");
        assertThat(orders.triggersVisible()).isTrue();
        assertThat(Admission.check("mysql", orders, "version_no").admitted()).isTrue();
        assertThat(
                        Admission.check("mysql", inventory.tables().get("no_key"), "version_no")
                                .reason())
                .isEqualTo("NoPrimaryKey");
        // A plain container logs binlogs in ROW/FULL by default.
        assertThat(inventory.checks())
                .extracting(SourceModel.Check::name)
                .contains("log_bin", "binlog_format", "binlog_row_image");

        Map<String, Long> versions =
                probe.versions(target, orders, List.of("id"), "version_no", 10);
        assertThat(versions)
                .containsEntry(Keys.encode(List.of("1")), 1L)
                .containsEntry(Keys.encode(List.of("2")), 3L);
        assertThat(
                        probe.versionsOf(
                                target,
                                orders,
                                List.of("id"),
                                "version_no",
                                List.of(List.of("2"), List.of("9"))))
                .containsOnlyKeys(Keys.encode(List.of("2")));
    }

    @Test
    void aHeartbeatKeepsTheSourcesContract() throws Exception {
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "secret")) {
            run(
                    c,
                    "CREATE TABLE beat_keys (t VARCHAR(64), k VARCHAR(64), PRIMARY KEY (t, k))",
                    "CREATE TABLE colvenna_heartbeat (id INT NOT NULL PRIMARY KEY, beat_at DATETIME(6)"
                            + " NOT NULL, version_no BIGINT NOT NULL)",
                    "CREATE TRIGGER beat_v BEFORE UPDATE ON colvenna_heartbeat FOR EACH ROW BEGIN IF"
                            + " NEW.version_no <= OLD.version_no THEN SIGNAL SQLSTATE '45000';"
                            + " END IF; END",
                    // Fires on every insert attempt: a second insert of row 1 would fail.
                    "CREATE TRIGGER beat_r BEFORE INSERT ON colvenna_heartbeat FOR EACH ROW BEGIN INSERT"
                            + " INTO beat_keys VALUES ('colvenna_heartbeat', NEW.id); END");
        }
        SourceProbe.Login root = new SourceProbe.Login("root", "secret");
        SourceProbe.Target target =
                new SourceProbe.Target(
                        MYSQL.getHost(),
                        MYSQL.getMappedPort(3306),
                        "shop",
                        null,
                        null,
                        root,
                        null,
                        null,
                        null);
        MySqlProbe probe = new MySqlProbe();
        Instant before = Instant.now().minusSeconds(5);
        Instant first = probe.heartbeat(target, root, "colvenna_heartbeat", "version_no").at();
        SourceProbe.Written written =
                probe.heartbeat(target, root, "colvenna_heartbeat", "version_no");
        Instant second = written.at();
        assertThat(written.after()).isAfterOrEqualTo(written.before());
        assertThat(first).isAfter(before);
        assertThat(second).isAfterOrEqualTo(first);
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "secret");
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT version_no FROM colvenna_heartbeat")) {
            r.next();
            assertThat(r.getLong(1)).isEqualTo(2);
        }
    }

    @Test
    void postgresInventoryShowsIdentityPublicationAndPartitions() throws Exception {
        try (Connection c =
                DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            run(
                    c,
                    "CREATE TABLE used_keys (t text, k text, PRIMARY KEY (t, k))",
                    "CREATE FUNCTION g() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF"
                            + " NEW.version_no <= OLD.version_no THEN RAISE EXCEPTION 'v'; END IF;"
                            + " RETURN NEW; END $$",
                    "CREATE FUNCTION r() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN INSERT INTO"
                            + " used_keys VALUES (TG_TABLE_NAME, NEW.id::text); RETURN NEW; END $$",
                    "CREATE TABLE payments (id bigint NOT NULL, region text NOT NULL, version_no"
                            + " bigint NOT NULL CHECK (version_no > 0), PRIMARY KEY (id, region))"
                            + " PARTITION BY LIST (region)",
                    "CREATE TABLE payments_east PARTITION OF payments FOR VALUES IN ('east')",
                    "CREATE TABLE payments_west PARTITION OF payments FOR VALUES IN ('west')",
                    "ALTER TABLE payments REPLICA IDENTITY FULL",
                    "ALTER TABLE payments_east REPLICA IDENTITY FULL",
                    "CREATE TRIGGER pg BEFORE UPDATE ON payments FOR EACH ROW EXECUTE FUNCTION g()",
                    "CREATE TRIGGER pr BEFORE INSERT ON payments FOR EACH ROW EXECUTE FUNCTION r()",
                    "CREATE PUBLICATION pub FOR TABLE payments_east WITH (publish_via_partition_root = false)",
                    "INSERT INTO payments VALUES (1, 'east', 2), (2, 'west', 1)");
        }
        SourceProbe.Login login =
                new SourceProbe.Login(POSTGRES.getUsername(), POSTGRES.getPassword());
        SourceProbe.Target target =
                new SourceProbe.Target(
                        POSTGRES.getHost(),
                        POSTGRES.getMappedPort(5432),
                        "pay",
                        "public",
                        "pub",
                        login,
                        null,
                        null,
                        null);
        PostgresProbe probe = new PostgresProbe();
        SourceModel.Inventory inventory = probe.inventory(target);
        assertThat(inventory.ready()).as(inventory.failedChecks()).isTrue();
        SourceModel.Table payments = inventory.tables().get("payments");
        assertThat(payments.postgres().partitioned()).isTrue();
        assertThat(payments.postgres().readTables())
                .containsExactly("payments_east", "payments_west");
        assertThat(payments.primaryKey()).containsExactly("id", "region");
        // payments_west lacks FULL identity and the publication: rejected for the first of them.
        assertThat(Admission.check("postgres", payments, "version_no").reason())
                .isEqualTo("ReplicaIdentityNotFull");
        assertThat(payments.postgres().notInPublication()).containsExactly("payments_west");
        assertThat(probe.versions(target, payments, List.of("id", "region"), "version_no", 10))
                .containsEntry(Keys.encode(List.of("1", "east")), 2L);
        // Looked up in the key's own types (bigint, text).
        assertThat(
                        probe.versionsOf(
                                target,
                                payments,
                                List.of("id", "region"),
                                "version_no",
                                List.of(List.of("1", "east"), List.of("1", "west"))))
                .containsOnlyKeys(Keys.encode(List.of("1", "east")));
        // Triggers are read by event: the guard fires BEFORE UPDATE, the reservation BEFORE INSERT.
        assertThat(payments.triggers())
                .extracting(t -> t.timing() + " " + t.event())
                .containsExactlyInAnyOrder("BEFORE UPDATE", "BEFORE INSERT");
    }
}
