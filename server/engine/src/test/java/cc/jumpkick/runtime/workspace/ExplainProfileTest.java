// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.ModuleInputProvenance;
import cc.jumpkick.runtime.PreflightMemo;
import cc.jumpkick.runtime.SettledHostCalibration;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk explain --profile X} answers for {@code jk build --profile X}: it reads the dirty memo
 * that build stored under X and keys its forecast with X, so after a profile build it reads clean,
 * and its dirty set is the one the build's own preflight computes under the same profile.
 */
@ExtendWith(SettledHostCalibration.class)
class ExplainProfileTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    @Test
    void explain_after_a_profile_build_reads_clean_under_that_profile(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                java = 25

                [profiles.strict]
                javac = ["-Werror"]
                """);
        Path src = Files.createDirectories(tmp.resolve("src/main/java"));
        Files.writeString(src.resolve("App.java"), "class App {}\n");
        JkBuild parsed = JkBuildParser.parse(tmp.resolve("jk.toml"));
        BuildGraph.Result graph = BuildGraph.resolve(tmp, parsed);
        assertThat(graph.hasErrors()).isFalse();
        BuildLayout layout =
                BuildLayout.of(tmp, tmp, graph.topoOrder().getFirst().manifest());
        Path cache = Files.createDirectories(tmp.resolve("cache"));

        // What `jk build --profile strict` leaves: its outputs and their provenance, and a memo under
        // strict with nothing dirty.
        Path classFile = layout.classesDir().resolve("App.class");
        Files.createDirectories(classFile.getParent());
        Files.writeString(classFile, "bytecode");
        Files.createDirectories(layout.mainJar().getParent());
        Files.writeString(layout.mainJar(), "jar");
        var fps = PreflightMemo.snapshotFingerprints(graph, false);
        PreflightMemo.storeDirty(tmp, graph, false, "strict", Set.of(), fps.fingerprints());
        ModuleInputProvenance.record(tmp, graph, fps.fingerprints());

        Session session = Session.defaults().withCacheDir(cache);
        ExplainPlan strict = ExplainReport.compute(
                        tmp, parsed, cache, session, new ExplainReport.Knobs("strict", 0, false))
                .plan();
        assertThat(dirty(strict))
                .as("explain --profile strict reads the strict build's memo")
                .isEmpty();
        assertThat(dirty(strict)).isEqualTo(buildDirty(session, graph, cache, tmp, "strict"));

        ExplainPlan plain = ExplainReport.compute(tmp, parsed, cache, session, ExplainReport.Knobs.defaults())
                .plan();
        assertThat(dirty(plain))
                .as("the default profile has no memo of its own: explain and build both schedule the module")
                .isNotEmpty();
        assertThat(dirty(plain)).isEqualTo(buildDirty(session, graph, cache, tmp, null));
    }

    private static Set<Path> dirty(ExplainPlan plan) {
        return plan.modules().stream()
                .filter(TaskForecast.Module::dirty)
                .map(m -> BuildGraph.canonicalPath(m.dir()))
                .collect(Collectors.toSet());
    }

    /** The modules the build's preflight schedules under {@code profile}. */
    private static Set<Path> buildDirty(
            Session session, BuildGraph.Result graph, Path cache, Path entryDir, @Nullable String profile)
            throws Exception {
        BuildForecasting.Preflight pf = SessionContext.where(
                session,
                () -> BuildForecasting.forecastWithFingerprints(
                        graph, cache, false, entryDir, WorkspaceTarget.PACKAGE, false, profile));
        return Stream.concat(pf.dirty().stream(), pf.restoreNeeded().stream())
                .map(BuildGraph::canonicalPath)
                .collect(Collectors.toSet());
    }
}
