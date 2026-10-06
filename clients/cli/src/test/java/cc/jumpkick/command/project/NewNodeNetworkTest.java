// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineSpawn;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.scaffold.NodeGenerators;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every {@code jk new --lang node} template, against the real generator from the npm registry under
 * a Node.js from nodejs.org: it generates without a prompt, builds, builds again from the cache, and
 * a server template's {@code jk run} answers {@code GET /}. A generator that changes the flags it
 * needs without a terminal fails here, not for a user.
 */
@Tag("network")
class NewNodeNetworkTest {

    static Stream<String> frameworks() {
        return NodeGenerators.all().stream().map(NodeGenerators.Framework::id);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("frameworks")
    void the_template_generates_builds_and_runs(String id, @TempDir Path dir) throws Exception {
        NodeGenerators.Framework framework = NodeGenerators.find(id).orElseThrow();
        Path app = dir.resolve("app");
        assertThat(run("new", "--lang", "node", "-t", id, "--group", "com.acme", app.toString()))
                .as("jk new --lang node -t %s", id)
                .isZero();
        assertThat(Files.readString(app.resolve("jk.toml"))).contains("node = ");
        assertThat(NodeGenerators.LOCKFILES.stream().anyMatch(l -> Files.exists(app.resolve(l))))
                .as("a committed lockfile")
                .isTrue();

        assertThat(run("build", "-C", app.toString())).as("first build").isZero();
        assertThat(run("build", "-C", app.toString())).as("second build").isZero();

        if (framework.shape() == NodeGenerators.Shape.SERVER) {
            int port = freePort();
            Files.writeString(app.resolve(".env"), "PORT=" + port + "\n");
            assertServes(app, port, dir.resolve("run"));
        } else {
            Capture.Streams out = Capture.both(() -> run("run", "-C", app.toString()));
            assertThat(out.err() + out.out()).contains("has nothing to start");
        }
    }

    /** {@code jk run} in its own process, until {@code GET /} answers 200; then the whole tree goes. */
    private static void assertServes(Path app, int port, Path logs) throws Exception {
        Files.createDirectories(logs);
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(EngineSpawn.forwardedJvmArgs());
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), "cc.jumpkick.cli.Jk", "run"));
        Process jk = new ProcessBuilder(command)
                .directory(app.toFile())
                .redirectOutput(logs.resolve("stdout.log").toFile())
                .redirectError(logs.resolve("stderr.log").toFile())
                .start();
        jk.getOutputStream().close();
        // HTTP/1.1: some servers drop an h2c upgrade on plain http (next start answers nothing).
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        int status = -1;
        try {
            long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
            while (status != 200 && jk.isAlive() && System.nanoTime() < deadline) {
                for (String host : List.of("127.0.0.1", "localhost")) {
                    try {
                        status = http.send(
                                        HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/"))
                                                .timeout(Duration.ofSeconds(5))
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode();
                        if (status == 200) break;
                    } catch (IOException notYet) {
                        // the server is still starting
                    }
                }
                if (status != 200) Thread.sleep(500);
            }
        } finally {
            jk.descendants().forEach(ProcessHandle::destroy);
            jk.destroy();
            jk.waitFor();
        }
        assertThat(status)
                .as(
                        "GET / on %d; jk run stdout:%n%s%nstderr:%n%s",
                        port,
                        Files.readString(logs.resolve("stdout.log")),
                        Files.readString(logs.resolve("stderr.log")))
                .isEqualTo(200);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
