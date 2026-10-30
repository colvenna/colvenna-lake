package com.colvenna.lake.operator.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.OperatorSettings;
import com.colvenna.lake.operator.crd.CatalogIdentity;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceSpec;
import com.colvenna.lake.operator.crd.LakeSourceStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.junit.jupiter.api.Test;

class MongoSourceTest {
    static LakeSourceSpec spec() {
        LakeSourceSpec spec = new LakeSourceSpec();
        spec.type = "mongodb";
        spec.connection = new LakeSourceSpec.Connection();
        spec.connection.host = "mongo-0.mongo.shop.svc";
        spec.connection.database = "shop";
        spec.connection.credentialsSecret = new LakeSourceSpec.SecretRef();
        spec.connection.credentialsSecret.name = "shop-mongo";
        spec.connection.caConfigMap = "mongo-ca";
        spec.connection.authSource = "admin";
        spec.target = new LakeSourceSpec.Target();
        spec.target.database = "ods_shop";
        return spec;
    }

    @Test
    void aMongoSourceNamesItsDatabaseAndNoVersionColumn() {
        assertThat(LakeSourceReconciler.validate(spec())).isNull();
        LakeSourceSpec versioned = spec();
        versioned.target.versionColumn = "version_no";
        assertThat(LakeSourceReconciler.validate(versioned)).contains("keyed by _id");
        LakeSourceSpec noDatabase = spec();
        noDatabase.connection.database = null;
        assertThat(LakeSourceReconciler.validate(noDatabase)).contains("connection.database");
        assertThat(SourceJobs.defaultPort(spec())).isEqualTo(27017);
        assertThat(SourceJobs.versionColumn(spec())).isNull();
    }

    @Test
    void aCollectionDoesNotJoinARunningJob() {
        assertThat(LakeSourceReconciler.addedAfterStart(spec(), List.of())).isNull();
        Admission.Verdict later = LakeSourceReconciler.addedAfterStart(spec(), List.of("orders"));
        assertThat(later.admitted()).isFalse();
        assertThat(later.reason()).isEqualTo("AddedAfterStart");
        assertThat(later.message()).contains("[orders]").contains("FLINK-40741");
        LakeSourceSpec mysql = spec();
        mysql.type = "mysql";
        assertThat(LakeSourceReconciler.addedAfterStart(mysql, List.of("orders"))).isNull();
    }

    @Test
    void theTargetsOfTablesThatLeaveStayTheSourcesToDrop() {
        LakeSourceStatus.SourceTable gone = table("customers", "Ready");
        LakeSourceStatus.SourceTable replaced = table("catalog", "Failed");
        LakeSourceStatus.SourceTable refused = table("ledger", "Rejected");
        LakeSourceStatus.SourceTable kept = table("orders", "Ready");
        List<String> retired = new java.util.ArrayList<>(List.of("customers"));
        LakeSourceReconciler.retire(
                List.of(gone, replaced, refused, kept), java.util.Set.of("orders"), retired);
        // Once each, the failed one too; the rejected one never had a target of its own.
        assertThat(retired).containsExactly("customers", "catalog");
    }

    private static LakeSourceStatus.SourceTable table(String name, String state) {
        LakeSourceStatus.SourceTable t = new LakeSourceStatus.SourceTable();
        t.name = name;
        t.target = name;
        t.state = state;
        return t;
    }

    @Test
    void oneJobReadsTheAdmittedCollectionsIntoLookupChangelogTables() throws Exception {
        LakeSource source = new LakeSource();
        source.setMetadata(
                new ObjectMetaBuilder()
                        .withName("shop")
                        .withNamespace("colvenna-data")
                        .withUid("abcdef12-0000-0000-0000-000000000000")
                        .build());
        source.setSpec(spec());
        LakeSourceStatus status = new LakeSourceStatus();
        status.identity = new CatalogIdentity();
        status.identity.tokenId = "t1";
        source.setStatus(status);
        LakeSourceStatus.SourceTable orders = new LakeSourceStatus.SourceTable();
        orders.name = "orders";
        orders.target = "orders";
        var desired =
                new SourceJobs(
                                new OperatorSettings(
                                        "https://c",
                                        Path.of("/t"),
                                        Path.of("/ca"),
                                        "s3://b/flink-state",
                                        "colvenna-flink",
                                        Map.of("mongodb", "repo/img@sha256:m"),
                                        Map.of(),
                                        List.of(),
                                        6000,
                                        6999,
                                        1000,
                                        9100))
                        .desired(
                                source,
                                status,
                                List.of(orders),
                                new SourceJobs.Context(false, "v1", "v2", Map.of()));
        assertThat(desired).containsOnlyKeys("shop-cdc");
        FlinkDeployment fd = desired.get("shop-cdc").deployment();
        assertThat(fd.getSpec().getImage()).isEqualTo("repo/img@sha256:m");
        assertThat(fd.getSpec().getJob().getEntryClass())
                .isEqualTo("com.colvenna.lake.jobs.cdc.MongoCdcJob");
        JsonNode arg = new ObjectMapper().readTree(fd.getSpec().getJob().getArgs()[0]);
        assertThat(arg.path("tables").toString()).isEqualTo("[\"orders\"]");
        assertThat(arg.path("port").asInt()).isEqualTo(27017);
        assertThat(arg.path("authSource").asText()).isEqualTo("admin");
        assertThat(arg.has("versionColumn")).isFalse();
        JsonNode options = arg.path("tableOptions");
        assertThat(options.path("changelog-producer").asText()).isEqualTo("lookup");
        assertThat(options.path("write-only").asText()).isEqualTo("false");
        assertThat(options.has("sequence.field")).isFalse();
        assertThat(fd.getSpec().getJob().getArgs()[0]).doesNotContain("password");
    }

    @Test
    void aLostOplogPositionAndAnUnsupportedChangeNameWhatTheyHit() {
        assertThat(
                        LakeSourceReconciler.positionLost(
                                "MongoCommandException: Command failed with error 286"
                                        + " (ChangeStreamHistoryLost)"))
                .isTrue();
        assertThat(LakeSourceReconciler.positionLost("Connection refused")).isFalse();
        assertThat(
                        LakeSourceReconciler.tablesNamed(
                                "IllegalStateException: SOURCE_UNSUPPORTED_CHANGE table=customers"
                                        + " op=drop",
                                "ods_shop"))
                .containsExactly("customers");
    }
}
