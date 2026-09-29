// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** A lock whose launcher and Jupiter engine sit on different Platform lines is refused. */
class JupiterAlignmentTest {

    @Test
    void a_launcher_off_the_jupiter_engines_line_is_refused_with_the_aligning_pin() {
        Resolution mixed = resolution(Map.of(
                "org.junit.platform:junit-platform-launcher:jar:",
                "6.1.3",
                "org.junit.jupiter:junit-jupiter-engine:jar:",
                "5.9.0"));
        assertThatThrownBy(() -> JupiterAlignment.check(mixed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("junit-jupiter 5.9.0 runs on JUnit Platform 1.9.0")
                .hasMessageContaining("junit-platform-launcher resolved to 6.1.3")
                .hasMessageContaining("pin junit-platform-launcher to 1.9.0");
    }

    @Test
    void aligned_lines_and_a_graph_without_jupiter_pass() {
        assertThatCode(() -> JupiterAlignment.check(resolution(Map.of(
                        "org.junit.platform:junit-platform-launcher:jar:",
                        "1.9.2",
                        "org.junit.jupiter:junit-jupiter-engine:jar:",
                        "5.9.0"))))
                .as("a patch apart on one line is one line")
                .doesNotThrowAnyException();
        assertThatCode(() -> JupiterAlignment.check(resolution(Map.of(
                        "org.junit.platform:junit-platform-launcher:jar:",
                        "6.1.3",
                        "org.junit.jupiter:junit-jupiter-api:jar:",
                        "6.1.3"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> JupiterAlignment.check(resolution(Map.of(
                        "org.junit.platform:junit-platform-launcher:jar:", "6.1.3", "junit:junit:jar:", "4.13.2"))))
                .as("no Jupiter, nothing to align")
                .doesNotThrowAnyException();
    }

    private static Resolution resolution(Map<String, String> versions) {
        Map<String, Resolution.ResolvedModule> modules = new TreeMap<>();
        versions.forEach(
                (module, version) -> modules.put(module, new Resolution.ResolvedModule(module, version, List.of())));
        return new Resolution(modules);
    }
}
