// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.ModuleInputProvenance;
import cc.jumpkick.runtime.PreflightMemo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The clean memo a successful build leaves behind may only vouch for the inputs that build
 * consumed. A source saved while the build runs is not one of them, and a memo that certified it
 * would make the next build report "all modules up to date" over a jar without the edit.
 */
class WorkspaceFinalPhaseTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    private static BuildGraph.Result project(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                java = 25
                """);
        Path src = tmp.resolve("src/main/java");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "class App {}\n");
        Files.createDirectories(BuildLayout.moduleTargetDir(tmp));
        return BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
    }

    /** The defect: an edit between the preflight walk and the memo store was recorded as built. */
    @Test
    void an_edit_after_the_preflight_walk_is_not_certified_clean(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = project(tmp);
        Path module = tmp.toAbsolutePath().normalize();
        Map<Path, String> preWalk =
                PreflightMemo.snapshotFingerprints(graph, false).fingerprints();
        var forecast = new BuildForecasting.Preflight(Set.of(module), preWalk);
        // The build read App.java as it was; this save lands before the build finishes.
        Files.writeString(tmp.resolve("src/main/java/App.java"), "class App { int edited; }\n");

        WorkspaceFinalPhase.certifyClean(tmp, graph, false, null, Optional.of(forecast));

        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false))
                .as("the memo cannot vouch for a tree it never fingerprinted")
                .isEmpty();
        String now =
                PreflightMemo.snapshotFingerprints(graph, false).fingerprints().get(module);
        assertThat(ModuleInputProvenance.outputsFromOtherInputs(
                        tmp, module, graph.topoOrder().getFirst().manifest(), now))
                .as("the outputs on disk are recorded as coming from the pre-edit inputs")
                .isTrue();
        Set<Path> dirty = SessionContext.where(
                Session.defaults().withCacheDir(tmp.resolve("cache")),
                () -> BuildForecasting.forecastWithFingerprints(graph, tmp.resolve("cache"), false, tmp)
                        .dirty());
        assertThat(dirty).as("the next build schedules the module").contains(module);
    }

    /** Nothing captured before the build is nothing the memo may vouch for. */
    @Test
    void without_captured_fingerprints_nothing_is_certified(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = project(tmp);

        WorkspaceFinalPhase.certifyClean(tmp, graph, false, null, Optional.empty());

        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isEmpty();
    }

    /**
     * A forced build skips the forecast walk but still fingerprints the tree before it runs, so a
     * save during the build is seen by the next one.
     */
    @ParameterizedTest
    @ValueSource(strings = {"force", "redo"})
    void a_forced_build_never_certifies_an_edit_made_while_it_ran(String mode, @TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = project(tmp);
        Path module = tmp.toAbsolutePath().normalize();
        Path cache = tmp.resolve("cache");
        JkConfig forced = mode.equals("force")
                ? JkConfig.empty().withForce(true)
                : JkConfig.empty().withRebuild(true);
        var forecast = SessionContext.where(
                Session.defaults().withCacheDir(cache).withConfig(forced),
                () -> BuildForecasting.forecastWithFingerprints(graph, cache, false, tmp));
        assertThat(forecast.dirty()).containsExactly(module);
        Files.writeString(tmp.resolve("src/main/java/App.java"), "class App { int edited; }\n");

        WorkspaceFinalPhase.certifyClean(tmp, graph, false, null, Optional.of(forecast));

        Set<Path> dirty = SessionContext.where(
                Session.defaults().withCacheDir(cache),
                () -> BuildForecasting.forecastWithFingerprints(graph, cache, false, tmp)
                        .dirty());
        assertThat(dirty).as("the next build schedules the module").contains(module);
    }
}
