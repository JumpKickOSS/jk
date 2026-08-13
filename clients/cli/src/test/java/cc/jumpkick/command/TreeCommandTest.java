// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

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
                [project]
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
                [project]
                group   = "com.example"
                name    = "%s"
                version = "1.0.0"
                """.formatted(name));
    }
}
