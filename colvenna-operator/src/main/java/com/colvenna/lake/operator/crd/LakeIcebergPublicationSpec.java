package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import io.fabric8.generator.annotation.ValidationRule;
import java.util.ArrayList;
import java.util.List;

/** Which table to publish to the Iceberg catalog, which of its columns, and who may read it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LakeIcebergPublicationSpec {
    @Required
    @ValidationRule(
            value = "self == oldSelf",
            message = "the table of a publication stays; create another publication for another")
    @JsonPropertyDescription(
            "A table of this namespace: a LakeSource's ODS table or a LakeTable's target. In"
                    + " Iceberg it is the same database.table.")
    public LakeTaskSpec.Table table;

    @Required
    @Size(min = 1, max = 500)
    @JsonPropertyDescription(
            "The table's columns reviewed for these readers. Iceberg readers get the whole table:"
                    + " while it has a column not listed here (one a source added since), it is"
                    + " not published.")
    public List<@Size(min = 1, max = 128) String> columns = new ArrayList<>();

    @Size(max = 20)
    @JsonPropertyDescription(
            "Catalog roles of the Iceberg catalog that get TABLE_READ_DATA on the table; the"
                    + " platform's admin assigns them to readers' principal roles.")
    public List<@Pattern("^[A-Za-z0-9_-]{1,64}$") String> readers = new ArrayList<>();
}
