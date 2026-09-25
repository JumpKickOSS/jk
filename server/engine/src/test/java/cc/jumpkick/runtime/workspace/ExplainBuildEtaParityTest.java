// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.test.TestWorkers;
import cc.jumpkick.wire.runtime.ExplainPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * HARD INVARIANT: {@code jk explain} and {@code jk build}'s countdown seed use one function
 * ({@link BuildService#estimateEtaMillis}) with the same defaults. If these diverge, the feature
 * is actively misleading — see docs/perf/progress-contract.md.
 */
class ExplainBuildEtaParityTest {

    @Test
    void auto_workers_resolve_wider_than_forced_serial() {
        // Both commands pass 0 ("auto") when -w is omitted, and 0 must not resolve like 1. With no
        // class-wall history the modest default is 2, still wider than a forced serial run, so an
        // explain does not price the suite as one JVM. The claim that the two defaults ARE both 0
        // is not testable from :engine — BuildCommand and ExplainCommand live in clients/cli — and
        // the test that pretended otherwise wrote `int a = 0; int b = 0; assertThat(a).isEqualTo(b)`.
        // That half is now BuildExplainPlanOptionsParityTest in :cli, which parses one argv against
        // both commands and compares the PlanOptions they derive.
        int auto = TestWorkers.resolve(0, 24, 8);
        int forcedOne = TestWorkers.resolve(1, 24, 8);
        assertThat(auto).isGreaterThan(forcedOne);
        assertThat(forcedOne).isEqualTo(1);
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
    void estimate_eta_is_deterministic_for_identical_inputs(@org.junit.jupiter.api.io.TempDir Path tmp) {
        ExplainPlan empty = new ExplainPlan(List.of(), Map.of(), 1, List.of());
        long a = BuildService.estimateEtaMillis(empty, tmp, tmp.resolve("c"), 0, null, null, false, false, true, 8);
        long b = BuildService.estimateEtaMillis(empty, tmp, tmp.resolve("c"), 0, null, null, false, false, true, 8);
        assertThat(a).isEqualTo(b).isZero();
    }

    @Test
    void fully_cached_explain_plan_is_not_dirty_and_etas_to_zero(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
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
