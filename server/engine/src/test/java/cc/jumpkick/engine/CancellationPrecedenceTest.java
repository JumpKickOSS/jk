// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.JobLimits;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.LockRequest;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The cancel chain against a live engine on a real socket, one precedence rule per test. A job is
 * held open by a lock request whose mock repository parks one download on a latch — that gives a
 * live jid from {@code job-start} and forks no compiler or plugin worker. The cancel reason is read
 * off the {@code request-finish} SSE frame, the same line the dashboard reads.
 */
@Tag("integration")
class CancellationPrecedenceTest extends EngineServerHarness {

    private static final String BY_USER = "cancelled by the user (Ctrl-C, jk cancel, or the dashboard)";
    private static final String BY_DISCONNECT = "the client disconnected before the job finished";
    private static final String HELD_PATH = "/com/foo/leaf/maven-metadata.xml";

    private @Nullable String previousM2;
    private HttpServer repo;
    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean holdLeaf = true;
    private @Nullable EngineServer server;
    private Path project;
    private EnginePaths.@Nullable Paths paths;
    private final List<String> engineLog = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void mockRepo() throws IOException {
        previousM2 = System.getProperty("jk.m2.local");
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
                jdk     = 25
                java    = 25

                [dependencies]
                leaf = { group = "com.foo", name = "leaf", version = "1.0" }
                """);
    }

    @AfterEach
    void stopEverything() {
        release.countDown();
        if (server != null) server.close();
        if (repo != null) repo.stop(0);
        if (previousM2 == null) System.clearProperty("jk.m2.local");
        else System.setProperty("jk.m2.local", previousM2);
    }

    @Test
    void cancel_by_jid_over_a_second_connection_acks_at_once_and_the_job_finishes_cancelled_by_the_user()
            throws Exception {
        Iterator<String> sse = startEngine(JkEngineConfig.DEFAULTS);
        try (Client building = new Client(EnginePaths.activeSocket(paths()));
                Client canceller = new Client(EnginePaths.activeSocket(paths()))) {
            long jid = startLock(building);
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            String ack = canceller.send(ProtoLifecycle.cancelRequest(jid));
            // The ack arrives while the download is still parked: it does not wait for the job to settle.
            assertThat(release.getCount()).isEqualTo(1);
            assertThat(EngineProtocol.typeOf(ack)).isEqualTo(EngineProtocol.CANCEL_ACK);
            assertThat(Jsonl.longValue(ack, "jid", -1)).isEqualTo(jid);
            assertThat(Jsonl.bool(ack, "cancelled", false)).isTrue();
            assertThat(Jsonl.has(ack, "note")).isFalse();

            String terminal = readUntil(building, EngineProtocol.BUILDPLAN_FINISH);
            assertThat(Jsonl.bool(terminal, "cancelled", false)).isTrue();
            assertThat(Jsonl.str(terminal, "dir")).isEqualTo(project.toString());
            assertThat(EngineProtocol.typeOf(readUntil(building, EngineProtocol.JOB_FINISH)))
                    .as("job-finish still reaches a client blocking on it")
                    .isEqualTo(EngineProtocol.JOB_FINISH);
        }
        String finish = awaitSseData(sse, "request-finish");
        assertThat(Jsonl.bool(finish, "cancelled", false)).isTrue();
        assertThat(Jsonl.str(finish, "cancelReason")).isEqualTo(BY_USER);
    }

    @Test
    void cancel_by_unknown_jid_is_a_soft_miss_with_the_note() throws Exception {
        startEngine(JkEngineConfig.DEFAULTS);
        try (Client c = new Client(EnginePaths.activeSocket(paths()))) {
            String ack = c.send(ProtoLifecycle.cancelRequest(4242));
            assertThat(Jsonl.longValue(ack, "jid", -1)).isEqualTo(4242);
            assertThat(Jsonl.bool(ack, "cancelled", true)).isFalse();
            assertThat(Jsonl.str(ack, "note")).isEqualTo("unknown or already finished jid");
        }
    }

    @Test
    void cancel_by_dir_cancels_every_live_job_under_it_and_then_finds_none() throws Exception {
        startEngine(JkEngineConfig.DEFAULTS);
        try (Client building = new Client(EnginePaths.activeSocket(paths()));
                Client canceller = new Client(EnginePaths.activeSocket(paths()))) {
            startLock(building);
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            String ack = canceller.send(ProtoLifecycle.cancelRequestForDir(project.toString()));
            assertThat(Jsonl.longValue(ack, "jid", -1)).isZero();
            assertThat(Jsonl.bool(ack, "cancelled", false)).isTrue();
            assertThat(Jsonl.str(ack, "note")).isEqualTo("cancelled 1 job(s)");

            readUntil(building, EngineProtocol.JOB_FINISH);
            String none = canceller.send(ProtoLifecycle.cancelRequestForDir(project.toString()));
            assertThat(Jsonl.longValue(none, "jid", -1)).isZero();
            assertThat(Jsonl.bool(none, "cancelled", true)).isFalse();
            assertThat(Jsonl.str(none, "note")).isEqualTo("no running jobs for dir");
        }
    }

    @Test
    void cancel_with_neither_jid_nor_dir_is_refused() throws Exception {
        startEngine(JkEngineConfig.DEFAULTS);
        try (Client c = new Client(EnginePaths.activeSocket(paths()))) {
            String ack = c.send(ProtoLifecycle.cancelRequestForDir(""));
            assertThat(Jsonl.longValue(ack, "jid", 0)).isEqualTo(-1);
            assertThat(Jsonl.bool(ack, "cancelled", true)).isFalse();
            assertThat(Jsonl.str(ack, "note")).isEqualTo("cancel-request requires jid or dir");
        }
    }

    @Test
    void a_client_that_disconnects_mid_job_is_cancelled_by_disconnect_not_by_the_user() throws Exception {
        Iterator<String> sse = startEngine(JkEngineConfig.DEFAULTS);
        try (Client building = new Client(EnginePaths.activeSocket(paths()))) {
            startLock(building);
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        }
        String finish = awaitSseData(sse, "request-finish");
        assertThat(Jsonl.bool(finish, "cancelled", false)).isTrue();
        assertThat(Jsonl.str(finish, "cancelReason")).isEqualTo(BY_DISCONNECT);
    }

    @Test
    void a_wall_deadline_is_a_third_reason_and_names_itself_on_the_wire() throws Exception {
        // The deadline is measured from job start, and the point of the test is a job cut off while
        // parked on the held download. Three seconds is far past the time the lock takes to reach
        // that download on a loaded machine, and far short of the latch's own 30 s release, so the
        // kill lands where the test says it does rather than in an earlier metadata fetch.
        Iterator<String> sse =
                startEngine(JkEngineConfig.DEFAULTS.withJobLimits(new JobLimits(0L, 3_000L, 200L, 500L)));
        try (Client building = new Client(EnginePaths.activeSocket(paths()))) {
            startLock(building);
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            // The deadline's error line and the cancelled terminal race each other onto the wire:
            // the watchdog writes the error after interrupting the runner, whose unwinding may
            // already have settled the plan. Both must be there, and job-finish must be last.
            List<String> rest = readToEof(building);
            List<String> types = rest.stream().map(EngineProtocol::typeOf).toList();
            assertThat(types)
                    .as(String.join("\n", rest))
                    .contains(EngineProtocol.ERROR, EngineProtocol.BUILDPLAN_FINISH);
            assertThat(types.getLast()).isEqualTo(EngineProtocol.JOB_FINISH);
            String error = rest.stream()
                    .filter(l -> EngineProtocol.ERROR.equals(EngineProtocol.typeOf(l)))
                    .findFirst()
                    .orElseThrow();
            assertThat(Jsonl.str(error, "code")).isEqualTo(EngineProtocol.ERR_DEADLINE);
            String terminal = rest.stream()
                    .filter(l -> EngineProtocol.BUILDPLAN_FINISH.equals(EngineProtocol.typeOf(l)))
                    .reduce((a, b) -> b)
                    .orElseThrow();
            assertThat(Jsonl.bool(terminal, "cancelled", false))
                    .as("wire:\n" + String.join("\n", rest) + "\nengine log:\n" + String.join("\n", engineLog))
                    .isTrue();
        }
        String finish = awaitSseData(sse, "request-finish");
        assertThat(Jsonl.bool(finish, "cancelled", false)).isTrue();
        assertThat(Jsonl.str(finish, "cancelReason"))
                .isEqualTo("exceeded the 3000ms wall deadline (JK_ENGINE_JOB_DEADLINE_MS); cancelled")
                .isNotEqualTo(BY_USER)
                .isNotEqualTo(BY_DISCONNECT);
    }

    @Test
    void a_job_that_finished_is_not_relabelled_cancelled_by_the_end_of_request_eof() throws Exception {
        holdLeaf = false;
        Iterator<String> sse = startEngine(JkEngineConfig.DEFAULTS);
        try (Client building = new Client(EnginePaths.activeSocket(paths()))) {
            startLock(building);
            String terminal = readUntil(building, EngineProtocol.BUILDPLAN_FINISH);
            assertThat(Jsonl.bool(terminal, "cancelled", false)).isFalse();
            readUntil(building, EngineProtocol.JOB_FINISH);
        }
        String finish = awaitSseData(sse, "request-finish");
        assertThat(Jsonl.bool(finish, "success", false)).isTrue();
        assertThat(Jsonl.bool(finish, "cancelled", true)).isFalse();
        assertThat(Jsonl.has(finish, "cancelReason")).isFalse();
    }

    private EnginePaths.Paths paths() {
        return requireNonNull(paths, "startEngine first");
    }

    /** Start an engine with HTTP on and return the dashboard SSE stream, subscribed before any job. */
    private Iterator<String> startEngine(JkEngineConfig config) throws Exception {
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
                        && Files.exists(paths.http())
                        && Files.exists(paths.httpToken()));
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
    private long startLock(Client c) throws IOException {
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

    private static List<String> readToEof(Client c) throws IOException {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = c.readLine()) != null) lines.add(line);
        return lines;
    }

    private String readUntil(Client c, String type) throws IOException {
        List<String> seen = new ArrayList<>();
        String line;
        while ((line = c.readLine()) != null) {
            seen.add(line);
            if (type.equals(EngineProtocol.typeOf(line))) return line;
        }
        throw new AssertionError("stream ended before a " + type + " line; saw:\n  " + String.join("\n  ", seen)
                + "\nengine log:\n  " + String.join("\n  ", List.copyOf(engineLog)));
    }
}
