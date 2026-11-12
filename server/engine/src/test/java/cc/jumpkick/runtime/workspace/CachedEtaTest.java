// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.runtime.EffortWeights;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A plan with nothing to rebuild must not be priced as a full build: {@link EffortWeights#costOf}
 * charged every step its full estimated weight regardless of the forecast sitting next to it, and a
 * workspace whose every module was reported "Fully Cached" advertised a full-build ETA.
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
        int partial =
                EffortWeights.costOf(A, Set.of(), plan, Set.of("package-jar")).weight();

        assertThat(partial).isGreaterThan(0).isLessThan(full);
    }

    @Test
    void a_cached_run_tests_step_drops_out_of_the_serial_test_bound() {
        var plan = planOf("compile-kotlin", "run-tests");

        assertThat(EffortWeights.costOf(A, Set.of(), plan).testWeight()).isGreaterThan(0);
        assertThat(EffortWeights.costOf(A, Set.of(), plan, Set.of("run-tests")).testWeight())
                .isZero();
    }

    /** A plan of no-op steps, each carrying a non-trivial estimated weight. */
    private static BuildPlan planOf(String... names) {
        BuildPlan.Builder b = BuildPlan.builder("test");
        for (String name : names) {
            b.addTask(Task.builder(name).weight(10).execute(ctx -> {}).build());
        }
        return b.build();
    }
}
