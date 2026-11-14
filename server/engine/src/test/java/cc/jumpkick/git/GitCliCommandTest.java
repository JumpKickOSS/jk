// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GitCliCommandTest {

    @Test
    void on_windows_git_runs_with_long_paths_on_whatever_the_users_config_says() {
        assertThat(GitCliExtension.command("git.exe", List.of("-c", "a=b"), List.of("clone", "x"), true))
                .containsExactly("git.exe", "-c", "core.longpaths=true", "-c", "a=b", "clone", "x");
    }

    @Test
    void elsewhere_git_runs_with_the_users_config_alone() {
        assertThat(GitCliExtension.command("git", List.of(), List.of("ls-remote", "u"), false))
                .containsExactly("git", "ls-remote", "u");
    }
}
