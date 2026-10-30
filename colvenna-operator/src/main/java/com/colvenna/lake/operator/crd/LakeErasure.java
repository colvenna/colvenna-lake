package com.colvenna.lake.operator.crd;

import io.fabric8.crd.generator.annotation.AdditionalPrinterColumn;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * The erasure of one data subject across every managed copy of the namespace's personal data (HLD
 * 12.4, {@code colvenna forget}): logical and physical completion, each with its evidence, by their
 * deadlines. See {@code docs/design/colvenna-operator-lld.md}, "治理与个人数据删除".
 */
@Group(LakeSource.GROUP)
@Version(LakeSource.VERSION)
@Kind("LakeErasure")
@Plural("lakeerasures")
@ShortNames("ler")
@AdditionalPrinterColumn(
        name = "Phase",
        jsonPath = ".status.phase",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Logical",
        jsonPath = ".status.logicallyErasedAt",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Physical",
        jsonPath = ".status.physicallyErasedAt",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Message",
        jsonPath = ".status.message",
        type = AdditionalPrinterColumn.Type.STRING,
        priority = 1)
@AdditionalPrinterColumn(
        name = "Age",
        jsonPath = ".metadata.creationTimestamp",
        type = AdditionalPrinterColumn.Type.DATE)
public class LakeErasure extends CustomResource<LakeErasureSpec, LakeErasureStatus>
        implements Namespaced {
    private static final long serialVersionUID = 1L;
}
