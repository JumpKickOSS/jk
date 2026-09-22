// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.PreflightMemo;
import cc.jumpkick.wire.runtime.TaskForecast;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *: the preflight dirty memo certifies package outputs only. A terminal-target run
 * (native/image) hitting a PACKAGE-stored all-clean memo would skip the target-aware forecast
 * and never produce its binary ({@code jk build && jk native} reporting success with no
 * artifact). Terminal targets must neither consult nor store the memo.
 */
class BuildForecastingTargetMemoTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    private static BuildGraph.Result writeProjectAndResolve(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                jdk = 25
                java = 25
                """);
        Path src = tmp.resolve("src/main/java");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "class App {}\n");
        // Clean-claim requires the module output tree to exist.
        Files.createDirectories(BuildLayout.moduleTargetDir(tmp, tmp));
        return BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
    }

    @Test
    void native_target_bypasses_and_preserves_the_package_memo(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = writeProjectAndResolve(tmp);
        assertThat(graph.hasErrors()).isFalse();

        // A successful jk build stores an all-clean memo.
        var fps = PreflightMemo.snapshotFingerprints(graph, false);
        PreflightMemo.storeDirty(tmp, graph, false, Set.of(), fps.fingerprints());
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isPresent();

        Path cache = tmp.resolve("cache");

        // PACKAGE trusts the memo: fully cached, no forecast walk.
        var pkg = BuildForecasting.forecastWithFingerprints(graph, cache, false, tmp, WorkspaceTarget.PACKAGE);
        assertThat(pkg.dirty()).isEmpty();

        // NATIVE must ignore the memo and run the target-aware walk (fresh cache ⇒ dirty).
        var nat = BuildForecasting.forecastWithFingerprints(graph, cache, false, tmp, WorkspaceTarget.NATIVE);
        assertThat(nat.dirty()).isNotEmpty();

        // ...and must not have overwritten the package memo with its own verdict.
        var reload = PreflightMemo.tryLoadDirty(tmp, graph, false);
        assertThat(reload).isPresent();
        assertThat(reload.get().dirty()).isEmpty();

        // IMAGE behaves like NATIVE.
        var img = BuildForecasting.forecastWithFingerprints(graph, cache, false, tmp, WorkspaceTarget.IMAGE);
        assertThat(img.dirty()).isNotEmpty();
    }

    @Test
    void an_install_memo_hit_with_jars_present_is_shelf_only(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = writeProjectAndResolve(tmp);
        BuildGraph.BuildUnit unit = graph.topoOrder().getFirst();
        JkBuild project = unit.manifest();
        BuildLayout layout = BuildLayout.of(tmp, unit.dir(), project);
        Files.createDirectories(layout.classesDir());
        Files.writeString(layout.classesDir().resolve("App.class"), "class");
        Files.createDirectories(layout.mainJar().getParent());
        Files.write(layout.mainJar(), new byte[] {1, 2, 3});
        var fps = PreflightMemo.snapshotFingerprints(graph, false);
        PreflightMemo.storeDirty(tmp, graph, false, Set.of(), fps.fingerprints());

        var install = BuildForecasting.forecastWithFingerprints(
                graph, tmp.resolve("cache"), false, tmp, WorkspaceTarget.INSTALL, Set.of(unit.dir()), false);
        assertThat(install.modules()).isNotEmpty().allMatch(TaskForecast.Module::shelfOnly);
        assertThat(install.modules().getFirst().steps())
                .extracting(TaskForecast.Task::name)
                .containsExactly(TaskNames.CACHE_INSTALL);

        Files.delete(layout.mainJar());
        var missing = BuildForecasting.forecastWithFingerprints(
                graph, tmp.resolve("cache"), false, tmp, WorkspaceTarget.INSTALL, Set.of(unit.dir()), false);
        assertThat(missing.modules()).noneMatch(TaskForecast.Module::shelfOnly);
        assertThat(missing.restoreNeeded()).isNotEmpty();

        Files.write(layout.mainJar(), new byte[] {1, 2, 3});
        Files.writeString(tmp.resolve("src/main/java/App.java"), "class App { int changed; }\n");
        var edited = BuildForecasting.forecastWithFingerprints(
                graph, tmp.resolve("cache"), false, tmp, WorkspaceTarget.INSTALL, Set.of(unit.dir()), false);
        assertThat(edited.dirty()).isNotEmpty();
        assertThat(edited.modules()).noneMatch(TaskForecast.Module::shelfOnly);
    }
}
