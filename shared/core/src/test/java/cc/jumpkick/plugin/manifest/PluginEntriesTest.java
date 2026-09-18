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

            [schema]
            release = { type = "string" }

            [entries]
            schema = "thing"

            [sub-schema.thing]
            tool = { type = "string", required = true }
            args = { type = "string-list", default = [] }
            options = { type = "string-map", default = { verbose = "false" } }
            unpack = { type = "string" }
            release = { type = "string", inherit = true }

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

            [[contribute.step-dependency]]
            per-entry  = true
            artifact   = "${entry.name}-release"
            coordinate = "org.acme:release:${entry.release}"
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

    /**
     * A string-map key of the owning table is an inline table too, so it is read as the key it is
     * — its quoted names holding dots and all — and never mistaken for an entry.
     */
    @Test
    void a_string_map_key_of_the_table_is_not_an_entry() {
        PluginDescriptor manifest = PluginDescriptors.parse("""
                [plugin]
                id = "entries-fixture"
                table = "entries-fixture"

                [schema]
                replace = { type = "string-map", default = {} }

                [entries]
                schema = "thing"

                [sub-schema.thing]
                tool = { type = "string", required = true }
                """, "entries-fixture.toml");
        PluginConfig config = PluginTableRegistry.validate(manifest, Toml.parse("""
                replace = { "([^\\\\.])com.google.protobuf" = "$1org.acme.shaded.protobuf", "class Hello" = "final class Hello" }

                [api]
                tool = "org.acme:api-gen:1.0"
                """));

        assertThat(config.entries().keySet()).containsExactly("api");
        assertThat(config.stringMap("replace"))
                .containsExactly(
                        Map.entry("([^\\.])com.google.protobuf", "$1org.acme.shaded.protobuf"),
                        Map.entry("class Hello", "final class Hello"));
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

    /**
     * An entry-schema key marked {@code inherit} reads the table's value when the entry leaves it
     * unset, and the entry's own when it writes one; with neither set the tool is not declared.
     */
    @Test
    void an_inheriting_entry_key_reads_the_tables_value_unless_the_entry_writes_its_own() {
        PluginTableRegistry.putBuiltIn(manifest(), null);
        JkBuild build = JkBuildParser.parse("""
                name = "demo"
                group = "com.example"
                version = "1.0.0"
                java = 25

                [entries-fixture]
                release = "2.0"

                [entries-fixture.api]
                tool = "org.acme:api-gen:1.0"

                [entries-fixture.grammar]
                tool = "org.antlr:antlr4:4.13.2"
                release = "3.0"
                """);

        assertThat(PluginContributions.stepDependencies(build, null, Map.of()))
                .extracting(PluginContributions.StepDep::artifact, PluginContributions.StepDep::coordinateSpec)
                .contains(
                        tuple("api-release", "org.acme:release:2.0"), tuple("grammar-release", "org.acme:release:3.0"));
    }

    @Test
    void an_inheriting_entry_key_must_be_a_key_of_the_table_schema() {
        assertThatThrownBy(() -> PluginDescriptors.parse("""
                        [plugin]
                        id = "bad"
                        table = "bad"

                        [entries]
                        schema = "thing"

                        [sub-schema.thing]
                        release = { type = "string", inherit = true }
                        """, "bad.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("release")
                .hasMessageContaining("inherits");
    }

    /**
     * A coordinate that is one {@code ${config.<key>}} or {@code ${entry.<key>}} over a string-list
     * key is every coordinate of the list: the first the root, the rest with it in one closure; an
     * empty list or an unset key declares no tool.
     */
    @Test
    void a_string_list_key_stands_for_several_coordinates_of_one_closure() {
        PluginTableRegistry.putBuiltIn(PluginDescriptors.parse("""
                        [plugin]
                        id = "jars-fixture"
                        table = "jars-fixture"

                        [schema]
                        jars = { type = "string-list", default = [] }

                        [entries]
                        schema = "run"

                        [sub-schema.run]
                        jars = { type = "string-list" }

                        [[contribute.step-dependency]]
                        artifact   = "jars"
                        coordinate = "${config.jars}"
                        transitive = true
                        for-step   = "run"

                        [[contribute.step-dependency]]
                        per-entry  = true
                        artifact   = "jars-${entry.name}"
                        coordinate = "${entry.jars}"
                        transitive = true
                        for-step   = "run-${entry.name}"
                        """, "jars-fixture.toml"), null);
        String base = """
                name = "demo"
                group = "com.example"
                version = "1.0.0"
                java = 25

                [jars-fixture]
                """;

        assertThat(PluginContributions.stepDependencies(
                        JkBuildParser.parse(base + "jars = [\"com.acme:rules:1.0\", \"com.acme:checks:2.0\"]\n"),
                        null,
                        Map.of()))
                .singleElement()
                .satisfies(dep -> {
                    assertThat(dep.artifact()).isEqualTo("jars");
                    assertThat(dep.coordinateSpec()).isEqualTo("com.acme:rules:1.0");
                    assertThat(dep.with()).containsExactly("com.acme:checks:2.0");
                    assertThat(dep.transitive()).isTrue();
                });
        assertThat(PluginContributions.stepDependencies(JkBuildParser.parse(base), null, Map.of()))
                .as("an empty list declares no tool")
                .isEmpty();
        assertThat(PluginContributions.stepDependencies(
                        JkBuildParser.parse(base
                                + "\n[jars-fixture.nohttp]\njars = [\"io.spring.nohttp:nohttp-checkstyle:0.0.11\"]\n"),
                        null,
                        Map.of()))
                .extracting(PluginContributions.StepDep::artifact, PluginContributions.StepDep::coordinateSpec)
                .containsExactly(tuple("jars-nohttp", "io.spring.nohttp:nohttp-checkstyle:0.0.11"));
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
