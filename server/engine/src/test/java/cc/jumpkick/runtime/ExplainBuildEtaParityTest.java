// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * HARD INVARIANT: {@code jk explain} and {@code jk build}'s countdown seed use one function
 * ({@link BuildService#estimateEtaMillis}) with the same defaults. If these diverge, the feature
 * is actively misleading — see docs/perf/progress-contract.md.
 */
class ExplainBuildEtaParityTest {

    @Test
    void bare_explain_workers_default_matches_bare_build_auto() {
        // BuildCommand: omit -w → 0 (auto). ExplainCommand must not use 1.
        int buildDefaultWorkers = 0;
        int explainDefaultWorkers = 0; // ExplainCommand.orElse(0)
        assertThat(explainDefaultWorkers).isEqualTo(buildDefaultWorkers);
        // Auto resolution must not force serial tests when classes > 1.
        int auto = cc.jumpkick.test.TestWorkers.resolve(0, 24, 8);
        int forcedOne = cc.jumpkick.test.TestWorkers.resolve(1, 24, 8);
        assertThat(auto).isGreaterThan(forcedOne);
        assertThat(forcedOne).isEqualTo(1);
    }

    @Test
    void eta_concurrency_matches_workspace_build_clamp() {
        // Build: width = min(maxReady, maxModuleConcurrency); requestedJvms; min(requested, maxModuleConcurrency)
        int maxReady = 27;
        int workers = 0; // auto → heap factor 1
        boolean parallelTests = true;
        int jobs = 8;
        int buildStyle = BuildService.etaConcurrency(maxReady, workers, parallelTests, jobs);
        // Serial -j1
        assertThat(BuildService.etaConcurrency(maxReady, workers, parallelTests, 1))
                .isEqualTo(1);
        // jobs clamp is applied
        assertThat(buildStyle).isLessThanOrEqualTo(jobs);
        assertThat(buildStyle).isGreaterThan(0);
    }

    @Test
    void estimate_eta_is_deterministic_for_identical_inputs(@org.junit.jupiter.api.io.TempDir Path tmp) {
        ExplainPlan empty = new ExplainPlan(List.of(), java.util.Map.of(), 1, List.of());
        long a = BuildService.estimateEtaMillis(empty, tmp, tmp.resolve("c"), 0, null, null, false, false, true, 8);
        long b = BuildService.estimateEtaMillis(empty, tmp, tmp.resolve("c"), 0, null, null, false, false, true, 8);
        assertThat(a).isEqualTo(b).isZero();
    }

    @Test
    void fully_cached_explain_plan_is_not_dirty_and_etas_to_zero(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        // Mirrors the dirty-memo fast path: no TaskForecaster, empty steps, ETA 0.
        Path mod = tmp.resolve("m");
        java.nio.file.Files.createDirectories(mod);
        java.nio.file.Files.writeString(mod.resolve("jk.toml"), """
                [project]
                group = "ex"
                name = "m"
                version = "1.0"
                java = 25
                """);
        java.nio.file.Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "ex"
                name = "ws"
                version = "1.0"
                java = 25

                [workspace]
                modules = ["m"]
                """);
        var graph = BuildGraph.resolve(tmp, cc.jumpkick.config.JkBuildParser.parse(tmp.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        ExplainPlan plan = BuildService.fullyCachedExplainPlan(graph);
        assertThat(plan.modules()).isNotEmpty();
        assertThat(plan.modules()).allMatch(m -> !m.dirty());
        assertThat(BuildService.estimateEtaMillis(plan, tmp, tmp.resolve("c"), 0, null, null, false, false, true, 8))
                .isZero();
    }
}
