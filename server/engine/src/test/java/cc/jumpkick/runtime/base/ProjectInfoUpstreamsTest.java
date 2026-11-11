// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.ProjectInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A selection's summary names each selected module's upstream siblings, through any scope, transitively. */
class ProjectInfoUpstreamsTest {

    @Test
    void a_selected_module_names_its_upstream_siblings_and_survives_the_wire(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["core", "lib", "app", "other"]
                """);
        module(root, "core", "");
        module(root, "lib", "[dependencies]\ncore = { workspace = true }\n");
        module(root, "app", "[test-dependencies]\nlib = { workspace = true }\n");
        module(root, "other", "");

        ProjectInfo info = ProjectInfoPlans.projectInfo(root, "app,other", null);
        assertThat(info.error()).isNullOrEmpty();
        String app = root.resolve("app").toAbsolutePath().normalize().toString();
        String other = root.resolve("other").toAbsolutePath().normalize().toString();
        assertThat(info.upstreamsOf(app))
                .containsExactlyInAnyOrder(
                        root.resolve("lib").toAbsolutePath().normalize().toString(),
                        root.resolve("core").toAbsolutePath().normalize().toString());
        assertThat(info.upstreamsOf(other)).isEmpty();
        assertThat(ProjectInfo.decode(info.encode()).upstreamsOf(app)).isEqualTo(info.upstreamsOf(app));

        assertThat(ProjectInfoPlans.projectInfo(root).upstreams())
                .as("no selector, no upstreams")
                .isEmpty();
    }

    private static void module(Path root, String name, String deps) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.ex"
                name = "%s"
                version = "1.0.0"
                java = 25

                %s""".formatted(name, deps));
    }
}
