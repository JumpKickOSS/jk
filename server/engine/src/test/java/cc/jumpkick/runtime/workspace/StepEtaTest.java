// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The step-priced estimate prices each step the forecast runs at its own recent wall from the
 * project's ledger, with no scale on top, and hands the live model each module's steps.
 */
class StepEtaTest {

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
}
