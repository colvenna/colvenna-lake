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
 * A declared DWD/DWS table: a Paimon table and the long-running Flink SQL job that keeps it filled
 * from its upstream tables (HLD 9.1). See {@code docs/design/colvenna-operator-lld.md}.
 */
@Group(LakeSource.GROUP)
@Version(LakeSource.VERSION)
@Kind("LakeTable")
@Plural("laketables")
@ShortNames("lt")
@AdditionalPrinterColumn(
        name = "Target",
        jsonPath = ".status.target",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Phase",
        jsonPath = ".status.phase",
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
public class LakeTable extends CustomResource<LakeTableSpec, LakeTableStatus>
        implements Namespaced {
    private static final long serialVersionUID = 1L;
}
