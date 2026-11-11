// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs({OS.LINUX, OS.MAC})
class ToolOutputTest {

    @Test
    void a_tool_that_never_exits_or_closes_its_output_times_out() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> assertThat(
                        ToolOutput.run(List.of("sh", "-c", "echo started; sleep 60"), Duration.ofMillis(500)))
                .isNull());
    }

    @Test
    void output_larger_than_the_pipe_buffer_is_read_whole() {
        String out =
                ToolOutput.run(List.of("sh", "-c", "head -c 200000 /dev/zero | tr '\\0' x"), Duration.ofSeconds(10));
        assertThat(out).hasSize(200_000);
    }

    @Test
    void a_non_zero_exit_is_no_answer() {
        assertThat(ToolOutput.run(List.of("sh", "-c", "echo missing; exit 1"), Duration.ofSeconds(10)))
                .isNull();
    }
}
