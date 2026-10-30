package com.colvenna.lake.operator.lineage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LineageSettingsTest {
    @Test
    void withoutAnEndpointNothingIsReported() {
        assertThat(LineageSettings.fromEnvironment(Map.<String, String>of()::get)).isEmpty();
    }

    @Test
    void theChartsValuesWithTheirDefaults() {
        LineageSettings s =
                LineageSettings.fromEnvironment(
                                Map.of("COLVENNA_LINEAGE_URL", "http://marquez:5000/api/v1/lineage")
                                        ::get)
                        .orElseThrow();
        assertThat(s.apiKeyFile()).isNull();
        assertThat(s.state()).isEqualTo("colvenna-operator-lineage");
        assertThat(s.every()).isEqualTo(Duration.ofSeconds(60));
        s =
                LineageSettings.fromEnvironment(
                                Map.of(
                                                "COLVENNA_LINEAGE_URL",
                                                "https://datahub/openapi/openlineage/api/v1/lineage",
                                                "COLVENNA_LINEAGE_API_KEY_FILE",
                                                "/lineage/api-key",
                                                "COLVENNA_LINEAGE_STATE",
                                                "r-lineage",
                                                "COLVENNA_LINEAGE_INTERVAL_SECONDS",
                                                "30")
                                        ::get)
                        .orElseThrow();
        assertThat(s.apiKeyFile()).isEqualTo(Path.of("/lineage/api-key"));
        assertThat(s.state()).isEqualTo("r-lineage");
        assertThat(s.every()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void anEndpointIsHttpAndReportsAreNotTooOften() {
        assertThatThrownBy(
                        () ->
                                LineageSettings.fromEnvironment(
                                        Map.of("COLVENNA_LINEAGE_URL", "kafka://b:9092")::get))
                .hasMessageContaining("http(s)");
        assertThatThrownBy(
                        () ->
                                LineageSettings.fromEnvironment(
                                        Map.of(
                                                        "COLVENNA_LINEAGE_URL",
                                                        "http://m/api/v1/lineage",
                                                        "COLVENNA_LINEAGE_INTERVAL_SECONDS",
                                                        "1")
                                                ::get))
                .hasMessageContaining("at least 10");
    }
}
