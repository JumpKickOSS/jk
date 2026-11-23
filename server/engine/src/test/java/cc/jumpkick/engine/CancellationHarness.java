// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.LockRequest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * The cancel chain against a live engine on a real socket, one precedence rule per test of a
 * subclass. A job is held open by a lock request whose mock repository parks one download on a
 * latch: that gives a live jid from {@code job-start} and forks no compiler or plugin worker. The
 * cancel reason is read off the {@code request-finish} SSE frame, the same line the dashboard reads.
 */
abstract class CancellationHarness extends EngineServerHarness {

    static final String BY_USER = "cancelled by the user (Ctrl-C, jk cancel, or the dashboard)";
    static final String BY_DISCONNECT = "the client disconnected before the job finished";
    static final String HELD_PATH = "/com/foo/leaf/maven-metadata.xml";

    HttpServer repo;
    final CountDownLatch held = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    volatile boolean holdLeaf = true;

    @Nullable
    EngineServer server;

    Path project;
    EnginePaths.@Nullable Paths paths;
    final List<String> engineLog = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void mockRepo() throws IOException {
        System.setProperty("jk.m2.local", shortTempDir().toString()); // never touch the real ~/.m2
        Map<String, byte[]> served = new HashMap<>();
        seedArtifact(served, "org.junit.jupiter", "junit-jupiter", "6.1.0");
        seedArtifact(served, "org.junit.platform", "junit-platform-launcher", "6.1.0");
        seedArtifact(served, "com.foo", "leaf", "1.0");
        repo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        repo.setExecutor(Executors.newCachedThreadPool());
        repo.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (holdLeaf && path.equals(HELD_PATH)) {
                held.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = served.get(path);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        repo.start();
        project = shortTempDir();
        Files.writeString(project.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                jdk     = { version = "25" }
                java    = 25

                [dependencies]
                leaf = { group = "com.foo", name = "leaf", version = "^1.0" }
                """);
    }

    @AfterEach
    void stopEverything() {
        release.countDown();
        if (server != null) server.close();
        if (repo != null) repo.stop(0);
    }

    /**
     * Let the parked download answer once the cancel has landed, so the cancelled job unwinds now
     * rather than at the plan's backstop for a step that ignores its cancel.
     */
    void unpark() {
        release.countDown();
    }

    EnginePaths.Paths paths() {
        return requireNonNull(paths, "startEngine first");
    }

    /** Start an engine with HTTP on and return the dashboard SSE stream, subscribed before any job. */
    Iterator<String> startEngine(JkEngineConfig config) throws Exception {
        Path stateDir = shortTempDir();
        EnginePaths.Paths paths = paths(stateDir);
        this.paths = paths;
        EngineServer server =
                new EngineServer(paths, config, httpOnEphemeralPort(stateDir.resolve("web")), "1.0", engineLog::add);
        this.server = server;
        runInBackground(server);
        waitUntil(
                Duration.ofSeconds(10),
                () -> Files.exists(EnginePaths.endpoint(paths))
                        && hasContent(paths.http())
                        && hasContent(paths.httpToken()));
        String url = Files.readString(paths.http());
        String token = Files.readString(paths.httpToken()).trim();
        HttpResponse<Stream<String>> sse = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(url + "api/events?access_token=" + token))
                                .build(),
                        HttpResponse.BodyHandlers.ofLines());
        assertThat(sse.statusCode()).isEqualTo(200);
        return sse.body().iterator();
    }

    /** Send the lock request and return its jid from {@code job-start}. */
    long startLock(Client c) throws IOException {
        String repoUrl = "http://127.0.0.1:" + repo.getAddress().getPort();
        c.sendLine(new LockRequest(
                        project.toString(),
                        shortTempDir().toString(),
                        List.of(),
                        false,
                        false,
                        repoUrl,
                        false,
                        false,
                        false,
                        false)
                .encode());
        String start = readUntil(c, EngineProtocol.JOB_START);
        long jid = Jsonl.longValue(start, "jid", -1);
        assertThat(jid).isPositive();
        return jid;
    }

    /** How long one frame may take to arrive before the test fails naming what it saw. */
    static final Duration FRAME_BOUND = Duration.ofSeconds(30);

    List<String> readToEof(Client c) throws IOException {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = readOne(c, lines)) != null) lines.add(line);
        return lines;
    }

    String readUntil(Client c, String type) throws IOException {
        List<String> seen = new ArrayList<>();
        String line;
        while ((line = readOne(c, seen)) != null) {
            seen.add(line);
            if (type.equals(EngineProtocol.typeOf(line))) return line;
        }
        throw new AssertionError("stream ended before a " + type + " line; saw:\n  " + String.join("\n  ", seen)
                + "\nengine log:\n  " + String.join("\n  ", List.copyOf(engineLog)));
    }

    /** One bounded read; a frame that does not arrive fails with the frames seen and the engine log. */
    @Nullable
    String readOne(Client c, List<String> seen) throws IOException {
        try {
            return c.readLine(FRAME_BOUND);
        } catch (AssertionError timeout) {
            throw new AssertionError(
                    timeout.getMessage() + "; saw:\n  " + String.join("\n  ", seen) + "\nengine log:\n  "
                            + String.join("\n  ", List.copyOf(engineLog)),
                    timeout);
        }
    }
}
