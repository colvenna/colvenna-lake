package com.colvenna.lake.operator.lineage;

import static com.colvenna.lake.operator.lineage.Fixtures.NAMING;
import static com.colvenna.lake.operator.lineage.Fixtures.NS;
import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.crd.LakeDashboard;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.crd.LakeTask;
import com.colvenna.lake.operator.lineage.LineageGraph.Dataset;
import com.colvenna.lake.operator.lineage.LineageGraph.Graph;
import com.colvenna.lake.operator.lineage.LineageGraph.Run;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.junit.jupiter.api.Test;

/** The runs the operator reports, from the declarations and their deployments. */
class LineageGraphTest {
    private final Fixtures.Catalog catalog = new Fixtures.Catalog();

    private Graph graph(
            List<LakeSource> sources,
            List<LakeTable> tables,
            List<LakeTask> tasks,
            List<LakeDashboard> dashboards,
            List<FlinkDeployment> deployments) {
        return LineageGraph.of(
                new LineageGraph.Namespace(NS, sources, tables, tasks, dashboards, deployments),
                catalog::schema,
                NAMING);
    }

    private static Map<String, Run> byJob(Graph g) {
        return g.runs().stream()
                .collect(Collectors.toMap(r -> r.job().name(), Function.identity()));
    }

    private static List<String> names(List<Dataset> datasets) {
        return datasets.stream().map(d -> d.namespace() + " " + d.name()).toList();
    }

    @Test
    void theTablePathFromTheSourceToDwsRunsTheDeployedVersions() {
        LakeSource src = Fixtures.source();
        LakeTable dwd = Fixtures.dwd("h-dwd");
        LakeTable dws = Fixtures.dws("h-dws");
        Graph g =
                graph(
                        List.of(src),
                        List.of(dwd, dws),
                        List.of(),
                        List.of(),
                        List.of(
                                Fixtures.cdc(src, "h-src", "j-src"),
                                Fixtures.tableJob(dwd, "j-dwd", true),
                                Fixtures.tableJob(dws, "j-dws", true)));
        Map<String, Run> runs = byJob(g);
        assertThat(runs)
                .containsOnlyKeys("orders-src-cdc", "dwd-orders-table", "dws-segments-table");
        assertThat(g.unsettled()).isEmpty();

        Run source = runs.get("orders-src-cdc");
        assertThat(names(source.inputs()))
                .containsExactly(
                        "mysql://mysql.example.internal:3306 shop.orders",
                        "mysql://mysql.example.internal:3306 shop.segments");
        assertThat(names(source.outputs()))
                .containsExactly(
                        "paimon://colvenna-catalog.colvenna-catalog.svc:8181 colvenna.ods.orders",
                        "paimon://colvenna-catalog.colvenna-catalog.svc:8181 colvenna.ods.segments");
        assertThat(source.job().version()).isEqualTo("h-src");
        assertThat(source.job().kind().streaming()).isTrue();
        assertThat(source.facts())
                .containsEntry("flinkJobId", "j-src")
                .containsEntry("specHash", "h-src")
                .containsEntry("deployment", "orders-src-cdc")
                .containsEntry("kind", "LakeSource")
                .containsEntry("provenance", "declared");
        assertThat(source.engineVersion()).isEqualTo("1.20");

        // What the planner found the query streams and looks up; the quarantine is written too.
        Run dwdRun = runs.get("dwd-orders-table");
        assertThat(names(dwdRun.inputs()))
                .containsExactly(
                        "paimon://colvenna-catalog.colvenna-catalog.svc:8181 colvenna.ods.orders",
                        "paimon://colvenna-catalog.colvenna-catalog.svc:8181 colvenna.ods.segments");
        assertThat(names(dwdRun.outputs()))
                .containsExactly(
                        "paimon://colvenna-catalog.colvenna-catalog.svc:8181 colvenna.dwd.orders",
                        "paimon://colvenna-catalog.colvenna-catalog.svc:8181 colvenna.dwd.orders_quarantine");
        assertThat(dwdRun.facts()).containsEntry("provenance", "planner");
        assertThat(dwdRun.job().sql()).contains("FOR SYSTEM_TIME AS OF");
        assertThat(names(runs.get("dws-segments-table").inputs()))
                .containsExactly(
                        "paimon://colvenna-catalog.colvenna-catalog.svc:8181 colvenna.dwd.orders");

        // The lake's datasets carry colvenna-catalog's columns.
        Dataset orders = dwdRun.inputs().get(0);
        assertThat(orders.columns())
                .extracting(LineageGraph.Column::name, LineageGraph.Column::type)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("id", "BIGINT NOT NULL"),
                        org.assertj.core.groups.Tuple.tuple("customer_id", "BIGINT"),
                        org.assertj.core.groups.Tuple.tuple("pt", "TIMESTAMP(3)"));
    }

    @Test
    void aRunIsItsVersionAndFlinkJob() {
        LakeTable dwd = Fixtures.dwd("h1");
        String first =
                byJob(
                                graph(
                                        List.of(),
                                        List.of(dwd),
                                        List.of(),
                                        List.of(),
                                        List.of(Fixtures.tableJob(dwd, "j1", true))))
                        .get("dwd-orders-table")
                        .id();
        assertThat(
                        byJob(
                                        graph(
                                                List.of(),
                                                List.of(dwd),
                                                List.of(),
                                                List.of(),
                                                List.of(Fixtures.tableJob(dwd, "j1", true))))
                                .get("dwd-orders-table")
                                .id())
                .isEqualTo(first);
        assertThat(
                        byJob(
                                        graph(
                                                List.of(),
                                                List.of(dwd),
                                                List.of(),
                                                List.of(),
                                                List.of(Fixtures.tableJob(dwd, "j2", true))))
                                .get("dwd-orders-table")
                                .id())
                .isNotEqualTo(first);
        LakeTable upgraded = Fixtures.dwd("h2");
        assertThat(
                        byJob(
                                        graph(
                                                List.of(),
                                                List.of(upgraded),
                                                List.of(),
                                                List.of(),
                                                List.of(Fixtures.tableJob(upgraded, "j1", true))))
                                .get("dwd-orders-table")
                                .id())
                .isNotEqualTo(first);
    }

    @Test
    void aDeploymentInTransitionKeepsItsLastReport() {
        LakeTable dwd = Fixtures.dwd("h-new");
        // The status still describes the version before: an upgrade under way.
        FlinkDeployment old = Fixtures.tableJob(Fixtures.dwd("h-old"), "j1", true);
        old.getMetadata().getOwnerReferences().get(0).setUid(dwd.getMetadata().getUid());
        Graph g = graph(List.of(), List.of(dwd), List.of(), List.of(), List.of(old));
        assertThat(g.runs()).isEmpty();
        assertThat(g.unsettled()).containsExactly("dwd-orders-table");
        // Not yet running the spec last written.
        g =
                graph(
                        List.of(),
                        List.of(dwd),
                        List.of(),
                        List.of(),
                        List.of(Fixtures.tableJob(dwd, "j1", false)));
        assertThat(g.runs()).isEmpty();
        assertThat(g.unsettled()).containsExactly("dwd-orders-table");
        // Failed: a run that failed.
        FlinkDeployment failed = Fixtures.tableJob(dwd, "j1", false);
        failed.getStatus().getJobStatus().setState(org.apache.flink.api.common.JobStatus.FAILED);
        g = graph(List.of(), List.of(dwd), List.of(), List.of(), List.of(failed));
        assertThat(g.runs()).extracting(Run::state).containsExactly(LineageGraph.FAIL);
        assertThat(g.unsettled()).isEmpty();
    }

    @Test
    void aSourceOverKafkaReadsTheTopicsAndAKafkaSourceItsEvents() {
        LakeSource viaKafka = Fixtures.source();
        viaKafka.getSpec().transport =
                new com.colvenna.lake.operator.crd.LakeSourceSpec.Transport();
        viaKafka.getSpec().transport.kafka =
                new com.colvenna.lake.operator.crd.LakeSourceSpec.KafkaTransport();
        viaKafka.getSpec().transport.kafka.bootstrapServers = "b1:9093,b2:9093";
        viaKafka.getSpec().transport.kafka.topicPrefix = "dbz";
        Run run =
                graph(
                                List.of(viaKafka),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(Fixtures.cdc(viaKafka, "h", "j")))
                        .runs()
                        .get(0);
        assertThat(names(run.inputs()))
                .containsExactly(
                        "kafka://b1:9093 dbz.shop.orders", "kafka://b1:9093 dbz.shop.segments");

        LakeSource events = Fixtures.named(new LakeSource(), "clicks");
        events.setSpec(new com.colvenna.lake.operator.crd.LakeSourceSpec());
        events.getSpec().type = "kafka";
        events.getSpec().kafka = new com.colvenna.lake.operator.crd.LakeSourceSpec.Kafka();
        events.getSpec().kafka.bootstrapServers = "k:9093";
        events.getSpec().kafka.topics.add("clicks");
        events.getSpec().target = new com.colvenna.lake.operator.crd.LakeSourceSpec.Target();
        events.getSpec().target.database = "ods_web";
        events.getSpec().target.table = "clicks";
        events.setStatus(new com.colvenna.lake.operator.crd.LakeSourceStatus());
        run =
                graph(
                                List.of(events),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(
                                        Fixtures.deployment(
                                                events, "clicks-events", "h", "j", true, "{}")))
                        .runs()
                        .get(0);
        assertThat(names(run.inputs())).containsExactly("kafka://k:9093 clicks");
        assertThat(names(run.outputs()))
                .containsExactly(
                        "paimon://colvenna-catalog.colvenna-catalog.svc:8181 colvenna.ods_web.clicks",
                        "paimon://colvenna-catalog.colvenna-catalog.svc:8181 colvenna.ods_web.clicks_rejections");
    }

    @Test
    void aPostgresTableIsNamedWithItsSchema() {
        LakeSource pg = Fixtures.source();
        pg.getSpec().type = "postgres";
        pg.getSpec().connection.schema = "public";
        pg.getSpec().connection.port = 5433;
        Run run =
                graph(
                                List.of(pg),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(
                                        Fixtures.deployment(
                                                pg,
                                                "orders-src-orders",
                                                "h",
                                                "j",
                                                true,
                                                "{\"targetTable\":\"orders\"}")))
                        .runs()
                        .get(0);
        assertThat(names(run.inputs()))
                .containsExactly("postgres://mysql.example.internal:5433 shop.public.orders");
        assertThat(names(run.outputs()))
                .containsExactly(
                        "paimon://colvenna-catalog.colvenna-catalog.svc:8181 colvenna.ods.orders");
    }

    @Test
    void aMongoCollectionIsNamedInItsDatabase() {
        LakeSource mongo = Fixtures.source();
        mongo.getSpec().type = "mongodb";
        Run run =
                graph(
                                List.of(mongo),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(Fixtures.cdc(mongo, "h", "j")))
                        .runs()
                        .get(0);
        assertThat(names(run.inputs()))
                .containsExactly(
                        "mongodb://mysql.example.internal:27017 shop.orders",
                        "mongodb://mysql.example.internal:27017 shop.segments");
    }

    @Test
    void aBackfillsAttemptsAreBatchRunsOfItsTablesQuery() {
        LakeTable dwd = Fixtures.dwd("h-dwd");
        Graph g =
                graph(
                        List.of(),
                        List.of(dwd),
                        List.of(Fixtures.backfill("bf", "Running", 2, "Failed")),
                        List.of(),
                        // Suspended for the backfill: its job argument says what query it runs.
                        List.of(Fixtures.tableJob(dwd, "j1", false)));
        assertThat(g.runs())
                .extracting(r -> r.job().name(), r -> r.state(), r -> r.facts().get("attempt"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("LakeTask.bf", LineageGraph.FAIL, "1"),
                        org.assertj.core.groups.Tuple.tuple(
                                "LakeTask.bf", LineageGraph.RUNNING, "2"));
        Run attempt = g.runs().get(1);
        assertThat(attempt.job().kind().streaming()).isFalse();
        assertThat(attempt.job().sql()).isEqualTo(dwd.getSpec().query);
        // The second attempt started when the first ended.
        assertThat(g.runs().get(0).startedAt()).hasToString("2026-10-17T01:00:00Z");
        assertThat(attempt.startedAt()).isEqualTo(g.runs().get(0).endedAt());
        assertThat(names(attempt.inputs()))
                .containsExactly(
                        "paimon://colvenna-catalog.colvenna-catalog.svc:8181 colvenna.ods.orders",
                        "paimon://colvenna-catalog.colvenna-catalog.svc:8181 colvenna.ods.segments");
        assertThat(attempt.facts())
                .containsEntry("kubernetesJob", "bf-2")
                .containsEntry("specHash", "h-dwd");
        // Maintenance moves no rows.
        LakeTask compact = Fixtures.backfill("compact", "Running", 1);
        compact.getSpec().type = "compact";
        assertThat(graph(List.of(), List.of(dwd), List.of(compact), List.of(), List.of()).runs())
                .isEmpty();
    }

    @Test
    void aDashboardQueryReadsTheDeclaredTablesItsSqlNames() {
        Run run =
                graph(List.of(), List.of(), List.of(), List.of(Fixtures.dashboard()), List.of())
                        .runs()
                        .get(0);
        assertThat(run.job().name()).isEqualTo("LakeDashboard.sales.by_label");
        assertThat(run.job().kind().type()).isEqualTo("MATERIALIZED_VIEW");
        assertThat(run.job().kind().integration()).isEqualTo("DORIS");
        assertThat(run.job().version()).isEqualTo("a1");
        assertThat(names(run.inputs()))
                .containsExactly(
                        "paimon://colvenna-catalog.colvenna-catalog.svc:8181 colvenna.dws.segments");
        assertThat(run.outputs())
                .extracting(Dataset::namespace, Dataset::name, Dataset::type, Dataset::subType)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "doris://doris-fe.colvenna-doris.svc:9030",
                                "internal.dash_sales.by_label",
                                "VIEW",
                                "MATERIALIZED"));
    }
}
