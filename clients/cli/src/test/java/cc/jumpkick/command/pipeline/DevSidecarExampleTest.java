// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
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
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
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
 * up, {@code dev-ready} waits for the JVM's own {@code [dev] ready} probe so the API answers the
 * moment it is announced, the front door answers, every sidecar line is an event with its source,
 * a source change restarts the JVM and leaves the sidecar alone, and Ctrl-C takes both processes
 * down. Vite itself
 * needs {@code npm install}, which this tier does not have, so the sidecar's command is swapped for
 * a single-file Java HTTP server that plays the dev server's part: it listens on the example's
 * port, prints a line and a carriage-return progress bar, and answers {@code /}. Everything else —
 * the manifest, the sources, the probe, the front door — is the example as documented, and {@code
 * jk dev} is spawned as a real process so the SIGINT is a real one.
 */
@Tag("integration")
@DisabledOnOs(OS.WINDOWS)
class DevSidecarExampleTest {

    private static final Path EXAMPLE =
            RepoRoot.find(DevSidecarExampleTest.class).resolve("docs/user/examples/vite-sidecar");

    /** A cold engine, a compile, a JVM start and a probe all fit; a hang does not. */
    private static final long READY_WAIT_SECONDS = 180;

    private static final String READY_LINE = "ready · ";

    /** The events a dev session adds to the workspace envelope; the ones a transcript must replay. */
    private static final Predicate<String> DEV_EVENT = l -> {
        String type = Jsonl.str(l, "type");
        return type != null && (type.startsWith("sidecar-") || type.startsWith("app-") || type.equals("dev-ready"));
    };

    @Test
    void the_example_serves_its_front_door_and_its_api_and_ctrl_c_stops_both(@TempDir Path dir) throws Exception {
        int webPort = freePort();
        int apiPort = freePort();
        Path project = stage(dir.resolve("vite-sidecar"), webPort, apiPort);
        Session session = Session.spawn(project, dir, apiPort);
        Process jk = session.jk;
        Path out = session.out;
        Path err = session.err;
        try {
            Optional<String> ready = awaitLine(out, typed("sidecar-ready"), jk);
            assertThat(ready)
                    .as("no sidecar-ready within the wait\nstdout:\n%s\nstderr:\n%s", read(out), read(err))
                    .isPresent();
            assertThat(Jsonl.str(ready.get(), "name")).isEqualTo("web");
            assertThat(Jsonl.str(ready.get(), "url")).isEqualTo("http://localhost:" + webPort);
            assertThat(Jsonl.bool(ready.get(), "frontDoor", false)).isTrue();

            List<String> events = lines(out);
            String started =
                    events.stream().filter(typed("sidecar-started")).findFirst().orElseThrow();
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
            Optional<String> appStarted = awaitLine(out, typed("app-started"), jk);
            assertThat(appStarted).isPresent();
            long appPid = Jsonl.longValue(appStarted.get(), "pid", -1);
            assertThat(awaitCount(out, listening(), 1, jk))
                    .as("the app's own stdout rides the stream as app-output\n%s", read(out))
                    .isTrue();

            Optional<String> devReady = awaitLine(out, typed("dev-ready"), jk);
            assertThat(devReady)
                    .as("the ready line has its event\n%s", read(out))
                    .isPresent();
            // The front door is fetched the instant dev-ready shows, no wait for the JVM's own
            // "listening" line: [dev] ready held the announcement until /api/hello answered.
            assertThat(get("http://127.0.0.1:" + apiPort + "/api/hello")).contains("hello from the JVM");
            assertThat(Jsonl.str(devReady.get(), "url")).isEqualTo("http://localhost:" + webPort);
            assertThat(Jsonl.str(devReady.get(), "app")).contains("demo.Api");
            assertThat(awaitText(err, READY_LINE + "http://localhost:" + webPort, jk))
                    .as("the one ready line names the front door\n%s", read(err))
                    .isTrue();
            assertThat(get("http://127.0.0.1:" + webPort + "/")).contains("stub dev server");

            // A source change restarts the JVM. The sidecar is the front door and survives the
            // restart, so its address is still true and the ready line is not said again.
            touchSource(project);
            Optional<String> restarted = awaitLine(out, typed("app-started").and(l -> pid(l) != appPid), jk);
            assertThat(restarted)
                    .as("no restart after a source change\n%s", read(err))
                    .isPresent();
            assertThat(awaitCount(out, listening(), 2, jk)).isTrue();
            assertThat(get("http://127.0.0.1:" + apiPort + "/api/hello")).contains("hello from the JVM");
            assertThat(lines(out).stream().filter(typed("app-exited")).map(DevSidecarExampleTest::pid))
                    .contains(appPid);
            assertThat(lines(out).stream().filter(typed("dev-ready")).count())
                    .as("a sidecar front door is announced once\n%s", read(out))
                    .isEqualTo(1);
            assertThat(count(read(err), READY_LINE)).isEqualTo(1);
            assertThat(lines(out).stream().filter(typed("sidecar-started")).count())
                    .as("the sidecar outlives the app's restart")
                    .isEqualTo(1);
            assertThat(alive(webPid)).isTrue();

            interrupt(jk);
            assertThat(jk.waitFor(30, TimeUnit.SECONDS))
                    .as("Ctrl-C did not end the session\n%s", read(err))
                    .isTrue();
            assertThat(jk.exitValue()).isEqualTo(Exit.INTERRUPTED);
            long newPid = pid(restarted.get());
            awaitGone(webPid);
            awaitGone(newPid);
            assertThat(alive(webPid)).as("the sidecar survived the session").isFalse();
            assertThat(alive(newPid)).as("the app survived the session").isFalse();
            replayTranscript(project, out);
        } finally {
            session.reap();
        }
    }

    @Test
    void with_the_app_as_the_front_door_the_ready_line_returns_after_every_restart(@TempDir Path dir) throws Exception {
        int apiPort = freePort();
        Path project = stage(dir.resolve("vite-sidecar"), freePort(), apiPort);
        Session session = Session.spawn(project, dir, apiPort, "--no-sidecars");
        Process jk = session.jk;
        Path out = session.out;
        Path err = session.err;
        try {
            String appUrl = "http://localhost:" + apiPort + "/api/hello";
            Optional<String> ready = awaitLine(out, typed("dev-ready"), jk);
            assertThat(ready)
                    .as("no dev-ready within the wait\nstdout:\n%s\nstderr:\n%s", read(out), read(err))
                    .isPresent();
            // The app is the front door and fetched at once: no wait for its "listening" line.
            assertThat(get("http://127.0.0.1:" + apiPort + "/api/hello")).contains("hello from the JVM");
            assertThat(Jsonl.str(ready.get(), "url"))
                    .as("the app is the front door: its [dev] ready address")
                    .isEqualTo(appUrl);
            assertThat(Jsonl.str(ready.get(), "app")).contains("demo.Api");
            assertThat(lines(out)).noneMatch(typed("sidecar-started"));
            assertThat(awaitText(err, READY_LINE + appUrl + " (java", jk)).isTrue();
            assertThat(count(read(err), READY_LINE)).isEqualTo(1);

            String started =
                    lines(out).stream().filter(typed("app-started")).findFirst().orElseThrow();
            long appPid = pid(started);
            assertThat(awaitCount(out, listening(), 1, jk)).isTrue();

            touchSource(project);
            Optional<String> restarted = awaitLine(out, typed("app-started").and(l -> pid(l) != appPid), jk);
            assertThat(restarted)
                    .as("no restart after a source change\n%s", read(err))
                    .isPresent();
            assertThat(awaitCount(out, typed("dev-ready"), 2, jk))
                    .as("the ready event returns with the restarted app\n%s", read(out))
                    .isTrue();
            // Fetched at once again: the probe ran after the restart too.
            assertThat(get("http://127.0.0.1:" + apiPort + "/api/hello")).contains("hello from the JVM");
            List<String> events = lines(out);
            int secondStart = events.indexOf(restarted.get());
            int secondReady = indexOfNth(events, typed("dev-ready"), 2);
            assertThat(secondReady)
                    .as("dev-ready follows the restarted app's start")
                    .isGreaterThan(secondStart);
            assertThat(Jsonl.str(events.get(secondReady), "url")).isEqualTo(appUrl);
            assertThat(awaitCount(out, listening(), 2, jk)).isTrue();
            assertThat(awaitText(err, "restarting app", jk)).isTrue();
            assertThat(count(read(err), READY_LINE)).as(read(err)).isEqualTo(2);

            interrupt(jk);
            assertThat(jk.waitFor(30, TimeUnit.SECONDS))
                    .as("Ctrl-C did not end the session\n%s", read(err))
                    .isTrue();
            assertThat(jk.exitValue()).isEqualTo(Exit.INTERRUPTED);
            long newPid = pid(restarted.get());
            awaitGone(newPid);
            assertThat(alive(newPid)).as("the app survived the session").isFalse();
            replayTranscript(project, out);
        } finally {
            session.reap();
        }
    }

    /** One spawned {@code jk dev} with its two output files, and the reaping a failure path owes the machine. */
    private record Session(Process jk, Path out, Path err) {

        static Session spawn(Path project, Path dir, int apiPort, String... options) throws IOException {
            Path out = dir.resolve("stdout.jsonl");
            Path err = dir.resolve("stderr.log");
            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    "cc.jumpkick.cli.Jk",
                    "dev",
                    "--output",
                    "json"));
            command.addAll(List.of(options));
            command.add("--");
            command.add(Integer.toString(apiPort));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(project.toFile());
            pb.redirectOutput(out.toFile());
            pb.redirectError(err.toFile());
            pb.redirectInput(new File("/dev/null"));
            return new Session(pb.start(), out, err);
        }

        /**
         * A failure path must not leave the session's children on the machine: SIGKILL on jk alone
         * would orphan the app and the sidecar, so end the session the way a user does first, and
         * reap whatever the events say was spawned.
         */
        void reap() throws Exception {
            if (jk.isAlive()) {
                interrupt(jk);
                jk.waitFor(15, TimeUnit.SECONDS);
            }
            jk.destroyForcibly();
            for (String l : lines(out)) {
                String type = Jsonl.str(l, "type");
                if ("sidecar-started".equals(type) || "app-started".equals(type)) {
                    ProcessHandle.of(pid(l)).ifPresent(ProcessHandle::destroyForcibly);
                }
            }
            // Into the test report, so a failure on a runner can be read without the temp dir.
            System.out.println("jk dev stderr:\n" + read(err));
            System.out.println("jk dev lifecycle events:\n"
                    + String.join(
                            "\n",
                            lines(out).stream()
                                    .filter(typed("sidecar-output")
                                            .or(typed("app-output"))
                                            .negate())
                                    .toList()));
        }
    }

    /**
     * The example as committed, with two edits a reader can verify: the sidecar runs the stub on a
     * free port instead of {@code npm run dev} on 5173, and the test-dependency table is dropped so
     * the copy builds with no lockfile and no repository.
     */
    private static Path stage(Path project, int webPort, int apiPort) throws IOException {
        Files.createDirectories(project.resolve("web"));
        copyTree(EXAMPLE.resolve("src/main"), project.resolve("src/main"));
        String manifest = Files.readString(EXAMPLE.resolve("jk.toml"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String sidecar = "web = { command = [\"" + java + "\", \"StubDevServer.java\", \"" + webPort
                + "\"], cwd = \"web\", ready = \"http://localhost:" + webPort + "\", front-door = true }";
        String appReady = "ready = \"http://localhost:" + apiPort + "/api/hello\"";
        String edited = manifest.lines()
                .map(l -> l.startsWith("web = {") ? sidecar : l)
                .map(l -> l.startsWith("ready = \"http://localhost:8080") ? appReady : l)
                .filter(l -> !l.startsWith("[test-dependencies]") && !l.startsWith("junit-jupiter"))
                .reduce((a, b) -> a + "\n" + b)
                .orElseThrow();
        assertThat(edited)
                .contains("[dev]\n" + appReady)
                .contains("[dev.sidecars]")
                .contains("main = \"demo.Api\"");
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

    /**
     * The session's transcript: one {@code details.jsonl} for the whole session, however many
     * builds the loop ran. It opens with {@code session-start}, closes with {@code session-finish}
     * carrying the Ctrl-C exit, records one {@code job} line per build the loop ran — as many as
     * the plans stdout showed — and holds every dev event stdout showed, each once, in the same
     * order, with nothing else of the kind. The other run dirs hold no transcript of their own.
     */
    private static void replayTranscript(Path project, Path out) throws IOException {
        Path home = ProjectBuilds.projectHome(project);
        List<Path> transcripts = new ArrayList<>();
        for (Path run : ProjectBuilds.listRuns(home)) {
            Path details = run.resolve(ProjectBuilds.DETAILS);
            if (Files.exists(details)) transcripts.add(details);
        }
        assertThat(transcripts)
                .as("one transcript for the session under %s", home)
                .hasSize(1);
        List<String> transcript = Files.readAllLines(transcripts.getFirst());
        assertThat(Jsonl.str(transcript.getFirst(), "type")).isEqualTo("session-start");
        assertThat(Jsonl.str(transcript.getFirst(), "command")).isEqualTo("dev");
        assertThat(Jsonl.str(transcript.getLast(), "type"))
                .as("Ctrl-C finishes the transcript\n%s", String.join("\n", transcript))
                .isEqualTo("session-finish");
        assertThat(Jsonl.intValue(transcript.getLast(), "exit", -1)).isEqualTo(Exit.INTERRUPTED);
        long builds = lines(out).stream().filter(typed("buildplan-start")).count();
        assertThat(builds).as("the loop built more than once").isGreaterThan(1);
        assertThat(transcript.stream().filter(typed("job")).count())
                .as("one job line per build the loop ran\n%s", String.join("\n", transcript))
                .isEqualTo(builds);
        assertThat(transcript.stream().filter(typed("buildplan-start")).count()).isEqualTo(builds);
        assertThat(transcript.stream().filter(typed("buildplan-finish")).count())
                .isEqualTo(builds);
        List<String> live = lines(out).stream().filter(DEV_EVENT).toList();
        assertThat(live).isNotEmpty();
        assertThat(transcript.stream().filter(DEV_EVENT).toList())
                .as("the transcript replays what stdout showed, once each, in order")
                .containsExactlyElementsOf(live);
    }

    /** A source change the way an editor makes one: the file's content moves, the program does not. */
    private static void touchSource(Path project) throws IOException {
        Files.writeString(
                project.resolve("src/main/java/demo/Api.java"), "\n// a saved edit\n", StandardOpenOption.APPEND);
    }

    private static void interrupt(Process jk) throws Exception {
        int killed = new ProcessBuilder("kill", "-INT", Long.toString(jk.pid()))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor();
        assertThat(killed).as("could not deliver SIGINT").isZero();
    }

    private static Predicate<String> typed(String type) {
        return l -> type.equals(Jsonl.str(l, "type"));
    }

    private static Predicate<String> listening() {
        return typed("app-output").and(l -> String.valueOf(Jsonl.str(l, "line")).startsWith("listening on"));
    }

    private static long pid(String event) {
        return Jsonl.longValue(event, "pid", -1);
    }

    private static int indexOfNth(List<String> events, Predicate<String> match, int n) {
        int seen = 0;
        for (int i = 0; i < events.size(); i++) {
            if (match.test(events.get(i)) && ++seen == n) return i;
        }
        return -1;
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) n++;
        return n;
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

    /** True once at least {@code n} events match; false when the session ends or the wait runs out first. */
    private static boolean awaitCount(Path out, Predicate<String> match, int n, Process jk) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (lines(out).stream().filter(match).count() >= n) return true;
            if (!jk.isAlive()) return false;
            Thread.sleep(200);
        }
        return false;
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
