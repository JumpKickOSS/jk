// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The legacy runner nudge, for modules whose history predates {@code wall1-ms}. A stored suite wall
 * is one runner count's outcome, so it has to be re-scheduled before it can price a build with a
 * different one — and here the rescale has to be bounded, because both of its inputs are
 * recency-weighted means over heterogeneous runs and need not come from the same builds.
 *
 * <p>{@link TestSuiteScalingTest} covers the current path.
 */
class TestEffortRescaleTest {

    /** The real case: recorded on one runner during a redo, re-used for a sharded incremental. */
    @Test
    void a_single_runner_wall_shrinks_for_a_sharded_build() {
        assertThat(TestEffort.rescaleForRunners(40_000, 1, 8))
                .as("40s on one runner, now eight — bounded at a quarter rather than an eighth")
                .isEqualTo(10_000);
    }

    /**
     * The inverse, and the one that made a 19 s build read as four minutes: an earlier version
     * reconstructed "work" as wall x runners and floored the result at a quarter of it, so the floor
     * became a multiplier. A stretch is capped at 2x.
     */
    @Test
    void a_sharded_wall_stretches_for_a_serial_build_but_only_so_far() {
        assertThat(TestEffort.rescaleForRunners(12_000, 8, 1))
                .as("the naive product would be 96s; the cap is 2x the recorded wall")
                .isEqualTo(24_000);
    }

    @Test
    void a_matching_runner_count_is_left_alone() {
        assertThat(TestEffort.rescaleForRunners(40_000, 8, 8)).isEqualTo(40_000);
    }

    /** No recorded concurrency — return the wall rather than invent a ratio for it. */
    @Test
    void an_unrecorded_runner_count_changes_nothing() {
        assertThat(TestEffort.rescaleForRunners(40_000, 0, 8)).isEqualTo(40_000);
        assertThat(TestEffort.rescaleForRunners(40_000, -1, 8)).isEqualTo(40_000);
    }

    @Test
    void the_bounds_hold_for_extreme_ratios() {
        assertThat(TestEffort.rescaleForRunners(40_000, 1, 1000)).isEqualTo(10_000);
        assertThat(TestEffort.rescaleForRunners(40_000, 1000, 1)).isEqualTo(80_000);
        assertThat(TestEffort.rescaleForRunners(0, 1, 8)).isZero();
    }
}
