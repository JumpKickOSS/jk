// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.model.PluginConfig;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The manifest keys a framework plugin uses to set a module's defaults and floors: {@code [plugin]
 * min-java}, a string key's {@code one-of} and {@code min-version}, {@code
 * [contribute.test-system-properties]} and {@code [contribute.lib-dir]}.
 */
class ManifestDefaultsContributionTest {

    private static final String HEAD = """
            [plugin]
            id = "defaults-fixture"
            table = "defaults-fixture"

            """;

    @Test
    void the_floors_and_defaults_parse() {
        PluginDescriptor manifest = PluginDescriptors.parse("""
                [plugin]
                id = "defaults-fixture"
                table = "defaults-fixture"
                min-java = 27

                [schema]
                version = { type = "string", required = true, min-version = "27" }
                layout  = { type = "string", default = "libs", one-of = ["libs", "jar"] }

                [contribute.test-system-properties]
                "helidon.config.profile" = "test"

                [contribute.lib-dir]
                when = { config = "layout", equals = "libs" }
                """, "fixture.toml");

        assertThat(manifest.minJava()).isEqualTo(27);
        assertThat(Objects.requireNonNull(manifest.schema().get("version")).minVersion())
                .isEqualTo("27");
        assertThat(Objects.requireNonNull(manifest.schema().get("layout")).oneOf())
                .containsExactly("libs", "jar");
        assertThat(manifest.contributions().testSystemProperties())
                .containsExactly(Map.entry("helidon.config.profile", "test"));
        assertThat(manifest.contributions().libDir())
                .isEqualTo(new PluginDescriptor.LibDir(new PluginDescriptor.Condition.ConfigEquals("layout", "libs")));
    }

    @Test
    void a_manifest_without_them_has_none() {
        PluginDescriptor manifest = PluginDescriptors.parse(HEAD, "fixture.toml");
        assertThat(manifest.minJava()).isZero();
        assertThat(manifest.contributions().testSystemProperties()).isEmpty();
        assertThat(manifest.contributions().libDir()).isNull();
    }

    @Test
    void malformed_entries_fail_the_load() {
        for (var bad : List.of(
                Map.entry(
                        "[plugin]\nid = \"x\"\ntable = \"x\"\nmin-java = \"27\"\n", "min-java must be a Java feature"),
                Map.entry(HEAD + "[schema]\nn = { type = \"int\", one-of = [\"1\"] }\n", "apply to string keys"),
                Map.entry(
                        HEAD + "[schema]\nl = { type = \"string\", default = \"war\", one-of = [\"jar\"] }\n",
                        "the default \"war\" is not one of [jar]"),
                Map.entry(HEAD + "[contribute.test-system-properties]\nflag = true\n", "\"flag\" must be a string"),
                Map.entry(
                        HEAD + "[contribute.lib-dir]\nwhen = { classpath-has = \"g:a\" }\n",
                        "classpath-has cannot gate the layout"),
                Map.entry(HEAD + "[contribute.lib-dir]\ndir = \"lib\"\n", "unknown key `dir`"))) {
            assertThatThrownBy(() -> PluginDescriptors.parse(bad.getKey(), "fixture.toml"))
                    .isInstanceOf(JkBuildParseException.class)
                    .hasMessageContaining(bad.getValue());
        }
    }

    @Test
    void the_first_plugin_to_name_a_test_property_keeps_it() {
        PluginDescriptor a = PluginDescriptors.parse("""
                [plugin]
                id = "a"
                table = "a"

                [contribute.test-system-properties]
                shared = "from-a"
                """, "a.toml");
        PluginDescriptor b = PluginDescriptors.parse("""
                [plugin]
                id = "b"
                table = "b"

                [contribute.test-system-properties]
                shared = "from-b"
                own = "b"
                """, "b.toml");
        var configs = Map.of(
                "a", new PluginConfig("a", Map.of()),
                "b", new PluginConfig("b", Map.of()));

        assertThat(PluginContributions.testSystemProperties(configs, List.of(a, b)))
                .containsExactly(Map.entry("shared", "from-a"), Map.entry("own", "b"));
        assertThat(PluginContributions.testSystemProperties(
                        Map.of("b", new PluginConfig("b", Map.of())), List.of(a, b)))
                .as("a plugin whose table the module does not declare contributes nothing")
                .containsExactly(Map.entry("shared", "from-b"), Map.entry("own", "b"));
    }
}
