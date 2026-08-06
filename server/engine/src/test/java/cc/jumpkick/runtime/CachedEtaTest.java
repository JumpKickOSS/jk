// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * a plan with nothing to rebuild must not be priced as a full build.
 *
 * <p>Two things conspired. {@link EffortWeights#costOf} charged every step its full estimated
 * weight regardless of the forecast sitting next to it, and {@link BuildService#applyHistoryPrior}
 * treats {@code base == 0} as "no estimate available" and substitutes this project's average
 * historical build. Together a workspace whose every module was reported "Fully Cached" advertised
 * a full-build ETA — ~3s for a 2ms no-op.
 */
class CachedEtaTest {

    private static final Path A = Path.of("/ws/a");

    @Test
    void a_cached_step_costs_nothing() {
        var plan = planOf("compile-kotlin", "package-jar");

        int full = EffortWeights.costOf(A, Set.of(), plan).weight();
        int cached = EffortWeights.costOf(A, Set.of(), plan, Set.of("compile-kotlin", "package-jar"))
                .weight();

        assertThat(full).isGreaterThan(0);
        assertThat(cached).isZero();
    }

    @Test
    void an_uncached_step_still_costs_its_weight() {
        var plan = planOf("compile-kotlin", "package-jar");

        int full = EffortWeights.costOf(A, Set.of(), plan).weight();
        int partial = EffortWeights.costOf(A, Set.of(), plan, Set.of("package-jar"))
                .weight();

        assertThat(partial).isGreaterThan(0).isLessThan(full);
    }

    @Test
    void a_cached_run_tests_step_drops_out_of_the_serial_test_bound() {
        var plan = planOf("compile-kotlin", "run-tests");

        assertThat(EffortWeights.costOf(A, Set.of(), plan).testWeight()).isGreaterThan(0);
        assertThat(EffortWeights.costOf(A, Set.of(), plan, Set.of("run-tests"))
                        .testWeight())
                .isZero();
    }

    @Test
    void the_history_prior_no_longer_hijacks_a_zero_estimate() {
        // Guard the mechanism the fix routes around: with real history, base == 0 is replaced by
        // the historical average. estimateEtaMillis must therefore not reach here when nothing is
        // dirty — that is exactly what produced "Fully Cached … estimate ~3s".
        // Stats(count, totalMillis, minMillis, maxMillis) -> avg 3s over 5 runs.
        BuildMetrics.Stats history = new BuildMetrics.Stats(5, 15_000, 2_000, 4_000);
        assertThat(BuildService.applyHistoryPrior(0, history)).isEqualTo(3_000);

        // A real (non-zero) estimate is left alone.
        assertThat(BuildService.applyHistoryPrior(1_500, history)).isEqualTo(1_500);
    }

    /** A plan of no-op steps, each carrying a non-trivial estimated weight. */
    private static cc.jumpkick.run.BuildPlan planOf(String... names) {
        cc.jumpkick.run.BuildPlan.Builder b = cc.jumpkick.run.BuildPlan.builder("test");
        for (String name : names) {
            b.addTask(cc.jumpkick.run.Task.builder(name)
                    .weight(10)
                    .execute(ctx -> {})
                    .build());
        }
        return b.build();
    }
}
