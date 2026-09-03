// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The history clamp is a bound only for history of this build's own kind.
 *
 * <p>{@code okHistory}'s fallback chain walks off the requested kind when the shaped bucket is thin
 * — to the bare project dir, then to the host — and the callers could not tell a shape-matched
 * sample from a fallback one. So a full-rebuild estimate got clamped against incremental history,
 * which is the exact pollution the {@code build} / {@code build:rebuild} split exists to prevent:
 * a schedule accurate to 2.8% (75.6 s simulated against 73.6 s actual) was capped to 27.6 s by a
 * 13.8 s incremental maximum, and the resulting −48% under-read looked like a modelling error
 * because a clamp leaves no trace in the output.
 */
class BuildEtaHistoryKindTest {

    /** Stats stores a TOTAL, not an average — avgMillis() divides. Build it from the average. */
    private static BuildMetrics.Stats stats(int count, long avg, long min, long max) {
        return new BuildMetrics.Stats(count, avg * count, min, max);
    }

    /** Incremental history: max 13.8 s, the shape that was capping full rebuilds at 27.6 s. */
    private static BuildMetrics.Stats incrementalHistory() {
        return stats(43, 12_504, 1_200, 13_823);
    }

    @Test
    void foreign_kind_history_does_not_bound_a_warm_schedule() {
        var foreign = new BuildEta.HistoryMatch(incrementalHistory(), false);
        assertThat(BuildEta.applyHistoryPrior(75_600, foreign))
                .as("a full-rebuild schedule must not be capped by incremental walls")
                .isEqualTo(75_600);
    }

    @Test
    void same_kind_history_still_bounds_an_absurd_over_estimate() {
        var own = new BuildEta.HistoryMatch(incrementalHistory(), true);
        assertThat(BuildEta.applyHistoryPrior(500_000, own))
                .as("the clamp is still wanted when the samples are this kind")
                .isEqualTo(2 * 13_823);
    }

    @Test
    void foreign_kind_history_is_still_a_prior_for_a_cold_seed() {
        var foreign = new BuildEta.HistoryMatch(incrementalHistory(), false);
        assertThat(BuildEta.applyHistoryPrior(0, foreign))
                .as("nothing modelled yet — a foreign average beats reporting zero")
                .isEqualTo(12_504);
    }

    @Test
    void no_history_leaves_the_schedule_alone() {
        assertThat(BuildEta.applyHistoryPrior(75_600, BuildEta.HistoryMatch.NONE))
                .isEqualTo(75_600);
        assertThat(BuildEta.applyHistoryPrior(75_600, (BuildEta.HistoryMatch) null))
                .isEqualTo(75_600);
    }
}
