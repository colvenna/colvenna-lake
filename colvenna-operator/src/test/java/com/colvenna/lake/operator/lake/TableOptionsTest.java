package com.colvenna.lake.operator.lake;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The options a declaration may add, and the defaults a table writing Iceberg metadata gets. */
class TableOptionsTest {
    @Test
    void icebergMetadataGoesBesideTheTableOnly() {
        assertThat(
                        TableOptions.refusal(
                                "spec.tableOptions",
                                Map.of("metadata.iceberg.storage", "table-location")))
                .isNull();
        assertThat(
                        TableOptions.refusal(
                                "spec.tableOptions",
                                Map.of("metadata.iceberg.storage", "rest-catalog")))
                .contains("table-location");
        assertThat(
                        TableOptions.refusal(
                                "spec.tableOptions",
                                Map.of("metadata.iceberg.previous-versions-max", "0")))
                .contains("1 to 999");
        assertThat(
                        TableOptions.refusal(
                                "spec.tableOptions", Map.of("metadata.iceberg.rest.uri", "x")))
                .contains("not allowed");
    }

    @Test
    void aTableWritingIcebergMetadataKeepsOlderVersionsAndIsFullyCompacted() {
        Map<String, String> declared = Map.of("metadata.iceberg.storage", "table-location");
        assertThat(TableOptions.icebergDefaults(declared, false))
                .containsExactly(Map.entry("metadata.iceberg.previous-versions-max", "30"));
        assertThat(TableOptions.icebergDefaults(declared, true))
                .containsEntry("compaction.optimization-interval", "1 h");
        assertThat(
                        TableOptions.icebergDefaults(
                                Map.of(
                                        "metadata.iceberg.storage",
                                        "table-location",
                                        "metadata.iceberg.previous-versions-max",
                                        "5"),
                                false))
                .isEmpty();
        assertThat(TableOptions.icebergDefaults(Map.of(), true)).isEmpty();
    }
}
