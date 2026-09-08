// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.command.toolchain.ToolRunCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk run <target>} workspace-module resolution — a shared leaf must not silently
 * pick whichever module is declared first, and a leaf shortcut must not shadow a real local path.
 */
class ToolRunModuleResolutionTest {

    private static void module(Path ws, String rel) throws Exception {
        Path dir = ws.resolve(rel);
        Files.createDirectories(dir);
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name  = "%s"
                version = "1.0.0"
                jdk = 25
                java = 25
                """.formatted(name));
    }

    private static Path workspace(Path ws, String... modules) throws Exception {
        StringBuilder list = new StringBuilder();
        for (String m : modules) {
            if (list.length() > 0) list.append(", ");
            list.append('"').append(m).append('"');
        }
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk = 25
                java = 25

                [workspace]
                modules = [%s]
                """.formatted(list));
        for (String m : modules) module(ws, m);
        return ws;
    }

    @Test
    void exact_module_path_resolves(@TempDir Path ws) throws Exception {
        workspace(ws, "clients/web", "server/web");

        assertThat(ToolRunCommand.resolveWorkspaceModule(ws, "clients/web"))
                .isEqualTo(ws.resolve("clients/web").toAbsolutePath().normalize());
    }

    @Test
    void unique_leaf_resolves(@TempDir Path ws) throws Exception {
        workspace(ws, "clients/cli", "server/engine");

        assertThat(ToolRunCommand.resolveWorkspaceModule(ws, "cli"))
                .isEqualTo(ws.resolve("clients/cli").toAbsolutePath().normalize());
    }

    @Test
    void duplicate_leaf_is_ambiguous_not_first_wins(@TempDir Path ws) throws Exception {
        workspace(ws, "clients/web", "server/web");

        assertThatThrownBy(() -> ToolRunCommand.resolveWorkspaceModule(ws, "web"))
                .isInstanceOf(ToolRunCommand.AmbiguousModuleTarget.class)
                .hasMessageContaining("clients/web")
                .hasMessageContaining("server/web");
    }

    @Test
    void a_real_local_path_is_not_shadowed_by_a_leaf_match(@TempDir Path ws) throws Exception {
        workspace(ws, "clients/web");
        Files.createDirectories(ws.resolve("web")); // a plain local dir named like the leaf

        // Local path wins: the classifiers later in the dispatch own `./web`.
        assertThat(ToolRunCommand.resolveWorkspaceModule(ws, "web")).isNull();
        // The exact declared path still selects the module.
        assertThat(ToolRunCommand.resolveWorkspaceModule(ws, "clients/web"))
                .isEqualTo(ws.resolve("clients/web").toAbsolutePath().normalize());
    }
}
