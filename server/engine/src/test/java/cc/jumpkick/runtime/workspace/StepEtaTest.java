// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.compile.JavaCompilerHost;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.runtime.base.RunShapes;
import cc.jumpkick.runtime.base.StepLedger;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.StepCost;
import cc.jumpkick.wire.runtime.WorkModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The step-priced estimate prices each step the forecast runs at its own recent wall from the
 * project's ledger, with no scale on top, and hands the live model each module's steps.
 */
class StepEtaTest {

    /** A warm resident compiler, so each step reads at its ledger wall (CompilerWarmUp prices a cold one). */
    @BeforeEach
    void warmCompiler() {
        CompilerWarmUp.warmthForTests(() -> 1_000);
    }

    @AfterEach
    void liveCompiler() {
        CompilerWarmUp.warmthForTests(JavaCompilerHost::warmth);
    }

    @Test
    void each_forecast_step_is_priced_at_its_ledger_wall(@TempDir Path ws) throws Exception {
        Path app = ws.resolve("app");
        String dir = app.toString();
        StepLedger.append(
                ProjectBuilds.projectHome(ws),
                RunShapes.Shape.DEFAULT,
                List.of(
                        new StepLedger.Sample(dir, "compile-java", "SUCCESS", 6_000),
                        new StepLedger.Sample(dir, "run-tests", "SUCCESS", 90_000),
                        new StepLedger.Sample(dir, "package-jar", "SKIPPED", 300)));
        var module = new TaskForecast.Module(
                app,
                "g:app",
                List.of(
                        new TaskForecast.Task("compile-java", TaskForecast.Status.PARTIAL, "compile · 1 source", null),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "run tests · ~100 tests", null),
                        new TaskForecast.Task("package-jar", TaskForecast.Status.CACHED, "", "k")),
                10,
                100,
                true,
                false);
        ExplainPlan plan = new ExplainPlan(List.of(module), Map.of(app, Set.of()), 1, List.of());

        BuildService.EtaModel model = SessionContext.where(
                Session.defaults(),
                () -> BuildEta.estimateEtaModel(plan, ws, ws.resolve("cache"), 1, null, null, false, false, false, 0));

        assertThat(model.steps()).containsOnlyKeys(app);
        assertThat(model.steps().get(app))
                .as("each priced at its ledger wall; its range is StepLedger's")
                .usingRecursiveFieldByFieldElementComparatorOnFields("step", "weight", "kind")
                .containsExactly(
                        new StepCost("compile-java", 40, StepCost.Kind.PRE),
                        new StepCost("run-tests", 600, StepCost.Kind.TEST),
                        new StepCost("package-jar", 2, StepCost.Kind.PRE));
        assertThat(model.etaMs()).isEqualTo(642L * WorkModel.MS_PER_WEIGHT);
    }

    @Test
    void the_guard_chain_is_a_lane_beside_the_suite_and_the_root_lanes_are_tails() {
        assertThat(StepEta.kindOf("compile-java")).isEqualTo(StepCost.Kind.PRE);
        assertThat(StepEta.kindOf("compile-test")).isEqualTo(StepCost.Kind.PRE);
        assertThat(StepEta.kindOf("run-tests")).isEqualTo(StepCost.Kind.TEST);
        assertThat(StepEta.kindOf("compile-guard")).isEqualTo(StepCost.Kind.LANE);
        assertThat(StepEta.kindOf("guard")).isEqualTo(StepCost.Kind.LANE);
        assertThat(StepEta.kindOf("guard-workspace")).isEqualTo(StepCost.Kind.TAIL);
        assertThat(StepEta.kindOf("package-assembly")).isEqualTo(StepCost.Kind.TAIL);
    }

    @Test
    void a_forced_builds_stepless_module_runs_what_the_last_forced_build_ran_there(@TempDir Path home) {
        Path app = home.resolve("app");
        RunShapes.Shape full = new RunShapes.Shape("", true, false);
        StepLedger.append(
                home,
                full,
                List.of(
                        new StepLedger.Sample(app.toString(), "compile-java", "SUCCESS", 1_300),
                        new StepLedger.Sample(app.toString(), "run-tests", "SUCCESS", 4_000)));
        StepLedger ledger = StepLedger.load(home);
        TaskForecast.Module clean = new TaskForecast.Module(app, "g:app", List.of(), 10, 100, false, false);

        assertThat(StepEta.stepsOf(clean, true, ledger, full))
                .extracting(TaskForecast.Task::name)
                .containsExactly("compile-java", "run-tests");
        assertThat(StepEta.stepsOf(clean, false, ledger, RunShapes.Shape.DEFAULT))
                .as("not forced: a clean module runs nothing")
                .isEmpty();
    }
}
