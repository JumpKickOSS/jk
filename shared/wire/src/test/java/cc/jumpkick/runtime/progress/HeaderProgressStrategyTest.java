// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.progress;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HeaderProgressStrategyTest {

    @Test
    void clock_and_weighted_basics() {
        var clock = new ClockProgressStrategy();
        long[] d = clock.display(new HeaderProgressState(90, 100, 100_000, 0, 30_000, false));
        assertThat(d[0]).isEqualTo(300);
        assertThat(d[1]).isEqualTo(1000);

        var w = new WeightedProgressStrategy();
        var st = new HeaderProgressState(0, 0, -1, 0, 0, false);
        assertThat(w.onWeightProgress(st, 50, 100)[0]).isEqualTo(50);
        assertThat(w.onWeightProgress(st, 30, 100)[0]).isEqualTo(50);
    }
}
