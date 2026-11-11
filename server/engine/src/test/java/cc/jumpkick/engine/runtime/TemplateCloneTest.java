// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs({OS.LINUX, OS.MAC})
class TemplateCloneTest {

    @Test
    void a_clone_that_never_exits_or_closes_its_output_times_out() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> assertThatThrownBy(
                        () -> TemplateClone.run(List.of("sh", "-c", "echo started; sleep 60"), Duration.ofMillis(500)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out"));
    }

    @Test
    void git_runs_without_terminal_prompts() throws IOException {
        TemplateClone.Result result =
                TemplateClone.run(List.of("sh", "-c", "echo prompt=$GIT_TERMINAL_PROMPT"), Duration.ofSeconds(10));
        assertThat(result.exit()).isZero();
        assertThat(result.output().strip()).isEqualTo("prompt=0");
    }
}
