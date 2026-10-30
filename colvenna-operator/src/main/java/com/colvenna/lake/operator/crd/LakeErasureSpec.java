package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.ValidationRule;

/** Which data subject to erase, and by when. It is accepted once: a changed spec is refused. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@ValidationRule(
        value = "self == oldSelf",
        message = "an erasure is accepted as created; create another one to change it")
public class LakeErasureSpec {
    /** The Secret's key the values are under when none is given. */
    public static final String DEFAULT_KEY = "values";

    /**
     * The acceptance contract's deadlines (section 4), the defaults and the longest an erasure may
     * ask for.
     */
    public static final String DEFAULT_LOGICAL = "24h";

    public static final String DEFAULT_PHYSICAL = "9d";

    @Required
    @JsonPropertyDescription(
            "Secret in this namespace with the subject's key values, one per line (tab-separated"
                    + " for a key of several columns). The values are personal data: they appear"
                    + " nowhere else.")
    public SubjectSecret subjectSecret;

    public Deadlines deadlines;

    /** The Secret and its key. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SubjectSecret {
        @Required
        @Pattern("^[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?$")
        public String name;

        @Default(DEFAULT_KEY)
        @Pattern("^[-._a-zA-Z0-9]{1,253}$")
        public String key;

        /** The key, "values" when unset (an object built in code has no CRD default). */
        public String key() {
            return key == null ? DEFAULT_KEY : key;
        }
    }

    /** How long after acceptance each level of completion may take. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Deadlines {
        @Pattern("^[1-9][0-9]{0,3}(m|h|d)$")
        @Default(DEFAULT_LOGICAL)
        @JsonPropertyDescription("Every managed read path no longer returns the subject.")
        public String logical;

        @Pattern("^[1-9][0-9]{0,3}(m|h|d)$")
        @Default(DEFAULT_PHYSICAL)
        @JsonPropertyDescription("No managed storage or backup can bring the subject back.")
        public String physical;
    }
}
