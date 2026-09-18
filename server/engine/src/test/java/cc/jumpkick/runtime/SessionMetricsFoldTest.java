// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.runtime.base.StepTimings;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every priced step reads the session's harvested metrics through one of three views — the step
 * stats, the per-unit rates and the per-class test walls. Each view is folded once per aggregate
 * window and shared, so pricing a dirty monorepo costs one fold per view rather than one per step
 * per module.
 */
class SessionMetricsFoldTest {

    private String previousStateDir;
    private Session previousSession;

    @BeforeEach
    void isolate() {
        previousStateDir = System.getProperty("jk.env.JK_STATE_DIR");
        previousSession = SessionContext.current();
    }

    @AfterEach
    void restore() {
        SessionContext.install(previousSession);
        BuildMetrics.clearSessionAggregatesMemo();
        BuildMetrics.clearMemo();
        if (previousStateDir == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", previousStateDir);
    }

    private static void harvest(Path state) throws Exception {
        Path home = state.resolve("builds").resolve("projects").resolve("demo-home");
        Files.createDirectories(home);
        Files.writeString(home.resolve(ProjectBuilds.PROJECT_METRICS), """
                [mean]
                invocation.build.wall-ms = 4200
                task.compile-java.wall-ms = 800
                module./ws/app.task.run-tests.per-unit-ms = 12.5
                [count]
                invocation.build.wall-ms = 9
                task.compile-java.wall-ms = 9

                [test-class."/ws/app"]
                com.example.AppTest = 900
                com.example.SlowTest = 5000

                [test-class."/ws/lib"]
                com.example.LibTest = 300
                """);
        Files.writeString(state.resolve("builds").resolve(ProjectBuilds.HOST_METRICS), """
                [mean]
                task.compile-java.wall-ms = 750
                """);
    }

    @Test
    void each_view_is_folded_once_per_aggregate_window(@TempDir Path state) throws Exception {
        harvest(state);
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
        Path engineCwd = Files.createDirectories(state.resolve("engine"));
        SessionContext.install(Session.defaults().withWorkingDir(engineCwd));
        BuildMetrics.clearSessionAggregatesMemo();
        BuildMetrics.clearMemo();
        BuildMetrics.resetSessionFoldCount();

        for (int i = 0; i < 5; i++) {
            assertThat(BuildMetrics.load(BuildMetrics.defaultFile()).step("", "compile-java"))
                    .isPresent();
            assertThat(StepTimings.load(StepTimings.defaultFile()).perUnit("/ws/app", "run-tests"))
                    .hasValue(12.5);
            assertThat(EffortWeights.loadClassWalls("/ws/app"))
                    .containsEntry("com.example.AppTest", 900L)
                    .containsEntry("com.example.SlowTest", 5000L)
                    .doesNotContainKey("com.example.LibTest");
        }
        assertThat(BuildMetrics.sessionFoldCount())
                .as("fifteen loads across three views fold three times")
                .isEqualTo(3);

        // A new window is a new fold, once, for the views that are asked for.
        BuildMetrics.clearSessionAggregatesMemo();
        BuildMetrics.load(BuildMetrics.defaultFile());
        BuildMetrics.load(BuildMetrics.defaultFile());
        assertThat(BuildMetrics.sessionFoldCount()).isEqualTo(4);
    }

    @Test
    void the_class_wall_index_answers_a_module_it_never_saw_with_nothing(@TempDir Path state) throws Exception {
        harvest(state);
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
        SessionContext.install(Session.defaults().withWorkingDir(Files.createDirectories(state.resolve("engine"))));
        BuildMetrics.clearSessionAggregatesMemo();

        assertThat(EffortWeights.loadClassWalls("/ws/nowhere")).isEmpty();
        assertThat(EffortWeights.loadClassWalls("/ws/lib")).containsOnlyKeys("com.example.LibTest");
    }
}
