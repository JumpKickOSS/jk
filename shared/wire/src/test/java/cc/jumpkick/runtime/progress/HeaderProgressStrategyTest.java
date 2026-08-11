// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.progress;

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

    @Test
    void weighted_is_monotonic() {
        var w = new WeightedProgressStrategy();
        var st = new HeaderProgressState(0, 0, -1, 0, 0, -1, false);
        assertThat(w.onWeightProgress(st, 50, 100)[0]).isEqualTo(50);
        assertThat(w.onWeightProgress(st, 30, 100)[0]).isEqualTo(50);
    }
}
