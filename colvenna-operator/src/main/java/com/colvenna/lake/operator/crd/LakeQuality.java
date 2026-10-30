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
 * The batch quality rules of one managed table and the gate their checks keep on its publication
 * (HLD 12.6); see {@code docs/design/colvenna-operator-lld.md}, "批量质量校验".
 */
@Group(LakeSource.GROUP)
@Version(LakeSource.VERSION)
@Kind("LakeQuality")
@Plural("lakequalities")
@ShortNames("lq")
@AdditionalPrinterColumn(
        name = "Table",
        jsonPath = ".status.table",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Phase",
        jsonPath = ".status.phase",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Gate",
        jsonPath = ".status.gate.cause",
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
public class LakeQuality extends CustomResource<LakeQualitySpec, LakeQualityStatus>
        implements Namespaced {
    private static final long serialVersionUID = 1L;
}
