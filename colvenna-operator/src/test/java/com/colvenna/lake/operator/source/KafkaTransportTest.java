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

class KafkaTransportTest {
    static LakeSourceSpec spec() {
        LakeSourceSpec spec = new LakeSourceSpec();
        spec.type = "mysql";
        spec.connection = new LakeSourceSpec.Connection();
        spec.connection.host = "shop.db";
        spec.connection.database = "shop";
        spec.connection.credentialsSecret = new LakeSourceSpec.SecretRef();
        spec.connection.credentialsSecret.name = "shop-db";
        spec.connection.caConfigMap = "rds-ca";
        spec.target = new LakeSourceSpec.Target();
        spec.target.database = "ods_shop";
        spec.transport = new LakeSourceSpec.Transport();
        spec.transport.kafka = new LakeSourceSpec.KafkaTransport();
        spec.transport.kafka.bootstrapServers = "kafka:9093";
        spec.transport.kafka.topicPrefix = "cdc";
        spec.transport.kafka.credentialsSecret = new LakeSourceSpec.SecretRef();
        spec.transport.kafka.credentialsSecret.name = "shop-kafka";
        spec.transport.kafka.caConfigMap = "kafka-ca";
        return spec;
    }

    private static LakeSourceStatus.SourceTable table(String name) {
        LakeSourceStatus.SourceTable t = new LakeSourceStatus.SourceTable();
        t.name = name;
        t.target = name;
        return t;
    }

    @Test
    void theTransportIsForMySqlWithABoundedOverlap() {
        assertThat(LakeSourceReconciler.validate(spec())).isNull();
        LakeSourceSpec postgres = spec();
        postgres.type = "postgres";
        assertThat(LakeSourceReconciler.validate(postgres)).contains("for mysql sources");
        LakeSourceSpec shortOverlap = spec();
        shortOverlap.transport.kafka.overlap = "30s";
        assertThat(LakeSourceReconciler.validate(shortOverlap)).contains("1m to 24h");
        LakeSourceSpec longOverlap = spec();
        longOverlap.transport.kafka.overlap = "25h";
        assertThat(LakeSourceReconciler.validate(longOverlap)).contains("1m to 24h");
    }

    @Test
    void oneKafkaJobReadsEveryTablesTopicIntoTheSameTargets() throws Exception {
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
        var desired =
                new SourceJobs(
                                new OperatorSettings(
                                        "https://c",
                                        Path.of("/t"),
                                        Path.of("/ca"),
                                        "s3://b/flink-state",
                                        "colvenna-flink",
                                        Map.of(
                                                "mysql",
                                                "repo/img@sha256:m",
                                                "kafka",
                                                "repo/img@sha256:k"),
                                        Map.of(),
                                        List.of(),
                                        6000,
                                        6999,
                                        1000,
                                        9100))
                        .desired(
                                source,
                                status,
                                List.of(table("orders"), table("customers")),
                                new SourceJobs.Context(false, "v1", "v2", Map.of()));
        assertThat(desired).containsOnlyKeys("shop-kafka");
        assertThat(desired.get("shop-kafka").tables()).containsExactly("customers", "orders");
        FlinkDeployment fd = desired.get("shop-kafka").deployment();
        assertThat(fd.getSpec().getImage()).isEqualTo("repo/img@sha256:k");
        assertThat(fd.getSpec().getJob().getEntryClass())
                .isEqualTo("com.colvenna.lake.jobs.cdc.KafkaCdcJob");
        JsonNode arg = new ObjectMapper().readTree(fd.getSpec().getJob().getArgs()[0]);
        JsonNode kafka = arg.path("kafka");
        assertThat(kafka.path("targets").toString())
                .isEqualTo("{\"cdc.shop.customers\":\"customers\",\"cdc.shop.orders\":\"orders\"}");
        assertThat(kafka.path("overlapMillis").asLong()).isEqualTo(600_000L);
        assertThat(kafka.path("group").asText()).isEqualTo("colvenna.colvenna-data.shop.abcdef12");
        assertThat(kafka.path("caFile").asText()).isEqualTo("/source-ca/ca.pem");
        // The job reads only Kafka: the database's CA and connection settings stay unused.
        assertThat(arg.has("sourceCa")).isFalse();
        JsonNode options = arg.path("tableOptions");
        assertThat(options.path("changelog-producer").asText()).isEqualTo("lookup");
        assertThat(options.path("changelog-producer.row-deduplicate").asText()).isEqualTo("true");
        assertThat(options.path("write-only").asText()).isEqualTo("false");
        assertThat(options.path("sequence.field").asText()).isEqualTo("version_no");
        assertThat(fd.getSpec().getJob().getArgs()[0]).doesNotContain("password");
        assertThat(io.fabric8.kubernetes.client.utils.Serialization.asJson(fd.getSpec()))
                .contains("shop-kafka")
                .contains("kafka-ca");
    }

    @Test
    void aDirectSourceKeepsTheInputChangelog() {
        LakeSourceSpec direct = spec();
        direct.transport = null;
        assertThat(SourceJobs.changelogOptions(direct))
                .isEqualTo(Map.of("changelog-producer", "input", "write-only", "true"));
    }
}
