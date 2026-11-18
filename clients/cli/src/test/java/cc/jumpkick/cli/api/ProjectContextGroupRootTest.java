// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A command that needs a project ({@code jk tree}, {@code jk why}, {@code jk audit}) is refused at
 * a group root before it starts anything. Its lock refresh, started there, fails and writes run
 * reports into a {@code target/} at a root that never builds.
 */
class ProjectContextGroupRootTest {

    @Test
    void a_group_root_is_no_project(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), "[workspaces]\nmembers = [\"a\"]\n");
        Path member = Files.createDirectories(root.resolve("a"));
        Files.writeString(member.resolve("jk.toml"), "group = \"g\"\nname = \"a\"\nversion = \"1\"\njava = 25\n");

        assertThat(ProjectContext.require(root, "tree")).isEmpty();
        assertThat(root.resolve("target")).doesNotExist();
        assertThat(ProjectContext.require(member, "tree")).isPresent();
    }
}
