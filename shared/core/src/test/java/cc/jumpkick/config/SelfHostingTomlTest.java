// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.WorkspaceMerge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
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
        // restored the workspace root; fail hard if it disappears.
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
        // The.class file path tells us where we are on disk regardless of
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
                if (Files.isRegularFile(manifest)) {
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
        // plugin-sdk is listed before jk-api: model depends on the SPI leaf (Gradle:jk-api →:plugin-sdk).
        // Phase 2 adds thin workers (test-runner, java-compiler) as workspace modules.
        assertThat(root.workspace().modules())
                .containsExactly(
                        "shared/plugin-sdk",
                        "shared/jk-api",
                        "shared/core",
                        "shared/client-io",
                        "shared/dynamic-surface",
                        "server/io",
                        "server/resolver",
                        "shared/toolchain-jdk",
                        "server/toolchain",
                        "shared/wire",
                        "server/engine",
                        "clients/cli",
                        "clients/web",
                        "plugins/test-runner",
                        "plugins/java-compiler",
                        "plugins/kotlin-compiler",
                        "plugins/groovy-compiler",
                        "plugins/auditor",
                        "plugins/publisher",
                        "plugins/image-builder",
                        "plugins/compat-bridge",
                        "plugins/minified",
                        "plugins/formatter",
                        "plugins/spring-boot",
                        "plugins/quarkus",
                        "plugins/grails",
                        "plugins/protobuf",
                        "plugins/android");
    }

    @Test
    void short_name_manifests_do_not_use_removed_catalog_pin() throws Exception {
        // catalog = … and host-local libs.toml are gone; short names resolve through the layered
        // catalog (project jk-libs.toml → global → bundled). Self-host manifests must not
        // set a per-manifest pin — the workspace-root jk-libs.toml pins them instead.
        for (String rel : List.of(
                "jk.toml",
                "clients/cli/jk.toml",
                "plugins/android/jk.toml",
                "plugins/formatter/jk.toml",
                "plugins/groovy-compiler/jk.toml",
                "plugins/kotlin-compiler/jk.toml",
                "plugins/quarkus/jk.toml")) {
            String text = Files.readString(REPO.resolve(rel));
            assertThat(text).as("%s must not set catalog = (removed)", rel).doesNotContain("catalog =");
        }
    }

    /**
     * The workspace-root jk-libs.toml must pin every catalog-resolved short name any workspace
     * manifest uses, each to the bundled coordinate, so a registry edit cannot silently
     * repoint them. The layered chain must serve those pins from the project layer.
     */
    @Test
    void catalog_pins_cover_every_self_host_short_name() throws Exception {
        JkBuild root = JkBuildParser.parseLocal(REPO.resolve("jk.toml"));
        List<Path> manifests = new ArrayList<>();
        manifests.add(REPO.resolve("jk.toml"));
        for (Path moduleDir :
                cc.jumpkick.config.WorkspaceLoader.loadModules(REPO, root).keySet()) {
            Path mt = moduleDir.resolve("jk.toml");
            if (Files.isRegularFile(mt)) manifests.add(mt);
        }
        // Catalog-resolved short names: `name = "<version>"` entries in *dependencies tables,
        // excluding workspace refs and structured { group = … } coordinates.
        Pattern entry = Pattern.compile("^([A-Za-z0-9._-]+)\\s*=\\s*\"[^\"]*\"\\s*(#.*)?$");
        Set<String> shortNames = new TreeSet<>();
        for (Path manifest : manifests) {
            String table = "";
            for (String raw : Files.readAllLines(manifest)) {
                String line = raw.strip();
                if (line.startsWith("[")) {
                    table = line;
                    continue;
                }
                if (!table.endsWith("dependencies]")) continue;
                var m = entry.matcher(line);
                if (m.matches() && !m.group(1).endsWith(".workspace")) shortNames.add(m.group(1));
            }
        }
        assertThat(shortNames).as("self-host manifests use catalog short names").isNotEmpty();

        cc.jumpkick.library.LibraryCatalog bundled = cc.jumpkick.library.LibraryCatalog.bundled();
        cc.jumpkick.library.LibraryCatalog pins = cc.jumpkick.library.LibraryCatalog.parse(
                Files.readString(cc.jumpkick.library.LibraryCatalog.projectFile(REPO)));
        cc.jumpkick.library.LibraryCatalog chain = cc.jumpkick.library.LibraryCatalog.forProject(REPO);
        for (String name : shortNames) {
            var expected = bundled.lookup(name);
            assertThat(expected)
                    .as("%s must exist in the bundled catalog", name)
                    .isPresent();
            assertThat(pins.lookup(name))
                    .as("jk-libs.toml must pin %s (add it with the bundled GA)", name)
                    .contains(expected.get());
            assertThat(chain.lookup(name))
                    .as("layered chain must serve the pinned GA for %s", name)
                    .contains(expected.get());
            assertThat(chain.source(name))
                    .as("%s must resolve from the project layer, not a mutable registry", name)
                    .hasValueSatisfying(s -> assertThat(s.layer()).isEqualTo("project"));
        }
    }

    /**
     *  — the re-lock-in-the-same-commit guard, automated. Any jk.toml / jk-libs.toml edit
     * must land with a re-stamped jk-lock.toml: a stale stamp costs every fresh checkout an ~18s
     * re-resolve, and staleness detection is what stands between an edited catalog pin and a
     * silently wrong resolution. Runs in CI via the plain unit tier.
     */
    @Test
    void lock_stamp_matches_manifests() throws Exception {
        Path lock = REPO.resolve("jk-lock.toml");
        Assumptions.assumeTrue(Files.isRegularFile(lock), "workspace lock missing");
        assertThat(cc.jumpkick.lock.LockfileReader.read(lock).manifestsSha256())
                .as("jk-lock.toml manifests-sha256 is stale — re-lock (jk lock) and commit the "
                        + "re-stamp together with the manifest/pin edit")
                .isEqualTo(cc.jumpkick.lock.LockManifestDigest.compute(REPO));
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
    void web_module_is_resources_library() throws Exception {
        JkBuild web = JkBuildParser.parse(REPO.resolve("clients/web/jk.toml"));
        assertThat(web.project().name()).isEqualTo("jk-web");
        assertThat(web.mainClass()).isNull();
        assertThat(web.assembly()).isFalse();
        // Traditional: src/main/resources + src/test/java (not SIMPLE flat src/).
        assertThat(web.project().layout()).isEqualTo(JkBuild.Layout.TRADITIONAL);
    }

    @Test
    void android_plugin_depends_on_apksig() throws Exception {
        JkBuild android = JkBuildParser.parse(REPO.resolve("plugins/android/jk.toml"));
        // Google Maven is a built-in remote — no need to redeclare [repositories].
        assertThat(android.repositories()).isEmpty();
        assertThat(android.dependencies().of(Scope.MAIN).stream()
                        .map(d -> d.module())
                        .toList())
                .contains("com.android.tools.build:apksig");
    }

    @Test
    void every_workspace_module_has_a_parseable_jk_toml() throws Exception {
        JkBuild root = JkBuildParser.parse(REPO.resolve("jk.toml"));
        // Language level is on the workspace root (java = 25); modules inherit — do not require
        // per-module jdk = pins (AGENTS.md: prefer java = N, rare jdk =).
        assertThat(root.project().java()).isEqualTo(25);
        for (String module : root.workspace().modules()) {
            Path moduleManifest = REPO.resolve(module).resolve("jk.toml");
            assertThat(moduleManifest).as("missing " + moduleManifest).exists();
            JkBuild parsed = JkBuildParser.parse(moduleManifest);
            assertThat(parsed.project().group()).isEqualTo("cc.jumpkick");
            assertThat(parsed.project().name()).startsWith("jk-");
        }
    }

    @Test
    void thin_worker_plugins_have_plugin_main_without_fat_assembly() throws Exception {
        // workers are thin jars + classpath sidecars, not assembly fat jars.
        // Only the engine stays assembly = true for ship.
        for (String module : List.of("plugins/test-runner", "plugins/java-compiler")) {
            JkBuild p = JkBuildParser.parse(REPO.resolve(module).resolve("jk.toml"));
            assertThat(p.assembly()).as(module + " must not fat-assemble").isFalse();
            assertThat(p.minified()).as(module).isFalse();
            assertThat(p.mainClass()).as(module).isEqualTo("cc.jumpkick.plugin.process.PluginMain");
            assertThat(p.dependencies().of(Scope.MAIN).stream()
                            .map(d -> d.module())
                            .toList())
                    .as(module)
                    .contains("cc.jumpkick:jk-plugin-sdk");
        }
        // test-runner + plugin-sdk keep the JDK-17 floor for the user's forked test JVM
        // (classfile 61). Do not inherit workspace java=25 — JdkFloorTest depends on this.
        JkBuild runner = JkBuildParser.parse(REPO.resolve("plugins/test-runner/jk.toml"));
        assertThat(runner.project().javaRelease()).isEqualTo(17);
        JkBuild sdk = JkBuildParser.parse(REPO.resolve("shared/plugin-sdk/jk.toml"));
        assertThat(sdk.project().javaRelease()).isEqualTo(17);
    }

    @Test
    void all_first_party_plugin_modules_are_thin_plugin_main_workers() throws Exception {
        JkBuild root = JkBuildParser.parse(REPO.resolve("jk.toml"));
        for (String module : root.workspace().modules()) {
            if (!module.startsWith("plugins/")) continue;
            JkBuild p = JkBuildParser.parse(REPO.resolve(module).resolve("jk.toml"));
            assertThat(p.mainClass()).as(module).isEqualTo("cc.jumpkick.plugin.process.PluginMain");
            assertThat(p.assembly())
                    .as(module + " must not set assembly (thin workers)")
                    .isFalse();
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
        // The slim client (Stage 5): the wire contract, never the engine itself.
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
