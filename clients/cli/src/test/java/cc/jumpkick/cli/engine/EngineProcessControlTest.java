// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The four rules of engine process control whose failure modes are severe: the pid the CLI itself
 * runs as is never a kill target, a live pid that is not a JVM is not a holder, a Ctrl-C inside the
 * kill grace window escalates at once, and stop / drain report an unreachable engine as a no-op.
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
                .as("-1: nothing to drain")
                .isEqualTo(-1);

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
            assertThat(EngineProcessControl.drain(socket)).isEqualTo(-1);
            answering.interrupt();
        } finally {
            if (forced == null) System.clearProperty(EngineTransport.TRANSPORT_PROPERTY);
            else System.setProperty(EngineTransport.TRANSPORT_PROPERTY, forced);
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
        if (Os.isWindows()) {
            return new ProcessBuilder("cmd", "/c", "ping", "-n", "40", "127.0.0.1").start();
        }
        return new ProcessBuilder("sleep", "30").start();
    }
}
