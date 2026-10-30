package com.colvenna.lake.operator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The chart ships the CRDs generated from the Java types; regenerate by copying them over. */
class CrdsUpToDateTest {
    @Test
    void patternsStayWithinKubernetesRegexLimits() throws Exception {
        // The API server compiles patterns with RE2, which refuses a repeat count above 1000.
        java.util.regex.Pattern repeat = java.util.regex.Pattern.compile("\\{(\\d+)(,(\\d+))?\\}");
        try (var files = Files.list(Path.of("target/classes/META-INF/fabric8"))) {
            for (Path crd : files.toList()) {
                java.util.regex.Matcher m = repeat.matcher(Files.readString(crd));
                while (m.find()) {
                    int high = Integer.parseInt(m.group(3) != null ? m.group(3) : m.group(1));
                    assertThat(high)
                            .as("%s: %s", crd.getFileName(), m.group())
                            .isLessThanOrEqualTo(1000);
                }
            }
        }
    }

    @Test
    void chartCrdsMatchTheGeneratedOnes() throws Exception {
        Path generated = Path.of("target/classes/META-INF/fabric8");
        Path chart = Path.of("../deploy/helm/colvenna-operator/crds");
        try (var files = Files.list(generated)) {
            for (Path crd : files.toList()) {
                assertThat(chart.resolve(crd.getFileName()))
                        .as("copy %s to deploy/helm/colvenna-operator/crds", crd.getFileName())
                        .hasSameTextualContentAs(crd);
            }
        }
    }
}
