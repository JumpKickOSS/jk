// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.node.DiscoveredNode;
import cc.jumpkick.node.NodeDiscovery;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk build} of a workspace with a JVM module and a node module builds both in one run, on the
 * host's Node.js the lock pins; {@code --skip-node} leaves the node build alone.
 */
@Tag("integration")
class NodeModuleBuildE2eTest {

    @Test
    void a_workspace_builds_its_jvm_and_node_modules_together(@TempDir Path ws) throws Exception {
        List<DiscoveredNode> found = new NodeDiscovery().discover();
        assumeTrue(!found.isEmpty(), "a Node.js on this host");
        String version = found.get(0).version();
        write(ws.resolve("jk.toml"), """
                group = "com.example"
                name  = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["api", "web"]
                """);
        write(ws.resolve("api/jk.toml"), "group = \"com.example\"\nname = \"api\"\nversion = \"1.0.0\"\njava = 25\n");
        write(ws.resolve("api/src/main/java/api/Api.java"), "package api; public class Api {}");
        Path web = ws.resolve("web");
        write(
                web.resolve("jk.toml"),
                "group = \"com.example\"\nname = \"web\"\nversion = \"1.0.0\"\nnode = "
                        + version.substring(0, version.indexOf('.')) + "\n");
        write(
                web.resolve("package.json"),
                "{\"name\":\"web\",\"version\":\"1.0.0\",\"scripts\":{\"build\":\"node build.js\"}}");
        write(web.resolve("package-lock.json"), """
                {"name":"web","version":"1.0.0","lockfileVersion":3,"requires":true,
                 "packages":{"":{"name":"web","version":"1.0.0"}}}
                """);
        write(web.resolve("build.js"), """
                const fs = require('fs');
                fs.mkdirSync('dist', {recursive: true});
                fs.writeFileSync('dist/index.html', '<h1>web</h1>');
                """);
        LockfileWriter.write(
                Lockfile.empty(JkVersion.VERSION).withNode(new NodePin(version, null, null, Map.of())),
                ws.resolve("jk-lock.toml"));

        assertThat(run(
                        "build",
                        "-C",
                        ws.toString(),
                        "--cache-dir",
                        ws.resolve("cache").toString()))
                .isZero();
        assertThat(ws.resolve("api/target/api-1.0.0.jar")).exists();
        assertThat(web.resolve("dist/index.html")).hasContent("<h1>web</h1>");
        assertThat(web.resolve("target/node-install.stamp")).exists();

        Files.delete(web.resolve("dist/index.html"));
        assertThat(run(
                        "build",
                        "--skip-node",
                        "-C",
                        ws.toString(),
                        "--cache-dir",
                        ws.resolve("cache").toString()))
                .isZero();
        assertThat(web.resolve("dist/index.html"))
                .as("--skip-node runs no node step")
                .doesNotExist();

        assertThat(run(
                        "build",
                        "-C",
                        ws.toString(),
                        "--cache-dir",
                        ws.resolve("cache").toString()))
                .isZero();
        assertThat(web.resolve("dist/index.html")).as("restored from the cache").hasContent("<h1>web</h1>");

        Files.writeString(web.resolve("build.js"), Files.readString(web.resolve("build.js")) + "\n// edited\n");
        String explain = Capture.stdout(() -> run(
                "explain",
                "-v",
                "-C",
                ws.toString(),
                "--cache-dir",
                ws.resolve("cache").toString()));
        assertThat(explain).contains("node-install").contains("node-build").contains("1 file changed");
    }

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }
}
