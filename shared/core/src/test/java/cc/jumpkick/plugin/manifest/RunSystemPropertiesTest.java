// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** {@code [packaging] run-system-properties}: the {@code -D}s a launch from the classes dir passes. */
class RunSystemPropertiesTest {

    private static final String HEAD = """
            [plugin]
            id = "fw"
            table = "fw"
            version = "1.0.0"

            [packaging]
            packager = "fw-jar"
            exec-mode = "jar"
            """;

    @Test
    void the_table_parses_and_is_empty_when_absent() {
        var declared =
                PluginDescriptors.parse(HEAD + "run-system-properties = { \"fw.dir\" = \"target\" }\n", "t.toml");
        assertThat(Objects.requireNonNull(declared.packaging()).runSystemProperties())
                .containsExactly(Map.entry("fw.dir", "target"));
        assertThat(Objects.requireNonNull(
                                PluginDescriptors.parse(HEAD, "t.toml").packaging())
                        .runSystemProperties())
                .isEmpty();
    }

    @Test
    void a_value_that_is_not_a_string_is_refused() {
        assertThatThrownBy(
                        () -> PluginDescriptors.parse(HEAD + "run-system-properties = { \"fw.dir\" = 1 }\n", "t.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("run-system-properties.fw.dir must be a string");
    }

    @Test
    void grails_runs_with_its_build_directory_under_target() {
        var grails = Objects.requireNonNull(
                PluginTableRegistry.byTable("grails").orElseThrow().packaging());
        assertThat(grails.runSystemProperties()).containsEntry("grails.project.target.dir", "target");
    }
}
