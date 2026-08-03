// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkBuildWorkspaceTest {

    private static final String LEAF_PROJECT = """
            [project]
            group    = "com.example"
            name     = "leaf"
            version  = "0.1.0"
            """;

    @Test
    void parses_workspace_modules() {
        JkBuild parsed = JkBuildParser.parse("""
                [project]
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                modules = ["libs/core", "services/api"]
                """);

        assertThat(parsed.isWorkspaceRoot()).isTrue();
        assertThat(parsed.workspace().modules()).containsExactly("libs/core", "services/api");
    }

    @Test
    void members_is_not_a_workspace_key() {
        // The pre-1.0 `members = [...]` synonym is gone: `modules` is the one spelling.
        JkBuild parsed = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "root"
                version = "0.1.0"

                [workspace]
                members = ["libs/core", "services/api"]
                """);

        assertThat(parsed.workspace().modules()).isEmpty();
    }

    @Test
    void absent_workspace_block_yields_null() {
        JkBuild parsed = JkBuildParser.parse(LEAF_PROJECT);
        assertThat(parsed.isWorkspaceRoot()).isFalse();
        assertThat(parsed.workspace()).isNull();
    }

    @Test
    void empty_workspace_block_is_not_a_root() {
        JkBuild parsed = JkBuildParser.parse(LEAF_PROJECT + """
                [workspace]
                """);
        assertThat(parsed.isWorkspaceRoot()).isFalse();
        assertThat(parsed.workspace().modules()).isEmpty();
    }

    @Test
    void non_list_modules_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(LEAF_PROJECT + """
                [workspace]
                modules = "libs/core"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("list");
    }

    @Test
    void workspace_loader_loads_module_jk_tomls(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                modules = ["libs/a", "libs/b"]
                """);
        for (String name : new String[] {"libs/a", "libs/b"}) {
            Path moduleDir = tempDir.resolve(name);
            Files.createDirectories(moduleDir);
            Files.writeString(moduleDir.resolve("jk.toml"), """
                    [project]
                    group    = "com.example"
                    name     = "%s"
                    version  = "0.1.0"
                    """.formatted(name.replace('/', '-')));
        }

        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(tempDir, root);
        assertThat(modules).hasSize(2);
        assertThat(modules).containsKey(tempDir.resolve("libs/a"));
    }

    @Test
    void concrete_member_parses_despite_a_broken_sibling(@TempDir Path tempDir) throws IOException {
        // Mid-refactor reality: one sibling's jk.toml is malformed. A member with fully
        // concrete [project] and no workspace: deps needs nothing from the siblings — its
        // parse must succeed; the broken sibling's error belongs to whoever builds it.
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "root"
                version = "1.0.0"

                [workspace]
                modules = ["good", "broken"]
                """);
        Path good = Files.createDirectories(tempDir.resolve("good"));
        Files.writeString(good.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "good"
                version = "1.0.0"
                jdk     = 25
                java    = 25
                """);
        Path broken = Files.createDirectories(tempDir.resolve("broken"));
        Files.writeString(broken.resolve("jk.toml"), "not [ valid toml ===");

        JkBuild parsed = JkBuildParser.parse(good.resolve("jk.toml"));
        assertThat(parsed.project().group()).isEqualTo("com.example");
        assertThat(parsed.project().version()).isEqualTo("1.0.0");
    }

    @Test
    void inheriting_member_still_fails_on_a_broken_root(@TempDir Path tempDir) throws IOException {
        // A member whose identity depends on the workspace root cannot silently parse with
        // sentinels — a malformed root must propagate to it.
        Files.writeString(tempDir.resolve("jk.toml"), "not [ valid toml ===");
        Path thin = Files.createDirectories(tempDir.resolve("thin"));
        // findRoot needs a parseable [workspace] to locate the root; a malformed root file
        // is found by directory walk, then fails to parse for the inheriting member.
        Files.writeString(thin.resolve("jk.toml"), """
                [project]
                name = "thin"
                """);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JkBuildParser.parse(thin.resolve("jk.toml")))
                .isInstanceOf(JkBuildParseException.class);
    }

    @Test
    void inherited_member_survives_a_missing_sibling_dir(@TempDir Path tempDir) throws IOException {
        // Identity resolves from the root; a missing listed sibling only matters to members
        // with workspace:<name> deps.
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "root"
                version = "1.0.0"

                [workspace]
                modules = ["thin", "missing"]
                """);
        Path thin = Files.createDirectories(tempDir.resolve("thin"));
        Files.writeString(thin.resolve("jk.toml"), """
                [project]
                name = "thin"
                """);
        // "missing" module dir deliberately absent.

        JkBuild parsed = JkBuildParser.parse(thin.resolve("jk.toml"));
        assertThat(parsed.project().group()).isEqualTo("com.example");
        assertThat(parsed.project().version()).isEqualTo("1.0.0");
    }

    @Test
    void member_explicit_value_overrides_the_root(@TempDir Path tempDir) throws IOException {
        // The precedence contract, asserted directly: a member's concrete value wins over
        // the root's — resolveFromWorkspaceRoot only fills what the member left open.
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "root"
                version = "1.0.0"
                jdk     = 25
                java    = 25

                [workspace]
                modules = ["pinned"]
                """);
        Path pinned = Files.createDirectories(tempDir.resolve("pinned"));
        Files.writeString(pinned.resolve("jk.toml"), """
                [project]
                group   = "com.other"
                name    = "pinned"
                version = "9.9.9"
                jdk     = 25
                java    = 25
                """);

        JkBuild parsed = JkBuildParser.parse(pinned.resolve("jk.toml"));
        assertThat(parsed.project().group()).isEqualTo("com.other");
        assertThat(parsed.project().version()).isEqualTo("9.9.9");
        assertThat(parsed.project().javaRelease()).isEqualTo(25);
    }

    @Test
    void workspace_loader_inherits_version_from_root(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "root"
                version = "2.5.0"

                [workspace]
                modules = ["lib"]
                """);
        Path lib = tempDir.resolve("lib");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "lib"
                version.workspace = true
                jdk     = 25
                java    = 25
                """);

        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(tempDir, root);
        assertThat(modules.get(lib).project().version()).isEqualTo("2.5.0");
        assertThat(modules.get(lib).project().inheritsVersionFromWorkspace()).isFalse();
    }

    @Test
    void workspace_loader_inherits_group_java_jdk_from_root(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group       = "com.acme"
                name        = "root"
                version     = "3.0.0"
                java        = 25
                jdk         = "temurin-25"
                description = "Root description"

                [workspace]
                modules = ["lib"]
                """);
        Path lib = tempDir.resolve("lib");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("jk.toml"), """
                [project]
                group.workspace = true
                name    = "lib"
                version.workspace = true
                java.workspace = true
                jdk.workspace = true
                description.workspace = true
                """);

        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(tempDir, root);
        var p = modules.get(lib).project();
        assertThat(p.group()).isEqualTo("com.acme");
        assertThat(p.name()).isEqualTo("lib");
        assertThat(p.version()).isEqualTo("3.0.0");
        assertThat(p.java()).isEqualTo(25);
        assertThat(p.jdk()).isEqualTo("temurin-25");
        assertThat(p.description()).isEqualTo("Root description");
        assertThat(p.inheritsFromWorkspace()).isFalse();
    }

    @Test
    void minimal_module_only_name_inherits_everything_except_description(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group       = "com.acme"
                name        = "root"
                version     = "1.0.0"
                java        = 25
                jdk         = "25"
                description = "Root only"

                [workspace]
                modules = ["foo"]
                """);
        Path foo = tempDir.resolve("foo");
        Files.createDirectories(foo);
        Files.writeString(foo.resolve("jk.toml"), """
                [project]
                name = "foo"
                """);

        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(tempDir, root);
        var p = modules.get(foo).project();
        assertThat(p.name()).isEqualTo("foo");
        assertThat(p.group()).isEqualTo("com.acme");
        assertThat(p.version()).isEqualTo("1.0.0");
        assertThat(p.java()).isEqualTo(25);
        assertThat(p.jdk()).isEqualTo("25");
        // description is optional and does not auto-inherit when omitted
        assertThat(p.description()).isNull();
    }

    @Test
    void workspace_loader_collision_uses_inherited_version(@TempDir Path tempDir) throws IOException {
        // Two modules inherit the same root version and share an artifact name → collision.
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "root"
                version = "1.0.0"

                [workspace]
                modules = ["a", "b"]
                """);
        for (String name : new String[] {"a", "b"}) {
            Path dir = tempDir.resolve(name);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("jk.toml"), """
                    [project]
                    group   = "com.example"
                    name    = "dup"
                    version.workspace = true
                    """);
        }
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadModules(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("artifact collision")
                .hasMessageContaining("dup-1.0.0");
    }

    @Test
    void workspace_loader_reports_missing_module(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                modules = ["libs/missing"]
                """);
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadModules(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("missing jk.toml");
    }

    @Test
    void workspace_loader_rejects_artifact_collision_between_modules(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                modules = ["libs/a", "libs/b"]
                """);
        // Two modules both call themselves `widget-0.1.0` — they'd race to
        // write the same jar under <root>/target/.
        for (String name : new String[] {"libs/a", "libs/b"}) {
            Path moduleDir = tempDir.resolve(name);
            Files.createDirectories(moduleDir);
            Files.writeString(moduleDir.resolve("jk.toml"), """
                    [project]
                    group    = "com.example"
                    name     = "widget"
                    version  = "0.1.0"
                    """);
        }
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadModules(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("workspace artifact collision")
                .hasMessageContaining("widget-0.1.0.jar")
                .hasMessageContaining("libs/a")
                .hasMessageContaining("libs/b");
    }

    @Test
    void workspace_loader_rejects_collision_between_root_and_module(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "0.1.0"

                [workspace]
                modules = ["libs/a"]
                """);
        Path moduleA = tempDir.resolve("libs/a");
        Files.createDirectories(moduleA);
        Files.writeString(moduleA.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "0.1.0"
                """);
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadModules(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("workspace artifact collision")
                .hasMessageContaining("<workspace root>")
                .hasMessageContaining("libs/a");
    }

    @Test
    void workspace_loader_rejects_nested_workspaces(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                modules = ["libs/a"]
                """);
        Path moduleA = tempDir.resolve("libs/a");
        Files.createDirectories(moduleA);
        // Module tries to declare its own [workspace] — should be rejected.
        Files.writeString(moduleA.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "a"
                version  = "0.1.0"

                [workspace]
                modules = ["sub"]
                """);
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadModules(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("workspaces cannot be nested")
                .hasMessageContaining("libs/a");
    }

    @Test
    void workspace_loader_allows_same_artifact_with_different_versions(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                modules = ["libs/a", "libs/b"]
                """);
        // Same artifact, different versions → no collision (jar filenames differ).
        Path a = tempDir.resolve("libs/a");
        Files.createDirectories(a);
        Files.writeString(a.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "0.1.0"
                """);
        Path b = tempDir.resolve("libs/b");
        Files.createDirectories(b);
        Files.writeString(b.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "0.2.0"
                """);
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(tempDir, root);
        assertThat(modules).hasSize(2);
    }
}
