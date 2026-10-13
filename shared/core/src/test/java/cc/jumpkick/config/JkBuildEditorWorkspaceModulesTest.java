// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildEditorTest.BASE;
import static cc.jumpkick.config.JkBuildParserFixtures.workspaceOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import org.junit.jupiter.api.Test;

/** Tests the editor's edits to a workspace's {@code modules} array. */
class JkBuildEditorWorkspaceModulesTest {

    private static final String WS = """
            group    = "cc.jumpkick"
            name     = "jk"
            version  = "0.1.0"

            [workspace]
            modules = ["core", "io"]
            """;

    @Test
    void add_module_appends_to_inline_array() {
        String result = JkBuildEditor.addWorkspaceModule(WS, "cli");
        assertThat(result).contains("modules = [\"core\", \"io\", \"cli\"]");
        assertThat(workspaceOf(JkBuildParser.parse(result)).modules()).containsExactly("core", "io", "cli");
    }

    @Test
    void add_module_is_idempotent() {
        String result = JkBuildEditor.addWorkspaceModule(WS, "io");
        assertThat(result).isEqualTo(WS);
    }

    /** A module already covered by a glob is not appended a second time as a literal. */
    @Test
    void add_module_covered_by_a_glob_is_a_noop() {
        String start = """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = ["libs/*"]
                """;
        assertThat(JkBuildEditor.addWorkspaceModule(start, "libs/core")).isEqualTo(start);
        assertThat(JkBuildEditor.addWorkspaceModule(start, "apps/web"))
                .contains("modules = [\"libs/*\", \"apps/web\"]");
    }

    @Test
    void add_module_to_empty_inline_array() {
        String start = """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = []
                """;
        String result = JkBuildEditor.addWorkspaceModule(start, "core");
        assertThat(result).contains("modules = [\"core\"]");
        assertThat(workspaceOf(JkBuildParser.parse(result)).modules()).containsExactly("core");
    }

    @Test
    void add_module_to_multiline_array_preserves_shape_and_comments() {
        String start = """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                # the workspace
                [workspace]
                modules = [
                    "core",
                    "io",
                ]
                """;
        String result = JkBuildEditor.addWorkspaceModule(start, "cli");
        assertThat(result).contains("# the workspace");
        assertThat(result).contains("    \"cli\",");
        assertThat(workspaceOf(JkBuildParser.parse(result)).modules()).containsExactly("core", "io", "cli");
    }

    @Test
    void add_module_to_multiline_array_without_trailing_comma() {
        String start = """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = [
                    "core",
                    "io"
                ]
                """;
        String result = JkBuildEditor.addWorkspaceModule(start, "cli");
        assertThat(workspaceOf(JkBuildParser.parse(result)).modules()).containsExactly("core", "io", "cli");
    }

    @Test
    void add_module_when_no_modules_key_creates_one() {
        String start = """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                """;
        String result = JkBuildEditor.addWorkspaceModule(start, "core");
        assertThat(result).contains("modules = [\"core\"]");
        assertThat(workspaceOf(JkBuildParser.parse(result)).modules()).containsExactly("core");
    }

    @Test
    void add_module_without_workspace_table_throws() {
        assertThatThrownBy(() -> JkBuildEditor.addWorkspaceModule(BASE, "core"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no [workspace] table");
    }

    @Test
    void remove_module_from_inline_array() {
        assertThat(workspaceOf(JkBuildParser.parse(JkBuildEditor.removeWorkspaceModule(WS, "core")))
                        .modules())
                .containsExactly("io");
        assertThat(workspaceOf(JkBuildParser.parse(JkBuildEditor.removeWorkspaceModule(WS, "io")))
                        .modules())
                .containsExactly("core");
    }

    @Test
    void remove_module_from_multiline_array_drops_the_element_line() {
        String start = """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                # the workspace
                [workspace]
                modules = [
                    "core",
                    "io",
                ]
                """;
        String result = JkBuildEditor.removeWorkspaceModule(start, "core");
        assertThat(result).contains("# the workspace").doesNotContain("\"core\"");
        assertThat(workspaceOf(JkBuildParser.parse(result)).modules()).containsExactly("io");
    }

    @Test
    void remove_module_is_idempotent_for_absent_paths_and_missing_tables() {
        assertThat(JkBuildEditor.removeWorkspaceModule(WS, "not-a-module")).isEqualTo(WS);
        assertThat(JkBuildEditor.removeWorkspaceModule(BASE, "core")).isEqualTo(BASE);
    }

    @Test
    void remove_last_module_leaves_an_empty_array() {
        String one = JkBuildEditor.removeWorkspaceModule(WS, "core");
        String none = JkBuildEditor.removeWorkspaceModule(one, "io");
        assertThat(workspaceOf(JkBuildParser.parse(none)).modules()).isEmpty();
    }

    @Test
    void register_module_creates_the_workspace_table_for_a_plain_project() {
        String result = JkBuildEditor.registerWorkspaceModule(BASE, "core");
        assertThat(result).contains("[workspace]");
        assertThat(result).contains("modules = [\"core\"]");
        // The original project identity keys are preserved.
        assertThat(result).contains("name     = \"widget\"");
        JkBuild parsed = JkBuildParser.parse(result);
        assertThat(parsed.isWorkspaceRoot()).isTrue();
        assertThat(workspaceOf(parsed).modules()).containsExactly("core");
    }

    @Test
    void register_module_appends_to_an_existing_workspace_table() {
        String once = JkBuildEditor.registerWorkspaceModule(BASE, "core");
        String twice = JkBuildEditor.registerWorkspaceModule(once, "cli");
        assertThat(workspaceOf(JkBuildParser.parse(twice)).modules()).containsExactly("core", "cli");
        // Idempotent — re-registering an existing module is a no-op.
        assertThat(JkBuildEditor.registerWorkspaceModule(twice, "core")).isEqualTo(twice);
    }

    @Test
    void a_metacharacter_bearing_module_path_round_trips() {
        String weird = "mods/a\"b";
        String edited = JkBuildEditor.registerWorkspaceModule(BASE, weird);
        assertThat(workspaceOf(JkBuildParser.parse(edited)).modules()).containsExactly(weird);
        // …and removing it finds the same element it wrote.
        assertThat(JkBuildEditor.removeWorkspaceModule(edited, weird)).doesNotContain("a\\\"b");
    }

    /** A {@code ]} inside a trailing comment or a quoted element is not the array's close. */
    @Test
    void a_bracket_in_a_comment_or_string_does_not_close_the_modules_array() {
        String start = """
                group = "cc.jumpkick"
                name = "jk"
                version = "0.1.0"

                [workspace]
                modules = [
                    "core", # see [docs]
                    "lib]s",
                ]
                """;
        String removed = JkBuildEditor.removeWorkspaceModule(start, "lib]s");
        assertThat(removed).contains("# see [docs]").doesNotContain("lib]s");
        assertThat(workspaceOf(JkBuildParser.parse(removed)).modules()).containsExactly("core");

        String added = JkBuildEditor.addWorkspaceModule(start, "cli");
        assertThat(added).contains("    \"cli\",\n]");
        assertThat(workspaceOf(JkBuildParser.parse(added)).modules()).containsExactly("core", "lib]s", "cli");

        String inline = "[workspace]\nmodules = [\"core\"] # [x]\n";
        assertThat(JkBuildEditor.addWorkspaceModule(inline, "cli"))
                .isEqualTo("[workspace]\nmodules = [\"core\", \"cli\"] # [x]\n");
    }

    /** The separator goes before the last element's trailing comment, never inside it. */
    @Test
    void a_trailing_comment_on_the_last_element_keeps_the_comma_before_it() {
        String start = """
                group = "cc.jumpkick"
                name = "jk"
                version = "0.1.0"

                [workspace]
                modules = [
                    "core",
                    "io" # the '#' ends the element, not the array
                ]
                """;
        String added = JkBuildEditor.addWorkspaceModule(start, "cli");
        assertThat(added).isEqualTo("""
                group = "cc.jumpkick"
                name = "jk"
                version = "0.1.0"

                [workspace]
                modules = [
                    "core",
                    "io", # the '#' ends the element, not the array
                    "cli",
                ]
                """);
        assertThat(workspaceOf(JkBuildParser.parse(added)).modules()).containsExactly("core", "io", "cli");
    }

    /** A quoted string inside a trailing comment is neither a registered module nor one to cut. */
    @Test
    void a_quoted_string_inside_a_comment_is_not_a_module() {
        String start = """
                group = "cc.jumpkick"
                name = "jk"
                version = "0.1.0"

                [workspace]
                modules = [
                    "core", # replaced "old"; see "cli"
                    "old",
                ]
                """;
        String removed = JkBuildEditor.removeWorkspaceModule(start, "old");
        assertThat(removed).isEqualTo("""
                group = "cc.jumpkick"
                name = "jk"
                version = "0.1.0"

                [workspace]
                modules = [
                    "core", # replaced "old"; see "cli"
                ]
                """);
        assertThat(workspaceOf(JkBuildParser.parse(removed)).modules()).containsExactly("core");

        String added = JkBuildEditor.addWorkspaceModule(removed, "cli");
        assertThat(added).contains("    \"core\", # replaced \"old\"; see \"cli\"\n    \"cli\",\n]\n");
        assertThat(workspaceOf(JkBuildParser.parse(added)).modules()).containsExactly("core", "cli");
    }
}
