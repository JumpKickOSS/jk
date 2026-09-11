// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which reader a compile gets is decided from the manifests on disk — the question the engine
 * asks before it forks a workspace — so it needs no engine to answer here. Selection and rendering
 * ({@link PlanRun#resolve}, {@link PlanRun#run}) do talk to the engine and are covered by the
 * command's integration tests.
 */
class PlanRunTest {

    @Test
    void a_workspace_root_compiles_as_a_workspace(@TempDir Path root) throws IOException {
        workspace(root, "app", "lib");

        PlanRun.Entry entry = PlanRun.Entry.of(root);

        assertThat(entry.workspace()).isTrue();
        assertThat(entry.member()).isFalse();
        assertThat(entry.requestDir()).isEqualTo(root.toRealPath());
    }

    @Test
    void a_member_dir_compiles_as_the_workspace_it_belongs_to(@TempDir Path root) throws IOException {
        workspace(root, "app", "libs/core");

        PlanRun.Entry entry = PlanRun.Entry.of(root.resolve("libs/core"));

        assertThat(entry.workspace()).isTrue();
        assertThat(entry.member()).isTrue();
        assertThat(entry.requestDir()).isEqualTo(root.toRealPath());
    }

    @Test
    void a_standalone_project_compiles_as_one_plan(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), manifest("solo"));

        PlanRun.Entry entry = PlanRun.Entry.of(dir);

        assertThat(entry.workspace()).isFalse();
        assertThat(entry.member()).isFalse();
        assertThat(entry.requestDir()).isEqualTo(dir.toRealPath());
    }

    @Test
    void a_project_under_a_root_that_does_not_list_it_is_standalone(@TempDir Path root) throws IOException {
        workspace(root, "app");
        Path stray = root.resolve("tools/bench");
        Files.createDirectories(stray);
        Files.writeString(stray.resolve("jk.toml"), manifest("bench"));

        PlanRun.Entry entry = PlanRun.Entry.of(stray);

        assertThat(entry.workspace()).isFalse();
        assertThat(entry.requestDir()).isEqualTo(stray.toRealPath());
    }

    private static void workspace(Path root, String... modules) throws IOException {
        StringBuilder toml = new StringBuilder(manifest("ws")).append("\n[workspace]\nmodules = [");
        for (int i = 0; i < modules.length; i++) {
            if (i > 0) toml.append(", ");
            toml.append('"').append(modules[i]).append('"');
        }
        Files.writeString(root.resolve("jk.toml"), toml.append("]\n").toString());
        for (String module : modules) {
            Path dir = root.resolve(module);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("jk.toml"), manifest(dir.getFileName().toString()));
        }
    }

    private static String manifest(String name) {
        return "name = \"" + name + "\"\ngroup = \"g\"\nversion = \"0.1.0\"\n";
    }
}
