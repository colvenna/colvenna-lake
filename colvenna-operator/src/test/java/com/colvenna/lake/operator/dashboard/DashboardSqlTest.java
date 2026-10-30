package com.colvenna.lake.operator.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.crd.LakeDashboard;
import com.colvenna.lake.operator.crd.LakeDashboardSpec;
import com.colvenna.lake.operator.governance.Exposure;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardSqlTest {
    static LakeDashboard dashboard() {
        LakeDashboard d = new LakeDashboard();
        d.setMetadata(
                new ObjectMetaBuilder()
                        .withName("sales")
                        .withNamespace("colvenna-data")
                        .withUid("0123abcd-0000-0000-0000-000000000000")
                        .build());
        return d;
    }

    static LakeDashboardSpec.Query query(String name, String serve, String sql) {
        LakeDashboardSpec.Query q = new LakeDashboardSpec.Query();
        q.name = name;
        q.serve = serve;
        q.sql = sql;
        q.refresh = "1m";
        return q;
    }

    @Test
    void namesAreTheDashboardsOwnAndFitDoris() {
        assertThat(DashboardSql.catalog(dashboard()))
                .isEqualTo("lake_colvenna_data_sales_0123abcd");
        assertThat(DashboardSql.database(dashboard()))
                .isEqualTo("dash_colvenna_data_sales_0123abcd");
        assertThat(DashboardSql.user(dashboard(), "analyst"))
                .isEqualTo("colvenna-data.sales.analyst");
        assertThat(DashboardSql.owner(dashboard()))
                .isEqualTo("lakedashboard-0123abcd000000000000000000000000");
        LakeDashboard longer = dashboard();
        longer.getMetadata().setName("a-dashboard-whose-name-is-far-too-long-for-a-doris-database");
        assertThat(DashboardSql.database(longer)).hasSizeLessThanOrEqualTo(64).startsWith("dash_");
        String user = DashboardSql.user(longer, "analyst");
        assertThat(user).hasSizeLessThanOrEqualTo(64).startsWith("u-").endsWith(".analyst");
        // Another dashboard's user of the same name is another user.
        LakeDashboard other = dashboard();
        other.getMetadata().setName("a-dashboard-whose-name-is-far-too-long-for-a-doris-databasf");
        assertThat(DashboardSql.user(other, "analyst")).isNotEqualTo(user);
        // Neither a namespace nor a user's name holds a dot: names cannot collide across
        // namespaces as a-b/c and a/b-c would with dashes.
        LakeDashboard ab = dashboard();
        ab.getMetadata().setNamespace("a-b");
        ab.getMetadata().setName("c");
        LakeDashboard a = dashboard();
        a.getMetadata().setNamespace("a");
        a.getMetadata().setName("b-c");
        assertThat(DashboardSql.user(ab, "u")).isNotEqualTo(DashboardSql.user(a, "u"));
    }

    @Test
    void theTablesAQueryReadsAreTheDeclaredOnesItNames() {
        assertThat(
                        DashboardSql.tablesRead(
                                "SELECT * FROM dwd_x.orders o JOIN `dws_x`.`segments` s ON 1 = 1"
                                        + " WHERE o.note <> 'ods_x.customers' -- ods_x.customers",
                                List.of("ods_x.customers", "dwd_x.orders", "dws_x.segments")))
                .containsExactly("dwd_x.orders", "dws_x.segments");
    }

    @Test
    void quotedTextAndCommentsAreNotNames() {
        String sql =
                "SELECT 'dws_x.segments' AS a, \"dws_x.segments\" AS b /* dws_x.segments */ FROM"
                        + " `dws_x`.`segments` -- dws_x.segments\n WHERE x = 'it''s dws_x.segments'";
        String out = DashboardSql.qualify(sql, List.of("dws_x.segments"), "c");
        assertThat(out)
                .startsWith(
                        "SELECT 'dws_x.segments' AS a, \"dws_x.segments\" AS b /* dws_x.segments */")
                .contains("FROM `c`.`dws_x`.`segments` -- dws_x.segments\n")
                .endsWith("'it''s dws_x.segments'");
        assertThat(DashboardSql.namesSystemSchema("SELECT * FROM information_schema.tables"))
                .isTrue();
        assertThat(DashboardSql.namesSystemSchema("SELECT * FROM `mysql` . user")).isTrue();
        assertThat(DashboardSql.namesSystemSchema("SELECT * FROM __internal_schema.audit_log"))
                .isTrue();
        assertThat(DashboardSql.namesSystemSchema("SELECT 'mysql.user', my_mysql.x FROM t"))
                .isFalse();
    }

    @Test
    void declaredTablesAreNamedInTheCatalogAndNothingElse() {
        String sql =
                "SELECT o.region, COUNT(*) FROM dws_x.segments s JOIN dwd_x.orders o ON s.region ="
                        + " o.region WHERE s.region <> 'dws_x.segments' AND other.t.x = 1";
        String out = DashboardSql.qualify(sql, List.of("dws_x.segments", "dwd_x.orders"), "lake_c");
        assertThat(out)
                .contains("FROM `lake_c`.`dws_x`.`segments` s")
                .contains("JOIN `lake_c`.`dwd_x`.`orders` o")
                // A column of an alias, a qualified name of another catalog: untouched.
                .contains("s.region =")
                .contains("other.t.x");
        // Already qualified: left alone.
        assertThat(
                        DashboardSql.qualify(
                                "SELECT * FROM lake_c.dws_x.segments",
                                List.of("dws_x.segments"),
                                "lake_c"))
                .isEqualTo("SELECT * FROM lake_c.dws_x.segments");
    }

    @Test
    void eachServingIsItsObject() {
        LakeDashboardSpec.Query mv = query("totals", "materialized", "SELECT 1");
        mv.refresh = "5m";
        assertThat(DashboardSql.serve("db", mv, "SELECT 1", null))
                .containsExactly(
                        "DROP MATERIALIZED VIEW IF EXISTS `db`.`totals`",
                        "CREATE MATERIALIZED VIEW `db`.`totals` BUILD IMMEDIATE REFRESH COMPLETE ON"
                                + " SCHEDULE EVERY 5 MINUTE DISTRIBUTED BY RANDOM BUCKETS 1"
                                + " PROPERTIES ('replication_num' = '1') AS SELECT 1");
        LakeDashboardSpec.Query internal = query("hot", "internal", "SELECT 1 AS id");
        internal.key = List.of("id");
        assertThat(DashboardSql.serve("db", internal, "SELECT 1 AS id", null).get(1))
                .startsWith("CREATE TABLE `db`.`hot` UNIQUE KEY(`id`) DISTRIBUTED BY HASH(`id`)");
        assertThat(DashboardSql.serve("db", query("v", "external", "SELECT 1"), "SELECT 1", null))
                .containsExactly("CREATE OR REPLACE VIEW `db`.`v` AS SELECT 1");
        // Served otherwise before: that object goes first, by its kind.
        assertThat(
                        DashboardSql.serve(
                                "db",
                                query("v", "external", "SELECT 1"),
                                "SELECT 1",
                                DashboardSql.Serve.INTERNAL))
                .containsExactly(
                        "DROP TABLE IF EXISTS `db`.`v` FORCE",
                        "CREATE OR REPLACE VIEW `db`.`v` AS SELECT 1");
        assertThat(DashboardSql.overwrite("db", "hot", "SELECT 1"))
                .isEqualTo("INSERT OVERWRITE TABLE `db`.`hot` SELECT 1");
    }

    @Test
    void theOwnerReadsItsCatalogAndBuildsInItsDatabaseOnly() {
        assertThat(DashboardSql.ownerGrants("o", "*AB", "cat", "db", "wg"))
                .containsExactly(
                        "CREATE USER IF NOT EXISTS 'o'@'%' IDENTIFIED BY PASSWORD '*AB'",
                        "SET PASSWORD FOR 'o'@'%' = '*AB'",
                        "GRANT SELECT_PRIV ON `cat`.*.* TO 'o'@'%'",
                        "GRANT SELECT_PRIV, LOAD_PRIV, ALTER_PRIV, CREATE_PRIV, DROP_PRIV,"
                                + " SHOW_VIEW_PRIV ON `db`.* TO 'o'@'%'",
                        "GRANT USAGE_PRIV ON WORKLOAD GROUP 'wg' TO 'o'@'%'",
                        "SET PROPERTY FOR 'o' 'default_workload_group' = 'wg'",
                        "SET PROPERTY FOR 'o' 'max_user_connections' = '2'");
        assertThat(DashboardSql.createWorkloadGroup("wg", 4))
                .isEqualTo(
                        "CREATE WORKLOAD GROUP IF NOT EXISTS `wg` PROPERTIES ('max_concurrency' ="
                                + " '4', 'max_queue_size' = '16', 'queue_timeout' = '10000')");
        assertThat(DashboardSql.alterWorkloadGroup("wg", 4))
                .endsWith(
                        "PROPERTIES ('max_concurrency' = '4', 'max_queue_size' = '16',"
                                + " 'queue_timeout' = '10000')");
        assertThat(DashboardSql.drop("db", "v", DashboardSql.Serve.EXTERNAL))
                .isEqualTo("DROP VIEW IF EXISTS `db`.`v`");
        assertThat(DashboardSql.drop("db", "m", DashboardSql.Serve.MATERIALIZED))
                .isEqualTo("DROP MATERIALIZED VIEW IF EXISTS `db`.`m`");
    }

    @Test
    void passwordsTravelAsTheirHashAndTokensAsLiterals() {
        // MySQL's mysql_native_password of "secret".
        assertThat(DashboardSql.passwordHash("secret"))
                .isEqualTo("*14E65567ABDB5135D0CFD9A70B3032C179A49EE7");
        assertThat(DashboardSql.createUser("colvenna-analyst", "*AB"))
                .isEqualTo(
                        "CREATE USER IF NOT EXISTS 'colvenna-analyst'@'%' IDENTIFIED BY PASSWORD '*AB'");
        assertThat(DashboardSql.createCatalog("c", "https://cat:8181", "t'x"))
                .contains("'paimon.rest.token' = 't\\'x'")
                .contains("'meta.cache.paimon.table.ttl-second' = '0'");
    }

    @Test
    void aMaskedTableIsReadThroughItsViewUnderItsOwnName() {
        String shadow = DashboardSql.shadowDatabase(dashboard(), "ods_x");
        assertThat(shadow).isEqualTo("pv_0123abcd_ods_x");
        assertThat(
                        DashboardSql.protectedView(
                                shadow,
                                "ods_x.orders",
                                "lake",
                                List.of(
                                        new Exposure.Shown("id", null),
                                        new Exposure.Shown("email", "mask"),
                                        new Exposure.Shown("phone", "mask"),
                                        new Exposure.Shown("amount", "redact"))))
                .isEqualTo(
                        "CREATE OR REPLACE VIEW `pv_0123abcd_ods_x`.`orders` AS SELECT `id`,"
                                + " MASK(CAST(`email` AS STRING)) AS `email`,"
                                + " MASK(CAST(`phone` AS STRING)) AS `phone`,"
                                + " NULLIF(`amount`, `amount`) AS `amount`"
                                + " FROM `lake`.`ods_x`.`orders`");
        // The query names the view; orders.id still resolves; the unmasked table stays the lake's.
        assertThat(
                        DashboardSql.qualify(
                                "SELECT orders.id, s.n FROM ods_x.orders JOIN ods_x.segments s"
                                        + " ON orders.id = s.id",
                                List.of("ods_x.orders", "ods_x.segments"),
                                "lake",
                                java.util.Map.of("ods_x.orders", shadow)))
                .isEqualTo(
                        "SELECT orders.id, s.n FROM `pv_0123abcd_ods_x`.`orders` JOIN"
                                + " `lake`.`ods_x`.`segments` s ON orders.id = s.id");
    }

    @Test
    void aRowConditionComparesColumnsWithLiteralsAndNothingElse() {
        assertThat(DashboardSql.rowsProblem("region = 'east'")).isNull();
        assertThat(
                        DashboardSql.rowsProblem(
                                "(`region` IN ('east', 'west') AND amount >= 10.5) OR NOT tier IS"
                                        + " NULL"))
                .isNull();
        assertThat(DashboardSql.rowsProblem("region = 'it''s'")).isNull();
        assertThat(DashboardSql.rowsProblem("region = current_user()"))
                .isEqualTo("the row condition calls current_user: no function may appear");
        assertThat(DashboardSql.rowsProblem("region = 'east'; DROP TABLE x"))
                .startsWith("the row condition has ;");
        assertThat(DashboardSql.rowsProblem("region = 'east' -- x")).isNotNull();
        assertThat(DashboardSql.rowsProblem("(region = 'east'")).contains("do not balance");
        assertThat(DashboardSql.rowsProblem("id IN (SELECT id FROM t)")).isNotNull();
    }

    @Test
    void aRowPolicyIsTheUsersAloneOnItsObject() {
        String name = DashboardSql.rowPolicyName("u", "dash", "live", "region = 'east'");
        assertThat(name).startsWith("colvenna_").hasSize(25);
        // Another condition is another policy, so the new one is made before the old one goes.
        assertThat(DashboardSql.rowPolicyName("u", "dash", "live", "region = 'west'"))
                .isNotEqualTo(name);
        assertThat(DashboardSql.createRowPolicy(name, "dash", "live", "u", "region = 'east'"))
                .isEqualTo(
                        "CREATE ROW POLICY IF NOT EXISTS `"
                                + name
                                + "` ON `dash`.`live` AS RESTRICTIVE TO 'u'@'%'"
                                + " USING (region = 'east')");
        assertThat(DashboardSql.createRowPolicy(name, "dash", "live", "u", null))
                .endsWith("USING (1 = 0)");
    }
}
