// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.node.DiscoveredNode;
import cc.jumpkick.node.NodeDiscovery;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A node module's build output reaches what depends on it: a JVM module's classpath through the
 * node module's resource jar, a war's root under {@code webapp-root}; and a node server runs with
 * {@code jk run}. On the host's Node.js, with packages that need no install.
 */
@Tag("integration")
@DisabledOnOs(OS.WINDOWS)
class NodePackagingE2eTest {

    private String version = "";

    @BeforeEach
    void hostNode() {
        List<DiscoveredNode> found = new NodeDiscovery().discover();
        assumeTrue(!found.isEmpty(), "a Node.js on this host");
        version = found.get(0).version();
    }

    @Test
    void a_jvm_module_that_depends_on_a_node_module_has_its_output_under_static(@TempDir Path ws) throws Exception {
        workspace(ws, "web", "app");
        nodeModule(ws.resolve("web"), "web", "");
        write(ws.resolve("app/jk.toml"), """
                group = "com.example"
                name = "app"
                version = "1.0.0"
                java = 25

                [application]
                main = "app.Main"

                [dependencies]
                web = { workspace = true }
                """);
        write(ws.resolve("app/src/main/java/app/Main.java"), """
                package app;
                import java.nio.file.*;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        var in = Main.class.getResourceAsStream("/static/index.html");
                        // target/: beside the jar it runs from, or above target/classes/app/.
                        String url = Main.class.getResource("Main.class").toString();
                        boolean jar = url.startsWith("jar:");
                        Path self = Path.of(java.net.URI.create(jar ? url.substring(4, url.indexOf("!/")) : url));
                        Path target = jar ? self.getParent() : self.getParent().getParent().getParent();
                        Files.writeString(target.resolve("seen.txt"),
                                in == null ? "missing" : new String(in.readAllBytes()));
                    }
                }
                """);
        lock(ws);

        assertThat(build(ws)).isZero();
        Path jar = ws.resolve("web/target/web-1.0.0.jar");
        assertThat(entries(jar)).contains("static/index.html");

        Path seen = ws.resolve("app/target/seen.txt");
        assertThat(run("run", "-C", ws.resolve("app").toString(), "--cache-dir", cache(ws)))
                .isZero();
        assertThat(seen).as("the app's runtime classpath").hasContent("<h1>web</h1>");

        assertThat(build(ws)).isZero();
        assertThat(entries(jar)).as("a second build restores the same jar").contains("static/index.html");
    }

    @Test
    void classpath_root_moves_the_output_and_nothing_is_packaged_without_one(@TempDir Path ws) throws Exception {
        workspace(ws, "web", "site");
        nodeModule(ws.resolve("web"), "web", "classpath-root = \"public\"\n");
        nodeModule(ws.resolve("site"), "site", "");
        lock(ws);

        assertThat(build(ws)).isZero();
        assertThat(entries(ws.resolve("web/target/web-1.0.0.jar"))).contains("public/index.html");
        assertThat(ws.resolve("site/target/site-1.0.0.jar"))
                .as("nothing depends on site and it names no classpath root")
                .doesNotExist();
        assertThat(ws.resolve("site/dist/index.html")).exists();
    }

    @Test
    void a_war_carries_a_node_sibling_under_its_webapp_root(@TempDir Path ws) throws Exception {
        workspace(ws, "web", "shop");
        nodeModule(ws.resolve("web"), "web", "webapp-root = \"ui\"\n");
        write(ws.resolve("shop/jk.toml"), """
                group = "com.example"
                name = "shop"
                version = "1.0.0"
                java = 25

                [war]

                [dependencies]
                web = { workspace = true }
                """);
        write(ws.resolve("shop/src/main/java/shop/Home.java"), "package shop; public class Home {}");
        lock(ws);

        assertThat(build(ws)).isZero();
        Path exploded = ws.resolve("shop/target/shop-1.0.0");
        assertThat(exploded.resolve("ui/index.html")).hasContent("<h1>web</h1>");
        assertThat(exploded.resolve("WEB-INF/lib/web-1.0.0.jar"))
                .as("web content, not a library")
                .doesNotExist();
        assertThat(entries(ws.resolve("shop/target/shop-1.0.0.war")))
                .contains("ui/index.html", "WEB-INF/classes/shop/Home.class");
    }

    @Test
    void jk_run_starts_a_node_server_and_refuses_a_static_module(@TempDir Path dir) throws Exception {
        Path server = dir.resolve("server");
        nodeModule(server, "server", "start = \"node server.js\"\n");
        write(server.resolve("server.js"), """
                require('fs').writeFileSync('started.txt',
                    process.env.PORT + ' ' + process.env.NODE_ENV + ' ' + process.version);
                """);
        write(server.resolve(".env"), "PORT=4321\n");
        lock(server);
        var captured = new ByteArrayOutputStream();
        var prevOut = System.out;
        var prevErr = System.err;
        var combined = new PrintStream(captured, true, StandardCharsets.UTF_8);
        System.setOut(combined);
        System.setErr(combined);
        int started;
        try {
            started = run("run", "-C", server.toString(), "--cache-dir", cache(dir));
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }
        assertThat(started).isZero();
        String banner = TestAnsi.strip(captured.toString(StandardCharsets.UTF_8));
        assertThat(banner)
                .as("the banner shows the start command as declared: %s", banner)
                .contains("Executing `node server.js`")
                .doesNotContain("node node");
        assertThat(server.resolve("started.txt"))
                .as("from the node directory, with .env's PORT, production and the locked Node.js")
                .hasContent("4321 production v" + version);

        Path site = dir.resolve("site");
        nodeModule(site, "site", "");
        lock(site);
        int[] exit = new int[1];
        Capture.Streams out =
                Capture.both(() -> exit[0] = run("run", "-C", site.toString(), "--cache-dir", cache(dir)));
        assertThat(exit[0]).isNotZero();
        assertThat(out.err() + out.out()).contains("has nothing to start");
    }

    private void workspace(Path ws, String... modules) throws IOException {
        StringBuilder list = new StringBuilder();
        for (String m : modules)
            list.append(list.isEmpty() ? "" : ", ").append('"').append(m).append('"');
        write(ws.resolve("jk.toml"), """
                group = "com.example"
                name  = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = [%s]
                """.formatted(list));
    }

    /** A node module whose build writes {@code dist/index.html} with plain node and needs no install. */
    private void nodeModule(Path dir, String name, String extra) throws IOException {
        String major = version.substring(0, version.indexOf('.'));
        String head = "group = \"com.example\"\nname = \"" + name + "\"\nversion = \"1.0.0\"\n";
        // A manifest with a [node] table carries the version in it.
        write(
                dir.resolve("jk.toml"),
                extra.isEmpty()
                        ? head + "node = " + major + "\n"
                        : head + "\n[node]\nversion = " + major + "\n" + extra);
        write(
                dir.resolve("package.json"),
                "{\"name\":\"" + name + "\",\"version\":\"1.0.0\",\"scripts\":{\"build\":\"node build.js\"}}");
        write(dir.resolve("package-lock.json"), """
                {"name":"%s","version":"1.0.0","lockfileVersion":3,"requires":true,
                 "packages":{"":{"name":"%s","version":"1.0.0"}}}
                """.formatted(name, name));
        write(dir.resolve("build.js"), """
                const fs = require('fs');
                fs.mkdirSync('dist', {recursive: true});
                fs.writeFileSync('dist/index.html', '<h1>web</h1>');
                """);
    }

    private void lock(Path dir) throws IOException {
        LockfileWriter.write(
                Lockfile.empty(JkVersion.VERSION).withNode(new NodePin(version, null, null, Map.of())),
                dir.resolve("jk-lock.toml"));
    }

    private static int build(Path ws) {
        return run("build", "-C", ws.toString(), "--cache-dir", cache(ws));
    }

    private static String cache(Path dir) {
        return dir.resolve("cache").toString();
    }

    private static List<String> entries(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            return Collections.list(file.entries()).stream()
                    .map(ZipEntry::getName)
                    .toList();
        }
    }

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }
}
