// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workspace member's deliverables are linked into the workspace's {@code target/} when that
 * member finishes. Its outputs live under {@code target/<module>/}, not under the module directory,
 * and the link step has to know that or it links nothing until the root itself completes.
 */
class WorkspaceArtifactsTest {

    @Test
    void a_member_s_native_client_is_linked_from_the_workspace_output_dir(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0"
                java = 25

                [workspace]
                modules = ["clients/cli"]
                """);
        Path cli = Files.createDirectories(root.resolve("clients/cli"));
        Files.writeString(cli.resolve("jk.toml"), """
                group = "com.example"
                name = "cli"
                version = "1.0"
                java = 25

                [application]
                main = "com.example.Main"

                [native]
                enabled = "always"
                name = "jk"
                """);
        Path built = root.resolve("target/clients/cli/jk");
        Files.createDirectories(built.getParent());
        Files.writeString(built, "native client");

        Map<Path, Path> links = WorkspaceArtifacts.computeLinks(List.of(cli), root);
        assertThat(links).containsEntry(built, root.resolve("target/jk"));

        WorkspaceArtifacts.linkModule(root, cli, links);
        assertThat(root.resolve("target/jk")).hasContent("native client");
    }
}
