package com.colvenna.lake.operator.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.crd.Governance;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceSpec;
import com.colvenna.lake.operator.crd.LakeSourceStatus;
import com.colvenna.lake.operator.crd.LakeTask;
import com.colvenna.lake.operator.crd.LakeTaskStatus;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A write-only source's personal targets are expired every hour; others are left alone. */
@EnableKubernetesMockClient(crud = true, https = false)
class RetentionTest {
    KubernetesClient kube;

    private LakeSource source(String type, boolean personal) {
        LakeSource s = new LakeSource();
        s.setMetadata(
                new ObjectMetaBuilder()
                        .withName("shop")
                        .withNamespace("ns")
                        .withUid(UUID.randomUUID().toString())
                        .build());
        LakeSourceSpec spec = new LakeSourceSpec();
        spec.type = type;
        spec.target = new LakeSourceSpec.Target();
        spec.target.database = "ods";
        spec.governance = new Governance();
        spec.governance.owner = "shop-team";
        spec.governance.level = "L3";
        spec.governance.retention = "24h";
        spec.governance.categories = personal ? List.of("personal") : List.of();
        s.setSpec(spec);
        LakeSourceStatus status = new LakeSourceStatus();
        for (String name : List.of("orders", "customers")) {
            LakeSourceStatus.SourceTable t = new LakeSourceStatus.SourceTable();
            t.name = name;
            t.target = name;
            t.state = name.equals("orders") ? "Ready" : "Snapshotting";
            status.tables.add(t);
        }
        s.setStatus(status);
        return s;
    }

    private List<LakeTask> tasks() {
        return kube.resources(LakeTask.class).inNamespace("ns").list().getItems();
    }

    @Test
    void aWriteOnlySourcesPersonalTablesAreExpiredEveryHour() {
        Retention retention = new Retention(kube);
        Instant now = Instant.parse("2026-10-15T10:30:00Z");
        LakeSource shop = source("mysql", true);
        retention.keep(shop, now);
        retention.keep(shop, now.plusSeconds(60));
        assertThat(tasks())
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.getSpec().type).isEqualTo("expireSnapshots");
                            assertThat(t.getSpec().table.qualifiedName()).isEqualTo("ods.orders");
                            assertThat(t.getSpec().expireSnapshots.olderThan).isEqualTo("24h");
                            assertThat(t.getMetadata().getOwnerReferences().get(0).getUid())
                                    .isEqualTo(shop.getMetadata().getUid());
                        });
        // The next hour: a new expiry; the finished one of the hour before goes.
        LakeTask first = tasks().get(0);
        first.setStatus(new LakeTaskStatus());
        first.getStatus().phase = "Succeeded";
        kube.resource(first).update();
        retention.keep(shop, now.plus(Duration.ofHours(1)));
        assertThat(tasks())
                .singleElement()
                .satisfies(
                        t ->
                                assertThat(t.getMetadata().getName())
                                        .isNotEqualTo(first.getMetadata().getName()));
    }

    @Test
    void writersThatCompactAndDataNotPersonalAreLeftToTheWriter() {
        Retention retention = new Retention(kube);
        Instant now = Instant.parse("2026-10-15T10:30:00Z");
        retention.keep(source("mysql", false), now);
        retention.keep(source("mongodb", true), now);
        assertThat(tasks()).isEmpty();
    }

    @Test
    void unclassifiedDataIsExpiredAsPersonalDataIs() {
        LakeSource shop = source("mysql", false);
        shop.getSpec().governance = null;
        new Retention(kube).keep(shop, Instant.parse("2026-10-15T10:30:00Z"));
        assertThat(tasks())
                .singleElement()
                .satisfies(t -> assertThat(t.getSpec().expireSnapshots.olderThan).isEqualTo("24h"));
    }
}
