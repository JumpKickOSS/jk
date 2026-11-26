// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.forge.ForgeKind;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk auth login} asks GitHub for release writes only in a project that releases there. */
class AuthLoginScopeTest {

    @Test
    void github_asks_for_repo_only_when_the_project_releases_to_github() {
        assertThat(AuthLoginCommand.defaultScope(ForgeKind.GITHUB, false)).isEqualTo("read:packages");
        assertThat(AuthLoginCommand.defaultScope(ForgeKind.GITHUB, true)).isEqualTo("read:packages repo");
        assertThat(AuthLoginCommand.defaultScope(ForgeKind.GITLAB, true)).isEqualTo("read_api");
    }

    @Test
    void the_workspace_roots_publish_release_decides(@TempDir Path dir) throws Exception {
        assertThat(AuthLoginCommand.releasesToGitHub(dir)).as("no jk.toml").isFalse();

        Files.writeString(dir.resolve("jk.toml"), "name = \"app\"\n\n[publish]\nrelease = \"s3://acme/app/\"\n");
        assertThat(AuthLoginCommand.releasesToGitHub(dir)).isFalse();

        Files.writeString(dir.resolve("jk.toml"), "name = \"app\"\n\n[publish]\nrelease = \"github\"\n");
        assertThat(AuthLoginCommand.releasesToGitHub(dir)).isTrue();
    }
}
