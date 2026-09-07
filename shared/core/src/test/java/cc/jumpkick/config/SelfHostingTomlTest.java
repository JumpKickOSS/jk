// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.layout.SourceLayout;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.lock.LockManifestDigest;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.WorkspaceMerge;
import cc.jumpkick.plugin.manifest.PluginModule;
import cc.jumpkick.testing.RepoRoot;
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

    /** The checkout root. See {@link RepoRoot} for why this cannot come from the JVM's CWD. */
    private static final Path REPO = RepoRoot.find(SelfHostingTomlTest.class);

    @BeforeAll
    static void requireSelfHostingWorkspace() throws Exception {
        // A root jk.toml that is missing or is not a workspace root is a failure, not a reason to
        // skip: every test below reads the manifests underneath it, so an assumption here would
        // report eleven passes for a suite that checked nothing. The comment on the old
        // `assumeTrue` already said "fail hard if it disappears"; now the code does.
        assertThat(JkBuildParser.parse(REPO.resolve("jk.toml")).isWorkspaceRoot())
                .as("root jk.toml at %s declares [workspace]", REPO)
                .isTrue();
    }

    @Test
    void root_jk_toml_declares_the_workspace() throws Exception {
        JkBuild root = JkBuildParser.parse(REPO.resolve("jk.toml"));
        assertThat(root.project().group()).isEqualTo("cc.jumpkick");
        assertThat(root.project().name()).isEqualTo("jk");
        assertThat(root.isWorkspaceRoot()).isTrue();
        // host is the S1/S7 codec + host-primitive leaf; plugin-sdk sits above it. jk-api is a
        // zero-dep model (PluginConfig lives there —).
        // Phase 2 adds thin workers (test-runner, java-compiler) as workspace modules.
        assertThat(root.workspace().modules())
                .containsExactly(
                        "shared/host",
                        "shared/plugin-sdk",
                        "shared/jk-api",
                        "shared/core",
                        "shared/client-io",
                        "shared/dynamic-surface",
                        "shared/guard-api",
                        "server/io",
                        "server/resolver",
                        "shared/toolchain-jdk",
                        "server/toolchain",
                        "shared/wire",
                        "server/guard",
                        "server/engine",
                        "clients/cli",
                        "clients/cli-terminal",
                        "clients/web",
                        "plugins/test-runner",
                        "plugins/java-compiler",
                        "plugins/kotlin-compiler",
                        "plugins/groovy-compiler",
                        "plugins/auditor",
                        "plugins/publisher",
                        "plugins/image-builder",
                        "plugins/minified",
                        "plugins/formatter",
                        "plugins/spring-boot",
                        "plugins/quarkus",
                        "plugins/grails",
                        "plugins/protobuf",
                        "plugins/android",
                        "plugins/micronaut",
                        "packs/spring",
                        "packs/quarkus",
                        "packs/android",
                        "packs/library",
                        "packs/monorepo");
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
        for (Path moduleDir : WorkspaceLoader.loadModules(REPO, root).keySet()) {
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

        LibraryCatalog bundled = LibraryCatalog.bundled();
        LibraryCatalog pins = LibraryCatalog.parse(Files.readString(LibraryCatalog.projectFile(REPO)));
        LibraryCatalog chain = LibraryCatalog.forProject(REPO);
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
        assertThat(LockfileReader.read(lock).manifestsSha256())
                .as("jk-lock.toml manifests-sha256 is stale — re-lock and commit the re-stamp together"
                        + " with the manifest/pin edit: `jk lock` keeps every pinned version and moves"
                        + " only what the edit rules out; `jk lock -F` / `jk update` float on purpose")
                .isEqualTo(LockManifestDigest.compute(REPO));
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
        assertThat(SourceLayout.looksTraditional(REPO.resolve("clients/web"))).isTrue();
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
            if (module.startsWith("packs/")) {
                // A rule pack is a resource-only artifact under its own group, named for its pack.
                assertThat(parsed.project().group()).isEqualTo("cc.jumpkick.guards");
                assertThat(parsed.project().name()).isEqualTo(module.substring("packs/".length()));
                continue;
            }
            assertThat(parsed.project().group()).isEqualTo("cc.jumpkick");
            assertThat(parsed.project().name()).startsWith("jk-");
        }
    }

    @Test
    void thin_worker_plugins_have_plugin_main_without_fat_assembly() throws Exception {
        // workers are thin jars (runtime classpath from the installed POM), not assembly fat jars.
        // Only the engine stays assembly = true for ship.
        for (String module : List.of("plugins/test-runner", "plugins/java-compiler")) {
            JkBuild p = JkBuildParser.parse(REPO.resolve(module).resolve("jk.toml"));
            assertThat(p.assembly()).as(module + " must not fat-assemble").isFalse();
            assertThat(p.minified()).as(module).isFalse();
            assertThat(p.isApplication())
                    .as(module + " must not declare [application]")
                    .isFalse();
            assertThat(p.mainClass()).as(module).isNull();
            assertThat(PluginModule.isWorker(REPO.resolve(module)))
                    .as(module + " is a plugin worker")
                    .isTrue();
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
        JkBuild host = JkBuildParser.parse(REPO.resolve("shared/host/jk.toml"));
        assertThat(host.project().javaRelease()).isEqualTo(17);
    }

    @Test
    void all_first_party_plugin_modules_are_thin_plugin_main_workers() throws Exception {
        JkBuild root = JkBuildParser.parse(REPO.resolve("jk.toml"));
        for (String module : root.workspace().modules()) {
            if (!module.startsWith("plugins/")) continue;
            JkBuild p = JkBuildParser.parse(REPO.resolve(module).resolve("jk.toml"));
            assertThat(p.isApplication())
                    .as(module + " must not declare [application]")
                    .isFalse();
            assertThat(p.mainClass()).as(module).isNull();
            assertThat(PluginModule.isWorker(REPO.resolve(module)))
                    .as(module + " is a plugin worker")
                    .isTrue();
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
        // The slim client (Stage 5): the wire contract + the :host leaf, never the engine itself
        // and never the plugin SPI.
        assertThat(mainModules)
                .contains(
                        "cc.jumpkick:jk-core",
                        "cc.jumpkick:jk-engine-api",
                        "cc.jumpkick:jk-host",
                        "cc.jumpkick:jk-cli-terminal");
        assertThat(mainModules)
                .doesNotContain(
                        "cc.jumpkick:jk-engine",
                        "cc.jumpkick:jk-io",
                        "cc.jumpkick:jk-resolver",
                        "cc.jumpkick:jk-toolchain",
                        "cc.jumpkick:jk-plugin-sdk");

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
        // :cli's last external dep was JLine; after it is gone. Workspace
        // merge still has other externals (zinc, jgit, …) — just not JLine.
        assertThat(mergedRootMain).doesNotContain("org.jline:jline-terminal-ffm");
    }
}
