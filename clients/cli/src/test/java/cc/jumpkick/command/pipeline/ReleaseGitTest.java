// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk release}'s git runner: a command that floods stderr before it finishes stdout still returns. */
class ReleaseGitTest {

    /**
     * A hook that writes more to stderr than a pipe holds before it closes stdout blocks on the full
     * pipe until someone reads it; a runner that reads stdout to the end first never returns.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_command_that_floods_stderr_before_stdout_ends_returns(@TempDir Path dir) {
        ReleaseCommand.Git git = new ReleaseCommand.Git(dir);

        String out = assertTimeoutPreemptively(
                Duration.ofSeconds(30),
                () -> git.output("-c", "alias.noisy=!yes x | head -c 8000000 >&2; echo done", "noisy"));

        assertThat(out).isEqualTo("done");
    }
}
