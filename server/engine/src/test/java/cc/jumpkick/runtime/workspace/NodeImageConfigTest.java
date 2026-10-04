// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.model.ImageTable;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A node module's image config: the server and static defaults, under what [image] sets. */
class NodeImageConfigTest {

    @Test
    void a_server_defaults_to_the_locked_distroless_base_port_3000_and_a_non_root_user(@TempDir Path dir)
            throws IOException {
        JkBuild project = project(dir, "");

        ImageConfig config = NodeImagePlans.config(project.image(), project, dir, false, null, null, "docker");

        assertThat(config.base()).isEqualTo("gcr.io/distroless/nodejs24-debian13");
        assertThat(config.ports()).containsExactly(3000);
        assertThat(config.user()).isEqualTo("65532");
        assertThat(config.env())
                .containsEntry("PORT", "3000")
                .containsEntry("HOSTNAME", "0.0.0.0")
                .containsEntry("NODE_ENV", "production");
    }

    @Test
    void the_table_wins_over_every_default(@TempDir Path dir) throws IOException {
        JkBuild project = project(dir, """

                [image]
                base = "node:24-slim"
                ports = [8080]
                user = "node"
                env = { PORT = "8080" }
                """);

        ImageConfig config = NodeImagePlans.config(project.image(), project, dir, false, null, null, "docker");

        assertThat(config.base()).isEqualTo("node:24-slim");
        assertThat(config.ports()).containsExactly(8080);
        assertThat(config.user()).isEqualTo("node");
        assertThat(config.env()).containsEntry("PORT", "8080").containsEntry("NODE_ENV", "production");
    }

    @Test
    void a_static_image_is_nginx_on_port_80(@TempDir Path dir) throws IOException {
        JkBuild project = project(dir, "");

        ImageConfig config = NodeImagePlans.config(project.image(), project, dir, true, null, null, "docker");

        assertThat(config.base()).isEqualTo("nginx:stable-alpine");
        assertThat(config.ports()).containsExactly(80);
        assertThat(config.user()).isNull();
        assertThat(config.env()).isEmpty();
    }

    @Test
    void a_server_needs_its_node_locked(@TempDir Path dir) throws IOException {
        JkBuild project = project(dir, "");
        Files.delete(dir.resolve("jk-lock.toml"));

        assertThatThrownBy(() -> NodeImagePlans.config(ImageTable.EMPTY, project, dir, false, null, null, "docker"))
                .hasMessageContaining("jk lock");
    }

    private static JkBuild project(Path dir, String extra) throws IOException {
        Files.writeString(
                dir.resolve("jk.toml"), "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\nnode = 24\n" + extra);
        Files.writeString(dir.resolve("package.json"), "{\"name\":\"web\",\"scripts\":{\"start\":\"node server.js\"}}");
        Files.writeString(dir.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"

                [node]
                version = "24.21.0"
                """);
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }
}
