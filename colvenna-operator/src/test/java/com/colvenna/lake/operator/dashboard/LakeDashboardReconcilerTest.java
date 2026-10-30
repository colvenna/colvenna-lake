package com.colvenna.lake.operator.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.catalog.CatalogClient;
import com.colvenna.lake.operator.crd.Governance;
import com.colvenna.lake.operator.crd.LakeDashboard;
import com.colvenna.lake.operator.crd.LakeDashboardSpec;
import com.colvenna.lake.operator.crd.LakeDashboardStatus;
import com.colvenna.lake.operator.crd.LakeQuality;
import com.colvenna.lake.operator.crd.LakeQualitySpec;
import com.colvenna.lake.operator.crd.LakeQualityStatus;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceSpec;
import com.colvenna.lake.operator.crd.LakeTaskSpec;
import com.colvenna.lake.operator.metrics.Metrics;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A dashboard against a Kubernetes mock, a fake colvenna-catalog and a fake Doris that knows each
 * object's kind as Doris does (a view is no table to DROP TABLE): who builds what, what a failure
 * leaves, what is made again.
 */
@EnableKubernetesMockClient(crud = true, https = false)
class LakeDashboardReconcilerTest {
    private static final String NS = "colvenna-data";
    private static final DorisSettings SETTINGS =
            new DorisSettings("doris", 9030, "colvenna-doris", "admin", "https://cat");
    KubernetesClient kube;
    private MockWebServer catalog;

    /** colvenna-catalog answering 503 for the table a dashboard reads. */
    private volatile boolean tableDown;

    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-10-15T00:00:00Z"));
    private final FakeDoris doris = new FakeDoris();
    private LakeDashboardReconciler reconciler;
    private BiCredentials bi;

    /** Statements as "<user>: <sql>", objects by name with their kind, failures by pattern. */
    static final class FakeDoris extends DorisClient {
        final List<String> log = new ArrayList<>();
        final Map<String, String> objects = new ConcurrentHashMap<>();
        final List<String> catalogs = new ArrayList<>();
        final List<Pattern> failing = new ArrayList<>();
        final Map<String, Long> refreshed = new ConcurrentHashMap<>();
        final Set<String> policies = ConcurrentHashMap.newKeySet();
        String uid;

        /** Whether a failure's message repeats the statement, as Doris's parse errors do. */
        boolean echo;

        FakeDoris() {
            super(SETTINGS, null);
        }

        @Override
        public Session root() {
            return session("root");
        }

        @Override
        public Session as(String user, String password, int seconds) {
            assertThat(password).isEqualTo(seal("owner/" + uid));
            return session(user);
        }

        @Override
        public String seal(String value) {
            return "sealed(" + value + ")";
        }

        List<String> by(String user) {
            return log.stream()
                    .filter(l -> l.startsWith(user + ": "))
                    .map(l -> l.substring(user.length() + 2))
                    .toList();
        }

        private static final Pattern OBJECT =
                Pattern.compile(
                        "^(CREATE OR REPLACE VIEW|CREATE MATERIALIZED VIEW|CREATE TABLE|DROP VIEW IF"
                                + " EXISTS|DROP MATERIALIZED VIEW IF EXISTS|DROP TABLE IF EXISTS|INSERT"
                                + " OVERWRITE TABLE) `[^`]+`\\.`([^`]+)`");

        private Session session(String user) {
            return new Session() {
                @Override
                public void execute(String sql) throws SQLException {
                    log.add(user + ": " + sql);
                    for (Pattern p : failing) {
                        if (p.matcher(sql).find()) {
                            throw new SQLException("injected: " + p + (echo ? " in " + sql : ""));
                        }
                    }
                    Matcher m = OBJECT.matcher(sql);
                    if (sql.startsWith("CREATE ROW POLICY IF NOT EXISTS")) {
                        policies.add(sql.split("`")[1]);
                    } else if (sql.startsWith("DROP ROW POLICY IF EXISTS")) {
                        policies.remove(sql.split("`")[1]);
                    } else if (sql.startsWith("CREATE CATALOG")) {
                        catalogs.add(sql.split("`")[1]);
                    } else if (m.find()) {
                        String verb = m.group(1);
                        String name = m.group(2);
                        String has = objects.get(name);
                        String kind =
                                verb.contains("MATERIALIZED")
                                        ? "MV"
                                        : verb.contains("VIEW") ? "VIEW" : "TABLE";
                        if (verb.startsWith("DROP")) {
                            if (has != null && !has.equals(kind)) {
                                throw new SQLException(name + " is not " + kind);
                            }
                            objects.remove(name);
                        } else if (verb.startsWith("INSERT")) {
                            if (!"TABLE".equals(has)) {
                                throw new SQLException("no table " + name);
                            }
                        } else if (has != null && !(kind.equals("VIEW") && has.equals("VIEW"))) {
                            throw new SQLException(name + " exists");
                        } else {
                            objects.put(name, kind);
                        }
                    }
                }

                @Override
                public List<Map<String, String>> query(String sql) {
                    log.add(user + ": " + sql);
                    if (sql.equals("SHOW CATALOGS")) {
                        return catalogs.stream().map(c -> Map.of("CatalogName", c)).toList();
                    }
                    if (sql.startsWith("SHOW TABLES FROM")) {
                        return objects.keySet().stream()
                                .map(o -> Map.of("Tables_in_db", o))
                                .toList();
                    }
                    if (sql.equals("SHOW ROW POLICY")) {
                        return policies.stream().map(p -> Map.of("PolicyName", p)).toList();
                    }
                    if (sql.contains("tasks('type' = 'mv')")) {
                        List<Map<String, String>> out = new ArrayList<>();
                        refreshed.forEach(
                                (mv, at) -> out.add(Map.of("MvName", mv, "started", at + ".0")));
                        return out;
                    }
                    return List.of();
                }

                @Override
                public void close() {}
            };
        }
    }

    @BeforeEach
    void start() throws Exception {
        catalog = new MockWebServer();
        catalog.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest r) {
                        String call = r.getMethod() + " " + r.getPath();
                        if (call.equals("GET /colvenna/admin/v1/principals")) {
                            return json("{\"principals\":[]}");
                        }
                        if (call.equals("POST /colvenna/admin/v1/principals")) {
                            return json("{\"name\":\"x\"}");
                        }
                        if (call.matches("POST /colvenna/admin/v1/principals/.*/tokens")) {
                            return json(
                                    "{\"id\":\"t1\",\"token\":\"colvenna_secret\",\"expiresAt\":"
                                            + "\"2026-11-14T00:00:00Z\"}");
                        }
                        if (call.matches("GET /colvenna/admin/v1/principals/.*/tokens")) {
                            return json("{\"tokens\":[{\"id\":\"t1\"}]}");
                        }
                        if (call.startsWith("GET /colvenna/admin/v1/grants")) {
                            return json("{\"grants\":[]}");
                        }
                        if (call.equals("POST /colvenna/admin/v1/grants")) {
                            String body = r.getBody().readUtf8();
                            // As colvenna-catalog does: every grant names its approval.
                            if (!body.matches(".*\"approval\":\"[^\"]+\".*")) {
                                return new MockResponse()
                                        .setResponseCode(400)
                                        .setBody("{\"error\":\"Field approval is required.\"}");
                            }
                            return json(body);
                        }
                        if (call.startsWith("DELETE ")) {
                            return new MockResponse();
                        }
                        if (call.equals("GET /v1/colvenna/databases/ods_x/tables/orders")) {
                            if (tableDown) {
                                return new MockResponse().setResponseCode(503).setBody("{}");
                            }
                            return json(
                                    "{\"path\":\"s3://w/ods_x/orders\",\"schema\":{\"fields\":["
                                            + "{\"id\":0,\"name\":\"id\",\"type\":\"BIGINT NOT NULL\"},"
                                            + "{\"id\":1,\"name\":\"email\",\"type\":\"STRING\"},"
                                            + "{\"id\":2,\"name\":\"amount\",\"type\":\"DOUBLE\"}]}}");
                        }
                        return new MockResponse().setResponseCode(404).setBody("{}");
                    }
                });
        catalog.start();
        bi = new BiCredentials(kube, new BiCredentials.Settings("colvenna-bi", "doris-users"));
        reconciler =
                new LakeDashboardReconciler(
                        kube,
                        new CatalogClient(
                                URI.create(catalog.url("/").toString()),
                                () -> "admin",
                                SSLContext.getDefault()),
                        Optional.of(doris),
                        Optional.of(bi),
                        new Metrics(),
                        now::get);
        kube.resource(
                        new SecretBuilder()
                                .withNewMetadata()
                                .withName("doris-users")
                                .withNamespace("colvenna-bi")
                                .endMetadata()
                                .build())
                .create();
        LakeSource source = new LakeSource();
        source.setMetadata(new ObjectMetaBuilder().withName("shop").withNamespace(NS).build());
        source.setSpec(new LakeSourceSpec());
        source.getSpec().target = new LakeSourceSpec.Target();
        source.getSpec().target.database = "ods_x";
        source.getSpec().governance = governance(List.of());
        kube.resource(source).create();
        kube.resource(
                        new SecretBuilder()
                                .withNewMetadata()
                                .withName("analyst")
                                .withNamespace(NS)
                                .endMetadata()
                                .addToData(
                                        "password",
                                        Base64.getEncoder()
                                                .encodeToString(
                                                        "s3cret".getBytes(StandardCharsets.UTF_8)))
                                .build())
                .create();
    }

    @AfterEach
    void stop() throws IOException {
        catalog.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setBody(body);
    }

    private static Governance governance(List<String> categories) {
        Governance g = new Governance();
        g.owner = "bi";
        g.level = "L2";
        g.categories = new ArrayList<>(categories);
        g.retention = "30d";
        return g;
    }

    private static LakeDashboardSpec.Query query(String name, String serve, String sql) {
        LakeDashboardSpec.Query q = new LakeDashboardSpec.Query();
        q.name = name;
        q.serve = serve;
        q.sql = sql;
        q.refresh = "1m";
        return q;
    }

    private LakeDashboard create() {
        LakeDashboard d = new LakeDashboard();
        d.setMetadata(
                new ObjectMetaBuilder()
                        .withName("sales")
                        .withNamespace(NS)
                        .withGeneration(1L)
                        .build());
        LakeDashboardSpec spec = new LakeDashboardSpec();
        spec.tables = List.of("ods_x.orders");
        LakeDashboardSpec.Query hot = query("hot", "internal", "SELECT id FROM ods_x.orders");
        hot.key = List.of("id");
        spec.queries =
                new ArrayList<>(
                        List.of(
                                query(
                                        "totals",
                                        "materialized",
                                        "SELECT COUNT(*) FROM ods_x.orders"),
                                query("live", "external", "SELECT * FROM ods_x.orders"),
                                hot));
        LakeDashboardSpec.User u = new LakeDashboardSpec.User();
        u.name = "analyst";
        u.passwordSecret = new LakeDashboardSpec.SecretKey();
        u.passwordSecret.name = "analyst";
        u.queries = List.of("totals", "live");
        spec.users = List.of(u);
        spec.governance = governance(List.of());
        d.setSpec(spec);
        LakeDashboard created = kube.resource(d).create();
        // The API server gives the uid the names follow from.
        doris.uid = created.getMetadata().getUid();
        owner = DashboardSql.owner(created);
        catalogName = DashboardSql.catalog(created);
        database = DashboardSql.database(created);
        return created;
    }

    private String owner;
    private String catalogName;
    private String database;

    /** The last round's answer to JOSDK. */
    private UpdateControl<LakeDashboard> control;

    /** One reconciliation, its status written back as JOSDK would. */
    private LakeDashboard round(LakeDashboard d) {
        LakeDashboard current =
                kube.resources(LakeDashboard.class).inNamespace(NS).withName("sales").get();
        current.setSpec(d.getSpec());
        current.getMetadata().setGeneration(d.getMetadata().getGeneration());
        current.setStatus(d.getStatus());
        control = reconciler.reconcile(current, null);
        return current;
    }

    private static String condition(LakeDashboard d, String type) {
        return d.getStatus().conditions.stream()
                .filter(c -> c.type.equals(type))
                .map(c -> c.status + " " + c.reason)
                .findFirst()
                .orElse(null);
    }

    private static final String USER = "colvenna-data.sales.analyst";

    @Test
    void theOwnerBuildsTheObjectsAndUsersGetTheirQueriesOnly() {
        LakeDashboard d = round(create());
        LakeDashboardStatus s = d.getStatus();
        assertThat(s.phase).as(s.message).isEqualTo("Ready");
        assertThat(doris.objects).containsEntry("totals", "MV").containsEntry("live", "VIEW");
        assertThat(doris.objects).containsEntry("hot", "TABLE");
        // Root makes the catalog, the owner and the users; it builds nothing.
        assertThat(doris.by("root"))
                .anyMatch(l -> l.startsWith("CREATE CATALOG IF NOT EXISTS `" + catalogName + "`"))
                .anyMatch(
                        l ->
                                l.equals(
                                        "GRANT SELECT_PRIV ON `"
                                                + catalogName
                                                + "`.*.* TO '"
                                                + owner
                                                + "'@'%'"))
                .noneMatch(l -> l.startsWith("CREATE TABLE") || l.contains(" VIEW "));
        assertThat(doris.by(owner))
                .anyMatch(l -> l.startsWith("CREATE MATERIALIZED VIEW `" + database + "`.`totals`"))
                .anyMatch(l -> l.contains("FROM `" + catalogName + "`.`ods_x`.`orders`"));
        assertThat(doris.by("root"))
                .contains(
                        "GRANT SELECT_PRIV ON `" + database + "`.`totals` TO '" + USER + "'@'%'",
                        "GRANT SELECT_PRIV ON `" + database + "`.`live` TO '" + USER + "'@'%'",
                        "REVOKE SELECT_PRIV ON `" + database + "`.`hot` FROM '" + USER + "'@'%'");
        // Its group, and no more connections than the group runs queries.
        assertThat(doris.by("root"))
                .contains(
                        "SET PROPERTY FOR '"
                                + USER
                                + "' 'default_workload_group' = '"
                                + DashboardSql.workloadGroup(d)
                                + "'",
                        "SET PROPERTY FOR '" + USER + "' 'max_user_connections' = '10'",
                        // Not every user's running queries, nor every user's name.
                        "REVOKE SELECT_PRIV ON `internal`.`information_schema`.* FROM '"
                                + USER
                                + "'@'%'",
                        "REVOKE SELECT_PRIV ON `internal`.`mysql`.* FROM '" + owner + "'@'%'");
        // The status keeps no hash: a seal of it.
        assertThat(s.users.get(USER)).startsWith("sealed(user/" + USER + "/*");
        assertThat(s.users.get(USER)).isNotEqualTo(DashboardSql.passwordHash("s3cret"));
    }

    private static SSLContext sslContext() {
        try {
            return SSLContext.getDefault();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LakeDashboardSpec.Sso sso(String username) {
        LakeDashboardSpec.Sso sso = new LakeDashboardSpec.Sso();
        sso.username = username;
        return sso;
    }

    @Test
    void aSignInUserIsPublishedToTheBiInterfaceAsTheirDorisUser() {
        // Another dashboard's person, there before: it stays.
        bi.publish(
                "colvenna-data/other",
                List.of(
                        new BiCredentials.Entry(
                                "eve",
                                "colvenna-data.other.e",
                                "pw",
                                "dash_o",
                                "colvenna-data/other")));
        LakeDashboard d = create();
        d.getSpec().users.get(0).sso = sso("Alice@example.com");
        d = round(d);
        assertThat(condition(d, "BiPublished")).isEqualTo("True Published");
        assertThat(bi.entries())
                .containsEntry(
                        USER,
                        new BiCredentials.Entry(
                                "Alice@example.com", USER, "s3cret", database, NS + "/sales"))
                .containsKey("colvenna-data.other.e");
        // No longer signing in: the entry goes, the other dashboard's stays.
        d.getSpec().users.get(0).sso = null;
        d.getMetadata().setGeneration(2L);
        d = round(d);
        assertThat(bi.entries()).doesNotContainKey(USER).containsKey("colvenna-data.other.e");
        d.getSpec().users.get(0).sso = sso("alice@example.com");
        d.getMetadata().setGeneration(3L);
        d = round(d);
        assertThat(bi.entries()).containsKey(USER);
        // Deleted: none of the dashboard's entries are left.
        reconciler.cleanup(d, null);
        assertThat(bi.entries()).doesNotContainKey(USER).containsKey("colvenna-data.other.e");
    }

    @Test
    void aRefusedDashboardWithdrawsItsSignInUsers() {
        LakeDashboard d = create();
        d.getSpec().users.get(0).sso = sso("alice");
        d = round(d);
        assertThat(bi.entries()).containsKey(USER);
        d.getSpec().queries.get(0).sql = "DELETE FROM ods_x.orders";
        d.getMetadata().setGeneration(2L);
        d = round(d);
        assertThat(d.getStatus().phase).isEqualTo("Failed");
        assertThat(bi.entries()).doesNotContainKey(USER);
    }

    @Test
    void aBiSecretThatFailsIsTheBiInterfacesConditionNotDoris() {
        LakeDashboard d = create();
        d.getSpec().users.get(0).sso = sso("alice");
        kube.secrets().inNamespace("colvenna-bi").withName("doris-users").delete();
        d = round(d);
        assertThat(condition(d, "DorisReady")).isEqualTo("True Ready");
        assertThat(condition(d, "BiPublished")).isEqualTo("False BiUnavailable");
        assertThat(d.getStatus().grantedAt).isNotNull();
        // Without the Secret there is nothing to withdraw: the dashboard can still go.
        assertThat(reconciler.cleanup(d, null).isRemoveFinalizer()).isTrue();
    }

    @Test
    void onePersonIsOneUserOfADashboard() {
        LakeDashboardSpec spec = create().getSpec();
        spec.users.get(0).sso = sso("Alice");
        LakeDashboardSpec.User twin = new LakeDashboardSpec.User();
        twin.name = "twin";
        twin.passwordSecret = spec.users.get(0).passwordSecret;
        twin.queries = List.of("live");
        twin.sso = sso("alice");
        spec.users = List.of(spec.users.get(0), twin);
        assertThat(LakeDashboardReconciler.validate(spec, catalogName))
                .isEqualTo("sign-in user alice is given to two users");
    }

    @Test
    void withoutABiInterfaceSignInUsersAreReportedNotPublished() {
        reconciler =
                new LakeDashboardReconciler(
                        kube,
                        new CatalogClient(
                                URI.create(catalog.url("/").toString()),
                                () -> "admin",
                                sslContext()),
                        Optional.of(doris),
                        Optional.empty(),
                        new Metrics(),
                        now::get);
        LakeDashboard d = create();
        d.getSpec().users.get(0).sso = sso("alice");
        d = round(d);
        assertThat(d.getStatus().phase).isEqualTo("Ready");
        assertThat(condition(d, "BiPublished")).isEqualTo("False NoBi");
    }

    @Test
    void aSteadyDashboardIsLeftAloneAndItsGrantsAreMadeAgainOnSchedule() {
        LakeDashboard d = round(create());
        doris.log.clear();
        now.set(now.get().plusSeconds(30));
        d = round(d);
        assertThat(d.getStatus().phase).isEqualTo("Ready");
        assertThat(doris.log)
                .noneMatch(l -> l.contains("CREATE MATERIALIZED") || l.contains("GRANT"));
        now.set(now.get().plus(LakeDashboardReconciler.REGRANT));
        d = round(d);
        assertThat(doris.by("root")).anyMatch(l -> l.startsWith("GRANT SELECT_PRIV ON `dash_"));
    }

    @Test
    void aMissingObjectIsMadeAgainAndAFailedRewriteKeepsTheCopy() {
        LakeDashboard d = round(create());
        doris.objects.remove("totals");
        doris.failing.add(Pattern.compile("^INSERT OVERWRITE"));
        now.set(now.get().plusSeconds(120));
        doris.log.clear();
        d = round(d);
        assertThat(doris.objects).containsEntry("totals", "MV").containsEntry("hot", "TABLE");
        LakeDashboardStatus.QueryState hot =
                d.getStatus().queries.stream().filter(q -> q.name.equals("hot")).findFirst().get();
        assertThat(hot.message).contains("injected");
        assertThat(hot.refreshedAt).isEqualTo("2026-10-15T00:00:00Z");
        // The failure dropped nothing.
        assertThat(doris.log).noneMatch(l -> l.contains("DROP TABLE"));
        assertThat(d.getStatus().phase).isEqualTo("Degraded");
        doris.failing.clear();
        now.set(now.get().plusSeconds(30));
        d = round(d);
        assertThat(d.getStatus().phase).isEqualTo("Ready");
    }

    @Test
    void aServingThatChangesDropsTheObjectByWhatItWas() {
        LakeDashboard d = round(create());
        d.getSpec().queries.get(1).serve = "materialized";
        d.getSpec().queries.remove(2);
        d.getMetadata().setGeneration(2L);
        doris.log.clear();
        d = round(d);
        assertThat(d.getStatus().phase).as(d.getStatus().message).isEqualTo("Ready");
        assertThat(doris.by(owner))
                .contains(
                        "DROP VIEW IF EXISTS `" + database + "`.`live`",
                        "DROP TABLE IF EXISTS `" + database + "`.`hot` FORCE");
        assertThat(doris.objects).containsEntry("live", "MV").doesNotContainKey("hot");
        assertThat(d.getStatus().queries).extracting(q -> q.name).containsExactly("totals", "live");
    }

    @Test
    void anObjectTheStatusLostIsReplacedWhateverItIs() {
        doris.objects.put("live", "TABLE");
        LakeDashboard d = round(create());
        assertThat(d.getStatus().phase).as(d.getStatus().message).isEqualTo("Ready");
        assertThat(doris.objects).containsEntry("live", "VIEW");
    }

    @Test
    void aRefusedDashboardServesNoOne() {
        LakeDashboard d = round(create());
        d.getSpec().tables = List.of("ods_x.orders", "elsewhere.t");
        d.getMetadata().setGeneration(2L);
        d = round(d);
        assertThat(d.getStatus().phase).isEqualTo("Failed");
        assertThat(doris.by("root")).contains("DROP USER IF EXISTS '" + USER + "'@'%'");
        assertThat(d.getStatus().users).isEmpty();
        // Its copies stay for the fix, and copy no more of the lake meanwhile.
        assertThat(doris.objects).containsKeys("totals", "live", "hot");
        assertThat(doris.by("root"))
                .contains("PAUSE MATERIALIZED VIEW JOB ON `" + database + "`.`totals`");
        // Fixed, it serves again: the users come back, the refreshes run again.
        d.getSpec().tables = List.of("ods_x.orders");
        d.getMetadata().setGeneration(3L);
        d = round(d);
        assertThat(d.getStatus().phase).as(d.getStatus().message).isEqualTo("Ready");
        assertThat(d.getStatus().users).containsKey(USER);
        assertThat(doris.by("root"))
                .contains("RESUME MATERIALIZED VIEW JOB ON `" + database + "`.`totals`");
    }

    @Test
    void aDashboardOverATableItsQualityGateHoldsServesNoOne() {
        LakeDashboard d = round(create());
        assertThat(d.getStatus().phase).isEqualTo("Ready");
        LakeQuality q = new LakeQuality();
        q.setMetadata(new ObjectMetaBuilder().withName("orders").withNamespace(NS).build());
        q.setSpec(new LakeQualitySpec());
        q.getSpec().table = new LakeTaskSpec.Table();
        q.getSpec().table.database = "ods_x";
        q.getSpec().table.table = "orders";
        q.getSpec().gate = new LakeQualitySpec.Gate();
        q.getSpec().gate.publish = true;
        q = kube.resource(q).create();
        q.setStatus(new LakeQualityStatus());
        q.getStatus().phase = "Ready";
        q.getStatus().gate = new LakeQualityStatus.GateState();
        q.getStatus().gate.open = false;
        q.getStatus().gate.cause = "Failed";
        q.getStatus().gate.message = "check-1 failed volume";
        q = kube.resource(q).updateStatus();
        d = round(d);
        // Refused, it is judged again on schedule: the gate opens without the dashboard changing.
        assertThat(control.getScheduleDelay()).isPresent();
        assertThat(d.getStatus().phase).isEqualTo("Failed");
        assertThat(condition(d, "Ready")).isEqualTo("False GovernanceRefused");
        assertThat(d.getStatus().message)
                .contains("quality gate")
                .contains("check-1 failed volume");
        assertThat(d.getStatus().users).isEmpty();
        assertThat(doris.by("root"))
                .contains("PAUSE MATERIALIZED VIEW JOB ON `" + database + "`.`totals`");
        // Open again (a passing check, or a waiver), it serves again.
        q.getStatus().gate.open = true;
        q.getStatus().gate.cause = "Passed";
        kube.resource(q).updateStatus();
        d = round(d);
        assertThat(d.getStatus().phase).as(d.getStatus().message).isEqualTo("Ready");
        assertThat(condition(d, "Ready")).isEqualTo("True Served");
        assertThat(d.getStatus().users).containsKey(USER);
    }

    @Test
    void anInvalidDeclarationWaitsForItsFix() {
        LakeDashboard d = round(create());
        d.getSpec().queries =
                List.of(query("totals", "external", "SELECT * FROM information_schema.tables"));
        d.getMetadata().setGeneration(2L);
        d = round(d);
        assertThat(d.getStatus().phase).isEqualTo("Failed");
        assertThat(condition(d, "Ready")).isEqualTo("False SpecInvalid");
        assertThat(d.getStatus().users).isEmpty();
        // Only a new declaration can fix it, and it brings a round of its own.
        assertThat(control.getScheduleDelay()).isEmpty();
    }

    /** The shop source's governance, ods_x: L3 personal, with labels by ODS table and column. */
    private void labelShop(Map<String, Governance.ColumnLabel> labels) {
        LakeSource source = kube.resources(LakeSource.class).inNamespace(NS).withName("shop").get();
        source.getSpec().governance = governance(List.of("personal"));
        source.getSpec().governance.level = "L3";
        source.getSpec().governance.columns = new LinkedHashMap<>(labels);
        kube.resource(source).update();
    }

    private static Governance.ColumnLabel label(String level, String mask, String... categories) {
        Governance.ColumnLabel l = new Governance.ColumnLabel();
        l.level = level;
        l.mask = mask;
        l.categories = List.of(categories);
        return l;
    }

    @Test
    void aLabelledTableIsReadThroughAViewMaskingWhatTheDashboardIsNotClearedFor() {
        labelShop(
                Map.of(
                        "orders.email",
                        label("L3", "mask", "personal"),
                        "orders.id",
                        label("L1", null)));
        LakeDashboard d = create();
        // Its copies are L3 personal, as what they copy; its users are cleared for L2 only.
        d.getSpec().governance = governance(List.of("personal"));
        d.getSpec().governance.level = "L3";
        d.getSpec().access = new LakeDashboardSpec.Access();
        d.getSpec().access.level = "L2";
        d = round(d);
        assertThat(d.getStatus().phase).as(d.getStatus().message).isEqualTo("Ready");
        String shadow = DashboardSql.shadowDatabase(d, "ods_x");
        assertThat(doris.by("root")).contains("CREATE DATABASE IF NOT EXISTS `" + shadow + "`");
        assertThat(doris.by("root"))
                .contains(
                        "GRANT SELECT_PRIV, ALTER_PRIV, CREATE_PRIV, DROP_PRIV, SHOW_VIEW_PRIV ON `"
                                + shadow
                                + "`.* TO '"
                                + owner
                                + "'@'%'");
        // The owner builds the view; amount, unlabelled, is labelled as its table and redacted.
        assertThat(doris.by(owner))
                .contains(
                        "CREATE OR REPLACE VIEW `"
                                + shadow
                                + "`.`orders` AS SELECT `id`, MASK(CAST(`email` AS STRING))"
                                + " AS `email`, NULLIF(`amount`, `amount`) AS `amount` FROM `"
                                + catalogName
                                + "`.`ods_x`.`orders`");
        assertThat(doris.by(owner))
                .contains(
                        "CREATE OR REPLACE VIEW `"
                                + database
                                + "`.`live` AS SELECT * FROM `"
                                + shadow
                                + "`.`orders`");
        assertThat(d.getStatus().exposure)
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(e.view).isEqualTo(shadow + ".orders");
                            assertThat(e.masked).containsExactly("email:mask", "amount:redact");
                        });
        // Cleared for personal data, it reads the lake directly again and the view goes.
        d.getSpec().access.level = "L3";
        d.getSpec().access.categories = List.of("personal");
        d.getMetadata().setGeneration(2L);
        d = round(d);
        assertThat(d.getStatus().exposure).isEmpty();
        assertThat(doris.by(owner)).contains("DROP VIEW IF EXISTS `" + shadow + "`.`orders`");
        assertThat(doris.by(owner))
                .contains(
                        "CREATE OR REPLACE VIEW `"
                                + database
                                + "`.`live` AS SELECT * FROM `"
                                + catalogName
                                + "`.`ods_x`.`orders`");
    }

    @Test
    void anUnlabelledTableAboveTheUsersClearanceIsReadWithEveryColumnRedacted() {
        labelShop(Map.of());
        LakeDashboard d = create();
        d.getSpec().governance = governance(List.of("personal"));
        d.getSpec().governance.level = "L3";
        d.getSpec().access = new LakeDashboardSpec.Access();
        d.getSpec().access.level = "L2";
        d = round(d);
        assertThat(d.getStatus().phase).as(d.getStatus().message).isEqualTo("Ready");
        assertThat(d.getStatus().exposure)
                .singleElement()
                .satisfies(
                        e ->
                                assertThat(e.masked)
                                        .containsExactly(
                                                "id:redact", "email:redact", "amount:redact"));
    }

    @Test
    void aChangedDefinitionDorisRefusesTakesTheOldObjectAway() {
        LakeDashboard d = round(create());
        assertThat(doris.objects).containsEntry("live", "VIEW");
        // A label the dashboard is not cleared for: live is to read the masking view now.
        labelShop(Map.of("orders.email", label("L3", "mask", "personal")));
        d.getSpec().governance = governance(List.of("personal"));
        d.getSpec().governance.level = "L3";
        d.getSpec().access = new LakeDashboardSpec.Access();
        d.getSpec().access.level = "L2";
        d.getMetadata().setGeneration(2L);
        doris.failing.add(Pattern.compile("^CREATE OR REPLACE VIEW `" + database + "`\\.`live`"));
        doris.log.clear();
        d = round(d);
        assertThat(doris.by(owner)).contains("DROP VIEW IF EXISTS `" + database + "`.`live`");
        assertThat(doris.objects).doesNotContainKey("live");
        assertThat(kube.v1().events().inNamespace(NS).list().getItems())
                .anyMatch(e -> e.getReason().equals("QueryWithdrawn"));
        doris.failing.clear();
        d = round(d);
        assertThat(doris.objects).containsEntry("live", "VIEW");
    }

    @Test
    void aRowPolicyDorisRefusesLeavesNoUserGranted() {
        LakeDashboard d = create();
        d.getSpec().users.get(0).rows = "region = 'east'";
        doris.failing.add(Pattern.compile("^CREATE ROW POLICY"));
        d = round(d);
        assertThat(d.getStatus().phase).isEqualTo("Degraded");
        assertThat(doris.by("root"))
                .contains(DashboardSql.revoke(database, "live", USER))
                .doesNotContain(DashboardSql.grant(database, "live", USER));
    }

    /** A dashboard over ods_x.orders labelled, its users cleared for L2 only. */
    private LakeDashboard masked() {
        labelShop(Map.of("orders.email", label("L3", "mask", "personal")));
        LakeDashboard d = create();
        d.getSpec().governance = governance(List.of("personal"));
        d.getSpec().governance.level = "L3";
        d.getSpec().access = new LakeDashboardSpec.Access();
        d.getSpec().access.level = "L2";
        return round(d);
    }

    @Test
    void aMaskingViewDorisRefusesLeavesNoUserGranted() {
        LakeDashboard d = round(create());
        assertThat(doris.by("root")).contains(DashboardSql.grant(database, "live", USER));
        labelShop(Map.of("orders.email", label("L3", "mask", "personal")));
        d.getSpec().governance = governance(List.of("personal"));
        d.getSpec().governance.level = "L3";
        d.getSpec().access = new LakeDashboardSpec.Access();
        d.getSpec().access.level = "L2";
        d.getMetadata().setGeneration(2L);
        doris.failing.add(Pattern.compile("^CREATE OR REPLACE VIEW `pv_"));
        doris.log.clear();
        d = round(d);
        assertThat(d.getStatus().phase).isEqualTo("Degraded");
        assertThat(doris.by("root")).contains(DashboardSql.revoke(database, "live", USER));
    }

    @Test
    void aCopyIsMadeAnewWhenTheMasksOfWhatItCopiesChange() {
        LakeDashboard d = masked();
        assertThat(d.getStatus().phase).as(d.getStatus().message).isEqualTo("Ready");
        // amount gets a label of its own: the view changes, and the copies with it.
        labelShop(
                Map.of(
                        "orders.email",
                        label("L3", "mask", "personal"),
                        "orders.amount",
                        label("L3", "mask", "personal")));
        doris.log.clear();
        d = round(d);
        assertThat(doris.by(owner))
                .anyMatch(l -> l.startsWith("CREATE MATERIALIZED VIEW `" + database + "`.`totals`"))
                .anyMatch(l -> l.startsWith("CREATE TABLE `" + database + "`.`hot`"));
    }

    @Test
    void aCatalogThatCannotSayTheColumnsLeavesNoUserGranted() {
        LakeDashboard d = masked();
        tableDown = true;
        doris.log.clear();
        d = round(d);
        assertThat(d.getStatus().phase).isEqualTo("Degraded");
        assertThat(doris.by("root")).contains(DashboardSql.revoke(database, "live", USER));
        // Nothing dropped: the objects stay for when the catalog answers.
        assertThat(doris.log).noneMatch(l -> l.contains("DROP"));
        tableDown = false;
        d = round(d);
        assertThat(d.getStatus().phase).isEqualTo("Ready");
        assertThat(doris.by("root")).contains(DashboardSql.grant(database, "live", USER));
    }

    @Test
    void aShadowDatabaseNoLongerNeededGoes() {
        labelShop(Map.of("orders.email", label("L3", "mask", "personal")));
        LakeDashboard d = create();
        d.getSpec().governance = governance(List.of("personal"));
        d.getSpec().governance.level = "L3";
        d.getSpec().access = new LakeDashboardSpec.Access();
        d.getSpec().access.level = "L2";
        d = round(d);
        String shadow = DashboardSql.shadowDatabase(d, "ods_x");
        assertThat(d.getStatus().shadowDatabases).containsExactly(shadow);
        d.getSpec().access.level = "L3";
        d.getSpec().access.categories = List.of("personal");
        d.getMetadata().setGeneration(2L);
        d = round(d);
        assertThat(doris.by("root")).contains(DashboardSql.dropDatabase(shadow));
        assertThat(d.getStatus().shadowDatabases).isEmpty();
    }

    @Test
    void aLabelOnAColumnTheTableLostStopsTheDashboard() {
        labelShop(Map.of("orders.phone", label("L3", "mask", "personal")));
        LakeDashboard d = create();
        d.getSpec().governance = governance(List.of("personal"));
        d.getSpec().governance.level = "L3";
        d = round(d);
        assertThat(d.getStatus().phase).isEqualTo("Failed");
        assertThat(d.getStatus().message)
                .isEqualTo("ods_x.orders: the label of phone names no column of the table");
        assertThat(control.getScheduleDelay()).isPresent();
    }

    @Test
    void eachUserSeesItsRowsAndAUserWithoutAConditionNone() {
        kube.resource(
                        new SecretBuilder()
                                .withNewMetadata()
                                .withName("viewer")
                                .withNamespace(NS)
                                .endMetadata()
                                .addToData("password", "dmlld2Vy")
                                .build())
                .create();
        LakeDashboard d = create();
        d.getSpec().users.get(0).rows = "region = 'east'";
        LakeDashboardSpec.User viewer = new LakeDashboardSpec.User();
        viewer.name = "viewer";
        viewer.passwordSecret = new LakeDashboardSpec.SecretKey();
        viewer.passwordSecret.name = "viewer";
        viewer.queries = List.of("live");
        d.getSpec().users = List.of(d.getSpec().users.get(0), viewer);
        d = round(d);
        assertThat(d.getStatus().phase).as(d.getStatus().message).isEqualTo("Ready");
        List<String> root = doris.by("root");
        String analyst = USER;
        String eastLive = DashboardSql.rowPolicyName(analyst, database, "live", "region = 'east'");
        int policy =
                root.indexOf(
                        DashboardSql.createRowPolicy(
                                eastLive, database, "live", analyst, "region = 'east'"));
        int grant = root.indexOf(DashboardSql.grant(database, "live", analyst));
        // The policy comes before the grant: never a moment with the grant alone.
        assertThat(policy).isNotNegative().isLessThan(grant);
        String viewerUser = DashboardSql.user(d, "viewer");
        assertThat(root)
                .contains(
                        DashboardSql.createRowPolicy(
                                DashboardSql.rowPolicyName(viewerUser, database, "live", null),
                                database,
                                "live",
                                viewerUser,
                                null));
        assertThat(d.getStatus().rowPolicies).hasSize(3).containsEntry(eastLive, "live");
        // A changed condition: its policy is made before the old one goes.
        doris.log.clear();
        d.getSpec().users.get(0).rows = "region = 'west'";
        d.getMetadata().setGeneration(2L);
        d = round(d);
        root = doris.by("root");
        String westLive = DashboardSql.rowPolicyName(analyst, database, "live", "region = 'west'");
        assertThat(
                        root.indexOf(
                                DashboardSql.createRowPolicy(
                                        westLive, database, "live", analyst, "region = 'west'")))
                .isNotNegative()
                .isLessThan(root.indexOf(DashboardSql.dropRowPolicy(eastLive, database, "live")));
        assertThat(d.getStatus().rowPolicies).containsKey(westLive).doesNotContainKey(eastLive);
        // A policy that went missing in Doris is made again.
        doris.policies.remove(westLive);
        doris.log.clear();
        round(d);
        assertThat(doris.policies).contains(westLive);
    }

    @Test
    void aCatalogStatementsErrorDoesNotCarryTheToken() {
        doris.failing.add(Pattern.compile("^CREATE CATALOG"));
        // Doris echoes the statement, token and all.
        doris.echo = true;
        LakeDashboard d = round(create());
        assertThat(d.getStatus().phase).isEqualTo("Degraded");
        assertThat(d.getStatus().message).contains("<redacted>").doesNotContain("colvenna_secret");
        assertThat(kube.v1().events().inNamespace(NS).list().getItems())
                .noneMatch(e -> String.valueOf(e.getMessage()).contains("colvenna_secret"));
    }

    @Test
    void deletingDropsWhatTheDashboardsNamesSayEvenWithoutAStatus() {
        LakeDashboard d = create();
        doris.log.clear();
        reconciler.cleanup(d, null);
        assertThat(doris.by("root"))
                .containsExactly(
                        "DROP USER IF EXISTS '" + USER + "'@'%'",
                        "DROP USER IF EXISTS '" + owner + "'@'%'",
                        "DROP DATABASE IF EXISTS `" + database + "` FORCE",
                        "DROP CATALOG IF EXISTS `" + catalogName + "`",
                        "DROP WORKLOAD GROUP IF EXISTS `" + DashboardSql.workloadGroup(d) + "`");
    }

    @Test
    void aMaterializedViewsRefreshIsWhatDorisSaysItStarted() {
        LakeDashboard d = round(create());
        doris.refreshed.put("totals", Instant.parse("2026-10-15T00:00:20Z").getEpochSecond());
        now.set(now.get().plusSeconds(30));
        d = round(d);
        LakeDashboardStatus.QueryState totals = d.getStatus().queries.get(0);
        assertThat(totals.refreshedAt).isEqualTo("2026-10-15T00:00:20Z");
        assertThat(totals.refreshAgeSeconds).isEqualTo(10L);
    }

    @Test
    void aDeclarationThatCannotBeServedIsRefused() {
        LakeDashboardSpec spec = new LakeDashboardSpec();
        spec.tables = List.of("dws_x.segments");
        spec.queries = List.of(query("totals", "materialized", "SELECT 1"));
        assertThat(LakeDashboardReconciler.validate(spec, "lake_x"))
                .contains("declares governance");
        spec.governance = governance(List.of());
        assertThat(LakeDashboardReconciler.validate(spec, "lake_x")).isNull();
        spec.queries = List.of(query("totals", "external", "DELETE FROM x"));
        assertThat(LakeDashboardReconciler.validate(spec, "lake_x")).contains("not a SELECT");
        spec.queries = List.of(query("totals", "external", "SELECT 1; DROP DATABASE x"));
        assertThat(LakeDashboardReconciler.validate(spec, "lake_x"))
                .contains("more than one statement");
        spec.queries =
                List.of(query("totals", "external", "SELECT * FROM information_schema.tables"));
        assertThat(LakeDashboardReconciler.validate(spec, "lake_x"))
                .contains("schema of Doris itself");
        // Past the masking views: the catalog named, a system table.
        spec.queries.get(0).sql = "SELECT * FROM `lake_x`.ods_x.orders";
        assertThat(LakeDashboardReconciler.validate(spec, "lake_x"))
                .contains("dashboard's catalog");
        spec.queries.get(0).sql = "SELECT * FROM ods_x.`orders$audit_log`";
        assertThat(LakeDashboardReconciler.validate(spec, "lake_x")).contains("system table");
        spec.queries.get(0).sql = "SELECT '$' FROM ods_x.orders";
        assertThat(LakeDashboardReconciler.validate(spec, "lake_x")).isNull();
        // The catalog in a string (a table function's argument), or past a quote in a name.
        spec.queries.get(0).sql = "SELECT 'lake_x.ods_x.orders' FROM ods_x.orders";
        assertThat(LakeDashboardReconciler.validate(spec, "lake_x"))
                .contains("dashboard's catalog");
        spec.queries.get(0).sql = "SELECT email AS `e'` FROM `lake_x`.ods_x.orders AS `o'`";
        assertThat(LakeDashboardReconciler.validate(spec, "lake_x"))
                .contains("dashboard's catalog");
        spec.queries.get(0).sql =
                "SELECT * FROM `o'`.x WHERE 1 = 1 AND `a'` IN (SELECT * FROM ods_x.`orders$files`)";
        assertThat(LakeDashboardReconciler.validate(spec, "lake_x")).contains("system table");
        spec.queries.get(0).sql =
                "SELECT * FROM paimon_meta('table' = 'ods_x.orders', 'query_type' = 'files')";
        assertThat(LakeDashboardReconciler.validate(spec, "lake_x")).contains("table function");
        // Users cleared above what the dashboard is classified for.
        spec.queries.get(0).sql = "SELECT 1";
        spec.access = new LakeDashboardSpec.Access();
        spec.access.level = "L3";
        assertThat(LakeDashboardReconciler.validate(spec, "lake_x"))
                .contains("above the dashboard's own");
        spec.access = null;
        spec.queries = List.of(query("totals", "external", "SELECT 1"));
        LakeDashboardSpec.User u = new LakeDashboardSpec.User();
        u.name = "analyst";
        u.queries = List.of("missing");
        spec.users = List.of(u);
        assertThat(LakeDashboardReconciler.validate(spec, "lake_x")).contains("not declared");
    }

    @Test
    void anInternalCopyIsRewrittenOnItsSchedule() {
        LakeDashboardSpec.Query q = query("hot", "internal", "SELECT 1");
        q.refresh = "2m";
        LakeDashboardStatus.QueryState s = new LakeDashboardStatus.QueryState();
        Instant t0 = Instant.parse("2026-10-15T10:00:00Z");
        assertThat(LakeDashboardReconciler.due(s, q, t0)).isTrue();
        s.refreshedAt = t0.toString();
        assertThat(LakeDashboardReconciler.due(s, q, t0.plusSeconds(60))).isFalse();
        assertThat(LakeDashboardReconciler.due(s, q, t0.plusSeconds(120))).isTrue();
    }
}
