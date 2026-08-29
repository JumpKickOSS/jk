// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.SleepMain;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.testing.Await;
import cc.jumpkick.testing.ShortTempDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The silent-peer stop path: a process holding this directory's election state without answering
 * its socket used to get a cheerful "not running" from {@code jk engine stop} — while every fresh
 * spawn kept losing the election to it. Stop now kills the holder and confirms it went.
 */
class EngineStopWedgeTest {

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jks-");

    private String prevState;

    @BeforeEach
    void isolateState() throws Exception {
        prevState = System.getProperty("jk.env.JK_STATE_DIR");
        System.setProperty(
                "jk.env.JK_STATE_DIR",
                Files.createDirectories(tempDirs.create()).toString());
    }

    @AfterEach
    void restoreState() {
        if (prevState == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", prevState);
    }

    @Test
    void stop_kills_a_holder_that_never_answers_its_socket() throws Exception {
        EnginePaths.Paths paths = EnginePaths.current();
        Files.createDirectories(paths.dir());
        String sockName = paths.key() + ".gen1.sock";
        Files.writeString(EnginePaths.endpoint(paths), sockName);
        Path socket = paths.dir().resolve(sockName);
        Process wedge = SleepMain.spawn(60_000);
        try {
            Files.writeString(EnginePaths.pidFor(socket), Long.toString(wedge.pid()));

            int exit = new EngineStopCommand().run(Invocation.builder().build());

            assertThat(exit).isEqualTo(Exit.SUCCESS);
            Await.until(Duration.ofSeconds(10), () -> !wedge.isAlive());
            assertThat(wedge.isAlive())
                    .as("the unresponsive holder must be killed, not reported 'not running'")
                    .isFalse();
        } finally {
            wedge.destroyForcibly();
            wedge.waitFor();
        }
    }

    @Test
    void stop_with_nothing_holding_the_state_is_still_a_clean_not_running() throws Exception {
        EnginePaths.Paths paths = EnginePaths.current();
        Files.createDirectories(paths.dir());

        assertThat(new EngineStopCommand().run(Invocation.builder().build())).isEqualTo(Exit.SUCCESS);
    }
}
