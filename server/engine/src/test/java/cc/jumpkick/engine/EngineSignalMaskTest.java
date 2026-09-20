// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.EngineJvmFlags;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.testing.Await;
import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * An engine spawned from a shell that ignores SIGINT and SIGHUP — a background job, {@code nohup}
 * — must not keep the ignore: every worker and test JVM it forks would inherit it, and their
 * Ctrl-C would be a no-op. Linux only: the disposition mask is read from {@code /proc}.
 */
@Tag("integration")
@EnabledOnOs(OS.LINUX)
class EngineSignalMaskTest {

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jks-");

    /** {@code SigIgn} bits: bit 0 is SIGHUP (1), bit 1 is SIGINT (2). */
    private static final long HUP_AND_INT = 0b11;

    @Test
    void an_engine_started_with_int_and_hup_ignored_resets_both_before_serving() throws Exception {
        // Control: the harness really does hand SIG_IGN down through exec.
        Process control = new ProcessBuilder(ignoringShell(List.of("cat", "/proc/self/status")))
                .redirectErrorStream(true)
                .start();
        String controlStatus = new String(control.getInputStream().readAllBytes());
        control.waitFor();
        assertThat(sigIgn(controlStatus) & HUP_AND_INT)
                .as("the control child inherits the shell's ignore of HUP and INT")
                .isEqualTo(HUP_AND_INT);

        Path home = tempDirs.create();
        EnginePaths.Paths paths = EnginePaths.resolve(home.resolve("state"), home.resolve("store"));
        Files.createDirectories(paths.dir());
        Process engine = new ProcessBuilder(ignoringShell(engineCommand(home)))
                .directory(paths.dir().toFile())
                .redirectErrorStream(true)
                .redirectOutput(paths.log().toFile())
                .start();
        try {
            Await.until(Duration.ofSeconds(60), () -> engine.isAlive() && answersHello(paths));
            String status = Files.readString(Path.of("/proc", Long.toString(engine.pid()), "status"));
            assertThat(sigIgn(status) & HUP_AND_INT)
                    .as(
                            "the serving engine ignores neither HUP nor INT: %s",
                            status.lines()
                                    .filter(l -> l.startsWith("SigIgn"))
                                    .findFirst()
                                    .orElse("?"))
                    .isZero();
            try (EngineServerHarness.Client c = new EngineServerHarness.Client(EnginePaths.activeSocket(paths))) {
                c.send(ProtoLifecycle.hello("signal-test"));
                String ack = c.send(ProtoLifecycle.statusRequest());
                assertThat(EngineProtocol.typeOf(ack)).isEqualTo(EngineProtocol.STATUS_ACK);
                assertThat(Jsonl.str(ack, "ignoredSignals"))
                        .as("status reports a clear mask")
                        .isEmpty();
            }
            assertThat(Files.readString(paths.log()))
                    .as("the reset is logged so a post-mortem can see the shell's mask")
                    .contains("ignored HUP, INT");
        } finally {
            engine.destroyForcibly();
        }
    }

    /** {@code sh -c 'trap "" INT HUP; exec "$@"' sh <command…>}: the command runs with both ignored. */
    private static List<String> ignoringShell(List<String> command) {
        List<String> sh = new ArrayList<>(List.of("sh", "-c", "trap '' INT HUP; exec \"$@\"", "sh"));
        sh.addAll(command);
        return sh;
    }

    /** The spawner's JAR line on this test JVM's classpath with a private home. */
    private static List<String> engineCommand(Path home) {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(EngineJvmFlags.BASE);
        cmd.add(EngineJvmFlags.heapDumpPath(home.resolve("dumps")));
        cmd.add("-Xmx64m");
        cmd.add("-Djk.env.JK_HOME=" + home);
        cmd.add("-Djk.env.JK_AUTO_WARMUP=false");
        cmd.add("-D" + OwnerWatchdog.PROPERTY + "=" + ProcessHandle.current().pid());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(EngineMain.class.getName());
        return cmd;
    }

    private static long sigIgn(String procStatus) {
        return procStatus
                .lines()
                .filter(l -> l.startsWith("SigIgn:"))
                .map(l -> Long.parseUnsignedLong(l.substring("SigIgn:".length()).trim(), 16))
                .findFirst()
                .orElseThrow();
    }

    private static boolean answersHello(EnginePaths.Paths paths) {
        if (!Files.exists(EnginePaths.endpoint(paths))) return false;
        try (EngineServerHarness.Client c = new EngineServerHarness.Client(EnginePaths.activeSocket(paths))) {
            String ack = c.send(ProtoLifecycle.hello("signal-test"));
            return ack != null && EngineProtocol.HELLO_ACK.equals(EngineProtocol.typeOf(ack));
        } catch (IOException e) {
            return false;
        }
    }
}
