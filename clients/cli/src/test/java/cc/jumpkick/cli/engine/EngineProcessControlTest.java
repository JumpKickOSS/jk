// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.testing.Sleepers;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.EngineTransport;
import cc.jumpkick.wire.protocol.EngineProtocol;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The four rules of engine process control whose failure modes are severe: the pid the CLI itself
 * runs as is never a kill target, a live pid that is not a JVM is not a holder, a Ctrl-C inside the
 * kill grace window escalates at once, and stop / drain report an unreachable engine as a no-op.
 * And a drain's missing ack is not a missing engine: the status says whether it is draining.
 */
class EngineProcessControlTest {

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkp-");

    @Test
    void force_stop_and_the_death_wait_never_target_the_current_jvm() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        Files.writeString(
                EnginePaths.pidFor(socket),
                Long.toString(ProcessHandle.current().pid()));

        assertThat(EngineProcessControl.forceStop(socket))
                .as("nothing listening is a successful stop, and this JVM is not waited on or killed")
                .isTrue();
        EngineProcessControl.waitForDeathOrKill(ProcessHandle.current().pid(), Duration.ofMillis(50));
        assertThat(ProcessHandle.current().isAlive()).isTrue();
    }

    @Test
    void a_live_pid_whose_command_is_not_a_jvm_is_not_a_holder() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        Process sleeper = notAJvm();
        try {
            Files.writeString(EnginePaths.pidFor(socket), Long.toString(sleeper.pid()));
            assertThat(EngineProcessControl.unresponsiveHolderPid(socket))
                    .as("a recycled pid running something visibly not java or jk must never get the hard kill")
                    .isZero();
        } finally {
            sleeper.destroyForcibly();
        }
    }

    @Test
    void hard_kill_escalates_to_sigkill_at_once_when_the_caller_is_interrupted() throws Exception {
        // Ignores SIGTERM, so only the escalation ends it; without the interrupt rule that is 30 s away.
        Process stubborn = new ProcessBuilder("bash", "-c", "trap '' TERM; sleep 60").start();
        try {
            long start = System.nanoTime();
            Thread.currentThread().interrupt();
            try {
                EngineProcessControl.hardKill(stubborn.pid());
            } finally {
                Thread.interrupted();
            }
            assertThat(stubborn.waitFor(5, TimeUnit.SECONDS))
                    .as("SIGKILL landed")
                    .isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        } finally {
            stubborn.destroyForcibly();
        }
    }

    @Test
    void stop_and_drain_treat_an_unreachable_engine_as_a_no_op_and_a_wrong_answer_as_failure() throws Exception {
        Path nothing = tempDirs.create().resolve("gen1.sock");
        assertThat(EngineProcessControl.stop(nothing))
                .as("stopping nothing is success")
                .isTrue();
        assertThat(EngineProcessControl.drain(nothing))
                .as("nothing to drain")
                .isEqualTo(new EngineProcessControl.Drain.Unreachable());

        // The stub listens on a Unix socket, so the client has to speak that transport whatever the
        // tier forces for the engines it spawns; the property outranks the environment for this JVM.
        Path socket = tempDirs.create().resolve("gen1.sock");
        String forced = System.getProperty(EngineTransport.TRANSPORT_PROPERTY);
        System.setProperty(EngineTransport.TRANSPORT_PROPERTY, "unix");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            Thread answering = Thread.ofVirtual().start(() -> answerEveryRequestWith(server, ProtoLifecycle.pong()));
            assertThat(EngineProcessControl.stop(socket))
                    .as("reachable but no bye is a real problem, not an already-stopped engine")
                    .isFalse();
            assertThat(EngineProcessControl.drain(socket))
                    .as("reachable but no bye is not a missing engine")
                    .isEqualTo(new EngineProcessControl.Drain.NoAck());
            answering.interrupt();
        } finally {
            if (forced == null) System.clearProperty(EngineTransport.TRANSPORT_PROPERTY);
            else System.setProperty(EngineTransport.TRANSPORT_PROPERTY, forced);
        }
    }

    /** Quick enough for a unit test; the shape (ask, back off, read the status, ask again) is what is under test. */
    private static final EngineProbe.Patience QUICK = new EngineProbe.Patience(200, 2, Duration.ofMillis(20));

    /** For an engine that never answers: the reply wait only has to elapse, so it can be short. */
    private static final EngineProbe.Patience SILENT = new EngineProbe.Patience(50, 2, Duration.ofMillis(20));

    private static final String VERSION = "9.9.9";

    @Test
    void a_drain_unanswered_in_time_is_draining_once_the_status_reports_jobs() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        try (ScriptedEngine engine = new ScriptedEngine(socket, (type, connection) -> switch (type) {
            case EngineProtocol.HELLO -> ScriptedEngine.helloAck(VERSION, 4242);
            case EngineProtocol.STATUS -> ScriptedEngine.statusAck(VERSION, 4242, 2, false);
            default -> null; // the bye lands after the watchdog: a GC pause mid-build
        })) {
            assertThat(EngineProcessControl.drainPatiently(socket, QUICK))
                    .isEqualTo(new EngineProcessControl.GracefulStop.Draining(2));
            assertThat(engine.received(EngineProtocol.SHUTDOWN))
                    .as("the request is in the engine's buffer; it is not repeated at a busy engine")
                    .isEqualTo(1);
        }
    }

    @Test
    void a_drain_unanswered_with_a_drain_already_underway_is_exiting() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        try (ScriptedEngine engine = new ScriptedEngine(socket, (type, connection) -> switch (type) {
            case EngineProtocol.HELLO -> ScriptedEngine.helloAck(VERSION, 4242);
            case EngineProtocol.STATUS -> ScriptedEngine.statusAck(VERSION, 4242, 0, true);
            default -> null;
        })) {
            assertThat(EngineProcessControl.drainPatiently(socket, QUICK))
                    .isEqualTo(new EngineProcessControl.GracefulStop.Exiting());
        }
    }

    @Test
    void an_idle_engine_that_never_saw_the_request_is_asked_again() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        try (ScriptedEngine engine = new ScriptedEngine(socket, (type, connection) -> switch (type) {
            case EngineProtocol.HELLO -> ScriptedEngine.helloAck(VERSION, 4242);
            case EngineProtocol.STATUS -> ScriptedEngine.statusAck(VERSION, 4242, 0, false);
            case EngineProtocol.SHUTDOWN -> connection == 1 ? null : ProtoLifecycle.bye(0, true);
            default -> null;
        })) {
            assertThat(EngineProcessControl.drainPatiently(socket, QUICK))
                    .isEqualTo(new EngineProcessControl.GracefulStop.Exiting());
            assertThat(engine.received(EngineProtocol.SHUTDOWN)).isEqualTo(2);
        }
    }

    @Test
    void an_engine_silent_through_every_round_is_reported_silent_not_absent() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        try (ScriptedEngine engine = ScriptedEngine.silent(socket)) {
            assertThat(EngineProcessControl.drainPatiently(socket, SILENT))
                    .isEqualTo(new EngineProcessControl.GracefulStop.Silent());
            assertThat(engine.received(EngineProtocol.SHUTDOWN)).isEqualTo(SILENT.probes());
        }
    }

    private static void answerEveryRequestWith(ServerSocketChannel server, String reply) {
        try {
            while (true) {
                try (SocketChannel c = server.accept()) {
                    c.read(ByteBuffer.allocate(4096));
                    c.write(ByteBuffer.wrap((reply + "\n").getBytes(StandardCharsets.UTF_8)));
                }
            }
        } catch (IOException e) {
            // server closed by the test
        }
    }

    /** A live process whose command line is visibly not java or jk. */
    private static Process notAJvm() throws IOException {
        return Sleepers.sleeper(30).start();
    }
}
