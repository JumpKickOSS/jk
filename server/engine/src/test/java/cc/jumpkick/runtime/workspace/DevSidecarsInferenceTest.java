// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.node.DiscoveredNode;
import cc.jumpkick.node.NodeDiscovery;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sidecars {@code jk dev} infers for the node modules an app depends on, and an entry the
 * manifest writes for one of them laid over it key by key.
 */
class DevSidecarsInferenceTest {

    @Test
    void a_dependency_s_dev_server_is_inferred_and_a_written_entry_is_laid_over_it(@TempDir Path ws) throws Exception {
        String node = hostNode();
        workspace(ws, node);
        write(ws.resolve("api/jk.toml"), """
                group = "g"
                name = "api"
                version = "1"

                [dependencies]
                web = { workspace = true }

                [dev.sidecars]
                web = { ready-timeout = "2m", env = { MODE = "dev" } }
                """);
        web(ws.resolve("web"), node);

        List<ExecPlan.Sidecar> sidecars = DevSidecars.resolve(
                ws.resolve("api"), JkBuildParser.parse(ws.resolve("api/jk.toml")), Map.of("PATH", "/usr/bin"));

        assertThat(sidecars).hasSize(1);
        ExecPlan.Sidecar web = sidecars.getFirst();
        assertThat(web.name()).isEqualTo("web");
        assertThat(web.command())
                .as("inferred: the package manager runs the dev script")
                .contains("run", "dev");
        assertThat(web.cwd()).isEqualTo(ws.resolve("web").toString());
        assertThat(web.probe().ready()).isEqualTo("http://localhost:5199");
        assertThat(web.probe().readyTimeoutMillis()).as("written").isEqualTo(120_000);
        assertThat(web.env()).containsEntry("MODE", "dev").containsKey("NODE_HOME");
        assertThat(web.frontDoor())
                .as("the one inferred server, with no other front door")
                .isTrue();
    }

    @Test
    void a_written_entry_without_a_command_must_name_a_node_module(@TempDir Path ws) throws Exception {
        String node = hostNode();
        workspace(ws, node);
        write(ws.resolve("api/jk.toml"), """
                group = "g"
                name = "api"
                version = "1"

                [dev.sidecars]
                db = { ready-timeout = "2m" }
                """);
        web(ws.resolve("web"), node);

        assertThatThrownBy(() -> DevSidecars.resolve(
                        ws.resolve("api"), JkBuildParser.parse(ws.resolve("api/jk.toml")), Map.of()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("[dev.sidecars.db] has no command");
    }

    private static String hostNode() {
        List<DiscoveredNode> found = new NodeDiscovery().discover();
        assumeTrue(!found.isEmpty(), "a Node.js on this host");
        return found.get(0).version();
    }

    private static void workspace(Path ws, String node) throws IOException {
        write(ws.resolve("jk.toml"), """
                group = "g"
                name = "ws"
                version = "1"

                [workspace]
                modules = ["api", "web"]
                """);
        LockfileWriter.write(
                Lockfile.empty(JkVersion.VERSION).withNode(new NodePin(node, null, null, Map.of())),
                ws.resolve("jk-lock.toml"));
    }

    private static void web(Path dir, String node) throws IOException {
        write(dir.resolve("jk.toml"), """
                group = "g"
                name = "web"
                version = "1"

                [node]
                version = %s
                dev-port = 5199
                """.formatted(node.substring(0, node.indexOf('.'))));
        write(dir.resolve("package.json"), "{\"name\":\"web\",\"scripts\":{\"dev\":\"node dev.js\"}}");
        write(dir.resolve("package-lock.json"), "{\"name\":\"web\",\"lockfileVersion\":3,\"packages\":{\"\":{}}}");
    }

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
