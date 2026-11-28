// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.model.PluginConfig;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A {@code [[packaging.variant]]} replaces the packaging when its {@code when} holds for the
 * module's config: one value ({@code equals}) or any of several ({@code one-of}).
 */
class PackagingVariantTest {

    private static final String MANIFEST = """
            [plugin]
            id = "p"
            table = "p"
            version = "1.0.0"

            [packaging]
            packager = "p-jar"
            exec-mode = "jar"
            self-contained = true
            app-dir = "app"
            app-jar = "run.jar"

            [[packaging.variant]]
            when = { config = "package", one-of = ["single", "one"] }
            packager = "p-jar"
            exec-mode = "jar"
            self-contained = true

            [[packaging.variant]]
            when = { config = "package", equals = "library" }
            packager = "p-lib"
            exec-mode = "none"
            """;

    private static PluginDescriptor.Packaging packaging() {
        return Objects.requireNonNull(
                PluginDescriptors.parse(MANIFEST, "p.toml").packaging());
    }

    private static PluginDescriptor.Packaging resolved(Map<String, Object> values) {
        return packaging().resolve(new PluginConfig("p", values));
    }

    @Test
    void one_of_is_read_as_its_set_of_values() {
        assertThat(packaging().variants().getFirst().when())
                .isEqualTo(new PluginDescriptor.Condition.ConfigOneOf("package", Set.of("single", "one")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"single", "one"})
    void any_value_one_of_names_selects_the_variant(String value) {
        PluginDescriptor.Packaging p = resolved(Map.of("package", value));
        assertThat(p.appDir()).isEmpty();
        assertThat(p.selfContained()).isTrue();
        assertThat(p.execMode()).isEqualTo("jar");
    }

    @Test
    void a_value_it_does_not_name_keeps_the_base_packaging() {
        assertThat(resolved(Map.of("package", "tree")).appDir()).isEqualTo("app");
        assertThat(resolved(Map.of()).appDir()).isEqualTo("app");
    }

    @Test
    void equals_still_selects_its_variant() {
        assertThat(resolved(Map.of("package", "library")).packager()).isEqualTo("p-lib");
    }

    @Test
    void a_variant_conditioned_on_anything_but_config_values_is_refused() {
        String toml = MANIFEST.replace(
                "when = { config = \"package\", equals = \"library\" }", "when = { kotlin-project = true }");
        assertThatThrownBy(() -> PluginDescriptors.parse(toml, "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("p.toml.packaging.variant");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[\"\"]", "[\"one\", 2]", "\"one\""})
    void one_of_needs_a_list_of_values(String list) {
        String toml = MANIFEST.replace("one-of = [\"single\", \"one\"]", "one-of = " + list);
        assertThatThrownBy(() -> PluginDescriptors.parse(toml, "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("one-of");
    }
}
