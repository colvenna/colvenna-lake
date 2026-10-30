package com.colvenna.lake.operator.flink;

import com.colvenna.lake.operator.OperatorSettings;
import com.colvenna.lake.operator.catalog.CatalogIdentities;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A Java entry of the job image run once as a Kubernetes Job: a LakeTable's query validation, a
 * LakeTask's run. It talks to colvenna-catalog only, as its owner's identity: no Kubernetes API
 * credentials, a read-only root file system, no pod retries. Its verdict is the container's
 * termination message, which only the entry writes.
 */
public final class BatchJobs {
    private final OperatorSettings settings;
    private final FlinkJobs flink;

    public BatchJobs(OperatorSettings settings) {
        this.settings = settings;
        this.flink = new FlinkJobs(settings);
    }

    /** The pod's CPU and memory requests, and the JVM heap within the memory. */
    public record Size(String cpu, String memory, String heap) {}

    /**
     * What runs, for whom, how big, and for how long: the run's deadline, and how long the finished
     * Job stays for its logs.
     */
    public record Run(
            HasMetadata owner,
            String name,
            Map<String, String> labels,
            String container,
            String image,
            String entry,
            String argument,
            Size size,
            Duration deadline,
            Duration kept) {}

    public Job build(Run run) {
        HasMetadata owner = run.owner();
        var restricted = FlinkJobs.restricted();
        restricted.setReadOnlyRootFilesystem(true);
        return new JobBuilder()
                .withNewMetadata()
                .withName(run.name())
                .withNamespace(owner.getMetadata().getNamespace())
                .withLabels(run.labels())
                .withOwnerReferences(
                        new OwnerReferenceBuilder()
                                .withApiVersion(owner.getApiVersion())
                                .withKind(owner.getKind())
                                .withName(owner.getMetadata().getName())
                                .withUid(owner.getMetadata().getUid())
                                .withController(true)
                                .withBlockOwnerDeletion(true)
                                .build())
                .endMetadata()
                .withNewSpec()
                // A retry is the owner's to make, as a new Job: a pod retry would hide the failure.
                .withBackoffLimit(0)
                .withActiveDeadlineSeconds(run.deadline().toSeconds())
                .withTtlSecondsAfterFinished((int) run.kept().toSeconds())
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(run.labels())
                .endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .withAutomountServiceAccountToken(false)
                .withNodeSelector(settings.jobNodeSelector())
                .withTolerations(settings.jobTolerations())
                .withSecurityContext(FlinkJobs.podSecurity())
                .addToInitContainers(FlinkJobs.truststore(run.image()))
                .addNewContainer()
                .withName(run.container())
                .withImage(run.image())
                .withCommand(
                        List.of(
                                "java",
                                "-Xmx" + run.size().heap(),
                                "-Djava.io.tmpdir=/tmp",
                                "-Djavax.net.ssl.trustStore=/trust/cacerts",
                                "-Djavax.net.ssl.trustStorePassword=changeit",
                                "-cp",
                                "/opt/flink/lib/*:/opt/flink/usrlib/colvenna-jobs.jar",
                                run.entry(),
                                run.argument()))
                .withEnv(flink.catalogEnv())
                .withSecurityContext(restricted)
                .withTerminationMessagePolicy("File")
                .withNewResources()
                .withRequests(
                        Map.of(
                                "cpu",
                                new Quantity(run.size().cpu()),
                                "memory",
                                new Quantity(run.size().memory())))
                .withLimits(Map.of("memory", new Quantity(run.size().memory())))
                .endResources()
                .withVolumeMounts(FlinkJobs.catalogMounts())
                .addToVolumeMounts(
                        new VolumeMountBuilder().withName("tmp").withMountPath("/tmp").build())
                .endContainer()
                .withVolumes(FlinkJobs.catalogVolumes(CatalogIdentities.tokenSecretName(owner)))
                .addToVolumes(
                        new VolumeBuilder().withName("tmp").withNewEmptyDir().endEmptyDir().build())
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    /** How the container ended, or null while it has not. */
    public static ContainerStateTerminated terminated(Pod pod, String container) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return null;
        }
        for (ContainerStatus c : pod.getStatus().getContainerStatuses()) {
            if (c.getName().equals(container)
                    && c.getState() != null
                    && c.getState().getTerminated() != null) {
                return c.getState().getTerminated();
            }
        }
        return null;
    }
}
