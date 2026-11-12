// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * A suite's class share runs ahead of its clock, its last classes running alone: its curve, read
 * from its last run, turns the share into the share of its wall that has passed.
 */
class SuiteCurveTest {

    @Test
    void a_curve_is_the_time_share_at_each_decile_of_the_class_share() {
        List<double[]> points = new ArrayList<>();
        // Class share runs ahead: 84% of the class walls at 71% of the wall, the rest alone.
        for (int i = 1; i <= 100; i++) {
            double t = i / 100.0;
            double share = t <= 0.71 ? t * 0.84 / 0.71 : 0.84 + (t - 0.71) * 0.16 / 0.29;
            points.add(new double[] {share, t});
        }
        int[] curve = Objects.requireNonNull(SuiteCurve.of(points));

        assertThat(curve).hasSize(SuiteCurve.POINTS);
        assertThat(curve[7])
                .as("80% of the class walls: well before 80% of the wall")
                .isLessThan(700);
        assertThat(curve[8]).as("90%: deep into the alone tail").isGreaterThan(800);
        assertThat(SuiteCurve.timeShare(curve, 0.84))
                .as("linear between deciles")
                .isCloseTo(0.71, within(0.03));
        assertThat(SuiteCurve.timeShare(curve, 0)).isZero();
        assertThat(SuiteCurve.timeShare(curve, 1)).isEqualTo(1.0);
    }

    @Test
    void no_curve_reads_the_share_as_it_is_and_a_suite_that_never_reached_a_decile_has_none() {
        assertThat(SuiteCurve.timeShare(null, 0.4)).isEqualTo(0.4);
        assertThat(SuiteCurve.of(List.of(new double[] {0.5, 0.5}))).isNull();
    }
}
