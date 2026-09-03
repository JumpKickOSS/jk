// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockManifestDigest;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *: an image push/load/tarball write is a side-effect, never a cacheable output. A
 * workspace member whose package steps are all clean must still forecast dirty under
 * {@code target=IMAGE} so the scheduler runs its image tail — otherwise a second consecutive
 * {@code jk image} reports success having pushed nothing.
 */
class TaskForecasterImageTargetTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    private static Path workspaceWithModule(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.1.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["app"]
                """);
        Path app = Files.createDirectories(tmp.resolve("app")).toRealPath();
        Files.writeString(app.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                jdk = 25
                java = 25
                """);
        Lockfile lf = new Lockfile(
                Lockfile.CURRENT_VERSION, "test", "pubgrub-v1", null, null, List.of(), List.of(), List.of());
        LockfileWriter.write(lf, tmp.resolve("jk-lock.toml"), LockManifestDigest.compute(tmp));
        // Package output present ⇒ clean under PACKAGE (SourcelessModuleForecastTest semantics).
        var build = JkBuildParser.parse(app.resolve("jk.toml"));
        Path jar = BuildLayout.of(app, build).mainJar();
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[] {0x50, 0x4b, 0x05, 0x06});
        return app;
    }

    @Test
    void clean_module_still_forecasts_write_image_under_image_target(@TempDir Path tmp) throws Exception {
        Path root = tmp.toRealPath();
        Path app = workspaceWithModule(root);
        BuildGraph.Result graph =
                BuildGraph.resolve(root, JkBuildParser.parse(Files.readString(root.resolve("jk.toml"))));
        assertThat(graph.hasErrors()).isFalse();
        Path cache = root.resolve("cache");

        SessionContext.where(Session.defaults(), () -> {
            var cas = JkStores.storeCas();
            var ac = new ActionCache(JkStores.cacheCas(cache), cache.resolve("actions"));

            // Baseline: clean under PACKAGE.
            var pkg = TaskForecaster.of(graph, cas, ac, cache, false, WorkspaceTarget.PACKAGE, Set.of());
            assertThat(pkg.stream()
                            .filter(m -> m.dir().equals(app))
                            .findFirst()
                            .orElseThrow()
                            .dirty())
                    .isFalse();

            // IMAGE with the module in the terminal set: write-image RUN ⇒ dirty.
            var img = TaskForecaster.of(graph, cas, ac, cache, false, WorkspaceTarget.IMAGE, Set.of(app));
            var m = img.stream().filter(x -> x.dir().equals(app)).findFirst().orElseThrow();
            assertThat(m.dirty()).isTrue();
            assertThat(m.steps()).anyMatch(s -> "write-image".equals(s.name()) && !s.cached());

            // IMAGE but NOT in the terminal set (unselected prereq): stays clean.
            var pre = TaskForecaster.of(graph, cas, ac, cache, false, WorkspaceTarget.IMAGE, Set.of());
            assertThat(pre.stream()
                            .filter(x -> x.dir().equals(app))
                            .findFirst()
                            .orElseThrow()
                            .dirty())
                    .isFalse();
            return null;
        });
    }

    @Test
    void terminalTargetDirs_mirrors_assemblePlan_eligibility(@TempDir Path tmp) throws Exception {
        Path root = tmp.toRealPath();
        Path app = workspaceWithModule(root);
        var rootBuild = JkBuildParser.parse(Files.readString(root.resolve("jk.toml")));
        BuildGraph.Result graph = BuildGraph.resolve(root, rootBuild);
        var units = graph.topoOrder();
        Path cache = root.resolve("cache");

        WorkspaceRequest base = new WorkspaceRequest(root, cache, null, 0, null, false, false, 0, null, true, true);

        // PACKAGE: no terminal dirs.
        assertThat(WorkspacePreflightPhase.terminalTargetDirs(units, base)).isEmpty();

        // IMAGE, selected: the module.
        WorkspaceRequest image = base.withSpec(WorkspaceSpec.image(Set.of(app), null, null, null, null, null));
        assertThat(WorkspacePreflightPhase.terminalTargetDirs(units, image)).containsExactly(app);

        // IMAGE, empty selection: whole graph.
        WorkspaceRequest imageAll = base.withSpec(WorkspaceSpec.of(WorkspaceTarget.IMAGE));
        assertThat(WorkspacePreflightPhase.terminalTargetDirs(units, imageAll)).containsExactly(app);

        // NATIVE needs a resolvable Graal home; without one the module is not a terminal.
        WorkspaceRequest nat = base.withSpec(WorkspaceSpec.nativeImage(Set.of(app), Map.of(), null, List.of()));
        assertThat(WorkspacePreflightPhase.terminalTargetDirs(units, nat)).isEmpty();
        WorkspaceRequest natWithHome = base.withSpec(
                WorkspaceSpec.nativeImage(Set.of(app), Map.of(app, root.resolve("graal")), null, List.of()));
        assertThat(WorkspacePreflightPhase.terminalTargetDirs(units, natWithHome))
                .containsExactly(app);

        // INSTALL: every cone module is a cache-install terminal (prereqs included).
        WorkspaceRequest inst = base.withSpec(WorkspaceSpec.install(Set.of(), Map.of(), null));
        assertThat(WorkspacePreflightPhase.terminalTargetDirs(units, inst)).containsExactly(app);
        WorkspaceRequest instSelected = base.withSpec(WorkspaceSpec.install(Set.of(app), Map.of(), null));
        assertThat(WorkspacePreflightPhase.terminalTargetDirs(units, instSelected))
                .containsExactly(app);
    }
}
