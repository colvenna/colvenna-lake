package com.colvenna.lake.operator.kube;

import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Kubernetes Events on a resource, the alert channel kubectl describe and monitoring read. */
public final class Events {
    private static final Logger LOG = LoggerFactory.getLogger(Events.class);
    private final KubernetesClient kube;

    public Events(KubernetesClient kube) {
        this.kube = kube;
    }

    public void normal(HasMetadata on, String reason, String message) {
        emit(on, "Normal", reason, message);
    }

    public void warning(HasMetadata on, String reason, String message) {
        emit(on, "Warning", reason, message);
    }

    private void emit(HasMetadata on, String type, String reason, String message) {
        String now = Instant.now().toString();
        var event =
                new EventBuilder()
                        .withNewMetadata()
                        .withName(
                                on.getMetadata().getName()
                                        + "."
                                        + UUID.randomUUID().toString().substring(0, 13))
                        .withNamespace(on.getMetadata().getNamespace())
                        .endMetadata()
                        .withInvolvedObject(
                                new ObjectReferenceBuilder()
                                        .withApiVersion(on.getApiVersion())
                                        .withKind(on.getKind())
                                        .withName(on.getMetadata().getName())
                                        .withNamespace(on.getMetadata().getNamespace())
                                        .withUid(on.getMetadata().getUid())
                                        .build())
                        .withType(type)
                        .withReason(reason)
                        .withMessage(message.length() > 1000 ? message.substring(0, 1000) : message)
                        .withFirstTimestamp(now)
                        .withLastTimestamp(now)
                        .withCount(1)
                        .withNewSource()
                        .withComponent("colvenna-operator")
                        .endSource()
                        .build();
        try {
            kube.v1()
                    .events()
                    .inNamespace(on.getMetadata().getNamespace())
                    .resource(event)
                    .create();
        } catch (KubernetesClientException e) {
            LOG.warn(
                    "Could not record event {} on {}: {}",
                    reason,
                    on.getMetadata().getName(),
                    e.getMessage());
        }
        if (type.equals("Warning")) {
            LOG.warn(
                    "{} {}/{}: {} {}",
                    on.getKind(),
                    on.getMetadata().getNamespace(),
                    on.getMetadata().getName(),
                    reason,
                    message);
        } else {
            LOG.info(
                    "{} {}/{}: {} {}",
                    on.getKind(),
                    on.getMetadata().getNamespace(),
                    on.getMetadata().getName(),
                    reason,
                    message);
        }
    }
}
