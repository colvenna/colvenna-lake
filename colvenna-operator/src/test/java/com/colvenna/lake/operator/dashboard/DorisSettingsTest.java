package com.colvenna.lake.operator.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DorisSettingsTest {
    @Test
    void noHostIsNoDorisAndTheSecretIsNamespaced() {
        assertThat(DorisSettings.fromEnvironment(Map.<String, String>of()::get, "https://cat"))
                .isEmpty();
        DorisSettings s =
                DorisSettings.fromEnvironment(
                                Map.of(
                                                "COLVENNA_DORIS_HOST",
                                                "fe.doris.svc",
                                                "COLVENNA_DORIS_ADMIN_SECRET",
                                                "colvenna-doris/colvenna-doris-admin")
                                        ::get,
                                "https://cat")
                        .orElseThrow();
        assertThat(s)
                .isEqualTo(
                        new DorisSettings(
                                "fe.doris.svc",
                                9030,
                                "colvenna-doris",
                                "colvenna-doris-admin",
                                "https://cat"));
        assertThatThrownBy(
                        () ->
                                DorisSettings.fromEnvironment(
                                        Map.of(
                                                        "COLVENNA_DORIS_HOST",
                                                        "fe",
                                                        "COLVENNA_DORIS_ADMIN_SECRET",
                                                        "x")
                                                ::get,
                                        "https://cat"))
                .hasMessageContaining("<namespace>/<name>");
    }
}
