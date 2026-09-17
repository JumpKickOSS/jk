// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;

/**
 * A manifest's {@code [entries]}: every {@code [<table>.<name>]} sub-table validates against the
 * entry schema and rides the config by name, and a {@code per-entry} step-dependency expands to one
 * tool per entry with the entry's own fields interpolated.
 */
class PluginEntriesTest {

    private static final String MANIFEST = """
            [plugin]
            id = "entries-fixture"
            table = "entries-fixture"

            [entries]
            schema = "thing"

            [sub-schema.thing]
            tool = { type = "string", required = true }
            args = { type = "string-list", default = [] }
            options = { type = "string-map", default = { verbose = "false" } }
            unpack = { type = "string" }

            [[contribute.step-dependency]]
            per-entry  = true
            artifact   = "${entry.name}"
            coordinate = "${entry.tool}"
            transitive = true
            for-step   = "run-${entry.name}"

            [[contribute.step-dependency]]
            per-entry  = true
            artifact   = "${entry.name}-unpack"
            coordinate = "${entry.unpack}"
            for-step   = "run-${entry.name}"
            """;

    private static PluginDescriptor manifest() {
        return PluginDescriptors.parse(MANIFEST, "entries-fixture.toml");
    }

    @Test
    void every_sub_table_is_one_validated_entry() {
        PluginConfig config = PluginTableRegistry.validate(manifest(), Toml.parse("""
                [api]
                tool = "org.acme:api-gen:1.0"
                args = ["-v"]
                options = { verbose = "true", lang = "java" }

                [grammar]
                tool = "org.antlr:antlr4:4.13.2"
                """));

        assertThat(config.entries().keySet()).containsExactly("api", "grammar");
        assertThat(config.entries().get("api"))
                .containsEntry("tool", "org.acme:api-gen:1.0")
                .containsEntry("args", List.of("-v"))
                .containsEntry("options", Map.of("verbose", "true", "lang", "java"));
        assertThat(config.entries().get("grammar"))
                .containsEntry("args", List.of())
                .containsEntry("options", Map.of("verbose", "false"));
    }

    @Test
    void an_entry_missing_a_required_key_names_the_entry() {
        assertThatThrownBy(() -> PluginTableRegistry.validate(manifest(), Toml.parse("[api]\nargs = []")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[entries-fixture.api] requires `tool`");
    }

    @Test
    void a_per_entry_tool_expands_once_per_entry() {
        PluginTableRegistry.putBuiltIn(manifest(), null);
        JkBuild build = JkBuildParser.parse("""
                name = "demo"
                group = "com.example"
                version = "1.0.0"
                java = 25

                [entries-fixture.api]
                tool = "org.acme:api-gen:1.0"

                [entries-fixture.grammar]
                tool = "org.antlr:antlr4:^4.13"
                """);

        assertThat(PluginContributions.stepDependencies(build, null, Map.of()))
                .extracting(
                        PluginContributions.StepDep::artifact,
                        PluginContributions.StepDep::coordinateSpec,
                        PluginContributions.StepDep::transitive,
                        PluginContributions.StepDep::forSteps)
                .containsExactly(
                        tuple("api", "org.acme:api-gen:1.0", true, List.of("run-api")),
                        tuple("grammar", "org.antlr:antlr4:^4.13", true, List.of("run-grammar")));
    }

    /** A per-entry tool over an optional key is declared only for the entries that set the key. */
    @Test
    void a_per_entry_tool_over_an_unset_optional_key_is_not_declared_for_that_entry() {
        PluginTableRegistry.putBuiltIn(manifest(), null);
        JkBuild build = JkBuildParser.parse("""
                name = "demo"
                group = "com.example"
                version = "1.0.0"
                java = 25

                [entries-fixture.api]
                tool = "org.acme:api-gen:1.0"

                [entries-fixture.wire]
                tool = "com.squareup.wire:wire-compiler:5.5.1"
                unpack = "io.zipkin.proto3:zipkin-proto3:1.0.0"
                """);

        assertThat(PluginContributions.stepDependencies(build, null, Map.of()))
                .extracting(PluginContributions.StepDep::artifact, PluginContributions.StepDep::coordinateSpec)
                .containsExactly(
                        tuple("api", "org.acme:api-gen:1.0"),
                        tuple("wire", "com.squareup.wire:wire-compiler:5.5.1"),
                        tuple("wire-unpack", "io.zipkin.proto3:zipkin-proto3:1.0.0"));
    }

    @Test
    void entry_variables_need_a_per_entry_declaration() {
        assertThatThrownBy(() -> PluginDescriptors.parse("""
                        [plugin]
                        id = "e"
                        table = "e"

                        [entries]
                        schema = "thing"

                        [sub-schema.thing]
                        tool = { type = "string", required = true }

                        [[contribute.step-dependency]]
                        artifact   = "one"
                        coordinate = "${entry.tool}"
                        """, "e.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("outside a per-entry = true step-dependency");
    }

    @Test
    void an_entry_variable_must_be_an_entry_key_or_the_name() {
        assertThatThrownBy(() -> PluginDescriptors.parse(MANIFEST.replace("${entry.tool}", "${entry.coord}"), "e.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("declares no `coord`");
    }

    @Test
    void the_entry_schema_may_not_declare_the_reserved_name_key() {
        assertThatThrownBy(() -> PluginDescriptors.parse(
                        MANIFEST.replace("tool = { type", "name = { type = \"string\" }\ntool = { type"), "e.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("${entry.name}");
    }
}
