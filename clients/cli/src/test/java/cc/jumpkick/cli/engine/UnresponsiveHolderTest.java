// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A silent socket probe is not proof of absence: a wedged process can hold the election's pid
 * file while never answering, and every fresh spawn then loses to it. {@code
 * unresponsiveHolderPid} is how stop/status see that process instead of reporting "not running".
 */
class UnresponsiveHolderTest {

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkw-");

    @Test
    void absent_stale_and_own_pid_files_are_not_holders() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        Path pidFile = EnginePaths.pidFor(socket);

        assertThat(EngineProcessControl.unresponsiveHolderPid(socket))
                .as("no pid file → genuinely not running")
                .isZero();

        Process dead = SleepMain.spawn(0);
        dead.waitFor();
        Files.writeString(pidFile, Long.toString(dead.pid()));
        assertThat(EngineProcessControl.unresponsiveHolderPid(socket))
                .as("a dead pid is a stale file, not a holder")
                .isZero();

        Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()));
        assertThat(EngineProcessControl.unresponsiveHolderPid(socket))
                .as("this JVM is never a kill target (in-process engine tests share it)")
                .isZero();
    }

    @Test
    void a_live_silent_engine_named_by_the_pid_file_is_the_holder() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        Process wedge = SleepMain.spawn(60_000, ENGINE_MAIN);
        try {
            Files.writeString(EnginePaths.pidFor(socket), Long.toString(wedge.pid()));
            assertThat(EngineProcessControl.unresponsiveHolderPid(socket)).isEqualTo(wedge.pid());
        } finally {
            wedge.destroyForcibly();
            wedge.waitFor();
        }
    }

    /** On the sleeper's command line, so it reads as a resident engine. */
    private static final String ENGINE_MAIN = "cc.jumpkick.engine.EngineMain";

    @Test
    void a_recycled_pid_is_no_holder_and_a_stop_leaves_it_alive() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        Path pidFile = EnginePaths.pidFor(socket);
        Process jvm = SleepMain.spawn(60_000);
        Process engineLike = null;
        try {
            Files.writeString(pidFile, Long.toString(jvm.pid()));
            // Only its command line tells this JVM from an engine. A host that cannot read one (a
            // Windows PowerShell query too slow under load) falls back to the pid file and a JVM
            // launcher, which the start-time check below is what keeps off recycled pids.
            if (!EngineFleet.commandLineOf(jvm.toHandle()).isBlank()) {
                assertThat(EngineProcessControl.unresponsiveHolderPid(socket))
                        .as("a JVM that is not an engine")
                        .isZero();
                assertThat(EngineProcessControl.forceStop(socket)).isTrue();
                assertThat(jvm.isAlive())
                        .as("nothing listening and no engine: the stop kills nothing")
                        .isTrue();
            }

            // An engine-looking process started well after the file was written took over its pid.
            engineLike = SleepMain.spawn(60_000, ENGINE_MAIN);
            Files.writeString(pidFile, Long.toString(engineLike.pid()));
            Files.setLastModifiedTime(pidFile, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
            assertThat(EngineProcessControl.unresponsiveHolderPid(socket)).isZero();
            assertThat(EngineProcessControl.forceStop(socket)).isTrue();
            assertThat(engineLike.isAlive()).isTrue();
        } finally {
            jvm.destroyForcibly();
            if (engineLike != null) engineLike.destroyForcibly();
        }
    }
}
