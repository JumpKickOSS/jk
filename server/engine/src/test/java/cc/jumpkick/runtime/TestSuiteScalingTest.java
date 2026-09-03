// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Percentage.withPercentage;

import org.junit.jupiter.api.Test;

/**
 * The two real {@code server/engine} observations behind {@link TestSuiteScaling} — 45.5 s on one
 * runner during a wide {@code --redo}, 17.4 s on 24 during a narrow incremental — have to normalize
 * to roughly the same stored cost, because they are the same suite. That agreement is the whole
 * reason the stored value can be averaged across build shapes.
 */
class TestSuiteScalingTest {

    private static final long WIDE_BUILD_WALL_MS = 45_554;
    private static final int WIDE_BUILD_RUNNERS = 1;
    private static final long NARROW_BUILD_WALL_MS = 17_384;
    private static final int NARROW_BUILD_RUNNERS = 24;

    @Test
    void the_same_suite_measured_at_1_and_at_24_runners_normalizes_to_one_cost() {
        long fromWide = TestSuiteScaling.normalize(WIDE_BUILD_WALL_MS, WIDE_BUILD_RUNNERS);
        long fromNarrow = TestSuiteScaling.normalize(NARROW_BUILD_WALL_MS, NARROW_BUILD_RUNNERS);

        assertThat(fromWide).isEqualTo(WIDE_BUILD_WALL_MS);
        assertThat(fromNarrow).isCloseTo(fromWide, withPercentage(15));
    }

    /** And back again: a stored cost has to reproduce the wall each shape actually measured. */
    @Test
    void a_stored_cost_reproduces_both_measured_walls() {
        long stored = TestSuiteScaling.normalize(WIDE_BUILD_WALL_MS, WIDE_BUILD_RUNNERS);

        assertThat(TestSuiteScaling.forRunners(stored, WIDE_BUILD_RUNNERS)).isEqualTo(WIDE_BUILD_WALL_MS);
        assertThat(TestSuiteScaling.forRunners(stored, NARROW_BUILD_RUNNERS))
                .as("stored cost must reproduce the 24-runner wall, not a linear 1-runner scale-up")
                .isCloseTo(NARROW_BUILD_WALL_MS, withPercentage(15));
    }

    @Test
    void normalize_and_for_runners_round_trip() {
        for (int runners : new int[] {1, 2, 4, 8, 12, 24, 64}) {
            long stored = TestSuiteScaling.normalize(30_000, runners);
            assertThat(TestSuiteScaling.forRunners(stored, runners))
                    .as("round trip at %d runners", runners)
                    .isCloseTo(30_000L, withPercentage(1));
        }
    }

    /** Sharding is sub-linear, so more runners must always help — but never linearly. */
    @Test
    void more_runners_help_sub_linearly() {
        long stored = 48_000;
        long at1 = TestSuiteScaling.forRunners(stored, 1);
        long at8 = TestSuiteScaling.forRunners(stored, 8);
        long at24 = TestSuiteScaling.forRunners(stored, 24);

        assertThat(at24).isLessThan(at8).isLessThan(at1);
        assertThat(at8).as("8 runners must not read as 8x faster").isGreaterThan(stored / 8);
        assertThat(at24).as("nor 24 as 24x").isGreaterThan(stored / 24);
    }

    @Test
    void degenerate_inputs_are_returned_unchanged() {
        assertThat(TestSuiteScaling.normalize(0, 8)).isZero();
        assertThat(TestSuiteScaling.normalize(-5, 8)).isZero();
        assertThat(TestSuiteScaling.normalize(1_000, 0)).isEqualTo(1_000);
        assertThat(TestSuiteScaling.normalize(1_000, -1)).isEqualTo(1_000);
        assertThat(TestSuiteScaling.forRunners(0, 8)).isZero();
        assertThat(TestSuiteScaling.forRunners(1_000, 0)).isEqualTo(1_000);
    }
}
