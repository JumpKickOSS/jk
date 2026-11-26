// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.forge.ForgeKind;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk auth login} asks a forge for release writes only in a project that releases there. */
class AuthLoginScopeTest {

    @Test
    void each_forge_asks_for_release_writes_only_when_the_project_releases_there() {
        assertThat(AuthLoginCommand.defaultScope(ForgeKind.GITHUB, false)).isEqualTo("read:packages");
        assertThat(AuthLoginCommand.defaultScope(ForgeKind.GITHUB, true)).isEqualTo("read:packages repo");
        assertThat(AuthLoginCommand.defaultScope(ForgeKind.GITLAB, false)).isEqualTo("read_api");
        assertThat(AuthLoginCommand.defaultScope(ForgeKind.GITLAB, true)).isEqualTo("api");
        assertThat(AuthLoginCommand.defaultScope(ForgeKind.GITEA, false)).isEqualTo("read:package");
        assertThat(AuthLoginCommand.defaultScope(ForgeKind.GITEA, true)).isEqualTo("read:package write:repository");
    }

    @Test
    void the_workspace_roots_publish_release_decides(@TempDir Path dir) throws Exception {
        assertThat(AuthLoginCommand.releasesTo(dir)).as("no jk.toml").isEmpty();

        Files.writeString(dir.resolve("jk.toml"), "name = \"app\"\n\n[publish]\nrelease = \"s3://acme/app/\"\n");
        assertThat(AuthLoginCommand.releasesTo(dir)).isEmpty();

        Files.writeString(dir.resolve("jk.toml"), "name = \"app\"\n\n[publish]\nrelease = \"github\"\n");
        assertThat(AuthLoginCommand.releasesTo(dir)).contains(ForgeKind.GITHUB);

        Files.writeString(dir.resolve("jk.toml"), "name = \"app\"\n\n[publish]\nrelease = \"gitlab\"\n");
        assertThat(AuthLoginCommand.releasesTo(dir)).contains(ForgeKind.GITLAB);

        Files.writeString(dir.resolve("jk.toml"), "name = \"app\"\n\n[publish]\nrelease = \"forgejo\"\n");
        assertThat(AuthLoginCommand.releasesTo(dir)).contains(ForgeKind.GITEA);
    }
}
