// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.node.DiscoveredNode;
import cc.jumpkick.node.NodeDiscovery;
import cc.jumpkick.testing.OciImages;
import cc.jumpkick.testing.RegistryStub;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk image} end to end, through the CLI and the engine, against a local registry: a JVM
 * application and a node server, each written to a tarball and pushed, built on base images the
 * registry serves.
 */
@Tag("integration")
@DisabledOnOs(OS.WINDOWS)
class ImageRegistryE2eTest {

    private RegistryStub registry;
    private String nodeVersion = "";

    @BeforeEach
    void start() throws IOException {
        List<DiscoveredNode> found = new NodeDiscovery().discover();
        assumeTrue(!found.isEmpty(), "a Node.js on this host");
        nodeVersion = found.get(0).version();
        registry = RegistryStub.open();
        registry.publishImage("base/jre", "25");
        registry.publishImage("base/nodejs", "24");
    }

    @AfterEach
    void stop() {
        if (registry != null) registry.close();
    }

    @Test
    void a_jvm_application_image_is_written_and_pushed(@TempDir Path ws) throws Exception {
        workspace(ws);

        Path tarball = ws.resolve("out/app.tar");
        assertThat(image(ws, "app", "--tarball", tarball.toString())).isZero();
        OciImages.Image built = OciImages.fromTarball(tarball);
        assertThat(built.files()).anyMatch(f -> f.endsWith("app-1.0.0.jar") || f.contains("example/Main.class"));
        assertThat(built.config()).contains("java").contains("\"amd64\"");

        assertThat(image(ws, "app", "--registry", registry.hostPort())).isZero();
        assertThat(registry.holdsManifest("acme/app", "1.0.0")).isTrue();
        OciImages.Image pushed = OciImages.fromRegistry(registry, "acme/app", "1.0.0");
        assertThat(pushed.files()).containsExactlyInAnyOrderElementsOf(built.files());
    }

    @Test
    void an_edit_rewrites_only_the_application_layer(@TempDir Path ws) throws Exception {
        workspace(ws);
        Path first = ws.resolve("out/first.tar");
        assertThat(image(ws, "app", "--tarball", first.toString())).isZero();

        Path main = ws.resolve("app/src/main/java/example/Main.java");
        Files.writeString(main, Files.readString(main).replace("hello", "hello again"));
        Path second = ws.resolve("out/second.tar");
        assertThat(image(ws, "app", "--tarball", second.toString())).isZero();

        List<String> before = OciImages.fromTarball(first).layerIds();
        List<String> after = OciImages.fromTarball(second).layerIds();
        assertThat(after).hasSameSizeAs(before);
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) if (!before.get(i).equals(after.get(i))) changed.add(i);
        assertThat(changed).as("only the layer holding the application changes").hasSize(1);
    }

    @Test
    void a_node_server_image_is_written_and_pushed(@TempDir Path ws) throws Exception {
        workspace(ws);

        Path tarball = ws.resolve("out/web.tar");
        assertThat(image(ws, "web", "--tarball", tarball.toString())).isZero();
        OciImages.Image built = OciImages.fromTarball(tarball);
        assertThat(built.files()).contains("app/server.js", "app/package.json");
        assertThat(built.config()).contains("server.js").contains("PORT=3000").contains("65532");

        assertThat(image(ws, "web", "--registry", registry.hostPort())).isZero();
        assertThat(registry.holdsManifest("acme/web", "1.0.0")).isTrue();
        assertThat(OciImages.fromRegistry(registry, "acme/web", "1.0.0").files())
                .contains("app/server.js");
    }

    @Test
    void an_up_to_date_member_image_is_still_written(@TempDir Path ws) throws Exception {
        workspace(ws);
        Path tarball = ws.resolve("out/web.tar");
        assertThat(image(ws, "web", "--tarball", tarball.toString())).isZero();
        Files.delete(tarball);

        assertThat(image(ws, "web", "--tarball", tarball.toString())).isZero();
        assertThat(tarball)
                .as("a cached image is written to the requested target")
                .isRegularFile();
    }

    private int image(Path ws, String module, String... target) {
        List<String> args = new ArrayList<>(List.of(
                "image",
                "-C",
                ws.toString(),
                "-m",
                module,
                "--cache-dir",
                ws.resolve("cache").toString()));
        args.addAll(List.of(target));
        return run(args.toArray(String[]::new));
    }

    /** {@code app}: a JVM application; {@code web}: a node server needing no install. */
    private void workspace(Path ws) throws IOException {
        write(ws.resolve("jk.toml"), """
                group = "com.example"
                name  = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["app", "web"]
                """);
        write(ws.resolve("app/jk.toml"), """
                group = "com.example"
                name  = "app"
                version = "1.0.0"

                [application]
                main = "example.Main"

                [image]
                base = "%s/base/jre:25"
                name = "acme/app"
                """.formatted(registry.hostPort()));
        write(ws.resolve("app/src/main/java/example/Main.java"), """
                package example;
                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                """);
        String major = nodeVersion.substring(0, nodeVersion.indexOf('.'));
        write(ws.resolve("web/jk.toml"), """
                group = "com.example"
                name  = "web"
                version = "1.0.0"

                [node]
                version = %s
                start = "node server.js"

                [image]
                base = "%s/base/nodejs:24"
                name = "acme/web"
                """.formatted(major, registry.hostPort()));
        write(ws.resolve("web/package.json"), """
                {"name":"web","version":"1.0.0","scripts":{"build":"node build.js"}}
                """);
        write(ws.resolve("web/package-lock.json"), """
                {"name":"web","version":"1.0.0","lockfileVersion":3,"requires":true,
                 "packages":{"":{"name":"web","version":"1.0.0"}}}
                """);
        write(ws.resolve("web/build.js"), "require('fs').mkdirSync('dist', {recursive: true});\n");
        write(ws.resolve("web/server.js"), """
                require('http').createServer((q, s) => s.end('web')).listen(process.env.PORT || 3000);
                """);
        // No dependencies to resolve; the node pin is the host's Node.js, so nothing is downloaded.
        LockfileWriter.write(
                Lockfile.empty(JkVersion.VERSION).withNode(new NodePin(nodeVersion, null, null, Map.of())),
                ws.resolve("jk-lock.toml"));
    }

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }
}
