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
 * Acceptance of rules one quality check failed, so its table's gate opens all the same; who
 * approved it is the Kubernetes user who created it (an admission policy checks the annotation).
 */
@Group(LakeSource.GROUP)
@Version(LakeSource.VERSION)
@Kind("LakeWaiver")
@Plural("lakewaivers")
@ShortNames("lw")
@AdditionalPrinterColumn(
        name = "Check",
        jsonPath = ".spec.check",
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
public class LakeWaiver extends CustomResource<LakeWaiverSpec, LakeWaiverStatus>
        implements Namespaced {
    private static final long serialVersionUID = 1L;

    /** The annotation naming the Kubernetes user who created the waiver. */
    public static final String APPROVED_BY = "colvenna-lake.io/approved-by";
}
