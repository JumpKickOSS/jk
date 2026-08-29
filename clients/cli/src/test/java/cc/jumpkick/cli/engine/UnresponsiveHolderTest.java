// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.testing.ShortTempDirs;
import java.nio.file.Files;
import java.nio.file.Path;
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

        assertThat(EngineClient.unresponsiveHolderPid(socket))
                .as("no pid file → genuinely not running")
                .isZero();

        Process dead = SleepMain.spawn(0);
        dead.waitFor();
        Files.writeString(pidFile, Long.toString(dead.pid()));
        assertThat(EngineClient.unresponsiveHolderPid(socket))
                .as("a dead pid is a stale file, not a holder")
                .isZero();

        Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()));
        assertThat(EngineClient.unresponsiveHolderPid(socket))
                .as("this JVM is never a kill target (in-process engine tests share it)")
                .isZero();
    }

    @Test
    void a_live_silent_jvm_named_by_the_pid_file_is_the_holder() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        Process wedge = SleepMain.spawn(60_000);
        try {
            Files.writeString(EnginePaths.pidFor(socket), Long.toString(wedge.pid()));
            assertThat(EngineClient.unresponsiveHolderPid(socket)).isEqualTo(wedge.pid());
        } finally {
            wedge.destroyForcibly();
            wedge.waitFor();
        }
    }
}
