// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleSelectionTest {

    @Test
    void expands_braces_and_commas() {
        assertThat(ModuleSelection.expandSpec("{api,worker}")).containsExactly("api", "worker");
        assertThat(ModuleSelection.expandSpec("a,b")).containsExactly("a", "b");
        assertThat(ModuleSelection.expandSpec("libs/{core,util}")).containsExactly("libs/core", "libs/util");
    }

    @Test
    void selects_literal_and_glob(@TempDir Path root) throws Exception {
        writeWorkspace(root, List.of("api", "worker", "libs/core", "libs/util"));
        JkBuild build = JkBuildParser.parse(root.resolve("jk.toml"));

        var lit = ModuleSelection.resolve(root, build, "api,worker");
        assertThat(lit.ok()).isTrue();
        assertThat(lit.moduleDirs())
                .containsExactlyInAnyOrder(root.resolve("api").normalize(), root.resolve("worker").normalize());

        var glob = ModuleSelection.resolve(root, build, "libs/*");
        assertThat(glob.ok()).isTrue();
        assertThat(glob.moduleDirs())
                .containsExactlyInAnyOrder(
                        root.resolve("libs/core").normalize(), root.resolve("libs/util").normalize());
    }

    @Test
    void unknown_module_fails_clearly(@TempDir Path root) throws Exception {
        writeWorkspace(root, List.of("api"));
        JkBuild build = JkBuildParser.parse(root.resolve("jk.toml"));
        var r = ModuleSelection.resolve(root, build, "nope");
        assertThat(r.ok()).isFalse();
        assertThat(r.errorMessage()).contains("no module matched");
    }

    @Test
    void single_project_matches_dot_or_name(@TempDir Path root) throws Exception {
        Files.writeString(
                root.resolve("jk.toml"),
                """
                [project]
                group = "com.ex"
                name = "solo"
                version = "1.0.0"
                java = 25
                """);
        JkBuild build = JkBuildParser.parse(root.resolve("jk.toml"));
        assertThat(ModuleSelection.resolve(root, build, ".").moduleDirs()).containsExactly(root.normalize());
        assertThat(ModuleSelection.resolve(root, build, "solo").moduleDirs()).containsExactly(root.normalize());
    }

    private static void writeWorkspace(Path root, List<String> modules) throws Exception {
        StringBuilder mods = new StringBuilder();
        for (String m : modules) {
            if (!mods.isEmpty()) mods.append(", ");
            mods.append('"').append(m).append('"');
            Files.createDirectories(root.resolve(m));
            Files.writeString(
                    root.resolve(m).resolve("jk.toml"),
                    """
                    [project]
                    group = "com.ex"
                    name = "%s"
                    version = "1.0.0"
                    java = 25
                    """
                            .formatted(m.replace('/', '-')));
        }
        Files.writeString(
                root.resolve("jk.toml"),
                """
                [project]
                group = "com.ex"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = [%s]
                """
                        .formatted(mods));
    }
}
