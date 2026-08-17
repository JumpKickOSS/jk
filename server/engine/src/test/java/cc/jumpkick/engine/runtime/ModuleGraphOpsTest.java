// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.protocol.ModuleGraphAck;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleGraphOpsTest {

    @Test
    void standalone_project_is_one_dot_node(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "0.1.0"
                """);
        ModuleGraphAck ack = ModuleGraphOps.render(dir, "dot", null, null);
        assertThat(ack.error()).isNull();
        assertThat(ack.graph()).contains("digraph modules");
        assertThat(ack.graph()).contains("com.example:app");
    }

    @Test
    void workspace_dot_includes_path_dep_edge(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name = "root"
                version = "1.0.0"

                [workspace]
                modules = ["lib", "app"]
                """);
        Files.createDirectories(ws.resolve("lib"));
        Files.writeString(ws.resolve("lib").resolve("jk.toml"), """
                group = "com.example"
                name = "lib"
                version = "1.0.0"
                """);
        Files.createDirectories(ws.resolve("app"));
        Files.writeString(ws.resolve("app").resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [dependencies]
                lib = { group = "com.example", name = "lib", version = "1.0.0" }
                """);
        ModuleGraphAck ack = ModuleGraphOps.render(ws, "dot", null, null);
        assertThat(ack.error()).isNull();
        assertThat(ack.graph()).contains("com.example:lib");
        assertThat(ack.graph()).contains("com.example:app");
    }

    @Test
    void unknown_format_is_an_error(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                """);
        ModuleGraphAck ack = ModuleGraphOps.render(dir, "plantuml", null, null);
        assertThat(ack.error()).contains("unsupported");
    }
}
