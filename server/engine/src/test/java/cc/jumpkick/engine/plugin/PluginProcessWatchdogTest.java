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
        // LIVENESS, not performance: the watchdog was given 400ms and the child would otherwise
        // hold the job open indefinitely. 15s is ~37x the watchdog on purpose; a tight budget here
        // would be a measurement of this machine.
        assertThat(Duration.between(start, Instant.now()))
                .as("the 400ms watchdog fired instead of the job wedging")
                .isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void an_orphan_holding_stdout_does_not_wedge_the_job() throws Exception {
        Path sh = Path.of("/bin/sh");
        assumeTrue(Files.isExecutable(sh), "POSIX shell required");

        // The shell exits at once, leaving a backgrounded sleep that inherited the stdout write
        // end. Reparented on Linux, it is invisible to descendants() and produces no EOF — the
        // root-exit drain grace must unwedge the conversation instead of blocking until the
        // orphan dies. No idle timeout: this is the non-watchdog path.
        Instant start = Instant.now();
        int exit = PluginProcess.converse(
                List.of(sh.toString(), "-c", "echo '##JKT:{\"event\":\"hello\"}'; sleep 30 & exit 0"),
                Map.of(),
                null,
                "##JKT:",
                (json, convo) -> {},
                null,
                false,
                0L);

        assertThat(exit).isZero();
        // LIVENESS, not performance: with no watchdog (0L) the guard being tested is that an orphan
        // holding stdout cannot wedge the job. 20s is a hang detector, not a budget.
        assertThat(Duration.between(start, Instant.now()))
                .as("the job finished rather than wedging on the orphan's stdout handle")
                .isLessThan(Duration.ofSeconds(20));
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
