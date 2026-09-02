// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.EngineServer;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.testing.Await;
import cc.jumpkick.testing.ShortTempDirs;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Exercises {@link EngineClient} against a real, in-process {@link EngineServer} — everything except
 * the actual OS-process spawn ({@link EngineClient#ensureRunning}'s cold-start path needs a real
 * {@code jk} binary to exec, which a JVM test run doesn't have; that path is covered by manual
 * verification per the Step 1 plan, not a unit test).
 */
@Tag("integration")
class EngineClientTest {

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkc-");

    @AfterEach
    void cleanup() {
        // Every test that gets a real EngineServer to run triggers planSharedWorkerMemoryOnce,
        // which mutates JvmOptions' process-wide static heap plan — reset it so it doesn't leak into
        // unrelated tests sharing this test JVM.
        JvmOptions.resetSharedPlanForTests();
    }

    private static Thread startInBackground(EngineServer server) {
        Thread t = new Thread(
                () -> {
                    try {
                        server.run();
                    } catch (IOException ignored) {
                        // surfaced via the socket never appearing; the Await below will time out
                    }
                },
                "test-engine-server");
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    void ping_is_false_when_nothing_is_listening() throws IOException {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        assertThat(EngineClient.ping(EnginePaths.activeSocket(p))).isFalse();
    }

    @Test
    void ping_handshake_and_status_round_trip_against_a_real_engine() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "7.7.7", null);
        startInBackground(server);
        // Endpoint is written before acceptLoop (AOT plan / HTTP / warmup still run first).
        // Wait for a real pong — cold CI can take longer than the 2s connect timeout between
        // writeEndpoint and the accept loop, so "endpoint exists" alone races.
        Await.until(Duration.ofSeconds(30), () -> EngineClient.ping(EnginePaths.activeSocket(p)));

        var hs = EngineClient.handshake(EnginePaths.activeSocket(p), "7.7.7");
        assertThat(hs).isPresent();
        assertThat(hs.get().version()).isEqualTo("7.7.7");
        assertThat(hs.get().pid()).isEqualTo(ProcessHandle.current().pid());

        var status = EngineClient.status(EnginePaths.activeSocket(p));
        assertThat(status).isPresent();
        assertThat(status.get().version()).isEqualTo("7.7.7");
        assertThat(status.get().heapUsedBytes()).isPositive(); // best-effort memory made it across the wire
        assertThat(status.get().heapCommittedBytes())
                .isGreaterThanOrEqualTo(status.get().heapUsedBytes());

        server.close();
    }

    @Test
    void stop_gracefully_shuts_a_running_engine_down() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = startInBackground(server);
        Await.until(Duration.ofSeconds(30), () -> EngineClient.ping(EnginePaths.activeSocket(p)));

        assertThat(EngineClient.stop(EnginePaths.activeSocket(p))).isTrue();
        serverThread.join(5_000);
        assertThat(serverThread.isAlive()).isFalse();
    }

    @Test
    void stop_on_a_non_running_engine_is_a_no_op_success() throws IOException {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        assertThat(EngineClient.stop(EnginePaths.activeSocket(p))).isTrue();
    }

    @Test
    void a_normal_engine_reports_not_draining_and_zero_plans() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        startInBackground(server);
        Await.until(Duration.ofSeconds(30), () -> EngineClient.ping(EnginePaths.activeSocket(p)));

        assertThat(EngineClient.handshake(EnginePaths.activeSocket(p), "1.0")
                        .orElseThrow()
                        .draining())
                .isFalse();
        var s = EngineClient.status(EnginePaths.activeSocket(p)).orElseThrow();
        assertThat(s.draining()).isFalse();
        assertThat(s.activeBuildPlans()).isZero();

        server.close();
    }

    @Test
    void drain_of_an_idle_engine_reports_zero_jobs_and_shuts_it_down() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = startInBackground(server);
        Await.until(Duration.ofSeconds(30), () -> EngineClient.ping(EnginePaths.activeSocket(p)));

        assertThat(EngineClient.drain(EnginePaths.activeSocket(p))).isZero(); // no in-flight jobs → immediate exit
        serverThread.join(5_000);
        assertThat(serverThread.isAlive()).isFalse();
    }

    @Test
    void force_stop_shuts_a_running_engine_down() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = startInBackground(server);
        Await.until(Duration.ofSeconds(30), () -> EngineClient.ping(EnginePaths.activeSocket(p)));

        long pid = EngineClient.readPidForSocket(EnginePaths.activeSocket(p));
        // In-process EngineServer records this JVM's pid; forceStop must not kill us.
        assertThat(pid).isEqualTo(ProcessHandle.current().pid());

        assertThat(EngineClient.forceStop(EnginePaths.activeSocket(p))).isTrue();
        serverThread.join(5_000);
        assertThat(serverThread.isAlive()).isFalse();
    }

    @Test
    void handshake_is_empty_within_seconds_against_a_silent_peer() throws Exception {
        // Accept connections, never read or write — models a wedged engine.
        Path dir = tempDirs.create();
        Path sock = dir.resolve("silent.sock");
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(sock));
            Thread acceptor = new Thread(
                    () -> {
                        try {
                            while (true) {
                                var client = server.accept();
                                // leave the client hanging; never reply
                                Thread.sleep(10_000);
                                client.close();
                            }
                        } catch (Exception ignored) {
                            // channel closed when test ends
                        }
                    },
                    "silent-peer");
            acceptor.setDaemon(true);
            acceptor.start();

            long t0 = System.nanoTime();
            assertThat(EngineClient.handshake(sock, "1.0")).isEmpty();
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            // LIVENESS, not performance: the exchange watchdog is 2s and the alternative is the
            // stream-idle timeout, which is minutes. 8s is 4x the watchdog on purpose — a tighter
            // budget would be measuring this machine rather than the watchdog.
            assertThat(ms)
                    .as("gave up on the 2s exchange watchdog, not after the minutes-long stream idle")
                    .isLessThan(8_000L);
        }
    }

    @Test
    void wait_for_death_or_kill_is_a_no_op_for_missing_pid() {
        EngineClient.waitForDeathOrKill(-1, Duration.ofMillis(50));
        EngineClient.waitForDeathOrKill(9_999_999_999L, Duration.ofMillis(50));
    }

    @Test
    void drain_on_a_non_running_engine_is_minus_one() throws IOException {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        assertThat(EngineClient.drain(EnginePaths.activeSocket(p))).isEqualTo(-1);
    }

    /**
     * No real Windows box in this test run, but {@code EngineTransport.useLoopbackTcp} only ever
     * reads {@code os.name} — overriding it exercises {@link EngineClient#connect}'s TCP+token
     * branch for real, end-to-end through the same public API every other test above uses.
     */
    @Test
    void ping_handshake_and_status_round_trip_over_the_loopback_tcp_transport() throws Exception {
        String previousOsName = System.getProperty("os.name");
        System.setProperty("os.name", "Windows 11");
        try {
            EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
            EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "7.7.7", null);
            startInBackground(server);
            Await.until(Duration.ofSeconds(30), () -> EngineClient.ping(EnginePaths.activeSocket(p)));

            var hs = EngineClient.handshake(EnginePaths.activeSocket(p), "7.7.7");
            assertThat(hs).isPresent();
            assertThat(hs.get().version()).isEqualTo("7.7.7");

            var status = EngineClient.status(EnginePaths.activeSocket(p));
            assertThat(status).isPresent();
            assertThat(status.get().version()).isEqualTo("7.7.7");

            assertThat(EngineClient.stop(EnginePaths.activeSocket(p))).isTrue();
        } finally {
            if (previousOsName != null) System.setProperty("os.name", previousOsName);
            else System.clearProperty("os.name");
        }
    }

    /**
     * The spawn path's artifact resolution: JK_ENGINE_EXE override, then the product-lib jar
     * paired with this client version. No client-binary FALLBACK.
     */
    @Test
    void engine_artifact_resolution_prefers_override_then_product_lib() throws IOException {
        Path dir = tempDirs.create();
        // Pointer lives beside the jars under this isolated product lib — no ambient JK_HOME involved.
        EngineInstall install = new EngineInstall(dir.resolve("lib"));

        // no override, nothing materialized: empty (caller must materialize or set JK_ENGINE_EXE)
        assertThat(EngineSpawn.resolveEngineArtifact(null, "1.2.3", install)).isEmpty();

        // a version-skewed materialization never launches — the version match is the contract
        materialize(install, dir, "0.1.0");
        assertThat(EngineSpawn.resolveEngineArtifact(null, "1.2.3", install)).isEmpty();

        // <home>/lib/jk-engine/<jar>: the JVM-hosted engine's fat jar
        Path engineJar = materialize(install, dir, "1.2.3");
        EngineSpawn.EngineArtifact viaLib =
                EngineSpawn.resolveEngineArtifact(null, "1.2.3", install).orElseThrow();
        assertThat(viaLib.kind()).isEqualTo(EngineSpawn.EngineArtifact.Kind.JAR);
        assertThat(viaLib.path()).isEqualTo(engineJar.toString());

        // JK_ENGINE_EXE wins over the materialized jar, always a dedicated executable
        EngineSpawn.EngineArtifact viaEnv = EngineSpawn.resolveEngineArtifact("/opt/jk/jk-engine", "1.2.3", install)
                .orElseThrow();
        assertThat(viaEnv.kind()).isEqualTo(EngineSpawn.EngineArtifact.Kind.EXE);
        assertThat(viaEnv.path()).isEqualTo("/opt/jk/jk-engine");

        // a blank override is ignored, not obeyed
        assertThat(EngineSpawn.resolveEngineArtifact("  ", "1.2.3", install)
                        .orElseThrow()
                        .path())
                .isEqualTo(viaLib.path());
    }

    /** Materialize a fake engine jar for {@code version} into the isolated product lib. */
    private static Path materialize(EngineInstall install, Path dir, String version) throws IOException {
        Path jar = dir.resolve("jk-engine-" + version + "-src.jar");
        Files.writeString(jar, "fake engine " + version);
        Cas cas = new Cas(dir.resolve("cache"));
        return install.materializeFromFiles(version, cas, jar).engineJar();
    }

    @Test
    void a_cache_prune_registers_its_jid_as_the_ctrl_c_cancel_handle() throws Exception {
        // jk cache prune's request carries no dir, so the engine journals it against the cache
        // path: Ctrl-C's dir-scoped cancel can never match it and the jid from job-start is the
        // only handle that reaches the job. Same for jk tool resolve and jk tool run <script>.
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, Jk.VERSION, null);
        startInBackground(server);
        Await.until(Duration.ofSeconds(30), () -> EngineClient.ping(EnginePaths.activeSocket(p)));
        EngineClient.ActiveJobs.forgetAll();

        Path cache = Files.createDirectories(tempDirs.create().resolve("cache"));
        List<Long> liveWhileRunning = new ArrayList<>();
        EngineRequests.CacheMaintSummary[] summary = new EngineRequests.CacheMaintSummary[1];
        BuildPlanResult result = EngineClient.runCacheMaintenance(
                p,
                new EngineRequests.CacheMaintRequest("prune", cache, true, false, cache),
                steps -> {
                    liveWhileRunning.addAll(EngineClient.ActiveJobs.snapshot());
                    return new BuildPlanListener() {};
                },
                (external, plans) -> {},
                summary);

        assertThat(result.success()).isTrue();
        assertThat(liveWhileRunning).hasSize(1).allMatch(jid -> jid > 0);
        // …and the handle is dropped once the stream ends, so the next Ctrl-C does not pay a
        // cancel RPC for a job that is already over.
        assertThat(EngineClient.ActiveJobs.snapshot()).isEmpty();

        server.close();
    }

    @Test
    void ensure_running_returns_immediately_when_a_matching_version_engine_is_already_up() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "3.3.3", null);
        startInBackground(server);
        Await.until(Duration.ofSeconds(30), () -> EngineClient.ping(EnginePaths.activeSocket(p)));

        EngineClient.Handshake hs = EngineClient.ensureRunning(p, "3.3.3");
        assertThat(hs.version()).isEqualTo("3.3.3");

        server.close();
    }
}
