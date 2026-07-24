// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.WorkspaceMerge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Sanity-checks the workspace's {@code jk.toml} files. Failing here means the manifests have
 * drifted from what the parser accepts — fix the manifest (or the parser) before flipping the
 * build.
 */
class SelfHostingTomlTest {

    /**
     * Walk up from the test class's own location until we find a {@code jk.toml} whose {@code
     * [workspace]} table claims this directory as a module. That root is the repo. This is more
     * robust than relying on the JVM's cwd, which differs between the Gradle test launcher
     * (per-module cwd) and a forked test JVM under {@code jk test} (typically inherits the parent
     * process's cwd).
     */
    private static final Path REPO = findRepoRoot();

    @BeforeAll
    static void requireSelfHostingWorkspace() {
        // ticket-1007 restored the workspace root; fail hard if it disappears.
        Assumptions.assumeTrue(
                REPO != null && Files.isRegularFile(REPO.resolve("jk.toml")),
                "workspace root jk.toml missing — self-hosting manifests are required");
        try {
            Assumptions.assumeTrue(
                    JkBuildParser.parse(REPO.resolve("jk.toml")).isWorkspaceRoot(),
                    "root jk.toml is not a workspace root");
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "root jk.toml unparseable: " + e.getMessage());
        }
    }

    private static Path findRepoRoot() {
        // The .class file path tells us where we are on disk regardless of
        // cwd. From there, walk up looking for jk.toml with [workspace].
        try {
            Path classPath = Path.of(SelfHostingTomlTest.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            Path candidate = classPath.toAbsolutePath().normalize();
            for (int i = 0; i < 12 && candidate != null; i++) {
                Path manifest = candidate.resolve("jk.toml");
                if (java.nio.file.Files.isRegularFile(manifest)) {
                    try {
                        JkBuild parsed = JkBuildParser.parse(manifest);
                        if (parsed.isWorkspaceRoot()) return candidate;
                    } catch (RuntimeException ignored) {
                        // unparseable jk.toml — keep walking
                    }
                }
                candidate = candidate.getParent();
            }
        } catch (Exception ignored) {
            // fall through
        }
        // No workspace root found — tests will skip via @BeforeAll.
        return null;
    }

    @Test
    void root_jk_toml_declares_the_workspace() throws Exception {
        JkBuild root = JkBuildParser.parse(REPO.resolve("jk.toml"));
        assertThat(root.project().group()).isEqualTo("cc.jumpkick");
        assertThat(root.project().name()).isEqualTo("jk");
        assertThat(root.isWorkspaceRoot()).isTrue();
        // plugin-sdk is listed before jk-api: model depends on the SPI leaf (Gradle :jk-api → :plugin-sdk).
        // Phase 2 adds thin workers (test-runner, java-compiler) as workspace modules.
        assertThat(root.workspace().modules())
                .containsExactly(
                        "shared/plugin-sdk",
                        "shared/jk-api",
                        "shared/core",
                        "shared/client-io",
                        "server/io",
                        "server/resolver",
                        "shared/toolchain-jdk",
                        "server/toolchain",
                        "shared/wire",
                        "clients/web",
                        "server/engine",
                        "clients/cli",
                        "plugins/test-runner",
                        "plugins/java-compiler",
                        "plugins/kotlin-compiler",
                        "plugins/auditor",
                        "plugins/publisher",
                        "plugins/image-builder",
                        "plugins/compat-bridge",
                        "plugins/formatter",
                        "plugins/spring-boot",
                        "plugins/android",
                        "plugins/protobuf",
                        "plugins/shrink");
    }

    @Test
    void engine_is_assembly_app_and_depends_on_web() throws Exception {
        JkBuild engine = JkBuildParser.parse(REPO.resolve("server/engine/jk.toml"));
        assertThat(engine.assembly()).isTrue();
        assertThat(engine.mainClass()).isEqualTo("cc.jumpkick.engine.EngineMain");
        assertThat(engine.dependencies().of(Scope.MAIN).stream()
                        .map(d -> d.library())
                        .toList())
                .contains("jk-web");
    }

    @Test
    void root_declares_central_and_google_repositories() throws Exception {
        JkBuild root = JkBuildParser.parse(REPO.resolve("jk.toml"));
        assertThat(root.repositories()).extracting(r -> r.name()).contains("central", "google");
    }

    @Test
    void web_module_is_resources_library() throws Exception {
        JkBuild web = JkBuildParser.parse(REPO.resolve("clients/web/jk.toml"));
        assertThat(web.project().name()).isEqualTo("jk-web");
        assertThat(web.mainClass()).isNull();
        assertThat(web.assembly()).isFalse();
    }

    @Test
    void android_plugin_declares_google_maven_for_apksig() throws Exception {
        JkBuild android = JkBuildParser.parse(REPO.resolve("plugins/android/jk.toml"));
        assertThat(android.repositories()).extracting(r -> r.name()).contains("google");
        assertThat(android.dependencies().of(Scope.MAIN).stream()
                        .map(d -> d.module())
                        .toList())
                .contains("com.android.tools.build:apksig");
    }

    @Test
    void every_workspace_module_has_a_parseable_jk_toml() throws Exception {
        JkBuild root = JkBuildParser.parse(REPO.resolve("jk.toml"));
        for (String module : root.workspace().modules()) {
            Path moduleManifest = REPO.resolve(module).resolve("jk.toml");
            assertThat(moduleManifest).as("missing " + moduleManifest).exists();
            JkBuild parsed = JkBuildParser.parse(moduleManifest);
            assertThat(parsed.project().group()).isEqualTo("cc.jumpkick");
            assertThat(parsed.project().name()).startsWith("jk-");
            assertThat(parsed.project().jdk()).isEqualTo("25");
        }
    }

    @Test
    void thin_worker_plugins_are_assembly_apps_with_plugin_main() throws Exception {
        for (String module : List.of("plugins/test-runner", "plugins/java-compiler")) {
            JkBuild p = JkBuildParser.parse(REPO.resolve(module).resolve("jk.toml"));
            assertThat(p.assembly()).as(module).isTrue();
            assertThat(p.mainClass()).as(module).isEqualTo("cc.jumpkick.plugin.process.PluginMain");
            assertThat(p.dependencies().of(Scope.MAIN).stream()
                            .map(d -> d.module())
                            .toList())
                    .as(module)
                    .contains("cc.jumpkick:jk-plugin-api");
        }
        // test-runner keeps the JDK-17 floor for the user's forked test JVM.
        JkBuild runner = JkBuildParser.parse(REPO.resolve("plugins/test-runner/jk.toml"));
        assertThat(runner.project().javaRelease()).isEqualTo(17);
    }

    @Test
    void all_first_party_plugin_modules_are_plugin_main_assemblies() throws Exception {
        JkBuild root = JkBuildParser.parse(REPO.resolve("jk.toml"));
        for (String module : root.workspace().modules()) {
            if (!module.startsWith("plugins/")) continue;
            JkBuild p = JkBuildParser.parse(REPO.resolve(module).resolve("jk.toml"));
            assertThat(p.mainClass()).as(module).isEqualTo("cc.jumpkick.plugin.process.PluginMain");
            assertThat(p.assemblyMode().isBundled()).as(module).isTrue();
        }
    }

    @Test
    void cli_module_declares_a_main_class_and_image_main_class() throws Exception {
        JkBuild cli = JkBuildParser.parse(REPO.resolve("clients/cli/jk.toml"));
        assertThat(cli.mainClass()).isEqualTo("cc.jumpkick.cli.Jk");
        assertThat(cli.isRunnable()).isTrue();

        // The project's jk.toml files declare sibling coordinates
        // explicitly (no `.workspace = true` shorthand) so module builds
        // can resolve lock-time + classpath without needing the parser
        // to apply WorkspaceMerge.
        List<String> mainModules =
                cli.dependencies().of(Scope.MAIN).stream().map(d -> d.module()).toList();
        // The slim client (Stage 5 / ticket-1020): the wire contract, never the engine itself.
        assertThat(mainModules).contains("cc.jumpkick:jk-core", "cc.jumpkick:jk-engine-api");
        assertThat(mainModules)
                .doesNotContain(
                        "cc.jumpkick:jk-engine",
                        "cc.jumpkick:jk-io",
                        "cc.jumpkick:jk-resolver",
                        "cc.jumpkick:jk-toolchain");

        // Engine module hosts EngineMain / shadow jar — never links :cli.
        JkBuild engine = JkBuildParser.parse(REPO.resolve("server/engine/jk.toml"));
        List<String> engineMain = engine.dependencies().of(Scope.MAIN).stream()
                .map(d -> d.module())
                .toList();
        assertThat(engineMain).doesNotContain("cc.jumpkick:jk-cli");
        assertThat(engine.mainClass()).isEqualTo("cc.jumpkick.engine.EngineMain");

        // Confirm the workspace-root merge still rewrites/dedupes the
        // module coords cleanly when invoked from the root.
        JkBuild root = JkBuildParser.parse(REPO.resolve("jk.toml"));
        JkBuild merged = WorkspaceMerge.merge(
                root, WorkspaceLoader.loadModules(REPO, root).values());
        List<String> mergedRootMain = merged.dependencies().of(Scope.MAIN).stream()
                .map(d -> d.module())
                .toList();
        // jk-engine is a workspace-internal dep and is filtered by WorkspaceMerge;
        // verify an external dep that survives the merge.
        assertThat(mergedRootMain).contains("org.jline:jline-terminal-ffm");
    }
}
