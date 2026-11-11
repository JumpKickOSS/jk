// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs({OS.LINUX, OS.MAC})
class ProcessesTest {

    private static List<String> sh(String script) {
        return List.of("sh", "-c", script);
    }

    @Test
    void a_child_that_never_exits_or_closes_its_output_times_out_and_is_killed() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            Process child = new ProcessBuilder(sh("echo started; sleep 60"))
                    .redirectErrorStream(true)
                    .start();
            Processes.Captured c = Processes.capture(child, Duration.ofMillis(500));
            assertThat(c.timedOut()).isTrue();
            assertThat(c.ok()).isFalse();
            assertThat(c.exit()).isEqualTo(-1);
            assertThat(child.waitFor(5, TimeUnit.SECONDS))
                    .as("killed at the deadline")
                    .isTrue();
        });
    }

    /** The child exits, but the grandchild it left behind keeps the pipe open past the deadline. */
    @Test
    void a_descendant_holding_the_pipe_does_not_hold_the_caller() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            Processes.Captured c = Processes.capture(sh("sleep 5 & echo done"), Duration.ofSeconds(1));
            assertThat(c.timedOut()).isFalse();
            assertThat(c.exit()).isZero();
            assertThat(c.output().strip()).isEqualTo("done");
        });
    }

    @Test
    void output_if_ok_is_no_answer_on_a_failure_or_a_timeout() {
        assertThat(Processes.outputIfOk(sh("echo yes"), Duration.ofSeconds(10))).isEqualTo("yes\n");
        assertThat(Processes.outputIfOk(sh("echo missing; exit 1"), Duration.ofSeconds(10)))
                .isNull();
        assertThat(Processes.outputIfOk(sh("echo started; sleep 60"), Duration.ofMillis(300)))
                .isNull();
        assertThat(Processes.outputIfOk(List.of("/nonexistent/jk-no-such-tool"), Duration.ofSeconds(10)))
                .isNull();
    }

    @Test
    void output_larger_than_the_pipe_buffer_is_read_whole() throws IOException {
        Processes.Captured c = Processes.capture(sh("head -c 200000 /dev/zero | tr '\\0' x"), Duration.ofSeconds(10));
        assertThat(c.ok()).isTrue();
        assertThat(c.output()).hasSize(200_000);
    }

    @Test
    void the_limit_keeps_the_head_and_still_drains_the_rest() throws IOException {
        Process child = new ProcessBuilder(sh("head -c 200000 /dev/zero | tr '\\0' x")).start();
        Processes.Captured c = Processes.capture(child, null, Duration.ofSeconds(10), 4096);
        assertThat(c.ok()).isTrue();
        assertThat(c.output()).hasSize(4096);
    }

    @Test
    void stdin_is_written_and_stderr_kept_apart() throws IOException {
        Process child = new ProcessBuilder(sh("cat; echo oops >&2; exit 3")).start();
        Processes.Captured c =
                Processes.capture(child, "secret".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(10), 1 << 20);
        assertThat(c.exit()).isEqualTo(3);
        assertThat(c.output()).isEqualTo("secret");
        assertThat(c.error().strip()).isEqualTo("oops");
    }
}
