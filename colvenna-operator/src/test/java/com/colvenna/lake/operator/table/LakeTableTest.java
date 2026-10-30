package com.colvenna.lake.operator.table;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.OperatorSettings;
import com.colvenna.lake.operator.crd.CatalogIdentity;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.crd.LakeTableSpec;
import com.colvenna.lake.operator.crd.LakeTableStatus;
import com.colvenna.lake.operator.flink.FlinkJobs;
import com.colvenna.lake.operator.kube.Creation;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ContainerStateTerminatedBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.apache.flink.kubernetes.operator.api.spec.UpgradeMode;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataTypes;
import org.junit.jupiter.api.Test;

class LakeTableTest {
    static final OperatorSettings SETTINGS =
            new OperatorSettings(
                    "https://colvenna-catalog:8181",
                    Path.of("/token"),
                    Path.of("/ca"),
                    "s3://bucket/flink-state",
                    "colvenna-flink",
                    Map.of("sql", "repo/colvenna-jobs@sha256:2"),
                    Map.of(),
                    List.of(),
                    6000,
                    6999,
                    1000,
                    9100);

    static LakeTable table() {
        LakeTable table = new LakeTable();
        table.setMetadata(
                new ObjectMetaBuilder()
                        .withName("orders-wide")
                        .withNamespace("colvenna-data")
                        .withUid("0123abcd-0000-0000-0000-000000000000")
                        .build());
        LakeTableSpec spec = new LakeTableSpec();
        spec.target = new LakeTableSpec.Target();
        spec.target.database = "dwd";
        spec.target.table = "orders";
        spec.primaryKey = List.of("order_id");
        spec.query = "SELECT order_id, amount FROM ods.orders";
        spec.upstream = List.of("ods.orders");
        table.setSpec(spec);
        LakeTableStatus status = new LakeTableStatus();
        status.identity = new CatalogIdentity();
        status.identity.tokenId = "t1";
        status.validation = new LakeTableStatus.Validation();
        status.validation.streams = List.of("ods.orders");
        table.setStatus(status);
        return table;
    }

    @Test
    void checksGoToTheJobAndTheQuarantineMirrorsTheTarget() throws Exception {
        LakeTable table = table();
        LakeTableSpec.Quality quality = new LakeTableSpec.Quality();
        quality.notNull = List.of("order_id");
        quality.allowed = Map.of("state", List.of("new", "paid"));
        LakeTableSpec.Range range = new LakeTableSpec.Range();
        range.min = "0";
        quality.ranges = Map.of("amount", range, "state", range);
        table.getSpec().quality = quality;
        List<DataField> output =
                List.of(
                        new DataField(0, "order_id", DataTypes.BIGINT()),
                        new DataField(1, "amount", DataTypes.DECIMAL(12, 2)),
                        new DataField(2, "state", DataTypes.STRING()));
        // A range of text is refused; so is a column the query does not output.
        assertThat(LakeTableReconciler.qualityProblems(table.getSpec(), output))
                .singleElement()
                .satisfies(p -> assertThat(p).contains("a range of state"));
        quality.ranges = Map.of("amount", range, "missing", range);
        assertThat(LakeTableReconciler.qualityProblems(table.getSpec(), output))
                .singleElement()
                .satisfies(p -> assertThat(p).contains("no column missing"));
        quality.ranges = Map.of("amount", range);
        assertThat(LakeTableReconciler.qualityProblems(table.getSpec(), output)).isEmpty();

        var argument =
                new ObjectMapper()
                        .readTree(TableJobs.argument(table, List.of("ods.orders")))
                        .path("quality");
        assertThat(argument.path("quarantine").asText()).isEqualTo("orders_quarantine");
        assertThat(argument.path("rules").toString())
                .contains("\"kind\":\"notNull\"")
                .contains("\"values\":[\"new\",\"paid\"]")
                .contains("\"min\":\"0\"");
        assertThat(TableJobs.quarantineFields(output))
                .extracting(DataField::name)
                .containsExactly(
                        "order_id", "amount", "state", "colvenna_rule", "colvenna_flagged_at");
        // Nobody streams the quarantine: no lookup changelog to compute.
        assertThat(TableJobs.quarantineOptions(table))
                .doesNotContainKey("changelog-producer")
                .containsEntry("merge-engine", "deduplicate");
    }

    @Test
    void theJobMayWriteItsTargetAndItsQuarantine() {
        assertThat(LakeTableReconciler.written("dwd.orders", null, null))
                .containsExactly("dwd.orders");
        assertThat(LakeTableReconciler.written("dwd.orders", "orders_quarantine", null))
                .containsExactly("dwd.orders", "dwd.orders_quarantine");
        // A new declaration waits: the running job still writes the quarantine it has.
        LakeTableStatus.Applied applied = new LakeTableStatus.Applied();
        applied.target = "dwd.orders";
        applied.quarantine = "orders_quarantine";
        assertThat(LakeTableReconciler.written("dwd.orders", null, applied))
                .containsExactly("dwd.orders", "dwd.orders_quarantine");
    }

    @Test
    void aDeclarationReadsOtherTablesWithOnlyManagedOptions() {
        LakeTableSpec spec = table().getSpec();
        assertThat(LakeTableReconciler.validate(spec)).isNull();
        spec.upstream = List.of("ods.orders", "dwd.orders");
        assertThat(LakeTableReconciler.validate(spec)).contains("reads itself");
        spec.upstream = List.of("orders");
        assertThat(LakeTableReconciler.validate(spec)).contains("not database.table");
        spec.upstream = List.of("ods.orders");
        spec.tableOptions = Map.of("merge-engine", "aggregation");
        assertThat(LakeTableReconciler.validate(spec)).contains("merge-engine is not allowed");
        spec.tableOptions = Map.of("consumer.expiration-time", "1 d");
        assertThat(LakeTableReconciler.validate(spec)).isNull();
    }

    @Test
    void onlyTablesOfTheNamespaceMayBeRead() {
        assertThat(Dependencies.refusal("ns", "ods.t", "LakeSource/ns/shop/u", null)).isNull();
        assertThat(Dependencies.refusal("ns", "dwd.t", "LakeTables/ns", "LakeTable/ns/x/u"))
                .isNull();
        assertThat(Dependencies.refusal("ns", "ods.t", "LakeSource/other/shop/u", null))
                .contains("namespace ns");
        assertThat(Dependencies.refusal("ns", "m2.t", null, null)).contains("namespace ns");
        // A LakeTable of another namespace, in a database named like this one's.
        assertThat(Dependencies.refusal("ns", "dwd.t", "LakeTables/ns", "LakeTable/other/x/u"))
                .isNotNull();
    }

    @Test
    void cyclesAreFoundThroughOtherTables() {
        Dependencies.Node a = new Dependencies.Node("dwd.a", List.of("dwd.b", "ods.x"));
        Dependencies.Node b = new Dependencies.Node("dwd.b", List.of("dws.c"));
        Dependencies.Node c = new Dependencies.Node("dws.c", List.of("dwd.a"));
        assertThat(Dependencies.cycle(a, List.of(b, c)))
                .containsExactly("dwd.a", "dwd.b", "dws.c", "dwd.a");
        assertThat(Dependencies.cycle(a, List.of(b))).isEmpty();
        Dependencies.Node self = new Dependencies.Node("dwd.s", List.of("dwd.s"));
        assertThat(Dependencies.cycle(self, List.of())).containsExactly("dwd.s", "dwd.s");
        // Others that loop among themselves do not make this walk run forever.
        Dependencies.Node d = new Dependencies.Node("dwd.d", List.of("dwd.e"));
        Dependencies.Node e = new Dependencies.Node("dwd.e", List.of("dwd.d"));
        Dependencies.Node f = new Dependencies.Node("dwd.f", List.of("dwd.d"));
        assertThat(Dependencies.cycle(f, List.of(d, e))).isEmpty();
    }

    @Test
    void theJobUpgradesWithSavepointsAndARollbackKeepsItsHash() throws Exception {
        LakeTable table = table();
        TableJobs jobs = new TableJobs(SETTINGS);
        FlinkDeployment fd = jobs.desired(table, table.getStatus());
        assertThat(fd.getMetadata().getName()).isEqualTo("orders-wide-table");
        assertThat(fd.getSpec().getJob().getUpgradeMode()).isEqualTo(UpgradeMode.SAVEPOINT);
        assertThat(fd.getSpec().getJob().getEntryClass()).isEqualTo(TableJobs.ENTRY);
        var arg = new ObjectMapper().readTree(fd.getSpec().getJob().getArgs()[0]);
        assertThat(arg.path("streams").get(0).asText()).isEqualTo("ods.orders");
        assertThat(arg.path("consumerId").asText()).isEqualTo("colvenna-data.orders-wide.0123abcd");
        assertThat(arg.toString()).doesNotContain("token");

        LakeTableStatus.Redeploy redeploy = new LakeTableStatus.Redeploy();
        redeploy.savepoint = "s3://bucket/flink-state/sp-1";
        redeploy.nonce = 1L;
        table.getStatus().redeploy = redeploy;
        FlinkDeployment after = jobs.desired(table, table.getStatus());
        assertThat(after.getSpec().getJob().getInitialSavepointPath())
                .isEqualTo(redeploy.savepoint);
        assertThat(FlinkJobs.hashOf(after)).isEqualTo(FlinkJobs.hashOf(fd));
        table.getSpec().query = "SELECT order_id, amount FROM ods.orders WHERE amount > 0";
        assertThat(FlinkJobs.hashOf(jobs.desired(table, table.getStatus())))
                .isNotEqualTo(FlinkJobs.hashOf(fd));
    }

    @Test
    void theTargetsChangelogComesFromTheRowsItHeld() {
        LakeTable table = table();
        table.getSpec().tableOptions = Map.of("snapshot.time-retained", "2 h");
        assertThat(TableJobs.tableOptions(table))
                .containsEntry("changelog-producer", "lookup")
                .containsEntry("merge-engine", "deduplicate")
                .doesNotContainKey("write-only")
                .containsEntry("snapshot.time-retained", "2 h")
                .containsEntry("consumer.expiration-time", "1 d")
                .containsEntry(
                        "colvenna-lake.owner",
                        "LakeTable/colvenna-data/orders-wide/0123abcd-0000-0000-0000-000000000000");
    }

    @Test
    void aQueryFitsItsTargetWhenColumnsAndTypesMatchAndKeptNonNullsStay() {
        List<DataField> target =
                List.of(
                        new DataField(0, "k", DataTypes.BIGINT().notNull()),
                        new DataField(1, "v", DataTypes.STRING()));
        assertThat(
                        LakeTableReconciler.schemaMismatch(
                                target,
                                List.of(
                                        new DataField(0, "k", DataTypes.BIGINT().notNull()),
                                        new DataField(1, "v", DataTypes.STRING().notNull()))))
                .isNull();
        // A nullable output into a NOT NULL column, a new type, a new column.
        assertThat(
                        LakeTableReconciler.schemaMismatch(
                                target,
                                List.of(
                                        new DataField(0, "k", DataTypes.BIGINT()),
                                        new DataField(1, "v", DataTypes.STRING()))))
                .isNotNull();
        assertThat(
                        LakeTableReconciler.schemaMismatch(
                                target,
                                List.of(
                                        new DataField(0, "k", DataTypes.BIGINT().notNull()),
                                        new DataField(1, "v", DataTypes.INT()))))
                .isNotNull();
        assertThat(
                        LakeTableReconciler.schemaMismatch(
                                target,
                                List.of(
                                        new DataField(0, "k", DataTypes.BIGINT().notNull()),
                                        new DataField(1, "v", DataTypes.STRING()),
                                        new DataField(2, "w", DataTypes.STRING()))))
                .isNotNull();
    }

    @Test
    void theFirstDeclarationKeepsATargetEvenWithinOneSecond() {
        LakeTable a = table();
        LakeTable b = table();
        a.getMetadata().setCreationTimestamp("2026-10-15T00:00:00Z");
        b.getMetadata().setCreationTimestamp("2026-10-15T00:00:00Z");
        b.getMetadata().setUid("ffff0000-0000-0000-0000-000000000000");
        assertThat(Creation.before(a, b)).isTrue();
        assertThat(Creation.before(b, a)).isFalse();
        b.getMetadata().setCreationTimestamp("2026-10-14T23:59:59Z");
        assertThat(Creation.before(b, a)).isTrue();
    }

    @Test
    void theValidationVerdictIsReadFromTheTerminationMessage() throws Exception {
        String accepted =
                "{\"fields\":[{\"id\":0,\"name\":\"order_id\",\"type\":\"BIGINT NOT NULL\"},"
                        + "{\"id\":1,\"name\":\"tags\",\"type\":{\"type\":\"ARRAY\",\"element\":"
                        + "\"STRING\"}}],\"streams\":[\"ods.orders\"],\"lookups\":[\"ods.c\"]}";
        LakeTableStatus.Validation v =
                Validations.read(
                        new ContainerStateTerminatedBuilder()
                                .withExitCode(0)
                                .withMessage(gzipBase64(accepted))
                                .build());
        assertThat(v.reason).isNull();
        assertThat(v.streams).containsExactly("ods.orders");
        assertThat(v.lookups).containsExactly("ods.c");
        assertThat(LakeTableReconciler.fields(v))
                .extracting(f -> f.type())
                .containsExactly(DataTypes.BIGINT().notNull(), DataTypes.ARRAY(DataTypes.STRING()));

        LakeTableStatus.Validation refused =
                Validations.read(
                        new ContainerStateTerminatedBuilder()
                                .withExitCode(0)
                                .withMessage(
                                        gzipBase64(
                                                "{\"reason\":\"UnorderedChangelog\",\"message\":\"x\"}"))
                                .build());
        assertThat(refused.reason).isEqualTo("UnorderedChangelog");
        LakeTableStatus.Validation crashed =
                Validations.read(
                        new ContainerStateTerminatedBuilder()
                                .withExitCode(137)
                                .withReason("OOMKilled")
                                .build());
        assertThat(crashed.reason).isEqualTo("ValidationError");
        assertThat(crashed.message).contains("OOMKilled");
    }

    @Test
    void theValidationPodIsRestrictedAndHasNoKubernetesAccess() {
        var job = new Validations(SETTINGS, null).build(table(), "repo/img", "0123456789abcdef");
        var pod = job.getSpec().getTemplate().getSpec();
        assertThat(job.getMetadata().getName()).isEqualTo("orders-wide-validate-01234567");
        assertThat(pod.getAutomountServiceAccountToken()).isFalse();
        assertThat(pod.getSecurityContext().getRunAsNonRoot()).isTrue();
        var container = pod.getContainers().get(0);
        assertThat(container.getSecurityContext().getReadOnlyRootFilesystem()).isTrue();
        assertThat(container.getCommand()).contains(TableJobs.VALIDATE);
        assertThat(String.join(" ", container.getCommand())).doesNotContain("token\"");
        assertThat(job.getSpec().getActiveDeadlineSeconds()).isEqualTo(300L);
    }

    private static String gzipBase64(String text) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
