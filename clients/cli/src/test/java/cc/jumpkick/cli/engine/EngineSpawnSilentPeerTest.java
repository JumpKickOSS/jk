// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cli.engine.EngineSpawn.Patience;
import cc.jumpkick.cli.engine.EngineSpawn.Reachability;
import cc.jumpkick.host.Os;
import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.EngineTransport;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A handshake that goes unanswered is not proof of a wedged engine: a SerialGC engine mid-pause
 * during a large build accepts the connection and answers seconds later. Ensure therefore waits a
 * silent peer out with repeated probes, and displaces only one that stays silent through all of
 * them — and even then never hard-kills a pid that is not visibly a JVM.
 */
class EngineSpawnSilentPeerTest {

    private static final String VERSION = "9.9.9";

    /** Quick enough for a unit test; the shape (probe, back off, probe again) is what is under test. */
    private static final Patience QUICK = new Patience(200, 3, Duration.ofMillis(20));

    /** A young peer's grace at a unit test's scale: young for the whole test, waited out for half a second. */
    private static final SilentPeer.Grace BRIEF_GRACE = new SilentPeer.Grace(
            Duration.ofMinutes(5), Duration.ofMillis(500), Duration.ofMillis(100), Duration.ofSeconds(2));

    /** No startup grace at all: only workers or CPU can show life, and neither does a sleeping JVM. */
    private static final SilentPeer.Grace NO_GRACE =
            new SilentPeer.Grace(Duration.ZERO, Duration.ofMillis(200), Duration.ofMillis(100), Duration.ofSeconds(1));

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jks-");

    private @Nullable String forcedTransport;

    @BeforeEach
    void speakUnixToTheFake() {
        // The fake listens on a Unix socket, so the client has to speak that transport whatever
        // the tier forces for the engines it spawns; the property outranks the environment.
        forcedTransport = System.getProperty(EngineTransport.TRANSPORT_PROPERTY);
        System.setProperty(EngineTransport.TRANSPORT_PROPERTY, "unix");
    }

    @AfterEach
    void restoreTransport() {
        if (forcedTransport == null) System.clearProperty(EngineTransport.TRANSPORT_PROPERTY);
        else System.setProperty(EngineTransport.TRANSPORT_PROPERTY, forcedTransport);
    }

    private EnginePaths.Paths paths() throws IOException {
        EnginePaths.Paths paths = EnginePaths.resolve(tempDirs.create());
        Files.createDirectories(paths.dir());
        return paths;
    }

    @Test
    void a_peer_silent_on_the_first_probe_and_live_on_the_next_is_ensured_not_displaced() throws Exception {
        EnginePaths.Paths paths = paths();
        Path socket = EnginePaths.activeSocket(paths);
        Process holder = SleepMain.spawn(60_000);
        try (FakeEngine engine = new FakeEngine(socket, 1)) {
            Files.writeString(EnginePaths.pidFor(socket), Long.toString(holder.pid()));

            // The real ensure, with its real patience: one silent probe, one answered.
            EngineProbe.Handshake hs = EngineSpawn.ensure(paths, VERSION);

            assertThat(hs.version()).isEqualTo(VERSION);
            assertThat(engine.connections.get())
                    .as("one silent probe, then the answered re-probe")
                    .isEqualTo(2);
            assertThat(holder.isAlive())
                    .as("the engine named by the pid file is untouched")
                    .isTrue();
            assertThat(paths.log()).doesNotExist();
        } finally {
            holder.destroyForcibly();
            holder.waitFor();
        }
    }

    @Test
    void a_peer_silent_through_every_probe_is_reported_silent_only_after_the_last() throws Exception {
        EnginePaths.Paths paths = paths();
        Path socket = EnginePaths.activeSocket(paths);
        try (FakeEngine engine = new FakeEngine(socket, Integer.MAX_VALUE)) {
            Reachability reach = EngineSpawn.probePatiently(socket, VERSION, QUICK);

            assertThat(reach).isInstanceOf(Reachability.Silent.class);
            assertThat(engine.connections.get()).isEqualTo(QUICK.probes());
        }
    }

    @Test
    void a_peer_that_answers_late_within_the_patience_is_live() throws Exception {
        EnginePaths.Paths paths = paths();
        Path socket = EnginePaths.activeSocket(paths);
        try (FakeEngine engine = new FakeEngine(socket, QUICK.reprobes())) {
            Reachability reach = EngineSpawn.probePatiently(socket, VERSION, QUICK);

            assertThat(reach).isInstanceOf(Reachability.Live.class);
            assertThat(engine.connections.get()).isEqualTo(QUICK.probes());
        }
    }

    /**
     * The holder is a JVM that just started and is still silent through every probe: a coordinator
     * loading, or mid-build with its wire starved. Ensure leaves it alone and fails naming it —
     * displacing it would kill every job it runs for every other terminal.
     */
    @Test
    void a_young_peer_silent_through_every_probe_is_left_alone_not_displaced() throws Exception {
        EnginePaths.Paths paths = paths();
        Path socket = EnginePaths.activeSocket(paths);
        Process holder = SleepMain.spawn(120_000);
        try (FakeEngine engine = new FakeEngine(socket, Integer.MAX_VALUE)) {
            Files.writeString(EnginePaths.pidFor(socket), Long.toString(holder.pid()));

            assertThatThrownBy(() -> EngineSpawn.ensure(paths, VERSION, QUICK, BRIEF_GRACE))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("pid " + holder.pid())
                    .hasMessageContaining("not displaced");
            assertThat(holder.isAlive())
                    .as("a young engine that accepts connections is never displaced for a slow handshake")
                    .isTrue();
            assertThat(Files.readString(paths.log())).doesNotContain("displacing unresponsive engine");
            assertThat(engine.connections.get()).isGreaterThan(QUICK.probes());
        } finally {
            holder.destroyForcibly();
            holder.waitFor();
        }
    }

    /** Past its startup grace, a holder that runs no worker and burns no CPU shows no life, and is displaced. */
    @Test
    void a_peer_silent_through_every_probe_that_shows_no_life_is_displaced() throws Exception {
        EnginePaths.Paths paths = paths();
        Path socket = EnginePaths.activeSocket(paths);
        Process idle = SleepMain.spawn(120_000);
        try (FakeEngine ignored = new FakeEngine(socket, Integer.MAX_VALUE)) {
            Files.writeString(EnginePaths.pidFor(socket), Long.toString(idle.pid()));
            // Let the JVM's own start-up CPU settle, so two readings apart show no step.
            Thread.sleep(1_500);

            Reachability reach = EngineSpawn.waitOutSilentPeer(paths, socket, VERSION, QUICK, NO_GRACE);

            assertThat(reach).isInstanceOf(Reachability.Absent.class);
            assertThat(idle.waitFor(5, TimeUnit.SECONDS))
                    .as("the lifeless holder is gone")
                    .isTrue();
            assertThat(Files.readString(paths.log()))
                    .contains("displacing unresponsive engine (pid " + idle.pid() + ")");
        } finally {
            idle.destroyForcibly();
        }
    }

    @Test
    void displacing_a_silent_peer_hard_kills_the_jvm_named_by_the_pid_file() throws Exception {
        EnginePaths.Paths paths = paths();
        Path socket = EnginePaths.activeSocket(paths);
        Process wedged = SleepMain.spawn(60_000);
        try (FakeEngine ignored = new FakeEngine(socket, Integer.MAX_VALUE)) {
            Files.writeString(EnginePaths.pidFor(socket), Long.toString(wedged.pid()));

            EngineSpawn.displaceSilent(paths, socket);

            assertThat(wedged.waitFor(5, TimeUnit.SECONDS))
                    .as("the wedged JVM is gone")
                    .isTrue();
            assertThat(Files.readString(paths.log()))
                    .contains("displacing unresponsive engine (pid " + wedged.pid() + ")");
        } finally {
            wedged.destroyForcibly();
        }
    }

    @Test
    void displacing_a_silent_peer_never_kills_a_pid_that_is_not_a_jvm() throws Exception {
        EnginePaths.Paths paths = paths();
        Path socket = EnginePaths.activeSocket(paths);
        // A recycled pid: something live, visibly not java or jk, now wearing the engine's number.
        Process bystander = notAJvm();
        try (FakeEngine engine = new FakeEngine(socket, Integer.MAX_VALUE)) {
            Files.writeString(EnginePaths.pidFor(socket), Long.toString(bystander.pid()));

            EngineSpawn.displaceSilent(paths, socket);

            assertThat(bystander.isAlive())
                    .as("not a JVM, so not a kill target")
                    .isTrue();
            assertThat(engine.connections.get())
                    .as("the peer is asked to stop over the socket instead")
                    .isEqualTo(1);
            assertThat(Files.readString(paths.log())).contains("displacing unresponsive engine —");
        } finally {
            bystander.destroyForcibly();
        }
    }

    /**
     * Listens where an engine would. The first {@code silentFor} connections read the hello and
     * answer nothing until the client gives up; every later one answers a same-version hello-ack.
     */
    private static final class FakeEngine implements AutoCloseable {
        final AtomicInteger connections = new AtomicInteger();
        private final ServerSocketChannel server;
        private final int silentFor;

        FakeEngine(Path socket, int silentFor) throws IOException {
            this.silentFor = silentFor;
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            Thread.ofVirtual().start(this::accept);
        }

        private void accept() {
            try {
                while (true) {
                    SocketChannel c = server.accept();
                    boolean answer = connections.incrementAndGet() > silentFor;
                    Thread.ofVirtual().start(() -> serve(c, answer));
                }
            } catch (IOException closed) {
                // the test closed the server
            }
        }

        private static void serve(SocketChannel c, boolean answer) {
            try (c) {
                ByteBuffer request = ByteBuffer.allocate(4096);
                c.read(request);
                if (answer) {
                    String ack = ProtoLifecycle.helloAck(VERSION, 4242, 1L, false, null) + "\n";
                    c.write(ByteBuffer.wrap(ack.getBytes(StandardCharsets.UTF_8)));
                    return;
                }
                // Silent: hold the connection until the client's watchdog closes it.
                ByteBuffer drain = ByteBuffer.allocate(64);
                while (c.read(drain) >= 0) drain.clear();
            } catch (IOException ignored) {
                // the client gave up — that is the point
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    /** A live process whose command line is visibly not java or jk. */
    private static Process notAJvm() throws IOException {
        if (Os.isWindows()) {
            return new ProcessBuilder("cmd", "/c", "ping", "-n", "40", "127.0.0.1").start();
        }
        return new ProcessBuilder("sleep", "30").start();
    }
}
