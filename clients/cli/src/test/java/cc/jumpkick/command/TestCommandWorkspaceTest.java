// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Workspace-root test selection fans out to members. */
class TestCommandWorkspaceTest {

    @Test
    void all_workspace_module_dirs_lists_members(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                name = "ws"
                group = "g"
                version = "0.1.0"

                [workspace]
                modules = ["a", "b/c"]
                """);
        Files.createDirectories(tmp.resolve("a"));
        Files.createDirectories(tmp.resolve("b/c"));
        Files.writeString(tmp.resolve("a/jk.toml"), "name = \"a\"\ngroup = \"g\"\nversion = \"0.1.0\"\n");
        Files.writeString(tmp.resolve("b/c/jk.toml"), "name = \"c\"\ngroup = \"g\"\nversion = \"0.1.0\"\n");

        var entry = JkBuildParser.parse(tmp.resolve("jk.toml"));
        Set<Path> dirs = TestCommand.allWorkspaceModuleDirs(tmp, entry);
        assertThat(dirs)
                .containsExactly(
                        tmp.resolve("a").toAbsolutePath().normalize(),
                        tmp.resolve("b/c").toAbsolutePath().normalize());
    }

    @Test
    void all_workspace_module_dirs_empty_for_standalone(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                name = "solo"
                group = "g"
                version = "0.1.0"
                """);
        var entry = JkBuildParser.parse(tmp.resolve("jk.toml"));
        assertThat(TestCommand.allWorkspaceModuleDirs(tmp, entry)).isEmpty();
    }
}
