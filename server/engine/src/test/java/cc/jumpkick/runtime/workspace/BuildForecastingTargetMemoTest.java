// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.PreflightMemo;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The preflight dirty memo certifies a module's inputs, which is the lifecycle's whole question
 * for every goal that packages. A goal's tail (a native image, an install, an image push) asks
 * its own question in {@link WorkspacePreflightPhase#withRunningTails}, so a memo hit can never
 * hide a missing binary or an unshelved jar.
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
        Files.createDirectories(BuildLayout.moduleTargetDir(tmp));
        return BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
    }

    /** Every goal past PACKAGE prices {@code jk build}'s lifecycle; its tail is priced apart. */
    @Test
    void every_goal_that_packages_prices_the_lifecycle_jk_build_prices() {
        for (WorkspaceTarget target : WorkspaceTarget.values()) {
            WorkspaceTarget core = WorkspacePreflightPhase.coreTarget(target);
            switch (target) {
                case COMPILE, TEST -> assertThat(core).as(target.name()).isEqualTo(target);
                case RESHELVE -> assertThat(core).as(target.name()).isNull();
                default -> assertThat(core).as(target.name()).isEqualTo(WorkspaceTarget.PACKAGE);
            }
        }
    }

    /**
     * The memo vouches for inputs, so an install, a native image or an image push reads it like a
     * build: clean with outputs present, a restore when the jar is gone, dirty after an edit.
     */
    @Test
    void the_lifecycle_memo_answers_for_every_packaging_goal(@TempDir Path tmp) throws Exception {
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

        var clean = BuildForecasting.forecastWithFingerprints(
                graph, tmp.resolve("cache"), false, tmp, WorkspaceTarget.PACKAGE, false, null);
        assertThat(clean.dirty()).isEmpty();
        assertThat(clean.restoreNeeded()).isEmpty();

        Files.delete(layout.mainJar());
        var missing = BuildForecasting.forecastWithFingerprints(
                graph, tmp.resolve("cache"), false, tmp, WorkspaceTarget.PACKAGE, false, null);
        assertThat(missing.restoreNeeded()).isNotEmpty();

        Files.write(layout.mainJar(), new byte[] {1, 2, 3});
        Files.writeString(tmp.resolve("src/main/java/App.java"), "class App { int changed; }\n");
        var edited = BuildForecasting.forecastWithFingerprints(
                graph, tmp.resolve("cache"), false, tmp, WorkspaceTarget.PACKAGE, false, null);
        assertThat(edited.dirty()).isNotEmpty();
    }
}
