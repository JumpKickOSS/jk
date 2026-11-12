// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** A suite's wall read at another count of test JVMs, as its parallel bound moves. */
class StepCostTest {

    @Test
    void a_suite_without_class_walls_scales_by_its_workers_to_the_exponent() {
        assertThat(StepCost.scale(2, 4, 0, 0)).isCloseTo(Math.pow(0.5, StepCost.WORKER_EXPONENT), within(1e-9));
        assertThat(StepCost.scale(8, 2, 0, 0)).isCloseTo(Math.pow(4, StepCost.WORKER_EXPONENT), within(1e-9));
    }

    @Test
    void an_unknown_or_unchanged_count_does_not_scale() {
        assertThat(StepCost.scale(0, 4, 0, 0)).isEqualTo(1);
        assertThat(StepCost.scale(4, 0, 100, 10)).isEqualTo(1);
        assertThat(StepCost.scale(4, 4, 100, 10)).isEqualTo(1);
    }

    @Test
    void the_longest_class_bounds_what_more_workers_buy() {
        // 600 s of classes, the longest 60 s: 2 JVMs take 300 s, 8 would take 75 s, but no fewer
        // than the 60 s class.
        assertThat(StepCost.scale(2, 8, 600_000, 60_000))
                .isCloseTo(Math.pow(75.0 / 300, StepCost.WORKER_EXPONENT), within(1e-9));
        assertThat(StepCost.scale(8, 20, 600_000, 60_000))
                .isCloseTo(Math.pow(60.0 / 75, StepCost.WORKER_EXPONENT), within(1e-9));
        assertThat(StepCost.scale(2, 8, 100_000, 90_000))
                .as("one class is most of it")
                .isEqualTo(1);
    }
}
