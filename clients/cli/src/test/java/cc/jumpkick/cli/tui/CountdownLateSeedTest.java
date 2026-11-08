// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The engine prices the ETA beside the build: a seed that lands after execute began counts from there. */
class CountdownLateSeedTest {

    @Test
    void a_seed_after_execute_began_is_anchored_at_execute_start() {
        Countdown c = new Countdown();
        c.executeStarted(1_000);
        c.lockSeed(); // unseeded: execute starting does not lock out the first seed
        assertThat(c.seed(10_000, 7_000)).isTrue();
        assertThat(c.r0SetAtElapsedMs()).isEqualTo(1_000);
        assertThat(c.etaEstimateMs()).as("execute start + R0").isEqualTo(11_000);
    }

    @Test
    void a_seed_before_execute_is_anchored_where_it_lands() {
        Countdown c = new Countdown();
        assertThat(c.seed(10_000, 2_000)).isTrue();
        c.executeStarted(3_000);
        assertThat(c.r0SetAtElapsedMs()).isEqualTo(2_000);
    }

    @Test
    void once_seeded_execute_locks_out_rewrites() {
        Countdown c = new Countdown();
        c.seed(10_000, 2_000);
        c.executeStarted(3_000);
        c.lockSeed();
        assertThat(c.seed(20_000, 5_000)).isFalse();
    }
}
