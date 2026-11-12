// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.plugin.MemoryProbe;
import cc.jumpkick.run.ContextPropagator;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.SettledHostCalibration;
import cc.jumpkick.task.JavaCompile;
import cc.jumpkick.test.TestWorkers;
import cc.jumpkick.wire.runtime.ExplainPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * HARD INVARIANT: {@code jk explain} and {@code jk build}'s countdown seed use one function
 * ({@link BuildService#estimateEtaMillis}) with the same defaults, and that function prices the
 * plan it is handed whichever compiler workers the forecast behind it could ask for a plan. If
 * these diverge, the feature is actively misleading — see docs/contributors/progress-contract.md.
 */
@ExtendWith(SettledHostCalibration.class)
class ExplainBuildEtaParityTest {

    @Test
    void auto_workers_resolve_wider_than_forced_serial() {
        // Both commands pass 0 ("auto") when -w is omitted, and 0 must not resolve like 1. With no
        // class-wall history the modest default is 2, still wider than a forced serial run, so an
        // explain does not price the suite as one JVM. The claim that the two defaults ARE both 0
        // is not testable from :engine; BuildExplainPlanOptionsParityTest in :cli parses one argv
        // against both commands and compares the plan inputs they derive.
        // On a host with room: the heap clamp would otherwise answer how loaded this machine is.
        MemoryProbe.forTests(new MemoryProbe.Memory(32L << 30, 16L << 30));
        try {
            int auto = TestWorkers.resolve(0, 24, 8);
            int forcedOne = TestWorkers.resolve(1, 24, 8);
            assertThat(auto).isGreaterThan(forcedOne);
            assertThat(forcedOne).isEqualTo(1);
        } finally {
            MemoryProbe.forTests(null);
        }
    }

    @Test
    void eta_concurrency_is_the_executors_cap_not_the_ready_width() {
        // The live scheduler admits a dependent once its prerequisites have compiled, so a build
        // keeps as many modules in flight as the jobs cap allows, whatever the graph's width under
        // full-completion semantics. The request's -j is the cap; -j1 is serial.
        assertThat(BuildService.etaConcurrency(8)).isEqualTo(8);
        assertThat(BuildService.etaConcurrency(1)).isEqualTo(1);
        // No cap on the request: the engine's own resolved jobs, the same number the build verb
        // resolves a non-positive wire value to.
        assertThat(BuildService.etaConcurrency(0)).isEqualTo(TestWorkers.effectiveJobs());
    }

    @Test
    void a_plan_prices_the_same_whichever_workers_its_forecast_could_plan_on(@TempDir Path tmp) throws Exception {
        // The worker rule decides how the forecast predicts a compile, never how the ETA prices the
        // prediction: explain plans on any worker, jk status on a warm one, the build's background
        // pricing on none, and one plan is one number under all three.
        Path dir = tmp.resolve("m");
        var compile =
                new TaskForecast.Task(TaskNames.COMPILE_JAVA, TaskForecast.Status.PARTIAL, "compile · 2 sources", null);
        var tests = new TaskForecast.Task(TaskNames.RUN_TESTS, TaskForecast.Status.RUN, "", null);
        var module = new TaskForecast.Module(dir, "ex:m", List.of(compile, tests), 40, 12, true, false);
        ExplainPlan plan = new ExplainPlan(List.of(module), Map.of(dir, Set.<Path>of()), 1, List.of());
        List<Long> etas = new ArrayList<>();
        for (JavaCompile.PlanWorkers rule : JavaCompile.PlanWorkers.values()) {
            etas.add(ScopedValue.where(JavaCompile.PLAN_WORKERS, rule)
                    .call(() -> BuildService.estimateEtaMillis(
                            plan, tmp, tmp.resolve("c"), 0, null, null, false, false, true, 8)));
        }
        assertThat(etas.getFirst()).isPositive();
        assertThat(etas).containsOnly(etas.getFirst());
    }

    @Test
    void the_build_prices_its_eta_beside_its_compiles_planning_on_no_worker() throws Exception {
        // The build's own compiles run on the resident workers while it prices: a plan asked of
        // them would queue ahead of a module's compile. Explain asks them (ANY); the background
        // pricing must not, so its compiles are predicted from their prior records.
        AtomicReference<JavaCompile.@Nullable PlanWorkers> seen = new AtomicReference<>();
        CountDownLatch published = new CountDownLatch(1);
        BackgroundEta eta = BackgroundEta.start(
                () -> {
                    seen.set(ContextPropagator.get(JavaCompile.PLAN_WORKERS));
                    return new BuildService.EtaModel(1L, List.of(), 1, true);
                },
                m -> published.countDown());
        assertThat(published.await(10, TimeUnit.SECONDS)).isTrue();
        eta.close();
        assertThat(seen.get()).isEqualTo(JavaCompile.PlanWorkers.NONE);
    }

    @Test
    void estimate_eta_is_deterministic_for_identical_inputs(@TempDir Path tmp) {
        ExplainPlan empty = new ExplainPlan(List.of(), Map.of(), 1, List.of());
        long a = BuildService.estimateEtaMillis(empty, tmp, tmp.resolve("c"), 0, null, null, false, false, true, 8);
        long b = BuildService.estimateEtaMillis(empty, tmp, tmp.resolve("c"), 0, null, null, false, false, true, 8);
        assertThat(a).isEqualTo(b).isZero();
    }

    @Test
    void fully_cached_explain_plan_is_not_dirty_and_etas_to_zero(@TempDir Path tmp) throws Exception {
        // Mirrors the dirty-memo fast path: no TaskForecaster, empty steps, ETA 0.
        Path mod = tmp.resolve("m");
        Files.createDirectories(mod);
        Files.writeString(mod.resolve("jk.toml"), """
                group = "ex"
                name = "m"
                version = "1.0"
                java = 25
                """);
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "ex"
                name = "ws"
                version = "1.0"
                java = 25

                [workspace]
                modules = ["m"]
                """);
        var graph = BuildGraph.resolve(tmp, JkBuildParser.parse(tmp.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        ExplainPlan plan = BuildService.fullyCachedExplainPlan(graph);
        assertThat(plan.modules()).isNotEmpty();
        assertThat(plan.modules()).allMatch(m -> !m.dirty());
        assertThat(BuildService.estimateEtaMillis(plan, tmp, tmp.resolve("c"), 0, null, null, false, false, true, 8))
                .isZero();
    }
}
