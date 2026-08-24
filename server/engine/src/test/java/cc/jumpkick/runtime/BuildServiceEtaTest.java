// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The whole-build history prior: a sanity anchor for the seeded ETA, one-sided by design. */
class BuildServiceEtaTest {

    private static BuildMetrics.Stats ok(long count, long avg, long min, long max) {
        return new BuildMetrics.Stats(count, count * avg, min, max);
    }

    @Test
    void open_loop_prefers_one_percent_over_estimate() {
        assertThat(BuildService.preferSlightOverEstimate(0)).isZero();
        assertThat(BuildService.preferSlightOverEstimate(68_000)).isEqualTo(68_680);
        assertThat(BuildService.preferSlightOverEstimate(100_000)).isEqualTo(101_000);
        // ~1%, not ~2.5%
        assertThat(BuildService.preferSlightOverEstimate(70_000)).isLessThan(70_000 + 1_750);
    }

    @Test
    void no_history_leaves_the_base_untouched() {
        assertThat(BuildService.applyHistoryPrior(0, null)).isZero();
        assertThat(BuildService.applyHistoryPrior(0, BuildMetrics.Stats.EMPTY)).isZero();
        assertThat(BuildService.applyHistoryPrior(4200, BuildMetrics.Stats.EMPTY))
                .isEqualTo(4200);
    }

    @Test
    void count_up_becomes_the_historical_average_when_the_project_has_history() {
        // Cold module + uncalibratable host used to mean "count up" (0) — history beats that.
        assertThat(BuildService.applyHistoryPrior(0, ok(5, 2000, 800, 6000))).isEqualTo(2000);
    }

    @Test
    void absurd_over_estimates_clamp_down_to_twice_the_historical_max() {
        assertThat(BuildService.applyHistoryPrior(60_000, ok(5, 2000, 800, 6000)))
                .isEqualTo(12_000);
    }

    @Test
    void the_clamp_is_one_sided_and_needs_a_settled_history() {
        // Incremental runs legitimately beat the historical average — never clamp UP.
        assertThat(BuildService.applyHistoryPrior(500, ok(5, 2000, 800, 6000))).isEqualTo(500);
        // Within 2× max → trusted as-is.
        assertThat(BuildService.applyHistoryPrior(9000, ok(5, 2000, 800, 6000))).isEqualTo(9000);
        // Fewer than 3 successful builds is too thin to clamp against.
        assertThat(BuildService.applyHistoryPrior(60_000, ok(2, 2000, 800, 6000)))
                .isEqualTo(60_000);
    }

    @Test
    void host_history_fills_count_up_when_project_path_is_unknown() {
        // applyHistoryPrior with host-tier stats must turn base=0 into a countdown seed.
        BuildMetrics.Stats host = ok(10, 4500, 1000, 12_000);
        assertThat(BuildService.applyHistoryPrior(0, host)).isEqualTo(4500);
        assertThat(BuildService.applyHistoryPrior(3000, host)).isEqualTo(3000);
    }

    @Test
    void history_shape_keys_separate_rebuild_from_incremental() {
        var inc = new BuildService.HistoryShape(false, 4);
        var reb = new BuildService.HistoryShape(true, 200);
        assertThat(inc.kind()).isEqualTo("build");
        assertThat(reb.kind()).isEqualTo("build:rebuild");
        assertThat(inc.dirKey(Path.of("/ws"))).isEqualTo("/ws#d4");
        assertThat(reb.dirKey(Path.of("/ws"))).isEqualTo("/ws#d200");
        assertThat(new BuildService.HistoryShape(false, -1).dirKey(Path.of("/ws")))
                .isEqualTo("/ws");
    }

    @Test
    void history_prior_never_pulls_step_sum_toward_whole_build_average() {
        // ETA is Σ dirty step walls — whole-build history must not inflate a partial schedule.
        assertThat(BuildService.applyHistoryPrior(20_000, ok(3, 150_000, 140_000, 160_000), true))
                .isEqualTo(20_000);
        assertThat(BuildService.applyHistoryPrior(20_000, ok(3, 150_000, 140_000, 160_000), false, 27))
                .isEqualTo(20_000);
        // Cold seed (base=0) may still use history when nothing is modeled yet.
        assertThat(BuildService.applyHistoryPrior(0, ok(3, 150_000, 140_000, 160_000), true))
                .isEqualTo(150_000);
        // One-sided clamp still applies for absurd over-estimates with settled history.
        assertThat(BuildService.applyHistoryPrior(60_000, ok(5, 2000, 800, 6000), false))
                .isEqualTo(12_000);
    }

    @Test
    void cascade_and_resource_drift_are_not_local_compile_content() {
        var cascade = new TaskForecast.Module(
                Path.of("/lib"),
                "g:lib",
                List.of(
                        new TaskForecast.Task(
                                "compile-main", TaskForecast.Status.RUN, "recompile · dependency changed", null),
                        new TaskForecast.Task(
                                "compile-test", TaskForecast.Status.RUN, "recompile · main changed", null),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "run tests · ~100 tests", null),
                        new TaskForecast.Task(
                                "package-jar", TaskForecast.Status.RUN, "repackage · compile changed", null)),
                10,
                100,
                true,
                false);
        assertThat(BuildService.hasLocalCompileContent(cascade)).isFalse();
        assertThat(BuildService.hasResourceDriftWork(cascade)).isFalse();
        assertThat(BuildService.hasHeavyPackagingTail(cascade)).isFalse();
        assertThat(BuildService.isCascadeForcedStep(cascade.steps().get(0))).isTrue();

        var resourceOnly = new TaskForecast.Module(
                Path.of("/core"),
                "g:core",
                List.of(
                        new TaskForecast.Task("compile-main", TaskForecast.Status.CACHED, "", "k"),
                        new TaskForecast.Task(
                                "compile-test", TaskForecast.Status.PARTIAL, "compile · 0 sources changed", null),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "run tests · ~792 tests", null),
                        new TaskForecast.Task(
                                "package-jar", TaskForecast.Status.RUN, "repackage · resources changed", null),
                        new TaskForecast.Task(
                                "copy-resources", TaskForecast.Status.RUN, "extra resources changed", null)),
                50,
                792,
                true,
                false);
        assertThat(BuildService.hasLocalCompileContent(resourceOnly)).isFalse();
        assertThat(BuildService.hasResourceDriftWork(resourceOnly)).isTrue();

        // a test-resource edit reruns the suite for real (test action keys hash test
        // resources), so run-tests must never be discounted for it — main-resource drift keeps
        // its dogfood-validated discount.
        var testResourceOnly = new TaskForecast.Module(
                Path.of("/core"),
                "g:core",
                List.of(
                        new TaskForecast.Task(
                                "copy-test-resources", TaskForecast.Status.RUN, "test resources changed", null),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "run tests · ~792 tests", null)),
                100,
                792,
                true,
                false);
        assertThat(BuildService.hasTestResourceDriftWork(testResourceOnly)).isTrue();
        var suite = testResourceOnly.steps().get(1);
        assertThat(BuildService.shouldDiscountCascadeStep(suite, false, true, true, true))
                .isFalse();
        // Rule 4 (pure cascade) is also vetoed by test-resource drift.
        assertThat(BuildService.shouldDiscountCascadeStep(suite, false, true, false, true))
                .isFalse();
        // Main-resource-only drift still discounts the suite (no test-resource signal).
        assertThat(BuildService.shouldDiscountCascadeStep(suite, false, true, true, false))
                .isTrue();

        var local = new TaskForecast.Module(
                Path.of("/engine"),
                "g:engine",
                List.of(
                        new TaskForecast.Task(
                                "compile-main", TaskForecast.Status.PARTIAL, "compile · 3 sources changed", null),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "run tests · ~1000 tests", null)),
                100,
                1000,
                true,
                false);
        assertThat(BuildService.hasLocalCompileContent(local)).isTrue();

        // counts ending in 0 contain the substring "0 source" — a naive contains()
        // treated a 10/20/100-source edit as zero-source and discounted the whole suite.
        var tenSources = new TaskForecast.Module(
                Path.of("/engine"),
                "g:engine",
                List.of(
                        new TaskForecast.Task(
                                "compile-main", TaskForecast.Status.PARTIAL, "compile · 10 sources changed", null),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "run tests · ~1000 tests", null)),
                100,
                1000,
                true,
                false);
        assertThat(BuildService.hasLocalCompileContent(tenSources)).isTrue();

        var cliShaped = new TaskForecast.Module(
                Path.of("/cli"),
                "g:cli",
                List.of(
                        new TaskForecast.Task(
                                "compile-main", TaskForecast.Status.RUN, "recompile · dependency changed", null),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "run tests · ~1000 tests", null),
                        new TaskForecast.Task(
                                "native-image", TaskForecast.Status.RUN, "rebuild · compile changed", null)),
                50,
                1000,
                true,
                false);
        assertThat(BuildService.hasLocalCompileContent(cliShaped)).isFalse();
        assertThat(BuildService.hasHeavyPackagingTail(cliShaped)).isTrue();
    }

    @Test
    void eta_discounts_cascade_and_resource_suites_keeps_cli_tests_not_native() throws Exception {
        Path core = Path.of("/core");
        Path a = Path.of("/a");
        Path cli = Path.of("/cli");
        Path eng = Path.of("/eng");
        var coreMod = new TaskForecast.Module(
                core,
                "g:core",
                List.of(
                        new TaskForecast.Task("compile-main", TaskForecast.Status.CACHED, "", "k"),
                        new TaskForecast.Task(
                                "compile-test", TaskForecast.Status.PARTIAL, "compile · 0 sources changed", null),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "run tests · ~792 tests", null),
                        new TaskForecast.Task(
                                "package-jar", TaskForecast.Status.RUN, "repackage · resources changed", null),
                        new TaskForecast.Task(
                                "copy-resources", TaskForecast.Status.RUN, "extra resources changed", null)),
                50,
                792,
                true,
                false);
        var cascadeMod = new TaskForecast.Module(
                a,
                "g:a",
                List.of(
                        new TaskForecast.Task(
                                "compile-main", TaskForecast.Status.RUN, "recompile · dependency changed", null),
                        new TaskForecast.Task(
                                "compile-test", TaskForecast.Status.RUN, "recompile · main changed", null),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "run tests · ~188 tests", null),
                        new TaskForecast.Task(
                                "package-jar", TaskForecast.Status.RUN, "repackage · compile changed", null)),
                20,
                188,
                true,
                false);
        var engineMod = new TaskForecast.Module(
                eng,
                "g:eng",
                List.of(
                        new TaskForecast.Task(
                                "compile-main", TaskForecast.Status.PARTIAL, "compile · 1 source changed", null),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "run tests · ~1000 tests", null),
                        new TaskForecast.Task(
                                "package-jar", TaskForecast.Status.RUN, "repackage · compile changed", null),
                        new TaskForecast.Task(
                                "package-assembly", TaskForecast.Status.RUN, "repackage · compile changed", null)),
                200,
                1000,
                true,
                false);
        var cliMod = new TaskForecast.Module(
                cli,
                "g:cli",
                List.of(
                        new TaskForecast.Task(
                                "compile-main", TaskForecast.Status.RUN, "recompile · dependency changed", null),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "run tests · ~1098 tests", null),
                        new TaskForecast.Task(
                                "package-jar", TaskForecast.Status.RUN, "repackage · compile changed", null),
                        new TaskForecast.Task(
                                "native-image", TaskForecast.Status.RUN, "rebuild · compile changed", null)),
                100,
                1098,
                true,
                false);
        var plan = new ExplainPlan(
                List.of(coreMod, cascadeMod, engineMod, cliMod),
                Map.of(core, Set.of(), a, Set.of(core), eng, Set.of(a), cli, Set.of(eng)),
                4,
                List.of());
        List<EffortWeights.ModuleCost> costs = SessionContext.where(
                Session.defaults(),
                () -> BuildService.etaCostsFromExplainPlan(
                        plan, Path.of("/tmp/jk-eta-cascade-test-cache"), 1, null, null, false, false));
        EffortWeights.ModuleCost coreCost =
                costs.stream().filter(c -> c.dir().equals(core)).findFirst().orElseThrow();
        EffortWeights.ModuleCost cascadeCost =
                costs.stream().filter(c -> c.dir().equals(a)).findFirst().orElseThrow();
        EffortWeights.ModuleCost engineCost =
                costs.stream().filter(c -> c.dir().equals(eng)).findFirst().orElseThrow();
        EffortWeights.ModuleCost cliCost =
                costs.stream().filter(c -> c.dir().equals(cli)).findFirst().orElseThrow();
        // Core resource drift: package/copy only — not 792-test suite.
        assertThat(coreCost.testWeight()).isZero();
        assertThat(coreCost.weight()).isLessThan(50);
        // Pure cascade: only recheck tokens.
        assertThat(cascadeCost.weight()).isLessThan(20);
        assertThat(cascadeCost.testWeight()).isZero();
        // Engine local compile keeps substantial weight.
        assertThat(engineCost.weight()).isGreaterThan(cascadeCost.weight() * 5);
        assertThat(engineCost.testWeight()).isGreaterThan(0);
        // Cli: full tests (heavy-tail signal), native cascade-discounted (not full ~30s wall).
        assertThat(cliCost.testWeight()).isGreaterThan(0);
        // Without native wall, cli weight is dominated by tests — close to testWeight.
        assertThat(cliCost.weight()).isLessThan(cliCost.testWeight() + 30);
    }

    @Test
    void full_work_shape_needs_depth_not_just_width() {
        // 28 lightly dirty modules (cascade / parse-heavy) must NOT look like a full rebuild.
        var wideShallow = new BuildService.HistoryShape(false, 28);
        List<EffortWeights.ModuleCost> shallow = new ArrayList<>();
        for (int i = 0; i < 28; i++) {
            // weight 5 ≪ 5s threshold — token/bookkeeping class
            shallow.add(EffortWeights.costOf(Path.of("/m" + i), Set.of(), 5, 0));
        }
        // Two heavy modules (engine tests + cli tests+native) — still not "full work."
        List<EffortWeights.ModuleCost> twoHeavy = new ArrayList<>(shallow.subList(0, 26));
        twoHeavy.add(EffortWeights.costOf(Path.of("/engine"), Set.of(), 400, 350));
        twoHeavy.add(EffortWeights.costOf(Path.of("/cli"), Set.of(), 350, 200));
        assertThat(BuildService.isFullWorkShape(wideShallow, shallow)).isFalse();
        assertThat(BuildService.isFullWorkShape(wideShallow, twoHeavy)).isFalse();

        // Explicit rebuild always floors.
        assertThat(BuildService.isFullWorkShape(new BuildService.HistoryShape(true, 2), twoHeavy))
                .isTrue();

        // Wide + many substantial modules → full work (true monorepo suite).
        List<EffortWeights.ModuleCost> deep = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            deep.add(EffortWeights.costOf(Path.of("/m" + i), Set.of(), 80, 60));
        }
        assertThat(BuildService.isFullWorkShape(wideShallow, deep)).isTrue();
    }

    @Test
    void cancelled_invocation_stats_do_not_seed_eta_priors(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        // Full success then a short cancelled (Ctrl-C) wall — okAcrossShapes / applyHistoryPrior
        // must keep the full-build average, not blend the truncated cancel.
        Path metrics = dir.resolve("metrics.json");
        BuildMetrics.record(
                metrics, new BuildMetrics.Outcome("build", "/proj#d1", "g:n", true, false, 12_000, List.of()), 1_000L);
        BuildMetrics.record(
                metrics, new BuildMetrics.Outcome("build", "/proj#d1", "g:n", false, true, 350, List.of()), 2_000L);
        BuildMetrics m = BuildMetrics.load(metrics);
        BuildMetrics.Stats okOnly = m.okAcrossShapes("build", "/proj");
        assertThat(okOnly.count()).isEqualTo(1);
        assertThat(okOnly.avgMillis()).isEqualTo(12_000);
        // History prior for a cold schedule (base=0) uses the ok average, not the cancel wall.
        assertThat(BuildService.applyHistoryPrior(0, okOnly)).isEqualTo(12_000);
        assertThat(BuildService.applyHistoryPrior(15_000, okOnly)).isEqualTo(15_000);
    }
}
