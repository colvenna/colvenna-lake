package com.colvenna.lake.operator.table;

import com.colvenna.lake.operator.crd.LakeTable;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A LakeTask's hold on a LakeTable's job while a backfill rewrites the target (HLD 9.2): an
 * annotation naming the task. The table's reconciler suspends the job with a savepoint while it is
 * there, and records in {@code status.fencedBy} that it saw the fence and the job is suspended.
 * Reconciliations of one table run one at a time and read the table at least as new as the last, so
 * once that is recorded no later one resumes the job until the fence is lifted.
 */
public final class Fence {
    public static final String ANNOTATION = "colvenna-lake.io/fenced-by";

    private Fence() {}

    /** The task holding the table's job suspended, or null. */
    public static String holder(LakeTable table) {
        Map<String, String> annotations = table.getMetadata().getAnnotations();
        return annotations == null ? null : annotations.get(ANNOTATION);
    }

    /** Whether the table's reconciler has suspended the job for this task. */
    public static boolean holds(LakeTable table, String task) {
        return table.getStatus() != null && task.equals(table.getStatus().fencedBy);
    }

    public static void hold(KubernetesClient kube, LakeTable table, String task) {
        annotate(kube, table, task);
    }

    public static void lift(KubernetesClient kube, LakeTable table) {
        annotate(kube, table, null);
    }

    private static void annotate(KubernetesClient kube, LakeTable table, String task) {
        kube.resources(LakeTable.class)
                .inNamespace(table.getMetadata().getNamespace())
                .withName(table.getMetadata().getName())
                .edit(
                        t -> {
                            Map<String, String> a =
                                    t.getMetadata().getAnnotations() == null
                                            ? new LinkedHashMap<>()
                                            : new LinkedHashMap<>(t.getMetadata().getAnnotations());
                            if (task == null) {
                                a.remove(ANNOTATION);
                            } else {
                                a.put(ANNOTATION, task);
                            }
                            t.getMetadata().setAnnotations(a);
                            return t;
                        });
    }
}
