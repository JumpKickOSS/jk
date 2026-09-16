// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module runs every code plugin whose table it declares; capability decides how many of a kind
 * it may hold. One packager replaces the main artifact, a second one is refused by name, and the
 * merged plan owns one namespace of step and command names.
 */
class ActivePluginsTest {

    private static final String HEADER = """
            name = "svc"
            group = "com.example"
            version = "1.0.0"
            java = 25
            """;

    @Test
    void a_generator_table_beside_a_framework_table_makes_two_active_plugins(@TempDir Path dir) {
        JkBuild build = JkBuildParser.parse(HEADER + """
                [spring-boot]
                version = "4.1.1"

                [openapi]
                generator = "spring"
                """);

        List<PluginBuild.Active> active = ActivePlugins.of(build, dir);
        assertThat(active).extracting(a -> a.manifest().id()).containsExactlyInAnyOrder("spring-boot", "openapi");
        assertThat(ActivePlugins.packager(active))
                .as("the framework packages the jar; the generator only contributes a step")
                .map(a -> a.manifest().id())
                .contains("spring-boot");
    }

    @Test
    void two_main_artifact_packagers_are_refused_by_name(@TempDir Path dir) {
        JkBuild build = JkBuildParser.parse(HEADER + """
                [spring-boot]
                version = "4.1.1"

                [quarkus]
                version = "3.38.0"
                """);

        assertThatThrownBy(() -> ActivePlugins.of(build, dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both package this module's main artifact")
                .hasMessageContaining("[spring-boot]")
                .hasMessageContaining("[quarkus]");
    }

    @Test
    void a_packager_that_writes_beside_the_main_artifact_joins_the_one_that_replaces_it(@TempDir Path dir) {
        JkBuild build = JkBuildParser.parse(HEADER + """
                [application]
                main     = "com.example.App"
                minified = true

                [spring-boot]
                version = "4.1.1"

                [minified]
                """);

        List<PluginBuild.Active> active = ActivePlugins.of(build, dir);
        assertThat(active).extracting(a -> a.manifest().id()).containsExactlyInAnyOrder("spring-boot", "minified");
        assertThat(ActivePlugins.packager(active)).map(a -> a.manifest().id()).contains("spring-boot");
    }

    @Test
    void a_lone_generator_table_is_active_with_no_packager(@TempDir Path dir) {
        JkBuild build = JkBuildParser.parse(HEADER + """
                [openapi]
                generator = "spring"

                [platform-dependencies]
                spring-boot-dependencies = "4.1.1"
                """);

        List<PluginBuild.Active> active = ActivePlugins.of(build, dir);
        assertThat(active).extracting(a -> a.manifest().id()).containsExactly("openapi");
        assertThat(ActivePlugins.packager(active)).isEmpty();
    }

    @Test
    void merged_declarations_keep_every_step_with_its_owner_and_the_main_artifact_packager(@TempDir Path dir)
            throws Exception {
        JkBuild build = JkBuildParser.parse(HEADER + """
                [spring-boot]
                version = "4.1.1"

                [openapi]
                generator = "spring"
                """);
        List<PluginBuild.Active> active = ActivePlugins.of(build, dir);
        Map<String, PluginBuild.Declarations> byId = Map.of(
                "spring-boot",
                        new PluginBuild.Declarations(
                                List.of(step("spring-aot")),
                                new PluginBuild.PackagerDecl("boot-jar", List.of("classes")),
                                List.of()),
                "openapi",
                        new PluginBuild.Declarations(
                                List.of(step("generate-openapi")),
                                null,
                                List.of(new PluginBuild.CommandDecl("openapi-validate", "validate"))));

        ActivePlugins.Declared merged = ActivePlugins.merge(
                active, a -> requireNonNull(byId.get(a.manifest().id())));

        assertThat(merged.decls().steps())
                .extracting(PluginBuild.TaskDecl::name)
                .containsExactlyInAnyOrder("spring-aot", "generate-openapi");
        assertThat(merged.ownerOf(step("generate-openapi")).manifest().id()).isEqualTo("openapi");
        assertThat(merged.ownerOf(step("spring-aot")).manifest().id()).isEqualTo("spring-boot");
        assertThat(requireNonNull(merged.decls().packager()).name()).isEqualTo("boot-jar");
        assertThat(requireNonNull(merged.packager()).manifest().id()).isEqualTo("spring-boot");
        assertThat(requireNonNull(merged.commandOwners().get("openapi-validate"))
                        .manifest()
                        .id())
                .isEqualTo("openapi");
    }

    @Test
    void a_step_name_two_plugins_register_is_refused(@TempDir Path dir) {
        JkBuild build = JkBuildParser.parse(HEADER + """
                [spring-boot]
                version = "4.1.1"

                [openapi]
                generator = "spring"
                """);
        List<PluginBuild.Active> active = ActivePlugins.of(build, dir);

        assertThatThrownBy(() -> ActivePlugins.merge(
                        active, a -> new PluginBuild.Declarations(List.of(step("generate")), null, List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both register a step named `generate`")
                .hasMessageContaining("[openapi]")
                .hasMessageContaining("[spring-boot]");
    }

    private static PluginBuild.TaskDecl step(String name) {
        return new PluginBuild.TaskDecl(
                name, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
    }
}
