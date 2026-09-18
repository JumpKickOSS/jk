// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.workspaceOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkBuildWorkspaceTest {

    private static final String LEAF_PROJECT = """
            group    = "com.example"
            name     = "leaf"
            version  = "0.1.0"
            """;

    @Test
    void parses_workspace_modules() {
        JkBuild parsed = JkBuildParser.parse("""
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                modules = ["libs/core", "services/api"]
                """);

        assertThat(parsed.isWorkspaceRoot()).isTrue();
        assertThat(workspaceOf(parsed).modules()).containsExactly("libs/core", "services/api");
    }

    @Test
    void members_is_not_a_workspace_key() {
        // The pre-1.0 `members = [...]` synonym is gone: `modules` is the one spelling.
        JkBuild parsed = JkBuildParser.parse("""
                group = "com.example"
                name = "root"
                version = "0.1.0"

                [workspace]
                members = ["libs/core", "services/api"]
                """);

        assertThat(workspaceOf(parsed).modules()).isEmpty();
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
        assertThat(workspaceOf(parsed).modules()).isEmpty();
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
    void workspace_loader_hands_members_the_roots_image_registry_facts(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                modules = ["svc/api", "svc/worker"]

                [image]
                base     = "eclipse-temurin:25-jre"
                registry = "ghcr.io/acme"
                name     = "platform"
                labels   = { team = "core" }
                """);
        Files.createDirectories(tempDir.resolve("svc/api"));
        Files.writeString(tempDir.resolve("svc/api/jk.toml"), """
                name = "api"

                [image]
                ports = [8080]
                registry = "registry.acme.test"
                """);
        Files.createDirectories(tempDir.resolve("svc/worker"));
        Files.writeString(tempDir.resolve("svc/worker/jk.toml"), "name = \"worker\"\n");

        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(tempDir, root);
        JkBuild api = Objects.requireNonNull(modules.get(tempDir.resolve("svc/api")));
        JkBuild worker = Objects.requireNonNull(modules.get(tempDir.resolve("svc/worker")));

        assertThat(api.image().base()).isEqualTo("eclipse-temurin:25-jre");
        assertThat(api.image().registry()).as("the member's own key wins").isEqualTo("registry.acme.test");
        assertThat(api.image().ports()).containsExactly(8080);
        assertThat(api.image().labels()).containsEntry("team", "core");
        assertThat(api.image().name()).as("the image name is per module").isNull();
        assertThat(worker.image().registry()).isEqualTo("ghcr.io/acme");
        assertThat(worker.image().name()).isNull();
        // a member parsed on its own reads the same table
        assertThat(JkBuildParser.parse(tempDir.resolve("svc/worker/jk.toml"))
                        .image()
                        .registry())
                .isEqualTo("ghcr.io/acme");
    }

    @Test
    void concrete_member_parses_despite_a_broken_sibling(@TempDir Path tempDir) throws IOException {
        // Mid-refactor reality: one sibling's jk.toml is malformed. A member with fully
        // concrete project identity and no workspace: deps needs nothing from the siblings — its
        // parse must succeed; the broken sibling's error belongs to whoever builds it.
        Files.writeString(tempDir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "root"
                version = "1.0.0"

                [workspace]
                modules = ["good", "broken"]
                """);
        Path good = Files.createDirectories(tempDir.resolve("good"));
        Files.writeString(good.resolve("jk.toml"), """
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
                group   = "com.example"
                name    = "root"
                version = "1.0.0"

                [workspace]
                modules = ["thin", "missing"]
                """);
        Path thin = Files.createDirectories(tempDir.resolve("thin"));
        Files.writeString(thin.resolve("jk.toml"), """
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
                group   = "com.example"
                name    = "root"
                version = "2.5.0"

                [workspace]
                modules = ["lib"]
                """);
        Path lib = tempDir.resolve("lib");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version.workspace = true
                jdk     = 25
                java    = 25
                """);

        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(tempDir, root);
        assertThat(modules).containsKey(lib);
        var p = Objects.requireNonNull(modules.get(lib)).project();
        assertThat(p.version()).isEqualTo("2.5.0");
        assertThat(p.inheritsVersionFromWorkspace()).isFalse();
    }

    @Test
    void workspace_loader_inherits_group_java_jdk_from_root(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
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
                group.workspace = true
                name    = "lib"
                version.workspace = true
                java.workspace = true
                jdk.workspace = true
                description.workspace = true
                """);

        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(tempDir, root);
        assertThat(modules).containsKey(lib);
        var p = Objects.requireNonNull(modules.get(lib)).project();
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
                name = "foo"
                """);

        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(tempDir, root);
        assertThat(modules).containsKey(foo);
        var p = Objects.requireNonNull(modules.get(foo)).project();
        assertThat(p.name()).isEqualTo("foo");
        assertThat(p.group()).isEqualTo("com.acme");
        assertThat(p.version()).isEqualTo("1.0.0");
        assertThat(p.java()).isEqualTo(25);
        assertThat(p.jdk()).isEqualTo("25");
        // description is optional and does not auto-inherit when omitted
        assertThat(p.description()).isNull();
    }

    @Test
    void workspace_loader_refuses_one_coordinate_declared_twice(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
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
                    group   = "com.example"
                    name    = "dup"
                    version.workspace = true
                    """);
        }
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadModules(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("workspace module collision")
                .hasMessageContaining("com.example:dup")
                .hasMessageContaining("`a`")
                .hasMessageContaining("`b`");
    }

    /**
     * A shared artifact name across groups is Maven's own shape (thingsboard's {@code common/edqs} and
     * {@code edqs}): both load, and each member's jar lands under its own {@code target/<rel>/}.
     */
    @Test
    void workspace_loader_accepts_one_name_in_two_groups_and_lays_their_jars_out_apart(@TempDir Path tempDir)
            throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                group   = "org.tb"
                name    = "root"
                version = "4.4.0"

                [workspace]
                modules = ["common/edqs", "edqs"]
                """);
        writeModule(tempDir, "common/edqs", "org.tb.common");
        writeModule(tempDir, "edqs", "org.tb");
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(tempDir, root);
        assertThat(modules).hasSize(2);
        List<Path> jars = modules.entrySet().stream()
                .map(e -> BuildLayout.of(tempDir, e.getKey(), e.getValue()).mainJar())
                .toList();
        assertThat(jars)
                .containsExactly(
                        tempDir.resolve("target/common/edqs/lib/edqs-4.4.0.jar"),
                        tempDir.resolve("target/edqs/lib/edqs-4.4.0.jar"));
    }

    /** An edge to a name two members carry cannot pick one silently: it is refused, naming both. */
    @Test
    void workspace_loader_refuses_an_edge_to_a_name_two_members_carry(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                group   = "org.tb"
                name    = "root"
                version = "4.4.0"

                [workspace]
                modules = ["common/edqs", "edqs", "application"]
                """);
        writeModule(tempDir, "common/edqs", "org.tb.common");
        writeModule(tempDir, "edqs", "org.tb");
        Path application = Files.createDirectories(tempDir.resolve("application"));
        Files.writeString(application.resolve("jk.toml"), """
                name = "application"

                [dependencies]
                edqs.workspace = true
                """);
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadModules(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("workspace edge `edqs` is ambiguous")
                .hasMessageContaining("`application` depends on it")
                .hasMessageContaining("`common/edqs` and `edqs` both carry that name");
    }

    /** The group on the edge picks one member, so the same three-member workspace loads. */
    @Test
    void workspace_loader_accepts_a_group_qualified_edge_to_a_shared_name(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                group   = "org.tb"
                name    = "root"
                version = "4.4.0"

                [workspace]
                modules = ["common/edqs", "edqs", "application"]
                """);
        writeModule(tempDir, "common/edqs", "org.tb.common");
        writeModule(tempDir, "edqs", "org.tb");
        Path application = Files.createDirectories(tempDir.resolve("application"));
        Files.writeString(application.resolve("jk.toml"), """
                name = "application"

                [dependencies]
                edqs = { workspace = true, group = "org.tb.common" }
                """);
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(tempDir, root);
        assertThat(modules).hasSize(3);
        JkBuild resolved = JkBuildParser.parse(application.resolve("jk.toml"));
        assertThat(resolved.dependencies().of(Scope.MAIN)).singleElement().satisfies(d -> {
            assertThat(d.module()).isEqualTo("org.tb.common:edqs");
            assertThat(d.version().raw()).isEqualTo("=4.4.0");
        });
    }

    @Test
    void workspace_loader_refuses_a_group_no_member_carrying_the_name_has(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                group   = "org.tb"
                name    = "root"
                version = "4.4.0"

                [workspace]
                modules = ["common/edqs", "edqs", "application"]
                """);
        writeModule(tempDir, "common/edqs", "org.tb.common");
        writeModule(tempDir, "edqs", "org.tb");
        Path application = Files.createDirectories(tempDir.resolve("application"));
        Files.writeString(application.resolve("jk.toml"), """
                name = "application"

                [dependencies]
                edqs = { workspace = true, group = "org.tb.msa" }
                """);
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadModules(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("workspace edge `edqs` names group `org.tb.msa`")
                .hasMessageContaining("`application` depends on it")
                .hasMessageContaining("`org.tb.common`, `org.tb`");
    }

    private static void writeModule(Path workspace, String rel, String group) throws IOException {
        Path dir = Files.createDirectories(workspace.resolve(rel));
        Files.writeString(dir.resolve("jk.toml"), "group = \"" + group + "\"\nname = \"edqs\"\n");
    }

    /** `libs/*` is what the docs open with: one glob, every module under it, sorted, no root edit. */
    @Test
    void workspace_loader_expands_single_segment_globs(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                modules = ["apps/web", "libs/*", "libs/core"]
                """);
        for (String rel : List.of("libs/core", "libs/beta", "apps/web")) {
            Path dir = Files.createDirectories(tempDir.resolve(rel));
            Files.writeString(dir.resolve("jk.toml"), "name = \"" + rel.replace('/', '-') + "\"\n");
        }
        Files.createDirectories(tempDir.resolve("libs/not-a-module")); // no jk.toml: skipped
        Files.createDirectories(tempDir.resolve("libs/.hidden"));
        Files.writeString(tempDir.resolve("libs/.hidden/jk.toml"), "name = \"hidden\"\n");

        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(WorkspaceModules.expand(tempDir, workspaceOf(root).modules()))
                .as("declared order for literals, sorted within a glob, duplicate collapsed")
                .containsExactly("apps/web", "libs/beta", "libs/core");
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(tempDir, root);
        assertThat(modules.keySet())
                .containsExactly(
                        tempDir.resolve("apps/web").normalize(),
                        tempDir.resolve("libs/beta").normalize(),
                        tempDir.resolve("libs/core").normalize());
    }

    @Test
    void workspace_loader_refuses_a_glob_that_selects_nothing_and_double_star(@TempDir Path tempDir)
            throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                modules = ["libs/*"]
                """);
        Files.createDirectories(tempDir.resolve("libs"));
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadModules(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("libs/*")
                .hasMessageContaining("matches no directory");
        assertThatThrownBy(() -> WorkspaceModules.expand(tempDir, List.of("**/core")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("**");
    }

    @Test
    void module_membership_through_a_glob_is_lexical() {
        List<String> entries = List.of("apps/web", "libs/*", "tools/?-cli");
        assertThat(WorkspaceModules.lists(entries, "libs/core")).isTrue();
        assertThat(WorkspaceModules.lists(entries, "libs/core/deep")).isFalse();
        assertThat(WorkspaceModules.lists(entries, "apps/web")).isTrue();
        assertThat(WorkspaceModules.lists(entries, "apps/api")).isFalse();
        assertThat(WorkspaceModules.lists(entries, "tools/a-cli")).isTrue();
        assertThat(WorkspaceModules.lists(entries, "tools/ab-cli")).isFalse();
    }

    @Test
    void workspace_loader_reports_missing_module(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
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
    void workspace_loader_rejects_one_coordinate_between_modules(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
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
                    group    = "com.example"
                    name     = "widget"
                    version  = "0.1.0"
                    """);
        }
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadModules(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("workspace module collision")
                .hasMessageContaining("com.example:widget")
                .hasMessageContaining("libs/a")
                .hasMessageContaining("libs/b");
    }

    @Test
    void workspace_loader_rejects_collision_between_root_and_module(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                group    = "com.example"
                name     = "widget"
                version  = "0.1.0"

                [workspace]
                modules = ["libs/a"]
                """);
        Path moduleA = tempDir.resolve("libs/a");
        Files.createDirectories(moduleA);
        Files.writeString(moduleA.resolve("jk.toml"), """
                group    = "com.example"
                name     = "widget"
                version  = "0.1.0"
                """);
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadModules(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("workspace module collision")
                .hasMessageContaining("<workspace root>")
                .hasMessageContaining("libs/a");
    }

    @Test
    void workspace_loader_rejects_nested_workspaces(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
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
                group    = "com.example"
                name     = "widget"
                version  = "0.1.0"
                """);
        Path b = tempDir.resolve("libs/b");
        Files.createDirectories(b);
        Files.writeString(b.resolve("jk.toml"), """
                group    = "com.example"
                name     = "widget"
                version  = "0.2.0"
                """);
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(tempDir, root);
        assertThat(modules).hasSize(2);
    }
}
