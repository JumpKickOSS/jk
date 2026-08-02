// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
                .containsExactlyInAnyOrder(
                        root.resolve("api").normalize(), root.resolve("worker").normalize());

        var glob = ModuleSelection.resolve(root, build, "libs/*");
        assertThat(glob.ok()).isTrue();
        assertThat(glob.moduleDirs())
                .containsExactlyInAnyOrder(
                        root.resolve("libs/core").normalize(),
                        root.resolve("libs/util").normalize());
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
        Files.writeString(root.resolve("jk.toml"), """
                [project]
                group = "com.ex"
                name = "solo"
                version = "1.0.0"
                java = 25
                """);
        JkBuild build = JkBuildParser.parse(root.resolve("jk.toml"));
        assertThat(ModuleSelection.resolve(root, build, ".").moduleDirs()).containsExactly(root.normalize());
        assertThat(ModuleSelection.resolve(root, build, "solo").moduleDirs()).containsExactly(root.normalize());
        assertThat(ModuleSelection.resolve(root, build, ":solo").moduleDirs()).containsExactly(root.normalize());
    }

    @Test
    void matches_project_name_and_gradle_colon_form(@TempDir Path root) throws Exception {
        writeWorkspaceNamed(
                root,
                List.of(
                        new Mod("server/engine", "jk-engine"),
                        new Mod("shared/client-io", "jk-client-io"),
                        new Mod("plugins/kotlin-compiler", "jk-kotlin-compiler")));
        JkBuild build = JkBuildParser.parse(root.resolve("jk.toml"));

        assertThat(ModuleSelection.resolve(root, build, ":jk-engine").moduleDirs())
                .containsExactly(root.resolve("server/engine").normalize());
        assertThat(ModuleSelection.resolve(root, build, "jk-engine").moduleDirs())
                .containsExactly(root.resolve("server/engine").normalize());
        // Gradle short project id (:engine) and path bare segment
        assertThat(ModuleSelection.resolve(root, build, ":engine").moduleDirs())
                .containsExactly(root.resolve("server/engine").normalize());
        // Gradle multi-segment path with colons
        assertThat(ModuleSelection.resolve(root, build, ":server:engine").moduleDirs())
                .containsExactly(root.resolve("server/engine").normalize());
        // Comma list of colon forms
        assertThat(ModuleSelection.resolve(root, build, ":jk-engine,:jk-client-io")
                        .moduleDirs())
                .containsExactlyInAnyOrder(
                        root.resolve("server/engine").normalize(),
                        root.resolve("shared/client-io").normalize());
        // Soft alias: project name without jk- prefix
        assertThat(ModuleSelection.resolve(root, build, ":kotlin-compiler").moduleDirs())
                .containsExactly(root.resolve("plugins/kotlin-compiler").normalize());
    }

    @Test
    void path_selectors_resolve_without_parsing_member_manifests(@TempDir Path root) throws Exception {
        // JK-1367: plain path/glob selectors take the cheap pass — a malformed member manifest
        // must not matter (and N manifests are not parsed per resolve).
        writeWorkspace(root, List.of("api", "worker"));
        Files.writeString(root.resolve("api/jk.toml"), "this is [ not toml");
        JkBuild build = JkBuildParser.parse(root.resolve("jk.toml"));

        var byPath = ModuleSelection.resolve(root, build, "api");
        assertThat(byPath.ok()).isTrue();
        assertThat(byPath.moduleDirs()).containsExactly(root.resolve("api").normalize());

        // Name selectors still work — the lazy second pass loads them.
        var byName = ModuleSelection.resolve(root, build, "worker");
        assertThat(byName.ok()).isTrue();
    }

    @Test
    void ambiguous_literal_fails_instead_of_fanning_out(@TempDir Path root) throws Exception {
        // JK-1366: `cli` naming both clients/cli and tools/cli is a collision, not a two-module
        // build — only globs/braces fan out.
        writeWorkspace(root, List.of("clients/cli", "tools/cli"));
        JkBuild build = JkBuildParser.parse(root.resolve("jk.toml"));

        var r = ModuleSelection.resolve(root, build, "cli");
        assertThat(r.ok()).isFalse();
        assertThat(r.errorMessage())
                .contains("ambiguous")
                .contains("clients/cli")
                .contains("tools/cli");

        // Full path, glob, and brace forms still select.
        assertThat(ModuleSelection.resolve(root, build, "clients/cli").ok()).isTrue();
        var glob = ModuleSelection.resolve(root, build, "*/cli");
        assertThat(glob.ok()).isTrue();
        assertThat(glob.moduleDirs()).hasSize(2);
    }

    @Test
    void unknown_selector_labels_are_deterministic(@TempDir Path root) throws Exception {
        // JK-1367: the "known:" labels pick the first non-path alias in insertion order (the
        // project name) — never a randomly iterated set member.
        writeWorkspaceNamed(root, List.of(new Mod("server/engine", "jk-engine")));
        JkBuild build = JkBuildParser.parse(root.resolve("jk.toml"));
        for (int i = 0; i < 5; i++) {
            var r = ModuleSelection.resolve(root, build, "nope");
            assertThat(r.ok()).isFalse();
            assertThat(r.errorMessage()).contains("server/engine (jk-engine)");
        }
    }

    @Test
    void normalize_token_strips_gradle_colons() {
        assertThat(ModuleSelection.normalizeToken(":jk-engine")).isEqualTo("jk-engine");
        assertThat(ModuleSelection.normalizeToken(":server:engine")).isEqualTo("server/engine");
        assertThat(ModuleSelection.normalizeToken("server/engine")).isEqualTo("server/engine");
    }

    private record Mod(String path, String projectName) {}

    private static void writeWorkspace(Path root, List<String> modules) throws Exception {
        List<Mod> named = new ArrayList<>();
        for (String m : modules) named.add(new Mod(m, m.replace('/', '-')));
        writeWorkspaceNamed(root, named);
    }

    private static void writeWorkspaceNamed(Path root, List<Mod> modules) throws Exception {
        StringBuilder mods = new StringBuilder();
        for (Mod m : modules) {
            if (!mods.isEmpty()) mods.append(", ");
            mods.append('"').append(m.path()).append('"');
            Files.createDirectories(root.resolve(m.path()));
            Files.writeString(root.resolve(m.path()).resolve("jk.toml"), """
                    [project]
                    group = "com.ex"
                    name = "%s"
                    version = "1.0.0"
                    java = 25
                    """.formatted(m.projectName()));
        }
        Files.writeString(root.resolve("jk.toml"), """
                [project]
                group = "com.ex"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = [%s]
                """.formatted(mods));
    }
}
