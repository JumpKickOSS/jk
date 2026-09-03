// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.model.Scope;
import cc.jumpkick.resolver.DependencyTreeStyle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreeCommandTest {

    @Test
    void default_depth_is_declared_only() {
        assertThat(TreeCommand.maxDepth(false, null)).isEqualTo(0);
    }

    @Test
    void transitive_flag_expands_the_lockfile_closure() {
        assertThat(TreeCommand.maxDepth(true, null)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void explicit_depth_wins_over_transitive() {
        assertThat(TreeCommand.maxDepth(true, 2)).isEqualTo(2);
        assertThat(TreeCommand.maxDepth(false, 1)).isEqualTo(1);
    }

    /** The default scope set is the style's declared order, through the command, not the list alone. */
    @Test
    void scopes_default_to_export_main_runtime() {
        assertThat(TreeCommand.parseScopes(null))
                .containsExactlyElementsOf(DependencyTreeStyle.defaultScopeOrder())
                .extracting(Scope::canonical)
                .containsExactly("export", "main", "runtime");
    }

    @Test
    void scopes_keep_the_order_given_and_drop_repeats() {
        assertThat(TreeCommand.parseScopes("runtime, main,export,main"))
                .extracting(Scope::canonical)
                .containsExactly("runtime", "main", "export");
    }

    @Test
    void all_and_exec_are_meta_tokens() {
        assertThat(TreeCommand.parseScopes("ALL")).containsExactlyElementsOf(DependencyTreeStyle.allScopeOrder());
        assertThat(TreeCommand.parseScopes("exec")).isEqualTo(TreeCommand.parseScopes("run"));
        assertThat(TreeCommand.parseScopes("run")).isNotEmpty();
    }

    @Test
    void an_unknown_scope_names_the_valid_set() {
        assertThatThrownBy(() -> TreeCommand.parseScopes("main,bogus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid scope 'bogus'")
                .hasMessageContaining("exec/run, all");
        assertThatThrownBy(() -> TreeCommand.parseScopes(" , "))
                .hasMessageContaining("--scopes requires at least one scope");
    }

    /** The short flags and their long names, on the surface the user reads. */
    @Test
    void help_lists_the_short_flags() {
        String help =
                Capture.stdout(() -> assertThat(Jk.execute("tree", "--help")).isZero());
        assertThat(help)
                .contains("-d, --depth")
                .contains("-f, --flatten")
                .contains("-S, --stack")
                .contains("-s, --scopes")
                .contains("exec/run/all");
    }

    @Test
    void bare_tree_uses_workspace_root_from_a_member_dir(@TempDir Path tmp) throws IOException {
        workspace(tmp, "foo", "foo/bar");
        module(tmp.resolve("foo"), "foo");
        module(tmp.resolve("foo").resolve("bar"), "bar");

        assertThat(TreeCommand.resolveTreeDir(tmp, null).dir())
                .isEqualTo(tmp.toAbsolutePath().normalize());
        assertThat(TreeCommand.resolveTreeDir(tmp.resolve("foo"), null).dir())
                .isEqualTo(tmp.toAbsolutePath().normalize());
        assertThat(TreeCommand.resolveTreeDir(tmp.resolve("foo").resolve("bar"), null)
                        .dir())
                .isEqualTo(tmp.toAbsolutePath().normalize());
    }

    @Test
    void colon_name_selects_the_workspace_module(@TempDir Path tmp) throws IOException {
        workspace(tmp, "foo", "foo/bar");
        module(tmp.resolve("foo"), "foo");
        module(tmp.resolve("foo").resolve("bar"), "bar");

        assertThat(TreeCommand.resolveTreeDir(tmp, ":foo").dir())
                .isEqualTo(tmp.resolve("foo").toAbsolutePath().normalize());
        assertThat(TreeCommand.resolveTreeDir(tmp.resolve("foo"), ":bar").dir())
                .isEqualTo(tmp.resolve("foo").resolve("bar").toAbsolutePath().normalize());
    }

    @Test
    void path_selects_a_module_directory(@TempDir Path tmp) throws IOException {
        workspace(tmp, "foo", "foo/bar");
        module(tmp.resolve("foo"), "foo");
        module(tmp.resolve("foo").resolve("bar"), "bar");

        assertThat(TreeCommand.resolveTreeDir(tmp, "foo").dir())
                .isEqualTo(tmp.resolve("foo").toAbsolutePath().normalize());
        assertThat(TreeCommand.resolveTreeDir(tmp, "foo/bar").dir())
                .isEqualTo(tmp.resolve("foo").resolve("bar").toAbsolutePath().normalize());
        assertThat(TreeCommand.resolveTreeDir(tmp.resolve("foo"), ".").dir())
                .isEqualTo(tmp.resolve("foo").toAbsolutePath().normalize());
        assertThat(TreeCommand.resolveTreeDir(tmp, ".").dir())
                .isEqualTo(tmp.toAbsolutePath().normalize());
    }

    @Test
    void path_from_a_sibling_resolves_against_the_workspace_root(@TempDir Path tmp) throws IOException {
        workspace(tmp, "foo", "other");
        module(tmp.resolve("foo"), "foo");
        module(tmp.resolve("other"), "other");

        assertThat(TreeCommand.resolveTreeDir(tmp.resolve("other"), "foo").dir())
                .isEqualTo(tmp.resolve("foo").toAbsolutePath().normalize());
    }

    @Test
    void unknown_module_or_path_fails(@TempDir Path tmp) throws IOException {
        workspace(tmp, "foo");
        module(tmp.resolve("foo"), "foo");

        assertThat(TreeCommand.resolveTreeDir(tmp, ":missing").ok()).isFalse();
        assertThat(TreeCommand.resolveTreeDir(tmp, "missing").ok()).isFalse();
        assertThat(TreeCommand.resolveTreeDir(tmp, ":").ok()).isFalse();
    }

    @Test
    void standalone_project_is_its_own_scope(@TempDir Path tmp) throws IOException {
        module(tmp, "solo");
        assertThat(TreeCommand.resolveTreeDir(tmp, null).dir())
                .isEqualTo(tmp.toAbsolutePath().normalize());
        assertThat(TreeCommand.resolveTreeDir(tmp, ".").dir())
                .isEqualTo(tmp.toAbsolutePath().normalize());
        assertThat(TreeCommand.resolveTreeDir(tmp, ":solo").dir())
                .isEqualTo(tmp.toAbsolutePath().normalize());
    }

    private static void workspace(Path dir, String... modules) throws IOException {
        Files.createDirectories(dir);
        String mods =
                String.join(", ", Arrays.stream(modules).map(m -> '"' + m + '"').toList());
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "root"
                version = "1.0.0"

                [workspace]
                modules = [%s]
                """.formatted(mods));
    }

    private static void module(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "%s"
                version = "1.0.0"
                """.formatted(name));
    }
}
