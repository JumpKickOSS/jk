// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.EngineJvmFlags;
import cc.jumpkick.testing.Await;
import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.DenyCheckRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A real engine JVM under the spawner's flag set and a tiny heap cap: a verb that allocates past
 * the cap ends the process with a heap dump beside its log, instead of leaving a silent peer.
 */
@Tag("integration")
class EngineOutOfMemoryExitTest {

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jko-");

    /** The heartbeat window: an exit the client would otherwise wait out. */
    private static final Duration HEARTBEAT = Duration.ofSeconds(30);

    @Test
    void a_capped_engine_exits_nonzero_and_dumps_its_heap_when_a_verb_allocates_past_the_cap() throws Exception {
        Path home = tempDirs.create();
        EnginePaths.Paths paths = EnginePaths.resolve(home.resolve("state"), home.resolve("store"));
        Files.createDirectories(paths.dir());
        Path dumpDir = EnginePaths.heapDumpDir(paths);

        // The deny-check verb reads the project's jk.toml whole; one three times the heap cannot fit.
        Path project = Files.createDirectories(home.resolve("proj"));
        try (RandomAccessFile manifest =
                new RandomAccessFile(project.resolve("jk.toml").toFile(), "rw")) {
            manifest.setLength(96L << 20);
        }

        Process engine = new ProcessBuilder(engineCommand(home, dumpDir))
                .directory(paths.dir().toFile())
                .redirectErrorStream(true)
                .redirectOutput(paths.log().toFile())
                .start();
        try {
            Await.until(Duration.ofSeconds(60), () -> engine.isAlive() && answersHello(paths));
            assertThat(engine.isAlive())
                    .as("the engine must boot inside the cap before it is driven past it")
                    .isTrue();

            try (EngineServerHarness.Client c = new EngineServerHarness.Client(EnginePaths.activeSocket(paths))) {
                c.sendLine(new DenyCheckRequest(project.toString()).encode());
                assertThat(engine.waitFor(HEARTBEAT.toSeconds(), TimeUnit.SECONDS))
                        .as("exits within the heartbeat window rather than serving on as a silent peer")
                        .isTrue();
            }

            assertThat(engine.exitValue()).isNotZero();
            Path dump = dumpDir.resolve("java_pid" + engine.pid() + ".hprof");
            assertThat(dump).as("one dump per exit, named by the JVM that died").isRegularFile();
            assertThat(Files.size(dump)).isPositive();
            assertThat(Files.readString(paths.log())).contains("OutOfMemoryError");
        } finally {
            engine.destroyForcibly();
        }
    }

    /** The spawner's JAR line on this test JVM's classpath, with a 32 MiB heap and a private home. */
    private static List<String> engineCommand(Path home, Path dumpDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(EngineJvmFlags.BASE);
        cmd.add(EngineJvmFlags.heapDumpPath(dumpDir));
        cmd.add("-Xmx32m");
        cmd.add("-Djk.env.JK_HOME=" + home);
        cmd.add("-Djk.env.JK_AUTO_WARMUP=false");
        cmd.add("-D" + OwnerWatchdog.PROPERTY + "=" + ProcessHandle.current().pid());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(EngineMain.class.getName());
        return cmd;
    }

    private static boolean answersHello(EnginePaths.Paths paths) {
        if (!Files.exists(EnginePaths.endpoint(paths))) return false;
        try (EngineServerHarness.Client c = new EngineServerHarness.Client(EnginePaths.activeSocket(paths))) {
            String ack = c.send(ProtoLifecycle.hello("oom-test"));
            return ack != null && EngineProtocol.HELLO_ACK.equals(EngineProtocol.typeOf(ack));
        } catch (IOException e) {
            return false;
        }
    }
}
