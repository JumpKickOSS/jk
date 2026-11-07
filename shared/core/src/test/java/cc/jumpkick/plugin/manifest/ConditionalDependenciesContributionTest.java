// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [contribute.resolution.conditional-dependencies]}: the descriptor inside a jar that names
 * an artifact's conditional dependencies, which {@code jk lock} follows for a module whose plugin is
 * active.
 */
class ConditionalDependenciesContributionTest {

    private static final String HEAD = """
            [plugin]
            id = "hello"
            table = "hello"
            version = "0.1.0"

            """;

    @Test
    void the_descriptor_and_its_two_keys_are_declared() {
        PluginDescriptor d = PluginDescriptors.parse(HEAD + """
                [contribute.resolution.conditional-dependencies]
                descriptor   = "META-INF/hello.properties"
                dependencies = "conditional"
                condition    = "when"
                """, "hello.toml");
        assertThat(d.contributions().conditionalDependencies())
                .isEqualTo(new PluginDescriptor.ConditionalDependencies(
                        "META-INF/hello.properties", "conditional", "when"));
        assertThat(d.contributions().isEmpty()).isFalse();
    }

    @Test
    void every_key_is_required() {
        assertThatThrownBy(() -> PluginDescriptors.parse(HEAD + """
                        [contribute.resolution.conditional-dependencies]
                        descriptor   = "META-INF/hello.properties"
                        dependencies = "conditional"
                        """, "hello.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("hello.toml.contribute.resolution.conditional-dependencies.condition is required");
    }

    @Test
    void a_missing_plugin_key_names_the_plugin_table() {
        assertThatThrownBy(() -> PluginDescriptors.parse("""
                        [plugin]
                        id = "hello"
                        version = "0.1.0"
                        """, "hello.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("hello.toml.plugin.table is required");
    }

    /** The built-in [quarkus] plugin names Quarkus's extension descriptor; a module without its table names none. */
    @Test
    void only_an_active_plugin_contributes(@TempDir Path dir) {
        var quarkus = JkBuildParser.parse("""
                group = "g"
                name = "n"
                version = "1"

                [quarkus]
                version = "3.40.1"
                """);
        assertThat(PluginContributions.conditionalDependencies(quarkus, dir))
                .containsExactly(new PluginDescriptor.ConditionalDependencies(
                        "META-INF/quarkus-extension.properties", "conditional-dependencies", "dependency-condition"));

        var plain = JkBuildParser.parse("""
                group = "g"
                name = "n"
                version = "1"
                """);
        assertThat(PluginContributions.conditionalDependencies(plain, dir)).isEmpty();
    }
}
