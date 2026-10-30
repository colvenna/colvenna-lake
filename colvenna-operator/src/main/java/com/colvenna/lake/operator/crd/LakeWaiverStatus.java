package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** What colvenna-operator made of a waiver. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LakeWaiverStatus {
    @JsonPropertyDescription(
            "Pending (its check has no results yet), Accepted, Rejected (made before its check"
                    + " had results, not approved, or naming a rule the check did not fail) or"
                    + " Superseded (the rules changed since its check, or a newer check replaced"
                    + " its check).")
    public String phase;

    public String message;

    @JsonPropertyDescription("database.table of its check.")
    public String table;

    @JsonPropertyDescription("The uid of the check it was accepted for.")
    public String checkUid;

    @JsonPropertyDescription("The Kubernetes user who created it, from its annotation.")
    public String approvedBy;

    public String acceptedAt;
}
