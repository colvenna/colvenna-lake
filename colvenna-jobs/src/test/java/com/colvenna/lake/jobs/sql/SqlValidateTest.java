package com.colvenna.lake.jobs.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.SchemaChange;
import org.apache.paimon.types.DataTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlValidateTest {
    @TempDir Path warehouse;

    private TableEnvironment tEnv;
    private Catalog catalog;

    @BeforeEach
    void catalog() throws Exception {
        tEnv = TableEnvironment.create(EnvironmentSettings.inStreamingMode());
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        catalog = ColvennaCatalog.register(tEnv, options);
        catalog.createDatabase("ods", false);
        catalog.createDatabase("dwd", false);
        Map<String, String> pk = Map.of("bucket", "1", "changelog-producer", "input");
        catalog.createTable(
                Identifier.create("ods", "orders"),
                Schema.newBuilder()
                        .column("order_id", DataTypes.BIGINT().notNull())
                        .column("customer_id", DataTypes.BIGINT())
                        .column("amount", DataTypes.DECIMAL(12, 2))
                        .column("version_no", DataTypes.BIGINT().notNull())
                        .primaryKey("order_id")
                        .options(pk)
                        .build(),
                false);
        catalog.createTable(
                Identifier.create("ods", "customers"),
                Schema.newBuilder()
                        .column("customer_id", DataTypes.BIGINT().notNull())
                        .column("region", DataTypes.STRING())
                        .primaryKey("customer_id")
                        .options(pk)
                        .build(),
                false);
        catalog.createTable(
                Identifier.create("ods", "payments"),
                Schema.newBuilder()
                        .column("order_id", DataTypes.BIGINT().notNull())
                        .column("paid", DataTypes.DECIMAL(12, 2))
                        .primaryKey("order_id")
                        .options(pk)
                        .build(),
                false);
        catalog.createTable(
                Identifier.create("dwd", "orders"),
                Schema.newBuilder()
                        .column("order_id", DataTypes.BIGINT().notNull())
                        .column("region", DataTypes.STRING())
                        .column("amount", DataTypes.DECIMAL(12, 2))
                        .primaryKey("order_id")
                        .options(pk)
                        .build(),
                false);
    }

    private SqlValidate.Result validate(String query, String... key) {
        return SqlValidate.validate(
                tEnv,
                SqlSpec.parse(
                        "{\"targetDatabase\":\"dwd\",\"targetTable\":\"t\",\"query\":"
                                + quote(query)
                                + ",\"primaryKey\":"
                                + list(key)
                                + ",\"upstream\":[\"ods.orders\"]}"));
    }

    @Test
    void lookupJoinStreamsTheFactsAndLooksUpTheDimension() {
        SqlValidate.Result r =
                validate(
                        "SELECT o.order_id, c.region, o.amount FROM (SELECT *, PROCTIME() AS pt"
                                + " FROM ods.orders) AS o JOIN ods.customers FOR SYSTEM_TIME AS"
                                + " OF o.pt AS c ON o.customer_id = c.customer_id",
                        "order_id");
        assertNull(r.reason, r.message);
        assertEquals(Set.of("ods.orders"), r.streams);
        assertEquals(Set.of("ods.customers"), r.lookups);
        // A lookup join keeps no state a backfill could leave behind.
        assertFalse(r.stateful);
        assertEquals("BIGINT NOT NULL", r.fields.get(0).type().toString());
        assertEquals(List.of("order_id", "region", "amount"), names(r));
    }

    @Test
    void regularJoinOutsideTheKeyIsUnordered() {
        SqlValidate.Result r =
                validate(
                        "SELECT o.order_id, c.region, o.amount FROM ods.orders AS o JOIN"
                                + " ods.customers AS c ON o.customer_id = c.customer_id",
                        "order_id");
        assertEquals("UnorderedChangelog", r.reason, r.message);
    }

    @Test
    void joinOnTheKeyKeepsOrder() {
        SqlValidate.Result r =
                validate(
                        "SELECT o.order_id, o.amount, p.paid FROM ods.orders AS o JOIN"
                                + " ods.payments AS p ON o.order_id = p.order_id",
                        "order_id");
        assertNull(r.reason, r.message);
        assertEquals(Set.of("ods.orders", "ods.payments"), r.streams);
        assertTrue(r.stateful);
    }

    @Test
    void aggregateByItsGroupKey() {
        SqlValidate.Result r =
                validate(
                        "SELECT region, COUNT(*) AS order_count, SUM(amount) AS amount_total"
                                + " FROM dwd.orders GROUP BY region",
                        "region");
        assertNull(r.reason, r.message);
        assertEquals(Set.of("dwd.orders"), r.streams);
        assertEquals("STRING NOT NULL", r.fields.get(0).type().toString());
        assertTrue(r.stateful);
        SqlValidate.Result other =
                validate(
                        "SELECT region, COUNT(*) AS order_count FROM dwd.orders GROUP BY region",
                        "order_count");
        assertEquals("UnorderedChangelog", other.reason, other.message);
    }

    @Test
    void theJobsConsumerIdReachesThePaimonSource() throws Exception {
        // How SqlJob sets it: Paimon's dynamic option paimon.<catalog>.<db>.<table>.<key>.
        tEnv.getConfig().set("paimon.colvenna.ods.orders.consumer-id", "c1");
        String query = "SELECT order_id, amount FROM ods.orders";
        Exception refused = assertThrows(Exception.class, () -> tEnv.explainSql(query));
        assertTrue(
                SqlValidate.rootMessage(refused).contains("consumer.expiration-time"),
                SqlValidate.rootMessage(refused));
        catalog.alterTable(
                Identifier.create("ods", "orders"),
                SchemaChange.setOption("consumer.expiration-time", "1 d"),
                false);
        tEnv.explainSql(query);
    }

    @Test
    void refusesWhatCannotBecomeATable() {
        assertEquals("InvalidQuery", validate("SELEC order_id FROM ods.orders", "order_id").reason);
        assertEquals(
                "InvalidQuery", validate("SELECT order_id FROM ods.missing", "order_id").reason);
        assertEquals(
                "UnsupportedColumn",
                validate("SELECT order_id, amount * 2 FROM ods.orders", "order_id").reason);
        assertEquals(
                "UnsupportedColumn",
                validate("SELECT order_id, PROCTIME() AS pt FROM ods.orders", "order_id").reason);
        assertEquals(
                "KeyColumnMissing", validate("SELECT amount FROM ods.orders", "order_id").reason);
        SqlValidate.Result statement =
                validate("INSERT INTO dwd.orders SELECT 1, 'x', 1.0", "order_id");
        assertEquals("InvalidQuery", statement.reason);
        assertTrue(statement.message.length() <= 600);
    }

    private static List<String> names(SqlValidate.Result r) {
        return r.fields.stream().map(f -> f.name()).collect(Collectors.toList());
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String list(String... items) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            b.append(i == 0 ? "" : ",").append(quote(items[i]));
        }
        return b.append("]").toString();
    }
}
