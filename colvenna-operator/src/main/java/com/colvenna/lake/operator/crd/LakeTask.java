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
 * One batch or maintenance run on a managed table (HLD 9.2): a backfill of a layered table, a
 * compaction, a snapshot expiry, an orphan-file cleanup or an end-of-day tag. It runs once; see
 * {@code docs/design/colvenna-operator-lld.md}.
 */
@Group(LakeSource.GROUP)
@Version(LakeSource.VERSION)
@Kind("LakeTask")
@Plural("laketasks")
@ShortNames("ltk")
@AdditionalPrinterColumn(
        name = "Table",
        jsonPath = ".status.table",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Type",
        jsonPath = ".spec.type",
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
public class LakeTask extends CustomResource<LakeTaskSpec, LakeTaskStatus> implements Namespaced {
    private static final long serialVersionUID = 1L;
}
