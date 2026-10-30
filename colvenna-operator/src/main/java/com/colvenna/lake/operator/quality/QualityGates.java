package com.colvenna.lake.operator.quality;

import com.colvenna.lake.operator.crd.LakeQuality;
import com.colvenna.lake.operator.crd.LakeQualityStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a namespace's quality gates hold back, for those who publish (LLD "门禁"): a table whose
 * LakeQuality publishes only while its gate is open is held while the gate is not judged open.
 */
public final class QualityGates {
    /** The order in which LakeQualities of one table come: the first created is its gate. */
    static final Comparator<LakeQuality> FIRST =
            Comparator.comparing((LakeQuality q) -> q.getMetadata().getCreationTimestamp())
                    .thenComparing(q -> q.getMetadata().getUid());

    private QualityGates() {}

    /** Why a table is held: its LakeQuality, and the gate's cause and message. */
    public record Hold(String quality, String cause, String message) {
        public String describe() {
            return "its quality gate (LakeQuality "
                    + quality
                    + ") is closed: "
                    + cause
                    + ", "
                    + message;
        }
    }

    /** The namespace's LakeQualities. */
    public static List<LakeQuality> of(KubernetesClient kube, String namespace) {
        return kube.resources(LakeQuality.class).inNamespace(namespace).list().getItems();
    }

    /** The held tables (database.table) of a namespace. */
    public static Map<String, Hold> holds(KubernetesClient kube, String namespace) {
        return holds(of(kube, namespace));
    }

    /** The held tables (database.table) of the namespace whose LakeQualities these are. */
    public static Map<String, Hold> holds(List<LakeQuality> qualities) {
        Map<String, Hold> out = new LinkedHashMap<>();
        for (LakeQuality q : qualities) {
            if (q.getSpec() == null || q.getSpec().table == null || !q.getSpec().publishes()) {
                continue;
            }
            LakeQualityStatus s = q.getStatus();
            // A second LakeQuality of a table (Failed: Conflict) is not its gate.
            if (s != null && LakeQualityReconciler.FAILED.equals(s.phase)) {
                continue;
            }
            String table = q.getSpec().table.qualifiedName();
            if (s == null || s.gate == null) {
                out.putIfAbsent(
                        table, new Hold(q.getMetadata().getName(), "Pending", "not judged yet"));
            } else if (!Boolean.TRUE.equals(s.gate.open)) {
                out.put(table, new Hold(q.getMetadata().getName(), s.gate.cause, s.gate.message));
            }
        }
        return out;
    }

    /**
     * "table: why" for each of {@code tables} that is held; the held ones are taken out of the
     * list.
     */
    public static List<String> withhold(Map<String, Hold> holds, List<String> tables) {
        List<String> held = new ArrayList<>();
        for (String table : List.copyOf(tables)) {
            Hold h = holds.get(table);
            if (h != null) {
                tables.remove(table);
                held.add(table + ": " + h.describe());
            }
        }
        return held;
    }

    /**
     * The table's gate: its first created LakeQuality that is not Failed (the others are
     * conflicts), judged or not; empty when the table has none.
     */
    public static Optional<LakeQuality> gateOf(List<LakeQuality> qualities, String table) {
        return qualities.stream()
                .filter(q -> q.getSpec() != null && q.getSpec().table != null)
                .filter(q -> table.equals(q.getSpec().table.qualifiedName()))
                .filter(
                        q ->
                                q.getStatus() == null
                                        || !LakeQualityReconciler.FAILED.equals(
                                                q.getStatus().phase))
                .min(FIRST);
    }
}
