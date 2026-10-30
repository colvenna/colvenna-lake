package com.colvenna.lake.operator.flink;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.OperatorSettings;
import com.colvenna.lake.operator.crd.LakeSource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.apache.flink.kubernetes.operator.api.spec.JobState;
import org.apache.flink.kubernetes.operator.api.spec.UpgradeMode;
import org.junit.jupiter.api.Test;

class FlinkJobsTest {
    static final OperatorSettings SETTINGS =
            new OperatorSettings(
                    "https://colvenna-catalog.colvenna-catalog.svc:8181",
                    Path.of("/token/token"),
                    Path.of("/ca/ca.crt"),
                    "s3://bucket/flink-state",
                    "colvenna-flink",
                    Map.of("mysql", "repo/colvenna-jobs@sha256:1"),
                    Map.of("colvenna-lake/role", "load"),
                    List.of(),
                    6000,
                    6999,
                    1000,
                    9100);

    static LakeSource owner() {
        LakeSource source = new LakeSource();
        source.setMetadata(
                new ObjectMetaBuilder()
                        .withName("shop")
                        .withNamespace("colvenna-data")
                        .withUid("2b111fc6-1234-5678-9abc-def012345678")
                        .build());
        return source;
    }

    static FlinkJobs.Job job(boolean suspended, String arg) {
        return job(suspended, arg, null);
    }

    static FlinkJobs.Job job(boolean suspended, String arg, String ca) {
        return new FlinkJobs.Job(
                "shop-cdc",
                "repo/colvenna-jobs@sha256:1",
                "com.colvenna.lake.jobs.cdc.MySqlCdcJob",
                List.of(arg),
                2,
                "30s",
                suspended,
                FlinkJobs.resource(null, null, 1.0, "2048m"),
                FlinkJobs.resource(2.0, "4096m", 1.0, "3072m"),
                List.of(FlinkJobs.secretEnv("COLVENNA_SOURCE_PASSWORD", "creds", "password")),
                "shop-catalog-token",
                Map.of("colvenna-lake.io/token-id", "t1"),
                Map.of("colvenna-lake.io/source", "shop"),
                FlinkJobs.quietLogging(),
                ca,
                FlinkJobs.StatePolicy.LAST_STATE);
    }

    @Test
    void aJobIsRestrictedHighlyAvailableAndOwned() {
        FlinkDeployment fd =
                new FlinkJobs(SETTINGS).build(owner(), job(false, "{\"tables\":[\"a\"]}"));
        var spec = fd.getSpec();
        assertThat(fd.getMetadata().getOwnerReferences().get(0).getController()).isTrue();
        assertThat(spec.getJob().getUpgradeMode()).isEqualTo(UpgradeMode.LAST_STATE);
        assertThat(spec.getJob().getState()).isEqualTo(JobState.RUNNING);
        String conf = Serialization.asJson(spec.getFlinkConfiguration());
        assertThat(conf)
                .contains("\"high-availability.type\":\"kubernetes\"")
                .contains("s3://bucket/flink-state/colvenna-data/shop-cdc-2b111fc6/checkpoints")
                .contains("\"restart-strategy.type\":\"exponential-delay\"")
                .contains("\"web.submit.enable\":\"false\"")
                .contains("\"kubernetes.rest-service.exposed.type\":\"ClusterIP\"");
        var pod = spec.getPodTemplate().getSpec();
        assertThat(pod.getSecurityContext().getRunAsNonRoot()).isTrue();
        assertThat(pod.getContainers().get(0).getSecurityContext().getAllowPrivilegeEscalation())
                .isFalse();
        assertThat(pod.getVolumes())
                .anyMatch(
                        v ->
                                v.getSecret() != null
                                        && v.getSecret()
                                                .getSecretName()
                                                .equals("shop-catalog-token"));
        // The password reaches the job from the Secret, never in its arguments or configuration.
        assertThat(pod.getContainers().get(0).getEnv())
                .anyMatch(
                        e ->
                                e.getName().equals("COLVENNA_SOURCE_PASSWORD")
                                        && e.getValueFrom() != null);
        assertThat(spec.getLogConfiguration().get("log4j-console.properties"))
                .contains("logger.debezium.name = io.debezium\nlogger.debezium.level = WARN");
    }

    @Test
    void theSpecHashTracksEveryChange() {
        FlinkJobs jobs = new FlinkJobs(SETTINGS);
        FlinkDeployment a = jobs.build(owner(), job(false, "{\"tables\":[\"a\"]}"));
        FlinkDeployment same = jobs.build(owner(), job(false, "{\"tables\":[\"a\"]}"));
        FlinkDeployment more = jobs.build(owner(), job(false, "{\"tables\":[\"a\",\"b\"]}"));
        FlinkDeployment suspended = jobs.build(owner(), job(true, "{\"tables\":[\"a\"]}"));
        assertThat(FlinkJobs.current(a, same)).isTrue();
        assertThat(FlinkJobs.current(a, more)).isFalse();
        assertThat(FlinkJobs.current(a, suspended)).isFalse();
        assertThat(suspended.getSpec().getJob().getState()).isEqualTo(JobState.SUSPENDED);
    }

    @Test
    void longNamesAreShortenedStably() {
        String name = FlinkJobs.name("a".repeat(40), "b".repeat(20));
        assertThat(name)
                .hasSizeLessThanOrEqualTo(45)
                .isEqualTo(FlinkJobs.name("a".repeat(40), "b".repeat(20)));
        assertThat(FlinkJobs.name("Shop", "order_items")).isEqualTo("shop-order-items");
    }

    @Test
    void rootCausesAreTheDeepestCause() {
        assertThat(
                        FlinkRest.rootCause(
                                "a.B: top\n\tat x\nCaused by: c.D: middle\nCaused by: e.F: bottom\n"))
                .contains("e.F: bottom");
        assertThat(FlinkRest.rootCause("a.B: only\n\tat x")).contains("a.B: only");
    }

    @Test
    void aSourceCaIsMountedAndTrusted() {
        FlinkDeployment fd =
                new FlinkJobs(SETTINGS)
                        .build(owner(), job(false, "{\"tables\":[\"a\"]}", "rds-ca"));
        var pod = fd.getSpec().getPodTemplate().getSpec();
        assertThat(pod.getVolumes())
                .anyMatch(
                        v ->
                                v.getConfigMap() != null
                                        && v.getConfigMap().getName().equals("rds-ca"));
        assertThat(pod.getContainers().get(0).getVolumeMounts())
                .anyMatch(m -> m.getMountPath().equals("/source-ca"));
        // Every certificate of the bundle reaches the JVM trust store, not only the first.
        assertThat(pod.getInitContainers().get(0).getArgs().get(0))
                .contains("BEGIN CERTIFICATE")
                .contains("for c in /trust/source-*.pem");
        FlinkDeployment none =
                new FlinkJobs(SETTINGS).build(owner(), job(false, "{\"tables\":[\"a\"]}"));
        assertThat(none.getSpec().getPodTemplate().getSpec().getVolumes())
                .noneMatch(v -> v.getName().equals("source-ca"));
    }
}
