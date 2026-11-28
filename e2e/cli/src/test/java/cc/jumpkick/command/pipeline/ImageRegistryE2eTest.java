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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Images end to end, through the CLI and the engine, against a local registry: a JVM application,
 * a node server and a native executable across two platforms, each written to a tarball by {@code jk image} and pushed by {@code jk
 * publish}'s image lane, built on base images the registry serves. A final release run takes the
 * native executables its stage runs left in a loopback bucket.
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
        assertThat(built.files())
                .anyMatch(f -> f.endsWith("app-1.0.0-SNAPSHOT.jar") || f.contains("example/Main.class"));
        assertThat(built.config()).contains("java").contains("\"amd64\"");

        assertThat(registry.tags("acme/app")).as("jk image pushes nothing").isEmpty();
        assertThat(publishImage(ws, "app")).isZero();
        assertThat(registry.holdsManifest("acme/app", "1.0.0")).isTrue();
        OciImages.Image pushed = OciImages.fromRegistry(registry, "acme/app", "1.0.0");
        assertThat(pushed.files())
                .as("the release's own jar, the image jk image built otherwise")
                .containsExactlyInAnyOrderElementsOf(built.files().stream()
                        .map(f -> f.replace("-SNAPSHOT", ""))
                        .toList());
        String referrers = registry.tags("acme/app").stream()
                .filter(t -> t.startsWith("sha256-"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the push attached no referrers"));
        assertThat(registry.manifest("acme/app", referrers))
                .as("the SBOM and the provenance, listed under the image's referrers tag")
                .contains("\"artifactType\":\"application/vnd.cyclonedx+json\"")
                .contains("\"artifactType\":\"application/vnd.in-toto+json\"");
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

        assertThat(publishImage(ws, "web")).isZero();
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

    @Test
    void a_native_executable_image_is_an_index_across_its_platforms(@TempDir Path dir) throws Exception {
        // Neither platform is this host's, so no native-image runs: the executables are the
        // release-named files a stage run on each platform's host leaves, here stand-ins.
        boolean arm = System.getProperty("os.arch").contains("aarch64");
        List<String> platforms = List.of(arm ? "linux/amd64" : "linux/arm64", "linux/s390x");
        String base = registry.publishIndex("base/cc", "13", platforms);
        write(dir.resolve("jk.toml"), """
                group = "com.example"
                name  = "cli"
                version = "1.0.0"
                java = 25

                [application]
                main = "example.Main"

                [native]
                name = "hello"

                [image]
                kind = "native"
                base = "%s"
                registry = "%s"
                platforms = ["%s", "%s"]
                """.formatted(base, registry.hostPort(), platforms.get(0), platforms.get(1)));
        write(dir.resolve("src/main/java/example/Main.java"), """
                package example;
                public final class Main {
                    public static void main(String[] args) {}
                }
                """);
        for (String platform : platforms) {
            String release = platform.replace("linux/", "linux-")
                    .replace("amd64", "x86_64")
                    .replace("arm64", "aarch64");
            write(dir.resolve("target/hello-1.0.0-" + release), "#!/bin/sh\necho " + platform + "\n");
        }

        Path tarball = dir.resolve("out/cli.tar");
        assertThat(run("image", "-C", dir.toString(), "--tarball", tarball.toString()))
                .isZero();
        assertThat(tarball).isRegularFile();

        assertThat(run("publish", "-C", dir.toString(), "--only", "image")).isZero();
        String index = registry.manifest("cli", "1.0.0");
        assertThat(index)
                .as("the tag names an OCI index over both platforms' images")
                .contains("\"application/vnd.oci.image.index.v1+json\"")
                .contains("\"architecture\":\"" + platforms.get(0).substring(6) + "\"")
                .contains("\"architecture\":\"s390x\"");
    }

    @Test
    void a_final_release_run_fetches_the_staged_executables_into_its_image(@TempDir Path dir) throws Exception {
        // No platform is this host's. The other linux one's executable is in the release, uploaded by
        // its stage run (a stand-in in a loopback bucket), not in target/; s390x's is in target/.
        boolean arm = System.getProperty("os.arch").contains("aarch64");
        List<String> platforms = List.of(arm ? "linux/amd64" : "linux/arm64", "linux/s390x");
        String linux = arm ? "linux-x86_64" : "linux-aarch64";
        List<String> releases = List.of(linux, "windows-x86_64");
        String base = registry.publishIndex("base/cc", "13", platforms);
        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        String staged = "#!/bin/sh\necho " + linux + "\n";
        objects.put("/releases/cli/1.0.0/hello-1.0.0-" + linux, staged.getBytes(StandardCharsets.UTF_8));
        objects.put("/releases/cli/1.0.0/hello-1.0.0-windows-x86_64.exe", "MZ".getBytes(StandardCharsets.UTF_8));
        HttpServer bucket = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bucket.createContext("/", exchange -> bucket(exchange, objects));
        bucket.start();
        Map<String, String> env = Map.of(
                "AWS_ENDPOINT_URL", "http://127.0.0.1:" + bucket.getAddress().getPort(),
                "AWS_ACCESS_KEY_ID", "test-key",
                "AWS_SECRET_ACCESS_KEY", "test-secret",
                "AWS_REGION", "us-east-1");
        env.forEach((k, v) -> System.setProperty("jk.env." + k, v));
        try {
            write(dir.resolve("jk.toml"), """
                    group = "com.example"
                    name  = "cli"
                    version = "1.0.0"
                    java = 25

                    [application]
                    main = "example.Main"

                    [native]
                    name = "hello"

                    [image]
                    kind = "native"
                    base = "%s"
                    registry = "%s"
                    platforms = ["%s", "%s"]

                    [publish]
                    release = "s3://releases/cli/"
                    sign    = false
                    sbom    = false

                    [release]
                    platforms = ["%s", "%s"]
                    """.formatted(
                    base, registry.hostPort(), platforms.get(0), platforms.get(1), releases.get(0), releases.get(1)));
            write(dir.resolve("src/main/java/example/Main.java"), """
                    package example;
                    public final class Main {
                        public static void main(String[] args) {}
                    }
                    """);
            write(dir.resolve("target/hello-1.0.0-linux-s390x"), "#!/bin/sh\necho s390x\n");

            assertThat(run("publish", "-C", dir.toString(), "--only", "release,image"))
                    .isZero();

            assertThat(Files.readString(dir.resolve("target/staged/hello-1.0.0-" + linux)))
                    .isEqualTo(staged);
            assertThat(dir.resolve("target/staged/hello-1.0.0-linux-s390x"))
                    .as("an executable in target/ is not fetched")
                    .doesNotExist();
            assertThat(objects).containsKey("/releases/cli/latest.json");
            String index = registry.manifest("cli", "1.0.0");
            assertThat(index)
                    .as("the tag names an OCI index over the fetched and the local executable's images")
                    .contains("\"application/vnd.oci.image.index.v1+json\"")
                    .contains("\"architecture\":\"" + platforms.get(0).substring(6) + "\"")
                    .contains("\"architecture\":\"s390x\"");
        } finally {
            bucket.stop(0);
            env.keySet().forEach(k -> System.clearProperty("jk.env." + k));
        }
    }

    /** A bucket as a release reads and writes it: GET, and PUT create-only or by compare-and-swap. */
    private static void bucket(HttpExchange exchange, Map<String, byte[]> objects) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())) {
                byte[] body = objects.get(path);
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("ETag", etag(body));
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            String match = exchange.getRequestHeaders().getFirst("If-Match");
            synchronized (objects) {
                byte[] previous = objects.get(path);
                boolean ok = match == null ? previous == null : previous != null && match.equals(etag(previous));
                if (!ok) {
                    exchange.sendResponseHeaders(412, -1);
                    return;
                }
                objects.put(path, body);
                exchange.sendResponseHeaders(200, -1);
            }
        }
    }

    private static String etag(byte[] body) {
        return "\"" + Integer.toHexString(Arrays.hashCode(body)) + "\"";
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

    /** {@code jk publish --only image} from {@code module}'s directory: its image, pushed to the root's registry. */
    private int publishImage(Path ws, String module) {
        return run("publish", "-C", ws.resolve(module).toString(), "--only", "image");
    }

    /**
     * {@code app}: a JVM application; {@code web}: a node server needing no install. The root's
     * {@code [image] registry} reaches both.
     */
    private void workspace(Path ws) throws IOException {
        write(ws.resolve("jk.toml"), """
                group = "com.example"
                name  = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["app", "web"]

                [image]
                registry = "%s"
                """.formatted(registry.hostPort()));
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
