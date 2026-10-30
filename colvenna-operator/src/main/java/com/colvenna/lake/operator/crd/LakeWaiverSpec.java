package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import io.fabric8.generator.annotation.ValidationRule;
import java.util.ArrayList;
import java.util.List;

/** Which failed rules of which check are accepted, and why. It is made once and not changed. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@ValidationRule(
        value = "self == oldSelf",
        message = "a waiver stands as created; withdraw it by deleting it")
public class LakeWaiverSpec {
    @Required
    @Pattern("^[a-z0-9]([-a-z0-9]{0,251}[a-z0-9])?$")
    @JsonPropertyDescription("The LakeTask of type check whose failed rules are accepted.")
    public String check;

    @Required
    @Size(min = 1, max = 20)
    @JsonPropertyDescription("The rules accepted: exactly ones the check failed.")
    public List<@Pattern("^[a-z][a-z0-9-]{0,39}$") String> rules = new ArrayList<>();

    @Required
    @Size(min = 1, max = 1000)
    @JsonPropertyDescription("Why; it is kept in the gate's decisions. No personal data.")
    public String reason;
}
