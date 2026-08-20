// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoLifecycle;
import cc.jumpkick.jsonl.Jsonl;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A newer engine takes over by atomically repointing the endpoint, the predecessor yields its
 * listeners immediately, and in-flight jobs drain — no kill, no lull-waiting. Also covers the
 * displacement watchdog (pid-file / endpoint identity) and drain-status reports to the successor.
 */
@Tag("integration")
class EngineTakeoverTest {

    private final List<Path> tempDirs = new ArrayList<>();

    private Path shortTempDir() throws IOException {
        // Prefer /tmp: macOS TMPDIR under /var/folders overflows UDS sun_path (~104 bytes).
        Path root =
                Files.isDirectory(Path.of("/tmp")) ? Path.of("/tmp") : Path.of(System.getProperty("java.io.tmpdir"));
        Path dir = Files.createTempDirectory(root, "jkt-");
        tempDirs.add(dir);
        return dir;
    }

    @AfterEach
    void cleanupTempDirs() {
        cc.jumpkick.engine.plugin.JvmOptions.resetSharedPlanForTests();
        for (Path dir : tempDirs) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException | UncheckedIOException ignored) {
            }
        }
    }

    private static void waitUntil(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met within " + timeout);
            Thread.sleep(10);
        }
    }

    private static String helloVersion(Path socket) {
        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(UnixDomainSocketAddress.of(socket));
            BufferedWriter w =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader r =
                    new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
            w.write(ProtoLifecycle.hello("probe"));
            w.write('\n');
            w.flush();
            String ack = r.readLine();
            if (ack == null || !EngineProtocol.HELLO_ACK.equals(EngineProtocol.typeOf(ack))) return null;
            return Jsonl.str(ack, "version");
        } catch (IOException e) {
            return null;
        }
    }

    private static String send(Path socket, String line) {
        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(UnixDomainSocketAddress.of(socket));
            BufferedWriter w =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader r =
                    new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
            w.write(line);
            w.write('\n');
            w.flush();
            return r.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * A -SNAPSHOT dev rebuild is the SAME version string with DIFFERENT content: the election
     * must treat a differing buildId as a skew (takeover), never as "already serving" — stale
     * dev engines once won these elections and kept serving old code. Empty buildIds fall back
     * to the version-string rule (release behavior, pinned by the same-version election test).
     */
    @Test
    void same_version_different_build_id_takes_over_instead_of_losing() throws Exception {
        Path state = shortTempDir();
        EnginePaths.Paths p = EnginePaths.resolve(state);

        EngineServer stale = new EngineServer(p, JkEngineConfig.DEFAULTS, null, "1.0.0-SNAPSHOT", "aaaaaaaaaaaa", null);
        CountDownLatch staleDone = new CountDownLatch(1);
        Thread staleT = new Thread(() -> {
            try {
                stale.run();
            } catch (IOException ignored) {
            } finally {
                staleDone.countDown();
            }
        });
        staleT.start();
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));
        assertThat(helloVersion(EnginePaths.activeSocket(p))).isEqualTo("1.0.0-SNAPSHOT");

        // Rebuilt dev engine: same version, different content identity — must WIN (take over).
        EngineServer rebuilt =
                new EngineServer(p, JkEngineConfig.DEFAULTS, null, "1.0.0-SNAPSHOT", "bbbbbbbbbbbb", null);
        Thread rebuiltT = new Thread(() -> {
            try {
                rebuilt.run();
            } catch (IOException ignored) {
            }
        });
        rebuiltT.start();
        try {
            assertThat(staleDone.await(15, TimeUnit.SECONDS))
                    .as("stale same-version engine is drained by the rebuilt one")
                    .isTrue();
            assertThat(helloVersion(EnginePaths.activeSocket(p))).isEqualTo("1.0.0-SNAPSHOT");
        } finally {
            rebuilt.close();
            rebuiltT.join(10_000);
        }
    }

    @Test
    void newer_engine_takes_over_and_the_displaced_one_drains() throws Exception {
        Path state = shortTempDir();
        EnginePaths.Paths p = EnginePaths.resolve(state);

        EngineServer old = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0.0-test", null);
        AtomicBoolean oldExited = new AtomicBoolean();
        CountDownLatch oldDone = new CountDownLatch(1);
        Thread oldT = new Thread(() -> {
            try {
                old.run();
            } catch (IOException ignored) {
            } finally {
                oldExited.set(true);
                oldDone.countDown();
            }
        });
        oldT.start();
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));
        Path firstSocket = EnginePaths.activeSocket(p);
        assertThat(helloVersion(firstSocket)).isEqualTo("1.0.0-test");

        // A newer engine starts: it must claim a fresh generation, repoint the endpoint, and
        // drain the old engine — which, idle, exits promptly. Nothing is killed.
        EngineServer newer = new EngineServer(p, JkEngineConfig.DEFAULTS, "2.0.0-test", null);
        Thread newT = new Thread(() -> {
            try {
                newer.run();
            } catch (IOException ignored) {
            }
        });
        newT.start();

        waitUntil(Duration.ofSeconds(10), () -> "2.0.0-test".equals(helloVersion(EnginePaths.activeSocket(p))));
        assertThat(EnginePaths.activeSocket(p)).isNotEqualTo(firstSocket);
        assertThat(oldDone.await(10, TimeUnit.SECONDS))
                .as("displaced engine drains and exits at idle")
                .isTrue();
        assertThat(oldExited).isTrue();

        // The survivor still serves via the repointed endpoint.
        assertThat(helloVersion(EnginePaths.activeSocket(p))).isEqualTo("2.0.0-test");
        newer.close();
        newT.join(10_000);
    }

    @Test
    void pid_file_mismatch_drains_a_ghost_engine() throws Exception {
        Path state = shortTempDir();
        EnginePaths.Paths p = EnginePaths.resolve(state);

        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0.0-test", null);
        CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                server.run();
            } catch (IOException ignored) {
            } finally {
                done.countDown();
            }
        });
        t.start();
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        Path pidFile = EnginePaths.pidFor(EnginePaths.activeSocket(p));
        assertThat(pidFile).exists();
        // Recreated state dir: successor overwrote the generation pid file. Filename still matches.
        Files.writeString(pidFile, "1\n");

        assertThat(done.await(10, TimeUnit.SECONDS))
                .as("engine whose pid file names another process yields and exits")
                .isTrue();
    }

    @Test
    void drain_closes_the_listener_while_a_plan_is_in_flight() throws Exception {
        Path state = shortTempDir();
        EnginePaths.Paths p = EnginePaths.resolve(state);

        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0.0-test", null);
        CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                server.run();
            } catch (IOException ignored) {
            } finally {
                done.countDown();
            }
        });
        t.start();
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));
        Path sock = EnginePaths.activeSocket(p);
        assertThat(server.claimPlanSlotForTests()).isTrue();

        String bye = send(sock, ProtoLifecycle.shutdown(false));
        assertThat(EngineProtocol.typeOf(bye)).isEqualTo(EngineProtocol.BYE);
        assertThat(Jsonl.bool(bye, "draining", false)).isTrue();

        waitUntil(Duration.ofSeconds(5), () -> helloVersion(sock) == null);
        assertThat(t.isAlive())
                .as("process stays up until in-flight plans finish")
                .isTrue();

        server.releasePlanSlotForTests();
        assertThat(done.await(10, TimeUnit.SECONDS))
                .as("engine exits once the last in-flight plan finishes")
                .isTrue();
    }

    @Test
    void displaced_engine_reports_drain_status_to_the_successor() throws Exception {
        Path state = shortTempDir();
        EnginePaths.Paths p = EnginePaths.resolve(state);

        EngineServer old = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0.0-test", null);
        CountDownLatch oldDone = new CountDownLatch(1);
        Thread oldT = new Thread(() -> {
            try {
                old.run();
            } catch (IOException ignored) {
            } finally {
                oldDone.countDown();
            }
        });
        oldT.start();
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));
        Path firstSocket = EnginePaths.activeSocket(p);
        assertThat(old.claimPlanSlotForTests()).isTrue();

        List<String> logs = Collections.synchronizedList(new ArrayList<>());
        EngineServer newer = new EngineServer(p, JkEngineConfig.DEFAULTS, "2.0.0-test", logs::add);
        Thread newT = new Thread(() -> {
            try {
                newer.run();
            } catch (IOException ignored) {
            }
        });
        newT.start();
        try {
            waitUntil(Duration.ofSeconds(10), () -> "2.0.0-test".equals(helloVersion(EnginePaths.activeSocket(p))));
            waitUntil(Duration.ofSeconds(5), () -> helloVersion(firstSocket) == null);
            waitUntil(Duration.ofSeconds(10), () -> logs.stream().anyMatch(s -> s.contains("is draining")));
            old.releasePlanSlotForTests();
            assertThat(oldDone.await(10, TimeUnit.SECONDS)).isTrue();
            waitUntil(Duration.ofSeconds(5), () -> logs.stream().anyMatch(s -> s.contains("finished draining")));
        } finally {
            newer.close();
            newT.join(10_000);
        }
    }

    @Test
    void displacement_watchdog_drains_an_engine_the_endpoint_no_longer_names(
            @org.junit.jupiter.api.io.TempDir Path unused) throws Exception {
        Path state = shortTempDir();
        EnginePaths.Paths p = EnginePaths.resolve(state);

        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0.0-test", null);
        CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                server.run();
            } catch (IOException ignored) {
            } finally {
                done.countDown();
            }
        });
        t.start();
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        // Simulate a takeover whose drain signal was lost: repoint the endpoint elsewhere.
        Files.writeString(EnginePaths.endpoint(p), p.key() + ".gen999.sock");

        // The watchdog (5s tick) notices and, idle, exits.
        assertThat(done.await(20, TimeUnit.SECONDS))
                .as("watchdog self-drains a displaced engine")
                .isTrue();
    }
}
