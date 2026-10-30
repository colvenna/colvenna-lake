package com.colvenna.lake.operator.source;

import com.colvenna.lake.operator.crd.Governance;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceStatus;
import com.colvenna.lake.operator.crd.LakeTask;
import com.colvenna.lake.operator.crd.LakeTaskSpec;
import com.colvenna.lake.operator.flink.FlinkJobs;
import com.colvenna.lake.operator.governance.GovernancePolicy;
import com.colvenna.lake.operator.task.LakeTaskReconciler;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the declared retention of a source's personal data whose writer leaves maintenance to
 * others (write-only): Paimon expires nothing there (LLD "治理与个人数据删除"). Each Ready target is expired
 * every {@link #EVERY} (snapshots, then tags and idle readers past theirs) by a LakeTask the source
 * owns; finished ones of earlier rounds are deleted.
 */
final class Retention {
    private static final Logger LOG = LoggerFactory.getLogger(Retention.class);

    static final Duration EVERY = Duration.ofHours(1);

    /** The label of the expiries, the source's name. */
    static final String LABEL = "colvenna-lake.io/retention";

    private final KubernetesClient kube;

    Retention(KubernetesClient kube) {
        this.kube = kube;
    }

    void keep(LakeSource source, Instant now) {
        Governance g = source.getSpec().governance;
        if (!Governance.treatedAsPersonal(g)
                || SourceJobs.writerCompacts(source.getSpec())
                || source.getStatus() == null) {
            return;
        }
        long minutes = GovernancePolicy.retention(g).toMinutes();
        String retention = minutes % 60 == 0 ? minutes / 60 + "h" : minutes + "m";
        String ns = source.getMetadata().getNamespace();
        String name = source.getMetadata().getName();
        String round = Long.toString(now.toEpochMilli() / EVERY.toMillis());
        for (LakeSourceStatus.SourceTable t : source.getStatus().tables) {
            if (!Enrollment.READY.equals(t.state)) {
                continue;
            }
            String task = FlinkJobs.name(name, "retain", t.target, round);
            if (kube.resources(LakeTask.class).inNamespace(ns).withName(task).get() == null) {
                create(source, task, t.target, retention);
            }
        }
        for (LakeTask task :
                kube.resources(LakeTask.class)
                        .inNamespace(ns)
                        .withLabel(LABEL, name)
                        .list()
                        .getItems()) {
            if (!task.getMetadata().getName().endsWith("-" + round)
                    && task.getStatus() != null
                    && LakeTaskReconciler.finished(task.getStatus())) {
                try {
                    kube.resource(task).delete();
                } catch (KubernetesClientException e) {
                    LOG.warn(
                            "Deleting {} failed: {}", task.getMetadata().getName(), e.getMessage());
                }
            }
        }
    }

    private void create(LakeSource source, String name, String table, String retention) {
        LakeTask task = new LakeTask();
        task.getMetadata().setName(name);
        task.getMetadata().setNamespace(source.getMetadata().getNamespace());
        task.getMetadata().setLabels(Map.of(LABEL, source.getMetadata().getName()));
        task.getMetadata()
                .setOwnerReferences(
                        List.of(
                                new OwnerReferenceBuilder()
                                        .withApiVersion(source.getApiVersion())
                                        .withKind(source.getKind())
                                        .withName(source.getMetadata().getName())
                                        .withUid(source.getMetadata().getUid())
                                        .withController(true)
                                        .build()));
        LakeTaskSpec spec = new LakeTaskSpec();
        spec.type = "expireSnapshots";
        spec.table = new LakeTaskSpec.Table();
        spec.table.database = source.getSpec().target.database;
        spec.table.table = table;
        spec.expireSnapshots = new LakeTaskSpec.ExpireSnapshots();
        spec.expireSnapshots.retainMin = 1;
        spec.expireSnapshots.olderThan = retention;
        task.setSpec(spec);
        try {
            kube.resource(task).create();
        } catch (KubernetesClientException e) {
            LOG.warn("Creating {} failed: {}", name, e.getMessage());
        }
    }
}
