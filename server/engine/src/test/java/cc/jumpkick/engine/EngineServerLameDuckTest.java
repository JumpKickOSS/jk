// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.JobLimits;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.host.Os;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.EngineTransport;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.LockRequest;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A stopping engine is a lame duck on a real socket, on both transports: it releases the HTTP port
 * at once, keeps answering status on its own socket with its jobs and its drain deadline, refuses
 * new jobs, and at the deadline cancels the job it is stuck on, kills that job's workers and exits,
 * taking its socket and pid files with it. A job is held open by a lock request whose mock
 * repository parks one download on a latch, as in {@link CancellationPrecedenceTest}.
 */
@Tag("integration")
class EngineServerLameDuckTest extends EngineServerHarness {

    private static final String HELD_PATH = "/com/foo/leaf/maven-metadata.xml";

    /** Short enough for a test, long enough that status is read before it passes on a loaded host. */
    private static final long DRAIN_DEADLINE_MS = 4_000L;

    private @Nullable String previousM2;
    private @Nullable String previousTransport;
    private HttpServer repo;
    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private @Nullable EngineServer server;
    private @Nullable Process worker;
    private Path project;
    private final List<String> engineLog = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void mockRepo() throws IOException {
        previousM2 = System.getProperty("jk.m2.local");
        previousTransport = System.getProperty(EngineTransport.TRANSPORT_PROPERTY);
        System.setProperty("jk.m2.local", shortTempDir().toString()); // never touch the real ~/.m2
        Map<String, byte[]> served = new HashMap<>();
        seedArtifact(served, "org.junit.jupiter", "junit-jupiter", "6.1.0");
        seedArtifact(served, "org.junit.platform", "junit-platform-launcher", "6.1.0");
        seedArtifact(served, "com.foo", "leaf", "1.0");
        repo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        repo.setExecutor(Executors.newCachedThreadPool());
        repo.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals(HELD_PATH)) {
                held.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
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
                java    = 25

                [dependencies]
                leaf = { group = "com.foo", name = "leaf", version = "^1.0" }
                """);
    }

    @AfterEach
    void stopEverything() {
        release.countDown();
        if (worker != null) worker.destroyForcibly();
        if (server != null) server.close();
        if (repo != null) repo.stop(0);
        if (previousM2 == null) System.clearProperty("jk.m2.local");
        else System.setProperty("jk.m2.local", previousM2);
        if (previousTransport == null) System.clearProperty(EngineTransport.TRANSPORT_PROPERTY);
        else System.setProperty(EngineTransport.TRANSPORT_PROPERTY, previousTransport);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unix", "tcp"})
    void a_stopping_engine_answers_status_refuses_jobs_and_exits_at_its_drain_deadline(String transport)
            throws Exception {
        assumeTrue(!Os.isWindows(), "the stand-in worker is a POSIX sleep");
        System.setProperty(EngineTransport.TRANSPORT_PROPERTY, transport);
        Path stateDir = shortTempDir();
        EnginePaths.Paths p = paths(stateDir);
        JkEngineConfig config = JkEngineConfig.DEFAULTS.withJobLimits(
                JobLimits.DEFAULTS.withDrainDeadlineMs(DRAIN_DEADLINE_MS).withDetachedDeadlineMs(0L));
        EngineServer engine =
                new EngineServer(p, config, httpOnEphemeralPort(stateDir.resolve("web")), "1.0", engineLog::add);
        this.server = engine;
        Thread runner = runInBackground(engine);
        waitUntil(Duration.ofSeconds(10), () -> Files.exists(EnginePaths.endpoint(p)) && hasContent(p.http()));
        Path socket = EnginePaths.activeSocket(p);
        Path pidFile = EnginePaths.pidFor(socket);
        URI http = URI.create(Files.readString(p.http()).trim());

        try (Client building = new Client(socket)) {
            long jid = startLock(building);
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
            // A worker the stuck job forked: the drain deadline must not leave it behind.
            Process sleeper = new ProcessBuilder("sleep", "300").start();
            this.worker = sleeper;
            Long previous = JobWorkers.bind(jid);
            try {
                JobWorkers.register(sleeper);
            } finally {
                JobWorkers.restore(previous);
            }

            long stopAt = System.currentTimeMillis();
            try (Client stopper = new Client(socket)) {
                String bye = stopper.send(ProtoLifecycle.shutdown(false));
                assertThat(EngineProtocol.typeOf(bye)).isEqualTo(EngineProtocol.BYE);
                assertThat(Jsonl.intValue(bye, "plans", -1)).isEqualTo(1);
                assertThat(Jsonl.bool(bye, "draining", false)).isTrue();
            }

            // HTTP is released at drain start, so a successor can bind the fixed port.
            waitUntil(Duration.ofSeconds(10), () -> !accepts(http));

            // The engine socket still answers: status says draining, with the job and the deadline.
            try (Client status = new Client(socket)) {
                String hello = status.send(ProtoLifecycle.hello("1.0", "probe"));
                assertThat(Jsonl.bool(hello, "draining", false)).isTrue();
                String ack = status.send(ProtoLifecycle.statusRequest());
                assertThat(EngineProtocol.typeOf(ack)).isEqualTo(EngineProtocol.STATUS_ACK);
                assertThat(Jsonl.bool(ack, "draining", false)).isTrue();
                assertThat(Jsonl.intValue(ack, "activeBuildPlans", -1)).isEqualTo(1);
                assertThat(Jsonl.longValue(ack, "drainDeadline", -1))
                        .isBetween(stopAt + DRAIN_DEADLINE_MS, System.currentTimeMillis() + DRAIN_DEADLINE_MS);
                assertThat(Jsonl.objectArray(ack, "jobs")).anyMatch(row -> Jsonl.longValue(row, "jid", -1) == jid);
            }

            // A new job is refused before it runs, with the code a job client takes over on.
            try (Client late = new Client(socket)) {
                late.sendLine(lockRequest());
                String refusal = requireNonNull(late.readLine(Duration.ofSeconds(10)));
                assertThat(EngineProtocol.typeOf(refusal)).isEqualTo(EngineProtocol.ERROR);
                assertThat(Jsonl.str(refusal, "code")).isEqualTo(EngineProtocol.ERR_SHUTTING_DOWN);
            }

            // At the deadline the stuck job is cancelled with a verdict that names the drain.
            List<String> rest = readToEof(building);
            String error = rest.stream()
                    .filter(l -> EngineProtocol.ERROR.equals(EngineProtocol.typeOf(l)))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no error line; saw:\n" + String.join("\n", rest)));
            assertThat(Jsonl.str(error, "code")).isEqualTo(EngineProtocol.ERR_DEADLINE);
            assertThat(Jsonl.str(error, "message")).contains("drain deadline").contains("drain-deadline-ms");
            assertThat(rest.stream()
                            .filter(l -> EngineProtocol.BUILDPLAN_FINISH.equals(EngineProtocol.typeOf(l)))
                            .anyMatch(l -> Jsonl.bool(l, "cancelled", false)))
                    .as(String.join("\n", rest))
                    .isTrue();
        }

        runner.join(30_000);
        assertThat(runner.isAlive())
                .as("the engine exits at its drain deadline; log:\n" + String.join("\n", List.copyOf(engineLog)))
                .isFalse();
        assertThat(requireNonNull(worker).waitFor(10, TimeUnit.SECONDS))
                .as("the stuck job's worker is killed")
                .isTrue();
        assertThat(socket).as("the socket file goes with the engine").doesNotExist();
        assertThat(pidFile).as("the pid file goes with the engine").doesNotExist();
        assertThat(EnginePaths.endpoint(p))
                .as("no successor took the endpoint, so it is removed")
                .doesNotExist();
        assertThat(List.copyOf(engineLog)).anyMatch(l -> l.contains("drain deadline passed with 1 job(s) in flight"));
    }

    /** Whether anything still accepts a TCP connection at {@code url}'s host and port. */
    private static boolean accepts(URI url) {
        try (Socket s = new Socket(url.getHost(), url.getPort())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String lockRequest() throws IOException {
        String repoUrl = "http://127.0.0.1:" + repo.getAddress().getPort();
        return new LockRequest(
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
                .encode();
    }

    /** Send the lock request and return its jid from {@code job-start}. */
    private long startLock(Client c) throws IOException {
        c.sendLine(lockRequest());
        String start = readUntil(c, EngineProtocol.JOB_START);
        long jid = Jsonl.longValue(start, "jid", -1);
        assertThat(jid).isPositive();
        return jid;
    }

    private static final Duration FRAME_BOUND = Duration.ofSeconds(30);

    private List<String> readToEof(Client c) throws IOException {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = readOne(c, lines)) != null) lines.add(line);
        return lines;
    }

    private String readUntil(Client c, String type) throws IOException {
        List<String> seen = new ArrayList<>();
        String line;
        while ((line = readOne(c, seen)) != null) {
            seen.add(line);
            if (type.equals(EngineProtocol.typeOf(line))) return line;
        }
        throw new AssertionError("stream ended before a " + type + " line; saw:\n  " + String.join("\n  ", seen)
                + "\nengine log:\n  " + String.join("\n  ", List.copyOf(engineLog)));
    }

    private @Nullable String readOne(Client c, List<String> seen) throws IOException {
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
