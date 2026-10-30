package com.colvenna.lake.operator.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.OperatorSettings;
import com.colvenna.lake.operator.crd.LakeTask;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true, https = false)
class TaskRunsTest {
    KubernetesClient kube;

    @Test
    void aPodThatHasNotEndedMayStillWriteOneWithoutAPhaseYetIncluded() {
        TaskRuns runs =
                new TaskRuns(
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
                                9100),
                        kube);
        LakeTask task = new LakeTask();
        task.setMetadata(new ObjectMetaBuilder().withName("compact-1").withNamespace("ns").build());
        assertThat(runs.active(task, 1)).isFalse();
        Pod pod =
                kube.resource(
                                new PodBuilder()
                                        .withNewMetadata()
                                        .withName("compact-1-run-1-x")
                                        .withNamespace("ns")
                                        .withLabels(Map.of("job-name", TaskRuns.name(task, 1)))
                                        .endMetadata()
                                        .build())
                        .create();
        // Just created: no status, no phase.
        assertThat(runs.active(task, 1)).isTrue();
        pod.setStatus(new io.fabric8.kubernetes.api.model.PodStatus());
        kube.resource(pod).updateStatus();
        assertThat(runs.active(task, 1)).isTrue();
        pod = kube.pods().inNamespace("ns").withName("compact-1-run-1-x").get();
        pod.getStatus().setPhase("Running");
        kube.resource(pod).updateStatus();
        assertThat(runs.active(task, 1)).isTrue();
        pod.getStatus().setPhase("Failed");
        kube.resource(pod).updateStatus();
        assertThat(runs.active(task, 1)).isFalse();
    }
}
