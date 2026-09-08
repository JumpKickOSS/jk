// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * jobs register their workspace-root entry dir, so {@code jk cancel} (and Ctrl-C) from a
 * member dir must resolve to the root before matching — a raw member path matched nothing.
 */
class CancelCommandScopeTest {

    @Test
    void a_workspace_member_resolves_to_the_root(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["app"]
                """);
        Files.createDirectories(ws.resolve("app"));
        Files.writeString(ws.resolve("app/jk.toml"), """
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);

        assertThat(CancelCommand.cancelScope(ws.resolve("app")))
                .isEqualTo(ws.toAbsolutePath().normalize());
    }

    @Test
    void a_standalone_project_is_its_own_scope(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name  = "solo"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);

        assertThat(CancelCommand.cancelScope(dir))
                .isEqualTo(dir.toAbsolutePath().normalize());
    }
}
