package com.colvenna.lake.operator.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.Durations;
import com.colvenna.lake.operator.crd.CatalogIdentity;
import com.colvenna.lake.operator.crd.Governance;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceSpec;
import com.colvenna.lake.operator.crd.LakeSourceStatus;
import com.colvenna.lake.operator.governance.GovernancePolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataTypes;
import org.junit.jupiter.api.Test;

class KafkaSourceTest {
    static LakeSourceSpec.Field field(String name, String type, boolean required) {
        LakeSourceSpec.Field f = new LakeSourceSpec.Field();
        f.name = name;
        f.type = type;
        f.required = required;
        return f;
    }

    static LakeSourceSpec spec() {
        LakeSourceSpec spec = new LakeSourceSpec();
        spec.type = "kafka";
        spec.kafka = new LakeSourceSpec.Kafka();
        spec.kafka.bootstrapServers = "kafka:9093";
        spec.kafka.topics = List.of("events");
        spec.kafka.credentialsSecret = new LakeSourceSpec.SecretRef();
        spec.kafka.credentialsSecret.name = "events-kafka";
        spec.kafka.caConfigMap = "kafka-ca";
        spec.events = new LakeSourceSpec.Events();
        spec.events.fields =
                new ArrayList<>(
                        List.of(
                                field("event_id", "STRING", true),
                                field("amount", "DECIMAL(12,2)", false)));
        spec.events.eventTime = new LakeSourceSpec.EventTime();
        spec.events.eventTime.field = "event_time";
        spec.target = new LakeSourceSpec.Target();
        spec.target.database = "ods_events";
        spec.target.table = "events";
        return spec;
    }

    @Test
    void aKafkaDeclarationNamesItsFieldsOnce() {
        LakeSourceSpec spec = spec();
        assertThat(LakeSourceReconciler.validate(spec)).isNull();
        spec.events.fields.add(field("event_id", "STRING", false));
        assertThat(LakeSourceReconciler.validate(spec)).contains("declared twice");
        spec = spec();
        spec.events.fields.add(field("_offset", "BIGINT", false));
        assertThat(LakeSourceReconciler.validate(spec)).contains("the platform adds");
        spec = spec();
        spec.events.fields.add(field("event_time", "STRING", false));
        assertThat(LakeSourceReconciler.validate(spec)).contains("event time");
        spec = spec();
        spec.target.tableOptions = Map.of("file-index.bloom-filter.columns", "event_id");
        assertThat(LakeSourceReconciler.validate(spec)).isNull();
        // The tables are the job's consumption record: nothing may expire them.
        spec.target.tableOptions = Map.of("partition.expiration-time", "31 d");
        assertThat(LakeSourceReconciler.validate(spec)).contains("is not allowed");
        spec.target.tableOptions = Map.of("bucket", "4");
        assertThat(LakeSourceReconciler.validate(spec)).contains("bucket is not allowed");
        spec = spec();
        spec.job = new LakeSourceSpec.Job();
        spec.job.checkpointInterval = "5s";
        assertThat(LakeSourceReconciler.validate(spec)).contains("at least 10s");
    }

    @Test
    void theEventsTableHasTheJobsColumns() {
        LakeSourceSpec spec = spec();
        assertThat(KafkaTables.eventFields(spec))
                .extracting(DataField::name)
                .containsExactly(
                        "event_id",
                        "amount",
                        "event_time_ms",
                        "dt",
                        "extra_json",
                        "_topic",
                        "_partition",
                        "_offset");
        spec.target.partitioning = "hourly";
        spec.events.unknownFields = "reject";
        assertThat(KafkaTables.eventFields(spec))
                .extracting(DataField::name)
                .containsSequence("dt", "hr", "_topic");
        assertThat(KafkaTables.partitionKeys(spec)).containsExactly("dt", "hr");
        assertThat(KafkaTables.options(spec, "o"))
                .containsEntry("bucket", "-1")
                .containsEntry("write-only", "true")
                .containsEntry("commit.force-create-snapshot", "true")
                .containsEntry("colvenna-lake.owner", "o");
    }

    @Test
    void optionalFieldsMayBeAddedButNothingElseChanges() {
        LakeSourceSpec spec = spec();
        List<DataField> before = KafkaTables.eventFields(spec);
        assertThat(KafkaTables.evolve(before, spec).refusal()).isNull();
        assertThat(KafkaTables.evolve(before, spec).added()).isEmpty();
        spec.events.fields.add(field("campaign", "STRING", false));
        KafkaTables.Plan plan = KafkaTables.evolve(before, spec);
        assertThat(plan.refusal()).isNull();
        assertThat(plan.added()).extracting(DataField::name).containsExactly("campaign");
        spec.events.fields.set(2, field("campaign", "STRING", true));
        assertThat(KafkaTables.evolve(before, spec).refusal()).contains("required");
        LakeSourceSpec retyped = spec();
        retyped.events.fields.set(1, field("amount", "DOUBLE", false));
        assertThat(KafkaTables.evolve(before, retyped).refusal()).contains("cannot become");
        LakeSourceSpec removed = spec();
        removed.events.fields.remove(1);
        assertThat(KafkaTables.evolve(before, removed).refusal()).isNotNull();
        assertThat(KafkaTables.type("DECIMAL(12, 2)")).isEqualTo(DataTypes.DECIMAL(12, 2));
    }

    @Test
    void theJobGetsItsSpecAndTheGroupButNeverThePassword() throws Exception {
        LakeSource source = new LakeSource();
        source.setMetadata(
                new ObjectMetaBuilder()
                        .withName("events")
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
                                new com.colvenna.lake.operator.OperatorSettings(
                                        "https://c",
                                        java.nio.file.Path.of("/t"),
                                        java.nio.file.Path.of("/ca"),
                                        "s3://b/flink-state",
                                        "colvenna-flink",
                                        Map.of("kafka", "repo/img@sha256:1"),
                                        Map.of(),
                                        List.of(),
                                        6000,
                                        6999,
                                        1000,
                                        9100))
                        .desired(
                                source,
                                status,
                                List.of(),
                                new SourceJobs.Context(false, "v1", "v2", Map.of()));
        assertThat(desired).containsOnlyKeys("events-events");
        FlinkDeployment fd = desired.get("events-events").deployment();
        JsonNode arg = new ObjectMapper().readTree(fd.getSpec().getJob().getArgs()[0]);
        assertThat(arg.path("group").asText()).isEqualTo("colvenna.colvenna-data.events.abcdef12");
        assertThat(arg.path("rejections").asText()).isEqualTo("events_rejections");
        assertThat(arg.path("caFile").asText()).isEqualTo("/source-ca/ca.pem");
        assertThat(arg.path("maxOutOfOrdernessMs").asLong()).isEqualTo(300_000);
        assertThat(arg.path("fields").get(1).path("type").asText()).isEqualTo("DECIMAL(12,2)");
        assertThat(fd.getSpec().getJob().getArgs()[0]).doesNotContain("password");
        assertThat(io.fabric8.kubernetes.client.utils.Serialization.asJson(fd.getSpec()))
                .contains("secretKeyRef")
                .contains("events-kafka");
        assertThat(Durations.millis("30d", "1d")).isEqualTo(30L * 86_400_000);
        // What restored offsets belong to, where the reconciler compares it: on the deployment.
        String identity = fd.getMetadata().getAnnotations().get(SourceJobs.KAFKA_IDENTITY);
        assertThat(identity).isNotNull();
        source.getSpec().kafka.topics = List.of("events", "clicks");
        assertThat(SourceJobs.kafkaIdentity(source.getSpec())).isNotEqualTo(identity);
    }

    @Test
    void anEventSourceIsPublishedOnlyGovernedAndWithoutPersonalData() {
        LakeSourceSpec spec = spec();
        assertThat(KafkaSources.verdict(spec).governed()).isFalse();
        spec.governance = new Governance();
        spec.governance.owner = "web";
        spec.governance.level = "L2";
        spec.governance.retention = "7d";
        assertThat(KafkaSources.verdict(spec).governed()).isTrue();
        spec.governance.level = "L3";
        spec.governance.categories = List.of("personal");
        spec.governance.retention = "24h";
        spec.governance.subjectKeys = Map.of(spec.target.table, List.of("event_id"));
        GovernancePolicy.Verdict personal = KafkaSources.verdict(spec);
        assertThat(personal.governed()).isFalse();
        assertThat(personal.reason()).isEqualTo("PersonalEvents");
    }

    @Test
    void probeFailuresSayWhatIsWrong() {
        assertThat(KafkaProbe.classify(new SaslAuthenticationException("bad")).reason())
                .isEqualTo("AuthenticationFailed");
        assertThat(KafkaProbe.classify(new TopicAuthorizationException("no")).reason())
                .isEqualTo("NotAuthorized");
        assertThat(KafkaProbe.classify(new UnknownTopicOrPartitionException("x")).reason())
                .isEqualTo("TopicMissing");
        assertThat(
                        KafkaProbe.classify(
                                        new org.apache.kafka.common.errors.TimeoutException("t"))
                                .reason())
                .isEqualTo("Unreachable");
        assertThat(new KafkaProbe.Topic("t", 2, 10, 4).lag()).isEqualTo(6);
    }
}
