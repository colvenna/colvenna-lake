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
 * A dashboard's queries served by the platform's Doris (HLD 10.1): each over the lake tables it
 * declares, served from the external tables, a materialized view, or an internal table, to the
 * Doris users it declares; see {@code docs/design/colvenna-operator-lld.md} "看板服务".
 */
@Group(LakeSource.GROUP)
@Version(LakeSource.VERSION)
@Kind("LakeDashboard")
@Plural("lakedashboards")
@ShortNames("ldb")
@AdditionalPrinterColumn(
        name = "Database",
        jsonPath = ".status.database",
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
public class LakeDashboard extends CustomResource<LakeDashboardSpec, LakeDashboardStatus>
        implements Namespaced {
    private static final long serialVersionUID = 1L;
}
