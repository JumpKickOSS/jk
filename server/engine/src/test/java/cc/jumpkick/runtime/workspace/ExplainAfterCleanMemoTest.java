// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.PreflightMemo;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dirty memo vouches for a module's inputs, not its outputs: it survives {@code jk clean} on
 * purpose, and comes back naming the modules whose outputs are gone. The whole-workspace {@code jk
 * explain} may take its shortcut only when nothing is dirty <em>and</em> nothing needs restoring
 * — otherwise a wiped {@code target/} reads as a fully cached build.
 */
class ExplainAfterCleanMemoTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    @Test
    void a_clean_memo_still_forecasts_the_restore_after_the_outputs_are_gone(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                java = 25
                """);
        Path src = Files.createDirectories(tmp.resolve("src/main/java"));
        Files.writeString(src.resolve("App.java"), "class App {}\n");
        BuildGraph.Result graph = BuildGraph.resolve(tmp, JkBuildParser.parse(tmp.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        BuildLayout layout =
                BuildLayout.of(tmp, tmp, graph.topoOrder().getFirst().manifest());

        // The outputs a build leaves, then the memo it stores: nothing dirty.
        Path classFile = layout.classesDir().resolve("App.class");
        Files.createDirectories(classFile.getParent());
        Files.writeString(classFile, "bytecode");
        Files.createDirectories(layout.mainJar().getParent());
        Files.writeString(layout.mainJar(), "jar");
        var fps = PreflightMemo.snapshotFingerprints(graph, false);
        PreflightMemo.storeDirty(tmp, graph, false, Set.of(), fps.fingerprints());
        Path cache = tmp.resolve("cache");

        ExplainPlan warm = BuildForecasting.explainFromGraph(graph, cache, false, tmp);
        assertThat(warm.modules()).allMatch(m -> !m.dirty());

        // jk clean: the memo stays, the outputs go.
        PathUtil.deleteRecursively(layout.moduleTargetDir());
        var memo = PreflightMemo.tryLoadDirty(tmp, graph, false);
        assertThat(memo).isPresent();
        assertThat(memo.get().dirty()).isEmpty();
        assertThat(memo.get().restoreNeeded())
                .as("the memo names the module whose outputs are gone")
                .isNotEmpty();

        ExplainPlan wiped = BuildForecasting.explainFromGraph(graph, cache, false, tmp);
        assertThat(wiped.modules())
                .as("the module the build will schedule is priced, not hidden behind the memo's clean claim")
                .anyMatch(TaskForecast.Module::dirty);
    }
}
