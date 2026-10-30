package com.colvenna.lake.operator.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceSpec;
import com.colvenna.lake.operator.crd.LakeSourceStatus;
import com.colvenna.lake.operator.crd.StatusCondition;
import com.colvenna.lake.operator.kube.Events;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SourceVerified says how each kind of source's certificate is checked, as the clients do. */
@EnableKubernetesMockClient(crud = true, https = false)
class SourceVerifiedTest {
    KubernetesClient kube;

    /** The source's SourceVerified condition, and the Warnings it raised, after two rounds. */
    private record Outcome(StatusCondition condition, List<Event> warnings) {}

    private Outcome verify(String type, boolean caDeclared) {
        LakeSource source = new LakeSource();
        source.setMetadata(
                new ObjectMetaBuilder()
                        .withName(type + (caDeclared ? "-ca" : "-default"))
                        .withNamespace("ns")
                        .withUid(type + "-uid")
                        .build());
        LakeSourceSpec spec = new LakeSourceSpec();
        spec.type = type;
        source.setSpec(spec);
        LakeSourceStatus status = new LakeSourceStatus();
        Events events = new Events(kube);
        Instant now = Instant.parse("2026-10-20T08:00:00Z");
        for (int round = 0; round < 2; round++) {
            LakeSourceReconciler.sourceVerified(
                    new LakeSourceReconciler.Round(
                            source, spec, status, 1L, now.plusSeconds(round)),
                    events,
                    caDeclared);
        }
        StatusCondition condition =
                status.conditions.stream()
                        .filter(c -> c.type.equals("SourceVerified"))
                        .findFirst()
                        .orElseThrow();
        List<Event> warnings =
                kube.v1().events().inNamespace("ns").list().getItems().stream()
                        .filter(
                                e ->
                                        e.getInvolvedObject()
                                                .getName()
                                                .equals(source.getMetadata().getName()))
                        .toList();
        return new Outcome(condition, warnings);
    }

    @Test
    void aDeclaredCaIsVerifiedForEveryKind() {
        for (String type : List.of("mysql", "postgres", "mongodb", "kafka")) {
            Outcome o = verify(type, true);
            assertThat(o.condition().status).as(type).isEqualTo("True");
            assertThat(o.condition().reason).as(type).isEqualTo("Verified");
            assertThat(o.condition().message)
                    .as(type)
                    .contains(
                            type.equals("kafka") ? "kafka.caConfigMap" : "connection.caConfigMap");
            assertThat(o.warnings()).as(type).isEmpty();
        }
        // Paimon's PostgreSQL action reads the schema over a connection it allows no SSL options.
        assertThat(verify("postgres", true).condition().message)
                .contains("except Paimon's schema reads");
    }

    @Test
    void withoutACaTheDatabasesEncryptButVerifyNothing() {
        // sslMode=REQUIRED and sslmode=require: no certificate check.
        for (String type : List.of("mysql", "postgres")) {
            Outcome o = verify(type, false);
            assertThat(o.condition().status).as(type).isEqualTo("False");
            assertThat(o.condition().reason).as(type).isEqualTo("NoCaConfigured");
            assertThat(o.condition().message).as(type).contains("not verified");
            // One Warning when the condition turns False, not one a round.
            assertThat(o.warnings())
                    .as(type)
                    .singleElement()
                    .satisfies(
                            e -> {
                                assertThat(e.getType()).isEqualTo("Warning");
                                assertThat(e.getReason()).isEqualTo("TlsNotVerified");
                                assertThat(e.getMessage()).contains("connection.caConfigMap");
                            });
        }
    }

    @Test
    void withoutACaMongoDbAndKafkaVerifyAgainstTheDefaultTrustStore() {
        // MongoProbe falls back to SSLContext.getDefault(), the jobs to the pod's JVM trust store;
        // the Kafka clients verify the host name unless told otherwise.
        for (String type : List.of("mongodb", "kafka")) {
            Outcome o = verify(type, false);
            assertThat(o.condition().status).as(type).isEqualTo("True");
            assertThat(o.condition().reason).as(type).isEqualTo("DefaultTrustStore");
            assertThat(o.condition().message)
                    .as(type)
                    .contains("default trust store")
                    .doesNotContain("not verified");
            assertThat(o.warnings()).as(type).isEmpty();
        }
    }

    /**
     * A Kafka source's probe round, the cluster answering every topic: what SourceVerified says
     * when {@code caConfigMap} is declared and the ConfigMap has {@code ca}.
     */
    private StatusCondition kafkaRound(String caConfigMap, String ca) {
        String name = "events-" + (caConfigMap == null ? "default" : ca == null ? "unread" : "ca");
        LakeSource source = new LakeSource();
        source.setMetadata(
                new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace("ns")
                        .withUid("abcdef12-0000-0000-0000-" + name.length())
                        .build());
        LakeSourceSpec spec = KafkaSourceTest.spec();
        spec.kafka.caConfigMap = caConfigMap;
        source.setSpec(spec);
        kube.secrets()
                .inNamespace("ns")
                .resource(
                        new SecretBuilder()
                                .withNewMetadata()
                                .withName("events-kafka")
                                .endMetadata()
                                .withStringData(Map.of("username", "ingest", "password", "secret"))
                                .build())
                .createOr(r -> r.update());
        if (caConfigMap != null) {
            kube.configMaps()
                    .inNamespace("ns")
                    .resource(
                            new ConfigMapBuilder()
                                    .withNewMetadata()
                                    .withName(caConfigMap)
                                    .endMetadata()
                                    .withData(ca == null ? Map.of() : Map.of("ca.pem", ca))
                                    .build())
                    .createOr(r -> r.update());
        }
        KafkaProbe answers =
                new KafkaProbe() {
                    @Override
                    public Result probe(
                            String bootstrap,
                            List<String> topics,
                            String mechanism,
                            Path caFile,
                            String group,
                            SourceProbe.Login login,
                            boolean changeTopics) {
                        return new Result(null, null, List.of());
                    }
                };
        LakeSourceStatus status = new LakeSourceStatus();
        new KafkaSources(null, kube, null, answers, new Events(kube), 0)
                .probe(
                        new LakeSourceReconciler.Round(
                                source, spec, status, 1L, Instant.parse("2026-10-20T08:00:00Z")));
        return status.conditions.stream()
                .filter(c -> c.type.equals("SourceVerified"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aKafkaSourceSaysWhatItsProbeCheckedAgainst() {
        StatusCondition declared = kafkaRound("kafka-ca", "-----BEGIN CERTIFICATE-----");
        assertThat(declared.status).isEqualTo("True");
        assertThat(declared.reason).isEqualTo("Verified");
        StatusCondition none = kafkaRound(null, null);
        assertThat(none.status).isEqualTo("True");
        assertThat(none.reason).isEqualTo("DefaultTrustStore");
        // Declared, but the ConfigMap has no ca.pem: nothing was checked against it.
        StatusCondition unread = kafkaRound("kafka-ca-empty", null);
        assertThat(unread.status).isEqualTo("False");
        assertThat(unread.reason).isEqualTo("CaMissing");
        assertThat(unread.message).contains("kafka-ca-empty has no ca.pem");
    }

    @Test
    void aDatabaseSourcesUnreadableCaIsTold() {
        LakeSource source = new LakeSource();
        source.setMetadata(
                new ObjectMetaBuilder().withName("shop").withNamespace("ns").withUid("u").build());
        LakeSourceSpec spec = LakeSourceReconcilerTest.spec();
        spec.connection.caConfigMap = "rds-ca";
        source.setSpec(spec);
        // Told apart from missing credentials: the probe then sets SourceReady and SourceVerified
        // False, CaMissing.
        assertThrows(SourceTargets.CaMissing.class, () -> SourceTargets.target(kube, source));
    }
}
