// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cli.engine.EngineSpawn;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.node.DiscoveredNode;
import cc.jumpkick.node.NodeDiscovery;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk dev} runs the whole stack: a JVM app with the dev server of the node module it depends
 * on and no {@code [dev.sidecars]} written, a node module alone, and a workspace root's every
 * runnable member at once — each node dev server on the host's Node.js the lock pins. {@code jk
 * dev} is spawned as a real process so the SIGINT that ends it is a real one.
 */
@Tag("integration")
@DisabledOnOs(OS.WINDOWS)
class DevStackE2eTest {

    private static final long WAIT_SECONDS = 180;

    @Test
    void an_app_runs_beside_the_dev_server_of_the_node_module_it_depends_on(@TempDir Path dir) throws Exception {
        String node = hostNode();
        Path ws = dir.resolve("ws");
        int webPort = freePort();
        int apiPort = freePort();
        workspace(ws, node, List.of("api", "web"));
        jvmApp(ws.resolve("api"), "api", apiPort, "web = { workspace = true }");
        nodeModule(ws.resolve("web"), "web", node, webPort);

        Session session = Session.spawn(ws.resolve("api"), dir);
        try {
            Optional<String> ready = awaitLine(session, typed("dev-ready"));
            assertThat(ready).as("no dev-ready\n%s", session.err()).isPresent();
            assertThat(Jsonl.str(ready.get(), "url")).isEqualTo("http://localhost:" + webPort);
            List<String> events = session.lines();
            assertThat(events.stream().filter(typed("sidecar-started")).map(l -> Jsonl.str(l, "name")))
                    .containsExactly("web");
            assertThat(events.stream().filter(typed("app-output")).map(l -> Jsonl.str(l, "line")))
                    .contains("JK_DEV_WEB_URL=http://localhost:" + webPort);

            long firstApp =
                    pid(events.stream().filter(typed("app-started")).findFirst().orElseThrow());
            long web = pid(
                    events.stream().filter(typed("sidecar-started")).findFirst().orElseThrow());
            Files.writeString(ws.resolve("api/src/main/java/demo/Main.java"), "\n// edit\n", StandardOpenOption.APPEND);
            assertThat(awaitCount(session, typed("app-started"), 2))
                    .as("the app restarts\n%s", session.err())
                    .isTrue();
            assertThat(session.lines().stream().filter(typed("sidecar-started")).count())
                    .as("the dev server outlives an app restart")
                    .isEqualTo(1);
            assertThat(alive(web)).isTrue();

            interrupt(session.jk);
            assertThat(session.jk.waitFor(30, TimeUnit.SECONDS)).isTrue();
            awaitGone(web);
            awaitGone(firstApp);
            for (String l : session.lines()) {
                if (typed("app-started").or(typed("sidecar-started")).test(l)) {
                    awaitGone(pid(l));
                    assertThat(alive(pid(l))).as("left running: %s", l).isFalse();
                }
            }
        } finally {
            session.reap();
        }
    }

    @Test
    void no_sidecars_runs_the_app_alone(@TempDir Path dir) throws Exception {
        String node = hostNode();
        Path ws = dir.resolve("ws");
        int apiPort = freePort();
        workspace(ws, node, List.of("api", "web"));
        jvmApp(ws.resolve("api"), "api", apiPort, "web = { workspace = true }");
        nodeModule(ws.resolve("web"), "web", node, freePort());

        Session session = Session.spawn(ws.resolve("api"), dir, "--no-sidecars");
        try {
            assertThat(awaitLine(session, typed("dev-ready")))
                    .as("no dev-ready\n%s", session.err())
                    .isPresent();
            assertThat(session.lines().stream().filter(typed("sidecar-started")))
                    .isEmpty();
        } finally {
            session.reap();
        }
    }

    @Test
    void a_node_module_alone_runs_its_dev_server_in_the_app_s_place(@TempDir Path dir) throws Exception {
        String node = hostNode();
        Path ws = dir.resolve("ws");
        int webPort = freePort();
        workspace(ws, node, List.of("web"));
        nodeModule(ws.resolve("web"), "web", node, webPort);

        Session session = Session.spawn(ws.resolve("web"), dir);
        try {
            Optional<String> ready = awaitLine(session, typed("dev-ready"));
            assertThat(ready).as("no dev-ready\n%s", session.err()).isPresent();
            assertThat(session.lines().stream().filter(typed("app-output")).map(l -> Jsonl.str(l, "line")))
                    .contains("dev on " + webPort);
            assertThat(session.lines().stream().filter(typed("sidecar-started")))
                    .isEmpty();
        } finally {
            session.reap();
        }
    }

    @Test
    void a_workspace_root_runs_every_member_and_m_narrows_it(@TempDir Path dir) throws Exception {
        String node = hostNode();
        Path ws = dir.resolve("ws");
        int adminPort = freePort();
        int shopPort = freePort();
        workspace(ws, node, List.of("api-a", "api-b", "admin", "shop"));
        jvmApp(ws.resolve("api-a"), "api-a", freePort(), "admin = { workspace = true }");
        jvmApp(ws.resolve("api-b"), "api-b", freePort(), "");
        nodeModule(ws.resolve("admin"), "admin", node, adminPort);
        nodeModule(ws.resolve("shop"), "shop", node, shopPort);

        Session all = Session.spawn(ws, dir.resolve("all"));
        try {
            Optional<String> ready = awaitLine(all, typed("dev-ready"));
            assertThat(ready).as("no dev-ready\n%s", all.err()).isPresent();
            assertThat(Jsonl.strArray(ready.get(), "urls"))
                    .containsExactlyInAnyOrder("http://localhost:" + adminPort, "http://localhost:" + shopPort);
            List<String> events = all.lines();
            assertThat(events.stream().filter(typed("app-started")).map(l -> Jsonl.str(l, "module")))
                    .containsExactlyInAnyOrder("api-a", "api-b");
            assertThat(events.stream().filter(typed("sidecar-started")).map(l -> Jsonl.str(l, "name")))
                    .containsExactlyInAnyOrder("admin", "shop");
            assertThat(events.stream().filter(typed("dev-ready")).count()).isEqualTo(1);
        } finally {
            all.reap();
        }

        Session narrowed = Session.spawn(ws, dir.resolve("narrowed"), "-m", "api-a");
        try {
            assertThat(awaitLine(narrowed, typed("dev-ready")))
                    .as("no dev-ready\n%s", narrowed.err())
                    .isPresent();
            List<String> events = narrowed.lines();
            assertThat(events.stream().filter(typed("app-started")).map(l -> Jsonl.str(l, "module")))
                    .containsExactly("api-a");
            assertThat(events.stream().filter(typed("sidecar-started")).map(l -> Jsonl.str(l, "name")))
                    .as("api-a and the node module it depends on")
                    .containsExactly("admin");
        } finally {
            narrowed.reap();
        }
    }

    // --- fixture --------------------------------------------------------------------------------

    private static String hostNode() {
        List<DiscoveredNode> found = new NodeDiscovery().discover();
        assumeTrue(!found.isEmpty(), "a Node.js on this host");
        return found.get(0).version();
    }

    private static void workspace(Path ws, String node, List<String> modules) throws IOException {
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
        LockfileWriter.write(
                Lockfile.empty(JkVersion.VERSION).withNode(new NodePin(node, null, null, Map.of())),
                ws.resolve("jk-lock.toml"));
    }

    /** A JVM app answering on {@code port}, printing what {@code jk dev} handed it about the web server. */
    private static void jvmApp(Path dir, String name, int port, String dependency) throws IOException {
        write(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "%s"
                version = "1.0.0"
                java = 25

                [application]
                main = "demo.Main"

                [dev]
                ready = "http://localhost:%d/"
                %s
                """.formatted(
                        name, port, dependency.isEmpty() ? "" : "\n[dependencies]\n" + dependency));
        write(dir.resolve("src/main/java/demo/Main.java"), """
                package demo;

                import com.sun.net.httpserver.HttpServer;
                import java.net.InetSocketAddress;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        System.out.println("JK_DEV_WEB_URL=" + System.getenv("JK_DEV_WEB_URL"));
                        HttpServer server = HttpServer.create(new InetSocketAddress(%d), 0);
                        server.createContext("/", exchange -> {
                            byte[] body = "api".getBytes();
                            exchange.sendResponseHeaders(200, body.length);
                            exchange.getResponseBody().write(body);
                            exchange.close();
                        });
                        server.start();
                    }
                }
                """.formatted(port));
    }

    /** A node module whose build writes {@code dist/} and whose dev script serves on {@code port}. */
    private static void nodeModule(Path dir, String name, String node, int port) throws IOException {
        write(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "%s"
                version = "1.0.0"

                [node]
                version = %s
                dev-port = %d
                """.formatted(name, node.substring(0, node.indexOf('.')), port));
        write(dir.resolve("package.json"), """
                {"name":"%s","version":"1.0.0","scripts":{"build":"node build.js","dev":"node dev.js %d"}}
                """.formatted(name, port));
        write(dir.resolve("package-lock.json"), """
                {"name":"%s","version":"1.0.0","lockfileVersion":3,"requires":true,
                 "packages":{"":{"name":"%s","version":"1.0.0"}}}
                """.formatted(name, name));
        write(dir.resolve("build.js"), """
                const fs = require('fs');
                fs.mkdirSync('dist', {recursive: true});
                fs.writeFileSync('dist/index.html', '<h1>%s</h1>');
                """.formatted(name));
        write(dir.resolve("dev.js"), """
                const http = require('http');
                const port = Number(process.argv[2]);
                http.createServer((req, res) => res.end('%s')).listen(port, () => console.log('dev on ' + port));
                """.formatted(name));
    }

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    // --- session --------------------------------------------------------------------------------

    /** One spawned {@code jk dev --output json} and its two output files. */
    private record Session(Process jk, Path out, Path errFile) {

        static Session spawn(Path project, Path dir, String... options) throws IOException {
            Files.createDirectories(dir);
            Path out = dir.resolve("stdout.jsonl");
            Path err = dir.resolve("stderr.log");
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.addAll(EngineSpawn.forwardedJvmArgs());
            command.addAll(List.of(
                    "-cp", System.getProperty("java.class.path"), "cc.jumpkick.cli.Jk", "dev", "--output", "json"));
            command.addAll(List.of(options));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(project.toFile());
            pb.redirectOutput(out.toFile());
            pb.redirectError(err.toFile());
            pb.redirectInput(new File("/dev/null"));
            return new Session(pb.start(), out, err);
        }

        List<String> lines() throws IOException {
            return Files.exists(out) ? Files.readAllLines(out) : List.of();
        }

        String err() {
            try {
                return Files.readString(errFile);
            } catch (IOException e) {
                return "<none>";
            }
        }

        /** End the session as a user does, then reap whatever its events say was started. */
        void reap() throws Exception {
            if (jk.isAlive()) {
                interrupt(jk);
                jk.waitFor(15, TimeUnit.SECONDS);
            }
            jk.destroyForcibly();
            for (String l : lines()) {
                if (typed("sidecar-started").or(typed("app-started")).test(l)) {
                    ProcessHandle.of(pid(l)).ifPresent(ProcessHandle::destroyForcibly);
                }
            }
            System.out.println("jk dev stderr:\n" + err());
        }
    }

    private static Optional<String> awaitLine(Session s, Predicate<String> match) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            Optional<String> hit = s.lines().stream().filter(match).findFirst();
            if (hit.isPresent()) return hit;
            if (!s.jk.isAlive()) return s.lines().stream().filter(match).findFirst();
            Thread.sleep(200);
        }
        return Optional.empty();
    }

    private static boolean awaitCount(Session s, Predicate<String> match, int n) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (s.lines().stream().filter(match).count() >= n) return true;
            if (!s.jk.isAlive()) return false;
            Thread.sleep(200);
        }
        return false;
    }

    private static void interrupt(Process jk) throws Exception {
        new ProcessBuilder("kill", "-INT", Long.toString(jk.pid()))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor();
    }

    private static Predicate<String> typed(String type) {
        return l -> type.equals(Jsonl.str(l, "type"));
    }

    private static long pid(String event) {
        return Jsonl.longValue(event, "pid", -1);
    }

    private static void awaitGone(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (alive(pid) && System.nanoTime() < deadline) Thread.sleep(100);
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
