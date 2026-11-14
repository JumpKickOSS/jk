// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime.progress;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HeaderProgressStrategyTest {

    @Test
    void clock_open_loop_without_residual() {
        var clock = new ClockProgressStrategy();
        // no residual (-1): elapsed/R0
        long[] d = clock.display(new HeaderProgressState(90, 100, 100_000, 0, 30_000, -1, false));
        assertThat(d[0]).isEqualTo(300);
        assertThat(d[1]).isEqualTo(1000);
    }

    @Test
    void clock_adaptive_with_residual_speeds_up_when_remaining_shrinks() {
        var clock = new ClockProgressStrategy();
        // 30s elapsed, residual still 70s → 30%
        long[] slow = clock.display(new HeaderProgressState(0, 0, 100_000, 0, 30_000, 70_000, false));
        assertThat(slow[0]).isEqualTo(300);
        // same elapsed, residual dropped to 10s → 75%
        long[] fast = clock.display(new HeaderProgressState(0, 0, 100_000, 0, 30_000, 10_000, false));
        assertThat(fast[0]).isEqualTo(750);
    }

    @Test
    void clock_never_goes_backwards_when_residual_grows() {
        var clock = new ClockProgressStrategy();
        clock.display(new HeaderProgressState(0, 0, 100_000, 0, 40_000, 20_000, false)); // 66.7%
        long[] back = clock.display(new HeaderProgressState(0, 0, 100_000, 0, 40_000, 80_000, false)); // would be 33%
        assertThat(back[0]).isGreaterThanOrEqualTo(667);
    }

    /**
     * A cold integration run's suite priced at 54 s ran 638 s: peak-held, the bar sat still for
     * minutes while the ETA grew (JK-ZJ8D9). Held, it moves at the pace the remainder sets, and never
     * reaches the cap while work is left.
     */
    @Test
    void clock_held_at_its_peak_still_advances_at_the_pace_the_remainder_sets() {
        var clock = new ClockProgressStrategy();
        clock.display(new HeaderProgressState(0, 0, 100_000, 0, 160_000, 30_000, false)); // 84.2%
        // 10 s later the remainder grew to 90 s: raw 170/260 = 65%, under the peak.
        long[] held = clock.display(new HeaderProgressState(0, 0, 100_000, 0, 170_000, 90_000, false));
        // peak + (cap - peak) * 10 / (170 + 90) = 0.842 + 0.148 * 10 / 260
        assertThat(held[0]).isEqualTo(848);
        // Then a quarter of its run left at every repaint, once a second: the bar keeps moving.
        long minuteAgo = held[0];
        for (long t = 171_000; t <= 600_000; t += 1_000) {
            long[] d = clock.display(new HeaderProgressState(0, 0, 100_000, 0, t, t / 4, false));
            assertThat(d[0]).as("at %d ms", t).isLessThan(990);
            if ((t - 170_000) % 60_000 == 0) {
                assertThat(d[0] - minuteAgo)
                        .as("a minute's progress to %d ms", t)
                        .isGreaterThanOrEqualTo(3);
                minuteAgo = d[0];
            }
        }
    }

    @Test
    void clock_held_at_its_peak_does_not_move_without_time_passing() {
        var clock = new ClockProgressStrategy();
        clock.display(new HeaderProgressState(0, 0, 100_000, 0, 40_000, 20_000, false)); // 66.7%
        long[] again = clock.display(new HeaderProgressState(0, 0, 100_000, 0, 40_000, 80_000, false));
        assertThat(again[0]).isEqualTo(667);
    }

    @Test
    void weighted_is_monotonic() {
        var w = new WeightedProgressStrategy();
        var st = new HeaderProgressState(0, 0, -1, 0, 0, -1, false);
        assertThat(w.onWeightProgress(st, 50, 100)[0]).isEqualTo(50);
        assertThat(w.onWeightProgress(st, 30, 100)[0]).isEqualTo(50);
    }

    @Test
    void weighted_denominator_growth_does_not_paint_backwards() {
        // Calibrate grows the denominator (preflight 100/1000 → 100/5100 ≈ 2%): the displayed
        // fraction must hold the 10% floor, not rebase down.
        var w = new WeightedProgressStrategy();
        var st = new HeaderProgressState(0, 0, -1, 0, 0, -1, false);
        assertThat(w.onWeightProgress(st, 100, 1000)[0]).isEqualTo(100);
        long[] grown = w.onWeightProgress(st, 110, 5100);
        assertThat((double) grown[0] / grown[1]).isGreaterThanOrEqualTo(0.099);
    }

    @Test
    void clock_takeover_carries_the_weighted_peak() {
        // AUTO switch: weighted climbs to 10% during preflight, then R0 seeds and clock takes
        // over near 0 elapsed — the shared peak keeps the paint monotonic.
        var peak = new SharedPeak();
        var w = new WeightedProgressStrategy(peak);
        var clock = new ClockProgressStrategy(peak);
        var pre = new HeaderProgressState(0, 0, -1, 0, 0, -1, false);
        w.onWeightProgress(pre, 100, 1000); // 10%
        long[] first = clock.display(new HeaderProgressState(0, 0, 100_000, 0, 500, -1, false)); // 0.5% raw
        assertThat((double) first[0] / first[1]).isGreaterThanOrEqualTo(0.10);
        // And clock still advances past the floor normally.
        long[] later = clock.display(new HeaderProgressState(0, 0, 100_000, 0, 30_000, -1, false));
        assertThat(later[0]).isEqualTo(300);
    }
}
