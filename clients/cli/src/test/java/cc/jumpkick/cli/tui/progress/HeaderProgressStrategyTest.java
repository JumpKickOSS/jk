// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.progress;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HeaderProgressStrategyTest {

    @Test
    void clock_tracks_elapsed_over_r0() {
        var clock = new ClockProgressStrategy();
        var st = new HeaderProgressState(90, 100, 100_000, 0, 30_000, false);
        long[] d = clock.display(st);
        assertThat(d[0]).isEqualTo(300);
        assertThat(d[1]).isEqualTo(1000);
        // Cap 99% while running
        long[] over = clock.display(new HeaderProgressState(90, 100, 100_000, 0, 200_000, false));
        assertThat(over[0]).isEqualTo(990);
        // Settle → 100%
        long[] done = clock.display(new HeaderProgressState(90, 100, 100_000, 0, 200_000, true));
        assertThat(done[0]).isEqualTo(1000);
    }

    @Test
    void weighted_is_monotonic() {
        var w = new WeightedProgressStrategy();
        var st = new HeaderProgressState(0, 0, -1, 0, 0, false);
        long[] a = w.onWeightProgress(st, 50, 100);
        assertThat(a[0]).isEqualTo(50);
        long[] b = w.onWeightProgress(st, 30, 100);
        assertThat(b[0]).isEqualTo(50); // peak hold
        long[] c = w.onWeightProgress(st, 70, 100);
        assertThat(c[0]).isEqualTo(70);
    }
}
