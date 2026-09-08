// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TomlScan;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Workspace-root module lists come from bootstrap TOML (engine owns the full parse). */
class TestCommandWorkspaceTest {

    @Test
    void workspace_modules_scan_lists_members(@TempDir Path tmp) throws Exception {
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

        assertThat(TomlScan.scan(tmp.resolve("jk.toml"), "workspace.modules").stringArray("workspace.modules"))
                .containsExactly("a", "b/c");
    }

    @Test
    void workspace_modules_scan_empty_for_standalone(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                name = "solo"
                group = "g"
                version = "0.1.0"
                """);
        assertThat(TomlScan.scan(tmp.resolve("jk.toml"), "workspace.modules").stringArray("workspace.modules"))
                .isEmpty();
    }
}
