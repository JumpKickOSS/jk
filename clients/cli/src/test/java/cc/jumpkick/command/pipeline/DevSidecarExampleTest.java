// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.testing.RepoRoot;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manual's {@code examples/vite-sidecar} under {@code jk dev}: the JVM API and the sidecar come
 * up, the front door answers, every sidecar line is an event with its source, and Ctrl-C takes both
 * processes down. Vite itself needs {@code npm install}, which this tier does not have, so the
 * sidecar's command is swapped for a single-file Java HTTP server that plays the dev server's part:
 * it listens on the example's port, prints a line and a carriage-return progress bar, and answers
 * {@code /}. Everything else — the manifest, the sources, the probe, the front door — is the
 * example as documented, and {@code jk dev} is spawned as a real process so the SIGINT is a real one.
 */
@Tag("integration")
@DisabledOnOs(OS.WINDOWS)
class DevSidecarExampleTest {

    private static final Path EXAMPLE =
            RepoRoot.find(DevSidecarExampleTest.class).resolve("docs/user/examples/vite-sidecar");

    /** A cold engine, a compile, a JVM start and a probe all fit; a hang does not. */
    private static final long READY_WAIT_SECONDS = 180;

    @Test
    void the_example_serves_its_front_door_and_its_api_and_ctrl_c_stops_both(@TempDir Path dir) throws Exception {
        int webPort = freePort();
        int apiPort = freePort();
        Path project = stage(dir.resolve("vite-sidecar"), webPort);
        Path out = dir.resolve("stdout.jsonl");
        Path err = dir.resolve("stderr.log");
        Process jk = spawnDev(project, out, err, apiPort);
        try {
            Optional<String> ready = awaitLine(out, l -> "sidecar-ready".equals(Jsonl.str(l, "type")), jk);
            assertThat(ready)
                    .as("no sidecar-ready within the wait\nstdout:\n%s\nstderr:\n%s", read(out), read(err))
                    .isPresent();
            assertThat(Jsonl.str(ready.get(), "name")).isEqualTo("web");
            assertThat(Jsonl.str(ready.get(), "url")).isEqualTo("http://localhost:" + webPort);
            assertThat(Jsonl.bool(ready.get(), "frontDoor", false)).isTrue();

            List<String> events = lines(out);
            String started = events.stream()
                    .filter(l -> "sidecar-started".equals(Jsonl.str(l, "type")))
                    .findFirst()
                    .orElseThrow();
            long webPid = Jsonl.longValue(started, "pid", -1);
            assertThat(webPid).isPositive();
            assertThat(events)
                    .as("every sidecar line is an event with its source")
                    .anySatisfy(l -> {
                        assertThat(Jsonl.str(l, "type")).isEqualTo("sidecar-output");
                        assertThat(Jsonl.str(l, "name")).isEqualTo("web");
                        assertThat(Jsonl.str(l, "stream")).isEqualTo("stdout");
                        assertThat(Jsonl.str(l, "line")).isEqualTo("stub dev server on http://localhost:" + webPort);
                    });
            assertThat(events.stream().map(l -> Jsonl.str(l, "line")).toList())
                    .as("a carriage-return progress bar shows its final state only")
                    .contains("bundling 100%")
                    .doesNotContain("bundling 10%");
            Optional<String> appStarted = awaitLine(out, l -> "app-started".equals(Jsonl.str(l, "type")), jk);
            assertThat(appStarted).isPresent();
            long appPid = Jsonl.longValue(appStarted.get(), "pid", -1);
            Optional<String> listening = awaitLine(
                    out,
                    l -> "app-output".equals(Jsonl.str(l, "type"))
                            && String.valueOf(Jsonl.str(l, "line")).startsWith("listening on"),
                    jk);
            assertThat(listening)
                    .as("the app's own stdout rides the stream as app-output\n%s", read(out))
                    .isPresent();

            assertThat(awaitText(err, "ready · http://localhost:" + webPort, jk))
                    .as("the one ready line names the front door\n%s", read(err))
                    .isTrue();
            assertThat(get("http://127.0.0.1:" + webPort + "/")).contains("stub dev server");
            assertThat(get("http://127.0.0.1:" + apiPort + "/api/hello")).contains("hello from the JVM");

            interrupt(jk);
            assertThat(jk.waitFor(30, TimeUnit.SECONDS))
                    .as("Ctrl-C did not end the session\n%s", read(err))
                    .isTrue();
            assertThat(jk.exitValue()).isEqualTo(Exit.INTERRUPTED);
            awaitGone(webPid);
            awaitGone(appPid);
            assertThat(alive(webPid)).as("the sidecar survived the session").isFalse();
            assertThat(alive(appPid)).as("the app survived the session").isFalse();
        } finally {
            // A failure path must not leave the session's children on the machine: SIGKILL on jk
            // alone would orphan the app and the sidecar, so end the session the way a user does
            // first, and reap whatever the events say was spawned.
            if (jk.isAlive()) {
                interrupt(jk);
                jk.waitFor(15, TimeUnit.SECONDS);
            }
            jk.destroyForcibly();
            for (String l : lines(out)) {
                String type = Jsonl.str(l, "type");
                if ("sidecar-started".equals(type) || "app-started".equals(type)) {
                    ProcessHandle.of(Jsonl.longValue(l, "pid", -1)).ifPresent(ProcessHandle::destroyForcibly);
                }
            }
            // Into the test report, so a failure on a runner can be read without the temp dir.
            System.out.println("jk dev stderr:\n" + read(err));
            System.out.println("jk dev lifecycle events:\n"
                    + String.join(
                            "\n",
                            lines(out).stream()
                                    .filter(l -> !"sidecar-output".equals(Jsonl.str(l, "type"))
                                            && !"app-output".equals(Jsonl.str(l, "type")))
                                    .toList()));
        }
    }

    /**
     * The example as committed, with two edits a reader can verify: the sidecar runs the stub on a
     * free port instead of {@code npm run dev} on 5173, and the test-dependency table is dropped so
     * the copy builds with no lockfile and no repository.
     */
    private static Path stage(Path project, int webPort) throws IOException {
        Files.createDirectories(project.resolve("web"));
        copyTree(EXAMPLE.resolve("src/main"), project.resolve("src/main"));
        String manifest = Files.readString(EXAMPLE.resolve("jk.toml"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String sidecar = "web = { command = [\"" + java + "\", \"StubDevServer.java\", \"" + webPort
                + "\"], cwd = \"web\", ready = \"http://localhost:" + webPort + "\", front-door = true }";
        String edited = manifest.lines()
                .map(l -> l.startsWith("web = {") ? sidecar : l)
                .filter(l -> !l.startsWith("[test-dependencies]") && !l.startsWith("junit-jupiter"))
                .reduce((a, b) -> a + "\n" + b)
                .orElseThrow();
        assertThat(edited).contains("[dev.sidecars]").contains("main = \"demo.Api\"");
        Files.writeString(project.resolve("jk.toml"), edited + "\n");
        Files.writeString(project.resolve("web/StubDevServer.java"), """
                import com.sun.net.httpserver.HttpServer;
                import java.io.OutputStream;
                import java.net.InetSocketAddress;

                public class StubDevServer {
                    public static void main(String[] args) throws Exception {
                        int port = Integer.parseInt(args[0]);
                        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
                        server.createContext("/", exchange -> {
                            byte[] body = "stub dev server".getBytes();
                            exchange.sendResponseHeaders(200, body.length);
                            try (OutputStream out = exchange.getResponseBody()) {
                                out.write(body);
                            }
                        });
                        System.out.print("bundling 10%\\rbundling 55%\\rbundling 100%\\n");
                        System.out.println("stub dev server on http://localhost:" + port);
                        server.start();
                        Thread.sleep(Long.MAX_VALUE);
                    }
                }
                """);
        return project;
    }

    private static Process spawnDev(Path project, Path out, Path err, int apiPort) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "cc.jumpkick.cli.Jk",
                "dev",
                "--output",
                "json",
                "--",
                Integer.toString(apiPort));
        pb.directory(project.toFile());
        pb.redirectOutput(out.toFile());
        pb.redirectError(err.toFile());
        pb.redirectInput(new File("/dev/null"));
        return pb.start();
    }

    private static void interrupt(Process jk) throws Exception {
        int killed = new ProcessBuilder("kill", "-INT", Long.toString(jk.pid()))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor();
        assertThat(killed).as("could not deliver SIGINT").isZero();
    }

    private static Optional<String> awaitLine(Path out, Predicate<String> match, Process jk) throws Exception {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(READY_WAIT_SECONDS);
        Optional<String> result = Optional.empty();
        while (System.nanoTime() < deadline) {
            Optional<String> hit = lines(out).stream().filter(match).findFirst();
            if (hit.isPresent()) {
                result = hit;
                break;
            }
            if (!jk.isAlive()) break;
            Thread.sleep(200);
        }
        System.out.println("awaitLine → "
                + result.map(l -> l.substring(0, Math.min(80, l.length()))).orElse("<none>")
                + " after " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms; jk alive="
                + jk.isAlive());
        return result;
    }

    private static boolean awaitText(Path file, String text, Process jk) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (read(file).contains(text)) return true;
            if (!jk.isAlive()) return false;
            Thread.sleep(200);
        }
        return false;
    }

    private static void awaitGone(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (alive(pid) && System.nanoTime() < deadline) Thread.sleep(100);
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static List<String> lines(Path out) throws IOException {
        return Files.exists(out) ? Files.readAllLines(out) : List.of();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "<none>";
        }
    }

    private static String get(String url) throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(url).isEqualTo(200);
            return response.body();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }
}
