// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.engine.ScriptedEngine;
import cc.jumpkick.cli.engine.SilentPeer;
import cc.jumpkick.cli.engine.SleepMain;
import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The doctor's engine row gives a silent socket its patience, and then tells a busy coordinator
 * — a process that shows life — from a wedged one.
 */
class DoctorEngineCheckTest {

    private static final String VERSION = "9.9.9";

    private static final EngineProbe.Patience QUICK = new EngineProbe.Patience(200, 2, Duration.ofMillis(20));

    private static final SilentPeer.Grace YOUNG = new SilentPeer.Grace(
            Duration.ofMinutes(5), Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofSeconds(1));

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkd-");

    @Test
    void an_engine_that_answers_late_within_the_patience_is_running() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        try (ScriptedEngine ignored = new ScriptedEngine(socket, (type, connection) -> {
            if (connection == 1) return null;
            return EngineProtocol.HELLO.equals(type)
                    ? ScriptedEngine.helloAck(VERSION, 4242)
                    : ScriptedEngine.statusAck(VERSION, 4242, 1, true);
        })) {
            DoctorCommand.Check check = DoctorCommand.checkEngine(socket, QUICK, YOUNG);

            assertThat(check.status()).isEqualTo(DoctorCommand.Status.OK);
            assertThat(check.detail()).contains("running pid 4242").contains("draining 1 job");
        }
    }

    @Test
    void a_silent_engine_whose_process_shows_life_is_busy_not_wedged() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        Process holder = SleepMain.spawn(60_000);
        try (ScriptedEngine ignored = ScriptedEngine.silent(socket)) {
            Files.writeString(EnginePaths.pidFor(socket), Long.toString(holder.pid()));

            DoctorCommand.Check check = DoctorCommand.checkEngine(socket, QUICK, YOUNG);

            assertThat(check.status()).isEqualTo(DoctorCommand.Status.WARN);
            assertThat(check.detail())
                    .contains("pid " + holder.pid() + " alive and busy")
                    .contains("through " + QUICK.probes() + " probes")
                    .doesNotContain("wedged");
            assertThat(holder.isAlive()).isTrue();
        } finally {
            holder.destroyForcibly();
            holder.waitFor();
        }
    }

    @Test
    void a_silent_socket_with_no_live_holder_is_likely_wedged() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        try (ScriptedEngine ignored = ScriptedEngine.silent(socket)) {
            DoctorCommand.Check check = DoctorCommand.checkEngine(socket, QUICK, YOUNG);

            assertThat(check.status()).isEqualTo(DoctorCommand.Status.FAIL);
            assertThat(check.detail()).contains("likely wedged").contains("jk engine stop -n");
        }
    }

    @Test
    void nothing_listening_is_not_running() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");

        DoctorCommand.Check check = DoctorCommand.checkEngine(socket, QUICK, YOUNG);

        assertThat(check.status()).isEqualTo(DoctorCommand.Status.WARN);
        assertThat(check.detail()).contains("not running");
    }
}
