// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime.progress;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * While a build's last module runs, the clock bar counts the residual down between engine samples
 * as the countdown beside it does, down to a quarter of it: that module holds its residual for its
 * whole compile.
 */
class ResidualCountdownTest {

    private static HeaderProgressState at(long elapsedMs, long residualMs, long residualAtMs) {
        return new HeaderProgressState(0, 1000, 630, 476, elapsedMs, residualMs, false, residualAtMs, 1);
    }

    @Test
    void with_more_modules_pending_their_completions_resample_it_and_it_holds() {
        var two = new HeaderProgressState(0, 1000, 630, 476, 880, 630, false, 476, 2);
        assertThat(two.residualNowMs()).isEqualTo(630);
    }

    @Test
    void the_residual_counts_down_from_when_it_was_taken() {
        assertThat(at(476, 630, 476).residualNowMs()).isEqualTo(630);
        assertThat(at(880, 630, 476).residualNowMs()).isEqualTo(226);
        assertThat(at(5_000, 630, 476).residualNowMs())
                .as("an overrun step holds a quarter, so the bar keeps creeping instead of pinning")
                .isEqualTo(157);
        assertThat(at(880, -1, 476).residualNowMs()).isEqualTo(-1);
    }

    @Test
    void the_clock_bar_follows_the_countdown_through_a_one_step_module() {
        ClockProgressStrategy clock = new ClockProgressStrategy();
        long[] start = clock.display(at(476, 630, 476));
        long[] mid = clock.display(at(880, 630, 476));
        // 880 / (880 + 226) = 0.80, where the held residual read 880 / (880 + 630) = 0.58.
        assertThat(mid[0] / (double) mid[1]).isBetween(0.79, 0.81);
        assertThat(mid[0]).isGreaterThan(start[0]);
    }
}
