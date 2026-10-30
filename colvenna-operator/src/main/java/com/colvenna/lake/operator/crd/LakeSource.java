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
 * A declared data source whose ingestion job and ODS tables colvenna-operator keeps converged. See
 * {@code docs/design/colvenna-operator-lld.md}.
 */
@Group(LakeSource.GROUP)
@Version(LakeSource.VERSION)
@Kind("LakeSource")
@Plural("lakesources")
@ShortNames("ls")
@AdditionalPrinterColumn(
        name = "Type",
        jsonPath = ".spec.type",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Target",
        jsonPath = ".spec.target.database",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Phase",
        jsonPath = ".status.phase",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Ready",
        jsonPath = ".status.summary",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Age",
        jsonPath = ".metadata.creationTimestamp",
        type = AdditionalPrinterColumn.Type.DATE)
public class LakeSource extends CustomResource<LakeSourceSpec, LakeSourceStatus>
        implements Namespaced {
    public static final String GROUP = "colvenna-lake.io";
    public static final String VERSION = "v1alpha1";
    private static final long serialVersionUID = 1L;
}
