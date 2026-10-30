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
 * One lake table published, read-only, to the open layer's Iceberg REST catalog (HLD 10.3); see
 * {@code docs/design/colvenna-operator-lld.md}, "开放层".
 */
@Group(LakeSource.GROUP)
@Version(LakeSource.VERSION)
@Kind("LakeIcebergPublication")
@Plural("lakeicebergpublications")
@ShortNames("lip")
@AdditionalPrinterColumn(
        name = "Table",
        jsonPath = ".status.table",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Phase",
        jsonPath = ".status.phase",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Snapshot",
        jsonPath = ".status.published.snapshot",
        type = AdditionalPrinterColumn.Type.INTEGER)
@AdditionalPrinterColumn(
        name = "Message",
        jsonPath = ".status.message",
        type = AdditionalPrinterColumn.Type.STRING,
        priority = 1)
@AdditionalPrinterColumn(
        name = "Age",
        jsonPath = ".metadata.creationTimestamp",
        type = AdditionalPrinterColumn.Type.DATE)
public class LakeIcebergPublication
        extends CustomResource<LakeIcebergPublicationSpec, LakeIcebergPublicationStatus>
        implements Namespaced {
    private static final long serialVersionUID = 1L;
}
