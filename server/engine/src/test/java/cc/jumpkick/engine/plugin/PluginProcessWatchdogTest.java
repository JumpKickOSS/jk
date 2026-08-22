// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The converse inactivity watchdog: a child that goes silent past the window is force-killed
 * (including descendants that would otherwise keep the stdout pipe open) and the conversation
 * returns its non-zero exit instead of blocking forever.
 */
class PluginProcessWatchdogTest {

    @Test
    void a_silent_child_is_killed_after_the_idle_window() throws Exception {
        Path sh = Path.of("/bin/sh");
        assumeTrue(Files.isExecutable(sh), "POSIX shell required");

        Instant start = Instant.now();
        // sleep is a child of sh and inherits stdout; killing only the shell would leave
        // readLine blocked until sleep exits — the watchdog must tear down the whole tree.
        int exit = PluginProcess.converse(
                List.of(sh.toString(), "-c", "echo '##JKT:{\"event\":\"hello\"}'; sleep 30"),
                Map.of(),
                null,
                "##JKT:",
                (json, convo) -> {},
                null,
                false,
                400L);

        assertThat(exit).isNotZero();
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void a_chatty_child_is_left_alone() throws Exception {
        Path sh = Path.of("/bin/sh");
        assumeTrue(Files.isExecutable(sh), "POSIX shell required");

        int exit = PluginProcess.converse(
                List.of(sh.toString(), "-c", "for i in 1 2 3 4; do echo '##JKT:{\"event\":\"tick\"}'; sleep 0.2; done"),
                Map.of(),
                null,
                "##JKT:",
                (json, convo) -> {},
                null,
                false,
                600L);

        assertThat(exit).isZero();
    }
}
