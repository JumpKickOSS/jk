// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GitCommandTest {

    @Test
    void on_windows_every_command_runs_with_long_paths_on_whatever_the_users_config_says() {
        assertThat(GitCommand.of("git.exe", List.of("clone", "x"), true))
                .containsExactly("git.exe", "-c", "core.longpaths=true", "clone", "x");
    }

    @Test
    void elsewhere_a_command_is_git_and_its_arguments() {
        assertThat(GitCommand.of("git", List.of("ls-remote", "u"), false)).containsExactly("git", "ls-remote", "u");
    }

    @Test
    void a_command_is_a_fresh_list_the_caller_may_extend() {
        List<String> cmd = GitCommand.of("status");
        cmd.add("--short");
        assertThat(cmd).endsWith("status", "--short");
    }
}
